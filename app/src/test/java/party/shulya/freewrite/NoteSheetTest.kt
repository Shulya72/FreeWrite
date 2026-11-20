package party.shulya.freewrite

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.StyleSpan
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.TextView
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

/**
 * Новый лист заметки (0.30, по макету ViaDesk): название — первой строкой, «Aa» (стили, Ж/К/Ч/З, маркер, цвет, списки,
 * цитата, отступ, шрифт), чек-листы, разлиновка по клеткам (без разметки / линейка / клетка), новая заметка — сразу с
 * клавиатурой, перо при клавиатуре — снова письмо, старые записи: рисунки — за своими строками.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w800dp-h1280dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NoteSheetTest {

    private val ctx get() = RuntimeEnvironment.getApplication()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Before
    fun setUp() {
        Input.markStylus(ctx)
        Profile.save(ctx, "Владимир", "Шульмин")
        ctx.getSharedPreferences("notes", 0).edit().clear().commit()
    }

    private fun shot(v: View, name: String) {
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        File("build/screenshots").mkdirs()
        File("build/screenshots/$name").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun field(a: NoteActivity, name: String): Any? = NoteActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(a)
    private fun note(a: NoteActivity) = a.model()
    private fun sheet(a: NoteActivity) = field(a, "sheet") as RuledSheet

    // ---------------------------------------------------------------- модель

    @Test
    fun spansFollowTextEdits() {
        val sp = mutableListOf(TextSpan(0, 5, Rich.B))
        Rich.adjust(sp, "Hello", "Hello!")
        assertEquals("дописанное в конце — тем же жирным", listOf(TextSpan(0, 6, Rich.B)), sp)
        Rich.adjust(sp, "Hello!", ">Hello!")
        assertEquals("вставка перед куском — кусок сдвигается", listOf(TextSpan(1, 7, Rich.B)), sp)
        Rich.adjust(sp, ">Hello!", ">Helo!")
        assertEquals(listOf(TextSpan(1, 6, Rich.B)), sp)
        Rich.adjust(sp, ">Helo!", ">")
        assertTrue("стёрли весь кусок — оформления нет", sp.isEmpty())

        // Ж: включить, ещё раз — снять; маркер — один цвет на кусок; соседние куски склеиваются.
        val t = "Планёрка на объекте"
        val s2 = ArrayList<TextSpan>()
        assertTrue(Rich.toggle(s2, Rich.B, 0, 8, t.length))
        assertFalse(Rich.toggle(s2, Rich.B, 0, 8, t.length))
        assertTrue(s2.isEmpty())
        Rich.set(s2, Rich.HL, 0, 8, t.length, 111)
        Rich.set(s2, Rich.HL, 3, 11, t.length, 222)
        assertEquals(listOf(TextSpan(0, 3, Rich.HL, 111), TextSpan(3, 11, Rich.HL, 222)), s2)
        Rich.set(s2, Rich.B, 0, 4, t.length); Rich.set(s2, Rich.B, 4, 8, t.length)
        assertTrue(TextSpan(0, 8, Rich.B) in s2)
        assertEquals(0 until 8, Rich.wordAt(t, 3))
        assertEquals("курсор сразу за словом — это слово", 0 until 8, Rich.wordAt(t, 8))
        assertEquals("без слова — вся строка", 0 until 13, Rich.wordAt("Планёрка — на", 10))
        // Разрез и склейка строк.
        val cut = Rich.slice(listOf(TextSpan(2, 10, Rich.I)), 5, 12)
        assertEquals(listOf(TextSpan(0, 5, Rich.I)), cut)
    }

    @Test
    fun styleIndentSpansAndPaperInFile() {
        val n = Note.create("Планёрка")
        n.blocks.clear()
        n.blocks += Block.Para("Обычная строка")
        n.blocks += Block.Para("Повестка", ParaStyle.H1)
        n.blocks += Block.Para("пункт", ParaStyle.BULLET, 1, mutableListOf(TextSpan(0, 5, Rich.B), TextSpan(1, 3, Rich.HL, NoteActivity.HIGHLIGHTS[0])))
        n.blocks += Block.Task("акты", false, 2, mutableListOf(TextSpan(0, 4, Rich.FC, NoteActivity.TEXT_COLORS[1])))
        n.paper = NotePaper.GRID
        val text = n.toJsonString()
        assertTrue("обычная строка — как до 0.30: $text", "{\"t\":\"p\",\"text\":\"Обычная строка\"}" in text)
        assertEquals(JSONObject(text).toString(), n.toJson().toString())
        val back = Note.fromJson(JSONObject(text))
        assertEquals(NotePaper.GRID, back.paper)
        assertEquals(2, back.layoutV)
        val b2 = back.blocks[2] as Block.Para
        assertEquals(ParaStyle.BULLET, b2.style); assertEquals(1, b2.indent)
        assertEquals(n.blocks[2].let { (it as Block.Para).spans }, b2.spans)
        val t3 = back.blocks[3] as Block.Task
        assertEquals(2, t3.indent); assertEquals(NoteActivity.TEXT_COLORS[1], t3.spans[0].value)
        assertEquals("записали → прочитали → записали: то же", text, back.toJsonString())
        // Запись до 0.30: без стилей, в линейку, раскладка 1.
        val old = Note.fromJson(JSONObject("""{"v":1,"id":"o1","title":"Старая","created":1,"updated":1,"caret":0,"blocks":[{"t":"p","text":"x"}],"drawings":[]}"""))
        assertEquals(NotePaper.LINES, old.paper); assertEquals(1, old.layoutV)
        assertEquals(ParaStyle.BODY, (old.blocks[0] as Block.Para).style)
    }

    // ---------------------------------------------------------------- лист

    @Test
    fun formatPanelListsAndCells() {
        val n = Note.create("Планёрка на объекте")
        NoteStore(ctx).save(n)
        val ctl = Robolectric.buildActivity(NoteActivity::class.java, Intent(ctx, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_ID, n.id)).setup()
        val a = ctl.get()
        idle()
        val root = a.window.decorView
        // Название — первой строкой листа, в две клетки.
        val title = a.editors()[0]
        assertEquals("Планёрка на объекте", title.paraText(0))
        val cell = Ui.dp(a, 36f)
        assertEquals(2 * cell, title.layout.getLineBottom(0) - title.layout.getLineTop(0))
        assertEquals(View.VISIBLE, root.findViewWithTag<View>("pen_bar").visibility)
        assertEquals(View.GONE, root.findViewWithTag<View>("keys_bar").visibility)

        root.findViewWithTag<View>("tool_keys").performClick(); idle()
        assertEquals(View.VISIBLE, root.findViewWithTag<View>("keys_bar").visibility)
        assertEquals(View.GONE, root.findViewWithTag<View>("pen_bar").visibility)
        val edit = a.editors()[0]
        edit.append("Повестка"); idle()
        // «Aa» → «Заголовок»: строка с курсором — заголовком; Enter — дальше обычная строка.
        root.findViewWithTag<View>("fmt_open").performClick(); idle()
        assertEquals(View.VISIBLE, root.findViewWithTag<View>("fmt_panel").visibility)
        root.findViewWithTag<View>("fmt_h1").performClick(); idle()
        assertEquals(ParaStyle.H1, (note(a).blocks[0] as Block.Para).style)
        edit.append("\n"); idle()
        assertEquals(ParaStyle.BODY, (note(a).blocks[1] as Block.Para).style)
        edit.append("Геодезия и съёмка"); idle()
        // Выделили слово — «Ж» и маркер.
        val w0 = edit.rawOffset(2, 0); val w1 = edit.rawOffset(2, 8)
        edit.setSelection(w0, w1); idle()
        root.findViewWithTag<View>("fmt_b").performClick(); idle()
        root.findViewWithTag<View>("fmt_hl_0").performClick(); idle()
        val l1 = note(a).blocks[1] as Block.Para
        assertTrue(l1.spans.toString(), TextSpan(0, 8, Rich.B) in l1.spans)
        assertTrue(TextSpan(0, 8, Rich.HL, NoteActivity.HIGHLIGHTS[0]) in l1.spans)
        val shown = edit.text as Spanned
        assertTrue("в поле — жирный", shown.getSpans(w0, w1, StyleSpan::class.java).isNotEmpty())
        assertTrue("и маркер", shown.getSpans(w0, w1, BackgroundColorSpan::class.java).isNotEmpty())
        assertEquals("выделение осталось — можно жать «Aa» дальше", w1, edit.selectionEnd)
        // Дописали — жирный не расползается за выделенное (дописано после пробела, курсор в конце).
        edit.setSelection(edit.length()); edit.append(" секции 3"); idle()
        assertEquals("Геодезия и съёмка секции 3", (note(a).blocks[1] as Block.Para).text)
        assertTrue(TextSpan(0, 8, Rich.B) in (note(a).blocks[1] as Block.Para).spans)

        // Списки: «•» — пункт, Enter — следующий пункт, Enter в пустом — выход из списка; отступ.
        edit.append("\n"); idle()
        root.findViewWithTag<View>("fmt_bullet").performClick(); idle()
        edit.append("Опалубка"); idle()
        edit.append("\n"); idle()
        assertEquals(ParaStyle.BULLET, (note(a).blocks[3] as Block.Para).style)
        edit.append("Бетон"); idle()
        root.findViewWithTag<View>("fmt_indent").performClick(); idle()
        assertEquals(1, (note(a).blocks[3] as Block.Para).indent)
        edit.append("\n"); idle()
        edit.append("\n"); idle()
        assertEquals("Enter в пустом вложенном пункте — сначала отступ меньше", 0, (note(a).blocks[4] as Block.Para).indent)
        assertEquals(ParaStyle.BULLET, (note(a).blocks[4] as Block.Para).style)
        edit.append("\n"); idle()
        assertEquals("ещё раз — обычная строка", ParaStyle.BODY, (note(a).blocks[4] as Block.Para).style)
        root.findViewWithTag<View>("fmt_num").performClick(); idle()
        edit.append("Первое"); idle(); edit.append("\n"); idle(); edit.append("Второе"); idle()
        root.findViewWithTag<View>("fmt_check").performClick(); idle()
        assertTrue(note(a).blocks[5] is Block.Task)
        // Списки на листе.
        val styles = edit.paras.map { it.style }
        assertTrue(styles.toString(), ParaStyle.BULLET in styles && ParaStyle.NUM in styles)
        // Текстом для «Поделиться».
        val txt = a.plainText()
        assertTrue(txt, txt.startsWith("Планёрка на объекте\n\nПовестка"))
        assertTrue(txt, "• Опалубка" in txt && "    • Бетон" in txt && "1. Первое" in txt && "☐ Второе" in txt)

        // Лист — по клеткам: каждый кусок и каждая строка в нём начинаются на линии клетки.
        val sh = sheet(a)
        for (i in 0 until sh.childCount) {
            val c = sh.getChildAt(i)
            if (c.visibility != View.GONE) assertEquals("кусок $i на линии клетки", 0, (c.top - sh.paddingTop) % cell)
        }
        for (k in 0 until edit.layout.lineCount) assertEquals("строка $k на линии клетки", 0, edit.layout.getLineTop(k) % cell)
        shot(root, "note-sheet-format.png")
        root.findViewWithTag<View>("fmt_close").performClick(); idle()

        // Разлиновка: в клетку, для всех новых.
        a.paperDialog(); idle()
        val dlg = a.paperDlg!!
        dlg.window!!.decorView.findViewWithTag<View>("paper_grid").performClick(); idle()
        dlg.window!!.decorView.findViewWithTag<android.widget.CheckBox>("paper_all").isChecked = true
        dlg.dismiss(); idle()
        assertEquals(NotePaper.GRID, note(a).paper)
        assertEquals(NotePaper.GRID, sh.paper)
        assertEquals(NotePaper.GRID, NotePaper.default(ctx))
        root.findViewWithTag<View>("keys_hide").performClick(); idle()
        shot(root, "note-sheet-grid.png")

        // «Отменить» — оформление тоже.
        val undo = NoteActivity::class.java.getDeclaredMethod("undo").apply { isAccessible = true }
        undo.invoke(a); idle()
        assertTrue("отменили чек-лист", note(a).blocks[5] is Block.Para)

        // Перо коснулось листа, пока печатают, — снова письмо от руки.
        root.findViewWithTag<View>("tool_keys").performClick(); idle()
        val ink = field(a, "ink") as NoteInkView
        val t = SystemClock.uptimeMillis()
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { x = 300f; y = 900f; pressure = 0.5f })
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 1, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
        ink.dispatchTouchEvent(down)
        val up = MotionEvent.obtain(t, t + 20, MotionEvent.ACTION_UP, 1, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
        ink.dispatchTouchEvent(up); idle()
        assertEquals(View.VISIBLE, root.findViewWithTag<View>("pen_bar").visibility)
        assertEquals(View.GONE, root.findViewWithTag<View>("keys_bar").visibility)
        ctl.pause()
        val saved = NoteStore(ctx).load(n.id)!!
        assertEquals(NotePaper.GRID, saved.paper)
        assertEquals(ParaStyle.H1, (saved.blocks[0] as Block.Para).style)
    }

    @Test
    fun newNoteOpensWithKeyboardAndEmptyOneIsNotKept() {
        val store = NoteStore(ctx)
        val n = Note.create("").also { store.save(it) }
        val ctl = Robolectric.buildActivity(NoteActivity::class.java,
            Intent(ctx, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_ID, n.id).putExtra(NoteActivity.EXTRA_KEYS, true)).setup()
        val a = ctl.get()
        idle()
        val root = a.window.decorView
        val title = a.editors()[0]
        assertTrue("курсор в названии", title.hasFocus() && title.caretAt().first == 0)
        assertEquals(View.VISIBLE, root.findViewWithTag<View>("keys_bar").visibility)
        // Ушли, ничего не написав, — заметки нет.
        @Suppress("DEPRECATION") a.onBackPressed(); idle()
        ctl.pause()
        assertNull(store.load(n.id))

        // Вторая: название, Enter — к первой строке, текст.
        val m = Note.create("").also { store.save(it) }
        val c2 = Robolectric.buildActivity(NoteActivity::class.java,
            Intent(ctx, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_ID, m.id).putExtra(NoteActivity.EXTRA_KEYS, true)).setup()
        val b = c2.get()
        idle()
        val t2 = b.editors()[0]
        t2.append("Звонок заказчику"); idle()
        t2.append("\n"); idle()
        assertEquals("после Enter — в первой строке", 1, t2.caretAt().first)
        t2.append("Перенести приёмку на пятницу"); idle()
        @Suppress("DEPRECATION") b.onBackPressed(); idle()
        c2.pause()
        val saved = store.load(m.id)!!
        assertEquals("Звонок заказчику", saved.title)
        assertEquals("Перенести приёмку на пятницу", (saved.blocks[0] as Block.Para).text)
    }

    @Test
    fun oldNoteDrawingsFollowTheirLines() {
        val store = NoteStore(ctx)
        // Запись до 0.30 и рисунок у второй строки — на её старом месте (как его рисовали в 0.29).
        val oldTitle = Ui.text(ctx, "Старая запись", 26f, Ui.INK, bold = true).apply { setPadding(0, 0, 0, Ui.dp(ctx, 2f)) }
        val oldMeta = Ui.mono(ctx, Ui.noteMeta(1_700_000_000_000L), 12f).apply { setPadding(0, 0, 0, Ui.dp(ctx, 10f)) }
        val line = Ui.dp(ctx, 36f)
        val w = Ui.dp(ctx, 800f - 24f - 56f - 36f)
        fun h(v: View): Int { v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)); return v.measuredHeight }
        val padTop = Ui.dp(ctx, 18f)
        val oldTop1 = padTop + h(oldTitle) + h(oldMeta) + line
        val y0 = oldTop1 + Ui.dp(ctx, 10f)
        val json = """{"v":1,"id":"old1","title":"Старая запись","created":1700000000000,"updated":1700000000000,"caret":1,
            "blocks":[{"t":"p","text":"Первая строка"},{"t":"p","text":"Вторая строка — схема справа"}],
            "drawings":[{"kind":"pen","color":-16777216,"w":3.0,"pts":[400,$y0,0.5,460,${y0 + 8},0.5]}]}"""
        store.write("old1", json)
        val ctl = Robolectric.buildActivity(NoteActivity::class.java, Intent(ctx, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_ID, "old1")).setup()
        val a = ctl.get()
        idle()
        val e = a.editors()[0]
        val top1 = e.top + e.totalPaddingTop + e.layout.getLineTop(e.layout.getLineForOffset(e.paraStart(2)))
        val d = note(a).drawings[0]
        assertEquals("рисунок — на том же месте у своей строки", (y0 - oldTop1).toFloat(), d.pts[1] - top1, 1.5f)
        ctl.pause()
        val saved = store.load("old1")!!
        assertEquals(2, saved.layoutV)
        assertEquals(d.pts[1], saved.drawings[0].pts[1], 0.11f)
        // Второй раз — не сдвигается.
        val c2 = Robolectric.buildActivity(NoteActivity::class.java, Intent(ctx, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_ID, "old1")).setup()
        idle()
        assertEquals(saved.drawings[0].pts[1], note(c2.get()).drawings[0].pts[1], 0.01f)
        shot(c2.get().window.decorView, "note-sheet-old.png")
    }

    @Test
    @Config(qualifiers = "w360dp-h780dp-port-xxhdpi")
    fun phoneBarsAndFormatFit() {
        val n = Note.create("Звонок заказчику")
        n.blocks.clear()
        n.blocks += Block.Para("Повестка", ParaStyle.H2)
        n.blocks += Block.Para("Перенести приёмку захватки 3 на пятницу", ParaStyle.BULLET, 0, mutableListOf(TextSpan(10, 17, Rich.B)))
        n.blocks += Block.Task("Акты на армирование", false)
        n.blocks += Block.Para("")
        n.caret = 3
        NoteStore(ctx).save(n)
        val a = Robolectric.buildActivity(NoteActivity::class.java, Intent(ctx, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_ID, n.id)).setup().get()
        idle()
        val root = a.window.decorView
        root.findViewWithTag<View>("tool_keys").performClick(); idle()
        root.findViewWithTag<View>("fmt_open").performClick(); idle()
        val panel = root.findViewWithTag<View>("fmt_panel")
        assertTrue("окно «Формат» — по ширине телефона", panel.width <= root.width)
        val dot = root.findViewWithTag<View>("fmt_hl_0")
        assertTrue("кружки маркера видны", dot.width > 0 && dot.height > 0)
        shot(root, "note-sheet-phone-format.png")
        root.findViewWithTag<View>("fmt_close").performClick(); idle()
        shot(root, "note-sheet-phone.png")
    }
}
