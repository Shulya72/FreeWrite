package party.shulya.freewrite

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * Экран «Запись» (этап 2).
 *
 * Пишете пером в любом месте листа. После паузы (или когда начинаете писать в другом месте)
 * надпись распознаётся: слово серым появляется у курсора, затем становится текстом,
 * а рукопись исчезает. Ручка и маркер оставляют рисунок рисунком, «Фигуры» делают из рисунка
 * ровную фигуру. Пальцем — прокрутка, галочки задач, перенос курсора, выбор картинки
 * (уголки — размер, сама картинка — сдвинуть или перенести по тексту).
 */
class NoteActivity : Activity(), NoteInkView.Host {

    enum class Tool { WRITE, KEYS, PEN, MARKER, SHAPE, ERASER, HAND, SELECT, PIPETTE }

    private lateinit var store: NoteStore
    private lateinit var note: Note
    private lateinit var engine: HandwritingEngine
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private lateinit var scroll: ScrollView
    private lateinit var sheet: RuledSheet
    private lateinit var ink: NoteInkView
    private lateinit var mini: LinearLayout
    private lateinit var imageBar: LinearLayout
    private var selectedImage: Block.Image? = null
    // Пока палец тянет картинку, лист не перерисовываем (иначе жест оборвётся) — откладываем.
    private var imageEditing = false
    private var renderPending = false
    private lateinit var hintView: TextView
    private lateinit var banner: LinearLayout
    private lateinit var bannerText: TextView
    private lateinit var bannerButton: TextView
    private lateinit var titleView: TextView
    private val toolButtons = HashMap<Tool, Ui.ToolButton>()
    private val quickColors = ArrayList<View>()
    private var toolBeforePipette = Tool.PEN
    private var pipetteSnap: ColorKit.Snapshot? = null

    private var tool = Tool.WRITE
    private var penColor = Ui.INK
    private var fingerWrites = false

    private val blockViews = ArrayList<View>()
    private var caretTextView: TextView? = null
    private var interim = ""
    private var caretOn = true
    private val bitmapCache = HashMap<String, Bitmap>()

    // Снимки для «Отменить» (0.27.6): строки копируются, рисунки — те же (в записи рисунки не меняют, только добавляют, стирают
    // и заменяют новыми). Раньше снимок был всей записью в JSON — после каждого штриха, и чем больше написано, тем дольше.
    private class Snap(val title: String, val blocks: List<Block>, val drawings: List<Drawing>, val caret: Int, val caretOffset: Int)
    private val undoStack = ArrayDeque<Snap>()
    private val redoStack = ArrayDeque<Snap>()

    private var lineHeightPx = 0

    // «Клавиатура» (0.27.6): строка ввода внизу — как строка формул в Excel: в ней текст строки с курсором, правка сразу на листе.
    private lateinit var keysBar: LinearLayout
    private lateinit var keysEdit: KeysEdit
    private lateinit var keysTask: Ui.ToolButton
    private var keysLoading = false   // строку ввода заполняем сами — не считать это вводом
    private var keysTyping = false    // идёт ввод — лист не переписывает строку ввода
    private var keysEditAt = 0L       // последний ввод (серия нажатий — один шаг «Отменить»)
    private var keysBlock: Block? = null
    private var pauseMs = 1000L
    private val shapeTool = ShapeTool()
    private var shapeHintShown = false

    // ---------------------------------------------------------------- письмо в текст

    private inner class Group(val id: Int) {
        val strokes = ArrayList<InkStroke>()
        var candidates: List<Candidate>? = null
        var resultFor = -1
        var committing = false
        var error: String? = null
    }

    private var current: Group? = null
    private val commitQueue = ArrayDeque<Group>()
    private val afterCommit = ArrayDeque<() -> Unit>()
    private var groupSeq = 0
    private val wordHeights = ArrayList<Float>()
    private val interimRunnable = Runnable { current?.let { recognize(it) } }
    private val commitRunnable = Runnable { current?.let { commit(it) } }
    private val caretBlink = object : Runnable {
        override fun run() {
            caretOn = !caretOn
            refreshCaretText()
            handler.postDelayed(this, 530)
        }
    }
    private val saveRunnable = Runnable { saveNow() }
    private val bannerTicker = object : Runnable {
        override fun run() {
            updateBanner()
            if (engine.language("ru").state == HandwritingEngine.State.DOWNLOADING) handler.postDelayed(this, 1000)
        }
    }

