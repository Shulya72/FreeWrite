package party.shulya.freewrite

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
import kotlin.math.abs

/**
 * Ластик «как в рисовалке» (0.27.6, Владимир: «провёл длинную линию, хочу стереть кусочек — удаляется вся; было бы хорошо,
 * если бы удалялся только участок, куда ластик попадает»).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PartialEraserTest {

    private val ctx get() = RuntimeEnvironment.getApplication()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Before
    fun setUp() {
        Input.markStylus(ctx)
        Profile.save(ctx, "Владимир", "Шульмин")
    }

    /** Горизонтальная линия от x0 до x1 на высоте y: точки через step, нажим растёт от 0,2 до 0,8. */
    private fun line(x0: Float, x1: Float, y: Float, step: Float, width: Float = 4f, color: Int = Color.BLUE): Drawing {
        val n = ((x1 - x0) / step).toInt() + 1
        val p = FloatArray(n * 3)
        for (i in 0 until n) { p[i * 3] = x0 + i * step; p[i * 3 + 1] = y; p[i * 3 + 2] = 0.2f + 0.6f * i / (n - 1) }
        return Drawing("pen", color, width, p)
    }

    private fun xs(d: Drawing) = (0 until d.pts.size / 3).map { d.pts[it * 3] }

    @Test
    fun cutsOnlyThePieceUnderTheEraser() {
        val d = line(0f, 1000f, 0f, 10f)
        // Посередине: два куска, разрез — ровно по краю ластика (радиус 20 + половина толщины 2).
        val res = InkEraser.cut(d, 500f, 0f, 20f)!!
        assertEquals(2, res.size)
        val (a, b) = res
        assertEquals(0f, xs(a).first(), 1e-3f); assertEquals(478f, xs(a).last(), 1e-3f)
        assertEquals(522f, xs(b).first(), 1e-3f); assertEquals(1000f, xs(b).last(), 1e-3f)
        // Вид, цвет, толщина — те же; нажим в точке разреза — между соседними точками; номера — новые.
        for (p in res) { assertEquals("pen", p.kind); assertEquals(Color.BLUE, p.color); assertEquals(4f, p.width, 0f); assertNotEquals(d.id, p.id) }
        assertEquals(0.2f + 0.6f * 47.8f / 100f, a.pts[a.pts.size - 1], 1e-3f)
        assertNotEquals(a.id, b.id)
        // Редкие точки (фигура из двух точек): разрез посреди отрезка.
        val two = Drawing("pen", Color.RED, 2f, floatArrayOf(0f, 0f, 0.5f, 1000f, 0f, 0.5f))
        val r2 = InkEraser.cut(two, 300f, 5f, 10f)!!
        assertEquals(2, r2.size)
        assertTrue(abs(xs(r2[0]).last() - 300f) in 9f..11f && abs(xs(r2[1]).first() - 300f) in 9f..11f)
        // У конца — линия просто короче; мимо — null; точка под ластиком — стёрта; ластик больше линии — стёрта целиком.
        val end = InkEraser.cut(d, 1000f, 0f, 20f)!!
        assertEquals(1, end.size); assertEquals(978f, xs(end[0]).last(), 1e-3f)
        assertNull(InkEraser.cut(d, 500f, 60f, 20f))
        assertEquals(0, InkEraser.cut(Drawing("pen", 0, 4f, floatArrayOf(5f, 5f, 0.5f)), 0f, 0f, 20f)!!.size)
        assertEquals(0, InkEraser.cut(line(0f, 30f, 0f, 10f), 15f, 0f, 40f)!!.size)
        // Огрызок у края ластика (короче трети толщины) не остаётся.
        val stub = InkEraser.cut(line(0f, 100f, 0f, 1f, width = 10f), 74f, 0f, 20f)!!
        assertEquals(1, stub.size) // справа осталось бы 100 − (74 + 25) = 1 < 3,5 — убрано
        // По списку: куски на месте исходного штриха, порядок рисования тот же.
        val other1 = line(0f, 100f, 300f, 10f); val other2 = line(0f, 100f, -300f, 10f)
        val list = InkEraser.eraseIn(listOf(other1, d, other2), 500f, 0f, 20f)!!
        assertEquals(4, list.size)
        assertTrue(list[0] === other1 && list[3] === other2)
        assertNull(InkEraser.eraseIn(listOf(other1, other2), 500f, 0f, 20f))
        // Куски сохраняются и читаются как обычные штрихи.
        val b0 = Board.create("ластик")
        b0.strokes.addAll(list)
        val back = Board.fromJson(JSONObject(b0.toJsonString()))
        assertEquals(4, back.strokes.size)
        assertEquals(478f, xs(back.strokes[1]).last(), 0.05f)
    }

    // ------------------------------------------------------------ на экране

    private fun ev(down: Long, t: Long, action: Int, x: Float, y: Float): MotionEvent {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 0.5f })
        return MotionEvent.obtain(down, t, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
    }

    private fun shot(v: View, name: String) {
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        File("build/screenshots").mkdirs()
        File("build/screenshots/$name").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun boardEraserCutsLongLineAndUndoesInOneStep() {
        val b0 = Board.create("Ластик")
        b0.strokes += line(100f, 1500f, 400f, 6f, width = 6f)
        b0.strokes += line(100f, 1500f, 600f, 6f, width = 6f, color = Color.rgb(200, 40, 40))
        BoardStore(ctx).save(b0)
        val a = Robolectric.buildActivity(BoardActivity::class.java, Intent(ctx, BoardActivity::class.java).putExtra(BoardActivity.EXTRA_ID, b0.id)).setup().get()
        idle()
        val view = BoardActivity::class.java.getDeclaredField("view").apply { isAccessible = true }.get(a) as BoardView
        val b = a.board()
        view.fitRect(0f, 200f, 1600f, 800f); idle()
        shot(view, "eraser-before.png")
        a.setTool(BoardActivity.Tool.ERASER, quiet = true)
        fun sx(x: Float) = (x - b.camX) * b.camZ
        fun sy(y: Float) = (y - b.camY) * b.camZ
        // Провели ластиком сверху вниз через обе линии посередине.
        val d = SystemClock.uptimeMillis()
        view.dispatchTouchEvent(ev(d, d, MotionEvent.ACTION_DOWN, sx(800f), sy(300f)))
        for (m in 1..30) view.dispatchTouchEvent(ev(d, d + m * 10L, MotionEvent.ACTION_MOVE, sx(800f), sy(300f + m * 13f)))
        view.dispatchTouchEvent(ev(d, d + 320, MotionEvent.ACTION_UP, sx(800f), sy(690f)))
        idle()
        assertEquals("каждая линия — на два куска", 4, b.strokes.size)
        val r = Ui.dpf(ctx, 14f) / b.camZ + 3f
        val blue = b.strokes.filter { it.color == Color.BLUE }.sortedBy { it.pts[0] }
        assertEquals(800f - r, xs(blue[0]).last(), 1.5f)
        assertEquals(800f + r, xs(blue[1]).first(), 1.5f)
        shot(view, "eraser-after.png")
        // Одно движение ластиком — один шаг «Отменить»: обе линии снова целые.
        a.undo(); idle()
        assertEquals(2, a.board().strokes.size)
        assertEquals(xs(b0.strokes[0]).last(), xs(a.board().strokes[0]).last(), 0.1f) // целая, до последней точки
    }

    @Test
    fun noteEraserCutsToo() {
        val note = Note.create("Ластик")
        note.drawings += line(50f, 700f, 300f, 5f)
        NoteStore(ctx).save(note)
        val a = Robolectric.buildActivity(NoteActivity::class.java, Intent(ctx, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_ID, note.id)).setup().get()
        idle()
        // Движение ластиком по середине линии: начало и два шага.
        a.onErase(370f, 280f, true); a.onErase(370f, 300f, false); a.onErase(372f, 320f, false)
        assertEquals(2, a.drawings().size)
        // Один шаг «Отменить» на всё движение.
        NoteActivity::class.java.getDeclaredMethod("undo").apply { isAccessible = true }.invoke(a)
        assertEquals(1, a.drawings().size)
    }
}
