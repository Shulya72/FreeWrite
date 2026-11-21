package party.shulya.freewrite

import android.content.Intent
import android.os.Looper
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

/**
 * Длинный абзац (0.32.1, Владимир прислал снимок после диктовки): у каждой следующей строки абзаца промежуток был на 20 %
 * больше прошлой — высота строки считалась от чисел, уже изменённых предыдущей строкой. Теперь все строки абзаца одной высоты.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w674dp-h1078dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NoteLongParaTest {
    private val ctx get() = RuntimeEnvironment.getApplication()
    @Before fun setUp() { Input.markStylus(ctx) }

    private val long = "Хм. Короче, все четыре схемы, они как бы нарисованы. Ты в курсе? Кто? Бобурышев. Дак, с Бобрышевым я и " +
        "общаюсь по этому поводу. Он же тогда мне написал письмо, якобы жду ИГС от геодезистов. ГС я направил. Я с ним вот " +
        "вчера разговаривал. Он говорит, что этот должны уже типа, там на финальной стадии, грубо говоря, согласования."

    private fun heights(n: Note, paper: String): List<Int> {
        n.paper = paper
        NoteStore(ctx).save(n)
        val a = Robolectric.buildActivity(NoteActivity::class.java, Intent(ctx, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_ID, n.id)).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val l = a.editors().first().layout
        return (0 until l.lineCount).map { l.getLineBottom(it) - l.getLineTop(it) }
    }

    @Test
    fun wrappedLinesOfOneParagraphHaveOneHeight() {
        // Обычный текст: строка 0 — название, дальше абзац на несколько строк.
        val body = heights(Note.create("Заметка").apply { blocks.clear(); blocks += Block.Para(long) }, NotePaper.NONE)
        assertTrue("абзац переносится на несколько строк: $body", body.size >= 5)
        val lines = body.drop(1)
        assertTrue("строки абзаца одной высоты: $body", lines.all { it == lines[0] })
        assertTrue("промежуток — около 1,2 высоты букв, а не растёт: $body", lines[0] < body[0])
        // Длинное название — тоже ровно.
        val title = heights(Note.create(long).apply { blocks.clear(); blocks += Block.Para("") }, NotePaper.NONE)
        val t = title.dropLast(1)
        assertTrue("строки названия одной высоты: $title", t.size >= 3 && t.all { it == t[0] })
    }
}
