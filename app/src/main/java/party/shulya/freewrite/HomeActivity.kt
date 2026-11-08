package party.shulya.freewrite

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/** Ежедневник: записи по дням, «Новая запись», проверка планшета и обновление. */
class HomeActivity : Activity() {

    private lateinit var store: NoteStore
    private lateinit var boards: BoardStore
    private lateinit var list: LinearLayout
    private lateinit var updateBar: LinearLayout
    private lateinit var updateText: TextView
    private lateinit var updateButton: TextView
    private var release: Updater.Release? = null
    private var downloaded: File? = null
    private var avatarText: TextView? = null
    private var avatarIcon: ImageView? = null
    private var phone = false

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = NoteStore(this)
        boards = BoardStore(this)
        phone = Input.isPhone(this)
        setContentView(buildUi())
        checkUpdates(silent = true)
        // Первый запуск: представиться (для общих досок). Можно «Позже».
        if (!Profile.isSet(this) && !Profile.skipped(this)) {
            window.decorView.post { if (!isFinishing) Profile.ask(this, required = false) { refreshProfile() } }
        }
    }

    override fun onResume() {
        super.onResume()
        renderList()
    }

    /**
     * Ежедневник (0.12): компактная шапка (название, кружок с инициалами, меню «⋮»), три плитки
     * «Запись / Доска / По коду», ниже — записи и доски по дням. На планшете то же, только просторнее
     * и с подписями на плитках; ширина — не больше 760 dp.
     */
    private fun buildUi(): View {
        val scroll = ScrollView(this).apply { setBackgroundColor(Ui.DESK); isFillViewport = true }
        val outer = FrameLayout(this)
        val side = if (phone) 16f else 28f
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(side), dp(if (phone) 14f else 24f), dp(side), dp(28f))
        }
        val maxW = dp(760f)
        val w = if (resources.displayMetrics.widthPixels > maxW) maxW else Ui.MATCH
        outer.addView(col, FrameLayout.LayoutParams(w, Ui.WRAP).apply { gravity = Gravity.CENTER_HORIZONTAL })
        scroll.addView(outer)

        // Шапка: FreeWrite и дата, справа — кружок с инициалами и «⋮».
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val brandCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val brand = SpannableString("FreeWrite").apply {
            setSpan(ForegroundColorSpan(Ui.ACCENT), 4, 9, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        brandCol.addView(Ui.text(this, "", if (phone) 26f else 30f, Ui.INK, bold = true).apply { text = brand; includeFontPadding = false })
        brandCol.addView(Ui.text(this, Ui.longDate(System.currentTimeMillis()), 14f, Ui.MUTED).apply { setPadding(0, dp(2f), 0, 0) })
        head.addView(brandCol, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))

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
        col.addView(head)
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
        col.addView(updateBar, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(14f) })

        // Три плитки: новая запись, новая доска, подключиться к общей доске по коду.
        val tiles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; tag = "home_tiles" }
        val gap = dp(if (phone) 8f else 12f)
        tiles.addView(tile(R.drawable.ic_note, if (phone) "Запись" else "Новая запись", "Совещание, звонок — от руки в текст", "home_new_note") { newNote() },
            LinearLayout.LayoutParams(0, Ui.MATCH, 1f))
        tiles.addView(tile(R.drawable.ic_board, if (phone) "Доска" else "Новая доска", "Чертежи, статусы, выноски", "home_new_board") { newBoard() },
            LinearLayout.LayoutParams(0, Ui.MATCH, 1f).apply { marginStart = gap })
        tiles.addView(tile(R.drawable.ic_join, "По коду", "К общей доске коллеги", "home_join") { joinDialog() },
            LinearLayout.LayoutParams(0, Ui.MATCH, 1f).apply { marginStart = gap })
        col.addView(tiles, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(if (phone) 16f else 22f) })

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(list, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(6f) })
        col.addView(Ui.text(this, "FreeWrite ${BuildConfig.VERSION_NAME}", 12f, Ui.MUTED).apply {
            gravity = Gravity.CENTER
            tag = "home_version"
            setPadding(0, dp(18f), 0, 0)
        }, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        return scroll
    }

    /** Плитка действия: на телефоне — значок и слово, на планшете — ещё и подпись. */
    private fun tile(icon: Int, title: String, sub: String, tag: String, onClick: () -> Unit): View {
        val t = LinearLayout(this).apply {
            this.tag = tag
            background = Ui.rounded(this@HomeActivity, Ui.PAPER, Ui.RULE, 14f)
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

    /** «⋮»: файл доски, обновления, проверка устройства; внизу — версия. */
    private fun showMenu(anchor: View) {
        val m = android.widget.PopupMenu(this, anchor)
        m.menu.add(0, MENU_OPEN_FILE, 0, "Открыть файл доски")
        m.menu.add(0, MENU_UPDATES, 1, "Проверить обновления")
        m.menu.add(0, MENU_CHECK, 2, if (phone) "Проверка устройства" else "Проверка планшета")
        m.menu.add(0, MENU_PROFILE, 3, Profile.name(this).ifEmpty { "Как вас зовут?" })
        m.menu.add(0, 0, 4, "Версия ${BuildConfig.VERSION_NAME}").isEnabled = false
        m.setOnMenuItemClickListener { onMenu(it.itemId); true }
        m.show()
    }

    private fun onMenu(id: Int) {
        when (id) {
            MENU_OPEN_FILE -> pickBoardFile()
            MENU_UPDATES -> checkUpdates(silent = false)
            MENU_CHECK -> startActivity(Intent(this, DiagnosticsActivity::class.java))
            MENU_PROFILE -> Profile.ask(this, required = false) { refreshProfile() }
        }
    }

    private fun renderList() {
        list.removeAllViews()
        // Записи и доски вместе, по дням, новые сверху.
        val entries: List<Pair<Long, () -> View>> =
            store.list().map { n -> n.created to { noteRow(n) } } +
                boards.list().map { b -> b.created to { boardRow(b) } }
        if (entries.isEmpty()) {
            list.addView(Ui.text(this, "Здесь появятся ваши записи и доски. «Запись» — пишите от руки, слова соберутся в текст. " +
                "«Доска» — чертежи и статусы. «По коду» — общая доска коллеги.", 15f, Ui.INK2).apply {
                background = Ui.rounded(this@HomeActivity, Ui.PAPER, Ui.RULE, 14f)
                setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
            }, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(16f) })
            return
        }
        var lastDay = -1L
        for ((created, row) in entries.sortedByDescending { it.first }) {
            val day = Ui.startOfDay(created)
            if (day != lastDay) {
                lastDay = day
                list.addView(Ui.text(this, Ui.dayTitle(created), 13f, Ui.MUTED, bold = true).apply {
                    setPadding(dp(4f), dp(16f), 0, dp(6f))
                })
            }
            list.addView(row(), LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { bottomMargin = dp(8f) })
        }
    }

    /**
     * Строка записи или доски: значок, название на всю ширину (время справа), под ним подпись,
     * ещё ниже — счётчики статусов (переносятся на следующую строку, ничего не сжимается).
     */
    private fun entryRow(icon: Int, title: String, time: Long, sub: String, chips: List<TextView>): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Ui.rounded(this@HomeActivity, Ui.PAPER, Ui.RULE, 14f)
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
            Toast.makeText(this, "В этой сборке нет адреса сервера — обновите FreeWrite", Toast.LENGTH_LONG).show()
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
                    Remote.apply(b, pulled.rows)
                    b.sync.remoteSeq = pulled.next
                    boards.save(b)
                    renderList()
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
            .setMessage("Доска «${b.title}»\n\n$code\n\nКоллега: Ежедневник → «Подключиться по коду» → ввести код.")
            .setPositiveButton("Поделиться кодом") { _, _ ->
                val text = "Подключись к доске «${b.title}» в FreeWrite: Ежедневник → «Подключиться по коду» → $code"
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
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "Доска «${b.title}» — FreeWrite на компьютере: $link")
                }, "Отправить ссылку"))
            }
            .setNeutralButton("Скопировать") { _, _ ->
                getSystemService(android.content.ClipboardManager::class.java)
                    ?.setPrimaryClip(android.content.ClipData.newPlainText("FreeWrite", link))
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
                            if (t.isNotEmpty()) { boards.rename(b.id, t); renderList() }
                        }
                        .setNegativeButton("Отмена", null)
                        .show()
                } else {
                    AlertDialog.Builder(this)
                        .setMessage("Удалить доску «${b.title}»? Вернуть её будет нельзя.")
                        .setPositiveButton("Удалить") { _, _ -> boards.delete(b.id); renderList() }
                        .setNegativeButton("Отмена", null)
                        .show()
                }
            }
            .show()
    }

    private fun noteRow(n: Note): View {
        val chips = ArrayList<TextView>()
        if (n.tasksDone > 0) chips += Ui.chip(this, "${n.tasksDone} сдано", Ui.OK, Ui.OK_SOFT)
        if (n.tasksOpen > 0) chips += Ui.chip(this, "${n.tasksOpen} не сдано", Ui.BAD, Ui.BAD_SOFT)
        return entryRow(R.drawable.ic_note, n.title, n.created, n.preview.ifEmpty { "Запись" }, chips).apply {
            setOnClickListener { open(n.id) }
            setOnLongClickListener { noteMenu(n); true }
        }
    }

    private fun open(id: String) {
        startActivity(Intent(this, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_ID, id))
    }

    private fun newNote() {
        askTitle("Новая запись", "Запись " + Ui.shortDate(System.currentTimeMillis())) { title ->
            val n = Note.create(title)
            store.save(n)
            open(n.id)
        }
    }

    private fun noteMenu(n: Note) {
        AlertDialog.Builder(this)
            .setTitle(n.title)
            .setItems(arrayOf("Переименовать", "Удалить")) { _, which ->
                if (which == 0) rename(n) else confirmDelete(n)
            }
            .show()
    }

    private fun rename(n: Note) {
        val edit = EditText(this).apply { setText(n.title); setSelection(n.title.length); isSingleLine = true }
        val box = FrameLayout(this).apply { setPadding(dp(20f), dp(8f), dp(20f), 0); addView(edit) }
        AlertDialog.Builder(this)
            .setTitle("Название записи")
            .setView(box)
            .setPositiveButton("Готово") { _, _ ->
                val t = edit.text.toString().trim()
                if (t.isNotEmpty()) { n.title = t; store.save(n); renderList() }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun confirmDelete(n: Note) {
        AlertDialog.Builder(this)
            .setMessage("Удалить запись «${n.title}»? Вернуть её будет нельзя.")
            .setPositiveButton("Удалить") { _, _ -> store.delete(n); renderList() }
            .setNegativeButton("Отмена", null)
            .show()
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
            Toast.makeText(this, "Разрешите FreeWrite устанавливать приложения, вернитесь и нажмите «Установить»", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val REQ_BOARD_FILE = 41
        private const val MENU_OPEN_FILE = 1
        private const val MENU_UPDATES = 2
        private const val MENU_CHECK = 3
        private const val MENU_PROFILE = 4
    }
}
