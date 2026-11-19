package party.shulya.freewrite

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.MotionEvent
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
import kotlin.math.sin

/**
 * Скорость письма в «Записи» (Владимир, 19.11: «начал писать ручкой, спустя несколько слов ручка начала тупить — ты уже
 * написал, а текст появляется потом»). Запись как после планёрки: 40 строк текста и 60 слов ручкой (по 6 штрихов в слове).
 * Печатает BENCH: конец штриха (снимок отмены + сохранение), кадр слоя пера, слово «В текст» (вставка + перерисовка листа).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w800dp-h1280dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NoteSpeedTest {

    private val ctx get() = RuntimeEnvironment.getApplication()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun ms(t0: Long) = (System.nanoTime() - t0) / 1e6

    @Before
    fun setUp() {
        Input.markStylus(ctx)
        Profile.save(ctx, "Владимир", "Шульмин")
    }

    /** Штрих буквы: ~70 точек с нажимом. */
    private fun letter(x0: Float, y0: Float, k: Int): Drawing {
        val n = 70
        val p = FloatArray(n * 3)
        for (i in 0 until n) {
            p[i * 3] = x0 + i * 0.6f
            p[i * 3 + 1] = y0 + 18f * sin(i * 0.25f + k).toFloat()
            p[i * 3 + 2] = 0.3f + 0.4f * ((i + k) % 7) / 7f
        }
        return Drawing("pen", Color.rgb(20, 40, 90), 5f, p)
    }

    private fun stroke(x0: Float, y0: Float): InkStroke = InkStroke(MotionEvent.TOOL_TYPE_STYLUS).apply {
        for (i in 0 until 70) points += InkPoint(x0 + i * 0.6f, y0 + 18f * sin(i * 0.25f).toFloat(), 1000L + i * 4, 0.5f)
    }

    @Test
    fun writingWithPenInLongNote() {
        val n = Note.create("Планёрка")
        n.blocks.clear()
        for (i in 0 until 40) n.blocks += if (i % 5 == 4) Block.Task("Задача номер $i по исполнительной", false) else Block.Para("Строка $i: геодезия, акты АОСР, исполнительная съёмка секции ${i % 7}")
        for (w in 0 until 60) for (k in 0 until 6) n.drawings += letter(60f + (w % 6) * 180f + k * 28f, 1700f + (w / 6) * 70f, k)
        n.caret = n.blocks.size - 1
        NoteStore(ctx).save(n)
        val a = Robolectric.buildActivity(NoteActivity::class.java, Intent(ctx, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_ID, n.id)).setup().get()
        idle()
        val cls = NoteActivity::class.java
        val ink = cls.getDeclaredField("ink").apply { isAccessible = true }.get(a) as NoteInkView
        val note = cls.getDeclaredField("note").apply { isAccessible = true }.get(a) as Note
        val pushUndo = cls.getDeclaredMethod("pushUndo").apply { isAccessible = true }
        val saveNow = cls.getDeclaredMethod("saveNow", Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        val insert = cls.getDeclaredMethod("insertAtCaret", String::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        val renderBlocks = cls.getDeclaredMethod("renderBlocks").apply { isAccessible = true }
        val points = note.drawings.sumOf { it.pts.size / 3 }
        println("BENCH note drawings=${note.drawings.size} points=$points blocks=${note.blocks.size}")

        fun avg(times: Int, f: () -> Unit): Double {
            repeat(3) { f() }
            val t = System.nanoTime()
            repeat(times) { f() }
            return ms(t) / times
        }
        val undo = avg(10) { pushUndo.invoke(a) }
        val save = avg(10) { saveNow.invoke(a, true) }
        val bmp = Bitmap.createBitmap(ink.width, ink.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val frame = avg(10) { ink.draw(c) }
        // Слой готовых рисунков (0.27.6) — перерисовывается, только когда рисунки поменялись или лист прокрутили.
        val layer = ink.layer
        val layerFrame = if (layer != null) avg(10) { layer.draw(c) } else 0.0
        // Конец штриха ручкой — как с пера (снимок отмены, рисунок в запись, сохранение через 0,4 с).
        a.findViewById<android.view.View>(android.R.id.content).findViewWithTag<android.view.View>("tool_pen").performClick()
        var x = 100f
        val end = avg(10) { a.onStrokeEnd(stroke(x, 1500f), ink.let { NoteInkView.StrokeStyle("pen", Color.BLUE, 5f) }, false); x += 30f }
        val t = System.nanoTime()
        idle() // отложенное сохранение
        val savedLater = ms(t)
        val word = avg(10) { insert.invoke(a, "слово", false) }
        val render = avg(10) { renderBlocks.invoke(a) }
        println(
            "BENCH note undo-snapshot ms=${"%.2f".format(undo)} save ms=${"%.2f".format(save)} ink-frame ms=${"%.2f".format(frame)} " +
                "layer-frame ms=${"%.2f".format(layerFrame)} pen-stroke-end ms=${"%.2f".format(end)} save-after-pause ms=${"%.1f".format(savedLater)} " +
                "word-in-text ms=${"%.2f".format(word)} render-sheet ms=${"%.2f".format(render)}",
        )
        // Сохранилось всё.
        saveNow.invoke(a, true)
        val back = NoteStore(ctx).load(n.id)!!
        assertEquals(note.drawings.size, back.drawings.size)
        assertEquals(note.blocks.size, back.blocks.size)
    }

    @Test
    fun layerShowsStrokesAndLinesUpdateInPlace() {
        val n = Note.create("Проверка")
        n.blocks.clear()
        n.blocks += Block.Para("Первая строка")
        n.blocks += Block.Task("Задача", false)
        n.blocks += Block.Para("Третья")
        n.caret = 2
        NoteStore(ctx).save(n)
        val a = Robolectric.buildActivity(NoteActivity::class.java, Intent(ctx, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_ID, n.id)).setup().get()
        idle()
        val cls = NoteActivity::class.java
        val ink = cls.getDeclaredField("ink").apply { isAccessible = true }.get(a) as NoteInkView
        fun note() = cls.getDeclaredField("note").apply { isAccessible = true }.get(a) as Note
        @Suppress("UNCHECKED_CAST")
        fun lines() = ArrayList(cls.getDeclaredField("lineViews").apply { isAccessible = true }.get(a) as List<android.widget.TextView?>)
        val insert = cls.getDeclaredMethod("insertAtCaret", String::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        val setCaret = cls.getDeclaredMethod("setCaret", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).apply { isAccessible = true }
        val undo = cls.getDeclaredMethod("undo").apply { isAccessible = true }

        // Штрих ручкой: готовый рисунок — на слое под пером, сам вид пера пустой (живого штриха нет).
        a.window.decorView.findViewWithTag<android.view.View>("tool_pen").performClick()
        a.onStrokeEnd(stroke(200f, 700f), NoteInkView.StrokeStyle("pen", Color.BLUE, 6f), false)
        assertEquals(1, note().drawings.size)
        fun inked(v: android.view.View): Int {
            val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            v.draw(Canvas(bmp))
            var k = 0
            for (y in 0 until bmp.height step 2) for (x in 0 until bmp.width step 2) if (android.graphics.Color.alpha(bmp.getPixel(x, y)) > 0) k++
            return k
        }
        assertTrue("рисунок на слое", inked(ink.layer!!) > 20)
        assertEquals("вид пера без живого штриха пуст", 0, inked(ink))

        // Слово в строку: строки не пересоздаются, текст — на месте.
        val before = lines()
        insert.invoke(a, "слово", false); idle()
        val after = lines()
        for (i in before.indices) assertTrue("строка $i — та же", before[i] === after[i])
        assertTrue(after[2]!!.text.toString().startsWith("Третья слово"))
        // Курсор в первую строку: у прежней строки курсора — чистый текст.
        setCaret.invoke(a, 0, -1); idle()
        assertEquals("Третья слово", lines()[2]!!.text.toString())
        assertTrue(lines()[0]!!.text.toString().contains("▏"))
        // Галочка задачи — лист собирается заново (другой вид строки).
        (note().blocks[1] as Block.Task).done = true
        cls.getDeclaredMethod("renderBlocks").apply { isAccessible = true }.invoke(a)
        assertTrue("задача — новая строка", lines()[1] !== after[1])

        // «Отменить»: слово, потом штрих.
        undo.invoke(a); idle()
        assertEquals("Третья", (note().blocks[2] as Block.Para).text)
        undo.invoke(a); idle()
        assertEquals(0, note().drawings.size)
        cls.getDeclaredMethod("redo").apply { isAccessible = true }.invoke(a); idle()
        assertEquals(1, note().drawings.size)

        // Сохранение: тот же файл читается, номер рисунка сохраняется.
        cls.getDeclaredMethod("saveNow", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(a, true)
        val back = NoteStore(ctx).load(n.id)!!
        assertEquals(1, back.drawings.size)
        assertEquals(note().drawings[0].id, back.drawings[0].id)
        assertEquals(note().drawings[0].pts.size, back.drawings[0].pts.size)
        assertEquals(note().blocks.size, back.blocks.size)
        assertEquals(org.json.JSONObject(note().toJsonString()).toString(), org.json.JSONObject(back.toJsonString()).toString())
    }
}
