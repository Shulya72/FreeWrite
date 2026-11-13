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
import android.widget.CheckBox
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
 * Шаг 3 ТЗ «Учёт ИГС» (0.22): захватки на плане. Заливку привязывают к захватке таблицы; касание заливки подсвечивает
 * строки захватки, касание номера захватки в таблице — её заливки (экран переезжает); номер пишется на заливке.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ZahvatkiTest {

    private val ctx get() = RuntimeEnvironment.getApplication()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun pause(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Before
    fun setUp() {
        Input.markStylus(ctx)
        Profile.save(ctx, "Владимир", "Шульмин")
    }

    /** Раздел «КЖ 6.1.1»: план (две заливки захваток 1 и 2 — разными цветами) и таблица «Несущие стены» (3 захватки). */
    private fun sample(): Board {
        val b = Board.create("ИГС")
        b.frames += BoardFrame(0f, 0f, 4200f, 2400f, "КЖ 6.1.1")
        b.fills += BoardFill(floatArrayOf(200f, 200f, 900f, 200f, 900f, 700f, 200f, 700f), Color.rgb(41, 121, 255))
        b.fills += BoardFill(floatArrayOf(950f, 200f, 1500f, 200f, 1500f, 700f, 950f, 700f), Color.rgb(46, 160, 67))
        val t = BoardTable(200f, 1000f, 1.5f, "Несущие стены")
        b.tables += t
        var o = 1.0
        for (z in listOf("1", "2", "3")) for (w in listOf("Опалубка", "Бетон")) b.recs += BoardRec(t.id, o++).apply {
            zah = z; con = "Вертикал"; work = w; igs = if (z == "1") IdTables.IGS_PTO else IdTables.IGS_WORK
        }
        return b
    }

    @Test
    fun modelJsonSyncExcel() {
        val b = sample()
        val t = b.tables[0]
        val f0 = b.fills[0]
        val before = f0.json()
        assertTrue("непривязанная заливка — та же строка, что до 0.22", !before.contains("\"lt\""))
        f0.lt = t.id; f0.lz = "1"
        b.fills[1].lt = t.id; b.fills[1].lz = "2"; b.fills[1].ln = false
        assertTrue(f0.json() != before && f0.json().contains("\"lz\":\"1\""))
        val back = Board.fromJson(JSONObject(b.toJsonString()))
        assertEquals(t.id, back.fills[0].lt); assertEquals("1", back.fills[0].lz); assertTrue(back.fills[0].ln)
        assertTrue(!back.fills[1].ln)
        assertEquals(listOf(f0), IdTables.fillsOf(b, t, "", "1"))
        assertEquals(2, IdTables.zahRows(b, t, "", "№1").size)
        assertEquals(listOf("" to "1", "" to "2", "" to "3"), IdTables.zahList(b, t))
        assertEquals(Color.rgb(41, 121, 255), IdTables.zahColors(b, t)["1"])
        // Копия заливки — та же захватка (несколько заливок у одной захватки); отмена — назад.
        assertEquals("1", f0.copy().lz)
        val snap = b.snapshot()
        f0.lz = "3"
        assertEquals("1", b.restored(snap).fills[0].lz)
        f0.lz = "1"
        // Обмен: привязка уходит коллегам вместе с заливкой.
        b.toJsonString(stampDevice = "dev1")
        val other = Board.fromJson(JSONObject(b.toJsonString()))
        other.fills[1].lz = "3"
        other.toJsonString(stampDevice = "dev2")
        Thread.sleep(5)
        BoardShare.merge(b, Board.fromJson(JSONObject(other.toJsonString())))
        assertEquals("3", b.fills[1].lz)
        // Excel: ячейка захватки — цветом её заливки.
        val out = ByteArrayOutputStream()
        IdExcel.write(b, IdExcel.allFilter(b), setOf(IdExcel.SHEET_ALL), out, "2026-11-11")
        val files = HashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { z -> while (true) { val e = z.nextEntry ?: break; files[e.name] = z.readBytes().toString(Charsets.UTF_8) } }
        val bg = Board.statusBg(Color.rgb(41, 121, 255))
        assertTrue(files["xl/styles.xml"]!!.contains(String.format("FF%02X%02X%02X", Color.red(bg), Color.green(bg), Color.blue(bg))))
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
    fun screenLinkFillsAndHighlightBothWays() {
        val s = open(sample())
        val root = s.a.window.decorView
        s.view.fitRect(0f, 0f, 4200f, 2400f)
        idle()
        pause(1000)
        root.findViewWithTag<View>("board_tool_hand").performClick()
        val t = s.b.tables[0]
        val f0 = s.b.fills[0]; val f1 = s.b.fills[1]

        // Палец по заливке — выделена; «Захватка» → таблица раздела одна, выбор номера «1» → привязана, номер на заливке.
        tap(s.view, MotionEvent.TOOL_TYPE_FINGER, s.sx(550f), s.sy(450f))
        assertEquals(listOf<Any>(f0), s.view.selection.toList())
        root.findViewWithTag<View>("sel_zah").performClick()
        idle()
        var d = latest()
        d.window!!.decorView.findViewWithTag<View>("zah_pick_0").performClick()
        assertTrue(d.window!!.decorView.findViewWithTag<CheckBox>("zah_label").isChecked)
        d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertEquals(t.id, f0.lt); assertEquals("1", f0.lz)
        // Выделенная привязанная заливка — строки захватки подсвечены в таблице, подпись на панели.
        assertEquals(IdTables.zahRows(s.b, t, "", "1").toSet(), s.view.markedRecs)
        s.view.clearSelection()
        idle()
        assertNull("выделение снято — подсветка тоже", s.view.markedRecs)

        // Вторая заливка — захватка «2» (вписали номер).
        tap(s.view, MotionEvent.TOOL_TYPE_FINGER, s.sx(1200f), s.sy(450f))
        root.findViewWithTag<View>("sel_zah").performClick()
        idle()
        d = latest()
        d.window!!.decorView.findViewWithTag<EditText>("zah_value").setText("2")
        d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertEquals("2", f1.lz)
        s.view.clearSelection()
        idle()
        s.view.invalidate()
        shot(s.view, "zahvatki-plan.png")
        // Значок цвета захватки у номера в таблице.
        assertEquals(f1.color, s.view.tableDraw.layout(s.b, t).swatch["2"])

        // Касание заливки — подсветка строк захватки (опалубка и бетон).
        tap(s.view, MotionEvent.TOOL_TYPE_FINGER, s.sx(1200f), s.sy(450f))
        assertEquals(IdTables.zahRows(s.b, t, "", "2").toSet(), s.view.markedRecs)
        assertTrue(texts(root).any { it.startsWith("Захватка 2 · Несущие стены") || it.startsWith("Захватка 2 · КЖ 6.1.1 · Несущие стены") })
        // «В таблице» — экран переезжает к строкам.
        root.findViewWithTag<View>("sel_zah_rows").performClick()
        idle()
        val rr = s.view.tableDraw.rowRect(s.b, t, IdTables.zahRows(s.b, t, "", "2")[0], RectF())!!
        assertTrue("строки на экране", s.sy(rr.centerY()) in 0f..s.view.height.toFloat())
        s.view.clearSelection()
        idle()

        // Касание номера захватки «2» в таблице (палец) — её заливка подсвечена и на экране; «← К таблице», «Готово».
        val row2 = IdTables.zahRows(s.b, t, "", "2")[0]
        val (zx, zy) = s.swatch(row2)
        tap(s.view, MotionEvent.TOOL_TYPE_FINGER, zx, zy)
        assertEquals(setOf(f1), s.view.markedFills)
        assertNull("карточка не открылась", s.a.tableUi.card)
        val fb = f1.bounds()
        assertTrue("заливка на экране", s.sx(fb.centerX()) in 0f..s.view.width.toFloat() && s.sy(fb.centerY()) in 0f..s.view.height.toFloat())
        assertNotNull(root.findViewWithTag<View>("zah_back"))
        shot(s.view, "zahvatki-from-table.png")
        root.findViewWithTag<View>("zah_done").performClick()
        idle()
        assertNull(s.view.markedFills)
        // Номер захватки без заливки («3») — сразу ввод номера (0.25.1), без карточки строки.
        val row3 = IdTables.zahRows(s.b, t, "", "3")[0]
        s.view.fitRect(0f, 0f, 4200f, 2400f); idle()
        val (z3x, z3y) = s.cell(row3, TableCol.ZAH)
        tap(s.view, MotionEvent.TOOL_TYPE_FINGER, z3x, z3y)
        assertNull(s.a.tableUi.card)
        val zd = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        assertEquals("3", zd.window!!.decorView.findViewWithTag<android.widget.EditText>("cell_text").text.toString())
        zd.dismiss(); idle()

        // Отвязать.
        tap(s.view, MotionEvent.TOOL_TYPE_FINGER, s.sx(550f), s.sy(450f))
        root.findViewWithTag<View>("sel_zah").performClick()
        idle()
        latest().getButton(android.app.AlertDialog.BUTTON_NEUTRAL).performClick()
        idle()
        assertEquals("", f0.lt)
        root.findViewWithTag<View>("board_undo").performClick()
        idle()
        assertEquals("1", s.b.fills[0].lz)
        s.a.finish()
    }

    private fun texts(v: View, out: MutableList<String> = ArrayList()): List<String> {
        if (v is android.widget.TextView) out += v.text.toString()
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) texts(v.getChildAt(i), out)
        return out
    }
}
