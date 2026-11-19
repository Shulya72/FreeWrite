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
 * Строка ввода внизу — как строка формул в Excel: в ней текст строки с курсором, правка сразу на листе; Enter — новая строка.
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
        assertEquals("строка ввода спрятана, пока не выбрали «Клавиатуру»", View.GONE, bar.visibility)

        root.findViewWithTag<View>("tool_keys").performClick(); idle()
        assertEquals(View.VISIBLE, bar.visibility)
        val edit = root.findViewWithTag<KeysEdit>("keys_edit")
        assertTrue("клавиатура — в строку ввода", edit.hasFocus())

        // Печатаем — текст сразу на листе.
        edit.append("Геодезия"); idle()
        edit.append(" — съёмка секции 3"); idle()
        assertEquals(listOf("Геодезия — съёмка секции 3"), texts(note()))
        // Enter на клавиатуре — новая строка; строка ввода — пустая, для неё.
        edit.append("\n"); idle()
        assertEquals(listOf("Геодезия — съёмка секции 3", ""), texts(note()))
        assertEquals("", edit.text.toString())
        edit.append("Акты АОСР"); idle()
        // «задача» — строка с галочкой; Enter в задаче — следующая задача, Enter в пустой задаче — обычная строка.
        root.findViewWithTag<View>("keys_task").performClick(); idle()
        edit.append("\n"); idle()
        edit.append("Исполнительная съёмка"); idle()
        root.findViewWithTag<View>("keys_enter").performClick(); idle()
        assertEquals("пустая задача", listOf("Геодезия — съёмка секции 3", "☐ Акты АОСР", "☐ Исполнительная съёмка", "☐ "), texts(note()))
        root.findViewWithTag<View>("keys_enter").performClick(); idle()
        edit.append("Следующая планёрка — понедельник"); idle()
        assertEquals(listOf("Геодезия — съёмка секции 3", "☐ Акты АОСР", "☐ Исполнительная съёмка", "Следующая планёрка — понедельник"), texts(note()))
        shot(root, "note-keyboard.png")

        // Курсор поставили в середину первой строки — в строке ввода она целиком, курсор там же; вставили слово.
        val setCaret = NoteActivity::class.java.getDeclaredMethod("setCaret", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).apply { isAccessible = true }
        setCaret.invoke(a, 0, 8); idle()
        assertEquals("Геодезия — съёмка секции 3", edit.text.toString())
        assertEquals(8, edit.selectionStart)
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
}