    // ---------------------------------------------------------------- жизненный цикл

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = NoteStore(this)
        val id = intent.getStringExtra(EXTRA_ID)
        note = id?.let { store.load(it) } ?: Note.create("Совещание").also { store.save(it) }
        lineHeightPx = Ui.dp(this, 36f)
        engine = HandwritingEngine { android.util.Log.i("FreeWrite", it) }
        engine.onChanged = { updateBanner() }
        buildUi()
        setContentView(rootView)
        engine.start(this)
        renderBlocks()
        updateBanner()
        setTool(Tool.WRITE)
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(caretBlink)
        handler.postDelayed(caretBlink, 530)
    }

    override fun onPause() {
        handler.removeCallbacks(caretBlink)
        current?.let { commit(it) }
        saveNow(wait = true)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        engine.close()
        io.shutdown()
        super.onDestroy()
    }

    @Deprecated("Activity.onBackPressed")
    override fun onBackPressed() {
        val finishNow = Runnable { saveNow(wait = true); finish() }
        handler.postDelayed(finishNow, 2500) // если распознавание зависло — всё равно выходим
        runAfterCommit {
            handler.removeCallbacks(finishNow)
            finishNow.run()
        }
    }

    // ---------------------------------------------------------------- интерфейс

    private lateinit var rootView: LinearLayout

    private fun dp(v: Float) = Ui.dp(this, v)

    private fun buildUi() {
        rootView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.DESK)
        }

        // Полоса инструментов
        val row = LinearLayout(this)
        val back = Ui.ToolButton(this, R.drawable.ic_back, "Назад").apply { setOnClickListener { onBackPressed() } }
        back.minimumWidth = dp(44f)
        row.addView(back)
        titleView = Ui.text(this, note.title, 16f, Ui.INK, bold = true).apply {
            maxWidth = dp(220f)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(4f), 0, dp(10f), 0)
            setOnClickListener { renameNote() }
        }
        val titleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(titleView)
            addView(Ui.mono(this@NoteActivity, Ui.noteMeta(note.created), 11f).apply { setPadding(dp(4f), 0, dp(10f), 0) })
            setOnClickListener { renameNote() }
        }
        row.addView(titleBox)
        row.addView(Ui.separator(this))
        addTool(row, Tool.WRITE, R.drawable.ic_write, "В текст")
        addTool(row, Tool.KEYS, R.drawable.ic_keyboard, "Клавиатура")
        addTool(row, Tool.PEN, R.drawable.ic_pen, "Ручка")
        addTool(row, Tool.MARKER, R.drawable.ic_marker, "Маркер")
        addTool(row, Tool.SHAPE, R.drawable.ic_shapes, "Фигуры")
        addTool(row, Tool.ERASER, R.drawable.ic_eraser, "Ластик")
        addTool(row, Tool.HAND, R.drawable.ic_hand, "Указка")
        addTool(row, Tool.SELECT, R.drawable.ic_lasso, "Выделить")
        // Три последних цвета и «Цвет» — палитра из 24 цветов и пипетка.
        for (i in 0 until 3) {
            val v = View(this).apply {
                contentDescription = "Цвет"
                tag = "quick_color_$i"
                setOnClickListener { setColor(ColorKit.recent(this@NoteActivity)[i], remember = false) }
            }
            row.addView(v, LinearLayout.LayoutParams(dp(24f), dp(24f)).apply { setMargins(dp(5f), 0, dp(5f), 0) })
            quickColors += v
        }
        row.addView(Ui.ToolButton(this, R.drawable.ic_palette, "Цвет").apply {
            tag = "tool_palette"
            setOnClickListener { ColorKit.showPalette(this@NoteActivity, penColor, { setColor(it) }, { startPipette() }) }
        })
        row.addView(Ui.separator(this))
        row.addView(Ui.ToolButton(this, R.drawable.ic_image, "Картинка").apply { setOnClickListener { pickImage() } })
        row.addView(Ui.ToolButton(this, R.drawable.ic_undo, "Отменить").apply { setOnClickListener { undo() } })
        row.addView(Ui.ToolButton(this, R.drawable.ic_redo, "Вернуть").apply { setOnClickListener { redo() } })
        row.addView(Ui.ToolButton(this, R.drawable.ic_sum, "Итоги").apply { setOnClickListener { showSummary() } })
        row.addView(Ui.separator(this))
        row.addView(CheckBox(this).apply {
            text = "Палец пишет"
            textSize = 13f
            setTextColor(Ui.INK2)
            buttonTintList = ColorStateList.valueOf(Ui.ACCENT)
            setOnCheckedChangeListener { _, on -> fingerWrites = on }
        })
        rootView.addView(Ui.toolbar(this, row), LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        rootView.addView(View(this).apply { setBackgroundColor(Ui.RULE) }, LinearLayout.LayoutParams(Ui.MATCH, dp(1f)))

        // Плашка «скачайте модель»
        banner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Ui.WARN_SOFT)
            setPadding(dp(16f), dp(8f), dp(12f), dp(8f))
        }
        bannerText = Ui.text(this, "", 14f, Ui.WARN)
        bannerButton = Ui.text(this, "Скачать", 14f, Color.WHITE, bold = true).apply {
            background = Ui.rounded(this@NoteActivity, Ui.ACCENT, null, 10f)
            setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
            setOnClickListener {
                engine.download(engine.language("ru"))
                handler.removeCallbacks(bannerTicker)
                handler.postDelayed(bannerTicker, 1000)
            }
        }
        banner.addView(bannerText, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        banner.addView(bannerButton)
        rootView.addView(banner, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))

        // Лист и слой пера
        val area = FrameLayout(this)
        scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = true
        }
        val pad = FrameLayout(this).apply { setPadding(dp(12f), dp(14f), dp(12f), dp(14f)) }
        sheet = RuledSheet(this).apply {
            lineGap = lineHeightPx.toFloat()
            background = Ui.rounded(this@NoteActivity, Ui.PAPER, Ui.RULE, 6f)
            setPadding(dp(56f), dp(18f), dp(36f), dp(24f))
        }
        pad.addView(sheet, FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { gravity = Gravity.CENTER_HORIZONTAL })
        scroll.addView(pad, FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        scroll.setOnScrollChangeListener { _, _, _, _, _ ->
            pipetteSnap = null
            ink.invalidate()
        }
        area.addView(scroll, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        ink = NoteInkView(this, this)
        area.addView(ink.layer, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH)) // готовые рисунки — слоем под пером
        area.addView(ink, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))

        mini = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Ui.rounded(this@NoteActivity, Ui.PAPER, Ui.RULE, 14f)
            elevation = dp(6f).toFloat()
            setPadding(dp(4f), dp(4f), dp(4f), dp(4f))
        }
        fun miniButton(icon: Int, label: String, action: () -> Unit): Ui.ToolButton {
            val b = Ui.ToolButton(this, icon, label).apply {
                minimumWidth = dp(48f)
                setOnClickListener { action() }
            }
            mini.addView(b)
            return b
        }
        miniButton(R.drawable.ic_space, "пробел") { runAfterCommit { insertAtCaret(" ", raw = true) } }
        // «буква»: одно нажатие — одна буква; если держать — стирает подряд, как клавиатура.
        miniButton(R.drawable.ic_backspace, "буква") {}.apply {
            tag = "mini_letter"
            setOnTouchListener(repeatOnHold { runAfterCommit { deleteChar() } })
        }
        miniButton(R.drawable.ic_backspace_word, "слово") { runAfterCommit { deleteWord() } }
        miniButton(R.drawable.ic_enter, "строка") { runAfterCommit { newLine(task = false) } }
        miniButton(R.drawable.ic_task, "задача") { runAfterCommit { newLine(task = true) } }
        miniButton(R.drawable.ic_edit, "править") { runAfterCommit { editCaretDialog() } }
        area.addView(mini, FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply {
            gravity = Gravity.TOP or Gravity.END
            setMargins(0, dp(10f), dp(14f), 0)
        })
        // Панель выбранной картинки (на месте мини-панели).
        imageBar = Ui.floatingBar(
            this, "Уголок — размер, картинку — сдвинуть",
            Ui.pill(this, "Удалить", Ui.BAD, Ui.BAD_SOFT).apply { setOnClickListener { deleteSelectedImage() } },
            Ui.pill(this, "Готово", Color.WHITE, Ui.ACCENT).apply { setOnClickListener { selectImage(null) } },
        ).apply {
            tag = "image_bar"
            visibility = View.GONE
        }
        area.addView(imageBar, FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply {
            gravity = Gravity.TOP or Gravity.END
            setMargins(0, dp(10f), dp(14f), 0)
        })
        // Панель выделенного рукописного (0.27.7): «В текст», «Удалить», «Готово».
        selLabel = Ui.text(this, "", 13f, Ui.INK2).apply { setPadding(0, 0, dp(10f), 0) }
        selBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(this@NoteActivity, Ui.PAPER, Ui.RULE, 14f)
            elevation = dp(6f).toFloat()
            setPadding(dp(14f), dp(6f), dp(6f), dp(6f))
            tag = "sel_bar"
            visibility = View.GONE
            addView(selLabel)
            fun pill(text: String, tag: String, fg: Int, bg: Int, f: () -> Unit) = addView(Ui.pill(this@NoteActivity, text, fg, bg).apply {
                this.tag = tag
                setOnClickListener { f() }
            }, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { marginStart = dp(6f) })
            pill("В текст", "sel_totext", Color.WHITE, Ui.ACCENT) { convertSelection() }
            pill("Удалить", "sel_delete", Ui.BAD, Ui.BAD_SOFT) { deleteSelection() }
            pill("Готово", "sel_done", Ui.INK, Ui.DESK) { clearSelection() }
        }
        area.addView(selBar, FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply {
            gravity = Gravity.TOP or Gravity.END
            setMargins(0, dp(10f), dp(14f), 0)
        })

        hintView = Ui.text(this, "", 13f, Ui.PAPER).apply {
            background = Ui.rounded(this@NoteActivity, Ui.INK, null, 999f)
            setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
            alpha = 0f
            gravity = Gravity.CENTER
        }
        area.addView(hintView, FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(18f)
        })
        rootView.addView(area, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
        buildKeysBar()
        refreshColors()
    }

    // ---------------------------------------------------------------- «Клавиатура»

    /** Строка ввода внизу экрана (видна, когда выбрана «Клавиатура»): над системной клавиатурой. */
    private fun buildKeysBar() {
        keysBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Ui.PAPER)
            setPadding(dp(10f), dp(6f), dp(6f), dp(6f))
            tag = "keys_bar"
            visibility = View.GONE
        }
        keysEdit = KeysEdit(this).apply {
            tag = "keys_edit"
            hint = "Пишите — текст встаёт в строку с курсором"
            textSize = 17f
            setTextColor(Ui.INK)
            setHintTextColor(Ui.MUTED)
            background = Ui.rounded(this@NoteActivity, Ui.DESK, Ui.RULE, 10f)
            setPadding(dp(12f), dp(9f), dp(12f), dp(9f))
            // Многострочное поле: длинная строка переносится, Enter — «\n», его ловим и делаем новую строку записи.
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
            maxLines = 4
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(e: Editable?) { if (!keysLoading) onKeysText(e?.toString().orEmpty()) }
            })
            onSelection = { _, _ ->
                if (!keysLoading && !keysTyping && tool == Tool.KEYS) { syncKeysCaret(); refreshCaretText() }
            }
            // «Стереть» в самом начале строки — соединить со строкой выше (как в обычном редакторе).
            setOnKeyListener { _, code, ev ->
                if (code == KeyEvent.KEYCODE_DEL && ev.action == KeyEvent.ACTION_DOWN && selectionStart == 0 && selectionEnd == 0) {
                    note.caretOffset = 0
                    deleteChar()
                    true
                } else false
            }
        }
        keysBar.addView(keysEdit, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        keysTask = Ui.ToolButton(this, R.drawable.ic_task, "задача").apply {
            tag = "keys_task"
            setOnClickListener { keysToggleTask() }
        }
        keysBar.addView(keysTask)
        keysBar.addView(Ui.ToolButton(this, R.drawable.ic_enter, "строка").apply {
            tag = "keys_enter"
            setOnClickListener { keysEdit.text.replace(max(0, keysEdit.selectionStart), max(0, keysEdit.selectionEnd), "\n") }
        })
        rootView.addView(View(this).apply {
            setBackgroundColor(Ui.RULE)
            tag = "keys_rule"
            visibility = View.GONE
        }, LinearLayout.LayoutParams(Ui.MATCH, dp(1f)))
        rootView.addView(keysBar, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
    }

    private fun showKeysBar(on: Boolean) {
        val v = if (on) View.VISIBLE else View.GONE
        keysBar.visibility = v
        rootView.findViewWithTag<View>("keys_rule")?.visibility = v
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        if (on) {
            loadKeysLine()
            showKeyboard()
        } else {
            imm.hideSoftInputFromWindow(keysEdit.windowToken, 0)
            keysEdit.clearFocus()
        }
    }

    private fun showKeyboard() {
        keysEdit.requestFocus()
        keysEdit.post {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(keysEdit, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    /** В строку ввода — текст строки с курсором, курсор поля — на его месте (на картинке — пусто: напишут — новая строка). */
    private fun loadKeysLine() {
        if (!::keysEdit.isInitialized) return
        val b = note.blocks.getOrNull(note.caret)
        val text = b?.let { blockText(it) }
        val value = text ?: ""
        val sel = if (text == null) 0 else caretPos(text)
        keysLoading = true
        try {
            if (keysEdit.text.toString() != value) keysEdit.setText(value)
            val at = sel.coerceIn(0, keysEdit.length())
            if (keysEdit.selectionStart != at || keysEdit.selectionEnd != at) keysEdit.setSelection(at)
        } finally {
            keysLoading = false
        }
        keysTask.selectedTool = b is Block.Task
        revealCaret()
    }

    /** Курсор листа — там же, где курсор строки ввода. */
    private fun syncKeysCaret() {
        val b = note.blocks.getOrNull(note.caret) ?: return
        val text = blockText(b) ?: return
        val sel = keysEdit.selectionEnd
        note.caretOffset = if (sel < 0 || sel >= text.length) -1 else sel
    }

    /** Ввели с клавиатуры: текст поля — это строка записи. Enter (или вставка с переносами) — новые строки. */
    private fun onKeysText(s: String) {
        if (tool != Tool.KEYS) return
        if ('\n' in s) { keysSplit(s); return }
        keysTyping = true
        try {
            val now = android.os.SystemClock.uptimeMillis()
            val b0 = note.blocks.getOrNull(note.caret)
            val newLine = b0 == null || blockText(b0) == null
            if (newLine || b0 !== keysBlock || now - keysEditAt > 2000) pushUndo()
            keysEditAt = now
            val b = caretTextBlock()
            keysBlock = b
            setBlockText(b, s)
            syncKeysCaret()
            if (newLine) renderBlocks() else refreshCaretText()
            changed(render = false)
        } finally {
            keysTyping = false
        }
        revealCaret()
    }

    /** Enter в строке ввода: строка делится по курсору; у задачи — следующая задача, пустая задача — обычная строка. */
    private fun keysSplit(s: String) {
        pushUndo()
        keysBlock = null
        val parts = s.split('\n')
        val b = caretTextBlock()
        val task = b is Block.Task
        if (task && parts.size == 2 && parts[0].isBlank() && parts[1].isBlank()) {
            note.blocks[note.caret] = Block.Para("")
            note.caretOffset = -1
            changed()
            return
        }
        setBlockText(b, parts[0])
        var idx = note.caret
        for (k in 1 until parts.size) {
            idx += 1
            note.blocks.add(idx, if (task) Block.Task(parts[k], false) else Block.Para(parts[k]))
        }
        note.caret = idx
        // Курсор — где был в поле (после Enter — в начале новой строки).
        val last = parts.last()
        val off = max(0, keysEdit.selectionEnd) - (s.lastIndexOf('\n') + 1)
        note.caretOffset = if (off < 0 || off >= last.length) -1 else off
        changed()
    }

    /** «задача»: строка с курсором — задача с галочкой и обратно. */
    private fun keysToggleTask() {
        pushUndo()
        keysBlock = null
        val b = caretTextBlock()
        note.blocks[note.caret] = when (b) {
            is Block.Task -> Block.Para(b.text)
            is Block.Para -> Block.Task(b.text, false)
            else -> b
        }
        changed()
        showKeyboard()
    }

    /** Строку с курсором — на виду (клавиатура занимает пол-экрана). */
    private fun revealCaret() {
        scroll.post {
            val v = caretTextView ?: return@post
            v.requestRectangleOnScreen(Rect(0, 0, v.width, v.height), false)
        }
    }

    private fun addTool(row: LinearLayout, t: Tool, icon: Int, label: String) {
        val b = Ui.ToolButton(this, icon, label).apply { setOnClickListener { setTool(t) } }
        b.tag = "tool_" + t.name.lowercase()
        toolButtons[t] = b
        row.addView(b)
    }

    private fun setTool(t: Tool) {
        if (t != Tool.WRITE) current?.let { commit(it) }
        if (t != Tool.SELECT && t != Tool.PIPETTE) clearSelection()
        val wasKeys = tool == Tool.KEYS
        tool = t
        if (t == Tool.KEYS && !wasKeys) {
            showKeysBar(true)
            hint("Пишите с клавиатуры — текст встаёт в строку с курсором. Нажмите на строку, чтобы её исправить", 3500)
        } else if (t != Tool.KEYS && wasKeys) {
            showKeysBar(false)
        }
        for ((k, v) in toolButtons) v.selectedTool = k == t
        updateImageBar()
        if (t != Tool.SHAPE) shapeTool.reset()
        if (t == Tool.SHAPE && !shapeHintShown) {
            shapeHintShown = true
            hint("Нарисуйте линию, стрелку, квадрат, круг или треугольник — станет ровной фигурой", 3500)
        }
        refreshColors()
    }

    private fun colorTool(t: Tool) = t == Tool.PEN || t == Tool.MARKER || t == Tool.SHAPE

    /** Выбран цвет: ручка/маркер/фигуры рисуют им. remember — поставить первым среди последних. */
    private fun setColor(c: Int, remember: Boolean = true) {
        penColor = c
        if (remember) ColorKit.remember(this, c)
        if (!colorTool(tool)) setTool(Tool.PEN) else refreshColors()
    }

    private fun startPipette() {
        current?.let { commit(it) }
        toolBeforePipette = if (colorTool(tool)) tool else Tool.PEN
        pipetteSnap = null
        setTool(Tool.PIPETTE)
        hint("Пипетка: коснитесь пером или пальцем того, чей цвет взять", 3500)
    }

    private fun refreshColors() {
        val rec = ColorKit.recent(this)
        for ((i, v) in quickColors.withIndex()) {
            val c = rec[i]
            val selected = c == penColor && colorTool(tool)
            v.background = ColorKit.swatch(this, c, false)
            v.scaleX = if (selected) 1.25f else 1f
            v.scaleY = v.scaleX
            v.alpha = if (selected || colorTool(tool)) 1f else 0.55f
        }
    }

    private fun hint(text: String, ms: Long = 2600) {
        hintView.text = text
        hintView.animate().cancel()
        hintView.alpha = 1f
        hintView.animate().alpha(0f).setStartDelay(ms).setDuration(300).start()
    }

    private fun updateBanner() {
        if (!::banner.isInitialized) return
        val ru = engine.language("ru")
        when (ru.state) {
            HandwritingEngine.State.READY -> banner.visibility = View.GONE
            HandwritingEngine.State.DOWNLOADING -> {
                banner.visibility = View.VISIBLE
                bannerText.text = "Русская модель: ${engine.stateText(ru)}"
                bannerButton.visibility = View.GONE
            }
            HandwritingEngine.State.MISSING, HandwritingEngine.State.ERROR -> {
                banner.visibility = View.VISIBLE
                bannerText.text = if (ru.state == HandwritingEngine.State.ERROR) {
                    "Модель распознавания: ${engine.stateText(ru)}"
                } else {
                    "Для письма в текст нужна русская модель распознавания (около 20 МБ, один раз)."
                }
                bannerButton.visibility = if (ru.model != null) View.VISIBLE else View.GONE
            }
            else -> {
                banner.visibility = View.VISIBLE
                bannerText.text = "Проверяю модель распознавания…"
                bannerButton.visibility = View.GONE
            }
        }
    }

    // ---------------------------------------------------------------- блоки на листе

    private fun textStyle(tv: TextView) {
        tv.textSize = 19f
        tv.setTextColor(Ui.INK)
        tv.setExactLineHeight(lineHeightPx)
        tv.tag = RuledSheet.RULED
        tv.minHeight = lineHeightPx
    }

    // Что сейчас на листе (0.27.6): строки те же и на тех же местах — меняется только текст, лист не собирается заново.
    // Раньше после каждого распознанного слова пересоздавались все строки записи — чем длиннее запись, тем дольше.
    private var shownKey: List<Any?> = emptyList()
    private val lineViews = ArrayList<TextView?>()
    private var titleBig: TextView? = null
    private var taskTextView: TextView? = null

    /** Строки (сами строки — по ссылке), галочки задач, размер и место картинок, выбранная картинка. */
    private fun structureKey(): List<Any?> {
        val k = ArrayList<Any?>(note.blocks.size * 2 + 1)
        k += selectedImage
        for (b in note.blocks) {
            k += b
            when (b) {
                is Block.Task -> k += b.done
                is Block.Image -> { k += b.wf; k += b.xa }
                else -> Unit
            }
        }
        return k
    }

    /** Лист не меняли местами — обновить только текст строк. false — надо собрать лист заново. */
    private fun renderTextsOnly(): Boolean {
        val key = structureKey()
        if (key != shownKey || lineViews.size != note.blocks.size) return false
        caretTextView = null
        var changedText = false
        for ((i, b) in note.blocks.withIndex()) {
            val tv = lineViews[i] ?: continue
            val t = blockText(b) ?: continue
            if (i == note.caret) { caretTextView = tv; changedText = true; continue } // строку курсора пишет refreshCaretText
            if (tv.text.toString() != t) { tv.text = t; changedText = true }
        }
        titleBig?.let { if (it.text.toString() != note.title) it.text = note.title }
        if (changedText) sheet.invalidate() // линейки листа — по новым строкам (строка могла стать в две)
        return true
    }

    private fun renderBlocks() {
        if (selectedImage != null && note.blocks.none { it === selectedImage }) selectedImage = null
        updateImageBar()
        if (renderTextsOnly()) {
            refreshCaretText()
            ink.invalidate()
            if (tool == Tool.KEYS && !keysTyping) loadKeysLine()
            return
        }
        sheet.removeAllViews()
        blockViews.clear()
        lineViews.clear()
        caretTextView = null
        val title = Ui.text(this, note.title, 26f, Ui.INK, bold = true).apply { setPadding(0, 0, 0, dp(2f)) }
        titleBig = title
        sheet.addView(title)
        sheet.addView(Ui.mono(this, Ui.noteMeta(note.created), 12f).apply { setPadding(0, 0, 0, dp(10f)) })
        note.blocks.forEachIndexed { i, b ->
            val v: View = when (b) {
                is Block.Para -> TextView(this).apply {
                    textStyle(this)
                    text = b.text
                    caretOnTap(this, i)
                    if (i == note.caret) caretTextView = this
                }
                is Block.Task -> taskView(b, i)
                is Block.Image -> imageView(b, i)
            }
            blockViews += v
            lineViews += when (b) {
                is Block.Para -> v as TextView
                is Block.Task -> taskTextView
                else -> null
            }
            val lp = LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP)
            if (v is NoteImageView) {
                // Вид картинки чуть шире текста — уголки у края листа не обрезаются.
                lp.leftMargin = -dp(NoteImageView.EDGE_DP)
                lp.rightMargin = -dp(NoteImageView.EDGE_DP)
            }
            sheet.addView(v, lp)
        }
        // Пустое место ниже текста: здесь тоже можно писать; нажатие — курсор в конец.
        val spacer = View(this).apply {
            minimumHeight = max(dp(420f), (resources.displayMetrics.heightPixels * 0.55f).toInt())
            setOnClickListener { setCaret(lastTextBlock()) }
        }
        sheet.addView(spacer, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        shownKey = structureKey()
        refreshCaretText()
        ink.invalidate()
        if (tool == Tool.KEYS && !keysTyping) loadKeysLine()
    }

    private fun taskView(b: Block.Task, i: Int): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val check = ImageView(this).apply {
            setImageResource(R.drawable.ic_check)
            imageTintList = ColorStateList.valueOf(if (b.done) Color.WHITE else Color.TRANSPARENT)
            background = Ui.rounded(this@NoteActivity, if (b.done) Ui.OK else Ui.PAPER, if (b.done) Ui.OK else Ui.INK2, 6f)
            setPadding(dp(3f), dp(3f), dp(3f), dp(3f))
            contentDescription = if (b.done) "Сдано" else "Не сдано"
            setOnClickListener { toggleTask(i) }
        }
        val box = FrameLayout(this).apply {
            // Текст стоит в верхней части строки (запас высоты — снизу), поэтому галочку
            // выравниваем по верху, а не по центру строки.
            addView(check, FrameLayout.LayoutParams(dp(24f), dp(24f)).apply {
                gravity = Gravity.TOP
                topMargin = dp(1f)
            })
        }
        row.addView(box, LinearLayout.LayoutParams(dp(34f), lineHeightPx))
        val text = TextView(this).apply {
            textStyle(this)
            text = b.text
            if (b.done) setTextColor(Ui.INK2)
            caretOnTap(this, i)
            if (i == note.caret) caretTextView = this
        }
        taskTextView = text
        row.addView(text, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        val state = Ui.chip(this, if (b.done) "сдано" else "не сдано", if (b.done) Ui.OK else Ui.BAD, if (b.done) Ui.OK_SOFT else Ui.BAD_SOFT)
        state.setOnClickListener { toggleTask(i) }
        row.addView(state, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply {
            topMargin = dp(2f)
            marginStart = dp(8f)
        })
        return row
    }

    private fun imageView(b: Block.Image, i: Int): View {
        // Картинку можно растянуть на всю ширину листа — загружаем с запасом по размеру.
        val bmp = bitmapCache.getOrPut(b.file) { store.loadImage(b, dp(800f)) ?: return emptyImage(i) }
        return NoteImageView(this, b, bmp, imageHost)
    }

    // ---------------------------------------------------------------- выбор и правка картинки

    private val imageHost = object : NoteImageView.Host {
        override fun isImageSelected(b: Block.Image) = b === selectedImage

        override fun onImageTap(v: NoteImageView) {
            runAfterCommit { selectImage(if (selectedImage === v.block) null else v.block) }
        }

        override fun onImageLongPress(v: NoteImageView) {
            AlertDialog.Builder(this@NoteActivity)
                .setMessage("Удалить картинку из записи?")
                .setPositiveButton("Удалить") { _, _ ->
                    val i = note.blocks.indexOfFirst { it === v.block }
                    if (i >= 0) removeBlock(i)
                }
                .setNegativeButton("Отмена", null)
                .show()
        }

        override fun onImageEditStart(v: NoteImageView) {
            imageEditing = true
            pushUndo()
            if (selectedImage !== v.block) selectImage(v.block)
        }

        private fun editEnded(): Boolean {
            imageEditing = false
            val r = renderPending
            renderPending = false
            return r
        }

        override fun onImageEditCancel(v: NoteImageView) {
            val needRender = editEnded()
            if (undoStack.isNotEmpty()) undoStack.removeLast()
            if (needRender) renderBlocks()
        }

        override fun onImageResized(v: NoteImageView) {
            changed(render = editEnded())
            ink.invalidate()
        }

        override fun onImageDropped(v: NoteImageView) {
            val needRender = editEnded()
            // Новое место — между строками, ближайшими к середине картинки.
            val from = note.blocks.indexOfFirst { it === v.block }
            val cy = v.top + v.translationY + v.height / 2f
            var pos = 0
            for ((j, bv) in blockViews.withIndex()) if (j != from && bv.top + bv.height / 2f < cy) pos++
            v.translationY = 0f
            if (from >= 0 && pos != from) {
                val caretBlock = note.blocks.getOrNull(note.caret)
                note.blocks.removeAt(from)
                note.blocks.add(pos.coerceIn(0, note.blocks.size), v.block)
                note.caret = note.blocks.indexOfFirst { it === caretBlock }.coerceAtLeast(0)
                changed()
            } else {
                v.invalidate()
                changed(render = needRender)
            }
        }
    }

    private fun selectImage(b: Block.Image?) {
        selectedImage = b
        for (v in blockViews) if (v is NoteImageView) v.invalidate()
        updateImageBar()
    }

    private fun updateImageBar() {
        if (!::imageBar.isInitialized) return
        imageBar.visibility = if (selectedImage != null) View.VISIBLE else View.GONE
        mini.visibility = if (selectedImage == null && tool == Tool.WRITE) View.VISIBLE else View.GONE
        if (::selBar.isInitialized) {
            pruneSelection()
            val on = selection.isNotEmpty() && selectedImage == null
            selBar.visibility = if (on) View.VISIBLE else View.GONE
            if (on) selLabel.text = "Выделено: " + Ui.plural(selection.size, "штрих", "штриха", "штрихов")
        }
    }

    // ---------------------------------------------------------------- «Выделить» и «В текст» (0.27.7)

    /**
     * Выделить написанное ручкой и превратить в напечатанный текст (Владимир: «как в приложении, с которого начали: выделил
     * то, что написал ручкой, — «преобразовать в текст»»). Перо обводит штрихи (штрих выбран, если внутри больше половины его
     * точек); «В текст» — распознать построчно (строки — по высоте, слова в строке — слева направо), каждая строка — строкой
     * текста у курсора; штрихи распознанных строк убираются. Один шаг «Отменить».
     */
    private val selection = ArrayList<Drawing>()
    private lateinit var selBar: LinearLayout
    private lateinit var selLabel: TextView
    private var converting = false

    private fun pruneSelection() {
        if (selection.isEmpty()) return
        val alive = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Drawing, Boolean>())
        alive.addAll(note.drawings)
        selection.retainAll { it in alive }
    }

    private fun clearSelection() {
        if (selection.isEmpty()) return
        selection.clear()
        updateImageBar()
        ink.invalidate()
    }

    override fun lassoMode(): Boolean = tool == Tool.SELECT

    override fun selectionRect(): android.graphics.RectF? {
        if (selection.isEmpty()) return null
        val r = android.graphics.RectF(selection[0].bounds())
        for (d in selection) r.union(d.bounds())
        return r
    }

    override fun onLasso(pts: FloatArray) {
        selection.clear()
        if (pts.size >= 6) {
            for (d in note.drawings) {
                val n = d.pts.size / 3
                if (n == 0) continue
                var inside = 0
                for (i in 0 until n) if (insidePolygon(pts, d.pts[i * 3], d.pts[i * 3 + 1])) inside++
                if (inside * 2 > n) selection += d
            }
        }
        if (selection.isEmpty() && pts.size >= 6) hint("Обведите пером то, что написали ручкой", 2200)
        updateImageBar()
        ink.invalidate()
    }

    private fun insidePolygon(p: FloatArray, x: Float, y: Float): Boolean {
        var c = false
        val n = p.size / 2
        var j = n - 1
        for (i in 0 until n) {
            val xi = p[i * 2]; val yi = p[i * 2 + 1]; val xj = p[j * 2]; val yj = p[j * 2 + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi + 1e-9f) + xi) c = !c
            j = i
        }
        return c
    }

    private fun deleteSelection() {
        pruneSelection()
        if (selection.isEmpty()) return
        pushUndo()
        val gone = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Drawing, Boolean>())
        gone.addAll(selection)
        note.drawings.removeAll { it in gone }
        selection.clear()
        changed(render = false)
        updateImageBar()
        ink.invalidate()
    }

    /** Строки выделенного: по высоте; в строке — слова слева направо, внутри слова — как писали. */
    private fun selectionLines(sel: List<Drawing>): List<List<Drawing>> {
        val order = java.util.IdentityHashMap<Drawing, Int>()
        for ((i, d) in note.drawings.withIndex()) order[d] = i
        val hs = sel.map { it.bounds().height() }.sorted()
        val mh = max(hs[hs.size / 2], Ui.dpf(this, 10f))
        val byY = sel.sortedBy { it.bounds().centerY() }
        val lines = ArrayList<ArrayList<Drawing>>()
        var cy = 0f
        for (d in byY) {
            val y = d.bounds().centerY()
            val cur = lines.lastOrNull()
            if (cur == null || y - cy > 0.8f * mh) {
                lines += arrayListOf(d); cy = y
            } else {
                cur += d
                cy = (cy * (cur.size - 1) + y) / cur.size
            }
        }
        return lines.map { line ->
            val byX = line.sortedBy { it.bounds().left }
            val words = ArrayList<ArrayList<Drawing>>()
            var right = -Float.MAX_VALUE
            for (d in byX) {
                val bb = d.bounds()
                if (words.isEmpty() || bb.left > right + 0.45f * mh) { words += arrayListOf(d); right = bb.right } else { words.last() += d; right = max(right, bb.right) }
            }
            words.flatMap { w -> w.sortedBy { order[it] ?: 0 } }
        }
    }

    private fun inkOf(line: List<Drawing>): List<InkStroke> {
        var t = 1_000L
        return line.map { d ->
            InkStroke(MotionEvent.TOOL_TYPE_STYLUS).apply {
                for (i in 0 until d.pts.size / 3) { points += InkPoint(d.pts[i * 3], d.pts[i * 3 + 1], t, d.pts[i * 3 + 2]); t += 8 }
                t += 120
            }
        }
    }

    private fun recognizeLine(strokes: List<InkStroke>, done: (String?) -> Unit) {
        testRecognizer?.let { done(it(strokes)?.trim()?.takeIf { s -> s.isNotEmpty() }); return }
        val ru = engine.language("ru")
        engine.recognize(ru, strokes, "", ink.width.toFloat(), lineHeightFor(strokes)) { out ->
            done(out.candidates.firstOrNull()?.text?.trim()?.takeIf { it.isNotEmpty() })
        }
    }

    private fun convertSelection() {
        pruneSelection()
        if (selection.isEmpty() || converting) return
        if (testRecognizer == null && engine.language("ru").state != HandwritingEngine.State.READY) {
            updateBanner()
            hint("Для «В текст» нужна русская модель распознавания — нажмите «Скачать» сверху", 3000)
            return
        }
        converting = true
        val lines = selectionLines(selection.toList())
        val texts = arrayOfNulls<String>(lines.size)
        var left = lines.size
        hint("Распознаю…", 1200)
        for ((k, line) in lines.withIndex()) {
            recognizeLine(inkOf(line)) { text ->
                texts[k] = text
                if (--left == 0) finishConvert(lines, texts)
            }
        }
    }

    private fun finishConvert(lines: List<List<Drawing>>, texts: Array<String?>) {
        converting = false
        val ok = lines.indices.filter { texts[it] != null }
        if (ok.isEmpty()) { hint("Не разобрал выделенное — осталось рисунком", 2500); return }
        runAfterCommit {
            pushUndo()
            val gone = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Drawing, Boolean>())
            for (k in ok) gone.addAll(lines[k])
            note.drawings.removeAll { it in gone }
            // Строки — у курсора: пустая строка курсора — первая из них, дальше — новые строки ниже.
            var at = note.caret
            val cur = note.blocks.getOrNull(at)
            var first = true
            for (k in ok) {
                val t = texts[k]!!
                if (first && cur != null && blockText(cur)?.isBlank() == true) {
                    setBlockText(cur, t)
                } else {
                    note.blocks.add(at + 1, Block.Para(t))
                    at += 1
                }
                first = false
            }
            note.caret = at
            note.caretOffset = -1
            selection.clear()
            changed()
            ink.invalidate()
            val words = ok.sumOf { texts[it]!!.split(Regex("\\s+")).size }
            hint("В текст: " + Ui.plural(words, "слово", "слова", "слов") + if (ok.size < lines.size) " (часть строк не разобрал — осталась рисунком)" else "", 2500)
        }
    }


    private fun deleteSelectedImage() {
        val i = note.blocks.indexOfFirst { it === selectedImage }
        selectedImage = null
        if (i >= 0) removeBlock(i) else updateImageBar()
    }

    private fun emptyImage(i: Int): View = Ui.text(this, "[картинка не найдена]", 14f, Ui.MUTED).apply {
        setOnLongClickListener { removeBlock(i); true }
    }

    /** Где в строке курсора показаны вставки (серое слово + курсор): позиция и длина. */
    private var shownInsertAt = 0
    private var shownInsertLen = 0

    /** Текст текущей строки + серое промежуточное слово + мигающий курсор — в месте курсора. */
    private fun refreshCaretText() {
        val tv = caretTextView ?: return
        val b = note.blocks.getOrNull(note.caret) ?: return
        val base = blockText(b) ?: return
        val pos = caretPos(base)
        val sb = SpannableStringBuilder(base.substring(0, pos))
        if (interim.isNotEmpty()) {
            val start = sb.length
            val shown = spacedInsert(base.substring(0, pos), base.substring(pos), interim)
            sb.append(shown)
            sb.setSpan(ForegroundColorSpan(Ui.MUTED), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val c = sb.length
        sb.append("▏")
        sb.setSpan(ForegroundColorSpan(if (caretOn) Ui.ACCENT else Color.TRANSPARENT), c, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        shownInsertAt = pos
        shownInsertLen = sb.length - pos
        sb.append(base.substring(pos))
        tv.text = sb
    }

    private fun lastTextBlock(): Int {
        for (i in note.blocks.indices.reversed()) if (note.blocks[i] !is Block.Image) return i
        return note.blocks.size - 1
    }

    /** Поставить курсор в строку i; offset — место внутри строки (-1 — в конец). */
    private fun setCaret(i: Int, offset: Int = -1) {
        runAfterCommit {
            selectedImage = null
            note.caret = i.coerceIn(0, note.blocks.size - 1)
            val text = blockText(note.blocks[note.caret])
            note.caretOffset = if (text == null || offset < 0 || offset >= text.length) -1 else offset
            interim = ""
            renderBlocks()
            changed(render = false)
            if (tool == Tool.KEYS) showKeyboard()
        }
    }

    /** Нажатие пальцем на строку ставит курсор в то место слова, куда нажали. */
    private fun caretOnTap(tv: TextView, i: Int) {
        val last = FloatArray(2)
        tv.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) { last[0] = e.x; last[1] = e.y }
            false
        }
        tv.setOnClickListener {
            var off = tv.getOffsetForPosition(last[0], last[1])
            if (i == note.caret && off > shownInsertAt) off = max(shownInsertAt, off - shownInsertLen)
            setCaret(i, off)
        }
    }

    /** Кнопка, которая повторяет действие, пока её держат. */
    private fun repeatOnHold(action: () -> Unit): View.OnTouchListener {
        var repeat: Runnable? = null
        return View.OnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    action()
                    val r = object : Runnable {
                        override fun run() {
                            action()
                            handler.postDelayed(this, 70)
                        }
                    }
                    repeat = r
                    handler.postDelayed(r, 450)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    repeat?.let { handler.removeCallbacks(it) }
                    repeat = null
                }
            }
            true
        }
    }

    // ---------------------------------------------------------------- правка текста

    private fun blockText(b: Block): String? = when (b) {
        is Block.Para -> b.text
        is Block.Task -> b.text
        else -> null
    }

    private fun setBlockText(b: Block, t: String) {
        when (b) {
            is Block.Para -> b.text = t
            is Block.Task -> b.text = t
            else -> Unit
        }
    }

    /** Позиция курсора в строке text (offset -1 или за концом — конец строки). */
    private fun caretPos(text: String): Int =
        if (note.caretOffset < 0 || note.caretOffset > text.length) text.length else note.caretOffset

    private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '-'

    /**
     * Что вставить у курсора с учётом пробелов. Если курсор поставлен пальцем внутрь текста
     * и вставка короткая (1–2 знака) рядом со словом — без пробелов: это исправление буквы.
     * При обычном письме в конец строки — слова через пробел («Привет» + «на» = «Привет на»).
     */
    private fun spacedInsert(before: String, after: String, word: String): String {
        if (word.isEmpty()) return word
        val placedCaret = note.caretOffset >= 0
        val touchesWord = (before.isNotEmpty() && isWordChar(before.last())) || (after.isNotEmpty() && isWordChar(after.first()))
        if (placedCaret && word.length <= 2 && word.all { isWordChar(it) } && touchesWord) return word
        val lead = before.isNotEmpty() && !before.last().isWhitespace() && word.first() !in ".,!?;:)»…"
        val trail = after.isNotEmpty() && !after.first().isWhitespace() && after.first() !in ".,!?;:)»…" && !word.last().isWhitespace()
        return (if (lead) " " else "") + word + (if (trail) " " else "")
    }

    /** Блок под курсором, в который можно писать текст (если курсор на картинке — новая строка после неё). */
    private fun caretTextBlock(): Block {
        val b = note.blocks.getOrNull(note.caret)
        if (b == null || b is Block.Image) {
            note.blocks.add(note.caret + 1, Block.Para(""))
            note.caret += 1
            note.caretOffset = -1
        }
        return note.blocks[note.caret]
    }

    /** Вставить текст у курсора. raw = true — как есть, без расстановки пробелов. */
    private fun insertAtCaret(word: String, raw: Boolean = false) {
        if (word.isEmpty()) return
        pushUndo()
        val b = caretTextBlock()
        val text = blockText(b) ?: return
        val pos = caretPos(text)
        val before = text.substring(0, pos)
        val after = text.substring(pos)
        val ins = if (raw) word else spacedInsert(before, after, word)
        setBlockText(b, before + ins + after)
        if (note.caretOffset >= 0) note.caretOffset = pos + ins.length
        changed()
    }

    private fun editCaret(f: (String) -> String) {
        pushUndo()
        val b = caretTextBlock()
        blockText(b)?.let { setBlockText(b, f(it)) }
        note.caretOffset = -1
        changed()
    }

    private var lastCharDelete = 0L

    /** Стереть одну букву перед курсором. В начале строки — соединить со строкой выше. */
    private fun deleteChar() {
        val b = note.blocks.getOrNull(note.caret) ?: return
        val text = blockText(b)
        val now = android.os.SystemClock.uptimeMillis()
        val coalesce = now - lastCharDelete < 1500 // серия нажатий — один шаг отмены
        lastCharDelete = now
        if (text == null) {
            hint("Курсор на картинке — нажмите на строку с текстом")
            return
        }
        val pos = caretPos(text)
        if (pos > 0) {
            if (!coalesce) pushUndo()
            val cut = text.offsetByCodePoints(pos, -1)
            setBlockText(b, text.substring(0, cut) + text.substring(pos))
            if (note.caretOffset >= 0) note.caretOffset = cut
            changed()
            return
        }
        // Курсор в начале строки: соединяем с предыдущей строкой (как на клавиатуре).
        val prevIndex = note.caret - 1
        val prev = note.blocks.getOrNull(prevIndex)
        val prevText = prev?.let { blockText(it) }
        if (prev == null || prevText == null) {
            if (text.isEmpty() && note.blocks.size > 1) {
                if (!coalesce) pushUndo()
                note.blocks.removeAt(note.caret)
                note.caret = max(0, note.caret - 1)
                note.caretOffset = -1
                changed()
            }
            return
        }
        if (!coalesce) pushUndo()
        setBlockText(prev, prevText + text)
        note.blocks.removeAt(note.caret)
        note.caret = prevIndex
        note.caretOffset = if (text.isEmpty()) -1 else prevText.length
        changed()
    }

    /** Стереть слово перед курсором (вместе с пробелами вокруг). */
    private fun deleteWord() {
        val b = note.blocks.getOrNull(note.caret)
        val text = b?.let { blockText(it) }
        if (b != null && text != null && text.isNotBlank()) {
            val pos = caretPos(text)
            val before = text.substring(0, pos).replace(Regex("""\s*\S+\s*$"""), "")
            if (before.length == pos) return
            pushUndo()
            setBlockText(b, before + text.substring(pos))
            if (note.caretOffset >= 0) note.caretOffset = before.length
            changed()
        } else if (note.blocks.size > 1) {
            pushUndo()
            note.blocks.removeAt(note.caret)
            note.caret = max(0, note.caret - 1)
            note.caretOffset = -1
            changed()
        }
    }

    /** Новая строка (или задача). Если курсор внутри строки — текст после курсора переносится. */
    private fun newLine(task: Boolean) {
        pushUndo()
        val b = note.blocks.getOrNull(note.caret)
        val text = b?.let { blockText(it) }
        if (task && b is Block.Para && b.text.isBlank()) {
            note.blocks[note.caret] = Block.Task("", false)
        } else if (b != null && text != null) {
            val pos = caretPos(text)
            setBlockText(b, text.substring(0, pos).trimEnd())
            val rest = text.substring(pos).trimStart()
            note.blocks.add(note.caret + 1, if (task) Block.Task(rest, false) else Block.Para(rest))
            note.caret += 1
            note.caretOffset = if (rest.isEmpty()) -1 else 0
        } else {
            note.blocks.add(note.caret + 1, if (task) Block.Task("", false) else Block.Para(""))
            note.caret += 1
            note.caretOffset = -1
        }
        changed()
    }

    private fun toggleTask(i: Int) {
        runAfterCommit {
            val b = note.blocks.getOrNull(i) as? Block.Task ?: return@runAfterCommit
            pushUndo()
            b.done = !b.done
            changed()
        }
    }

    private fun removeBlock(i: Int) {
        pushUndo()
        note.blocks.removeAt(i)
        if (note.blocks.isEmpty()) note.blocks += Block.Para("")
        note.caret = note.caret.coerceIn(0, note.blocks.size - 1)
        changed()
    }

    private fun editCaretDialog() {
        val b = note.blocks.getOrNull(note.caret)
        val text = when (b) { is Block.Para -> b.text; is Block.Task -> b.text; else -> null }
        if (text == null) {
            hint("Нажмите на строку с текстом, чтобы её исправить")
            return
        }
        val edit = EditText(this).apply {
            setText(text)
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val box = FrameLayout(this).apply { setPadding(dp(20f), dp(8f), dp(20f), 0); addView(edit) }
        AlertDialog.Builder(this)
            .setTitle("Исправить строку")
            .setView(box)
            .setPositiveButton("Готово") { _, _ -> editCaret { edit.text.toString() } }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun renameNote() {
        val edit = EditText(this).apply {
            setText(note.title)
            setSelection(note.title.length)
            isSingleLine = true
        }
        val box = FrameLayout(this).apply { setPadding(dp(20f), dp(8f), dp(20f), 0); addView(edit) }
        AlertDialog.Builder(this)
            .setTitle("Название записи")
            .setView(box)
            .setPositiveButton("Готово") { _, _ ->
                val t = edit.text.toString().trim()
                if (t.isNotEmpty()) {
                    pushUndo()
                    note.title = t
                    titleView.text = t
                    changed()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ---------------------------------------------------------------- отмена и сохранение

    private fun copyBlock(b: Block): Block = when (b) {
        is Block.Para -> Block.Para(b.text)
        is Block.Task -> Block.Task(b.text, b.done)
        is Block.Image -> Block.Image(b.file, b.width, b.height, b.wf, b.xa)
    }

    private fun snap() = Snap(note.title, note.blocks.map { copyBlock(it) }, ArrayList(note.drawings), note.caret, note.caretOffset)

    private fun restore(x: Snap): Note =
        Note(note.id, x.title, note.created, note.updated, x.blocks.mapTo(ArrayList()) { copyBlock(it) }, ArrayList(x.drawings), x.caret)
            .apply { caretOffset = x.caretOffset }

    private fun pushUndo() {
        undoStack.addLast(snap())
        while (undoStack.size > 60) undoStack.removeFirst()
        redoStack.clear()
    }

    private fun undo() {
        val g = current
        if (g != null && g.strokes.isNotEmpty()) {
            // Сначала убираем ещё не распознанные штрихи — по одному.
            g.strokes.removeAt(g.strokes.lastIndex)
            g.resultFor = -1
            interim = ""
            handler.removeCallbacks(interimRunnable)
            handler.removeCallbacks(commitRunnable)
            if (g.strokes.isEmpty()) current = null else handler.postDelayed(interimRunnable, 150)
            refreshCaretText()
            ink.invalidate()
            return
        }
        if (commitQueue.isNotEmpty() || undoStack.isEmpty()) return
        redoStack.addLast(snap())
        note = restore(undoStack.removeLast())
        titleView.text = note.title
        changed()
    }

    private fun redo() {
        if (redoStack.isEmpty() || commitQueue.isNotEmpty() || current != null) return
        undoStack.addLast(snap())
        note = restore(redoStack.removeLast())
        titleView.text = note.title
        changed()
    }

    private fun changed(render: Boolean = true) {
        if (render) {
            if (imageEditing) renderPending = true else renderBlocks()
        }
        handler.removeCallbacks(saveRunnable)
        handler.postDelayed(saveRunnable, 400)
    }

    /**
     * Сохранить запись. Текст файла собирается здесь (рисунки — готовыми строками, быстро), пишется в фоне (0.27.6: раньше
     * запись целиком переводилась в JSON и писалась на главном потоке после каждой паузы в письме — перо замирало).
     * [wait] — дождаться записи (уход с экрана). Один фоновый поток — файлы пишутся по порядку.
     */
    private fun saveNow(wait: Boolean = false) {
        handler.removeCallbacks(saveRunnable)
        val text = try { store.prepare(note) } catch (t: Throwable) { saveFailed(t); return }
        val id = note.id
        val job = try { io.submit { try { store.write(id, text) } catch (t: Throwable) { handler.post { saveFailed(t) } } } } catch (t: Throwable) { null }
        if (job == null) { try { store.write(id, text) } catch (t: Throwable) { saveFailed(t) }; return }
        if (wait) try { job.get() } catch (_: Throwable) {}
    }

    private fun saveFailed(t: Throwable) {
        Toast.makeText(this, "Не удалось сохранить: ${t.message}", Toast.LENGTH_LONG).show()
    }

    // ---------------------------------------------------------------- картинки и итоги

    private fun pickImage() {
        current?.let { commit(it) }
        val i = Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent.createChooser(i, "Выберите картинку"), REQ_IMAGE)
        } catch (t: Throwable) {
            hint("Не нашлось приложения для выбора картинки")
        }
    }

    @Deprecated("Activity.onActivityResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_IMAGE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        hint("Добавляю картинку…", 1500)
        io.execute {
            val block = try { store.importImage(this, uri) } catch (t: Throwable) { null }
            handler.post {
                if (block == null) {
                    hint("Не удалось открыть картинку")
                    return@post
                }
                runAfterCommit {
                    pushUndo()
                    note.blocks.add(note.caret + 1, block)
                    note.blocks.add(note.caret + 2, Block.Para(""))
                    note.caret += 2
                    selectedImage = block
                    changed()
                }
            }
        }
    }

    private fun showSummary() {
        runAfterCommit {
            val tasks = note.blocks.filterIsInstance<Block.Task>().filter { it.text.isNotBlank() }
            val open = tasks.filter { !it.done }
            val done = tasks.filter { it.done }
            val text = buildString {
                append("Не сдано · ").append(open.size).append('\n')
                open.forEach { append("— ").append(it.text).append('\n') }
                if (open.isEmpty()) append("нет\n")
                append("\nСдано · ").append(done.size).append('\n')
                done.forEach { append("— ").append(it.text).append('\n') }
                if (done.isEmpty()) append("нет\n")
            }
            AlertDialog.Builder(this)
                .setTitle("Итоги записи")
                .setMessage(text + "\nЗдесь будет ИИ-помощник: кратко пересказать совещание и выписать задачи со сроками. Подключим отдельным этапом.")
                .setPositiveButton("Скопировать") { _, _ ->
                    getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(note.title, note.title + "\n\n" + text))
                    hint("Итоги скопированы")
                }
                .setNegativeButton("Закрыть", null)
                .show()
        }
    }

    // ---------------------------------------------------------------- NoteInkView.Host

    override fun contentOffset(): PointF {
        val a = IntArray(2)
        val b = IntArray(2)
        ink.getLocationInWindow(a)
        sheet.getLocationInWindow(b)
        return PointF((b[0] - a[0]).toFloat(), (b[1] - a[1]).toFloat())
    }

    override fun forwardToSheet(ev: MotionEvent) {
        val e = MotionEvent.obtain(ev)
        scroll.dispatchTouchEvent(e)
        e.recycle()
    }

    override fun passThrough(): Boolean = tool == Tool.HAND || tool == Tool.KEYS
    override fun pipetteMode(): Boolean = tool == Tool.PIPETTE

    /** Цвет под точкой слоя пера: рисунок рядом — его цвет, иначе пиксель листа (текст, картинка). */
    override fun pipetteColor(x: Float, y: Float): Int? {
        val o = contentOffset()
        ColorKit.drawingNear(note.drawings, x - o.x, y - o.y, Ui.dpf(this, 10f))?.let { return it.color }
        val snap = pipetteSnap ?: ColorKit.snapshot(scroll)?.also { pipetteSnap = it }
        return snap?.colorAt(x, y)
    }

    override fun onPipettePick(x: Float, y: Float) {
        val c = pipetteColor(x, y) ?: return
        pipetteSnap = null
        penColor = c
        ColorKit.remember(this, c)
        setTool(toolBeforePipette)
        hint("Цвет взят")
    }
    override fun fingerWrites(): Boolean = fingerWrites
    override fun isEraser(): Boolean = tool == Tool.ERASER

    override fun strokeStyle(): NoteInkView.StrokeStyle = when (tool) {
        Tool.MARKER -> NoteInkView.StrokeStyle("marker", penColor, Ui.dpf(this, 16f))
        Tool.PEN -> NoteInkView.StrokeStyle("pen", penColor, Ui.dpf(this, 2.4f))
        Tool.SHAPE -> NoteInkView.StrokeStyle("shape", penColor, Ui.dpf(this, 2.4f))
        else -> NoteInkView.StrokeStyle("write", Ui.INK, Ui.dpf(this, 2.6f))
    }

    override fun onStrokeStart(x: Float, y: Float) {
        if (selectedImage != null) selectImage(null)
        if (tool != Tool.WRITE) return
        handler.removeCallbacks(interimRunnable)
        handler.removeCallbacks(commitRunnable)
        val g = current
        if (g != null && g.strokes.isNotEmpty() && (isFar(g, x, y) || isNextWord(g, x, y))) commit(g)
        if (current == null) current = Group(++groupSeq)
    }

    override fun onStrokeEnd(stroke: InkStroke, style: NoteInkView.StrokeStyle, tap: Boolean) {
        if (tap && tapOnCheckbox(stroke.points[0].x, stroke.points[0].y)) {
            if (current?.strokes?.isEmpty() == true) current = null
            if (current != null) scheduleWrite()
            return
        }
        if (style.kind == "write") {
            val g = current ?: Group(++groupSeq).also { current = it }
            g.strokes += stroke
            scheduleWrite()
        } else if (style.kind == "shape") {
            pushUndo()
            val r = shapeTool.onStroke(
                stroke, style, note.drawings, Ui.dpf(this, 24f), Ui.dpf(this, 4f), android.os.SystemClock.uptimeMillis(),
            )
            note.drawings.removeAll { d -> r.remove.any { it === d } }
            note.drawings += r.add
            changed(render = false)
        } else {
            pushUndo()
            note.drawings += NoteInkView.toDrawing(stroke, style)
            changed(render = false)
        }
        ink.invalidate()
    }

    private var eraseUndoPushed = false

    override fun onErase(x: Float, y: Float, first: Boolean) {
        if (first) eraseUndoPushed = false
        val r = Ui.dpf(this, 14f)
        // Как в рисовалке (0.27.6): стирается только участок под ластиком, куски по краям остаются.
        val keep = InkEraser.eraseIn(note.drawings, x, y, r) ?: return
        if (!eraseUndoPushed) { pushUndo(); eraseUndoPushed = true } // одно движение ластиком — один шаг «Отменить»
        note.drawings.clear()
        note.drawings.addAll(keep)
        ink.eraseHole(x, y, r)
        changed(render = false)
        ink.invalidate()
    }

    override fun drawings(): List<Drawing> = note.drawings

    override fun pendingStrokes(): List<InkStroke> {
        val out = ArrayList<InkStroke>()
        for (g in commitQueue) out += g.strokes
        current?.let { out += it.strokes }
        return out
    }

    private fun tapOnCheckbox(x: Float, y: Float): Boolean {
        for ((i, v) in blockViews.withIndex()) {
            if (note.blocks.getOrNull(i) !is Block.Task) continue
            if (y >= v.top && y <= v.bottom && x >= v.left && x <= v.left + dp(40f)) {
                toggleTask(i)
                return true
            }
        }
        return false
    }

    // ---------------------------------------------------------------- распознавание

    private fun scheduleWrite() {
        handler.removeCallbacks(interimRunnable)
        handler.removeCallbacks(commitRunnable)
        handler.postDelayed(interimRunnable, 180)
        handler.postDelayed(commitRunnable, pauseMs)
    }

    private fun isFar(g: Group, x: Float, y: Float): Boolean {
        val b = NoteInkView.bounds(g.strokes)
        val h = b[3] - b[1]
        val pad = max(Ui.dpf(this, 60f), h * 2.2f)
        return x < b[0] - pad || x > b[2] + pad || y < b[1] - pad || y > b[3] + pad
    }

    /**
     * Перо оторвали и начали следующее слово (0.27.7, Владимир: «написал слово, отрываю ручку — как пробел — и пишу следующее;
     * логично, чтобы предыдущее сразу становилось текстом, а не занимало страницу»). Раньше слово ждало паузы в 1 с или
     * прыжка дальше 60 dp — обычный промежуток между словами меньше, и вся строка копилась одной надписью.
     * Новое слово: штрих начат правее надписи на промежуток ≥ 0,45 высоты букв (между буквами — меньше), или строкой ниже,
     * или заметно левее/выше. Точки над «ё», «й» и перекладины — над словом или внутри него, слово не рвут.
     */
    private fun isNextWord(g: Group, x: Float, y: Float): Boolean {
        val b = NoteInkView.bounds(g.strokes)
        val typical = if (wordHeights.isNotEmpty()) wordHeights.sorted()[wordHeights.size / 2] * 0.8f else 0f
        val h = maxOf(b[3] - b[1], typical, Ui.dpf(this, 14f))
        return x > b[2] + 0.45f * h || y > b[3] + 0.6f * h || y < b[1] - 1.0f * h || x < b[0] - 0.8f * h
    }

    private fun lineHeightFor(strokes: List<InkStroke>): Float {
        val base = if (wordHeights.isNotEmpty()) wordHeights.sorted()[wordHeights.size / 2] else {
            val b = NoteInkView.bounds(strokes); b[3] - b[1]
        }
        val lo = Ui.dpf(this, 40f)
        return (base * 1.15f).coerceIn(lo, max(lo, ink.height.toFloat()))
    }

    private fun preContext(): String {
        // Текст перед курсором (до 20 знаков): строки выше + начало текущей строки до курсора.
        val sb = StringBuilder()
        for (i in 0..min(note.caret, note.blocks.size - 1)) {
            val t = blockText(note.blocks[i]) ?: continue
            val part = if (i == note.caret) t.substring(0, caretPos(t)) else t
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(part)
        }
        return sb.takeLast(20).toString()
    }

    private fun recognize(g: Group) {
        val ru = engine.language("ru")
        val n = g.strokes.size
        if (n == 0) return
        if (ru.state != HandwritingEngine.State.READY) {
            updateBanner()
            if (g.committing) {
                g.candidates = emptyList()
                g.error = "модель распознавания не скачана"
                g.resultFor = n
                flush()
            }
            return
        }
        val snapshot = ArrayList(g.strokes)
        engine.recognize(ru, snapshot, preContext(), ink.width.toFloat(), lineHeightFor(snapshot)) { out ->
            if (n < g.resultFor) return@recognize
            g.candidates = if (out.error == null) out.candidates else emptyList()
            g.error = out.error
            g.resultFor = n
            if (g === current && n == g.strokes.size) {
                interim = out.candidates.firstOrNull()?.text?.trim().orEmpty()
                refreshCaretText()
            }
            if (g.committing) flush()
        }
    }

    private fun commit(g: Group) {
        handler.removeCallbacks(commitRunnable)
        handler.removeCallbacks(interimRunnable)
        if (g === current) current = null
        if (g.strokes.isEmpty() || g.committing) return
        g.committing = true
        commitQueue.addLast(g)
        if (g.resultFor == g.strokes.size) flush() else recognize(g)
    }

    /** Вставить в текст все готовые группы по порядку; затем выполнить отложенные действия. */
    private fun flush() {
        while (commitQueue.isNotEmpty()) {
            val h = commitQueue.first()
            if (h.resultFor != h.strokes.size) break
            commitQueue.removeFirst()
            applyGroup(h)
        }
        val cur = current
        interim = if (cur != null && cur.resultFor == cur.strokes.size) cur.candidates?.firstOrNull()?.text?.trim().orEmpty() else ""
        refreshCaretText()
        ink.invalidate()
        if (commitQueue.isEmpty() && current == null) {
            while (afterCommit.isNotEmpty()) afterCommit.removeFirst().invoke()
        }
    }

    private fun applyGroup(g: Group) {
        val best = g.candidates?.firstOrNull()?.text?.trim().orEmpty()
        val style = NoteInkView.StrokeStyle("write", Ui.INK, Ui.dpf(this, 2.6f))
        // Крупная стрелка, рамка, круг вокруг слова — это рисунок, а не буква: делаем ровную фигуру.
        val textSize = if (wordHeights.isNotEmpty()) max(lineHeightPx.toFloat(), wordHeights.sorted()[wordHeights.size / 2]) else lineHeightPx.toFloat()
        ShapeTool.fromWriting(g.strokes, best, textSize * 2.6f, Ui.dpf(this, 4f))?.let { shape ->
            pushUndo()
            note.drawings += ShapeTool.drawingOf(shape, NoteInkView.StrokeStyle("pen", Ui.INK, Ui.dpf(this, 2.4f)))
            changed(render = false)
            return
        }
        if (best.isEmpty()) {
            pushUndo()
            for (s in g.strokes) note.drawings += NoteInkView.toDrawing(s, style)
            hint(g.error?.let { "Не распознано (${HandwritingEngine.shortError(it)}) — оставил рисунком" }
                ?: "Не разобрал надпись — оставил её рисунком")
            changed(render = false)
            return
        }
        val bb = NoteInkView.bounds(g.strokes)
        wordHeights += bb[3] - bb[1]
        while (wordHeights.size > 15) wordHeights.removeAt(0)
        ink.fade(g.strokes, style)
        insertAtCaret(best)
    }

    /** Выполнить действие после того, как недописанные слова станут текстом (чтобы не сбить порядок). */
    private fun runAfterCommit(action: () -> Unit) {
        current?.let { commit(it) }
        if (commitQueue.isEmpty() && current == null) action() else afterCommit.addLast(action)
    }

    companion object {
        const val EXTRA_ID = "note_id"
        private const val REQ_IMAGE = 41

        /** Для проверок: распознавание выделенного без ML Kit (в Robolectric его нет). */
        internal var testRecognizer: ((List<InkStroke>) -> String?)? = null
    }
}
