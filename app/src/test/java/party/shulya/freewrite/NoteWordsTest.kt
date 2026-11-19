package party.shulya.freewrite

import android.content.Intent
import android.os.Looper
import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * «В текст» в «Записи»: слово становится текстом, как только начали следующее (0.27.7, Владимир: «написал слово, отрываю
 * ручку — как пробел — и пишу следующее; логично, чтобы предыдущее сразу исчезало и становилось текстом»).
 * В проверках ML Kit не работает — нераспознанное слово остаётся рисунком: по числу рисунков видно, что слово «ушло».
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w800dp-h1280dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NoteWordsTest {

    private val ctx get() = RuntimeEnvironment.getApplication()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Before
    fun setUp() {
        Input.markStylus(ctx)
        Profile.save(ctx, "Владимир", "Шульмин")
    }

    /** Штрих буквы в прямоугольнике (x0..x1, y0..y1). */
    private fun stroke(x0: Float, x1: Float, y0: Float, y1: Float): InkStroke = InkStroke(MotionEvent.TOOL_TYPE_STYLUS).apply {
        for (i in 0 until 20) points += InkPoint(x0 + (x1 - x0) * i / 19f, if (i % 2 == 0) y0 else y1, 1000L + i * 5, 0.5f)
    }

    @Test
    fun previousWordGoesAsSoonAsNextStarts() {
        val n = Note.create("Слова")
        NoteStore(ctx).save(n)
        val a = Robolectric.buildActivity(NoteActivity::class.java, Intent(ctx, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_ID, n.id)).setup().get()
        idle()
        val style = NoteInkView.StrokeStyle("write", Ui.INK, 5f)
        fun write(s: InkStroke) {
            a.onStrokeStart(s.points[0].x, s.points[0].y)
            a.onStrokeEnd(s, style, false)
        }
        // Слово «огонь»: буквы через небольшие промежутки (меньше 0,45 высоты), высота 40.
        write(stroke(100f, 130f, 300f, 340f))
        write(stroke(136f, 166f, 300f, 340f))
        write(stroke(172f, 202f, 300f, 340f))
        assertEquals("слово ещё пишется", 0, a.drawings().size)
        // Точка над буквой (выше слова, но не на строку) — то же слово.
        write(stroke(180f, 184f, 282f, 286f))
        assertEquals(0, a.drawings().size)
        // Следующее слово — правее с промежутком в букву: прошлое сразу ушло (здесь — рисунком, ML Kit в проверках нет).
        write(stroke(232f, 262f, 300f, 340f))
        assertEquals("прошлое слово ушло, как только начали следующее", 4, a.drawings().size)
        write(stroke(268f, 298f, 300f, 340f))
        assertEquals(4, a.drawings().size)
        // Новая строка ниже — тоже новое слово.
        write(stroke(100f, 130f, 380f, 420f))
        assertEquals(6, a.drawings().size)
        // Последнее слово — после паузы, как раньше.
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1200))
        assertEquals(7, a.drawings().size)
    }
}
