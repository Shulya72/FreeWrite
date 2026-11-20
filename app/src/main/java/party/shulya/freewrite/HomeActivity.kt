package party.shulya.freewrite

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * Главный экран ViaDesk (0.28): три вкладки внизу — Заметки, Задачи, Доски. Заметки — как на айфоне: поиск, папки,
 * закреплённые сверху, разбивка по дням. Доски — как раньше. Задачи (календарь) — в следующих версиях.
 */
class HomeActivity : Activity() {

    private lateinit var store: NoteStore
    private lateinit var boards: BoardStore
    private lateinit var folders: NoteFolders
    private lateinit var updateBar: LinearLayout
    private lateinit var updateText: TextView
    private lateinit var updateButton: TextView
    private lateinit var titleView: TextView
    private lateinit var notesList: LinearLayout
    private lateinit var boardsList: LinearLayout
    private lateinit var folderRow: LinearLayout
    private lateinit var countText: TextView
    private lateinit var compose: View
    /** Поиск заметок — в белой шапке, только на вкладке «Заметки». */
    private lateinit var searchWrap: View
    /** Граница под белой шапкой: у «Задач» своя — под днями недели (шапка календаря тоже белая). */
    private lateinit var headLine: View
    private val pages = LinkedHashMap<String, View>()
    private val tabs = LinkedHashMap<String, LinearLayout>()
    private var release: Updater.Release? = null
    private var downloaded: File? = null
    private var avatarText: TextView? = null
    private var avatarIcon: ImageView? = null
    private var phone = false
    private var tab = TAB_NOTES
    /** Выбранная папка; пусто — все заметки. */
    private var folder = ""
    private var query = ""
    /** Заставка при запуске (0.29.2). */
    internal val splash by lazy { Splash(this) }

    /** Инициалы для кружка в шапке: «ВШ». */
    private fun initials(): String {
        val f = Profile.first(this).trim(); val l = Profile.last(this).trim()
        return (f.take(1) + l.take(1)).uppercase()
    }

