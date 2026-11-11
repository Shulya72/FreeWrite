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
import android.widget.EditText
import android.widget.TextView
import org.json.JSONObject
import org.junit.Assert.assertEquals
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.time.Duration
import java.util.zip.ZipInputStream

/**
 * 0.21: объёмы в таблицах ИД (столбец «Объём по схеме», проект по видам работ, перебор / осталось) и раздел = шифр
 * (таблицы-подразделы со своей нумерацией захваток; «всего по шифру» — в сводке раздела и в Excel).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VolumeTest {

    private val ctx get() = RuntimeEnvironment.getApplication()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun pause(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private val nb = ' '

    @Before
    fun setUp() {
        Input.markStylus(ctx)
        Profile.save(ctx, "Владимир", "Шульмин")
    }

    /** Раздел «КЖ 6.1.1»: «Несущие стены» (4 захватки, проект) и «Ненесущие стены» (2 захватки, проект). */
    private fun sample(): Board {
        val b = Board.create("ИГС")
        b.frames += BoardFrame(0f, 0f, 3400f, 2600f, "КЖ 6.1.1")
        val t1 = BoardTable(100f, 100f, 1.5f, "Несущие стены")
        val t2 = BoardTable(100f, 1300f, 1.5f, "Ненесущие стены")
        b.tables += t1; b.tables += t2
        var o = 1.0
        val v1 = listOf(186.4 to 42.3, 192.1 to 44.1, 178.6 to 40.8, null)
        for ((i, p) in v1.withIndex()) for ((w, v) in listOf("Опалубка" to p?.first, "Бетон" to p?.second)) b.recs += BoardRec(t1.id, o++).apply {
            zah = (i + 1).toString(); con = "Вертикал"; work = w; vol = v?.let { IdTables.volKey(it) }.orEmpty()
            igs = if (v != null) IdTables.IGS_WORK else IdTables.IGS_NONE
        }
        for ((i, p) in listOf(96.2 to 14.4, 101.5 to 15.2).withIndex()) for ((w, v) in listOf("Опалубка" to p.first, "Бетон" to p.second)) b.recs += BoardRec(t2.id, o++).apply {
            zah = (i + 1).toString(); con = "Вертикал"; work = w; vol = IdTables.volKey(v)
        }
        t1.plan["бетон"] = VolPlan("Бетон", "170", "м³"); t1.plan["опалубка"] = VolPlan("Опалубка", "540", "м²")
        t2.plan["бетон"] = VolPlan("Бетон", "58", "м³"); t2.plan["опалубка"] = VolPlan("Опалубка", "380", "м²")
        return b
    }

    @Test
    fun numbersSumsJsonHistoryExcel() {
        // Числа: запятая или точка, пробелы между тысячами, единица рядом.
        assertEquals("42.3", IdTables.parseVol("42,3"))
        assertEquals("1128.7", IdTables.parseVol("1 128,7 м²"))
        assertEquals("42.3", IdTables.parseVol("42.3м3"))
        assertEquals("170", IdTables.parseVol("170,0"))
        assertEquals("", IdTables.parseVol("бетон"))
        assertEquals("1${nb}128,7", IdTables.volFmt(1128.7))
        assertEquals("28,4", IdTables.volFmt(58.0 - 29.6))
        assertEquals("м³", IdTables.defaultUnit("Бетон")); assertEquals("м²", IdTables.defaultUnit("Опалубка"))
        assertEquals("т", IdTables.defaultUnit("Армирование")); assertEquals("м³", IdTables.defaultUnit("Обратная засыпка"))

        val b = sample()
        val t1 = b.tables[0]; val t2 = b.tables[1]
        // Раздел = шифр: полное имя таблицы, шифр в шапке.
        assertEquals("КЖ 6.1.1 · Несущие стены", IdTables.fullTitle(b, t1))
        assertEquals("КЖ 6.1.1", IdTables.sectionPrefix(b, t1))
        assertEquals("КЖ 6.1.1", IdTables.fullTitle(b, BoardTable(10f, 10f, 1f, "КЖ 6.1.1")))
        assertEquals("КЖ 9", IdTables.fullTitle(b, BoardTable(9000f, 9000f, 1f, "КЖ 9")))

        // Объёмы: по видам работ, проект, перебор / осталось, без объёма.
        val v1 = IdTables.volumes(b, t1)
        assertEquals(listOf("Бетон", "Опалубка"), v1.map { it.work })
        val bet = v1[0]; val op = v1[1]
        assertEquals(127.2, bet.fact, 1e-9); assertEquals(3, bet.n); assertEquals(75, bet.percent); assertTrue(!bet.over)
        assertEquals(557.1, op.fact, 1e-9); assertTrue(op.over); assertEquals(103, op.percent)
        assertEquals("захватка 4 без объёма — опалубка и бетон", 2, v1.sumOf { it.missing.size })
        assertEquals(listOf("м³", "м²"), v1.map { it.unit })
        assertEquals("42,3 м³", IdTables.cellText(b, b.recs[1], TableCol.VOL))

        // JSON: объём строки и проект таблицы; отмена; старая таблица со своими столбцами — объём встаёт после вида работ.
        val back = Board.fromJson(JSONObject(b.toJsonString()))
        assertEquals(IdTables.tableJson(t1), IdTables.tableJson(back.tables[0]))
        assertEquals("42.3", back.recs[1].vol)
        assertEquals("540", back.tables[0].plan["опалубка"]!!.value)
        val old = JSONObject(IdTables.tableJson(t2)).put("cols", org.json.JSONArray().apply {
            for (k in listOf("zah", "con", "work", "date", "ck", "dwg", "igs", "dcust", "dpto", "who", "note")) put(JSONObject().put("k", k))
        })
        val ot = IdTables.parseTable(old)
        assertEquals(listOf("aosr", "nsch", "zah", "con", "work", "vol", "date"), ot.cols.take(7).map { it.k })
        // № схемы и № АОСР: следующий номер — последнее число +1 с нулями.
        assertEquals("ИС-13", IdTables.nextNumber("ИС-12"))
        assertEquals("АОСР 6.1.1-026", IdTables.nextNumber("АОСР 6.1.1-025"))
        b.recs[0].nsch = "ИС-12"; b.recs[1].nsch = "ИС-13"; b.recs[0].aosr = "АОСР 6.1.1-025"
        assertEquals(listOf("ИС-14"), IdTables.nextNumbers(b, t1, TableCol.NSCH))
        val back2 = Board.fromJson(JSONObject(b.toJsonString()))
        assertEquals("ИС-13", back2.recs[1].nsch); assertEquals("АОСР 6.1.1-025", back2.recs[0].aosr)
        assertEquals(listOf("№ АОСР", "№ схемы", "Захватка", "Конструкция", "Вид работ", "Объём по схеме", "Дата работ"), t1.visibleCols().take(7).map { it.title() })
        val snap = b.snapshot()
        t1.plan.remove("бетон")
        assertEquals("170", b.restored(snap).tables[0].plan["бетон"]!!.value)
        t1.plan["бетон"] = VolPlan("Бетон", "170", "м³")

        // История: правка объёма записывается.
        val r = b.recs[0]
        Vedoms.set(b, r, TableCol.VOL, "190.5", "Владимир Шульмин")
        assertEquals("Объём по схеме: 186,4 → 190,5", Vedoms.eventText(b, Vedoms.history(b, r).first()))

        // Excel: число и единица в «Схемах», объёмы и «всего по шифру» в «Сводке».
        val out = ByteArrayOutputStream()
        IdExcel.write(b, IdExcel.allFilter(b), setOf(IdExcel.SHEET_SUMMARY, IdExcel.SHEET_ALL, IdExcel.SHEET_PER), out, "2026-11-11")
        val files = HashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { z -> while (true) { val e = z.nextEntry ?: break; files[e.name] = z.readBytes().toString(Charsets.UTF_8) } }
        val sum = files["xl/worksheets/sheet1.xml"]!!
        // Всего по шифру, бетон: 127,2 (несущие) + 29,6 (ненесущие) = 156,8 из 228.
        assertTrue(Regex("<v>[^<]*</v>").findAll(sum).map { it.value }.joinToString(),
            sum.contains("Объёмы · раздел (шифр)") && sum.contains("Всего по шифру") && sum.contains("<v>156.8</v>") && sum.contains("<v>228.0</v>"))
        val all = files["xl/worksheets/sheet2.xml"]!!
        assertTrue(all.contains("Объём по схеме") && all.contains(">Ед.<") && all.contains("<v>42.3</v>") && all.contains("КЖ 6.1.1 · Несущие стены"))
        assertTrue(all.contains("№ схемы") && all.contains("№ АОСР") && all.contains("ИС-13") && all.contains("АОСР 6.1.1-025"))
        assertTrue(files["xl/workbook.xml"]!!.contains("name=\"КЖ 6.1.1 · Несущие стены\""))
        // Проект вписан не у всех таблиц раздела — по шифру проекта нет (процент обманул бы).
        t2.plan.remove("бетон")
        val out2 = ByteArrayOutputStream()
        IdExcel.write(b, IdExcel.allFilter(b), setOf(IdExcel.SHEET_SUMMARY), out2, "2026-11-11")
        ZipInputStream(ByteArrayInputStream(out2.toByteArray())).use { z ->
            while (true) { val e = z.nextEntry ?: break; val x = z.readBytes().toString(Charsets.UTF_8); if (e.name.endsWith("sheet1.xml")) assertTrue(x.contains("проект не у всех таблиц")) }
        }
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
        fun cell(r: BoardRec, col: String): Pair<Float, Float> {
            val t = IdTables.tableOf(b, r)!!
            val c = view.tableDraw.cellRect(b, t, r, col, RectF())!!
            return sx(c.centerX()) to sy(c.centerY())
        }
        /** Середина строки блока «Объёмы» (i — вид работ; -1 — шапка блока; size — строка «без объёма»). */
        fun volRow(t: BoardTable, i: Int): Pair<Float, Float> {
            val l = view.tableDraw.layout(b, t)
            val y = if (i < 0) l.volTop + TableDraw.VOL_HEAD / 2f
            else if (i >= l.vol!!.size) l.volTop + TableDraw.VOL_HEAD + l.vol.size * TableDraw.VOL_ROW + TableDraw.VOL_NOTE / 2f
            else l.volTop + TableDraw.VOL_HEAD + (i + 0.5f) * TableDraw.VOL_ROW
            return sx(t.x + 500f * t.scale) to sy(t.y + y * t.scale)
        }
    }

    private fun open(b: Board): Screen {
        BoardStore(ctx).save(b)
        val a = Robolectric.buildActivity(BoardActivity::class.java, Intent(ctx, BoardActivity::class.java).putExtra(BoardActivity.EXTRA_ID, b.id)).setup().get()
        idle()
        val view = BoardActivity::class.java.getDeclaredField("view").apply { isAccessible = true }.get(a) as BoardView
        return Screen(a, view)
    }

    private fun latest() = ShadowAlertDialog.getLatestAlertDialog()

    private fun texts(v: View, out: MutableList<String> = ArrayList()): List<String> {
        if (v is TextView) out += v.text.toString()
        if (v is ViewGroup) for (i in 0 until v.childCount) texts(v.getChildAt(i), out)
        return out
    }

    @Test
    fun screenVolumesProjectAndSectionTotal() {
        val s = open(sample())
        val root = s.a.window.decorView
        val t1 = s.b.tables[0]
        val tb = s.view.tableDraw.bounds(s.b, t1, RectF())
        s.view.fitRect(tb.left - 40f, tb.top - 60f, tb.right + 40f, tb.bottom + 40f)
        idle()
        pause(1000)
        val l = s.view.tableDraw.layout(s.b, t1)
        assertEquals(2, l.vol!!.size)
        assertEquals(2, l.volMissing.size)
        shot(s.view, "volumes-table.png")

        // Ячейка «Объём по схеме» (захватка 4, опалубка) — поле с цифрами → 180,2.
        val rows = IdTables.allRows(s.b, t1)
        s.a.tableUi.cellAction(t1, rows[6], TableCol.VOL)
        var d = latest()
        d.window!!.decorView.findViewWithTag<EditText>("cell_text").setText("180,2")
        d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertEquals("180.2", rows[6].vol)
        assertEquals(1, s.view.tableDraw.layout(s.b, t1).volMissing.size)
        // «В текст» + активная ячейка: написанное пером «41,5» — в объём бетона захватки 4.
        root.findViewWithTag<View>("board_tool_write").performClick()
        val (bx, by) = s.cell(rows[7], TableCol.VOL)
        tap(s.view, MotionEvent.TOOL_TYPE_STYLUS, bx, by)
        assertEquals(rows[7] to TableCol.VOL, s.a.tableUi.active)
        assertTrue(s.a.tableUi.penIntoCell(5000f, 5000f, "41,5"))
        assertEquals("41.5", rows[7].vol)
        root.findViewWithTag<View>("cell_done").performClick()
        idle()
        assertTrue(s.view.tableDraw.layout(s.b, t1).volMissing.isEmpty())

        // № схемы: в поле — подсказка «следующий номер» после последнего в таблице.
        rows[0].nsch = "ИС-12"
        s.a.tableUi.cellAction(t1, rows[1], TableCol.NSCH)
        d = latest()
        val sugg = d.window!!.decorView.findViewWithTag<TextView>("cell_sugg_0")
        assertEquals("ИС-13", sugg.text.toString())
        sugg.performClick()
        d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertEquals("ИС-13", rows[1].nsch)

        // Палец по строке «Опалубка» блока объёмов — окно проекта: 760 м² (факт 737,3 — уже не перебор).
        root.findViewWithTag<View>("board_tool_hand").performClick()
        pause(1000)
        val (ox, oy) = s.volRow(t1, 1)
        tap(s.view, MotionEvent.TOOL_TYPE_FINGER, ox, oy)
        d = latest()
        assertTrue(texts(d.window!!.decorView).joinToString().contains("Опалубка") || d.isShowing)
        d.window!!.decorView.findViewWithTag<EditText>("proj_value").setText("760")
        d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertEquals("760", t1.plan["опалубка"]!!.value)
        assertTrue(!IdTables.volumes(s.b, t1)[1].over)
        // «+ проект» — новый вид работ «Армирование» в тоннах.
        val (px, py) = s.volRow(t1, -1)
        tap(s.view, MotionEvent.TOOL_TYPE_FINGER, px, py)
        val items = shadowOf(latest()).items.map { it.toString() }
        shadowOf(latest()).clickOnItem(items.indexOf("Армирование"))
        idle()
        d = latest()
        d.window!!.decorView.findViewWithTag<EditText>("proj_value").setText("12,5")
        d.window!!.decorView.findViewWithTag<View>("proj_unit_2").performClick() // т
        d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertTrue(t1.plan.values.any { it.value == "12.5" && it.unit == "т" })
        s.view.invalidate()
        shot(s.view, "volumes-project.png")

        // Сводка по разделу: объёмы по таблицам и «всего по шифру».
        s.a.tableUi.summaryDialog(fr = s.b.frames[0])
        idle()
        val sv = texts(latest().window!!.decorView).joinToString("\n")
        assertTrue(sv, sv.contains("Несущие стены · бетон") && sv.contains("Ненесущие стены · опалубка"))
        assertTrue(sv, sv.contains("Всего по шифру КЖ 6.1.1 · бетон"))
        latest().dismiss()

        // Новая таблица в разделе: название подраздела; шифр — у раздела.
        root.findViewWithTag<View>("board_tool_table").performClick()
        tap(s.view, MotionEvent.TOOL_TYPE_STYLUS, s.sx(2600f), s.sy(400f))
        d = latest()
        d.window!!.decorView.findViewWithTag<EditText>("table_title").setText("Колонны")
        d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        latest().dismiss()
        val t3 = s.b.tables.last()
        assertEquals("КЖ 6.1.1 · Колонны", IdTables.fullTitle(s.b, t3))
        s.a.finish()
    }
}
