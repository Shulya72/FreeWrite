package party.shulya.freewrite

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.io.File
import java.io.FileOutputStream
import java.time.Duration

/**
 * Заметка как на айфоне (0.31, Владимир прислал видео с экрана айфона): весь текст — одно поле, название — первая строка,
 * Enter продолжает чек-лист и список, в пустом пункте — снимает его, «Стереть» в начале пункта — снимает список, иначе
 * склеивает строки; оформление выделенного — на панели над клавиатурой; перо пишет в текст у курсора; рисунки едут вместе
 * с текстом; картинки — между кусками текста.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w800dp-h1280dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NoteEditorTest {

    private val ctx get() = RuntimeEnvironment.getApplication()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun pause(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Before
    fun setUp() {
        Input.markStylus(ctx)
        Profile.save(ctx, "Владимир", "Шульмин")
        ctx.getSharedPreferences("notes", 0).edit().clear().commit()
    }

    @After
    fun tearDown() { NoteActivity.testRecognizer = null }

    private fun shot(v: View, name: String) {
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        File("build/screenshots").mkdirs()
        File("build/screenshots/$name").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun field(a: NoteActivity, name: String): Any? = NoteActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(a)
    private fun note(a: NoteActivity): Note { a.plainText(); return field(a, "note") as Note }
    private fun click(a: NoteActivity, tag: String) { a.window.decorView.findViewWithTag<View>(tag)!!.performClick(); idle() }

    /** Печать, как с клавиатуры: вставка у курсора (выделенное заменяется). */
    private fun type(e: NoteEdit, s: String) {
        val a = minOf(e.selectionStart, e.selectionEnd); val b = maxOf(e.selectionStart, e.selectionEnd)
        e.text.replace(a, b, s)
        idle()
    }

    private fun lines(e: NoteEdit) = e.lines().map { (if (it.attr.task) (if (it.attr.done) "☑ " else "☐ ") else "") + it.text + (if (it.attr.style.isNotEmpty() && !it.attr.task) " [${it.attr.style}]" else "") + (if (it.attr.indent > 0) " >${it.attr.indent}" else "") }

    private fun open(n: Note, keys: Boolean = false): NoteActivity {
        NoteStore(ctx).save(n)
        val i = Intent(ctx, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_ID, n.id)
        if (keys) i.putExtra(NoteActivity.EXTRA_KEYS, true)
        val a = Robolectric.buildActivity(NoteActivity::class.java, i).setup().get()
        idle()
        return a
    }

    private fun tap(e: NoteEdit, x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        for (act in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val ev = MotionEvent.obtain(t, t + 40, act, x, y, 0)
            e.dispatchTouchEvent(ev)
            ev.recycle()
        }
        idle()
    }

    // ---------------------------------------------------------------- набор как на айфоне

    @Test
    fun typeTitleChecklistListsLikeIphone() {
        val a = open(Note.create("").apply { paper = NotePaper.NONE }, keys = true)
        val root = a.window.decorView
        val e = a.editors().single()
        assertEquals("новая заметка — только название, курсор в нём", "", e.text.toString())
        assertEquals("Название", e.hint.toString())
        assertEquals(View.VISIBLE, root.findViewWithTag<View>("keys_bar").visibility)
        assertEquals(View.GONE, root.findViewWithTag<View>("pen_bar").visibility)
        assertEquals(View.VISIBLE, root.findViewWithTag<View>("note_done").visibility)

        type(e, "Привет")
        type(e, "\n")
        type(e, "Как дела?")
        assertEquals(listOf("Привет [title]", "Как дела?"), lines(e))
        assertEquals("Привет", note(a).title)

        // Выделили «дела» — панель сама показывает Ж/К/Ч/З; «B» — жирный.
        val s = e.text.indexOf("дела")
        e.setSelection(s, s + 4); idle()
        click(a, "bar_b")
        assertTrue((note(a).blocks[0] as Block.Para).spans.contains(TextSpan(4, 8, Rich.B)))
        // Цвет текста (маркер айфона): «Лиловый».
        click(a, "bar_color")
        a.lastMenu!!.menu.performIdentifierAction(1, 0)
        idle()
        assertTrue((note(a).blocks[0] as Block.Para).spans.any { it.kind == Rich.FC && it.value == NoteActivity.PEN_COLORS[0] })

        // Чек-лист: Enter продолжает, в пустом пункте — снимает.
        e.setSelection(e.text.length); idle()
        type(e, "\n")
        click(a, "keys_task")
        type(e, "Купить молоко")
        type(e, "\n")
        type(e, "Хлеб")
        type(e, "\n")
        assertEquals("☐ ", lines(e)[4])
        type(e, "\n")
        assertEquals("Enter в пустом пункте — пункт снят, строка не добавилась",
            listOf("Привет [title]", "Как дела?", "☐ Купить молоко", "☐ Хлеб", ""), lines(e))

        // Касание кружка — отмечено.
        val l = e.layout
        val top = l.getLineTop(l.getLineForOffset(e.rawOffset(2, 0)))
        tap(e, Ui.dpf(ctx, 12f), top + Ui.dpf(ctx, 12f))
        assertEquals("☑ Купить молоко", lines(e)[2])
        assertTrue((note(a).blocks[1] as Block.Task).done)

        // Список «Aa → • Список»: Enter — следующий пункт; «Стереть» в начале пункта — снимает список, потом склеивает.
        type(e, "Повестка")
        click(a, "fmt_open")
        click(a, "fmt_bullet")
        type(e, "\n")
        type(e, "Акты")
        assertEquals(listOf("Повестка [bullet]", "Акты [bullet]"), lines(e).takeLast(2))
        e.setSelection(e.rawOffset(5, 0)); idle()
        assertTrue(e.specialBackspace()); idle()
        assertEquals("Акты", lines(e)[5])
        assertTrue(e.specialBackspace()); idle()
        assertEquals("ПовесткаАкты [bullet]", lines(e).last())
        click(a, "note_undo")
        val e2 = a.editors().single()
        assertEquals("«Отменить» — строки снова раздельно", listOf("Повестка [bullet]", "Акты"), lines(e2).takeLast(2))
        click(a, "fmt_close")
        shot(root, "note-iphone-keys.png")

        // Нумерованный список: номера по порядку.
        e2.setSelection(e2.text.length); idle()
        type(e2, "\n")
        click(a, "bar_list")
        a.lastMenu!!.menu.performIdentifierAction(3, 0); idle()
        type(e2, "Первое\nВторое\nТретье")
        assertEquals(listOf("Первое [num]", "Второе [num]", "Третье [num]"), lines(e2).takeLast(3))
        // Строку выше/ниже (как на айфоне).
        click(a, "bar_up")
        assertEquals(listOf("Первое [num]", "Третье [num]", "Второе [num]"), lines(a.editors().single()).takeLast(3))
        shot(root, "note-iphone-lists.png")
        // Та же заметка в линейку: каждая строка — на линии.
        a.paperDialog(); idle()
        a.paperDlg!!.findViewById<View>(android.R.id.custom).findViewWithTag<View>("paper_lines").performClick(); idle()
        a.paperDlg!!.dismiss(); idle()
        shot(root, "note-iphone-lined.png")
        val ed = a.editors().single()
        val cell = Ui.dp(ctx, 36f)
        val ll = ed.layout
        for (k in 0 until ll.lineCount) assertEquals("строка $k — в клетку", if (k == 0) 2 * cell else cell, ll.getLineBottom(k) - ll.getLineTop(k))

        // Ушли и вернулись — всё на месте.
        val saved = note(a)
        NoteActivity::class.java.getDeclaredMethod("saveNow", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(a, true)
        val back = NoteStore(ctx).load(saved.id)!!
        assertEquals("Привет", back.title)
        assertTrue(back.blocks.any { it is Block.Task && it.text == "Купить молоко" && it.done })
        assertTrue(back.blocks.any { it is Block.Para && it.text == "Третье" && it.style == ParaStyle.NUM })
    }

    @Test
    fun indentEnterAndTitleStaysFirst() {
        val n = Note.create("Планёрка")
        n.blocks.clear()
        n.blocks += Block.Para("пункт", ParaStyle.BULLET, 1)
        val a = open(n, keys = true)
        val e = a.editors().single()
        assertEquals(listOf("Планёрка [title]", "пункт [bullet] >1"), lines(e))
        e.setSelection(e.text.length); idle()
        type(e, "\n")
        assertEquals("пустой вложенный пункт", "[bullet] >1", lines(e)[2].trim())
        type(e, "\n")
        assertEquals("Enter в пустом вложенном пункте — сначала отступ меньше", " [bullet]", lines(e)[2])
        type(e, "\n")
        assertEquals("ещё раз — обычная строка", "", lines(e)[2])
        // В название списком не превратить.
        e.setSelection(2); idle()
        click(a, "fmt_open")
        click(a, "fmt_bullet")
        assertEquals("Планёрка [title]", lines(e)[0])
    }

    // ---------------------------------------------------------------- перо и рисунки

    private fun stroke(x0: Float, x1: Float, y0: Float, y1: Float): InkStroke = InkStroke(MotionEvent.TOOL_TYPE_STYLUS).apply {
        for (i in 0 until 30) points += InkPoint(x0 + (x1 - x0) * i / 29f, if (i % 2 == 0) y0 else y1, 1000L + i * 5, 0.5f)
    }

    @Test
    fun penWritesAtCaretAndDrawingsMoveWithText() {
        val n = Note.create("Огонь")
        n.blocks.clear()
        n.blocks += Block.Para("строка один")
        n.drawings += Drawing("pen", Ui.PEN_INK, 3f, floatArrayOf(100f, 700f, 0.5f, 300f, 760f, 0.5f))
        NoteActivity.testRecognizer = { s -> if (s[0].points[0].x < 400f) "горит" else "ярко" }
        val a = open(n)
        val root = a.window.decorView
        assertEquals("с пером — панель пера", View.VISIBLE, root.findViewWithTag<View>("pen_bar").visibility)
        val e = a.editors().single()
        e.setSelection(e.text.length); idle()
        val style = NoteInkView.StrokeStyle("write", Ui.INK, 5f)
        fun write(s: InkStroke) { a.onStrokeStart(s.points[0].x, s.points[0].y); a.onStrokeEnd(s, style, false) }
        write(stroke(100f, 160f, 1000f, 1080f))
        pause(250)
        assertNotNull("серое слово у курсора", a.interimMark())
        assertEquals("горит", a.interimMark()!!.text.trim())
        write(stroke(500f, 560f, 1000f, 1080f))
        assertEquals("строка один горит", note(a).blocks[0].let { (it as Block.Para).text })
        pause(1200)
        assertEquals("строка один горит ярко", (note(a).blocks[0] as Block.Para).text)

        // Дописали две строки выше рисунка — рисунок уехал вниз на их высоту.
        val y0 = note(a).drawings[0].bounds().top
        val h0 = e.height
        setKeys(a)
        type(e, "\nвторая\nтретья")
        idle()
        val dy = note(a).drawings[0].bounds().top - y0
        assertTrue("рисунок сдвинулся на высоту строк: $dy (поле стало выше на ${e.height - h0})", dy > Ui.dpf(ctx, 40f))
        click(a, "note_undo")
        assertEquals("«Отменить» — рисунок на месте", y0, note(a).drawings[0].bounds().top, 0.5f)
        shot(root, "note-iphone-pen.png")
    }

    private fun setKeys(a: NoteActivity) { click(a, "tool_keys") }

    // ---------------------------------------------------------------- картинки

    private fun photo(name: String) {
        val bmp = Bitmap.createBitmap(640, 440, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(android.graphics.Color.rgb(157, 185, 214))
        val dir = File(ctx.filesDir, "note_images").apply { mkdirs() }
        FileOutputStream(File(dir, name)).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
    }

    @Test
    fun imagesBetweenTextAndBackspaceRemovesImage() {
        photo("ed-photo.jpg")
        val n = Note.create("Фото")
        n.blocks.clear()
        n.blocks += Block.Para("до")
        n.blocks += Block.Image("ed-photo.jpg", 640, 440)
        val a = open(n, keys = true)
        val eds = a.editors()
        assertEquals("текст, картинка, пустая строка под ней", 2, eds.size)
        assertEquals(listOf("Фото [title]", "до"), lines(eds[0]))
        // Под картинкой печатают — это строка записи после картинки.
        eds[1].requestFocus(); eds[1].setSelection(eds[1].text.length); idle()
        type(eds[1], "после")
        val bl = note(a).blocks
        assertEquals(3, bl.size)
        assertTrue(bl[1] is Block.Image)
        assertEquals("после", (bl[2] as Block.Para).text)
        // «Стереть» в начале строки под картинкой: раз — картинка выбрана, два — удалена, куски текста склеились.
        eds[1].setSelection(eds[1].rawOffset(0, 0)); idle()
        assertTrue(eds[1].specialBackspace()); idle()
        assertEquals(View.VISIBLE, a.window.decorView.findViewWithTag<View>("image_bar").visibility)
        eds[1].specialBackspace(); idle()
        assertEquals(1, a.editors().size)
        assertEquals(listOf("Фото [title]", "до", "после"), lines(a.editors()[0]))
        click(a, "note_undo")
        assertEquals(2, a.editors().size)
        assertTrue(note(a).blocks.any { it is Block.Image })
    }

    @Test
    fun emptyNewNoteIsDroppedOnBack() {
        val n = Note.create("")
        val a = open(n, keys = true)
        assertTrue(a.isEmptyNote())
        a.onBackPressed(); idle()
        assertFalse(File(ctx.filesDir, "notes/${n.id}.json").exists())
    }
}
