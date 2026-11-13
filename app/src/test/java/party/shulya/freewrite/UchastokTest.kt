package party.shulya.freewrite

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import org.json.JSONObject
import org.junit.Assert.assertEquals
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
import org.robolectric.shadows.ShadowAlertDialog
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.time.Duration
import java.util.zip.ZipInputStream

/**
 * Участок (0.22.1, Владимир): «шифр КЖ8 — лестничные марши, участок LM1, LM2, LM3, захватка — этаж, тип конструкции —
 * марш или площадка, вид работ — опалубка, бетон». Порядок столбцов: № АОСР, № схемы, Участок, Захватка, Тип конструкции,
 * Вид работ… «3 этаж» участка LM1 и «3 этаж» участка LM2 — разные захватки (строки, заливки, ведомости).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UchastokTest {

    private val ctx get() = RuntimeEnvironment.getApplication()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun pause(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Before
    fun setUp() {
        Input.markStylus(ctx)
        Profile.save(ctx, "Владимир", "Шульмин")
    }

    /** Раздел «КЖ8», таблица «Лестничные марши»: LM1 и LM2, по этажу 3, марш и площадка, опалубка и бетон. Заливка на плане. */
    private fun stairs(): Board {
        val b = Board.create("Лестницы")
        b.frames += BoardFrame(0f, 0f, 4200f, 2600f, "КЖ8")
        b.fills += BoardFill(floatArrayOf(200f, 200f, 900f, 200f, 900f, 700f, 200f, 700f), Color.rgb(255, 152, 0))
        val t = BoardTable(200f, 1000f, 1.5f, "Лестничные марши")
        b.tables += t
        var o = 1.0
        for (u in listOf("LM1", "LM2")) for (con in listOf("Марш", "Площадка межэтажная")) for (w in listOf("Опалубка", "Бетон"))
            b.recs += BoardRec(t.id, o++).apply { uch = u; zah = "3 этаж"; this.con = con; work = w; igs = IdTables.IGS_WORK }
        return b
    }

    @Test
    fun stairsModelFillsVedomExcel() {
        // Порядок и названия столбцов по умолчанию.
        assertEquals(listOf("aosr", "nsch", "uch", "zah", "con", "work", "vol"), TableCol.defaults().take(7).map { it.k })
        assertEquals("Участок", TableCol.defaultName(TableCol.UCH))
        assertEquals("Тип конструкции", TableCol.defaultName(TableCol.CON))

        val b = stairs()
        val t = b.tables[0]
        assertEquals("КЖ8 · Лестничные марши", IdTables.fullTitle(b, t))
        // Одинаковый этаж у разных участков — разные захватки.
        assertEquals(4, IdTables.zahRows(b, t, "LM1", "3 этаж").size)
        assertEquals(4, IdTables.zahRows(b, t, "lm2", "3 Этаж").size)
        assertEquals(listOf("LM1" to "3 этаж", "LM2" to "3 этаж"), IdTables.zahList(b, t))
        assertEquals(listOf("LM1", "LM2"), IdTables.uchList(b, t))
        assertTrue(b.recs[0].key() != b.recs[4].key())
        assertEquals("LM1", IdTables.cellText(b, b.recs[0], TableCol.UCH))

        // JSON: участок — «u», только если есть (строка схемы без участка та же, что до 0.22.1).
        val r0 = b.recs[0]
        assertTrue(IdTables.recJson(r0).contains("\"u\":\"LM1\""))
        val plain = BoardRec(t.id, 99.0).apply { zah = "1"; work = "Бетон" }
        assertTrue(!IdTables.recJson(plain).contains("\"u\""))
        // Заливка захватки LM2 · 3 этаж: «lu» — только у привязанной с участком.
        val f = b.fills[0]
        f.lt = t.id; f.lz = "3 этаж"
        assertTrue(!f.json().contains("\"lu\""))
        f.lu = "LM2"
        assertTrue(f.json().contains("\"lu\":\"LM2\""))
        assertEquals("LM2 · 3 этаж", f.label())
        assertEquals(listOf(f), IdTables.fillsOf(b, t, "LM2", "3 этаж"))
        assertTrue(IdTables.fillsOf(b, t, "LM1", "3 этаж").isEmpty())
        assertEquals(f.color, IdTables.zahColors(b, t)[b.recs[4].zkey()])
        assertEquals("LM2", f.copy().lu)
        val back = Board.fromJson(JSONObject(b.toJsonString()))
        assertEquals("LM1", back.recs[0].uch)
        assertEquals("LM2", back.fills[0].lu)
        assertEquals(b.recs.map { IdTables.recJson(it) }, back.recs.map { IdTables.recJson(it) })
        // Отмена.
        val snap = b.snapshot()
        r0.uch = "LM3"; f.lu = ""
        val rb = b.restored(snap)
        assertEquals("LM1", rb.recs[0].uch); assertEquals("LM2", rb.fills[0].lu)
        r0.uch = "LM1"; f.lu = "LM2"
        // Список столбцов из 0.22.0 (без участка) — участок встаёт после № схемы.
        val old = JSONObject(IdTables.tableJson(t)).put("cols", org.json.JSONArray().apply {
            for (k in listOf("aosr", "nsch", "zah", "con", "work", "vol", "date", "ck", "dwg", "igs", "dcust", "dpto", "who", "note")) put(JSONObject().put("k", k))
        })
        assertEquals(listOf("aosr", "nsch", "uch", "zah", "con"), IdTables.parseTable(old).cols.take(5).map { it.k })
        // Обмен: участок, исправленный коллегой, приходит.
        b.toJsonString(stampDevice = "dev1")
        val other = Board.fromJson(JSONObject(b.toJsonString()))
        other.recs[1].uch = "LM3"
        other.toJsonString(stampDevice = "dev2")
        Thread.sleep(5)
        BoardShare.merge(b, Board.fromJson(JSONObject(other.toJsonString())))
        assertEquals("LM3", b.recs[1].uch)
        b.recs[1].uch = "LM1"

        // Ведомость: подпись с участком; участок запоминается (строку удалили — в архиве виден).
        val v = BoardVedom(3000f, 200f, 1.5f, Vedoms.CUST, "З-13")
        b.vedoms += v
        Vedoms.add(b, v, b.recs.filter { it.uch == "LM2" }, "Владимир Шульмин")
        val it0 = Vedoms.items(b, v)[0]
        assertEquals("КЖ8 · Лестничные марши · LM2 · захв. 3 этаж · Марш · Опалубка", Vedoms.labelText(b, it0))
        Vedoms.remember(b, it0)
        assertEquals("LM2", it0.lu)
        assertTrue(Vedoms.itemJson(it0).contains("\"lu\":\"LM2\""))
        assertEquals("LM2", Vedoms.parseItem(JSONObject(Vedoms.itemJson(it0))).lu)
        val gone = b.recs.first { it.id == it0.r }
        b.recs.remove(gone)
        assertEquals("LM2", Vedoms.label(b, it0).uch)
        b.recs += gone

        // Excel: опись ведомости — столбец «Участок»; лист схем — тоже.
        val out = ByteArrayOutputStream()
        IdExcel.writeVedom(b, v, out, "2026-11-12")
        val sh = unzip(out)["xl/worksheets/sheet1.xml"]!!
        assertTrue(sh.contains("Участок") && sh.contains("Тип конструкции") && sh.contains("LM2"))
        val out2 = ByteArrayOutputStream()
        IdExcel.write(b, IdExcel.allFilter(b), setOf(IdExcel.SHEET_ALL), out2, "2026-11-12")
        val sh2 = unzip(out2).filterKeys { it.startsWith("xl/worksheets/") }.values.joinToString()
        assertTrue(sh2.contains("Участок") && sh2.contains("LM1"))
    }

    private fun unzip(out: ByteArrayOutputStream): Map<String, String> {
        val files = HashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { z -> while (true) { val e = z.nextEntry ?: break; files[e.name] = z.readBytes().toString(Charsets.UTF_8) } }
        return files
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
        /** Цветной квадратик у номера захватки (0.25.1: квадратик — план/заливка, сам номер — ввод). */
        fun swatch(r: BoardRec): Pair<Float, Float> {
            val t = IdTables.tableOf(b, r)!!
            val c = view.tableDraw.cellRect(b, t, r, TableCol.ZAH, RectF())!!
            return sx(c.left + (TableDraw.PAD + TableDraw.SWATCH / 2f) * t.scale) to sy(c.centerY())
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

    @Test
    fun screenNextFloorKeepsUchastokAndFillLinksToIt() {
        val s = open(stairs())
        val root = s.a.window.decorView
        s.view.fitRect(0f, 0f, 4200f, 2600f)
        idle()
        pause(1000)
        val t = s.b.tables[0]

        // «+ следующая захватка»: участок — как у последней строки (LM2), этаж +1, те же виды работ.
        s.a.tableUi.onTap(t, TableHit(TableHit.ADD_NEXT, null, null), pen = true)
        idle()
        var d = latest()
        assertEquals("LM2", d.window!!.decorView.findViewWithTag<EditText>("add_uch").text.toString())
        assertEquals("4 этаж", d.window!!.decorView.findViewWithTag<EditText>("add_zah").text.toString())
        d.window!!.decorView.findViewWithTag<EditText>("add_con").setText("Марш")
        d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        val added = IdTables.zahRows(s.b, t, "LM2", "4 этаж")
        assertEquals(listOf("Опалубка", "Бетон"), added.map { it.work })
        assertTrue(added.all { it.con == "Марш" })
        // Такая же строка, но другой участок — не повтор.
        s.a.tableUi.onTap(t, TableHit(TableHit.ADD, null, null), pen = true)
        idle()
        d = latest()
        d.window!!.decorView.findViewWithTag<EditText>("add_uch").setText("LM3")
        d.window!!.decorView.findViewWithTag<EditText>("add_zah").setText("4 этаж")
        d.window!!.decorView.findViewWithTag<EditText>("add_con").setText("Марш")
        d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertEquals(2, IdTables.zahRows(s.b, t, "LM3", "4 этаж").size)
        assertEquals(listOf("LM1", "LM2", "LM3"), IdTables.uchList(s.b, t))

        // Заливка → «Захватка»: есть поле участка; выбор «LM2 · 3 этаж» → привязана к участку.
        s.view.fitRect(0f, 0f, 4200f, 2600f); idle()
        pause(1000)
        root.findViewWithTag<View>("board_tool_hand").performClick()
        tap(s.view, MotionEvent.TOOL_TYPE_FINGER, s.sx(550f), s.sy(450f))
        val f = s.b.fills[0]
        assertEquals(listOf<Any>(f), s.view.selection.toList())
        root.findViewWithTag<View>("sel_zah").performClick()
        idle()
        d = latest()
        assertNotNull(d.window!!.decorView.findViewWithTag<EditText>("zah_uch"))
        d.window!!.decorView.findViewWithTag<View>("zah_pick_1").performClick()
        assertEquals("LM2", d.window!!.decorView.findViewWithTag<EditText>("zah_uch").text.toString())
        d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertEquals("LM2", f.lu); assertEquals("3 этаж", f.lz)
        assertEquals(IdTables.zahRows(s.b, t, "LM2", "3 этаж").toSet(), s.view.markedRecs)
        s.view.clearSelection()
        idle()

        // Касание этажа LM2 в таблице — его заливка; этаж LM1 (без заливки) — карточка строки.
        val rLm2 = IdTables.zahRows(s.b, t, "LM2", "3 этаж")[0]
        val (zx, zy) = s.swatch(rLm2)
        tap(s.view, MotionEvent.TOOL_TYPE_FINGER, zx, zy)
        assertEquals(setOf(f), s.view.markedFills)
        root.findViewWithTag<View>("zah_done").performClick()
        idle()
        s.view.fitRect(0f, 0f, 4200f, 2600f); idle()
        pause(1000)
        val rLm1 = IdTables.zahRows(s.b, t, "LM1", "3 этаж")[0]
        val (z1x, z1y) = s.cell(rLm1, TableCol.ZAH)
        tap(s.view, MotionEvent.TOOL_TYPE_FINGER, z1x, z1y)
        assertNull("сразу ввод номера, без карточки", s.a.tableUi.card)
        org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().dismiss(); idle()
        s.view.invalidate()
        shot(s.view, "table-uchastok.png")
        s.a.finish()
    }
}
