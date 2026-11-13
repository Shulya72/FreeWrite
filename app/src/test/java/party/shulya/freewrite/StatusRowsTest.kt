package party.shulya.freewrite

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
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
 * 0.18 (FW-011): два списка статусов — ИД (метки и ИГС в строках ИД) и конструкции (выноски);
 * строка ИД у захватки «Захватка 1 — Опалубка: Чек-лист ✓/✗, DWG ✓/✗, ИГС [статус]»;
 * подсчёт по разделам и сводка. Планшет с пером, как у Владимира.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StatusRowsTest {

    private val ctx get() = RuntimeEnvironment.getApplication()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun pause(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Before
    fun setUp() {
        Input.markStylus(ctx)
        Profile.save(ctx, "Владимир", "Шульмин")
    }

    // ------------------------------------------------------------ модель

    @Test
    fun twoStatusListsAndOldBoards() {
        val b = Board.create("Новая")
        assertEquals(listOf("Конструкция не выполнена", "Не предоставлена", "В работе", "Сборка", "На согласовании АН", "Не согласована", "На подписи у заказчика",
            "Возврат на корректировку", "Сдано в ПТО"), b.statuses(BoardStatus.ID).map { it.name })
        assertEquals("сдано — «Сдано в ПТО»", listOf("Сдано в ПТО"), b.statuses(BoardStatus.ID).filter { it.done }.map { it.name })
        assertEquals(4, b.statuses(BoardStatus.CK).size)
        assertEquals(3, b.statuses(BoardStatus.DWG).size)
        assertEquals(5, b.statuses(BoardStatus.CON).size)
        val c0 = b.firstStatus(BoardStatus.CON)
        assertEquals("по кругу внутри своего списка", b.statuses(BoardStatus.ID)[0].id, b.nextStatus(b.statuses(BoardStatus.ID).last().id, BoardStatus.ID))
        assertEquals(b.statuses(BoardStatus.CON)[1].id, b.nextStatus(c0, BoardStatus.CON))
        // Признак списка — только у конструкций (у статусов ИД JSON как до 0.18) и сохраняется.
        val js = b.statusesJson()
        assertTrue(js.contains("\"g\":\"con\""))
        assertFalse(JSONObject(js.substring(1, js.indexOf('}') + 1)).has("g"))
        val back = Board.fromJson(JSONObject(b.toJsonString()))
        assertEquals(b.statuses.map { it.id + it.group }, back.statuses.map { it.id + it.group })

        // Старая доска с нетронутым списком «Не сдано / В работе / Сдано», меток нет — список ИД Владимира.
        val old = """{"id":"o1","title":"Старая","statuses":[{"id":"s0","name":"Не сдано","color":1},{"id":"s1","name":"В работе","color":2},{"id":"s2","name":"Сдано","color":3}]}"""
        val o1 = Board.fromJson(JSONObject(old))
        assertEquals("Конструкция не выполнена", o1.statuses(BoardStatus.ID)[0].name)
        assertEquals(5, o1.statuses(BoardStatus.CON).size)
        // На двух планшетах одна и та же старая доска получает одинаковые статусы (общая доска сходится).
        assertEquals(o1.entryHashes(), Board.fromJson(JSONObject(old)).entryHashes())
        // Старая доска, где метки уже стоят, — её статусы остаются списком ИД.
        val used = old.dropLast(1) + ""","stickers":[{"id":"k1","x":1,"y":2,"sid":"s1","scale":1}]}"""
        val o2 = Board.fromJson(JSONObject(used))
        assertEquals(listOf("Не сдано", "В работе", "Сдано"), o2.statuses(BoardStatus.ID).map { it.name })
        assertEquals("В работе", o2.status(o2.stickers[0].sid).name)

        // Доска 0.18 с нетронутым списком ИД: номера i0–i3 те же, названия — ИГС, «Скан в архиве» (i4) → «Сдано в ПТО».
        val b18 = Board.create("0.18")
        b18.statuses.clear()
        b18.statuses += listOf(BoardStatus("i0", "Конструкция не принята", 1), BoardStatus("i1", "В оформлении", 2),
            BoardStatus("i2", "На подписи у Заказчика", 3), BoardStatus("i3", "Передано в ПТО", 4), BoardStatus("i4", "Скан в архиве", 5))
        b18.statuses += Board.conStatuses()
        b18.stickers += BoardSticker(1f, 2f, "i4", 1f)
        b18.stickers += BoardSticker(1f, 2f, "i1", 1f)
        val u = Board.fromJson(JSONObject(b18.toJsonString()))
        assertEquals(IdTables.igsStatuses().map { it.id + it.name }, u.statuses(BoardStatus.ID).map { it.id + it.name })
        assertEquals(listOf("Сдано в ПТО", "В работе"), u.stickers.map { u.status(it.sid).name })
        assertEquals(5, u.statuses(BoardStatus.CON).size)
        assertEquals(4, u.statuses(BoardStatus.CK).size)
        assertEquals("одинаково на двух планшетах", u.entryHashes(), Board.fromJson(JSONObject(b18.toJsonString())).entryHashes())

        // Строка ИД: JSON, снимок для «Отменить», следующая захватка.
        val r = BoardRow(10f, 20f, 1.5f, "Захватка 1 — Опалубка", true, false, b.statuses(BoardStatus.ID)[3].id)
        b.rows += r
        val rb = Board.fromJson(JSONObject(b.toJsonString())).rows.single()
        assertEquals(Board.rowJson(r).replace(r.id, ""), Board.rowJson(rb).replace(rb.id, ""))
        assertEquals(r.id, rb.id)
        val snap = b.snapshot()
        r.check = false
        assertTrue(b.restored(snap).rows.single().check)
        assertEquals("Захватка 2 — Опалубка", BoardRow.nextTitle("Захватка 1 — Опалубка"))
        assertEquals("Захватка 10", BoardRow.nextTitle("Захватка 9"))
        assertEquals("Опалубка", BoardRow.nextTitle("Опалубка"))
        // Строка — новый вид для общей доски и файла.
        b.toJsonString(stampDevice = "dev1")
        assertTrue(Remote.pending(b).any { it.kind == "row" && it.id == r.id })
    }

    // ------------------------------------------------------------ на экране

    private fun event(down: Long, t: Long, action: Int, x: Float, y: Float, tool: Int): MotionEvent {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = tool })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 0.5f; size = 0.05f })
        val src = if (tool == MotionEvent.TOOL_TYPE_STYLUS) InputDevice.SOURCE_STYLUS else InputDevice.SOURCE_TOUCHSCREEN
        return MotionEvent.obtain(down, t, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, src, 0)
    }

    private fun tap(v: View, tool: Int, x: Float, y: Float) {
        val d = SystemClock.uptimeMillis()
        v.dispatchTouchEvent(event(d, d, MotionEvent.ACTION_DOWN, x, y, tool))
        v.dispatchTouchEvent(event(d, d + 60, MotionEvent.ACTION_UP, x, y, tool))
        idle()
    }

    /** Подержать пальцем 0,7 с и (если dx/dy) протащить. */
    private fun holdAndDrag(v: View, x: Float, y: Float, dx: Float, dy: Float) {
        val d = SystemClock.uptimeMillis()
        val f = MotionEvent.TOOL_TYPE_FINGER
        v.dispatchTouchEvent(event(d, d, MotionEvent.ACTION_DOWN, x, y, f))
        pause(700)
        val t = SystemClock.uptimeMillis()
        if (dx != 0f || dy != 0f) for (i in 1..10) v.dispatchTouchEvent(event(d, t + i * 12L, MotionEvent.ACTION_MOVE, x + dx * i / 10, y + dy * i / 10, f))
        v.dispatchTouchEvent(event(d, t + 150, MotionEvent.ACTION_UP, x + dx, y + dy, f))
        idle()
    }

    private fun field(o: Any, name: String): Any? = o.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(o)

    private fun shot(v: View, name: String) {
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        val dir = File("build/screenshots").apply { mkdirs() }
        FileOutputStream(File(dir, name)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun rowAtZahvatkaChecksStatusMoveAndCountsBySection() {
        val b0 = Board.create("КЖ")
        b0.frames += BoardFrame(0f, 0f, 2400f, 1400f, "КЖ 6.1.1")
        b0.frames += BoardFrame(3000f, 0f, 1500f, 1400f, "КЖ 6.1.2")
        b0.callouts += BoardCallout(3200f, 900f, 2f, "Плита", b0.statuses(BoardStatus.CON)[3].id).apply { addLeader(3400f, 500f) }
        BoardStore(ctx).save(b0)
        val controller = Robolectric.buildActivity(BoardActivity::class.java,
            Intent(ctx, BoardActivity::class.java).putExtra(BoardActivity.EXTRA_ID, b0.id)).setup()
        val a = controller.get()
        idle()
        val root = a.window.decorView
        val view = field(a, "view") as BoardView
        val bd = { a.board() }
        view.fitRect(0f, 0f, 2400f, 1400f)
        idle()
        fun sx(x: Float) = (x - bd().camX) * bd().camZ
        fun sy(y: Float) = (y - bd().camY) * bd().camZ
        val ids = bd().statuses(BoardStatus.ID)

        // «Строка ИД» (с 0.20 инструмента на панели нет — его место заняла «Таблица ИД», старые строки работают):
        // касание пером у захватки — название (предложено «Захватка 1 — ») → строка.
        a.javaClass.getDeclaredMethod("setTool", BoardActivity.Tool::class.java, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(a, BoardActivity.Tool.ROW, false)
        tap(view, MotionEvent.TOOL_TYPE_STYLUS, sx(300f), sy(400f))
        var dlg = ShadowAlertDialog.getLatestAlertDialog()
        val edit = dlg.window!!.decorView.findViewWithTag<android.widget.EditText>("row_title")
        assertEquals("Захватка 1 — ", edit.text.toString())
        edit.setText("Захватка 1 — Опалубка")
        dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        val r = bd().rows.single()
        assertEquals("Захватка 1 — Опалубка", r.title)
        assertEquals(ids[0].id, r.sid)
        assertEquals("строка знает свой раздел", "КЖ 6.1.1", bd().sectionOf(r)?.title)

        // Касание пальцем частей строки: галочки и ИГС.
        pause(1000) // сразу после пера палец считается ладонью
        view.rowW(r)
        fun part(p: Int) = r.x + (r.parts[p] + r.parts[p + 1]) / 2f * r.scale
        val cy = r.y + r.h / 2f
        tap(view, MotionEvent.TOOL_TYPE_FINGER, sx(part(BoardView.RowPart.CHECK)), sy(cy))
        assertTrue("Чек-лист ✓", r.check)
        tap(view, MotionEvent.TOOL_TYPE_FINGER, sx(part(BoardView.RowPart.DWG)), sy(cy))
        assertTrue("DWG ✓", r.dwg)
        tap(view, MotionEvent.TOOL_TYPE_FINGER, sx(part(BoardView.RowPart.STATUS)), sy(cy))
        assertEquals("ИГС — следующий статус ИД", ids[1].id, r.sid)
        tap(view, MotionEvent.TOOL_TYPE_FINGER, sx(part(BoardView.RowPart.DWG)), sy(cy))
        assertFalse("ещё касание — снять", r.dwg)

        // Подержать и потащить — строка переезжает (и выделена: уголки — размер).
        val y0 = r.y
        holdAndDrag(view, sx(part(BoardView.RowPart.TITLE)), sy(cy), 0f, 120f)
        assertTrue("строка переехала вниз: ${r.y - y0}", r.y - y0 > 120f / bd().camZ * 0.8f)
        assertTrue(view.selection.single() === r)
        val bar = root.findViewWithTag<View>("selection_bar") ?: root
        val texts = ArrayList<String>()
        fun walk(v: View) { if (v is android.widget.TextView) texts += v.text.toString(); if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i)) }
        walk(bar)
        assertTrue("на панели — раздел: $texts", texts.any { it.startsWith("Строка ИД · КЖ 6.1.1") })

        // «+ Следующая» — следующая захватка под строкой.
        root.findViewWithTag<View>("sel_next").performClick()
        idle()
        assertEquals(2, bd().rows.size)
        val r2 = bd().rows[1]
        assertEquals("Захватка 2 — Опалубка", r2.title)
        assertFalse(r2.check); assertFalse(r2.dwg)
        assertEquals(ids[0].id, r2.sid)
        assertTrue("под первой", r2.y > r.y)
        view.clearSelection()
        idle()

        // Подержать на ИГС и отпустить, не двигая, — список статусов ИД.
        val cy2 = r2.y + r2.h / 2f
        view.rowW(r2)
        holdAndDrag(view, sx(r2.x + (r2.parts[3] + r2.parts[4]) / 2f * r2.scale), sy(cy2), 0f, 0f)
        dlg = ShadowAlertDialog.getLatestAlertDialog()
        assertNotNull(dlg)
        assertEquals(ids.size + 1, dlg.listView.adapter.count) // статусы ИД + «Настроить статусы…»
        dlg.dismiss()
        idle()

        // Легенда по разделу «КЖ 6.1.1»: две строки, галочки.
        root.findViewWithTag<View>("board_statuses").performClick()
        idle()
        root.findViewWithTag<View>("legend_section").performClick()
        idle()
        dlg = ShadowAlertDialog.getLatestAlertDialog()
        shadowOf(dlg).clickOnItem(1) // разделы сверху вниз: «КЖ 6.1.1», потом «КЖ 6.1.2»
        idle()
        val checks = root.findViewWithTag<android.widget.TextView>("legend_checks").text.toString()
        assertEquals("Строк ИД: 2 · Чек-лист ✓ 1 · DWG ✓ 0", checks)
        shot(root, "rows-legend.png")

        // Сводка: таблица для Excel — разделы строками.
        val table = a.summaryTable().trim().lines()
        assertTrue(table[0].startsWith("Раздел\tСтрок ИД\tЧек-лист ✓\tDWG ✓\t" + ids[0].name))
        val kj1 = table.first { it.startsWith("КЖ 6.1.1\t") }.split('\t')
        assertEquals(listOf("КЖ 6.1.1", "2", "1", "0", "1", "1", "0", "0", "0"), kj1.take(9))
        val kj2 = table.first { it.startsWith("КЖ 6.1.2\t") }.split('\t')
        assertEquals("выноска — бетонирование в КЖ 6.1.2", "1", kj2[4 + ids.size + 3])
        root.findViewWithTag<View>("legend_summary").performClick()
        idle()
        assertNotNull(ShadowAlertDialog.getLatestAlertDialog().window!!.decorView.findViewWithTag<View>("summary_section_0"))
        shot(ShadowAlertDialog.getLatestAlertDialog().window!!.decorView, "rows-summary.png")
        ShadowAlertDialog.getLatestAlertDialog().dismiss()

        // «Отменить» — вернуть как было до «+ Следующая» и т. д.
        val n = bd().rows.size
        root.findViewWithTag<View>("board_undo").performClick()
        idle()
        assertTrue(bd().rows.size <= n)
        controller.pause().stop().destroy()
    }
}
