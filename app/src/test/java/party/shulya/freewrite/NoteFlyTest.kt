package party.shulya.freewrite

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
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
import java.time.Duration

/**
 * «Улетающие буквы» (0.27.8, Владимир: «эффект как на Samsung — написал слово, и буквы улетели в строчку; было бы круто,
 * если бы приложение не тупило от этого»). Распознавание в проверках подставное (testRecognizer).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w800dp-h1280dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NoteFlyTest {

    private val ctx get() = RuntimeEnvironment.getApplication()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun pause(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Before
    fun setUp() {
        Input.markStylus(ctx)
        Profile.save(ctx, "Владимир", "Шульмин")
    }

    @After
    fun tearDown() { NoteActivity.testRecognizer = null }

    private fun stroke(x0: Float, x1: Float, y0: Float, y1: Float): InkStroke = InkStroke(MotionEvent.TOOL_TYPE_STYLUS).apply {
        for (i in 0 until 30) points += InkPoint(x0 + (x1 - x0) * i / 29f, if (i % 2 == 0) y0 else y1, 1000L + i * 5, 0.5f)
    }

    /** Рамка того, что нарисовано в виде (непрозрачные точки), или null. */
    private fun inkBox(v: View): android.graphics.Rect? {
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        var l = Int.MAX_VALUE; var t = Int.MAX_VALUE; var r = -1; var b = -1
        for (y in 0 until bmp.height step 2) for (x in 0 until bmp.width step 2) if (Color.alpha(bmp.getPixel(x, y)) > 30) {
            if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y
        }
        return if (r < 0) null else android.graphics.Rect(l, t, r, b)
    }

    private fun shot(v: View, name: String) {
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        File("build/screenshots").mkdirs()
        File("build/screenshots/$name").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun wordFliesIntoTheLine() {
        val n = Note.create("Огонь")
        NoteStore(ctx).save(n)
        NoteActivity.testRecognizer = { s -> if (s[0].points[0].x < 220f) "огонь" else "горит" }
        val a = Robolectric.buildActivity(NoteActivity::class.java, Intent(ctx, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_ID, n.id)).setup().get()
        idle()
        val ink = NoteActivity::class.java.getDeclaredField("ink").apply { isAccessible = true }.get(a) as NoteInkView
        fun note() = a.model()
        val style = NoteInkView.StrokeStyle("write", Ui.INK, 5f)
        fun write(s: InkStroke) { a.onStrokeStart(s.points[0].x, s.points[0].y); a.onStrokeEnd(s, style, false) }
        // Слово крупно, ниже строки текста.
        write(stroke(100f, 140f, 900f, 980f))
        write(stroke(150f, 200f, 900f, 980f))
        pause(250) // серое слово у курсора
        val src = inkBox(ink.layer!!)!! // нераспознанные штрихи — на слое под пером
        // Начали следующее слово — «огонь» стало текстом и полетело в строку.
        write(stroke(260f, 300f, 900f, 980f))
        assertEquals("огонь", (note().blocks[0] as Block.Para).text)
        assertEquals(1, ink.flyingCount)
        // Кадр полёта — дёшево: рисуется только слой пера.
        var t = System.nanoTime()
        val bmp = Bitmap.createBitmap(ink.width, ink.height, Bitmap.Config.ARGB_8888)
        repeat(20) { ink.draw(Canvas(bmp)) }
        println("BENCH fly frame-ms=${"%.2f".format((System.nanoTime() - t) / 1e6 / 20)}")
        pause(170)
        shot(a.window.decorView, "note-fly-mid.png")
        val mid = inkBox(ink)
        pause(300)
        ink.draw(Canvas(bmp))
        assertEquals("долетели", 0, ink.flyingCount)
        // На полпути буквы выше, чем были (летят к строке наверху), и мельче.
        println("BENCH fly src=$src mid=$mid")
        assertTrue("летят вверх, к строке", mid != null && mid.top < src.top)
        assertTrue("и мельче", mid!!.height() < src.height())
        shot(a.window.decorView, "note-fly-done.png")
    }
}
