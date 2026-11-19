package party.shulya.freewrite

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import org.junit.After
import org.junit.Assert.assertEquals
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
import kotlin.math.sin

/**
 * «Выделить» → «В текст» в «Записи» (0.27.7, Владимир: «есть подчеркушки, которые я написал ручкой, — выделить и
 * преобразовать в напечатанный текст, как в приложении, с которого начали»). ML Kit в проверках заменён (testRecognizer).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w800dp-h1280dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NoteSelectTest {

    private val ctx get() = RuntimeEnvironment.getApplication()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Before
    fun setUp() {
        Input.markStylus(ctx)
        Profile.save(ctx, "Владимир", "Шульмин")
    }

    @After
    fun tearDown() { NoteActivity.testRecognizer = null }

    private fun word(x0: Float, y0: Float): Drawing {
        val p = FloatArray(60 * 3) { i -> when (i % 3) { 0 -> x0 + (i / 3) * 2f; 1 -> y0 + 14f * sin((i / 3) * 0.5f); else -> 0.5f } }
        return Drawing("pen", Color.rgb(20, 40, 90), 5f, p)
    }

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
    fun lassoHandwritingThenToText() {
        val n = Note.create("Планёрка")
        n.blocks.clear(); n.blocks += Block.Para("Повестка")
        n.caret = 0
        // Две строки ручкой (по 3 слова) и рисунок в стороне.
        for (w in 0 until 3) n.drawings += word(120f + w * 150f, 700f)
        for (w in 0 until 3) n.drawings += word(120f + w * 150f, 790f)
        n.drawings += word(1200f, 1300f)
        NoteStore(ctx).save(n)
        NoteActivity.testRecognizer = { strokes ->
            val y = strokes[0].points[0].y
            if (y < 750f) "Позвонить геодезистам" else "Акты на армирование"
        }
        val a = Robolectric.buildActivity(NoteActivity::class.java, Intent(ctx, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_ID, n.id)).setup().get()
        idle()
        val root = a.window.decorView
        val ink = NoteActivity::class.java.getDeclaredField("ink").apply { isAccessible = true }.get(a) as NoteInkView
        fun note() = NoteActivity::class.java.getDeclaredField("note").apply { isAccessible = true }.get(a) as Note
        root.findViewWithTag<View>("tool_select").performClick(); idle()
        // Обвести пером обе строки (рисунок в стороне — нет).
        val o = a.contentOffset()
        val loop = listOf(80f to 650f, 600f to 650f, 600f to 840f, 80f to 840f, 80f to 655f)
        val d = android.os.SystemClock.uptimeMillis()
        ink.dispatchTouchEvent(ev(d, d, MotionEvent.ACTION_DOWN, loop[0].first + o.x, loop[0].second + o.y))
        var t = d
        for (k in 1 until loop.size) {
            val (x0, y0) = loop[k - 1]; val (x1, y1) = loop[k]
            for (s in 1..10) { t += 8; ink.dispatchTouchEvent(ev(d, t, MotionEvent.ACTION_MOVE, x0 + (x1 - x0) * s / 10f + o.x, y0 + (y1 - y0) * s / 10f + o.y)) }
        }
        ink.dispatchTouchEvent(ev(d, t + 8, MotionEvent.ACTION_UP, loop.last().first + o.x, loop.last().second + o.y))
        idle()
        val bar = root.findViewWithTag<View>("sel_bar")
        assertEquals(View.VISIBLE, bar.visibility)
        val sel = NoteActivity::class.java.getDeclaredField("selection").apply { isAccessible = true }.get(a) as List<*>
        assertEquals("обе строки, без рисунка в стороне", 6, sel.size)
        shot(root, "note-select.png")
        // «В текст»: каждая строка — строкой текста ниже курсора; штрихи строк убраны, рисунок в стороне остался.
        root.findViewWithTag<View>("sel_totext").performClick(); idle()
        val texts = note().blocks.map { (it as? Block.Para)?.text }
        assertEquals(listOf("Повестка", "Позвонить геодезистам", "Акты на армирование"), texts)
        assertEquals(1, note().drawings.size)
        assertEquals(View.GONE, bar.visibility)
        shot(root, "note-select-text.png")
        // «Отменить» — как было.
        NoteActivity::class.java.getDeclaredMethod("undo").apply { isAccessible = true }.invoke(a); idle()
        assertEquals(7, note().drawings.size)
        assertEquals(listOf("Повестка"), note().blocks.map { (it as? Block.Para)?.text })
        // Удалить выделенное: обвести рисунок в стороне — «Удалить».
        val d2 = android.os.SystemClock.uptimeMillis() + 2000
        val l2 = listOf(1150f to 1250f, 1400f to 1250f, 1400f to 1350f, 1150f to 1350f, 1150f to 1255f)
        ink.dispatchTouchEvent(ev(d2, d2, MotionEvent.ACTION_DOWN, l2[0].first + o.x, l2[0].second + o.y))
        var t2 = d2
        for (k in 1 until l2.size) for (s in 1..10) {
            t2 += 8
            ink.dispatchTouchEvent(ev(d2, t2, MotionEvent.ACTION_MOVE, l2[k - 1].first + (l2[k].first - l2[k - 1].first) * s / 10f + o.x,
                l2[k - 1].second + (l2[k].second - l2[k - 1].second) * s / 10f + o.y))
        }
        ink.dispatchTouchEvent(ev(d2, t2 + 8, MotionEvent.ACTION_UP, l2.last().first + o.x, l2.last().second + o.y))
        idle()
        root.findViewWithTag<View>("sel_delete").performClick(); idle()
        assertEquals(6, note().drawings.size)
    }
}
