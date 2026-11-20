package party.shulya.freewrite

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File
import java.io.FileOutputStream
import java.time.Duration

/**
 * «Редактор таблиц» (0.30.1, Владимир: «чтобы не просить каждый раз — заходил бы в редактор таблиц и составлял свою:
 * обратная засыпка — участок-месяц, объём, DWG, чек-лист, PDF; сети — разработка, песчаное основание, монтаж трубопровода,
 * защитный слой, лотки, колодцы»). Свой вид таблицы: столбцы (показать, переименовать, порядок, свои — число, дата, отметка,
 * список), виды работ, части объёма; таблица несёт вид с собой.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TableEditorTest {

    private val ctx get() = RuntimeEnvironment.getApplication()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun pause(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Before
    fun setUp() {
        Input.markStylus(ctx)
        Profile.save(ctx, "Владимир", "Шульмин")
        ctx.getSharedPreferences("igs", android.content.Context.MODE_PRIVATE).edit().clear().apply()
        File(ctx.filesDir, "table_templates.json").delete()
    }

    private fun latest() = ShadowAlertDialog.getLatestAlertDialog()
    private fun texts(v: View, out: MutableList<String> = ArrayList()): List<String> {
        if (v is TextView) out += v.text.toString()
        if (v is ViewGroup) for (i in 0 until v.childCount) texts(v.getChildAt(i), out)
        return out
    }

    /** Окно целиком (и то, что ниже прокрутки) — картинкой. */
    private fun shotDialog(d: android.app.Dialog, name: String) {
        val root = d.window!!.decorView as ViewGroup
        val custom = root.findViewById<ViewGroup>(android.R.id.custom)
        val content = (custom?.let { findScroll(it) })?.getChildAt(0) ?: root
        val w = 1300
        content.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        content.layout(0, 0, content.measuredWidth, content.measuredHeight)
        val bmp = Bitmap.createBitmap(content.measuredWidth.coerceAtLeast(1), content.measuredHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        bmp.eraseColor(android.graphics.Color.WHITE)
        content.draw(Canvas(bmp))
        val dir = File("build/screenshots").apply { mkdirs() }
        FileOutputStream(File(dir, name)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun findScroll(g: ViewGroup): android.widget.ScrollView? {
        for (i in 0 until g.childCount) {
            val c = g.getChildAt(i)
            if (c is android.widget.ScrollView) return c
            if (c is ViewGroup) findScroll(c)?.let { return it }
        }
        return null
    }

    private fun shot(v: View, name: String) {
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        val dir = File("build/screenshots").apply { mkdirs() }
        FileOutputStream(File(dir, name)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private class Screen(val a: BoardActivity, val view: BoardView) {
        val b get() = a.board()
        fun sx(x: Float) = (x - b.camX) * b.camZ
        fun sy(y: Float) = (y - b.camY) * b.camZ
    }

    private fun open(b: Board): Screen {
        BoardStore(ctx).save(b)
        val a = Robolectric.buildActivity(BoardActivity::class.java, Intent(ctx, BoardActivity::class.java).putExtra(BoardActivity.EXTRA_ID, b.id)).setup().get()
        idle()
        val view = BoardActivity::class.java.getDeclaredField("view").apply { isAccessible = true }.get(a) as BoardView
        return Screen(a, view)
    }

    private fun event(down: Long, t: Long, action: Int, x: Float, y: Float, tool: Int): MotionEvent {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = tool })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 0.5f })
        return MotionEvent.obtain(down, t, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
    }

    private fun tap(v: View, x: Float, y: Float) {
        val d = SystemClock.uptimeMillis()
        v.dispatchTouchEvent(event(d, d, MotionEvent.ACTION_DOWN, x, y, MotionEvent.TOOL_TYPE_FINGER))
        v.dispatchTouchEvent(event(d, d + 60, MotionEvent.ACTION_UP, x, y, MotionEvent.TOOL_TYPE_FINGER))
        idle()
    }

    // ---------------------------------------------------------------- модель

    @Test
    fun ownKindTravelsWithTableAndOldTablesUnchanged() {
        val b = Board.create("Сети")
        val pdf = TableCol(TableCol.newCustomKey(), "PDF", type = TableCol.LIST, opts = mutableListOf(ColOpt("Нет", TableEditor.OPT_COLORS[0]), ColOpt("Есть", TableEditor.OPT_COLORS[2])))
        val act = TableCol(TableCol.newCustomKey(), "Акт", type = TableCol.MARK)
        val day = TableCol(TableCol.newCustomKey(), "Приёмка", type = TableCol.DATEC)
        val len = TableCol(TableCol.newCustomKey(), "Длина, м", type = TableCol.NUM)
        val cols = TableTemplate.defaultCols(setOf(TableCol.CON)).apply {
            first { it.k == TableCol.UCH }.name = "Месяц"
            addAll(indexOfFirst { it.k == TableCol.VOL } + 1, listOf(pdf, act, day, len))
        }
        val tpl = TableTemplate(TableKinds.newUserId(), "Наружные сети К1", "Сети", cols,
            mutableListOf("Разработка", "Песчаное основание", "Монтаж трубопровода"), mutableListOf(TableKinds.Part(TableKinds.newPartKey(), "Труба"), TableKinds.Part(TableKinds.newPartKey(), "Колодцы")))
        // Шаблон: записали → прочитали — то же.
        val back = TableTemplate.parse(JSONObject(tpl.json()))!!
        assertEquals(tpl.json(), back.json())

        val t = BoardTable(0f, 0f, 1f, "Сети К1").apply { kind = tpl.id; ownKind = tpl.toKind(); this.cols.addAll(tpl.tableCols()) }
        b.tables += t
        val r = BoardRec(t.id, 1.0).apply { uch = "Ноябрь"; zah = "1"; work = "Монтаж трубопровода"; vp[tpl.parts[0].key] = "86.5"; extra[pdf.k] = "Есть"; extra[act.k] = TableCol.CHECK; extra[day.k] = "2026-11-20"; extra[len.k] = "12.5" }
        b.recs += r
        // Части объёма — со своими названиями, на месте «Объёма».
        assertEquals(listOf("Месяц", "Захватка", "Вид работ", "Труба", "Колодцы", "PDF", "Акт", "Приёмка", "Длина, м"),
            t.visibleCols().map { it.title() }.filter { it in setOf("Месяц", "Захватка", "Вид работ", "Труба", "Колодцы", "PDF", "Акт", "Приёмка", "Длина, м") })
        assertEquals("Есть", IdTables.cellText(b, r, pdf.k))
        assertEquals("✓", IdTables.cellText(b, r, act.k))
        assertEquals("20.11.26", IdTables.cellText(b, r, day.k))
        assertEquals("12,5", IdTables.cellText(b, r, len.k))
        // Доска с такой таблицей: устройство вида — в самой таблице (у коллеги без шаблона — то же).
        val j = IdTables.tableJson(t)
        assertTrue(j, "\"kd\":" in j && "\"ty\":\"list\"" in j && "\"op\":[" in j)
        val t2 = IdTables.parseTable(JSONObject(j))
        assertEquals("Наружные сети К1", t2.kindDef()!!.name)
        assertEquals(listOf("Труба", "Колодцы"), t2.parts().map { it.name })
        assertEquals(j, IdTables.tableJson(t2))
        assertEquals(TableCol.LIST, t2.allCols().first { it.k == pdf.k }.type)
        // Старые таблицы встроенных видов — строка та же, без новых полей.
        val old = BoardTable(0f, 0f, 1f, "КЖ").apply { kind = TableKinds.KJ_VERT.id; ownCols() }
        val oj = IdTables.tableJson(old)
        assertFalse(oj, "\"kd\"" in oj || "\"ty\"" in oj)
        // Отбор: варианты списка — по их порядку.
        b.recs += BoardRec(t.id, 2.0).apply { zah = "2"; extra[pdf.k] = "Нет" }
        assertEquals(listOf("Нет", "Есть"), IdTables.filterValues(b, t, pdf.k).map { it.label })
        // «Сохранить как свою» с таблицы встроенного вида — части с теми же ключами (данные строк читаются как раньше).
        val kj = BoardTable(0f, 0f, 1f, "Вертикальные").apply { kind = TableKinds.KJ_VERT.id }
        b.tables += kj
        val fromKj = TableTemplate.fromTable(b, kj)
        assertEquals(listOf("w", "c"), fromKj.parts.map { it.key })
        assertEquals(listOf("Опалубка", "Бетон"), fromKj.works)
        assertTrue(TableKinds.isUser(fromKj.id))
    }

    // ---------------------------------------------------------------- экран

    @Test
    fun editorMakesOwnTableAndItIsPlacedAndFilled() {
        val s = open(Board.create("Сети"))
        s.view.fitRect(0f, 0f, 1600f, 900f); idle()
        s.a.tableUi.chooseKind(200f to 200f); idle()
        val er = s.a.tableUi.explorer!!.window!!.decorView
        er.findViewWithTag<View>("kind_new").performClick(); idle()
        val ed = TableEditor.last!!
        val d = ed.dialog!!
        val root = d.window!!.decorView
        root.findViewWithTag<EditText>("tpl_name").setText("Наружные сети К1")
        root.findViewWithTag<EditText>("tpl_section").setText("Сети")
        // Участок — показать и назвать «Месяц».
        val uchI = (0 until 30).first { root.findViewWithTag<EditText>("tpl_col_name_$it")?.text?.toString() == "Участок" }
        root.findViewWithTag<CheckBox>("tpl_col_show_$uchI").isChecked = true
        root.findViewWithTag<EditText>("tpl_col_name_$uchI").setText("Месяц")
        // Виды работ.
        for (w in listOf("Разработка", "Песчаное основание", "Монтаж трубопровода", "Устройство защитного слоя", "Монтаж колодцев")) {
            root.findViewWithTag<EditText>("tpl_work_new").setText(w)
            root.findViewWithTag<View>("tpl_work_add").performClick(); idle()
        }
        // Свой столбец «PDF» — список «Нет, Есть, Проверен» и отметка «Акт».
        root.findViewWithTag<View>("tpl_col_add").performClick(); idle()
        var cd = ed.colDialog!!.window!!.decorView
        cd.findViewWithTag<EditText>("tplc_name").setText("PDF")
        cd.findViewWithTag<View>("tplc_type_4").performClick()
        cd.findViewWithTag<EditText>("tplc_opts").setText("Нет, Есть, Проверен")
        ed.colDialog!!.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        root.findViewWithTag<View>("tpl_col_add").performClick(); idle()
        cd = ed.colDialog!!.window!!.decorView
        cd.findViewWithTag<EditText>("tplc_name").setText("Акт")
        cd.findViewWithTag<View>("tplc_type_3").performClick()
        ed.colDialog!!.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        assertTrue(texts(root).contains("Список: Нет, Есть, Проверен"))
        shotDialog(d, "table-editor.png")
        d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick(); idle()

        // В проводнике — раздел «Сети» со своей таблицей.
        val tpl = TableTemplates(ctx).list().single()
        assertEquals("Сети", tpl.section)
        val ex = s.a.tableUi.explorer!!
        val er2 = ex.window!!.decorView
        er2.findViewWithTag<EditText>("kind_search").setText("сети"); idle()
        assertNotNull(er2.findViewWithTag<View>("kind_edit_${tpl.id}"))
        shotDialog(ex, "table-editor-explorer.png")
        er2.findViewWithTag<View>("kind_${tpl.id}").performClick(); idle()
        // Раздела нет — шифр; потом форма строки: месяц, захватка, виды работ — свои, все отмечены.
        latest().window!!.decorView.findViewWithTag<EditText>("table_title").setText("НВК 3.1")
        latest().getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        val form = latest()
        val fr = form.window!!.decorView
        assertTrue(texts(fr).joinToString().contains("Месяц"))
        fr.findViewWithTag<EditText>("add_uch").setText("Ноябрь")
        fr.findViewWithTag<EditText>("add_zah").setText("Уч. 1")
        form.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        val t = s.b.tables.single()
        assertEquals(tpl.id, t.kind)
        assertEquals("Наружные сети К1", t.kindDef()!!.name)
        val rows = IdTables.allRows(s.b, t)
        assertEquals(listOf("Разработка", "Песчаное основание", "Монтаж трубопровода", "Устройство защитного слоя", "Монтаж колодцев"), rows.map { it.work })
        assertEquals("Ноябрь", rows[0].uch)

        // Ячейки: «PDF» — варианты, «Акт» — касание ставит ✓.
        val tb = s.view.tableDraw.bounds(s.b, t, RectF())
        s.view.fitRect(tb.left - 40f, tb.top - 40f, tb.right + 40f, tb.bottom + 40f); idle(); pause(600)
        val pdf = t.allCols().first { it.title() == "PDF" }
        val act = t.allCols().first { it.title() == "Акт" }
        val r0 = rows[0]
        val c = s.view.tableDraw.cellRect(s.b, t, r0, pdf.k, RectF())!!
        tap(s.view, s.sx(c.centerX()), s.sy(c.centerY()))
        s.a.tableUi.popup!!.contentView.findViewWithTag<View>("pop_opt_1").performClick(); idle()
        assertEquals("Есть", s.a.board().recs.first { it.id == r0.id }.extra[pdf.k])
        val ca = s.view.tableDraw.cellRect(s.b, t, r0, act.k, RectF())!!
        tap(s.view, s.sx(ca.centerX()), s.sy(ca.centerY()))
        assertEquals(TableCol.CHECK, s.a.board().recs.first { it.id == r0.id }.extra[act.k])
        shot(s.a.window.decorView, "table-editor-board.png")

        // Поправили шаблон (новый вид работ) — таблица на доске тоже («Обновить таблицы этого вида»).
        s.a.tableUi.chooseKind(); idle()
        s.a.tableUi.explorer!!.window!!.decorView.findViewWithTag<EditText>("kind_search").setText("сети"); idle()
        s.a.tableUi.explorer!!.window!!.decorView.findViewWithTag<View>("kind_edit_${tpl.id}").performClick(); idle()
        val ed2 = TableEditor.last!!.dialog!!
        ed2.window!!.decorView.findViewWithTag<EditText>("tpl_work_new").setText("Лотки")
        ed2.window!!.decorView.findViewWithTag<View>("tpl_work_add").performClick(); idle()
        assertTrue(ed2.window!!.decorView.findViewWithTag<CheckBox>("tpl_apply").isChecked)
        ed2.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        assertTrue("Лотки" in s.a.board().tables.single().kindDef()!!.works)
        s.a.finish()
    }

    @Test
    fun saveTableOnBoardAsOwn() {
        val b = Board.create("ИГС")
        val t = BoardTable(100f, 100f, 1.5f, "Обратная засыпка").apply { kind = TableKinds.CUSTOM }
        b.tables += t
        b.recs += BoardRec(t.id, 1.0).apply { zah = "1"; work = "Обратная засыпка"; vol = "120" }
        val s = open(b)
        val tt = s.b.tables.single()
        s.a.tableUi.saveAsTemplate(tt); idle()
        val d = TableEditor.last!!.dialog!!
        assertEquals("Обратная засыпка", d.window!!.decorView.findViewWithTag<EditText>("tpl_name").text.toString())
        d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        val tpl = TableTemplates(ctx).list().single()
        val now = s.a.board().tables.single()
        assertEquals("таблица стала своего вида", tpl.id, now.kind)
        assertTrue("Обратная засыпка" in tpl.works)
        assertEquals("объём строки на месте", "120", s.a.board().recs.single().vol)
        assertTrue(now.visibleCols().any { it.k == TableCol.VOL })
        s.a.finish()
    }
}
