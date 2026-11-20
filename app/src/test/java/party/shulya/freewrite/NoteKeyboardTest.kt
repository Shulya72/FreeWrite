package party.shulya.freewrite

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
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

/**
 * «Клавиатура» в «Записи» (0.27.6, Владимир: «в ежедневнике исчезла возможность делать заметки через клавиатуру»).
 * С 0.27.9 (Владимир: «пишу в нижнем окошке, а думал, печататься будет сверху, где положено») поле ввода — сама строка с
 * курсором на листе; Enter — новая строка, поле переезжает в неё. «задача» и «строка» — плавающая панель.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w800dp-h1280dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NoteKeyboardTest {

    private val ctx get() = RuntimeEnvironment.getApplication()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Before
    fun setUp() {
        Input.markStylus(ctx)
        Profile.save(ctx, "Владимир", "Шульмин")
    }

    private fun shot(v: View, name: String) {
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        File("build/screenshots").mkdirs()
        File("build/screenshots/$name").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun sheetOf(a: NoteActivity) = NoteActivity::class.java.getDeclaredField("sheet").apply { isAccessible = true }.get(a) as RuledSheet
    private fun blockViews(a: NoteActivity) = NoteActivity::class.java.getDeclaredField("blockViews").apply { isAccessible = true }.get(a) as List<*>

    /** Поле ввода стоит на листе там, где строка с курсором (в строке задачи — внутри неё). */
    private fun assertFieldAtCaret(a: NoteActivity, edit: KeysEdit, n: Note) {
        val sheet = sheetOf(a)
        var v: View = edit
        while (v.parent !== sheet) v = v.parent as View
        assertEquals("поле — в строке с курсором", blockViews(a)[n.caret], v)
        // Других строк с этим текстом на листе нет — строка не дублируется.
        val same = ArrayList<String>()
        fun walk(x: View) {
            if (x is TextView && x !is KeysEdit && x.tag == RuledSheet.RULED && x.text.isNotEmpty() && x.text.toString() == edit.text.toString()) same += x.text.toString()
            if (x is android.view.ViewGroup) for (i in 0 until x.childCount) walk(x.getChildAt(i))
        }
        walk(sheet)
        assertEquals(emptyList<String>(), same)
    }

    private fun texts(n: Note) = n.blocks.map {
        when (it) { is Block.Para -> it.text; is Block.Task -> "☐ " + it.text; else -> "[картинка]" }
    }

    @Test
    fun typeNotesWithKeyboard() {
        val n = Note.create("Планёрка")
        NoteStore(ctx).save(n)
        val ctl = Robolectric.buildActivity(NoteActivity::class.java, Intent(ctx, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_ID, n.id)).setup()
        val a = ctl.get()
        idle()
        val root = a.window.decorView
        val note = { NoteActivity::class.java.getDeclaredField("note").apply { isAccessible = true }.get(a) as Note }
        val bar = root.findViewWithTag<View>("keys_bar")
        assertEquals("кнопки «задача»/«строка» спрятаны, пока не выбрали «Клавиатуру»", View.GONE, bar.visibility)
        assertEquals("поля ввода на экране нет", null, root.findViewWithTag<KeysEdit>("keys_edit"))

        root.findViewWithTag<View>("tool_keys").performClick(); idle()
        assertEquals(View.VISIBLE, bar.visibility)
        val edit = root.findViewWithTag<KeysEdit>("keys_edit")
        assertTrue("клавиатура — в строку с курсором", edit.hasFocus())
        assertFieldAtCaret(a, edit, note())
        // Внизу поля нет: поле на листе, выше середины экрана.
        val at = IntArray(2); edit.getLocationInWindow(at)
        assertTrue("поле — наверху, в строке листа", at[1] < root.height / 2)

        // Печатаем — текст сразу на листе.
        edit.append("Геодезия"); idle()
        edit.append(" — съёмка секции 3"); idle()
        assertEquals(listOf("Геодезия — съёмка секции 3"), texts(note()))
        // Enter на клавиатуре — новая строка; строка ввода — пустая, для неё.
        edit.append("\n"); idle()
        assertEquals(listOf("Геодезия — съёмка секции 3", ""), texts(note()))
        assertEquals("", edit.text.toString())
        assertFieldAtCaret(a, edit, note())
        assertTrue("поле переехало в новую строку с фокусом", edit.hasFocus())
        edit.append("Акты АОСР"); idle()
        // «задача» — строка с галочкой; Enter в задаче — следующая задача, Enter в пустой задаче — обычная строка.
        root.findViewWithTag<View>("keys_task").performClick(); idle()
        assertFieldAtCaret(a, edit, note()) // поле — внутри строки задачи, рядом с галочкой
        edit.append("\n"); idle()
        edit.append("Исполнительная съёмка"); idle()
        edit.append("\n"); idle()
        assertEquals("пустая задача", listOf("Геодезия — съёмка секции 3", "☐ Акты АОСР", "☐ Исполнительная съёмка", "☐ "), texts(note()))
        edit.append("\n"); idle()
        edit.append("Следующая планёрка — понедельник"); idle()
        assertEquals(listOf("Геодезия — съёмка секции 3", "☐ Акты АОСР", "☐ Исполнительная съёмка", "Следующая планёрка — понедельник"), texts(note()))
        shot(root, "note-keyboard.png")

        // Курсор поставили в середину первой строки — в строке ввода она целиком, курсор там же; вставили слово.
        val setCaret = NoteActivity::class.java.getDeclaredMethod("setCaret", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).apply { isAccessible = true }
        setCaret.invoke(a, 0, 8); idle()
        assertEquals("Геодезия — съёмка секции 3", edit.text.toString())
        assertEquals(8, edit.selectionStart)
        assertFieldAtCaret(a, edit, note())
        edit.text.insert(8, ","); idle()
        assertEquals("Геодезия, — съёмка секции 3", texts(note())[0])
        assertEquals(9, edit.selectionStart)
        // Enter посреди строки — делит её.
        edit.text.insert(9, "\n"); idle()
        assertEquals(listOf("Геодезия,", " — съёмка секции 3"), texts(note()).take(2))
        // «Стереть» в начале строки — соединить с верхней.
        edit.setSelection(0)
        edit.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)); idle()
        assertEquals("Геодезия, — съёмка секции 3", texts(note())[0])
        assertEquals("Геодезия, — съёмка секции 3", edit.text.toString())
        assertEquals(9, edit.selectionStart)

        // «Отменить» — строка ввода показывает то, что вернулось.
        val undo = NoteActivity::class.java.getDeclaredMethod("undo").apply { isAccessible = true }
        undo.invoke(a); idle()
        assertEquals(listOf("Геодезия,", " — съёмка секции 3"), texts(note()).take(2))
        assertEquals(note().blocks[note().caret].let { (it as Block.Para).text }, edit.text.toString())

        // Сохранилось; другой инструмент — строка ввода прячется.
        root.findViewWithTag<View>("tool_pen").performClick(); idle()
        assertEquals(View.GONE, bar.visibility)
        assertEquals("поле ушло с листа — снова обычные строки", null, edit.parent)
        ctl.pause()
        val saved = NoteStore(ctx).load(n.id)!!
        assertEquals(texts(note()), texts(saved))
        // На листе — те же строки.
        val sheetTexts = ArrayList<String>()
        fun walk(v: View) {
            if (v is TextView && v !is KeysEdit) sheetTexts += v.text.toString()
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        assertTrue(sheetTexts.any { it.startsWith("Следующая планёрка — понедельник") })
    }

    @Test
    fun cursorOnPictureTypesNewLineBelowIt() {
        val n = Note.create("Фото")
        n.blocks.clear()
        n.blocks += Block.Para("Секция 3")
        n.blocks += Block.Image("missing.jpg", 800, 600)
        n.caret = 1
        NoteStore(ctx).save(n)
        val a = Robolectric.buildActivity(NoteActivity::class.java, Intent(ctx, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_ID, n.id)).setup().get()
        idle()
        val root = a.window.decorView
        val note = { NoteActivity::class.java.getDeclaredField("note").apply { isAccessible = true }.get(a) as Note }
        root.findViewWithTag<View>("tool_keys").performClick(); idle()
        val edit = root.findViewWithTag<KeysEdit>("keys_edit")
        // Курсор на картинке — поле пустой строкой сразу под ней.
        val sheet = sheetOf(a)
        assertEquals(sheet.indexOfChild(blockViews(a)[1] as View) + 1, sheet.indexOfChild(edit))
        edit.append("Подпись к фото"); idle()
        assertEquals(listOf("Секция 3", "[картинка]", "Подпись к фото"), texts(note()))
        assertEquals(2, note().caret)
        assertFieldAtCaret(a, edit, note())
        assertEquals("Подпись к фото", edit.text.toString())
    }
}
