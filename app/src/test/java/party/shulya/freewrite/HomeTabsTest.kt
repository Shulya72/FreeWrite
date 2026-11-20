package party.shulya.freewrite

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File
import java.util.Calendar

/**
 * ViaDesk 0.28: три вкладки внизу (Заметки, Задачи, Доски), заметки как на айфоне — поиск, папки, закреплённые сверху,
 * разбивка по дням (Владимир: «папки в заметках сделай»).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w800dp-h1280dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeTabsTest {

    private val ctx get() = RuntimeEnvironment.getApplication()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private val day = 86_400_000L

    @Before
    fun setUp() {
        Input.markStylus(ctx)
        Profile.save(ctx, "Владимир", "Шульмин")
        ctx.getSharedPreferences("home", 0).edit().clear().commit()
    }

    private fun at(daysAgo: Int, h: Int, m: Int): Long = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, -daysAgo); set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0)
    }.timeInMillis

    private fun note(title: String, text: String, edited: Long, folder: String = "", pinned: Boolean = false): Note =
        Note(java.util.UUID.randomUUID().toString(), title, edited, edited, mutableListOf(Block.Para(text)), mutableListOf(), 0).apply {
            this.folder = folder; this.pinned = pinned
        }

    private fun shot(v: View, name: String) {
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        File("build/screenshots").mkdirs()
        File("build/screenshots/$name").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun texts(v: View, onlyShown: Boolean = true): List<String> {
        val out = ArrayList<String>()
        fun walk(x: View) {
            if (onlyShown && x.visibility != View.VISIBLE) return
            if (x is TextView) out += x.text.toString()
            if (x is ViewGroup) for (i in 0 until x.childCount) walk(x.getChildAt(i))
        }
        walk(v)
        return out
    }

    @Test
    fun groupsTimesSearchAndOrder() {
        val now = at(0, 15, 0)
        assertEquals("Сегодня", NotesList.group(at(0, 8, 5), now))
        assertEquals("Вчера", NotesList.group(at(1, 18, 0), now))
        assertEquals("Предыдущие 7 дней", NotesList.group(at(4, 9, 0), now))
        assertEquals("Предыдущие 30 дней", NotesList.group(at(20, 9, 0), now))
        val old = at(70, 9, 0)
        val c = Calendar.getInstance().apply { timeInMillis = old }
        val months = listOf("Январь", "Февраль", "Март", "Апрель", "Май", "Июнь", "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь")
        val sameYear = c.get(Calendar.YEAR) == Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.YEAR)
        assertEquals(months[c.get(Calendar.MONTH)] + if (sameYear) "" else " ${c.get(Calendar.YEAR)}", NotesList.group(old, now))
        assertEquals("8:05", NotesList.rowTime(at(0, 8, 5), now))
        assertEquals("Вчера", NotesList.rowTime(at(1, 8, 5), now))
        assertTrue(NotesList.rowTime(at(40, 8, 5), now).matches(Regex("""\d\d\.\d\d\.\d{4}""")))

        val a = note("Планёрка", "Разбивка котлована ВСК", at(0, 8, 0), pinned = true)
        val b = note("Акты", "армирование, ёмкость", at(0, 7, 0), folder = "f1")
        val d = note("Звонок", "технадзор", at(1, 9, 0))
        val e = note("Идеи", "сводка", at(40, 9, 0), folder = "f1")
        val all = listOf(e, d, b, a)
        val s = NotesList.arrange(all, "", "", now)
        assertEquals(listOf(NotesList.PINNED, "Сегодня", "Вчера"), s.take(3).map { it.first })
        assertEquals(listOf("Планёрка"), s[0].second.map { it.title })
        assertEquals("в папке — только её заметки", listOf("Акты", "Идеи"), NotesList.arrange(all, "f1", "", now).flatMap { it.second }.map { it.title })
        assertEquals("поиск без регистра", listOf("Планёрка"), NotesList.arrange(all, "", "вск", now).flatMap { it.second }.map { it.title })
        assertEquals("ё = е, все слова", listOf("Акты"), NotesList.arrange(all, "", "емкость армир", now).flatMap { it.second }.map { it.title })
        assertTrue(NotesList.arrange(all, "", "нет такого", now).isEmpty())
    }

    @Test
    fun folderAndPinSurviveSaveAndOldNotesStillRead() {
        val store = NoteStore(ctx)
        val n = note("Акты", "текст", System.currentTimeMillis(), folder = "abc", pinned = true)
        store.save(n)
        val back = store.load(n.id)!!
        assertEquals("abc", back.folder)
        assertTrue(back.pinned)
        // Заметка без папки пишется как раньше — без новых полей.
        val plain = note("Просто", "текст", System.currentTimeMillis())
        assertFalse(plain.toJsonString().contains("folder"))
        assertFalse(plain.toJsonString().contains("pinned"))
        val j = JSONObject(n.toJsonString())
        assertEquals("abc", j.getString("folder"))
        // Старый файл (0.27) — без папки и не закреплён.
        val old = Note.fromJson(JSONObject("""{"v":1,"id":"x","title":"Старая","created":1,"updated":1,"caret":0,"blocks":[{"t":"p","text":"а"}],"drawings":[]}"""))
        assertEquals("", old.folder)
        assertFalse(old.pinned)

        // Папки: создать, переименовать, удалить — заметки остаются, только без папки.
        val folders = NoteFolders(ctx)
        val f = folders.add("Работа")
        folders.rename(f.id, "Объект")
        assertEquals(listOf("Объект"), folders.list().map { it.name })
        val inF = note("В папке", "т", System.currentTimeMillis(), folder = f.id)
        store.save(inF)
        folders.delete(f.id, store)
        assertTrue(folders.list().isEmpty())
        assertEquals("", store.load(inF.id)!!.folder)
    }

    @Test
    fun tabsFoldersPinSearchOnScreen() {
        val store = NoteStore(ctx)
        val folders = NoteFolders(ctx)
        val work = folders.add("Работа")
        store.save(note("Планёрка 19 ноября", "Секция 3: разбивка осей", at(0, 8, 5), pinned = true))
        store.save(note("Акты на армирование", "Плита 4 этажа, оси 1–7", at(0, 7, 40), folder = work.id))
        store.save(note("Звонок технадзору", "Перенос приёмки на 10:30, ВСК", at(1, 18, 12)))
        store.save(note("Идеи по доске", "Сводка ИГС по разделам", at(34, 12, 0)))
        val b = Board.create("КЖ 7.1.3 — Секция 3")
        BoardStore(ctx).save(b)

        val ctl = Robolectric.buildActivity(HomeActivity::class.java).setup()
        val a = ctl.get()
        idle()
        val root = a.window.decorView
        val title = root.findViewWithTag<TextView>("home_title")
        assertEquals("Заметки", title.text.toString())
        for (t in listOf("tab_notes", "tab_tasks", "tab_boards")) assertTrue(t, root.findViewWithTag<View>(t) != null)
        val shown = texts(root)
        assertTrue("закреплённые сверху: $shown", shown.indexOf(NotesList.PINNED) in 0 until shown.indexOf("Сегодня"))
        assertTrue("группы по дням: $shown", "Вчера" in shown)
        assertTrue("папка видна у заметки во «Всех»: $shown", "Работа" in shown)
        assertTrue("счётчик: $shown", "4 заметки" in shown)
        assertEquals(View.VISIBLE, root.findViewWithTag<View>("home_new_note").visibility)
        shot(root, "viadesk-notes.png")

        // Папка «Работа» — только её заметка.
        root.findViewWithTag<View>("folder_0").performClick(); idle()
        val inWork = texts(root.findViewWithTag("notes_list"))
        assertTrue(inWork.toString(), "Акты на армирование" in inWork && "Звонок технадзору" !in inWork)
        // Новая заметка из папки попадает в неё.
        root.findViewWithTag<View>("home_new_note").performClick(); idle()
        val dlg = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(dlg.window!!.decorView.findViewWithTag<android.widget.EditText>("title_input").text.startsWith("Заметка "))
        dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        val newId = shadowOf(a).nextStartedActivity.getStringExtra(NoteActivity.EXTRA_ID)!!
        assertEquals(work.id, store.load(newId)!!.folder)
        root.findViewWithTag<View>("folder_all").performClick(); idle()

        // Поиск по словам из текста.
        root.findViewWithTag<android.widget.EditText>("notes_search").setText("всК"); idle()
        val found = texts(root.findViewWithTag("notes_list"))
        assertTrue(found.toString(), "Звонок технадзору" in found && "Планёрка 19 ноября" !in found)
        root.findViewWithTag<android.widget.EditText>("notes_search").setText(""); idle()

        // Подержать заметку → «Закрепить».
        fun row(t: String): View {
            var v: View = (root.findViewWithTag<ViewGroup>("notes_list")).let { list ->
                val all = ArrayList<View>()
                fun walk(x: View) { if (x is TextView && x.text.toString() == t) all += x; if (x is ViewGroup) for (i in 0 until x.childCount) walk(x.getChildAt(i)) }
                walk(list); all.first()
            }
            while (!v.isLongClickable) v = v.parent as View
            return v
        }
        row("Звонок технадзору").performLongClick(); idle()
        shadowOf(ShadowAlertDialog.getLatestAlertDialog()).clickOnItem(0); idle()
        assertTrue(store.list().first { it.title == "Звонок технадзору" }.pinned)
        // «Переместить в папку» → «Работа».
        row("Идеи по доске").performLongClick(); idle()
        shadowOf(ShadowAlertDialog.getLatestAlertDialog()).clickOnItem(1); idle()
        shadowOf(ShadowAlertDialog.getLatestAlertDialog()).clickOnItem(1); idle()
        assertEquals(work.id, store.list().first { it.title == "Идеи по доске" }.folder)

        // «+ Папка» — новая папка и сразу открыта.
        root.findViewWithTag<View>("folder_add").performClick(); idle()
        val fd = ShadowAlertDialog.getLatestAlertDialog()
        fd.window!!.decorView.findViewWithTag<android.widget.EditText>("folder_name").setText("Личное")
        fd.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        assertEquals(listOf("Работа", "Личное"), folders.list().map { it.name })
        assertTrue(texts(root).any { it.startsWith("Личное · 0") })
        root.findViewWithTag<View>("folder_all").performClick(); idle()

        // Вкладка «Доски»: плитки и доски, кнопки новой заметки нет.
        root.findViewWithTag<View>("tab_boards").performClick(); idle()
        assertEquals("Доски", title.text.toString())
        assertEquals(View.GONE, root.findViewWithTag<View>("home_new_note").visibility)
        assertTrue(texts(root).contains("КЖ 7.1.3 — Секция 3"))
        shot(root, "viadesk-boards.png")
        // Вкладка «Задачи» — пока заглушка до календаря.
        root.findViewWithTag<View>("tab_tasks").performClick(); idle()
        assertEquals("Задачи", title.text.toString())
        assertEquals(View.VISIBLE, root.findViewWithTag<View>("tasks_soon").visibility)
        shot(root, "viadesk-tasks.png")
        ctl.pause().stop().destroy()
        // Открытая вкладка запоминается.
        val again = Robolectric.buildActivity(HomeActivity::class.java).setup().get()
        idle()
        assertEquals("Задачи", again.window.decorView.findViewWithTag<TextView>("home_title").text.toString())
    }

    @Test
    fun undoInNoteKeepsFolderAndPin() {
        val store = NoteStore(ctx)
        val n = note("Планёрка", "Строка", System.currentTimeMillis() - 3 * day, folder = "f9", pinned = true)
        store.save(n)
        val ctl = Robolectric.buildActivity(NoteActivity::class.java, Intent(ctx, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_ID, n.id)).setup()
        val a = ctl.get()
        idle()
        val root = a.window.decorView
        root.findViewWithTag<View>("tool_keys").performClick(); idle()
        root.findViewWithTag<KeysEdit>("keys_edit").append(" и ещё"); idle()
        NoteActivity::class.java.getDeclaredMethod("undo").apply { isAccessible = true }.invoke(a); idle()
        ctl.pause()
        val back = store.load(n.id)!!
        assertEquals("f9", back.folder)
        assertTrue(back.pinned)
        assertTrue("время правки обновилось", back.updated > n.updated)
        ctl.stop().destroy()
    }

    @Test
    @Config(qualifiers = "w360dp-h780dp-port-xxhdpi")
    fun phoneTabsFit() {
        NoteStore(ctx).save(note("Планёрка на объекте", "Секция 3", System.currentTimeMillis()))
        val a = Robolectric.buildActivity(HomeActivity::class.java).setup().get()
        idle()
        val root = a.window.decorView
        val bar = root.findViewWithTag<ViewGroup>("home_tabs")
        assertEquals(3, bar.childCount)
        for (i in 0 until 3) assertTrue("вкладка ${i + 1} на экране", bar.getChildAt(i).right <= bar.width && bar.getChildAt(i).width > 0)
        shot(root, "viadesk-phone.png")
    }
}