    private fun refreshProfile() {
        val ini = initials()
        avatarText?.apply { text = ini; visibility = if (ini.isEmpty()) View.GONE else View.VISIBLE }
        avatarIcon?.visibility = if (ini.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun dp(v: Float) = Ui.dp(this, v)
    private val prefs get() = getSharedPreferences("home", MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Окно до первого кадра — тёмно-синее, как заставка (`Theme.FreeWrite.Launch`), дальше — обычная тема (0.29.2).
        setTheme(R.style.Theme_FreeWrite)
        super.onCreate(savedInstanceState)
        store = NoteStore(this)
        boards = BoardStore(this)
        folders = NoteFolders(this)
        phone = Input.isPhone(this)
        tab = prefs.getString("tab", TAB_NOTES) ?: TAB_NOTES
        folder = prefs.getString("folder", "") ?: ""
        if (folder.isNotEmpty() && folders.list().none { it.id == folder }) folder = ""
        setContentView(buildUi())
        if (Splash.wanted(this, savedInstanceState)) splash.show()
        showTab(tab)
        openPlanFrom(intent)
        // Оповещения календаря: будильник мог пропасть (оптимизация батареи HarmonyOS) — поставить заново.
        runCatching { PlanAlarms.reschedule(this, planner.store.items) }
        checkUpdates(silent = true)
        // Первый запуск: представиться (для общих досок). Можно «Позже».
        if (!Profile.isSet(this) && !Profile.skipped(this)) splash.after {
            window.decorView.post { if (!isFinishing) Profile.ask(this, required = false) { refreshProfile() } }
        }
    }

    override fun onResume() {
        super.onResume()
        renderAll()
        // Прошлый раз приложение закрылось с ошибкой — предложить отчёт (0.26.2), один раз за запуск.
        if (!crashOffered) {
            crashOffered = true
            splash.after {
                window.decorView.postDelayed({
                    if (!isFinishing) CrashReport.pending(this)?.let { CrashReport.offer(this, it) }
                }, 400)
            }
        }
    }

    private fun renderAll() {
        renderNotes()
        renderBoards()
        if (::planner.isInitialized && tab == TAB_TASKS) planner.refresh()
    }

    // ---------------------------------------------------------------- каркас: шапка, страницы, вкладки

    /** Колонка по центру: на планшете не шире 760 dp. */
    private fun centered(child: View): FrameLayout {
        val maxW = dp(760f)
        val w = if (resources.displayMetrics.widthPixels > maxW) maxW else Ui.MATCH
        return FrameLayout(this).apply { addView(child, FrameLayout.LayoutParams(w, Ui.WRAP).apply { gravity = Gravity.CENTER_HORIZONTAL }) }
    }

    private fun buildUi(): View {
        val side = if (phone) 16f else 28f
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG)
            // Поиск не забирает курсор при открытии — клавиатура только по касанию поля.
            isFocusableInTouchMode = true
            descendantFocusability = android.view.ViewGroup.FOCUS_BEFORE_DESCENDANTS
        }

        // Шапка: крупное название вкладки, справа — кружок с инициалами и «⋮».
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(side), dp(if (phone) 12f else 20f), dp(side - 6f), dp(6f))
        }
        titleView = Ui.text(this, "Заметки", if (phone) 28f else 34f, Ui.INK).apply {
            typeface = Ui.brandFont(this@HomeActivity); includeFontPadding = false; tag = "home_title"; letterSpacing = -0.02f
        }
        head.addView(titleView, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        val avatar = FrameLayout(this).apply {
            tag = "home_profile"
            contentDescription = "Как вас зовут"
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Ui.ACCENT_SOFT)
            }
            isClickable = true
            setOnClickListener { Profile.ask(this@HomeActivity, required = false) { refreshProfile() } }
        }
        avatarText = Ui.text(this, "", 15f, Ui.ACCENT, bold = true).apply { gravity = Gravity.CENTER }
        avatar.addView(avatarText, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        avatarIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_person)
            imageTintList = ColorStateList.valueOf(Ui.ACCENT)
            setPadding(dp(9f), dp(9f), dp(9f), dp(9f))
        }
        avatar.addView(avatarIcon, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        head.addView(avatar, LinearLayout.LayoutParams(dp(40f), dp(40f)))
        val more = ImageView(this).apply {
            tag = "home_menu"
            contentDescription = "Ещё"
            setImageResource(R.drawable.ic_more)
            imageTintList = ColorStateList.valueOf(Ui.INK2)
            setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
            isClickable = true
            setOnClickListener { v -> showMenu(v) }
        }
        head.addView(more, LinearLayout.LayoutParams(dp(40f), dp(40f)).apply { marginStart = dp(4f) })
        // Шапка белая (тема «Светлая целиком», 0.29.3): название, кружок с инициалами, «⋮», на «Заметках» — поиск.
        val headBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BAR); tag = "home_head" }
        headBox.addView(centered(head), LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        searchWrap = centered(FrameLayout(this).apply { setPadding(dp(side), dp(6f), dp(side), dp(12f)); addView(searchField()) })
        headBox.addView(searchWrap, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        root.addView(headBox, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        headLine = Ui.barLine(this)
        root.addView(headLine, LinearLayout.LayoutParams(Ui.MATCH, dp(1f)))
        refreshProfile()

        // Плашка обновления
        updateBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(this@HomeActivity, Ui.ACCENT_SOFT, null, 12f)
            setPadding(dp(14f), dp(10f), dp(10f), dp(10f))
            visibility = View.GONE
        }
        updateText = Ui.text(this, "", 14f, Ui.ACCENT)
        updateButton = Ui.text(this, "Обновить", 14f, Color.WHITE, bold = true).apply {
            background = Ui.rounded(this@HomeActivity, Ui.ACCENT, null, 10f)
            setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
            setOnClickListener { startUpdate() }
        }
        updateBar.addView(updateText, LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { marginEnd = dp(8f) })
        updateBar.addView(updateButton)
        val upWrap = FrameLayout(this).apply { setPadding(dp(side), dp(4f), dp(side), dp(4f)); addView(updateBar) }
        root.addView(centered(upWrap), LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))

        // Страницы вкладок
        val content = FrameLayout(this)
        pages[TAB_NOTES] = notesPage(side)
        pages[TAB_TASKS] = tasksPage(side)
        pages[TAB_BOARDS] = boardsPage(side)
        for (v in pages.values) content.addView(v, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        compose = ImageView(this).apply {
            tag = "home_new_note"
            contentDescription = "Новая заметка"
            setImageResource(R.drawable.ic_compose)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(Ui.INK)
            }
            elevation = dp(6f).toFloat()
            setPadding(dp(15f), dp(15f), dp(15f), dp(15f))
            isClickable = true
            setOnClickListener { newNote() }
        }
        content.addView(compose, FrameLayout.LayoutParams(dp(56f), dp(56f)).apply {
            gravity = Gravity.BOTTOM or Gravity.END; marginEnd = dp(side); bottomMargin = dp(16f)
        })
        root.addView(content, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))

        // Вкладки внизу
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Ui.BAR)
            setPadding(dp(8f), dp(6f), dp(8f), dp(8f))
            tag = "home_tabs"
        }
        bar.addView(tabButton(TAB_NOTES, R.drawable.ic_note, "Заметки"), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        bar.addView(tabButton(TAB_TASKS, R.drawable.ic_calendar, "Задачи"), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        bar.addView(tabButton(TAB_BOARDS, R.drawable.ic_board, "Доски"), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        root.addView(Ui.barLine(this), LinearLayout.LayoutParams(Ui.MATCH, dp(1f)))
        root.addView(bar, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        root.post { root.requestFocus() }
        return root
    }

    private fun tabButton(id: String, icon: Int, label: String): LinearLayout {
        val b = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            tag = "tab_$id"
            contentDescription = label
            setPadding(0, dp(6f), 0, dp(5f))
            isClickable = true
            setOnClickListener { showTab(id) }
        }
        b.addView(ImageView(this).apply { setImageResource(icon) }, LinearLayout.LayoutParams(dp(26f), dp(26f)))
        b.addView(Ui.text(this, label, 12f, Ui.INK2, bold = true).apply { gravity = Gravity.CENTER; setPadding(0, dp(2f), 0, 0) })
        tabs[id] = b
        return b
    }

    private fun showTab(id: String) {
        tab = if (id in pages) id else TAB_NOTES
        prefs.edit().putString("tab", tab).apply()
        for ((k, v) in pages) v.visibility = if (k == tab) View.VISIBLE else View.GONE
        for ((k, b) in tabs) {
            val on = k == tab
            val c = if (on) Ui.ACCENT else Ui.INK2
            (b.getChildAt(0) as ImageView).imageTintList = ColorStateList.valueOf(c)
            (b.getChildAt(1) as TextView).setTextColor(c)
            b.background = if (on) Ui.rounded(this, Ui.ACCENT_SOFT, null, 16f) else null
        }
        titleView.text = when (tab) { TAB_TASKS -> "Задачи"; TAB_BOARDS -> "Доски"; else -> "Заметки" }
        compose.visibility = if (tab == TAB_NOTES) View.VISIBLE else View.GONE
        searchWrap.visibility = if (tab == TAB_NOTES) View.VISIBLE else View.GONE
        headLine.visibility = if (tab == TAB_TASKS) View.GONE else View.VISIBLE
        if (tab == TAB_TASKS && ::planner.isInitialized) planner.refresh()
    }

    private fun scrollPage(side: Float, fill: (LinearLayout) -> Unit): ScrollView {
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(side), dp(6f), dp(side), dp(96f))
        }
        fill(col)
        scroll.addView(centered(col))
        return scroll
    }

    // ---------------------------------------------------------------- Заметки

    /** Поле поиска заметок (в белой шапке): все слова, без регистра, «ё» = «е». */
    private fun searchField(): View {
        val search = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(this@HomeActivity, Ui.DESK, null, 12f)
            setPadding(dp(10f), 0, dp(10f), 0)
        }
        search.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_search); imageTintList = ColorStateList.valueOf(Ui.MUTED)
        }, LinearLayout.LayoutParams(dp(20f), dp(20f)))
        val edit = EditText(this).apply {
            tag = "notes_search"
            hint = "Поиск"
            isSingleLine = true
            textSize = 16f
            background = null
            setTextColor(Ui.INK)
            setHintTextColor(Ui.MUTED)
            setPadding(dp(8f), dp(9f), dp(4f), dp(9f))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(e: Editable?) { query = e?.toString().orEmpty(); renderNotes() }
            })
        }
        search.addView(edit, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        return search
    }

    private fun notesPage(side: Float): View = scrollPage(side) { col ->
        // Папки
        folderRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; tag = "folder_row" }
        val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(folderRow) }
        col.addView(hs, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(8f) })

        notesList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "notes_list" }
        col.addView(notesList, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        countText = Ui.text(this, "", 13f, Ui.MUTED).apply { gravity = Gravity.CENTER; tag = "notes_count"; setPadding(0, dp(16f), 0, 0) }
        col.addView(countText, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
    }

    /** Папка-«таблетка»: выбранная — тёмно-синяя, остальные — белые с рамкой. */
    private fun folderChip(label: String, on: Boolean, tag: String): TextView = Ui.text(this, label, 14f, if (on) Color.WHITE else Ui.INK, bold = on).apply {
        this.tag = tag
        isSingleLine = true
        background = if (on) Ui.rounded(this@HomeActivity, Ui.INK, null, 18f) else Ui.rounded(this@HomeActivity, Ui.CARD, Ui.CHIP_LINE, 18f)
        setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
        isClickable = true
    }

    private fun renderFolders(all: List<Note>) {
        folderRow.removeAllViews()
        val list = folders.list()
        val lp = { LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { marginEnd = dp(8f) } }
        folderRow.addView(folderChip("Все заметки · ${all.size}", folder.isEmpty(), "folder_all").apply {
            setOnClickListener { pickFolder("") }
        }, lp())
        for ((i, f) in list.withIndex()) {
            val n = all.count { it.folder == f.id }
            folderRow.addView(folderChip("${f.name} · $n", folder == f.id, "folder_$i").apply {
                setOnClickListener { pickFolder(f.id) }
                setOnLongClickListener { folderMenu(f); true }
            }, lp())
        }
        folderRow.addView(folderChip("+ Папка", false, "folder_add").apply {
            setTextColor(Ui.INK2)
            background = Ui.dashed(this@HomeActivity, Ui.DASH_LINE, 18f)
            setOnClickListener { askFolderName("Новая папка", "") { name -> val f = folders.add(name); pickFolder(f.id) } }
        }, lp())
    }

    private fun pickFolder(id: String) {
        folder = id
        prefs.edit().putString("folder", id).apply()
        renderNotes()
    }

    private fun askFolderName(heading: String, current: String, done: (String) -> Unit) {
        val edit = EditText(this).apply {
            setText(current); setSelection(current.length); isSingleLine = true; hint = "Например, Работа"; tag = "folder_name"
        }
        val box = FrameLayout(this).apply { setPadding(dp(20f), dp(8f), dp(20f), 0); addView(edit) }
        val dialog = AlertDialog.Builder(this)
            .setTitle(heading)
            .setView(box)
            .setPositiveButton("Готово") { _, _ -> edit.text.toString().trim().takeIf { it.isNotEmpty() }?.let(done) }
            .setNegativeButton("Отмена", null)
            .create()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        edit.requestFocus()
    }

    private fun folderMenu(f: NoteFolder) {
        AlertDialog.Builder(this)
            .setTitle(f.name)
            .setItems(arrayOf("Переименовать", "Удалить папку")) { _, which ->
                if (which == 0) askFolderName("Название папки", f.name) { name -> folders.rename(f.id, name); renderNotes() }
                else AlertDialog.Builder(this)
                    .setMessage("Удалить папку «${f.name}»? Заметки из неё останутся — во «Всех заметках».")
                    .setPositiveButton("Удалить") { _, _ ->
                        folders.delete(f.id, store)
                        if (folder == f.id) folder = ""
                        renderNotes()
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
            }
            .show()
    }

    private fun renderNotes() {
        val all = store.list()
        val now = System.currentTimeMillis()
        renderFolders(all)
        notesList.removeAllViews()
        val sections = NotesList.arrange(all, folder, query, now)
        val shown = sections.sumOf { it.second.size }
        if (sections.isEmpty()) {
            val msg = when {
                query.isNotBlank() -> "Ничего не нашлось по «${query.trim()}»"
                folder.isNotEmpty() -> "В этой папке пока пусто. Новая заметка — кнопка с карандашом внизу справа: она попадёт сюда."
                else -> "Здесь будут ваши заметки. Кнопка с карандашом внизу справа — новая заметка: пишите от руки, слова соберутся в текст."
            }
            notesList.addView(Ui.text(this, msg, 15f, Ui.INK2).apply {
                background = Ui.rounded(this@HomeActivity, Ui.CARD, Ui.RULE, 16f)
                setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
            }, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(16f) })
        }
        val names = folders.list().associate { it.id to it.name }
        for ((title, notes) in sections) {
            val h = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4f), dp(18f), 0, dp(8f)) }
            if (title == NotesList.PINNED) h.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_pin); imageTintList = ColorStateList.valueOf(Ui.AMBER)
            }, LinearLayout.LayoutParams(dp(18f), dp(18f)).apply { marginEnd = dp(6f) })
            h.addView(Ui.text(this, title, 19f, Ui.INK).apply { typeface = Ui.brandFont(this@HomeActivity) })
            notesList.addView(h)
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = Ui.rounded(this@HomeActivity, Ui.CARD, Ui.RULE, 16f)
            }
            for ((i, n) in notes.withIndex()) {
                if (i > 0) card.addView(View(this).apply { setBackgroundColor(Ui.RULE) },
                    LinearLayout.LayoutParams(Ui.MATCH, dp(1f)).apply { marginStart = dp(16f) })
                card.addView(noteRow(n, now, if (folder.isEmpty()) names[n.folder] else null))
            }
            notesList.addView(card, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        }
        countText.text = if (shown == 0) "" else Ui.plural(shown, "заметка", "заметки", "заметок")
    }

    /** Строка заметки: название, под ним время и первая строка текста; в «Всех заметках» — ещё и папка. */
    private fun noteRow(n: Note, now: Long, folderName: String?): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(11f), dp(16f), dp(12f))
            isClickable = true
            setOnClickListener { open(n.id) }
            setOnLongClickListener { noteMenu(n); true }
        }
        row.addView(Ui.text(this, n.title.ifBlank { "Новая заметка" }, 17f, Ui.INK, bold = true).apply {
            isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END; tag = "row_title"
        })
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(2f), 0, 0) }
        line.addView(Ui.text(this, NotesList.rowTime(NotesList.edited(n), now), 14f, Ui.ACCENT, bold = true).apply { setPadding(0, 0, dp(8f), 0) })
        line.addView(Ui.text(this, n.preview.ifEmpty { "Нет дополнительного текста" }, 14f, Ui.MUTED).apply {
            isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        row.addView(line)
        val chips = ArrayList<TextView>()
        if (n.tasksDone > 0) chips += Ui.chip(this, "${n.tasksDone} сдано", Ui.OK, Ui.OK_SOFT)
        if (n.tasksOpen > 0) chips += Ui.chip(this, "${n.tasksOpen} не сдано", Ui.BAD, Ui.BAD_SOFT)
        if (!folderName.isNullOrEmpty() || chips.isNotEmpty()) {
            val flow = Ui.FlowRow(this).apply { hGap = dp(6f); vGap = dp(6f); setPadding(0, dp(6f), 0, 0) }
            if (!folderName.isNullOrEmpty()) flow.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(ImageView(this@HomeActivity).apply {
                    setImageResource(R.drawable.ic_folder); imageTintList = ColorStateList.valueOf(Ui.MUTED)
                }, LinearLayout.LayoutParams(dp(15f), dp(15f)).apply { marginEnd = dp(4f) })
                addView(Ui.text(this@HomeActivity, folderName, 13f, Ui.MUTED).apply { tag = "row_folder" })
            })
            for (c in chips) flow.addView(c)
            row.addView(flow, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        }
        return row
    }

    private fun open(id: String) {
        startActivity(Intent(this, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_ID, id))
    }

    private fun newNote() {
        askTitle("Новая заметка", "Заметка " + Ui.shortDate(System.currentTimeMillis())) { title ->
            val n = Note.create(title)
            n.folder = folder // новая заметка — в открытую папку
            store.save(n)
            open(n.id)
        }
    }

    private fun noteMenu(n: Note) {
        AlertDialog.Builder(this)
            .setTitle(n.title)
            .setItems(arrayOf(if (n.pinned) "Открепить" else "Закрепить", "Переместить в папку", "Переименовать", "Удалить")) { _, which ->
                when (which) {
                    0 -> { n.pinned = !n.pinned; store.save(n); renderNotes() }
                    1 -> moveDialog(n)
                    2 -> rename(n)
                    else -> confirmDelete(n)
                }
            }
            .show()
    }

    private fun moveDialog(n: Note) {
        val list = folders.list()
        val items = arrayOf("Без папки") + list.map { it.name } + arrayOf("+ Новая папка")
        AlertDialog.Builder(this)
            .setTitle("Переместить «${n.title}»")
            .setItems(items) { _, which ->
                when {
                    which == 0 -> { n.folder = ""; store.save(n); renderNotes() }
                    which <= list.size -> { n.folder = list[which - 1].id; store.save(n); renderNotes() }
                    else -> askFolderName("Новая папка", "") { name -> val f = folders.add(name); n.folder = f.id; store.save(n); renderNotes() }
                }
            }
            .show()
    }

    private fun rename(n: Note) {
        val edit = EditText(this).apply { setText(n.title); setSelection(n.title.length); isSingleLine = true }
        val box = FrameLayout(this).apply { setPadding(dp(20f), dp(8f), dp(20f), 0); addView(edit) }
        AlertDialog.Builder(this)
            .setTitle("Название заметки")
            .setView(box)
            .setPositiveButton("Готово") { _, _ ->
                val t = edit.text.toString().trim()
                if (t.isNotEmpty()) { n.title = t; n.updated = System.currentTimeMillis(); store.save(n); renderNotes() }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun confirmDelete(n: Note) {
        AlertDialog.Builder(this)
            .setMessage("Удалить заметку «${n.title}»? Вернуть её будет нельзя.")
            .setPositiveButton("Удалить") { _, _ -> store.delete(n); renderNotes() }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ---------------------------------------------------------------- Задачи: календарь (0.29)

    internal lateinit var planner: PlannerPage

    private fun tasksPage(side: Float): View {
        planner = PlannerPage(this)
        return planner.root
    }

    /** Открыть день календаря из уведомления (оповещение о событии). */
    private fun openPlanFrom(i: Intent?) {
        val day = i?.getStringExtra(EXTRA_PLAN_DAY) ?: return
        val d = runCatching { java.time.LocalDate.parse(day) }.getOrNull() ?: return
        showTab(TAB_TASKS)
        planner.openDay(d, i.getStringExtra(EXTRA_PLAN_ITEM), PlannerPage.MONTH)
        i.removeExtra(EXTRA_PLAN_DAY)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openPlanFrom(intent)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (tab == TAB_TASKS && ::planner.isInitialized && planner.back()) return
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    // ---------------------------------------------------------------- Доски

    private fun boardsPage(side: Float): View = scrollPage(side) { col ->
        // Три плитки: новая доска, подключиться по коду, открыть файл.
        val tiles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; tag = "home_tiles" }
        val gap = dp(if (phone) 8f else 12f)
        tiles.addView(tile(R.drawable.ic_board, if (phone) "Доска" else "Новая доска", "Чертежи, таблицы, статусы", "home_new_board") { newBoard() },
            LinearLayout.LayoutParams(0, Ui.MATCH, 1f))
        tiles.addView(tile(R.drawable.ic_join, "По коду", "К общей доске коллеги", "home_join") { joinDialog() },
            LinearLayout.LayoutParams(0, Ui.MATCH, 1f).apply { marginStart = gap })
        tiles.addView(tile(R.drawable.ic_open_file, if (phone) "Файл" else "Из файла", "Доска или Nebo", "home_open_file") { pickBoardFile() },
            LinearLayout.LayoutParams(0, Ui.MATCH, 1f).apply { marginStart = gap })
        col.addView(tiles, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(6f) })
        boardsList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(boardsList, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(6f) })
        col.addView(Ui.text(this, "ViaDesk ${BuildConfig.VERSION_NAME}", 12f, Ui.MUTED).apply {
            gravity = Gravity.CENTER
            tag = "home_version"
            setPadding(0, dp(18f), 0, 0)
        }, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
    }

    /** Плитка действия: на телефоне — значок и слово, на планшете — ещё и подпись. */
    private fun tile(icon: Int, title: String, sub: String, tag: String, onClick: () -> Unit): View {
        val t = LinearLayout(this).apply {
            this.tag = tag
            background = Ui.rounded(this@HomeActivity, Ui.CARD, Ui.RULE, 16f)
            isClickable = true
            setOnClickListener { onClick() }
        }
        val ico = ImageView(this).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(Ui.ACCENT)
            background = Ui.rounded(this@HomeActivity, Ui.ACCENT_SOFT, null, 10f)
            setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
        }
        if (phone) {
            t.orientation = LinearLayout.VERTICAL
            t.gravity = Gravity.CENTER
            t.setPadding(dp(6f), dp(12f), dp(6f), dp(10f))
            t.addView(ico, LinearLayout.LayoutParams(dp(38f), dp(38f)))
            t.addView(Ui.text(this, title, 14f, Ui.INK, bold = true).apply {
                gravity = Gravity.CENTER; isSingleLine = true; setPadding(0, dp(6f), 0, 0)
            })
        } else {
            t.orientation = LinearLayout.HORIZONTAL
            t.gravity = Gravity.CENTER_VERTICAL
            t.setPadding(dp(14f), dp(14f), dp(12f), dp(14f))
            t.addView(ico, LinearLayout.LayoutParams(dp(44f), dp(44f)))
            val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12f), 0, 0, 0) }
            c.addView(Ui.text(this, title, 17f, Ui.INK, bold = true).apply { isSingleLine = true })
            c.addView(Ui.text(this, sub, 13f, Ui.INK2).apply { maxLines = 2 })
            t.addView(c, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        }
        return t
    }

    private fun renderBoards() {
        boardsList.removeAllViews()
        val all = boards.list()
        if (all.isEmpty()) {
            boardsList.addView(Ui.text(this, "Здесь будут ваши доски: чертежи, таблицы ИД, ведомости и планы. «Новая доска» — начать, " +
                "«По коду» — подключиться к общей доске коллеги.", 15f, Ui.INK2).apply {
                background = Ui.rounded(this@HomeActivity, Ui.CARD, Ui.RULE, 16f)
                setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
            }, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(16f) })
            return
        }
        var lastDay = -1L
        for (b in all.sortedByDescending { it.created }) {
            val day = Ui.startOfDay(b.created)
            if (day != lastDay) {
                lastDay = day
                boardsList.addView(Ui.text(this, Ui.dayTitle(b.created), 13f, Ui.MUTED, bold = true).apply {
                    setPadding(dp(4f), dp(16f), 0, dp(6f))
                })
            }
            boardsList.addView(boardRow(b), LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { bottomMargin = dp(8f) })
        }
    }

    /**
     * Строка записи или доски: значок, название на всю ширину (время справа), под ним подпись,
     * ещё ниже — счётчики статусов (переносятся на следующую строку, ничего не сжимается).
     */
    private fun entryRow(icon: Int, title: String, time: Long, sub: String, chips: List<TextView>): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Ui.rounded(this@HomeActivity, Ui.CARD, Ui.RULE, 16f)
            setPadding(dp(12f), dp(12f), dp(14f), dp(12f))
            isClickable = true
        }
        val ico = ImageView(this).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(Ui.ACCENT)
            background = Ui.rounded(this@HomeActivity, Ui.ACCENT_SOFT, null, 10f)
            setPadding(dp(7f), dp(7f), dp(7f), dp(7f))
        }
        row.addView(ico, LinearLayout.LayoutParams(dp(36f), dp(36f)).apply { topMargin = dp(1f) })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12f), 0, 0, 0) }
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        line.addView(Ui.text(this, title, 16f, Ui.INK, bold = true).apply {
            isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END; tag = "row_title"
        }, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        line.addView(Ui.text(this, Ui.time(time), 13f, Ui.MUTED).apply { setPadding(dp(8f), 0, 0, 0) })
        col.addView(line)
        if (sub.isNotEmpty()) col.addView(Ui.text(this, sub, 13f, Ui.MUTED).apply {
            isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(1f), 0, 0)
        })
        if (chips.isNotEmpty()) {
            val flow = Ui.FlowRow(this).apply { hGap = dp(6f); vGap = dp(6f); setPadding(0, dp(7f), 0, 0) }
            for (c in chips) flow.addView(c.apply { isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END })
            col.addView(flow, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        }
        row.addView(col, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        return row
    }

    private fun boardRow(b: BoardMeta): View {
        val kind = if (b.shared) "Общая доска" else "Доска"
        val sub = if (b.items == 0) "$kind · пустая" else "$kind · " + Ui.plural(b.items, "предмет", "предмета", "предметов")
        // Счётчики по статусам доски (до трёх самых частых).
        val chips = b.counts.filter { it.third > 0 }.sortedByDescending { it.third }.take(3)
            .map { (name, color, n) -> Ui.chip(this, "$n ${name.lowercase()}", Board.statusFg(color), Board.statusBg(color)) }
        return entryRow(if (b.shared) R.drawable.ic_cloud else R.drawable.ic_board, b.title, b.created, sub, chips).apply {
            setOnClickListener { openBoard(b.id) }
            setOnLongClickListener { boardMenu(b); true }
        }
    }

    private fun openBoard(id: String) {
        startActivity(Intent(this, BoardActivity::class.java).putExtra(BoardActivity.EXTRA_ID, id))
    }

    private fun newBoard() {
        askTitle("Новая доска", "Доска " + Ui.shortDate(System.currentTimeMillis())) { title ->
            val b = Board.create(title)
            boards.save(b)
            openBoard(b.id)
        }
    }

    /**
     * Спросить название нового документа. Предложенное название выделено целиком —
     * можно сразу писать своё. Пустое поле — остаётся предложенное.
     */
    private fun askTitle(heading: String, suggested: String, create: (String) -> Unit) {
        val edit = EditText(this).apply {
            setText(suggested)
            setSelectAllOnFocus(true) // предложенное выделено — можно сразу писать своё
            selectAll()
            isSingleLine = true
            tag = "title_input"
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        }
        val box = FrameLayout(this).apply { setPadding(dp(20f), dp(8f), dp(20f), 0); addView(edit) }
        val dialog = AlertDialog.Builder(this)
            .setTitle(heading)
            .setMessage("Название")
            .setView(box)
            .setPositiveButton("Создать") { _, _ -> create(edit.text.toString().trim().ifEmpty { suggested }) }
            .setNegativeButton("Отмена", null)
            .create()
        edit.setOnEditorActionListener { _, _, _ ->
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.performClick()
            true
        }
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        edit.requestFocus()
    }

    /** Файл доски от коллеги (сохранён из Telegram в «Загрузки» и т. п.) — выбрать и открыть. */
    private fun pickBoardFile() {
        val i = Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE) }
        try {
            startActivityForResult(Intent.createChooser(i, "Файл доски (.freewrite)"), REQ_BOARD_FILE)
        } catch (_: Throwable) {
            Toast.makeText(this, "Не нашлось приложения для выбора файла", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Activity.onActivityResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == REQ_BOARD_FILE && resultCode == RESULT_OK && uri != null) {
            startActivity(Intent(this, ImportActivity::class.java).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
    }

    /** Подключиться к общей доске коллеги по коду-приглашению. */
    private fun joinDialog() {
        val t = RemoteSync.transport()
        if (t == null) {
            Toast.makeText(this, "В этой сборке нет адреса сервера — обновите ViaDesk", Toast.LENGTH_LONG).show()
            return
        }
        val edit = EditText(this).apply {
            hint = "Например, ABCD-EFGH"
            isSingleLine = true
            tag = "join_code"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(8f), dp(20f), 0)
            addView(Ui.text(this@HomeActivity, "Код доски — у коллеги: на доске кнопка «Совместно».", 14f, Ui.INK2))
            addView(edit)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Подключиться к общей доске")
            .setView(box)
            .setPositiveButton("Подключиться") { _, _ ->
                val code = edit.text.toString()
                Profile.require(this) { refreshProfile(); join(t, code) }
            }
            .setNegativeButton("Отмена", null)
            .create()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        edit.requestFocus()
    }

    private fun join(t: SyncTransport, code: String) {
        if (code.isBlank()) return
        val progress = AlertDialog.Builder(this)
            .setTitle("Подключаюсь…")
            .setMessage("Получаю доску с сервера. Картинки догрузятся, когда доска откроется.")
            .setCancelable(false)
            .show()
        Thread {
            val r = runCatching {
                val j = Remote.join(t, code.trim())
                j to Remote.pull(t, j.boardId, j.key, 0)
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                progress.dismiss()
                r.onSuccess { (j, pulled) ->
                    val now = System.currentTimeMillis()
                    // Своей копии доски нет — пустая (без статусов: они придут с сервера).
                    val b = boards.load(j.boardId) ?: Board(j.boardId, j.title.ifEmpty { "Общая доска" }, now, now)
                    b.sync.remoteKey = j.key
                    b.sync.remoteInvite = j.invite
                    b.sync.remoteSeq = 0
                    b.sync.pushedRev = ""
                    // Доска могла уже быть на планшете (подключались раньше, старой версией) — перечитать целиком.
                    Remote.apply(b, pulled.rows, refresh = true)
                    b.sync.remoteSeq = pulled.next
                    b.sync.readBy = Remote.appVersion
                    boards.save(b)
                    renderAll()
                    Toast.makeText(this, "Подключено: «${b.title}»", Toast.LENGTH_LONG).show()
                    openBoard(b.id)
                }.onFailure { e ->
                    val msg = (e as? SyncException)?.message ?: "Нет связи с сервером. Проверьте интернет."
                    AlertDialog.Builder(this).setTitle("Не удалось подключиться").setMessage(msg)
                        .setPositiveButton("Понятно", null).show()
                }
            }
        }.start()
    }

    private fun shareBoard(id: String) {
        val board = boards.load(id) ?: return
        BoardShare.offer(this, board) { boards.save(board) }
    }

    /** Код общей доски для коллег — с кнопкой «Поделиться кодом». */
    private fun showCode(id: String) {
        val b = boards.load(id) ?: return
        val code = Remote.prettyInvite(b.sync.remoteInvite)
        AlertDialog.Builder(this)
            .setTitle("Код для коллег")
            .setMessage("Доска «${b.title}»\n\n$code\n\nКоллега: Доски → «По коду» → ввести код.")
            .setPositiveButton("Поделиться кодом") { _, _ ->
                val text = "Подключись к доске «${b.title}» в ViaDesk: Доски → «По коду» → $code"
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text)
                }, "Отправить код"))
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    /**
     * Сайт для компьютера (FW-003): ссылка на просмотр общей доски — отправить себе и открыть на компьютере.
     * Руководству — «Ссылка для руководства» на самом сайте (по ней ничего не изменить).
     */
    private fun showSite(id: String) {
        val b = boards.load(id) ?: return
        val link = Remote.siteLink(BuildConfig.SYNC_URL, b.sync.remoteInvite)
        if (link == null) {
            AlertDialog.Builder(this).setMessage("В этой сборке приложения нет адреса сервера.").setPositiveButton("Понятно", null).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Открыть на компьютере")
            .setMessage("Доска «${b.title}» на сайте — смотреть с компьютера (только просмотр, обновляется сама):\n\n$link\n\n" +
                "Отправьте ссылку себе (например, в Telegram) и откройте на компьютере. Ссылка с кодом доски — только для себя.\n" +
                "Руководству — кнопка «Ссылка для руководства» на сайте: по ней доску можно только смотреть.")
            .setPositiveButton("Отправить себе") { _, _ ->
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "Доска «${b.title}» — ViaDesk на компьютере: $link")
                }, "Отправить ссылку"))
            }
            .setNeutralButton("Скопировать") { _, _ ->
                getSystemService(android.content.ClipboardManager::class.java)
                    ?.setPrimaryClip(android.content.ClipData.newPlainText("ViaDesk", link))
                Toast.makeText(this, "Ссылка скопирована", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun boardMenu(b: BoardMeta) {
        val items = if (b.shared) arrayOf("Переименовать", "Удалить", "Поделиться файлом", "Код для коллег", "Открыть на компьютере")
        else arrayOf("Переименовать", "Удалить", "Поделиться файлом")
        AlertDialog.Builder(this)
            .setTitle(b.title)
            .setItems(items) { _, which ->
                if (which == 4) {
                    showSite(b.id)
                } else if (which == 3) {
                    showCode(b.id)
                } else if (which == 2) {
                    shareBoard(b.id)
                } else if (which == 0) {
                    val edit = EditText(this).apply { setText(b.title); setSelection(b.title.length); isSingleLine = true }
                    val box = FrameLayout(this).apply { setPadding(dp(20f), dp(8f), dp(20f), 0); addView(edit) }
                    AlertDialog.Builder(this)
                        .setTitle("Название доски")
                        .setView(box)
                        .setPositiveButton("Готово") { _, _ ->
                            val t = edit.text.toString().trim()
                            if (t.isNotEmpty()) { boards.rename(b.id, t); renderAll() }
                        }
                        .setNegativeButton("Отмена", null)
                        .show()
                } else {
                    AlertDialog.Builder(this)
                        .setMessage("Удалить доску «${b.title}»? Вернуть её будет нельзя.")
                        .setPositiveButton("Удалить") { _, _ -> boards.delete(b.id); renderAll() }
                        .setNegativeButton("Отмена", null)
                        .show()
                }
            }
            .show()
    }

    /** «⋮»: файл доски, новая папка, обновления, проверка устройства; внизу — версия. */
    private fun showMenu(anchor: View) {
        val m = android.widget.PopupMenu(this, anchor)
        m.menu.add(0, MENU_OPEN_FILE, 0, "Открыть файл доски")
        m.menu.add(0, MENU_FOLDER, 1, "Новая папка заметок")
        m.menu.add(0, MENU_UPDATES, 2, "Проверить обновления")
        m.menu.add(0, MENU_CHECK, 3, if (phone) "Проверка устройства" else "Проверка планшета")
        m.menu.add(0, MENU_PROFILE, 4, Profile.name(this).ifEmpty { "Как вас зовут?" })
        m.menu.add(0, MENU_REPORT, 5, "Отправить отчёт о работе")
        m.menu.add(0, 0, 6, "ViaDesk ${BuildConfig.VERSION_NAME}").isEnabled = false
        m.setOnMenuItemClickListener { onMenu(it.itemId); true }
        m.show()
    }

    private fun onMenu(id: Int) {
        when (id) {
            MENU_OPEN_FILE -> pickBoardFile()
            MENU_FOLDER -> { showTab(TAB_NOTES); askFolderName("Новая папка", "") { name -> val f = folders.add(name); pickFolder(f.id) } }
            MENU_UPDATES -> checkUpdates(silent = false)
            MENU_CHECK -> startActivity(Intent(this, DiagnosticsActivity::class.java))
            MENU_PROFILE -> Profile.ask(this, required = false) { refreshProfile() }
            MENU_REPORT -> { CrashReport.markSeen(this); CrashReport.share(this, CrashReport.build(this)) }
        }
    }

    // ---------------------------------------------------------------- обновление

    private fun checkUpdates(silent: Boolean) {
        if (!silent) Toast.makeText(this, "Проверяю обновления…", Toast.LENGTH_SHORT).show()
        Updater.check { r, err ->
            if (isFinishing) return@check
            if (r != null && Updater.isNewer(r.version, BuildConfig.VERSION_NAME)) {
                release = r
                downloaded = null
                updateText.text = "Доступна новая версия ${r.version} (у вас ${BuildConfig.VERSION_NAME})"
                updateButton.text = "Обновить"
                updateButton.isEnabled = true
                updateBar.visibility = View.VISIBLE
            } else if (!silent && err != null) {
                // Встроенная проверка не дотянулась до сервера — предлагаем браузер.
                AlertDialog.Builder(this)
                    .setTitle("Не удалось проверить обновления")
                    .setMessage("$err.\n\nМожно скачать последнюю версию в браузере и установить её поверх.")
                    .setPositiveButton("Скачать в браузере") { _, _ -> Updater.openInBrowser(this) }
                    .setNegativeButton("Закрыть", null)
                    .show()
            } else if (!silent) {
                Toast.makeText(this, "У вас последняя версия ${BuildConfig.VERSION_NAME}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private var downloadFailed = false

    private fun startUpdate() {
        if (downloadFailed) {
            downloadFailed = false
            Updater.openInBrowser(this)
            return
        }
        downloaded?.let { if (it.exists()) { install(it); return } }
        val r = release ?: return
        updateButton.isEnabled = false
        updateText.text = "Скачиваю версию ${r.version}…"
        Updater.download(this, r, progress = { pct ->
            updateText.text = "Скачиваю версию ${r.version}… $pct%"
        }) { file, err ->
            updateButton.isEnabled = true
            if (file == null) {
                updateText.text = "Не удалось скачать: $err. Можно скачать в браузере."
                updateButton.text = "В браузере"
                downloadFailed = true
                return@download
            }
            downloaded = file
            updateText.text = "Версия ${r.version} скачана"
            updateButton.text = "Установить"
            install(file)
        }
    }

    private fun install(file: File) {
        if (!Updater.install(this, file)) {
            Toast.makeText(this, "Разрешите ViaDesk устанавливать приложения, вернитесь и нажмите «Установить»", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val REQ_BOARD_FILE = 41
        private const val MENU_OPEN_FILE = 1
        private const val MENU_UPDATES = 2
        private const val MENU_CHECK = 3
        private const val MENU_PROFILE = 4
        private const val MENU_REPORT = 5
        private const val MENU_FOLDER = 6
        const val EXTRA_PLAN_DAY = "plan_day"
        const val EXTRA_PLAN_ITEM = "plan_item"
        const val TAB_NOTES = "notes"
        const val TAB_TASKS = "tasks"
        const val TAB_BOARDS = "boards"
        /** Окно «закрылся с ошибкой» уже показывали в этом запуске. */
        internal var crashOffered = false
    }
}
