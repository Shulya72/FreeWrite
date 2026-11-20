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
    private var penColor = Ui.PEN_INK
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

    // «Клавиатура» (0.27.9): строка с курсором на листе — поле ввода [keysEdit]. Нижние панели (0.30): при клавиатуре —
    // [keysBarBottom], при пере — [penBar].
    private lateinit var keysBarBottom: View
    private lateinit var keysRow: LinearLayout
    private lateinit var penBar: View
    private lateinit var penRow: LinearLayout
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
        engine = HandwritingEngine { android.util.Log.i("ViaDesk", it) }
        engine.onChanged = { updateBanner() }
        buildUi()
        setContentView(rootView)
        rootView.requestFocus()
        engine.start(this)
        renderBlocks()
        updateBanner()
        if (intent.getBooleanExtra(EXTRA_KEYS, false)) {
            // Новая заметка (0.30): сразу клавиатура, курсор — в названии.
            setTool(Tool.KEYS, focusLine = false)
            titleEdit.requestFocus()
            titleEdit.post { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(titleEdit, InputMethodManager.SHOW_IMPLICIT) }
        } else setTool(Tool.WRITE)
        if (note.layoutV < 2) sheet.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (sheet.width == 0) return
                sheet.viewTreeObserver.removeOnGlobalLayoutListener(this)
                migrateLayout()
            }
        })
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
        val finishNow = Runnable {
            // Пустую заметку (ничего не написали) не оставляем — как на айфоне.
            if (isEmptyNote()) { deleted = true; handler.removeCallbacks(saveRunnable); store.delete(note) } else saveNow(wait = true)
            finish()
        }
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
            setBackgroundColor(Ui.BG)
            // Курсор не уходит сам в поле названия (ни при открытии, ни когда строку-поле пересобирают) — только по касанию.
            isFocusableInTouchMode = true
            isFocusable = true
            descendantFocusability = android.view.ViewGroup.FOCUS_BEFORE_DESCENDANTS
        }

        // Сверху (0.30, как в макете): «‹ Заметки», справа — Отменить, Вернуть, Поделиться, «⋯». Название — первой строкой листа.
        val top = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Ui.BAR)
            setPadding(dp(4f), dp(2f), dp(6f), dp(2f))
        }
        titleView = Ui.text(this, "Заметки", 17f, Ui.ACCENT, bold = true).apply {
            tag = "note_back"
            setPadding(dp(2f), dp(10f), dp(10f), dp(10f))
            setOnClickListener { onBackPressed() }
        }
        top.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_back); imageTintList = ColorStateList.valueOf(Ui.ACCENT)
            setPadding(dp(8f), dp(8f), dp(2f), dp(8f))
            contentDescription = "Назад"
            setOnClickListener { onBackPressed() }
        }, LinearLayout.LayoutParams(dp(36f), dp(44f)))
        top.addView(titleView)
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        fun topIcon(icon: Int, desc: String, tag: String, f: (View) -> Unit) = Ui.ToolButton(this, icon, "", compact = true).apply {
            this.tag = tag; contentDescription = desc; setOnClickListener { f(it) }
        }.also { top.addView(it, LinearLayout.LayoutParams(dp(46f), Ui.WRAP)) }
        topIcon(R.drawable.ic_undo, "Отменить", "note_undo") { undo() }
        topIcon(R.drawable.ic_redo, "Вернуть", "note_redo") { redo() }
        topIcon(R.drawable.ic_share, "Поделиться", "note_share") { shareNote() }
        topIcon(R.drawable.ic_more, "Ещё", "note_more") { noteMenu(it) }
        rootView.addView(top, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        rootView.addView(Ui.barLine(this), LinearLayout.LayoutParams(Ui.MATCH, dp(1f)))

        // Снизу — две панели (как в макете): при клавиатуре — «Aa», чек-лист, картинка, перо, убрать клавиатуру; при пере —
        // «Aa», В текст, Ручка, Маркер, Фигуры, Ластик, Указка, Выделить, цвета, картинка, «Клавиатура».
        penRow = LinearLayout(this)
        penRow.addView(aaButton("fmt_open_pen"))
        penRow.addView(Ui.separator(this))
        addTool(penRow, Tool.WRITE, R.drawable.ic_write, "В текст")
        addTool(penRow, Tool.PEN, R.drawable.ic_pen, "Ручка")
        addTool(penRow, Tool.MARKER, R.drawable.ic_marker, "Маркер")
        addTool(penRow, Tool.SHAPE, R.drawable.ic_shapes, "Фигуры")
        addTool(penRow, Tool.ERASER, R.drawable.ic_eraser, "Ластик")
        addTool(penRow, Tool.HAND, R.drawable.ic_hand, "Указка")
        addTool(penRow, Tool.SELECT, R.drawable.ic_lasso, "Выделить")
        // Три последних цвета и «Цвет» — палитра из 24 цветов и пипетка.
        for (i in 0 until 3) {
            val v = View(this).apply {
                contentDescription = "Цвет"
                tag = "quick_color_$i"
                setOnClickListener { setColor(ColorKit.recent(this@NoteActivity)[i], remember = false) }
            }
            penRow.addView(v, LinearLayout.LayoutParams(dp(24f), dp(24f)).apply { setMargins(dp(5f), 0, dp(5f), 0) })
            quickColors += v
        }
        penRow.addView(Ui.ToolButton(this, R.drawable.ic_palette, "Цвет").apply {
            tag = "tool_palette"
            setOnClickListener { ColorKit.showPalette(this@NoteActivity, penColor, { setColor(it) }, { startPipette() }) }
        })
        penRow.addView(Ui.separator(this))
        penRow.addView(Ui.ToolButton(this, R.drawable.ic_image, "Картинка").apply { tag = "note_image"; setOnClickListener { pickImage() } })
        addTool(penRow, Tool.KEYS, R.drawable.ic_keyboard, "Клавиатура")
        penBar = Ui.toolbar(this, penRow).apply { tag = "pen_bar" }

        keysRow = LinearLayout(this)
        keysRow.addView(aaButton("fmt_open"))
        keysTask = Ui.ToolButton(this, R.drawable.ic_task, "Чек-лист").apply {
            tag = "keys_task"
            setOnClickListener { keysToggleTask() }
        }
        keysRow.addView(keysTask)
        keysRow.addView(Ui.ToolButton(this, R.drawable.ic_image, "Картинка").apply { tag = "keys_image"; setOnClickListener { pickImage() } })
        keysRow.addView(Ui.ToolButton(this, R.drawable.ic_pen, "Перо").apply { tag = "keys_pen"; setOnClickListener { setTool(Tool.WRITE) } })
        keysRow.addView(Ui.ToolButton(this, R.drawable.ic_keyboard, "Убрать").apply {
            tag = "keys_hide"; contentDescription = "Убрать клавиатуру"
            setOnClickListener { hideKeyboard() }
        })
        keysBarBottom = Ui.toolbar(this, keysRow).apply { tag = "keys_bar"; visibility = View.GONE }

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
        buildKeysEdit()
        buildTitleEdit()
        buildFormatPanel(area)
        rootView.addView(Ui.barLine(this), LinearLayout.LayoutParams(Ui.MATCH, dp(1f)))
        rootView.addView(keysBarBottom, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        rootView.addView(penBar, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        refreshColors()
    }

    /** Кнопка «Aa» — окно «Формат». */
    private fun aaButton(tag: String): View = Ui.text(this, "Aa", 19f, Ui.INK, bold = true).apply {
        this.tag = tag
        contentDescription = "Формат"
        gravity = Gravity.CENTER
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        background = Ui.rounded(this@NoteActivity, Ui.DESK, null, 10f)
        setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
        layoutParams = LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { setMargins(dp(4f), dp(4f), dp(6f), dp(4f)) }
        setOnClickListener { toggleFormat() }
    }

    // ---------------------------------------------------------------- «Клавиатура»

    /**
     * «Клавиатура» (0.27.9, Владимир: «нажимаю клавиатуру — пишу в нижнем окошке, а думал, печататься будет сверху, где
     * положено»): строка с курсором на листе сама становится полем ввода ([keysEdit] — одно поле, оно переезжает в строку с
     * курсором при каждой сборке листа). Внизу (0.30) — панель клавиатуры: «Aa», «Чек-лист», «Картинка», «Перо», «Убрать».
     */
    private fun buildKeysEdit() {
        keysEdit = KeysEdit(this).apply {
            textStyle(this)
            tag = "keys_edit"
            background = null
            setPadding(0, 0, 0, 0)
            hint = "Печатайте здесь…"
            gravity = Gravity.TOP or Gravity.START
            setHintTextColor(Ui.INK2 and 0x00FFFFFF or 0x80000000.toInt())
            // Многострочное поле: длинная строка переносится, Enter — «\n», его ловим и делаем новую строку записи.
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
            isSingleLine = false
            setHorizontallyScrolling(false)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(e: Editable?) { if (!keysLoading) onKeysText(e?.toString().orEmpty()) }
            })
            onSelection = { _, _ ->
                if (!keysLoading && !keysTyping && tool == Tool.KEYS) syncKeysCaret()
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
    }

    /** Поле ввода — в строку листа: отцепить от прежнего места (лист собирается заново), стиль — как у строки. */
    private fun takeKeysEdit(b: Block?): KeysEdit {
        (keysEdit.parent as? android.view.ViewGroup)?.removeView(keysEdit)
        val style = (b as? Block.Para)?.style ?: ParaStyle.BODY
        textStyle(keysEdit, style)
        keysEdit.tag = "keys_edit"
        if (b is Block.Task && b.done) keysEdit.setTextColor(Ui.INK2)
        keysStyle = style
        return keysEdit
    }

    /** Стиль строки, в которой сейчас поле ввода (оформление поля — как у строки). */
    private var keysStyle = ParaStyle.BODY

    private fun showKeysBar(on: Boolean, focus: Boolean = true) {
        keysBarBottom.visibility = if (on) View.VISIBLE else View.GONE
        penBar.visibility = if (on) View.GONE else View.VISIBLE
        if (!on) hideKeyboard()
        renderBlocks() // строка с курсором — полем ввода или снова обычной строкой
        if (on && focus) showKeyboard()
    }

    /** Убрать клавиатуру (кнопка «Убрать» и переход к перу). */
    private fun hideKeyboard() {
        keysHidden = true
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(rootView.windowToken, 0)
        keysEdit.clearFocus()
        if (::titleEdit.isInitialized) titleEdit.clearFocus()
    }

    private fun showKeyboard() {
        keysHidden = false
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
            val sig = lineSig(b)
            if (keysEdit.text.toString() != value || sig != keysSig) {
                val selA = keysEdit.selectionStart; val selB = keysEdit.selectionEnd
                keysEdit.setText(if (b is TextLine) styled(b, keysStyle) else styledPlain("", keysStyle))
                keysSig = sig
                // Только оформление (то же содержимое) — выделение остаётся, «Aa» можно нажимать дальше.
                if (keysEdit.text.toString() == value && selA in 0..value.length && selB in 0..value.length && selA != selB) {
                    keysEdit.setSelection(selA, selB); return
                }
            }
            val at = sel.coerceIn(0, keysEdit.length())
            if (keysEdit.selectionStart != at || keysEdit.selectionEnd != at) keysEdit.setSelection(at)
        } finally {
            keysLoading = false
            keysTask.selectedTool = b is Block.Task
            revealCaret()
        }
    }

    /** Что показано в поле ввода (текст и оформление): поменялось — перезаполнить. */
    private var keysSig = 0

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
            keysSig = lineSig(b) // поле само сдвигает своё оформление так же — перезаполнять не нужно
            syncKeysCaret()
            if (newLine) renderBlocks() else sheet.invalidate() // линейки — по строке (могла стать в две)
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
        val style = (b as? Block.Para)?.style ?: ParaStyle.BODY
        val listy = ParaStyle.isList(style) || style == ParaStyle.QUOTE
        if ((task || listy) && parts.size == 2 && parts[0].isBlank() && parts[1].isBlank()) {
            // Enter в пустом пункте — выход из списка (как на айфоне): обычная строка.
            note.blocks[note.caret] = Block.Para("", ParaStyle.BODY, (b as TextLine).indent)
            note.caretOffset = -1
            changed()
            return
        }
        // Оформление — по новому тексту поля (с переносами), потом режем по строкам.
        val line = b as TextLine
        val spans = Rich.copy(line.spans)
        Rich.adjust(spans, line.text, s)
        var from = 0
        val pieces = parts.map { part -> Rich.slice(spans, from, from + part.length).also { from += part.length + 1 } }
        line.text = parts[0]
        line.spans.clear(); line.spans += pieces[0]
        var idx = note.caret
        for (k in 1 until parts.size) {
            idx += 1
            note.blocks.add(idx, if (task) Block.Task(parts[k], false, line.indent, pieces[k])
                else Block.Para(parts[k], ParaStyle.next(style), line.indent, pieces[k]))
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
            is Block.Task -> Block.Para(b.text, ParaStyle.BODY, b.indent, b.spans)
            is Block.Para -> Block.Task(b.text, false, b.indent, b.spans)
            else -> b
        }
        changed()
        if (tool == Tool.KEYS) showKeyboard()
    }

    /** Строку с курсором — на виду (клавиатура занимает пол-экрана). */
    private fun revealCaret() {
        scroll.post {
            val v: View = (if (tool == Tool.KEYS && keysEdit.parent != null) keysEdit else caretTextView) ?: return@post
            v.requestRectangleOnScreen(Rect(0, 0, v.width, v.height), false)
        }
    }

    private fun addTool(row: LinearLayout, t: Tool, icon: Int, label: String) {
        val b = Ui.ToolButton(this, icon, label).apply { setOnClickListener { setTool(t) } }
        b.tag = "tool_" + t.name.lowercase()
        toolButtons[t] = b
        row.addView(b)
    }

    private fun setTool(t: Tool, focusLine: Boolean = true) {
        if (t != Tool.WRITE) current?.let { commit(it) }
        if (t != Tool.SELECT && t != Tool.PIPETTE) clearSelection()
        val wasKeys = tool == Tool.KEYS
        tool = t
        if (t == Tool.KEYS && !wasKeys) {
            keysHidden = !focusLine
            showKeysBar(true, focusLine)
            if (focusLine) hint("Печатайте — текст встаёт в строку с курсором. Перо коснулось листа — снова письмо от руки", 3500)
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

    /**
     * Вид строки текста по стилю абзаца (0.30). Высота каждой строки — ровно клетка разлиновки (у «Названия» — две):
     * её задаёт [CellHeight] в самом тексте ([styled]), низ букв — на 4 dp выше линии.
     */
    private fun textStyle(tv: TextView, style: String = ParaStyle.BODY) {
        tv.textSize = ParaStyle.textSp(style)
        tv.setTextColor(if (style == ParaStyle.QUOTE) Ui.INK2 else Ui.INK)
        tv.typeface = when (style) {
            ParaStyle.TITLE -> Ui.brandFont(this)
            ParaStyle.H1, ParaStyle.H2 -> Typeface.DEFAULT_BOLD
            ParaStyle.MONO -> Typeface.MONOSPACE
            else -> Typeface.DEFAULT
        }
        tv.includeFontPadding = false
        tv.setLineSpacing(0f, 1f)
        tv.tag = RuledSheet.RULED
        tv.minHeight = lineHeightPx * ParaStyle.cells(style)
    }

    /** Текст строки с оформлением и высотой клеток. */
    private fun styled(l: TextLine, style: String): SpannableStringBuilder {
        val sb = SpannableStringBuilder(l.text)
        Rich.apply(sb, l.spans)
        cellSpan(sb, style)
        return sb
    }

    private fun styledPlain(text: CharSequence, style: String): SpannableStringBuilder =
        SpannableStringBuilder(text).also { cellSpan(it, style) }

    private fun cellSpan(sb: SpannableStringBuilder, style: String) {
        sb.setSpan(CellHeight(lineHeightPx * ParaStyle.cells(style), dp(4f)), 0, sb.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
    }

    /** Подпись строки: текст и оформление (поменялись — строку на листе переписать). */
    private fun lineSig(b: Block?): Int = when (b) {
        is TextLine -> b.text.hashCode() * 31 + b.spans.hashCode()
        else -> 0
    }

    // Что сейчас на листе (0.27.6): строки те же и на тех же местах — меняется только текст, лист не собирается заново.
    // Раньше после каждого распознанного слова пересоздавались все строки записи — чем длиннее запись, тем дольше.
    private var shownKey: List<Any?> = emptyList()
    private val lineViews = ArrayList<TextView?>()
    private val lineSigs = ArrayList<Int>()
    private var taskTextView: TextView? = null

    /** Строки (сами строки — по ссылке), галочки задач, размер и место картинок, выбранная картинка. */
    private fun structureKey(): List<Any?> {
        val k = ArrayList<Any?>(note.blocks.size * 2 + 2)
        k += selectedImage
        k += if (tool == Tool.KEYS) note.caret else -1 // строка-поле ввода стоит на месте курсора
        k += note.paper
        for (b in note.blocks) {
            k += b
            when (b) {
                is Block.Task -> { k += b.done; k += b.indent }
                is Block.Para -> { k += b.style; k += b.indent }
                is Block.Image -> { k += b.wf; k += b.xa }
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
        val keys = tool == Tool.KEYS
        for ((i, b) in note.blocks.withIndex()) {
            val tv = lineViews[i] ?: continue
            val t = blockText(b) ?: continue
            if (keys && i == note.caret) continue // поле ввода: текст в нём пишет сам ввод (и loadKeysLine)
            if (i == note.caret) { caretTextView = tv; changedText = true; continue } // строку курсора пишет refreshCaretText
            val sig = lineSig(b)
            if (tv.text.toString() != t || lineSigs.getOrNull(i) != sig) {
                tv.text = styled(b as TextLine, (b as? Block.Para)?.style ?: ParaStyle.BODY)
                if (i < lineSigs.size) lineSigs[i] = sig
                changedText = true
            }
        }
        if (::titleEdit.isInitialized && !titleTyping && titleEdit.text.toString() != note.title) loadTitle()
        if (changedText) sheet.invalidate()
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
        val titleFocus = ::titleEdit.isInitialized && titleEdit.hasFocus()
        sheet.removeAllViews()
        blockViews.clear()
        lineViews.clear()
        lineSigs.clear()
        caretTextView = null
        sheet.paper = note.paper
        sheet.tall.clear()
        // Дата — строкой сверху, под ней название (первая строка листа, две клетки), дальше — строки записи.
        sheet.addView(TextView(this).apply {
            textStyle(this)
            textSize = 13f
            setTextColor(Ui.MUTED)
            gravity = Gravity.CENTER_HORIZONTAL
            tag = "note_date"
            text = styledPlain(Ui.noteMeta(note.created), ParaStyle.BODY)
        })
        (titleEdit.parent as? android.view.ViewGroup)?.removeView(titleEdit)
        if (!titleTyping && titleEdit.text.toString() != note.title) loadTitle()
        sheet.addView(titleEdit, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        sheet.tall += titleEdit
        if (titleFocus) titleEdit.requestFocus()
        val keys = tool == Tool.KEYS && ::keysEdit.isInitialized
        var num = 0
        var prev: Block? = null
        note.blocks.forEachIndexed { i, b ->
            val (v, tv) = when (b) {
                is Block.Para -> {
                    val p = prev
                    num = if (b.style == ParaStyle.NUM && p is Block.Para && p.style == ParaStyle.NUM && p.indent == b.indent) num + 1 else 1
                    paraView(b, i, num, keys)
                }
                is Block.Task -> taskView(b, i) to taskTextView
                is Block.Image -> imageView(b, i) to null
            }
            prev = b
            if (b is Block.Para && b.style == ParaStyle.TITLE) sheet.tall += v
            blockViews += v
            lineViews += tv
            lineSigs += lineSig(b)
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
        if (keys && keysEdit.parent == null) {
            // Курсор на картинке (или записи пусто): поле — пустой строкой после неё; напишут — станет новой строкой.
            val at = if (note.blocks.isEmpty()) sheet.childCount else sheet.indexOfChild(blockViews[note.caret.coerceIn(0, blockViews.size - 1)]) + 1
            sheet.addView(takeKeysEdit(null), at, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        }
        sheet.addView(spacer, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        shownKey = structureKey()
        refreshCaretText()
        ink.invalidate()
        if (keys) {
            if (!keysTyping) loadKeysLine()
            // Поле переехало в другую строку — фокус и клавиатура с ним (пока печатают название — не отбираем).
            if (keysEdit.parent != null && !keysEdit.hasFocus() && !titleFocus && !keysHidden) showKeyboard()
        }
    }

    /** Строка-абзац: текст в стиле абзаца; у списка — значок или номер слева, у цитаты — черта, отступ — пустым местом. */
    private fun paraView(b: Block.Para, i: Int, num: Int, keys: Boolean): Pair<View, TextView> {
        val tv: TextView = if (keys && i == note.caret) takeKeysEdit(b) else TextView(this).apply {
            textStyle(this, b.style)
            text = styled(b, b.style)
            caretOnTap(this, i)
            if (i == note.caret) caretTextView = this
        }
        val marker = when (b.style) {
            ParaStyle.BULLET -> "•"
            ParaStyle.DASH -> "–"
            ParaStyle.NUM -> "$num."
            else -> null
        }
        if (b.indent == 0 && marker == null && b.style != ParaStyle.QUOTE) return tv to tv
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (b.indent > 0) row.addView(View(this), LinearLayout.LayoutParams(dp(INDENT_DP) * b.indent, 1))
        if (marker != null) row.addView(TextView(this).apply {
            textStyle(this)
            setTextColor(Ui.INK2)
            text = styledPlain(marker, ParaStyle.BODY)
            gravity = Gravity.END
            setPadding(0, 0, dp(8f), 0)
            tag = "list_marker"
        }, LinearLayout.LayoutParams(dp(36f), Ui.WRAP))
        if (b.style == ParaStyle.QUOTE) row.addView(View(this).apply { setBackgroundColor(Ui.CHIP_LINE) },
            LinearLayout.LayoutParams(dp(3f), Ui.MATCH).apply { marginEnd = dp(12f) })
        row.addView(tv, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        return row to tv
    }

    private fun taskView(b: Block.Task, i: Int): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (b.indent > 0) row.addView(View(this), LinearLayout.LayoutParams(dp(INDENT_DP) * b.indent, 1))
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
        val text: TextView = if (tool == Tool.KEYS && ::keysEdit.isInitialized && i == note.caret) takeKeysEdit(b) else TextView(this).apply {
            textStyle(this)
            text = styled(b, ParaStyle.BODY)
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
        val ins = shownInsertLen
        if (b is TextLine) Rich.apply(sb, b.spans) { x -> if (x < pos) x else x + ins }
        cellSpan(sb, (b as? Block.Para)?.style ?: ParaStyle.BODY)
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

    /** Новый текст строки; оформление сдвигается вместе с текстом ([Rich.adjust]). */
    private fun setBlockText(b: Block, t: String) {
        if (b !is TextLine || b.text == t) return
        Rich.adjust(b.spans, b.text, t)
        b.text = t
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
        // Курсор в начале строки списка, цитаты или с отступом: сначала убрать отступ, потом список (как на айфоне).
        if (b is TextLine && (b.indent > 0 || (b is Block.Para && (ParaStyle.isList(b.style) || b.style == ParaStyle.QUOTE)))) {
            if (!coalesce) pushUndo()
            if (b.indent > 0) b.indent -= 1 else if (b is Block.Para) b.style = ParaStyle.BODY
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
        (prev as TextLine).text = prevText + text
        Rich.appendShifted(prev.spans, (b as TextLine).spans, prevText.length, prev.text.length)
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
            val line = b as TextLine
            val pos = caretPos(text)
            val left = text.substring(0, pos).trimEnd()
            val restAt = pos + (text.length - pos - text.substring(pos).trimStart().length)
            val rest = text.substring(restAt)
            val restSpans = Rich.slice(line.spans, restAt, text.length)
            val leftSpans = Rich.slice(line.spans, 0, left.length)
            line.text = left
            line.spans.clear(); line.spans += leftSpans
            val style = ParaStyle.next((b as? Block.Para)?.style ?: ParaStyle.BODY)
            note.blocks.add(note.caret + 1, if (task) Block.Task(rest, false, line.indent, restSpans) else Block.Para(rest, style, line.indent, restSpans))
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

    // ---------------------------------------------------------------- название — первая строка листа (0.30)

    private lateinit var titleEdit: EditText
    private var titleLoading = false
    private var titleTyping = false
    private var titleEditAt = 0L
    /** Клавиатуру убрали кнопкой — поле ввода не отбирает фокус само при пересборке листа. */
    private var keysHidden = false

    /** Поле названия: стиль «Название» (две клетки), Enter — к первой строке записи. */
    private fun buildTitleEdit() {
        titleEdit = EditText(this).apply {
            textStyle(this, ParaStyle.TITLE)
            tag = "note_title"
            background = null
            setPadding(0, 0, 0, 0)
            hint = "Название"
            setHintTextColor(Ui.MUTED)
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
            isSingleLine = false
            setHorizontallyScrolling(false)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(e: Editable?) { if (!titleLoading) onTitleText(e?.toString().orEmpty()) }
            })
            // Коснулись названия — печатают его: внизу панель клавиатуры (поле строки остаётся без фокуса). Именно касание:
            // курсор система ставит в первое поле и сама, при открытии экрана.
            setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_UP) {
                    keysHidden = false
                    if (tool != Tool.KEYS) post { setTool(Tool.KEYS, focusLine = false); titleEdit.requestFocus() }
                }
                false
            }
        }
        loadTitle()
    }

    private fun loadTitle() {
        if (!::titleEdit.isInitialized) return
        titleLoading = true
        try {
            titleEdit.setText(styledPlain(note.title, ParaStyle.TITLE))
        } finally { titleLoading = false }
    }

    /** Печатают название. Enter — название кончилось: остальное — первой строкой записи, курсор туда. */
    private fun onTitleText(s: String) {
        val now = android.os.SystemClock.uptimeMillis()
        if ('\n' in s) {
            pushUndo()
            val before = s.substringBefore('\n')
            val after = s.substringAfter('\n').replace("\n", " ")
            note.title = before.trim()
            val first = note.blocks.firstOrNull()
            if (after.isNotEmpty() || first !is Block.Para || first.text.isNotEmpty()) note.blocks.add(0, Block.Para(after))
            note.caret = 0
            note.caretOffset = if (after.isEmpty()) -1 else 0
            loadTitle()
            titleEdit.clearFocus()
            changed()
            showKeyboard()
            return
        }
        titleTyping = true
        try {
            if (now - titleEditAt > 2000) pushUndo()
            titleEditAt = now
            note.title = s
            changed(render = false)
        } finally { titleTyping = false }
    }

    // ---------------------------------------------------------------- «Aa» — формат (0.30)

    private lateinit var fmtPanel: LinearLayout
    private val fmtButtons = HashMap<String, TextView>()

    private fun toggleFormat() {
        if (fmtPanel.visibility == View.VISIBLE) { fmtPanel.visibility = View.GONE; return }
        runAfterCommit {
            refreshFormat()
            fmtPanel.visibility = View.VISIBLE
        }
    }

    /**
     * Окно «Формат» (как в макете и Заметках на айфоне): стиль строки, Ж/К/Ч/З, маркер, цвет текста, списки, цитата, отступ,
     * шрифт. Лежит над нижней панелью и не забирает клавиатуру: выделили слово — нажали «Ж». Без выделения — слово у курсора.
     */
    private fun buildFormatPanel(area: FrameLayout) {
        fmtPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(this@NoteActivity, Ui.BAR, Ui.BAR_LINE, 18f)
            elevation = dp(10f).toFloat()
            setPadding(dp(14f), dp(8f), dp(14f), dp(12f))
            tag = "fmt_panel"
            visibility = View.GONE
            isClickable = true
        }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(Ui.text(this, "Формат", 17f, Ui.INK, bold = true), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        head.addView(Ui.text(this, "✕", 18f, Ui.INK2).apply {
            tag = "fmt_close"; setPadding(dp(12f), dp(6f), dp(4f), dp(6f)); setOnClickListener { fmtPanel.visibility = View.GONE }
        })
        fmtPanel.addView(head)
        fun row(): Ui.FlowRow = Ui.FlowRow(this).apply { hGap = dp(6f); vGap = dp(6f); setPadding(0, dp(4f), 0, dp(4f)) }.also { fmtPanel.addView(it) }
        fun label(t: String) = fmtPanel.addView(Ui.text(this, t, 12f, Ui.MUTED, bold = true).apply { setPadding(0, dp(8f), 0, dp(2f)) })
        fun chip(tag: String, text: String, f: () -> Unit): TextView = Ui.pill(this, text, Ui.INK, Ui.DESK).apply {
            this.tag = tag
            setOnClickListener { f() }
            fmtButtons[tag] = this
        }
        val styles = row()
        styles.addView(chip("fmt_title", "Название") { applyStyle(ParaStyle.TITLE) }.apply { typeface = Ui.brandFont(this@NoteActivity) })
        styles.addView(chip("fmt_h1", "Заголовок") { applyStyle(ParaStyle.H1) })
        styles.addView(chip("fmt_h2", "Подзаголовок") { applyStyle(ParaStyle.H2) })
        styles.addView(chip("fmt_body", "Основной") { applyStyle(ParaStyle.BODY) })
        val marks = row()
        marks.addView(chip("fmt_b", "Ж") { applyInline(Rich.B) }.apply { typeface = Typeface.DEFAULT_BOLD })
        marks.addView(chip("fmt_i", "К") { applyInline(Rich.I) }.apply { setTypeface(Typeface.SERIF, Typeface.ITALIC) })
        marks.addView(chip("fmt_u", "Ч") { applyInline(Rich.U) }.apply { paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG })
        marks.addView(chip("fmt_s", "З") { applyInline(Rich.S) }.apply { paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG })
        label("Выделение маркером")
        val hl = row()
        for ((i, c) in HIGHLIGHTS.withIndex()) hl.addView(dot("fmt_hl_$i", c) { applyColor(Rich.HL, c) })
        hl.addView(dot("fmt_hl_none", 0) { applyColor(Rich.HL, 0) })
        label("Цвет текста")
        val fc = row()
        fc.addView(dot("fmt_fc_none", 0) { applyColor(Rich.FC, 0) })
        for ((i, c) in TEXT_COLORS.withIndex()) fc.addView(dot("fmt_fc_$i", c) { applyColor(Rich.FC, c) })
        label("Списки и абзац")
        val lists = row()
        lists.addView(chip("fmt_bullet", "•  Список") { applyList(ParaStyle.BULLET) })
        lists.addView(chip("fmt_dash", "–  Список") { applyList(ParaStyle.DASH) })
        lists.addView(chip("fmt_num", "1.  Список") { applyList(ParaStyle.NUM) })
        lists.addView(chip("fmt_check", "☐  Чек-лист") { runAfterCommit { keysToggleTask() } })
        lists.addView(chip("fmt_quote", "❝ Цитата") { applyList(ParaStyle.QUOTE) })
        lists.addView(chip("fmt_outdent", "⇤") { applyIndent(-1) })
        lists.addView(chip("fmt_indent", "⇥") { applyIndent(1) })
        label("Шрифт")
        val fonts = row()
        fonts.addView(chip("fmt_font_normal", "Обычный") { applyFont(null) })
        fonts.addView(chip("fmt_font_serif", "С засечками") { applyFont(Rich.SERIF) }.apply { typeface = Typeface.SERIF })
        fonts.addView(chip("fmt_font_mono", "Моноширинный") { applyFont(ParaStyle.MONO) }.apply { typeface = Typeface.MONOSPACE })
        val scroll = ScrollView(this).apply { addView(fmtPanel) }
        area.addView(scroll, FrameLayout.LayoutParams(minOf(dp(560f), resources.displayMetrics.widthPixels - dp(16f)), Ui.WRAP).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(8f)
        })
    }

    /** Кружок цвета в «Формате» (0 — «без цвета»: крестик). */
    private fun dot(tag: String, color: Int, f: () -> Unit): TextView = TextView(this).apply {
        this.tag = tag
        gravity = Gravity.CENTER
        text = if (color == 0) "✕" else ""
        setTextColor(Ui.INK2)
        background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(if (color == 0) Ui.BAR else color)
            setStroke(dp(1f), Ui.CHIP_LINE)
        }
        layoutParams = android.view.ViewGroup.LayoutParams(dp(34f), dp(34f))
        minWidth = dp(34f); minHeight = dp(34f) // ряд с переносом меряет по содержимому — пустой кружок не схлопывается
        setOnClickListener { f() }
        fmtButtons[tag] = this
    }

    /** Строка и кусок, к которым применяется формат: выделение в поле ввода, иначе слово у курсора. */
    private fun fmtTarget(): Triple<TextLine, Int, Int>? {
        val b = note.blocks.getOrNull(note.caret) as? TextLine ?: return null
        if (tool == Tool.KEYS && keysEdit.parent != null) {
            val a = keysEdit.selectionStart; val e = keysEdit.selectionEnd
            if (a in 0..b.text.length && e in 0..b.text.length && a != e) return Triple(b, minOf(a, e), maxOf(a, e))
        }
        val w = Rich.wordAt(b.text, caretPos(b.text))
        return Triple(b, w.first, w.last + 1)
    }

    private fun afterFormat() {
        keysSig = 0 // поле ввода перезаполнить оформлением
        changed()
        refreshFormat()
        if (tool == Tool.KEYS && !keysHidden && !titleEdit.hasFocus()) showKeyboard()
    }

    private fun applyInline(kind: String) = runAfterCommit {
        val (l, a, e) = fmtTarget() ?: return@runAfterCommit
        if (a >= e) { hint("Выделите слово или поставьте курсор в слово"); return@runAfterCommit }
        pushUndo()
        Rich.toggle(l.spans, kind, a, e, l.text.length)
        afterFormat()
    }

    private fun applyColor(kind: String, color: Int) = runAfterCommit {
        val (l, a, e) = fmtTarget() ?: return@runAfterCommit
        if (a >= e) { hint("Выделите слово или поставьте курсор в слово"); return@runAfterCommit }
        pushUndo()
        if (color == 0) Rich.clear(l.spans, kind, a, e) else Rich.set(l.spans, kind, a, e, l.text.length, color)
        Rich.normalize(l.spans, l.text.length)
        afterFormat()
    }

    /** Стиль строки с курсором (пункт чек-листа станет абзацем этого стиля). */
    private fun applyStyle(style: String) = runAfterCommit {
        val b = note.blocks.getOrNull(note.caret) ?: return@runAfterCommit
        pushUndo()
        when (b) {
            is Block.Para -> b.style = style
            is Block.Task -> note.blocks[note.caret] = Block.Para(b.text, style, b.indent, b.spans)
            else -> return@runAfterCommit
        }
        afterFormat()
    }

    /** Список / цитата: нажали ещё раз — снова обычная строка. */
    private fun applyList(style: String) = runAfterCommit {
        val b = note.blocks.getOrNull(note.caret) ?: return@runAfterCommit
        pushUndo()
        when (b) {
            is Block.Para -> b.style = if (b.style == style) ParaStyle.BODY else style
            is Block.Task -> note.blocks[note.caret] = Block.Para(b.text, style, b.indent, b.spans)
            else -> return@runAfterCommit
        }
        afterFormat()
    }

    private fun applyIndent(d: Int) = runAfterCommit {
        val b = note.blocks.getOrNull(note.caret) as? TextLine ?: return@runAfterCommit
        val v = (b.indent + d).coerceIn(0, ParaStyle.MAX_INDENT)
        if (v == b.indent) return@runAfterCommit
        pushUndo()
        b.indent = v
        afterFormat()
    }

    /** Шрифт: обычный (снять засечки и моноширинный), с засечками — у куска, моноширинный — у строки. */
    private fun applyFont(kind: String?) = runAfterCommit {
        val b = note.blocks.getOrNull(note.caret) ?: return@runAfterCommit
        when (kind) {
            ParaStyle.MONO -> {
                pushUndo()
                when (b) {
                    is Block.Para -> b.style = if (b.style == ParaStyle.MONO) ParaStyle.BODY else ParaStyle.MONO
                    is Block.Task -> note.blocks[note.caret] = Block.Para(b.text, ParaStyle.MONO, b.indent, b.spans)
                    else -> return@runAfterCommit
                }
            }
            Rich.SERIF -> {
                val (l, a, e) = fmtTarget() ?: return@runAfterCommit
                if (a >= e) return@runAfterCommit
                pushUndo()
                Rich.toggle(l.spans, Rich.SERIF, a, e, l.text.length)
            }
            else -> {
                val l = b as? TextLine ?: return@runAfterCommit
                pushUndo()
                val (_, a, e) = fmtTarget() ?: return@runAfterCommit
                Rich.clear(l.spans, Rich.SERIF, a, e)
                if (b is Block.Para && b.style == ParaStyle.MONO) b.style = ParaStyle.BODY
            }
        }
        afterFormat()
    }

    /** Подсветить в «Формате» то, что уже стоит у строки и куска. */
    private fun refreshFormat() {
        if (!::fmtPanel.isInitialized) return
        val b = note.blocks.getOrNull(note.caret)
        val style = (b as? Block.Para)?.style
        val t = fmtTarget()
        fun on(tag: String, v: Boolean) = fmtButtons[tag]?.let {
            if (it.text.isEmpty() || it.text == "✕") { it.alpha = if (v) 1f else 0.85f; return@let }
            it.background = Ui.rounded(this, if (v) Ui.INK else Ui.DESK, null, 999f)
            it.setTextColor(if (v) Color.WHITE else Ui.INK)
        }
        on("fmt_title", style == ParaStyle.TITLE); on("fmt_h1", style == ParaStyle.H1); on("fmt_h2", style == ParaStyle.H2)
        on("fmt_body", style == ParaStyle.BODY)
        on("fmt_bullet", style == ParaStyle.BULLET); on("fmt_dash", style == ParaStyle.DASH); on("fmt_num", style == ParaStyle.NUM)
        on("fmt_quote", style == ParaStyle.QUOTE); on("fmt_check", b is Block.Task)
        on("fmt_font_mono", style == ParaStyle.MONO)
        val (l, a, e) = t ?: Triple(null, 0, 0)
        fun cov(k: String) = l != null && Rich.covered(l.spans, k, a, e)
        on("fmt_b", cov(Rich.B)); on("fmt_i", cov(Rich.I)); on("fmt_u", cov(Rich.U)); on("fmt_s", cov(Rich.S))
        on("fmt_font_serif", cov(Rich.SERIF)); on("fmt_font_normal", style != ParaStyle.MONO && !cov(Rich.SERIF))
    }

    // ---------------------------------------------------------------- «⋯», разлиновка, поделиться

    private fun noteMenu(anchor: View) {
        runAfterCommit {
            val m = android.widget.PopupMenu(this, anchor)
            m.menu.add(0, 1, 0, if (note.pinned) "Открепить" else "Закрепить")
            m.menu.add(0, 2, 1, "Разлиновка: " + NotePaper.label(note.paper))
            m.menu.add(0, 3, 2, "Итоги записи")
            m.menu.add(0, 4, 3, if (fingerWrites) "Палец пишет: да" else "Палец пишет: нет")
            m.menu.add(0, 5, 4, "Удалить заметку")
            m.setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> { note.pinned = !note.pinned; saveSoon(); hint(if (note.pinned) "Заметка закреплена" else "Заметка откреплена") }
                    2 -> paperDialog()
                    3 -> showSummary()
                    4 -> { fingerWrites = !fingerWrites; hint(if (fingerWrites) "Палец пишет, как перо" else "Палец — листать и ставить курсор") }
                    5 -> deleteDialog()
                }
                true
            }
            m.show()
            lastMenu = m
        }
    }

    internal var lastMenu: android.widget.PopupMenu? = null
    internal var paperDlg: AlertDialog? = null

    /** «Разлиновка»: без разметки, в линейку, в клетку — для этой заметки или для всех новых. */
    internal fun paperDialog() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20f), dp(8f), dp(20f), 0) }
        val row = Ui.FlowRow(this).apply { hGap = dp(8f); vGap = dp(8f) }
        val pills = HashMap<String, TextView>()
        fun paint() { for ((p, v) in pills) { val on = p == note.paper; v.background = Ui.rounded(this, if (on) Ui.INK else Ui.DESK, null, 999f); v.setTextColor(if (on) Color.WHITE else Ui.INK) } }
        val all = CheckBox(this).apply {
            text = "Для всех новых заметок"
            tag = "paper_all"
            isChecked = NotePaper.default(this@NoteActivity) == note.paper && note.paper != NotePaper.LINES
            buttonTintList = ColorStateList.valueOf(Ui.ACCENT)
            setOnCheckedChangeListener { _, on -> NotePaper.setDefault(this@NoteActivity, if (on) note.paper else NotePaper.LINES) }
        }
        for (p in NotePaper.ALL) row.addView(Ui.pill(this, NotePaper.label(p), Ui.INK, Ui.DESK).apply {
            tag = "paper_$p"
            setOnClickListener {
                if (note.paper != p) { note.paper = p; changed() }
                if (all.isChecked) NotePaper.setDefault(this@NoteActivity, p)
                paint()
            }
        }.also { pills[p] = it })
        paint()
        box.addView(row)
        box.addView(all)
        box.addView(Ui.text(this, "Шаг линейки и клетки — строка текста: каждая строка ложится на линию, название — на две.", 13f, Ui.MUTED).apply {
            setPadding(0, dp(6f), 0, 0)
        })
        paperDlg = AlertDialog.Builder(this).setTitle("Разлиновка").setView(box).setPositiveButton("Готово", null).show()
    }

    private fun deleteDialog() {
        AlertDialog.Builder(this)
            .setMessage("Удалить заметку «${note.title.ifBlank { "Новая заметка" }}»?")
            .setPositiveButton("Удалить") { _, _ ->
                handler.removeCallbacks(saveRunnable)
                deleted = true
                store.delete(note)
                finish()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private var deleted = false

    /** Текст заметки для «Поделиться» (0.30; Word и PDF — следующим шагом): строки с отметками списков и чек-листа. */
    internal fun plainText(): String = buildString {
        if (note.title.isNotBlank()) append(note.title.trim()).append("\n\n")
        var num = 0
        var prev: Block? = null
        for (b in note.blocks) {
            val pad = "    ".repeat((b as? TextLine)?.indent ?: 0)
            when (b) {
                is Block.Para -> {
                    val p = prev
                    num = if (b.style == ParaStyle.NUM && p is Block.Para && p.style == ParaStyle.NUM && p.indent == b.indent) num + 1 else 1
                    val mark = when (b.style) { ParaStyle.BULLET -> "• "; ParaStyle.DASH -> "– "; ParaStyle.NUM -> "$num. "; ParaStyle.QUOTE -> "> "; else -> "" }
                    append(pad).append(mark).append(b.text).append('\n')
                }
                is Block.Task -> append(pad).append(if (b.done) "☑ " else "☐ ").append(b.text).append('\n')
                is Block.Image -> append("[картинка]\n")
            }
            prev = b
        }
    }.trimEnd()

    private fun shareNote() {
        runAfterCommit {
            saveNow()
            val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, note.title.ifBlank { "Заметка" })
                .putExtra(Intent.EXTRA_TEXT, plainText())
            try { startActivity(Intent.createChooser(send, "Отправить заметку")) } catch (_: Throwable) { hint("Не нашлось приложения, чтобы отправить") }
        }
    }

    // ---------------------------------------------------------------- старые записи: рисунки — за своими строками (0.30)

    /**
     * Запись до 0.30 ([Note.layoutV] = 1): название и дата были другой высоты, строки — не по клеткам. Рисунки (схемы у строк)
     * сдвигаем вслед за их строкой: старое место строки считаем прежними видами, новое — по листу. Один раз, без «Отменить».
     */
    private fun migrateLayout() {
        if (note.layoutV >= 2) return
        if (note.drawings.isEmpty() || blockViews.size != note.blocks.size || sheet.width == 0) { note.layoutV = 2; saveSoon(); return }
        val w = sheet.width - sheet.paddingLeft - sheet.paddingRight
        fun measure(v: View): Int {
            v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            return v.measuredHeight
        }
        fun oldLine(text: String, width: Int): Int {
            val tv = TextView(this).apply { textSize = 19f; setExactLineHeight(lineHeightPx); minHeight = lineHeightPx; this.text = text }
            tv.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            return tv.measuredHeight
        }
        var y = sheet.paddingTop + measure(Ui.text(this, note.title, 26f, Ui.INK, bold = true).apply { setPadding(0, 0, 0, dp(2f)) }) +
            measure(Ui.mono(this, Ui.noteMeta(note.created), 12f).apply { setPadding(0, 0, 0, dp(10f)) })
        val oldTops = IntArray(note.blocks.size)
        for ((i, b) in note.blocks.withIndex()) {
            oldTops[i] = y
            y += when (b) {
                is Block.Para -> oldLine(b.text, w)
                is Block.Task -> maxOf(lineHeightPx, oldLine(b.text, w - dp(34f) - dp(80f)))
                is Block.Image -> blockViews[i].measuredHeight
            }
        }
        val newTops = IntArray(note.blocks.size) { blockViews[it].top }
        for (d in note.drawings) {
            if (d.pts.size < 2) continue
            val y0 = d.pts[1]
            var k = 0
            while (k + 1 < oldTops.size && oldTops[k + 1] <= y0) k++
            val dy = (newTops[k] - oldTops[k]).toFloat()
            if (dy == 0f) continue
            var j = 1
            while (j < d.pts.size) { d.pts[j] += dy; j += 3 }
            d.changed()
        }
        note.layoutV = 2
        saveSoon() // не правка: время заметки в списке не меняется
        ink.invalidate()
    }

    // ---------------------------------------------------------------- отмена и сохранение

    private fun copyBlock(b: Block): Block = when (b) {
        is Block.Para -> Block.Para(b.text, b.style, b.indent, Rich.copy(b.spans))
        is Block.Task -> Block.Task(b.text, b.done, b.indent, Rich.copy(b.spans))
        is Block.Image -> Block.Image(b.file, b.width, b.height, b.wf, b.xa)
    }

    private fun snap() = Snap(note.title, note.blocks.map { copyBlock(it) }, ArrayList(note.drawings), note.caret, note.caretOffset)

    private fun restore(x: Snap): Note =
        Note(note.id, x.title, note.created, note.updated, x.blocks.mapTo(ArrayList()) { copyBlock(it) }, ArrayList(x.drawings), x.caret)
            .apply { caretOffset = x.caretOffset; folder = note.folder; pinned = note.pinned; paper = note.paper; layoutV = note.layoutV }

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
        loadTitle()
        changed()
    }

    private fun redo() {
        if (redoStack.isEmpty() || commitQueue.isNotEmpty() || current != null) return
        undoStack.addLast(snap())
        note = restore(redoStack.removeLast())
        loadTitle()
        changed()
    }

    /** Сохранить скоро, не трогая время правки (закрепили, перенесли раскладку — это не правка текста). */
    private fun saveSoon() {
        handler.removeCallbacks(saveRunnable)
        handler.postDelayed(saveRunnable, 400)
    }

    private fun changed(render: Boolean = true) {
        note.updated = System.currentTimeMillis() // список заметок (0.28) — по времени правки
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
        if (deleted) return
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

    /** Перо коснулось листа, пока печатают (0.30, как в макете): клавиатура уходит, написанное станет текстом у курсора. */
    override fun penWhileTyping(): Boolean {
        if (tool != Tool.KEYS) return false
        setTool(Tool.WRITE)
        return true
    }
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
        else -> NoteInkView.StrokeStyle("write", Ui.PEN_INK, Ui.dpf(this, 2.6f))
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
        val widest = FloatArray(1)
        val keep = InkEraser.eraseIn(note.drawings, x, y, r, widest) ?: return
        if (!eraseUndoPushed) { pushUndo(); eraseUndoPushed = true } // одно движение ластиком — один шаг «Отменить»
        note.drawings.clear()
        note.drawings.addAll(keep)
        ink.eraseHole(x, y, r + widest[0] + 1f) // стёртое у маркера шире круга ластика
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
        testRecognizer?.let { h ->
            // Проверки: распознавание без ML Kit (в Robolectric его нет).
            val text = h(ArrayList(g.strokes))?.trim()?.takeIf { it.isNotEmpty() }
            g.candidates = if (text != null) listOf(Candidate(text, null)) else emptyList()
            g.error = null
            g.resultFor = n
            if (g === current) { interim = text.orEmpty(); refreshCaretText() }
            if (g.committing) flush()
            return
        }
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
            note.drawings += ShapeTool.drawingOf(shape, NoteInkView.StrokeStyle("pen", Ui.PEN_INK, Ui.dpf(this, 2.4f)))
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
        ink.fly(g.strokes, style, flyTarget(best))
        insertAtCaret(best)
    }

    /**
     * Куда улетают буквы (0.27.8): место слова в строке с курсором — серое слово (оно и есть распознанное) или, если его нет,
     * сразу за курсором; координаты листа. null — строки с курсором нет на экране.
     */
    private fun flyTarget(word: String): android.graphics.RectF? {
        val tv = caretTextView ?: return null
        val layout = tv.layout ?: return null
        val len = tv.text.length
        val start = shownInsertAt.coerceIn(0, len)
        val end = (shownInsertAt + shownInsertLen - 1).coerceIn(start, len) // без знака курсора
        val line = layout.getLineForOffset(start)
        val x0 = layout.getPrimaryHorizontal(start)
        var x1 = if (end > start && layout.getLineForOffset(end) == line) layout.getPrimaryHorizontal(end) else 0f
        if (x1 <= x0) x1 = x0 + tv.paint.measureText(" $word")
        val base = layout.getLineBaseline(line).toFloat()
        val fm = tv.paint.fontMetrics
        var ox = tv.paddingLeft.toFloat()
        var oy = tv.paddingTop.toFloat()
        var v: View = tv
        while (v !== sheet) {
            ox += v.left; oy += v.top
            v = v.parent as? View ?: return null
        }
        return android.graphics.RectF(ox + x0, oy + base + fm.ascent * 0.8f, ox + x1, oy + base + fm.descent * 0.6f)
    }

    /** Выполнить действие после того, как недописанные слова станут текстом (чтобы не сбить порядок). */
    private fun runAfterCommit(action: () -> Unit) {
        current?.let { commit(it) }
        if (commitQueue.isEmpty() && current == null) action() else afterCommit.addLast(action)
    }

    /** В заметке ничего нет: ни названия, ни текста, ни картинок, ни рисунков. */
    internal fun isEmptyNote(): Boolean = note.title.isBlank() && note.drawings.isEmpty() &&
        note.blocks.all { (it is TextLine && it.text.isBlank()) } && current == null

    companion object {
        const val EXTRA_ID = "note_id"
        /** Открыть сразу с клавиатурой (новая заметка). */
        const val EXTRA_KEYS = "note_keys"
        /** Отступ на один шаг (dp). */
        const val INDENT_DP = 28f
        /** Маркеры «Формата»: жёлтый, зелёный, голубой, розовый, оранжевый, фиолетовый (как в макете). */
        val HIGHLIGHTS = intArrayOf(
            Color.argb(140, 255, 214, 10), Color.argb(107, 52, 199, 89), Color.argb(115, 90, 200, 250),
            Color.argb(97, 255, 105, 180), Color.argb(107, 255, 149, 0), Color.argb(97, 175, 82, 222),
        )
        /** Цвета текста: красный, синий, зелёный, оранжевый, фиолетовый. */
        val TEXT_COLORS = intArrayOf(
            Color.rgb(224, 53, 43), Color.rgb(47, 124, 246), Color.rgb(46, 170, 74), Color.rgb(232, 131, 12), Color.rgb(155, 89, 214),
        )
        private const val REQ_IMAGE = 41

        /** Для проверок: распознавание выделенного без ML Kit (в Robolectric его нет). */
        internal var testRecognizer: ((List<InkStroke>) -> String?)? = null
    }
}
