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
import android.widget.EditText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
 * 0.23.1 (Владимир): рамка раздела — каждая сторона тянется отдельно (а не только угол с масштабом); объём — касание
 * ячейки сразу ввод числа, без карточки строки.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FrameVolumeTest {

    private val ctx get() = RuntimeEnvironment.getApplication()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun pause(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Before
    fun setUp() {
        Input.markStylus(ctx)
        Profile.save(ctx, "Владимир", "Шульмин")
    }

    private fun event(down: Long, t: Long, action: Int, x: Float, y: Float, tool: Int): MotionEvent {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = tool })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 0.5f; size = 0.05f })
        val src = if (tool == MotionEvent.TOOL_TYPE_STYLUS) InputDevice.SOURCE_STYLUS else InputDevice.SOURCE_TOUCHSCREEN
        return MotionEvent.obtain(down, t, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, src, 0)
    }

    private fun tap(v: View, x: Float, y: Float) {
        val d = SystemClock.uptimeMillis()
        v.dispatchTouchEvent(event(d, d, MotionEvent.ACTION_DOWN, x, y, MotionEvent.TOOL_TYPE_FINGER))
        v.dispatchTouchEvent(event(d, d + 60, MotionEvent.ACTION_UP, x, y, MotionEvent.TOOL_TYPE_FINGER))
        idle()
    }

    private fun drag(v: View, x0: Float, y0: Float, x1: Float, y1: Float) {
        val d = SystemClock.uptimeMillis()
        v.dispatchTouchEvent(event(d, d, MotionEvent.ACTION_DOWN, x0, y0, MotionEvent.TOOL_TYPE_FINGER))
        for (i in 1..6) v.dispatchTouchEvent(event(d, d + i * 30L, MotionEvent.ACTION_MOVE, x0 + (x1 - x0) * i / 6, y0 + (y1 - y0) * i / 6, MotionEvent.TOOL_TYPE_FINGER))
        v.dispatchTouchEvent(event(d, d + 220, MotionEvent.ACTION_UP, x1, y1, MotionEvent.TOOL_TYPE_FINGER))
        idle()
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

    @Test
    fun frameSidesMoveSeparately() {
        val b = Board.create("Разделы")
        b.frames += BoardFrame(0f, 0f, 1000f, 600f, "КЖ 6.1.1")
        b.texts += BoardText(300f, 300f, 40f, "внутри", Ui.INK)
        val s = open(b)
        s.view.fitRect(-200f, -200f, 1400f, 900f); idle(); pause(600)
        val f = s.b.frames[0]
        s.view.select(listOf(f)); idle()
        val k = s.b.camZ
        // Правая сторона (середина) — вправо: шире, высота и левый край те же, надпись на месте.
        drag(s.view, s.sx(1000f), s.sy(300f), s.sx(1000f) + 150f, s.sy(300f) + 40f)
        assertEquals(0f, f.x, 0.5f); assertEquals(0f, f.y, 0.5f)
        assertEquals(1000f + 150f / k, f.w, 1f); assertEquals(600f, f.h, 0.5f)
        assertEquals(300f, s.b.texts[0].x, 0.01f)
        // Верхняя сторона — вниз: ниже верх, низ на месте.
        drag(s.view, s.sx(f.x + f.w / 2f), s.sy(0f), s.sx(f.x + f.w / 2f), s.sy(0f) + 100f)
        assertEquals(100f / k, f.y, 1f); assertEquals(600f, f.y + f.h, 1f)
        // Угол — обе стороны, без масштаба содержимого.
        drag(s.view, s.sx(f.x), s.sy(f.y + f.h), s.sx(f.x) - 80f, s.sy(f.y + f.h) + 60f)
        assertEquals(-80f / k, f.x, 1f); assertEquals(600f + 60f / k, f.y + f.h, 1f)
        assertEquals(40f, s.b.texts[0].size, 0.01f)
        // «Отменить» — по шагу.
        s.a.window.decorView.findViewWithTag<View>("board_undo").performClick(); idle()
        val g = s.a.board().frames[0]
        assertEquals(0f, g.x, 1f); assertEquals(600f, g.y + g.h, 1f)
        val bmp = Bitmap.createBitmap(s.view.width, s.view.height, Bitmap.Config.ARGB_8888)
        s.view.select(listOf(g)); idle()
        s.view.draw(Canvas(bmp))
        val dir = File("build/screenshots").apply { mkdirs() }
        FileOutputStream(File(dir, "frame-handles.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        s.a.finish()
    }

    @Test
    fun volumeCellTapEntersNumberDirectly() {
        val b = Board.create("Объём")
        val t = BoardTable(100f, 100f, 1.5f, "КЖ 6.1.1")
        b.tables += t
        b.recs += BoardRec(t.id, 1.0).apply { zah = "1"; con = "Стены"; work = "Бетон" }
        val s = open(b)
        val r = s.view.tableDraw.bounds(s.b, t, RectF())
        s.view.fitRect(r.left, r.top, r.right, r.bottom); idle(); pause(600)
        val rec = s.b.recs[0]
        val c = s.view.tableDraw.cellRect(s.b, t, rec, TableCol.VOL, RectF())!!
        tap(s.view, s.sx(c.centerX()), s.sy(c.centerY()))
        assertNull("карточка строки не открылась", s.a.tableUi.card)
        val d = ShadowAlertDialog.getLatestAlertDialog()
        d.window!!.decorView.findViewWithTag<EditText>("cell_text").setText("42,5")
        d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertEquals("42.5", s.a.board().recs[0].vol)
        s.a.finish()
    }
}
