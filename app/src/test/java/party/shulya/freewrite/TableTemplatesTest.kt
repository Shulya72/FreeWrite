package party.shulya.freewrite

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.widget.EditText
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Шаблоны таблиц команды и виды «только для этой доски» (0.32.2, Владимир: «сохраняю — спрашивает: только для этой доски или
 * как шаблон; шаблон уходит на сервер и есть при создании новой доски, а только для доски — база шаблонов не забивается»).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TableTemplatesTest {

    private val ctx get() = RuntimeEnvironment.getApplication()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private val file get() = File(ctx.filesDir, "table_templates.json")

    @Before
    fun setUp() {
        Input.markStylus(ctx)
        Profile.save(ctx, "Владимир", "Шульмин")
        ctx.getSharedPreferences("igs", android.content.Context.MODE_PRIVATE).edit().clear().apply()
        file.delete()
        RemoteSync.testTransport = null
    }

    @After
    fun tearDown() { RemoteSync.testTransport = null }

    private fun tpl(name: String, section: String = "Сети") = TableTemplate(TableKinds.newUserId(), name, section,
        TableTemplate.defaultCols(), mutableListOf("Монтаж трубопровода"), mutableListOf())

    // ------------------------------------------------------------ общий список на сервере

    @Test
    fun templateGoesToServerAndToOtherDevices() {
        val srv = FakeServer()
        val lib = TableTemplates(ctx)
        val t = tpl("Наружные сети К1")
        lib.save(t)
        assertTrue("пока не отправлен", lib.entries().single().pending)
        assertFalse(TemplateSync.syncNow(ctx, srv))
        assertFalse(lib.entries().single().pending)
        assertEquals("Владимир Шульмин", srv.templates[t.id]!!.author)
        assertEquals("Наружные сети К1", TableTemplate.parse(JSONObject(srv.templates[t.id]!!.body))!!.name)

        // Другое устройство (свой планшет пустой): после обмена шаблон есть.
        file.delete()
        assertTrue(lib.list().isEmpty())
        assertTrue(TemplateSync.syncNow(ctx, srv))
        assertEquals("Наружные сети К1", lib.list().single().name)

        // Коллега поправил позже — у нас поправится.
        val fixed = t.copy().apply { name = "Наружные сети К2" }
        val later = BoardSync.rev(System.currentTimeMillis() + 60_000, 0, "kolya1")
        srv.templates[t.id] = FakeServer.Tpl(t.id, later, fixed.json(), false, "Коля")
        assertTrue(TemplateSync.syncNow(ctx, srv))
        assertEquals("Наружные сети К2", lib.get(t.id)!!.name)

        // Коллега убрал шаблон — у нас его тоже нет.
        srv.templates[t.id] = FakeServer.Tpl(t.id, BoardSync.rev(System.currentTimeMillis() + 120_000, 0, "kolya1"), "", true, "Коля")
        assertTrue(TemplateSync.syncNow(ctx, srv))
        assertTrue(lib.list().isEmpty())

        // Без сети: сохранили — лежит неотправленным, связь появилась — ушёл.
        srv.offline = true
        val t2 = tpl("Обратная засыпка", "Земляные работы")
        lib.save(t2)
        assertTrue(runCatching { TemplateSync.syncNow(ctx, srv) }.isFailure)
        assertTrue(lib.entries().first { it.id == t2.id }.pending)
        assertEquals("Обратная засыпка", lib.get(t2.id)!!.name)
        srv.offline = false
        TemplateSync.syncNow(ctx, srv)
        assertFalse(lib.entries().first { it.id == t2.id }.pending)
        assertNotNull(srv.templates[t2.id])

        // Убрали у себя — на сервере отметка удаления (дойдёт до всех).
        lib.delete(t2.id)
        TemplateSync.syncNow(ctx, srv)
        assertTrue(srv.templates[t2.id]!!.deleted)
        assertNull(lib.get(t2.id))
    }

    @Test
    fun oldLocalTemplatesBecomeTeamTemplates() {
        // Файл до 0.32.2 — просто список шаблонов этого планшета.
        val a = tpl("Сети К1")
        val b = tpl("Обратная засыпка", "Земляные работы")
        file.writeText(JSONArray().put(JSONObject(a.json())).put(JSONObject(b.json())).toString())
        val lib = TableTemplates(ctx)
        assertEquals(listOf("Сети К1", "Обратная засыпка"), lib.list().map { it.name })
        assertTrue(lib.entries().all { it.pending })
        val srv = FakeServer()
        TemplateSync.syncNow(ctx, srv)
        assertEquals(setOf(a.id, b.id), srv.templates.keys)
        assertTrue(lib.entries().none { it.pending })
    }

    // ------------------------------------------------------------ виды «только для этой доски»

    @Test
    fun boardKindsTravelWithTheBoard() {
        val b = Board.create("Сети")
        val t = tpl("Ливнёвка К2")
        b.kinds += BoardKind(BoardKind.idFor(t.id), t)
        assertFalse(":" in b.kinds[0].id) // номер предмета без двоеточия — сервер такие принимает
        // Файл доски.
        val back = Board.fromJson(JSONObject(b.toJsonString()))
        assertEquals("Ливнёвка К2", back.kinds.single().tpl.name)
        assertEquals(t.id, back.kinds.single().tpl.id)
        assertEquals(BoardKind.json(b.kinds[0]), BoardKind.json(back.kinds[0]))
        // «Отменить».
        val snap = b.snapshot()
        b.kinds.clear()
        assertEquals(1, b.restored(snap).kinds.size)
        b.kinds += BoardKind(BoardKind.idFor(t.id), t)
        // Общая доска: вид «tkind» среди правок; у коллеги — тот же вид.
        b.toJsonString(stampDevice = "dev1")
        val ch = Remote.pending(b).first { it.kind == "tkind" }
        val other = Board.create("Сети")
        Remote.apply(other, listOf(Remote.Row(ch.id, ch.rev, ch.kind, ch.data, 1)))
        assertEquals("Ливнёвка К2", other.kinds.single().tpl.name)
        // Обмен файлом.
        val viaFile = Board.fromJson(JSONObject(Board.create("Сети").toJsonString()))
        BoardShare.merge(viaFile, Board.fromJson(JSONObject(b.toJsonString())))
        assertEquals(1, viaFile.kinds.size)
    }

    // ------------------------------------------------------------ на экране

    private class Screen(val a: BoardActivity) { val b get() = a.board() }

    private fun open(b: Board): Screen {
        BoardStore(ctx).save(b)
        val a = Robolectric.buildActivity(BoardActivity::class.java, Intent(ctx, BoardActivity::class.java).putExtra(BoardActivity.EXTRA_ID, b.id)).setup().get()
        idle()
        return Screen(a)
    }

    private fun shot(d: android.app.Dialog, name: String) {
        val v = d.window!!.decorView
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        File("build/screenshots").mkdirs()
        File("build/screenshots/$name").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Открыть редактор новой таблицы, вписать название и раздел, нажать «Сохранить». */
    private fun newKind(s: Screen, name: String, section: String): TableEditor {
        s.a.tableUi.chooseKind(200f to 200f); idle()
        s.a.tableUi.explorer!!.window!!.decorView.findViewWithTag<View>("kind_new").performClick(); idle()
        val ed = TableEditor.last!!
        val root = ed.dialog!!.window!!.decorView
        root.findViewWithTag<EditText>("tpl_name").setText(name)
        root.findViewWithTag<EditText>("tpl_section").setText(section)
        ed.dialog!!.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        return ed
    }

    private fun explorerHas(s: Screen, kindId: String): Boolean {
        s.a.tableUi.chooseKind(200f to 200f); idle()
        val er = s.a.tableUi.explorer!!.window!!.decorView
        er.findViewWithTag<EditText>("kind_search").setText("сет"); idle()
        val has = er.findViewWithTag<View>("kind_$kindId") != null
        s.a.tableUi.explorer!!.dismiss(); idle()
        return has
    }

    @Test
    fun saveAsksWhereAndBoardOnlyStaysOnThisBoard() {
        val srv = FakeServer()
        RemoteSync.testTransport = srv
        val s = open(Board.create("Объект 1"))
        // «Только для этой доски»: в доске, в общем списке — нет.
        val ed = newKind(s, "Ливнёвка К2", "Сети")
        val sd = ed.scopeDialog!!
        assertTrue(sd.isShowing)
        shot(sd, "table-template-scope.png")
        sd.window!!.decorView.findViewWithTag<View>("tpl_scope_board").performClick(); idle()
        assertFalse(ed.dialog?.isShowing == true)
        val kind = s.b.kinds.single().tpl
        assertEquals("Ливнёвка К2", kind.name)
        assertTrue(TableTemplates(ctx).list().isEmpty())
        // В выборе таблиц этой доски — есть, с пометкой «только эта доска».
        s.a.tableUi.chooseKind(200f to 200f); idle()
        val er = s.a.tableUi.explorer!!.window!!.decorView
        er.findViewWithTag<EditText>("kind_search").setText("сет"); idle()
        assertNotNull(er.findViewWithTag<View>("kind_board_${kind.id}"))
        shot(s.a.tableUi.explorer!!, "table-template-explorer.png")
        s.a.tableUi.explorer!!.dismiss(); idle()
        // «Отменить» убирает.
        s.a.undo(); idle()
        assertTrue(s.a.board().kinds.isEmpty())
        s.a.redo(); idle()
        assertEquals(1, s.a.board().kinds.size)

        // «Сохранить как шаблон»: в общем списке, ушёл на сервер.
        val ed2 = newKind(s, "Наружные сети К1", "Сети")
        ed2.scopeDialog!!.window!!.decorView.findViewWithTag<View>("tpl_scope_lib").performClick(); idle()
        val team = TableTemplates(ctx).list().single()
        assertEquals("Наружные сети К1", team.name)
        TemplateSync.syncNow(ctx, srv)
        assertNotNull(srv.templates[team.id])
        s.a.finish(); idle()

        // Новая доска: шаблон команды — в выборе таблиц, вид первой доски — нет.
        val s2 = open(Board.create("Объект 2"))
        assertTrue(explorerHas(s2, team.id))
        assertFalse(explorerHas(s2, kind.id))
        s2.a.finish(); idle()

        // Вид первой доски можно сделать шаблоном: правка → «Сохранить» → «как шаблон».
        val s3 = open(s.b)
        s3.a.tableUi.chooseKind(200f to 200f); idle()
        val er3 = s3.a.tableUi.explorer!!.window!!.decorView
        er3.findViewWithTag<EditText>("kind_search").setText("сет"); idle()
        er3.findViewWithTag<View>("kind_edit_${kind.id}").performClick(); idle()
        val ed3 = TableEditor.last!!
        assertEquals("Только для этой доски", (ed3.dialog!!.window!!.decorView.findViewWithTag<View>("tpl_where") as android.widget.TextView).text.toString())
        ed3.dialog!!.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        ed3.scopeDialog!!.window!!.decorView.findViewWithTag<View>("tpl_scope_lib").performClick(); idle()
        assertTrue(s3.a.board().kinds.isEmpty())
        assertEquals(setOf("Наружные сети К1", "Ливнёвка К2"), TableTemplates(ctx).list().map { it.name }.toSet())
        // Шаблон правится без вопроса «куда».
        s3.a.tableUi.chooseKind(200f to 200f); idle()
        val er4 = s3.a.tableUi.explorer!!.window!!.decorView
        er4.findViewWithTag<EditText>("kind_search").setText("сет"); idle()
        er4.findViewWithTag<View>("kind_edit_${team.id}").performClick(); idle()
        val ed4 = TableEditor.last!!
        ed4.scopeDialog = null
        ed4.dialog!!.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        assertNull(ed4.scopeDialog)
        s3.a.finish()
    }
}
