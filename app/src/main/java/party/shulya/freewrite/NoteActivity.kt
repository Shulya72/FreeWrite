package party.shulya.freewrite

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * Экран заметки (0.31 — как Заметки на айфоне, Владимир прислал видео с экрана айфона).
 *
 * Лист — сплошной текст ([NoteEdit]): курсор куда угодно, выделение через строки, «Вырезать / Скопировать / Вставить».
 * Картинки стоят между кусками текста. Сверху — «‹», «Отменить», «Вернуть», «Поделиться», «⋯» и «✓» (готово, убрать
 * клавиатуру). Снизу — плавающая панель: при клавиатуре — «Aa», чек-лист, «Вложить», перо и (выделили текст) Ж, К, Ч, З,
 * цвет, списки, отступы, строку выше/ниже, цитата; при пере — В текст, Ручка, Маркер, Фигуры, Ластик, Указка, Выделить, цвета.
 *
 * Пером пишут в любом месте листа: после паузы (или когда начали следующее слово) надпись распознаётся — слово серым у
 * курсора, затем текстом, буквы улетают в строку. Ручка и маркер оставляют рисунок рисунком (рисунки едут вместе с текстом,
 * когда выше дописали строки), «Фигуры» делают ровную фигуру. Пальцем — прокрутка, курсор, выделение, галочки.
 */
class NoteActivity : Activity(), NoteInkView.Host, NoteEdit.Host {

    enum class Tool { WRITE, KEYS, PEN, MARKER, SHAPE, ERASER, HAND, SELECT, PIPETTE }

    private lateinit var store: NoteStore
    private lateinit var note: Note
    private lateinit var engine: HandwritingEngine
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private lateinit var rootView: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var sheet: RuledSheet
    private lateinit var ink: NoteInkView
    private lateinit var mini: LinearLayout
    private lateinit var imageBar: LinearLayout
    private lateinit var hintView: TextView
    private lateinit var banner: LinearLayout
    private lateinit var bannerText: TextView
    private lateinit var bannerButton: TextView
    private lateinit var dateView: TextView
    private lateinit var spacer: View
    private lateinit var doneButton: View
    private lateinit var keysBar: View
    private lateinit var keysStrip: HorizontalScrollView
    private lateinit var formatStart: View
    private lateinit var penBar: View
    private val toolButtons = HashMap<Tool, Ui.ToolButton>()
    private val barButtons = HashMap<String, View>()
    private val quickColors = ArrayList<View>()
    private var toolBeforePipette = Tool.PEN
    private var pipetteSnap: ColorKit.Snapshot? = null

    private var tool = Tool.WRITE
    private var penColor = Ui.PEN_INK
    private var fingerWrites = false
    private var lineHeightPx = 0

    /** Куски листа по порядку: поле текста ([NoteEdit]) или картинка; первый — всегда поле (в нём название). */
    private val segs = ArrayList<View>()
    private val edits: List<NoteEdit> get() = segs.filterIsInstance<NoteEdit>()
    /** Поле с курсором. */
    private var cur: NoteEdit? = null
    /** Номер блока записи у каждой строки поля (-1 — название или пустая строка между картинками). */
    private val paraBlock = java.util.IdentityHashMap<NoteEdit, IntArray>()
    private var titleCaret = false
    private var selectedImage: Block.Image? = null
    private var imageEditing = false
    private val bitmapCache = HashMap<String, Bitmap>()
    private var interim = ""

    // Снимки для «Отменить»: блоки копируются, рисунки — те же (рисунки не меняют на месте, только добавляют, стирают и
    // заменяют новыми).
    private class Snap(val title: String, val blocks: List<Block>, val drawings: List<Drawing>, val caret: Int, val caretOffset: Int, val titleCaret: Boolean)
    private val undoStack = ArrayDeque<Snap>()
    private val redoStack = ArrayDeque<Snap>()
    /** Правка идёт из кода (перо, «Aa», картинка): шаг «Отменить» уже записан. */
    private var programmatic = 0
    private var burstEdit: NoteEdit? = null
    private var lastEditAt = 0L
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
        val keys = intent.getBooleanExtra(EXTRA_KEYS, false)
        // Без пера (телефон) — как на айфоне: клавиатура; с пером — письмо от руки.
        stylusKnown = Input.stylusSeen(this)
        tool = if (keys || !stylusKnown) Tool.KEYS else Tool.WRITE
        buildUi()
        setContentView(rootView)
        engine.start(this)
        // Разлиновки больше нет (Владимир: «пусть будет максимально похоже на айфоновские заметки»): все заметки — чистый лист.
        // Записи до 0.30 и записи без рисунков — сразу; в линейку и в клетку с рисунками — после первой раскладки (рисунки
        // сдвигаются вслед за своими строками, `migratePaper`).
        if (note.paper != NotePaper.NONE && (note.layoutV < 2 || note.drawings.isEmpty())) { note.paper = NotePaper.NONE; saveSoon() }
        render(titleCaret = keys)
        updateBanner()
        setTool(tool, focus = keys, force = true)
        if (note.layoutV < 2 || note.paper != NotePaper.NONE) sheet.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (sheet.width == 0) return
                sheet.viewTreeObserver.removeOnGlobalLayoutListener(this)
                if (note.layoutV < 2) migrateLayout() else migratePaper()
            }
        })
    }

    override fun onPause() {
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

    private fun dp(v: Float) = Ui.dp(this, v)

    private fun roundButton(icon: Int, desc: String, tag: String, fill: Int = Ui.PAPER, tint: Int = Ui.INK, f: (View) -> Unit): ImageView =
        ImageView(this).apply {
            this.tag = tag
            contentDescription = desc
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(tint)
            background = Ui.rounded(this@NoteActivity, fill, if (fill == Ui.PAPER) Ui.BAR_LINE else null, 999f)
            elevation = dp(2f).toFloat()
            setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
            layoutParams = LinearLayout.LayoutParams(dp(44f), dp(44f))
            setOnClickListener { f(it) }
        }

    private fun capsuleIcon(icon: Int, desc: String, tag: String, f: (View) -> Unit): ImageView = ImageView(this).apply {
        this.tag = tag
        contentDescription = desc
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(Ui.INK)
        setPadding(dp(11f), dp(10f), dp(11f), dp(10f))
        layoutParams = LinearLayout.LayoutParams(dp(46f), dp(44f))
        setOnClickListener { f(it) }
    }

    private fun buildUi() {
        rootView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.PAPER)
            // Курсор не уходит сам в поле при открытии экрана — только по касанию или по команде.
            isFocusableInTouchMode = true
            isFocusable = true
            descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
        }

        // Сверху, как на айфоне: «‹» слева; справа — «Отменить», «Вернуть», «Поделиться», «⋯» в одной капсуле и «✓».
        val top = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Ui.PAPER)
            setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
        }
        top.addView(roundButton(R.drawable.ic_back, "Назад", "note_back") { onBackPressed() })
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val group = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(this@NoteActivity, Ui.PAPER, Ui.BAR_LINE, 999f)
            elevation = dp(2f).toFloat()
            setPadding(dp(4f), 0, dp(4f), 0)
        }
        group.addView(capsuleIcon(R.drawable.ic_undo, "Отменить", "note_undo") { undo() })
        group.addView(capsuleIcon(R.drawable.ic_redo, "Вернуть", "note_redo") { redo() })
        group.addView(capsuleIcon(R.drawable.ic_share, "Поделиться", "note_share") { shareNote() })
        group.addView(capsuleIcon(R.drawable.ic_more, "Ещё", "note_more") { noteMenu(it) })
        top.addView(group, LinearLayout.LayoutParams(Ui.WRAP, dp(44f)))
        doneButton = roundButton(R.drawable.ic_done, "Готово", "note_done", fill = Ui.ACCENT, tint = Color.WHITE) { finishTyping() }
        top.addView(doneButton, LinearLayout.LayoutParams(dp(44f), dp(44f)).apply { marginStart = dp(10f) })
        rootView.addView(top, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))

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
        val area = FrameLayout(this).apply { setBackgroundColor(Ui.PAPER) }
        scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = true
        }
        val pad = FrameLayout(this)
        sheet = RuledSheet(this).apply {
            lineGap = lineHeightPx.toFloat()
            setBackgroundColor(Ui.PAPER)
            maxWidthPx = dp(860f)
            tallBands = { tallBands() }
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

        dateView = TextView(this).apply {
            tag = "note_date"
            textSize = 13f
            setTextColor(Ui.MUTED)
            gravity = Gravity.CENTER_HORIZONTAL
            includeFontPadding = false
        }
        spacer = View(this).apply {
            minimumHeight = max(dp(320f), (resources.displayMetrics.heightPixels * 0.4f).toInt())
            // Пустое место ниже текста: здесь тоже можно писать пером; касание пальцем — курсор в конец.
            setOnClickListener {
                runAfterCommit {
                    val e = lastEdit()
                    setCaretIn(e, e.text.length)
                    if (tool == Tool.KEYS) showKeyboard()
                }
            }
        }

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
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = dp(64f)
        })
        rootView.addView(area, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
        // Нижняя панель — под листом (не поверх): строка с курсором над клавиатурой не прячется под панелью.
        val bottom = FrameLayout(this).apply {
            setBackgroundColor(Ui.PAPER)
            if (!thinBars) setPadding(0, dp(4f), 0, dp(8f))
        }
        buildBottomBars(bottom)
        rootView.addView(bottom, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        rootView.addView(buildFormatPanel(), LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        // Клавиатура выехала (коснулись текста) — «Aa» уходит, как на айфоне.
        rootView.viewTreeObserver.addOnGlobalLayoutListener {
            val w = rootView.width
            val h = rootView.height
            if (w != layoutW) { layoutW = w; fullH = h }
            fullH = max(fullH, h)
            val ime = h < fullH * 0.8f
            if (ime && !imeShown && fmtScroll.visibility == View.VISIBLE) closeFormat(keyboard = false)
            imeShown = ime
        }
        refreshColors()
    }

    /**
     * На телефоне — тонкая полоса во всю ширину, прижатая к клавиатуре (Владимир: капсула «будет перекрывать текст»), на
     * планшете — капсула.
     */
    private val thinBars: Boolean get() = Input.isPhone(this)

    /** Плавающая капсула внизу (как на айфоне): белая, со скруглением и тенью, прокручивается вбок, если не влезает. */
    private fun capsule(row: LinearLayout, tag: String): Pair<View, HorizontalScrollView> {
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(8f), if (thinBars) 0 else dp(3f), dp(8f), if (thinBars) 0 else dp(3f))
        val hs = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row, ViewGroup.LayoutParams(Ui.WRAP, Ui.WRAP))
        }
        if (thinBars) {
            // Тонкая полоса: линия сверху, без скругления и тени.
            val strip = LinearLayout(this).apply {
                this.tag = tag
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Ui.BAR)
                addView(Ui.barLine(this@NoteActivity), LinearLayout.LayoutParams(Ui.MATCH, dp(1f)))
                addView(hs, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
            }
            return strip to hs
        }
        val box = FrameLayout(this).apply {
            this.tag = tag
            background = Ui.rounded(this@NoteActivity, Ui.PAPER, Ui.BAR_LINE, 26f)
            elevation = dp(8f).toFloat()
            addView(hs, FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP))
        }
        return box to hs
    }

    private fun barIcon(icon: Int, desc: String, tag: String, f: (View) -> Unit): Ui.ToolButton = Ui.ToolButton(this, icon, "", compact = true).apply {
        this.tag = tag
        contentDescription = desc
        minimumWidth = dp(if (thinBars) 42f else 46f)
        val v = if (thinBars) 7f else 9f
        setPadding(dp(9f), dp(v), dp(9f), dp(v))
        setOnClickListener { f(it) }
        barButtons[tag] = this
    }

    /** Ж, К, Ч, З на панели клавиатуры — буквой своего вида. */
    private fun barLetter(text: String, desc: String, tag: String, f: () -> Unit): TextView = TextView(this).apply {
        this.tag = tag
        contentDescription = desc
        this.text = text
        textSize = 19f
        setTextColor(Ui.INK)
        gravity = Gravity.CENTER
        minWidth = dp(if (thinBars) 40f else 44f)
        minHeight = dp(if (thinBars) 38f else 42f)
        setPadding(dp(6f), 0, dp(6f), 0)
        isClickable = true
        setOnClickListener { f() }
        barButtons[tag] = this
    }

    private fun buildBottomBars(area: FrameLayout) {
        // При клавиатуре: «Aa», чек-лист, «Вложить», перо — дальше оформление выделенного (панель сама прокручивается к нему).
        val keysRow = LinearLayout(this)
        keysRow.addView(aaButton("fmt_open"))
        keysRow.addView(barIcon(R.drawable.ic_checklist, "Чек-лист", "keys_task") { toggleTaskLines() })
        keysRow.addView(barIcon(R.drawable.ic_attach, "Вложить", "keys_image") { attachMenu(it) })
        keysRow.addView(barIcon(R.drawable.ic_markup, "Перо", "keys_pen") { setTool(Tool.WRITE) })
        keysRow.addView(Ui.separator(this))
        formatStart = barLetter("B", "Жирный", "bar_b") { applyInline(Rich.B) }.apply { typeface = Typeface.DEFAULT_BOLD }
        keysRow.addView(formatStart)
        keysRow.addView(barLetter("I", "Курсив", "bar_i") { applyInline(Rich.I) }.apply { setTypeface(Typeface.SERIF, Typeface.ITALIC) })
        keysRow.addView(barLetter("U", "Подчёркнутый", "bar_u") { applyInline(Rich.U) }.apply { paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG })
        keysRow.addView(barLetter("S", "Зачёркнутый", "bar_s") { applyInline(Rich.S) }.apply { paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG })
        keysRow.addView(barIcon(R.drawable.ic_highlight, "Цвет текста", "bar_color") { colorMenu(it) })
        keysRow.addView(barIcon(R.drawable.ic_list, "Список", "bar_list") { listMenu(it) })
        keysRow.addView(barIcon(R.drawable.ic_outdent, "Отступ меньше", "bar_outdent") { applyIndent(-1) })
        keysRow.addView(barIcon(R.drawable.ic_indent, "Отступ больше", "bar_indent") { applyIndent(1) })
        keysRow.addView(barIcon(R.drawable.ic_line_up, "Строку выше", "bar_up") { moveLines(-1) })
        keysRow.addView(barIcon(R.drawable.ic_line_down, "Строку ниже", "bar_down") { moveLines(1) })
        keysRow.addView(barIcon(R.drawable.ic_quote, "Цитата", "bar_quote") { applyList(ParaStyle.QUOTE) })
        keysRow.addView(Ui.separator(this))
        keysRow.addView(barIcon(R.drawable.ic_keyboard_hide, "Убрать клавиатуру", "keys_hide") { hideKeyboard() })
        val (kb, ks) = capsule(keysRow, "keys_bar")
        keysBar = kb
        keysStrip = ks

        // При пере: «Aa», В текст, Ручка, Маркер, Фигуры, Ластик, Указка, Выделить, три цвета, «Цвет», «Вложить», клавиатура.
        val penRow = LinearLayout(this)
        penRow.addView(aaButton("fmt_open_pen"))
        penRow.addView(Ui.separator(this))
        addTool(penRow, Tool.WRITE, R.drawable.ic_write, "В текст")
        addTool(penRow, Tool.PEN, R.drawable.ic_pen, "Ручка")
        addTool(penRow, Tool.MARKER, R.drawable.ic_marker, "Маркер")
        addTool(penRow, Tool.SHAPE, R.drawable.ic_shapes, "Фигуры")
        addTool(penRow, Tool.ERASER, R.drawable.ic_eraser, "Ластик")
        addTool(penRow, Tool.HAND, R.drawable.ic_hand, "Указка")
        addTool(penRow, Tool.SELECT, R.drawable.ic_lasso, "Выделить")
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
        penRow.addView(Ui.ToolButton(this, R.drawable.ic_attach, "Вложить").apply { tag = "note_image"; setOnClickListener { attachMenu(it) } })
        addTool(penRow, Tool.KEYS, R.drawable.ic_keyboard, "Клавиатура")
        penBar = capsule(penRow, "pen_bar").first

        for (b in listOf(keysBar, penBar)) area.addView(b, if (thinBars) FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP)
            else FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setMargins(dp(10f), dp(2f), dp(10f), dp(2f))
            })
    }

    /** Кнопка «Aa» — окно «Формат». */
    private fun aaButton(tag: String): View = Ui.text(this, "Aa", 19f, Ui.INK, bold = true).apply {
        this.tag = tag
        contentDescription = "Формат"
        gravity = Gravity.CENTER
        setPadding(dp(10f), dp(if (thinBars) 6f else 8f), dp(10f), dp(if (thinBars) 6f else 8f))
        layoutParams = LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { setMargins(dp(2f), 0, dp(2f), 0) }
        setOnClickListener { toggleFormat() }
    }

    private fun addTool(row: LinearLayout, t: Tool, icon: Int, label: String) {
        val b = Ui.ToolButton(this, icon, label).apply { setOnClickListener { setTool(t) } }
        b.tag = "tool_" + t.name.lowercase()
        toolButtons[t] = b
        row.addView(b)
    }

    private fun setTool(t: Tool, focus: Boolean = true, force: Boolean = false) {
        if (t != Tool.WRITE) current?.let { commit(it) }
        if (t != Tool.SELECT && t != Tool.PIPETTE) clearSelection()
        val was = tool
        tool = t
        val keys = t == Tool.KEYS
        for (e in edits) e.showSoftInputOnFocus = keys
        if (keys && (was != Tool.KEYS || force)) {
            if (focus) showKeyboard()
        } else if (!keys && (was == Tool.KEYS || force)) {
            hideKeyboard()
        }
        for ((k, v) in toolButtons) v.selectedTool = k == t
        updateImageBar()
        updateBars()
        updateBanner()
        if (t != Tool.SHAPE) shapeTool.reset()
        if (t == Tool.SHAPE && !shapeHintShown) {
            shapeHintShown = true
            hint("Нарисуйте линию, стрелку, квадрат, круг или треугольник — станет ровной фигурой", 3500)
        }
        refreshColors()
    }

    private var stripAtFormat = false
    private var layoutW = 0
    private var fullH = 0
    private var imeShown = false

    /** Нижняя панель: клавиатура (или выделили текст) — оформление, иначе — перо; «✓» — пока печатают. */
    private fun updateBars() {
        if (!::keysBar.isInitialized) return
        val sel = cur?.let { it.selectionStart != it.selectionEnd } == true
        val strip = tool == Tool.KEYS || sel
        keysBar.visibility = if (strip) View.VISIBLE else View.GONE
        penBar.visibility = if (strip) View.GONE else View.VISIBLE
        doneButton.visibility = if (tool == Tool.KEYS) View.VISIBLE else View.GONE
        if (sel != stripAtFormat) {
            stripAtFormat = sel
            keysStrip.post { keysStrip.smoothScrollTo(if (sel) max(0, formatStart.left - dp(8f)) else 0, 0) }
        }
    }

    private val imm get() = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager

    private fun showKeyboard() {
        val e = caretEdit()
        e.showSoftInputOnFocus = true
        if (!e.hasFocus()) e.requestFocus()
        e.post { imm.showSoftInput(e, InputMethodManager.SHOW_IMPLICIT) }
    }

    private fun hideKeyboard() {
        imm.hideSoftInputFromWindow(rootView.windowToken, 0)
    }

    /** «✓» (как на айфоне): правку закончили — клавиатура уходит, выделение снимается. */
    private fun finishTyping() {
        runAfterCommit {
            hideKeyboard()
            cur?.let { if (it.selectionStart != it.selectionEnd) it.setSelection(it.selectionEnd) }
            updateBars()
        }
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
        // Модель нужна только для письма пером в текст: печатают с клавиатуры — плашку не показываем.
        if (tool == Tool.KEYS && ru.state != HandwritingEngine.State.DOWNLOADING) { banner.visibility = View.GONE; return }
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

    // ---------------------------------------------------------------- лист: куски текста и картинки

    private fun cells(h: Int): Int {
        if (note.paper == NotePaper.NONE || lineHeightPx <= 0 || h <= 0) return h
        return ((h + lineHeightPx - 1) / lineHeightPx) * lineHeightPx
    }

    /** Разлиновка листа: без разметки — поля как на айфоне; в линейку и в клетку — как раньше (рисунки старых записей на месте). */
    private fun applyPaper() {
        sheet.paper = note.paper
        if (note.paper == NotePaper.NONE) sheet.setPadding(dp(22f), dp(6f), dp(22f), dp(24f))
        else sheet.setPadding(dp(56f), dp(18f), dp(36f), dp(24f))
        val meta = Ui.noteMeta(note.created)
        if (note.paper == NotePaper.NONE) {
            dateView.text = meta
            dateView.setPadding(0, dp(4f), 0, dp(10f))
        } else {
            dateView.setPadding(0, 0, 0, 0)
            dateView.text = SpannableStringBuilder(meta).also {
                it.setSpan(CellHeight(lineHeightPx, dp(4f)), 0, it.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            }
        }
    }

    private fun tallBands(): List<FloatArray> {
        val out = ArrayList<FloatArray>()
        for (v in segs) if (v is NoteEdit) for (b in v.tallBands()) out += floatArrayOf(v.top + b[0], v.top + b[1])
        return out
    }

    private fun newEdit(first: Boolean): NoteEdit = NoteEdit(this, this, first).apply {
        tag = "note_edit"
        showSoftInputOnFocus = tool == Tool.KEYS
        setOnFocusChangeListener { v, has -> if (has) { cur = v as NoteEdit; updateBars(); refreshFormat() } }
    }

    private class ImgHolder(ctx: Context, val block: Block.Image) : TextView(ctx) {
        init {
            text = "[картинка не найдена]"
            textSize = 14f
            setTextColor(Ui.MUTED)
        }
    }

    private fun blockOfView(v: View?): Block.Image? = (v as? NoteImageView)?.block ?: (v as? ImgHolder)?.block

    private fun imageView(b: Block.Image): View {
        // Картинку можно растянуть на всю ширину листа — загружаем с запасом по размеру.
        val bmp = bitmapCache.getOrPut(b.file) { store.loadImage(b, dp(800f)) ?: return ImgHolder(this, b).apply {
            setOnLongClickListener { removeImage(b); true }
        } }
        return NoteImageView(this, b, bmp, imageHost)
    }

    private fun attrOf(b: TextLine): PAttr = when (b) {
        is Block.Task -> PAttr(true, ParaStyle.BODY, b.indent, b.done)
        is Block.Para -> PAttr(false, b.style, b.indent, false)
        else -> PAttr()
    }

    private fun blockOf(l: ELine): Block = if (l.attr.task) Block.Task(l.text, l.attr.done, l.attr.indent, l.spans.toMutableList())
        else Block.Para(l.text, l.attr.style, l.attr.indent, l.spans.toMutableList())

    /** Собрать лист заново из записи и поставить курсор: в блок [caretBlock] на место [caretOff] (-1 — конец) или в название. */
    private fun render(caretBlock: Int = note.caret, caretOff: Int = note.caretOffset, titleCaret: Boolean = false) {
        val keepScroll = if (::scroll.isInitialized) scroll.scrollY else 0
        if (selectedImage != null && note.blocks.none { it === selectedImage }) selectedImage = null
        sheet.removeAllViews()
        segs.clear()
        cur = null
        applyPaper()
        sheet.addView(dateView, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        var lines = ArrayList<ELine>()
        lines += ELine(note.title, emptyList(), PAttr(style = ParaStyle.TITLE))
        var first = true
        fun flush() {
            val e = newEdit(first)
            e.load(lines)
            segs += e
            sheet.addView(e, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
            first = false
            lines = ArrayList()
        }
        // Новая заметка (без названия, одна пустая строка): на листе только название (как на айфоне).
        val only = note.blocks.singleOrNull() as? Block.Para
        val blank = note.title.isEmpty() && only != null && only.text.isEmpty() && only.style == ParaStyle.BODY && only.indent == 0 && only.spans.isEmpty()
        if (!blank) for (b in note.blocks) when (b) {
            is Block.Image -> {
                flush()
                val v = imageView(b)
                segs += v
                sheet.addView(v, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply {
                    if (v is NoteImageView) {
                        // Вид картинки чуть шире текста — уголки у края листа не обрезаются.
                        leftMargin = -dp(NoteImageView.EDGE_DP)
                        rightMargin = -dp(NoteImageView.EDGE_DP)
                    }
                })
            }
            is Block.Para -> lines += ELine(b.text, b.spans, attrOf(b))
            is Block.Task -> lines += ELine(b.text, b.spans, attrOf(b))
        }
        flush()
        sheet.addView(spacer, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        syncModel(maps = true)
        placeCaret(caretBlock, caretOff, titleCaret || blank)
        updateImageBar()
        updateBars()
        ink.invalidate()
        if (keepScroll > 0) scroll.post { scroll.scrollTo(0, keepScroll) }
    }

    private fun firstEdit(): NoteEdit = segs.first { it is NoteEdit } as NoteEdit
    private fun lastEdit(): NoteEdit = segs.last { it is NoteEdit } as NoteEdit

    /** Поле с курсором (его нет на листе — первое). */
    private fun caretEdit(): NoteEdit = cur?.takeIf { it.parent === sheet } ?: firstEdit()

    private fun setCaretIn(e: NoteEdit, raw: Int) {
        cur = e
        if (!e.hasFocus()) e.requestFocus()
        e.setSelection(raw.coerceIn(0, e.text.length))
        updateBars()
    }

    private fun placeCaret(block: Int, off: Int, title: Boolean) {
        if (title) { val e = firstEdit(); setCaretIn(e, e.rawOffset(0, -1)); return }
        for (v in segs) {
            if (v !is NoteEdit) continue
            val i = paraBlock[v]?.indexOf(block) ?: -1
            if (i >= 0) { setCaretIn(v, v.rawOffset(i, off)); return }
        }
        // Курсор был на картинке — в начало текста под ней.
        val img = note.blocks.getOrNull(block)
        val at = segs.indexOfFirst { img != null && blockOfView(it) === img }
        val next = segs.drop(max(0, at + 1)).firstOrNull { it is NoteEdit } as? NoteEdit
        if (at >= 0 && next != null) { setCaretIn(next, next.rawOffset(0, 0)); return }
        val e = lastEdit()
        setCaretIn(e, e.text.length)
    }

    /**
     * Запись — по листу: название, блоки (строки полей и картинки), курсор. [maps] — только пересчитать, какой строке какой
     * блок соответствует. Пустой кусок между картинками (одна пустая строка) — без блоков.
     */
    private fun syncModel(maps: Boolean = false) {
        val blocks = ArrayList<Block>()
        var title = note.title
        paraBlock.clear()
        var caretB = -1
        var caretO = -1
        var inTitle = false
        val ce = cur
        for (v in segs) when (v) {
            is NoteEdit -> {
                val ls = v.lines()
                val map = IntArray(ls.size) { -1 }
                var from = 0
                if (v.first) { title = ls[0].text; from = 1 }
                val part = if (from < ls.size) ls.subList(from, ls.size) else emptyList()
                val skip = !v.first && part.size == 1 && part[0].text.isEmpty() && part[0].attr == PAttr() && part[0].spans.isEmpty()
                if (!skip) for (k in from until ls.size) { map[k] = blocks.size; blocks += blockOf(ls[k]) }
                paraBlock[v] = map
                if (v === ce) {
                    val (i, o) = v.caretAt()
                    if (map.getOrElse(i) { -1 } >= 0) {
                        caretB = map[i]
                        caretO = if (o >= ls[i].text.length) -1 else o
                    } else if (v.first && i == 0) inTitle = true
                    else caretB = max(0, blocks.size - 1)
                }
            }
            else -> blockOfView(v)?.let { blocks += it }
        }
        if (blocks.isEmpty()) blocks += Block.Para("")
        if (maps) return
        note.title = title
        note.blocks.clear()
        note.blocks.addAll(blocks)
        if (ce != null) {
            titleCaret = inTitle
            note.caret = if (caretB >= 0) caretB.coerceIn(0, blocks.size - 1) else 0
            note.caretOffset = if (inTitle) 0 else caretO
        }
    }

    // ---------------------------------------------------------------- поле текста (NoteEdit.Host)

    override fun cellPx(): Int = if (note.paper == NotePaper.NONE) 0 else lineHeightPx

    override fun beforeEdit(e: NoteEdit, start: Int) {
        if (programmatic == 0) {
            val now = SystemClock.uptimeMillis()
            // Серия нажатий — один шаг «Отменить» (пауза больше 1,5 с или другой кусок — новый шаг).
            if (e !== burstEdit || now - lastEditAt > 1500) pushUndo()
            burstEdit = e
            lastEditAt = now
        }
        if (shiftEdit == null) beginShift(e, start)
    }

    override fun afterEdit(e: NoteEdit) {
        endShift(e)
        if (programmatic == 0) lastEditAt = SystemClock.uptimeMillis()
        cur = e
        changed()
        updateBars()
    }

    override fun onToggleDone(e: NoteEdit, i: Int) {
        runAfterCommit {
            pushUndo()
            burstEdit = null
            val a = e.paras.getOrNull(i) ?: return@runAfterCommit
            a.done = !a.done
            e.attrsChanged(i..i)
            changed()
            refreshFormat()
        }
    }

    /** «Стереть» в начале куска под картинкой: первый раз — выбрать картинку, второй — удалить её. */
    override fun onBackspaceAtStart(e: NoteEdit): Boolean {
        val si = segs.indexOf(e)
        val b = blockOfView(segs.getOrNull(si - 1)) ?: return false
        if (selectedImage === b) removeImage(b) else {
            selectImage(b)
            hint("Ещё раз «Стереть» — удалить картинку")
        }
        return true
    }

    override fun newStep() { burstEdit = null }

    override fun onSelection(e: NoteEdit, start: Int, end: Int) {
        cur = e
        if (selectedImage != null && start != end) selectImage(null)
        updateBars()
        refreshFormat()
    }

    // Рисунки едут вместе с текстом (0.31): выше дописали строки — рисунки ниже места правки сдвигаются на столько же.
    private var shiftEdit: NoteEdit? = null
    private var shiftY = 0f
    private var shiftH0 = 0

    private fun contentH(e: NoteEdit): Int {
        val l = e.layout ?: return e.height
        return cells(max(e.minHeight, l.height + e.totalPaddingTop + e.totalPaddingBottom))
    }

    private fun lineBottomY(e: NoteEdit, off: Int): Float {
        val l = e.layout ?: return e.bottom.toFloat()
        return e.top + e.totalPaddingTop + l.getLineBottom(l.getLineForOffset(off.coerceIn(0, e.text.length))).toFloat()
    }

    private fun beginShift(e: NoteEdit, start: Int) {
        if (note.drawings.isEmpty() || e.layout == null) return
        shiftEdit = e
        shiftY = lineBottomY(e, start)
        shiftH0 = contentH(e)
    }

    private fun endShift(e: NoteEdit) {
        if (shiftEdit !== e) return
        shiftEdit = null
        val dy = contentH(e) - shiftH0
        if (dy != 0) shiftDrawings(shiftY - 1f, dy.toFloat())
    }

    /** Сдвинуть рисунки, что ниже [fromY] (координаты листа), на [dy]. Рисунки — новыми (снимки «Отменить» не трогаются). */
    private fun shiftDrawings(fromY: Float, dy: Float) {
        var any = false
        for ((i, d) in note.drawings.withIndex()) {
            if (d.bounds().top < fromY) continue
            val pts = d.pts.copyOf()
            var j = 1
            while (j < pts.size) { pts[j] += dy; j += 3 }
            note.drawings[i] = Drawing(d.kind, d.color, d.width, pts).also { it.id = d.id }
            any = true
        }
        if (any) ink.invalidate()
    }

    /** Правка из кода (перо, «Aa», панель): рисунки ниже сдвигаются, шаг «Отменить» записан заранее. */
    private inline fun quietEdit(e: NoteEdit, f: () -> Unit) {
        programmatic++
        val y0 = lineBottomY(e, min(e.selectionStart, e.selectionEnd).coerceAtLeast(0))
        val h0 = contentH(e)
        try { f() } finally { programmatic-- }
        if (shiftEdit === e) shiftEdit = null
        val dy = contentH(e) - h0
        if (dy != 0 && note.drawings.isNotEmpty()) shiftDrawings(y0 - 1f, dy.toFloat())
    }

    // ---------------------------------------------------------------- правка текста (перо, мини-панель)

    /** Текст строки с курсором до и после курсора (без невидимых знаков) и стоит ли курсор внутри строки. */
    private fun caretContext(e: NoteEdit): Triple<String, String, Boolean> {
        val a = min(e.selectionStart, e.selectionEnd).coerceAtLeast(0)
        val b = max(e.selectionStart, e.selectionEnd).coerceAtLeast(0)
        val i = e.paraAt(a)
        val ps = e.paraStart(i)
        val pe = e.paraEnd(i)
        val before = e.text.subSequence(ps, a).toString().replace(NoteEdit.ZWS, "")
        val after = e.text.subSequence(min(b, pe), pe).toString().replace(NoteEdit.ZWS, "")
        return Triple(before, after, after.isNotEmpty())
    }

    private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '-'

    /**
     * Что вставить у курсора с учётом пробелов. Если курсор стоит внутри строки и вставка короткая (1–2 знака) рядом со
     * словом — без пробелов: это исправление буквы. При обычном письме в конец строки — слова через пробел.
     */
    private fun spacedInsert(before: String, after: String, word: String, placed: Boolean): String {
        if (word.isEmpty()) return word
        val touchesWord = (before.isNotEmpty() && isWordChar(before.last())) || (after.isNotEmpty() && isWordChar(after.first()))
        if (placed && word.length <= 2 && word.all { isWordChar(it) } && touchesWord) return word
        val lead = before.isNotEmpty() && !before.last().isWhitespace() && word.first() !in ".,!?;:)»…"
        val trail = after.isNotEmpty() && !after.first().isWhitespace() && after.first() !in ".,!?;:)»…" && !word.last().isWhitespace()
        return (if (lead) " " else "") + word + (if (trail) " " else "")
    }

    /** Вставить текст у курсора (выделенное заменяется). raw = true — как есть, без расстановки пробелов. */
    private fun insertAtCaret(word: String, raw: Boolean = false) {
        if (word.isEmpty()) return
        val e = caretEdit()
        pushUndo()
        burstEdit = null
        val a = min(e.selectionStart, e.selectionEnd).coerceAtLeast(0)
        val b = max(e.selectionStart, e.selectionEnd).coerceAtLeast(0)
        val (before, after, placed) = caretContext(e)
        val ins = if (raw) word else spacedInsert(before, after, word, placed)
        quietEdit(e) { e.text.replace(a, b, ins) }
        e.setSelection((a + ins.length).coerceAtMost(e.text.length))
        cur = e
    }

    private var lastCharDelete = 0L

    /** Стереть букву перед курсором; в начале строки — по правилам айфона (снять список, склеить строки). */
    private fun deleteChar() {
        val e = caretEdit()
        val now = SystemClock.uptimeMillis()
        if (now - lastCharDelete > 1500) { pushUndo(); burstEdit = null } // серия нажатий — один шаг отмены
        lastCharDelete = now
        quietEdit(e) { e.backspace() }
    }

    /** Стереть слово перед курсором (вместе с пробелами вокруг). Строка пустая — как «Стереть». */
    private fun deleteWord() {
        val e = caretEdit()
        val a = e.selectionStart.coerceAtLeast(0)
        val ps = e.paraStart(e.paraAt(a))
        val before = e.text.subSequence(ps, a).toString()
        if (before.replace(NoteEdit.ZWS, "").isBlank()) { deleteChar(); return }
        val cut = before.replace(Regex("""\s*[^\s​]+\s*$"""), "")
        pushUndo()
        burstEdit = null
        quietEdit(e) { e.text.delete(ps + cut.length, a) }
    }

    /** Новая строка (или задача). Курсор внутри строки — текст после него переносится; «задача» в пустой строке — пункт. */
    private fun newLine(task: Boolean) {
        val e = caretEdit()
        pushUndo()
        burstEdit = null
        val (i0, _) = e.caretAt()
        if (task && e.paraText(i0).isBlank() && !(e.first && i0 == 0)) {
            e.paras[i0].task = true
            e.paras[i0].style = ParaStyle.BODY
            quietEdit(e) { e.attrsChanged(i0..i0) }
            changed()
            return
        }
        val a = min(e.selectionStart, e.selectionEnd).coerceAtLeast(0)
        val b = max(e.selectionStart, e.selectionEnd).coerceAtLeast(0)
        quietEdit(e) { e.text.replace(a, b, "\n") }
        if (task) {
            val (i, _) = e.caretAt()
            e.paras[i].task = true
            e.paras[i].style = ParaStyle.BODY
            quietEdit(e) { e.attrsChanged(i..i) }
        }
        changed()
    }

    private fun editCaretDialog() {
        val e = caretEdit()
        val (i, _) = e.caretAt()
        val text = e.paraText(i)
        val edit = EditText(this).apply {
            setText(text)
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val box = FrameLayout(this).apply { setPadding(dp(20f), dp(8f), dp(20f), 0); addView(edit) }
        AlertDialog.Builder(this)
            .setTitle("Исправить строку")
            .setView(box)
            .setPositiveButton("Готово") { _, _ ->
                pushUndo()
                burstEdit = null
                val t = edit.text.toString().replace('\n', ' ')
                quietEdit(e) { e.text.replace(e.paraStart(i), e.paraEnd(i), t) }
                e.setSelection(e.paraEnd(i))
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    /** Кнопка, которая повторяет действие, пока её держат. */
    @SuppressLint("ClickableViewAccessibility")
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

    // ---------------------------------------------------------------- оформление: панель и «Aa»

    /** Правка оформления: один шаг «Отменить», рисунки ниже сдвигаются, если строки стали выше. */
    private fun fmt(undo: Boolean = true, f: (NoteEdit) -> Unit) = runAfterCommit {
        val e = caretEdit()
        if (undo) { pushUndo(); burstEdit = null }
        quietEdit(e) { f(e) }
        changed()
        refreshFormat()
    }

    /** Строки выделения, кроме названия. */
    private fun bodyParas(e: NoteEdit): IntRange {
        val r = e.selParas()
        return if (e.first && r.first == 0) 1..r.last else r
    }

    private fun applyInline(kind: String) {
        val e = caretEdit()
        fmt(undo = e.selectionStart != e.selectionEnd) { it.toggleKind(kind) }
    }

    private fun applyColor(kind: String, color: Int) {
        val e = caretEdit()
        fmt(undo = e.selectionStart != e.selectionEnd) { it.setColorKind(kind, color) }
    }

    /** Стиль строк (пункт чек-листа станет абзацем этого стиля). */
    private fun applyStyle(style: String) = fmt { e ->
        val r = bodyParas(e)
        if (r.isEmpty()) { hint("Это название заметки — оно всегда крупное"); return@fmt }
        for (i in r) e.paras[i].apply { task = false; done = false; this.style = style }
        e.attrsChanged(r)
    }

    /** Список / цитата: у всех строк уже этот — снять, иначе поставить. */
    private fun applyList(style: String) = fmt { e ->
        val r = bodyParas(e)
        if (r.isEmpty()) return@fmt
        val all = r.all { !e.paras[it].task && e.paras[it].style == style }
        for (i in r) e.paras[i].apply { task = false; done = false; this.style = if (all) ParaStyle.BODY else style }
        e.attrsChanged(r)
    }

    /** Список из меню (Нет / С маркерами / С пунктиром / С нумерацией) — ровно этот. */
    private fun setList(style: String) = fmt { e ->
        val r = bodyParas(e)
        if (r.isEmpty()) return@fmt
        for (i in r) e.paras[i].apply { task = false; done = false; this.style = style }
        e.attrsChanged(r)
    }

    /** Чек-лист: строки выделения — пунктами (уже все пункты — обычными строками). */
    private fun toggleTaskLines() = fmt { e ->
        val r = bodyParas(e)
        if (r.isEmpty()) {
            // Курсор в названии: чек-лист — с новой строки под ним.
            e.setSelection(e.rawOffset(0, -1))
            e.text.insert(e.selectionEnd, "\n")
            val i = e.caretAt().first
            e.paras[i].apply { task = true; style = ParaStyle.BODY }
            e.attrsChanged(i..i)
            return@fmt
        }
        val all = r.all { e.paras[it].task }
        for (i in r) e.paras[i].apply { task = !all; done = false; if (!all) style = ParaStyle.BODY }
        e.attrsChanged(r)
    }

    private fun applyIndent(d: Int) = fmt { e ->
        val r = bodyParas(e)
        if (r.isEmpty()) return@fmt
        for (i in r) e.paras[i].indent = (e.paras[i].indent + d).coerceIn(0, ParaStyle.MAX_INDENT)
        e.attrsChanged(r)
    }

    private fun moveLines(dir: Int) = fmt { e ->
        if (!e.moveLines(dir)) hint(if (dir < 0) "Выше двигать некуда" else "Ниже двигать некуда", 1500)
    }

    /** Шрифт: обычный (снять засечки и моноширинный), с засечками — у выделенного, моноширинный — у строк. */
    private fun applyFont(kind: String?) = fmt { e ->
        when (kind) {
            ParaStyle.MONO -> {
                val r = bodyParas(e)
                if (r.isEmpty()) return@fmt
                val all = r.all { !e.paras[it].task && e.paras[it].style == ParaStyle.MONO }
                for (i in r) e.paras[i].apply { task = false; style = if (all) ParaStyle.BODY else ParaStyle.MONO }
                e.attrsChanged(r)
            }
            Rich.SERIF -> e.toggleKind(Rich.SERIF)
            else -> {
                if (e.kindOn(Rich.SERIF)) e.toggleKind(Rich.SERIF)
                val r = bodyParas(e)
                for (i in r) if (!e.paras[i].task && e.paras[i].style == ParaStyle.MONO) e.paras[i].style = ParaStyle.BODY
                if (!r.isEmpty()) e.attrsChanged(r)
            }
        }
    }

    /** Цвет текста (как маркер на айфоне): Нет, Лиловый, Розовый, Оранжевый, Мятный, Синий. */
    private fun colorMenu(anchor: View) = runAfterCommit {
        val m = PopupMenu(this, anchor)
        m.menu.add(0, 0, 0, "Нет")
        for ((i, n) in PEN_COLOR_NAMES.withIndex()) m.menu.add(0, i + 1, i + 1, n)
        m.setOnMenuItemClickListener { applyColor(Rich.FC, if (it.itemId == 0) 0 else PEN_COLORS[it.itemId - 1]); true }
        m.show()
        lastMenu = m
    }

    private fun listMenu(anchor: View) = runAfterCommit {
        val e = caretEdit()
        val a = e.paras.getOrNull(e.caretAt().first) ?: PAttr()
        val m = PopupMenu(this, anchor)
        val items = listOf(ParaStyle.BODY to "Нет", ParaStyle.BULLET to "С маркерами", ParaStyle.DASH to "С пунктиром", ParaStyle.NUM to "С нумерацией")
        for ((i, p) in items.withIndex()) m.menu.add(0, i, i, p.second).apply {
            isCheckable = true
            isChecked = !a.task && (a.style == p.first || (p.first == ParaStyle.BODY && !ParaStyle.isList(a.style)))
        }
        m.setOnMenuItemClickListener { setList(items[it.itemId].first); true }
        m.show()
        lastMenu = m
    }

    private lateinit var fmtPanel: LinearLayout
    private lateinit var fmtScroll: View
    private val fmtButtons = HashMap<String, TextView>()

    private fun toggleFormat() {
        if (fmtScroll.visibility == View.VISIBLE) { closeFormat(); return }
        runAfterCommit {
            // Как на айфоне: «Aa» — на месте клавиатуры, лист над ним короче, строка с курсором на виду.
            hideKeyboard()
            refreshFormat()
            fmtScroll.visibility = View.VISIBLE
            revealCaret()
        }
    }

    /** Закрыть «Aa»; печатали — клавиатура возвращается. */
    private fun closeFormat(keyboard: Boolean = true) {
        if (fmtScroll.visibility != View.VISIBLE) return
        fmtScroll.visibility = View.GONE
        if (keyboard && tool == Tool.KEYS) showKeyboard()
    }

    /** Строку с курсором — на виду (после того как лист стал короче). */
    private fun revealCaret() {
        scroll.post {
            val e = cur ?: return@post
            val l = e.layout ?: return@post
            val line = l.getLineForOffset(e.selectionEnd.coerceIn(0, e.text.length))
            e.requestRectangleOnScreen(android.graphics.Rect(0, e.totalPaddingTop + l.getLineTop(line) - dp(8f),
                e.width, e.totalPaddingTop + l.getLineBottom(line) + dp(8f)), false)
        }
    }

    /**
     * Окно «Формат» (как «Aa» в Заметках на айфоне): стиль строки, Ж/К/Ч/З, шрифт, маркер, цвет текста, списки, цитата,
     * отступ. Стоит внизу на месте клавиатуры, лист над ним — короче (не поверх текста, Владимир: «весь текст перекрывается
     * значками»). Ряды кнопок прокручиваются вбок. Без выделения Ж/К/Ч/З и цвет — для того, что напечатают дальше.
     */
    private fun buildFormatPanel(): View {
        fmtPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f), dp(2f), dp(12f), dp(8f))
            tag = "fmt_panel"
            isClickable = true
        }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(Ui.text(this, "Формат", 15f, Ui.INK, bold = true), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        head.addView(Ui.text(this, "✕", 17f, Ui.INK2).apply {
            tag = "fmt_close"; setPadding(dp(14f), dp(6f), dp(4f), dp(4f)); setOnClickListener { closeFormat() }
        })
        fmtPanel.addView(head)
        fun row(): LinearLayout {
            val r = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            fmtPanel.addView(HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(r, ViewGroup.LayoutParams(Ui.WRAP, Ui.WRAP))
            }, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(6f) })
            return r
        }
        fun gap(r: LinearLayout) = r.addView(View(this).apply { setBackgroundColor(Ui.CHIP_LINE) },
            LinearLayout.LayoutParams(dp(1f), dp(24f)).apply { setMargins(dp(6f), 0, dp(6f), 0) })
        fun label(r: LinearLayout, t: String) = r.addView(Ui.text(this, t, 12f, Ui.MUTED, bold = true).apply { setPadding(0, 0, dp(6f), 0) })
        fun chip(r: LinearLayout, tag: String, text: String, f: () -> Unit): TextView = Ui.pill(this, text, Ui.INK, Ui.DESK).apply {
            this.tag = tag
            textSize = 13.5f
            setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
            setOnClickListener { f() }
            fmtButtons[tag] = this
        }.also { r.addView(it, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { marginEnd = dp(6f) }) }
        fun dotIn(r: LinearLayout, tag: String, color: Int, f: () -> Unit) =
            r.addView(dot(tag, color, f), LinearLayout.LayoutParams(dp(30f), dp(30f)).apply { marginEnd = dp(6f) })
        val styles = row()
        chip(styles, "fmt_title", "Название") { applyStyle(ParaStyle.TITLE) }.apply { typeface = Typeface.DEFAULT_BOLD }
        chip(styles, "fmt_h1", "Заголовок") { applyStyle(ParaStyle.H1) }
        chip(styles, "fmt_h2", "Подзаголовок") { applyStyle(ParaStyle.H2) }
        chip(styles, "fmt_body", "Основной") { applyStyle(ParaStyle.BODY) }
        chip(styles, "fmt_font_mono", "Моноширинный") { applyFont(ParaStyle.MONO) }.apply { typeface = Typeface.MONOSPACE }
        val marks = row()
        chip(marks, "fmt_b", "Ж") { applyInline(Rich.B) }.apply { typeface = Typeface.DEFAULT_BOLD }
        chip(marks, "fmt_i", "К") { applyInline(Rich.I) }.apply { setTypeface(Typeface.SERIF, Typeface.ITALIC) }
        chip(marks, "fmt_u", "Ч") { applyInline(Rich.U) }.apply { paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG }
        chip(marks, "fmt_s", "З") { applyInline(Rich.S) }.apply { paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG }
        gap(marks)
        chip(marks, "fmt_font_normal", "Обычный") { applyFont(null) }
        chip(marks, "fmt_font_serif", "С засечками") { applyFont(Rich.SERIF) }.apply { typeface = Typeface.SERIF }
        val hl = row()
        label(hl, "Маркер")
        for ((i, c) in HIGHLIGHTS.withIndex()) dotIn(hl, "fmt_hl_$i", c) { applyColor(Rich.HL, c) }
        dotIn(hl, "fmt_hl_none", 0) { applyColor(Rich.HL, 0) }
        gap(hl)
        label(hl, "Цвет")
        dotIn(hl, "fmt_fc_none", 0) { applyColor(Rich.FC, 0) }
        for ((i, c) in TEXT_COLORS.withIndex()) dotIn(hl, "fmt_fc_$i", c) { applyColor(Rich.FC, c) }
        val lists = row()
        chip(lists, "fmt_bullet", "•  Список") { applyList(ParaStyle.BULLET) }
        chip(lists, "fmt_dash", "–  Список") { applyList(ParaStyle.DASH) }
        chip(lists, "fmt_num", "1.  Список") { applyList(ParaStyle.NUM) }
        chip(lists, "fmt_check", "○  Чек-лист") { toggleTaskLines() }
        chip(lists, "fmt_quote", "❝ Цитата") { applyList(ParaStyle.QUOTE) }
        chip(lists, "fmt_outdent", "⇤") { applyIndent(-1) }
        chip(lists, "fmt_indent", "⇥") { applyIndent(1) }
        // Внизу во всю ширину, с линией сверху; на широком экране кнопки — не шире 760 dp, по центру.
        val dock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BAR)
            visibility = View.GONE
            isClickable = true
            addView(Ui.barLine(this@NoteActivity), LinearLayout.LayoutParams(Ui.MATCH, dp(1f)))
            addView(fmtPanel, LinearLayout.LayoutParams(minOf(dp(760f), resources.displayMetrics.widthPixels), Ui.WRAP).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            })
        }
        fmtScroll = dock
        return dock
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
        layoutParams = ViewGroup.LayoutParams(dp(34f), dp(34f))
        minWidth = dp(34f); minHeight = dp(34f) // ряд с переносом меряет по содержимому — пустой кружок не схлопывается
        setOnClickListener { f() }
        fmtButtons[tag] = this
    }

    /** Подсветить на панели и в «Формате» то, что уже стоит у строки и выделенного. */
    private fun refreshFormat() {
        if (!::fmtPanel.isInitialized) return
        val e = cur ?: return
        val a = e.paras.getOrNull(e.caretAt(min(e.selectionStart, e.selectionEnd).coerceAtLeast(0)).first) ?: return
        val style = if (a.task) null else a.style
        fun bar(tag: String, v: Boolean) = when (val b = barButtons[tag]) {
            is Ui.ToolButton -> b.selectedTool = v
            is TextView -> {
                b.background = if (v) Ui.rounded(this, Ui.ACCENT_SOFT, null, 10f) else null
                b.setTextColor(if (v) Ui.ACCENT else Ui.INK)
            }
            else -> Unit
        }
        bar("bar_b", e.kindOn(Rich.B)); bar("bar_i", e.kindOn(Rich.I)); bar("bar_u", e.kindOn(Rich.U)); bar("bar_s", e.kindOn(Rich.S))
        bar("keys_task", a.task)
        bar("bar_list", style != null && ParaStyle.isList(style))
        bar("bar_quote", style == ParaStyle.QUOTE)
        bar("bar_color", e.kindOn(Rich.FC))
        if (fmtScroll.visibility != View.VISIBLE) return
        fun on(tag: String, v: Boolean) = fmtButtons[tag]?.let {
            if (it.text.isEmpty() || it.text == "✕") { it.alpha = if (v) 1f else 0.85f; return@let }
            it.background = Ui.rounded(this, if (v) Ui.INK else Ui.DESK, null, 999f)
            it.setTextColor(if (v) Color.WHITE else Ui.INK)
        }
        on("fmt_title", style == ParaStyle.TITLE); on("fmt_h1", style == ParaStyle.H1); on("fmt_h2", style == ParaStyle.H2)
        on("fmt_body", style == ParaStyle.BODY)
        on("fmt_bullet", style == ParaStyle.BULLET); on("fmt_dash", style == ParaStyle.DASH); on("fmt_num", style == ParaStyle.NUM)
        on("fmt_quote", style == ParaStyle.QUOTE); on("fmt_check", a.task)
        on("fmt_font_mono", style == ParaStyle.MONO)
        on("fmt_b", e.kindOn(Rich.B)); on("fmt_i", e.kindOn(Rich.I)); on("fmt_u", e.kindOn(Rich.U)); on("fmt_s", e.kindOn(Rich.S))
        on("fmt_font_serif", e.kindOn(Rich.SERIF)); on("fmt_font_normal", style != ParaStyle.MONO && !e.kindOn(Rich.SERIF))
    }

    // ---------------------------------------------------------------- картинки

    private var resizeH0 = 0
    private var resizeY0 = 0f

    private val imageHost = object : NoteImageView.Host {
        override fun isImageSelected(b: Block.Image) = b === selectedImage

        override fun onImageTap(v: NoteImageView) {
            runAfterCommit { selectImage(if (selectedImage === v.block) null else v.block) }
        }

        override fun onImageLongPress(v: NoteImageView) {
            AlertDialog.Builder(this@NoteActivity)
                .setMessage("Удалить картинку из записи?")
                .setPositiveButton("Удалить") { _, _ -> removeImage(v.block) }
                .setNegativeButton("Отмена", null)
                .show()
        }

        override fun onImageEditStart(v: NoteImageView) {
            imageEditing = true
            pushUndo()
            burstEdit = null
            resizeH0 = cells(v.height)
            resizeY0 = v.bottom.toFloat()
            if (selectedImage !== v.block) selectImage(v.block)
        }

        override fun onImageEditCancel(v: NoteImageView) {
            imageEditing = false
            if (undoStack.isNotEmpty()) undoStack.removeLast()
        }

        override fun onImageResized(v: NoteImageView) {
            imageEditing = false
            changed()
            sheet.post {
                val dy = cells(v.height) - resizeH0
                if (dy != 0) shiftDrawings(resizeY0 - 1f, dy.toFloat())
                ink.invalidate()
            }
        }

        override fun onImageDropped(v: NoteImageView) {
            imageEditing = false
            // Новое место — между строками, ближайшими к середине картинки.
            val cy = v.top + v.translationY + v.height / 2f
            v.translationY = 0f
            syncModel()
            val from = note.blocks.indexOfFirst { it === v.block }
            var pos = boundaryAt(cy, v)
            if (from >= 0 && pos > from) pos -= 1
            if (from >= 0 && pos != from) {
                note.blocks.removeAt(from)
                note.blocks.add(pos.coerceIn(0, note.blocks.size), v.block)
                changed()
                render(caretBlock = note.blocks.indexOfFirst { it === v.block })
            } else {
                v.invalidate()
                changed()
            }
        }
    }

    /** Номер блока, перед которым встать картинке, отпущенной на высоте [y] (координаты листа). */
    private fun boundaryAt(y: Float, skip: View): Int {
        syncModel(maps = true)
        var k = 0
        for (v in segs) {
            if (v === skip) { k++; continue }
            when (v) {
                is NoteEdit -> {
                    val map = paraBlock[v] ?: continue
                    val l = v.layout ?: continue
                    val st = v.starts()
                    for (i in map.indices) {
                        if (map[i] < 0) continue
                        val top = v.top + v.totalPaddingTop + l.getLineTop(l.getLineForOffset(st[i]))
                        val endOff = if (i + 1 < st.size) st[i + 1] - 1 else v.text.length
                        val bottom = v.top + v.totalPaddingTop + l.getLineBottom(l.getLineForOffset(endOff))
                        if (y < (top + bottom) / 2f) return map[i]
                        k = map[i] + 1
                    }
                }
                else -> blockOfView(v)?.let {
                    val bi = note.blocks.indexOfFirst { b -> b === it }
                    if (y < v.top + v.height / 2f) return bi
                    k = bi + 1
                }
            }
        }
        return k
    }

    private fun selectImage(b: Block.Image?) {
        selectedImage = b
        for (v in segs) if (v is NoteImageView) v.invalidate()
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

    private fun deleteSelectedImage() {
        val b = selectedImage ?: return
        selectedImage = null
        removeImage(b)
    }

    private fun removeImage(b: Block.Image) {
        runAfterCommit {
            pushUndo()
            burstEdit = null
            syncModel()
            val i = note.blocks.indexOfFirst { it === b }
            if (i < 0) return@runAfterCommit
            var caret = note.caret
            val off = note.caretOffset
            note.blocks.removeAt(i)
            if (note.blocks.isEmpty()) note.blocks += Block.Para("")
            if (caret > i) caret -= 1
            if (selectedImage === b) selectedImage = null
            changed()
            render(caret.coerceIn(0, note.blocks.size - 1), off, titleCaret)
        }
    }

    /** Картинку — к курсору (как на айфоне): курсор внутри строки — строка делится, картинка встаёт между частями. */
    private fun insertImage(block: Block.Image) = runAfterCommit {
        val e = caretEdit()
        pushUndo()
        burstEdit = null
        val (i, o) = e.caretAt()
        val len = e.paraText(i).length
        val title = e.first && i == 0
        var split = false
        if (!title && o in 1 until len) { quietEdit(e) { e.text.insert(e.selectionEnd, "\n") }; split = true }
        syncModel()
        val map = paraBlock[e] ?: IntArray(0)
        val mi = map.getOrElse(if (split) i + 1 else i) { -1 }
        val at = when {
            title -> 0
            mi < 0 -> blocksBefore(e) // пустая строка под картинкой — сразу за ней
            split || len == 0 || o == 0 -> mi
            else -> mi + 1
        }.coerceIn(0, note.blocks.size)
        note.blocks.add(at, block)
        selectedImage = block
        changed()
        val next = note.blocks.getOrNull(at + 1)
        render(if (next is TextLine) at + 1 else at, 0)
    }

    /** Сколько блоков записи стоит выше поля [e]. */
    private fun blocksBefore(e: NoteEdit): Int {
        var n = 0
        for (v in segs) {
            if (v === e) break
            if (v is NoteEdit) n += paraBlock[v]?.count { it >= 0 } ?: 0 else if (blockOfView(v) != null) n++
        }
        return n
    }

    private var cameraFile: File? = null

    /** «Вложить» (скрепка, как на айфоне): снять фото, выбрать фото. */
    private fun attachMenu(anchor: View) = runAfterCommit {
        val m = PopupMenu(this, anchor)
        m.menu.add(0, 1, 0, "Снять фото")
        m.menu.add(0, 2, 1, "Выбрать фото")
        m.setOnMenuItemClickListener {
            when (it.itemId) { 1 -> takePhoto(); 2 -> pickImage() }
            true
        }
        m.show()
        lastMenu = m
    }

    private fun takePhoto() {
        current?.let { commit(it) }
        try {
            val dir = File(cacheDir, "camera").apply { mkdirs() }
            dir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 86_400_000L) it.delete() }
            val f = File(dir, "shot-${System.currentTimeMillis()}.jpg")
            cameraFile = f
            val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
            val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, uri)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_CAMERA)
        } catch (t: Throwable) {
            hint("Не нашлось камеры")
        }
    }

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
        if (resultCode != RESULT_OK) return
        val uri = when (requestCode) {
            REQ_IMAGE -> data?.data
            REQ_CAMERA -> cameraFile?.takeIf { it.length() > 0 }?.let { FileProvider.getUriForFile(this, "$packageName.files", it) }
            else -> null
        } ?: return
        hint("Добавляю картинку…", 1500)
        io.execute {
            val block = try { store.importImage(this, uri) } catch (t: Throwable) { null }
            if (requestCode == REQ_CAMERA) cameraFile?.delete()
            handler.post {
                if (block == null) hint("Не удалось открыть картинку") else insertImage(block)
            }
        }
    }

    // ---------------------------------------------------------------- «Выделить» и «В текст» (0.27.7)

    /**
     * Выделить написанное ручкой и превратить в напечатанный текст. Перо обводит штрихи (штрих выбран, если внутри больше
     * половины его точек); «В текст» — распознать построчно, каждая строка — строкой текста у курсора; штрихи распознанных
     * строк убираются. Один шаг «Отменить».
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

    override fun selectionRect(): RectF? {
        if (selection.isEmpty()) return null
        val r = RectF(selection[0].bounds())
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
        burstEdit = null
        val gone = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Drawing, Boolean>())
        gone.addAll(selection)
        note.drawings.removeAll { it in gone }
        selection.clear()
        changed()
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
            burstEdit = null
            val gone = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Drawing, Boolean>())
            for (k in ok) gone.addAll(lines[k])
            note.drawings.removeAll { it in gone }
            // Строки — у курсора: пустая строка курсора — первая из них, дальше — новые строки ниже.
            val e = caretEdit()
            val (i, _) = e.caretAt()
            val text = ok.joinToString("\n") { texts[it]!! }
            val blank = e.paraText(i).isBlank()
            quietEdit(e) {
                if (blank) e.text.replace(e.paraStart(i), e.paraEnd(i), text)
                else e.text.insert(e.paraEnd(i), "\n$text")
            }
            e.setSelection(e.paraEnd(i + ok.size - (if (blank) 1 else 0)).coerceAtMost(e.text.length))
            selection.clear()
            changed()
            updateImageBar()
            ink.invalidate()
            val words = ok.sumOf { texts[it]!!.split(Regex("\\s+")).size }
            hint("В текст: " + Ui.plural(words, "слово", "слова", "слов") + if (ok.size < lines.size) " (часть строк не разобрал — осталась рисунком)" else "", 2500)
        }
    }

    // ---------------------------------------------------------------- «⋯», разлиновка, поделиться

    private fun noteMenu(anchor: View) {
        runAfterCommit {
            val m = PopupMenu(this, anchor)
            m.menu.add(0, 1, 0, if (note.pinned) "Открепить" else "Закрепить")
            m.menu.add(0, 3, 2, "Итоги записи")
            m.menu.add(0, 4, 3, if (fingerWrites) "Палец пишет: да" else "Палец пишет: нет")
            m.menu.add(0, 5, 4, "Удалить заметку")
            m.setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> { note.pinned = !note.pinned; saveSoon(); hint(if (note.pinned) "Заметка закреплена" else "Заметка откреплена") }
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

    internal var lastMenu: PopupMenu? = null
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

    /** Текст заметки для «Поделиться»: строки с отметками списков и чек-листа. */
    internal fun plainText(): String {
        syncModel()
        return buildString {
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
    }

    private fun shareNote() {
        runAfterCommit {
            saveNow()
            val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, note.title.ifBlank { "Заметка" })
                .putExtra(Intent.EXTRA_TEXT, plainText())
            try { startActivity(Intent.createChooser(send, "Отправить заметку")) } catch (_: Throwable) { hint("Не нашлось приложения, чтобы отправить") }
        }
    }

    private fun showSummary() {
        runAfterCommit {
            syncModel()
            val tasks = note.blocks.filterIsInstance<Block.Task>().filter { it.text.isNotBlank() }
            val open = tasks.filter { !it.done }
            val done = tasks.filter { it.done }
            val text = buildString {
                append("Не сделано · ").append(open.size).append('\n')
                open.forEach { append("— ").append(it.text).append('\n') }
                if (open.isEmpty()) append("нет\n")
                append("\nСделано · ").append(done.size).append('\n')
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

    // ---------------------------------------------------------------- старые записи: рисунки — за своими строками (0.30)

    /**
     * Запись до 0.30 ([Note.layoutV] = 1): название и дата были другой высоты, строки — не по клеткам. Рисунки (схемы у строк)
     * сдвигаем вслед за их строкой: старое место строки считаем, как было, новое — по листу. Один раз, без «Отменить».
     */
    private fun migrateLayout() {
        if (note.layoutV >= 2) return
        syncModel(maps = true)
        if (note.drawings.isEmpty() || sheet.width == 0) { note.layoutV = 2; saveSoon(); return }
        // Лист до 0.30: поля 56 и 36 dp, сверху 18 dp. Теперь лист чистый, поля другие — рисунки сдвигаются и вбок.
        val w = sheet.width - dp(56f) - dp(36f)
        val dx = (sheet.paddingLeft - dp(56f)).toFloat()
        fun measure(v: View): Int {
            v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            return v.measuredHeight
        }
        fun oldLine(text: String, width: Int): Int {
            val tv = TextView(this).apply { textSize = 19f; setExactLineHeight(lineHeightPx); minHeight = lineHeightPx; this.text = text }
            tv.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            return tv.measuredHeight
        }
        val newTops = IntArray(note.blocks.size)
        val imageH = IntArray(note.blocks.size)
        for (v in segs) when (v) {
            is NoteEdit -> {
                val map = paraBlock[v] ?: continue
                val l = v.layout ?: continue
                val st = v.starts()
                for (i in map.indices) if (map[i] >= 0 && i < st.size) newTops[map[i]] = v.top + v.totalPaddingTop + l.getLineTop(l.getLineForOffset(st[i]))
            }
            else -> blockOfView(v)?.let { b ->
                val bi = note.blocks.indexOfFirst { it === b }
                if (bi >= 0) { newTops[bi] = v.top; imageH[bi] = v.height }
            }
        }
        var y = dp(18f) + measure(Ui.text(this, note.title, 26f, Ui.INK, bold = true).apply { setPadding(0, 0, 0, dp(2f)) }) +
            measure(Ui.mono(this, Ui.noteMeta(note.created), 12f).apply { setPadding(0, 0, 0, dp(10f)) })
        val oldTops = IntArray(note.blocks.size)
        for ((i, b) in note.blocks.withIndex()) {
            oldTops[i] = y
            y += when (b) {
                is Block.Para -> oldLine(b.text, w)
                is Block.Task -> maxOf(lineHeightPx, oldLine(b.text, w - dp(34f) - dp(80f)))
                is Block.Image -> imageH[i]
            }
        }
        for ((n, d) in note.drawings.withIndex()) {
            if (d.pts.size < 2) continue
            val y0 = d.pts[1]
            var k = 0
            while (k + 1 < oldTops.size && oldTops[k + 1] <= y0) k++
            val dy = (newTops[k] - oldTops[k]).toFloat()
            if (dy == 0f && dx == 0f) continue
            note.drawings[n] = moved(d, dx, dy)
        }
        note.layoutV = 2
        saveSoon() // не правка: время заметки в списке не меняется
        ink.invalidate()
    }

    private fun moved(d: Drawing, dx: Float, dy: Float): Drawing {
        val pts = d.pts.copyOf()
        var j = 0
        while (j + 1 < pts.size) { pts[j] += dx; pts[j + 1] += dy; j += 3 }
        return Drawing(d.kind, d.color, d.width, pts).also { it.id = d.id }
    }

    /**
     * Разлиновку убрали (0.31): заметка в линейку или в клетку становится чистым листом. Рисунки (схемы у строк) сдвигаются
     * вслед за своей строкой — с места строки на листе в клетку (как открыли) на её место на чистом листе. Один раз, без «Отменить».
     */
    private fun migratePaper() {
        val oldTops = anchorTops()
        val oldLeft = sheet.paddingLeft
        note.paper = NotePaper.NONE
        applyPaper()
        for (e in edits) e.relayout()
        sheet.requestLayout()
        sheet.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                sheet.viewTreeObserver.removeOnGlobalLayoutListener(this)
                val newTops = anchorTops()
                val dx = (sheet.paddingLeft - oldLeft).toFloat()
                for ((n, d) in note.drawings.withIndex()) {
                    if (d.pts.size < 2) continue
                    val y0 = d.pts[1]
                    var k = 0
                    while (k + 1 < oldTops.size && oldTops[k + 1] <= y0) k++
                    val dy = (newTops[k] - oldTops[k]).toFloat()
                    if (dy != 0f || dx != 0f) note.drawings[n] = moved(d, dx, dy)
                }
                saveSoon() // не правка: время заметки в списке не меняется
                ink.invalidate()
            }
        })
    }

    /** Верх названия и каждого блока записи на листе (координаты листа): [0] — название, [i + 1] — блок i. */
    private fun anchorTops(): IntArray {
        syncModel(maps = true)
        val out = IntArray(note.blocks.size + 1)
        val f = firstEdit()
        out[0] = f.top + f.totalPaddingTop
        for (v in segs) when (v) {
            is NoteEdit -> {
                val map = paraBlock[v] ?: continue
                val l = v.layout ?: continue
                val st = v.starts()
                for (i in map.indices) if (map[i] >= 0 && i < st.size) out[map[i] + 1] = v.top + v.totalPaddingTop + l.getLineTop(l.getLineForOffset(st[i]))
            }
            else -> blockOfView(v)?.let { b ->
                val bi = note.blocks.indexOfFirst { it === b }
                if (bi >= 0) out[bi + 1] = v.top
            }
        }
        return out
    }

    // ---------------------------------------------------------------- отмена и сохранение

    private fun copyBlock(b: Block): Block = when (b) {
        is Block.Para -> Block.Para(b.text, b.style, b.indent, Rich.copy(b.spans))
        is Block.Task -> Block.Task(b.text, b.done, b.indent, Rich.copy(b.spans))
        is Block.Image -> b // картинку не меняют на месте, кроме размера и места — их хранит снимок ниже
    }

    private class ImageLook(val wf: Float, val xa: Float)
    private val imageLooks = java.util.IdentityHashMap<Snap, Map<Block.Image, ImageLook>>()

    private fun snap(): Snap {
        syncModel()
        val s = Snap(note.title, note.blocks.map { copyBlock(it) }, ArrayList(note.drawings), note.caret, note.caretOffset, titleCaret)
        val looks = java.util.IdentityHashMap<Block.Image, ImageLook>()
        for (b in note.blocks) if (b is Block.Image) looks[b] = ImageLook(b.wf, b.xa)
        if (looks.isNotEmpty()) imageLooks[s] = looks
        return s
    }

    private fun restore(x: Snap) {
        imageLooks.remove(x)?.forEach { (b, l) -> b.wf = l.wf; b.xa = l.xa }
        val n = Note(note.id, x.title, note.created, note.updated, x.blocks.mapTo(ArrayList()) { copyBlock(it) }, ArrayList(x.drawings), x.caret)
            .apply { caretOffset = x.caretOffset; folder = note.folder; pinned = note.pinned; paper = note.paper; layoutV = note.layoutV }
        note = n
        render(x.caret, x.caretOffset, x.titleCaret)
    }

    private fun pushUndo() {
        undoStack.addLast(snap())
        while (undoStack.size > 60) imageLooks.remove(undoStack.removeFirst())
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
            ink.invalidate()
            return
        }
        if (commitQueue.isNotEmpty() || undoStack.isEmpty()) return
        redoStack.addLast(snap())
        restore(undoStack.removeLast())
        burstEdit = null
        changed()
    }

    private fun redo() {
        if (redoStack.isEmpty() || commitQueue.isNotEmpty() || current != null) return
        undoStack.addLast(snap())
        restore(redoStack.removeLast())
        burstEdit = null
        changed()
    }

    /** Сохранить скоро, не трогая время правки (закрепили, перенесли раскладку — это не правка текста). */
    private fun saveSoon() {
        handler.removeCallbacks(saveRunnable)
        handler.postDelayed(saveRunnable, 400)
    }

    private fun changed() {
        note.updated = System.currentTimeMillis() // список заметок (0.28) — по времени правки
        handler.removeCallbacks(saveRunnable)
        handler.postDelayed(saveRunnable, 400)
        refreshFormat()
    }

    /**
     * Сохранить запись. Текст файла собирается здесь, пишется в фоне. [wait] — дождаться записи (уход с экрана). Один фоновый
     * поток — файлы пишутся по порядку.
     */
    private fun saveNow(wait: Boolean = false) {
        handler.removeCallbacks(saveRunnable)
        if (deleted) return
        val text = try { syncModel(); store.prepare(note) } catch (t: Throwable) { saveFailed(t); return }
        val id = note.id
        val job = try { io.submit { try { store.write(id, text) } catch (t: Throwable) { handler.post { saveFailed(t) } } } } catch (t: Throwable) { null }
        if (job == null) { try { store.write(id, text) } catch (t: Throwable) { saveFailed(t) }; return }
        if (wait) try { job.get() } catch (_: Throwable) {}
    }

    private fun saveFailed(t: Throwable) {
        Toast.makeText(this, "Не удалось сохранить: ${t.message}", Toast.LENGTH_LONG).show()
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

    /** Перо коснулось листа, пока печатают: клавиатура уходит, написанное станет текстом у курсора. */
    override fun penWhileTyping(): Boolean {
        if (!stylusKnown) { stylusKnown = true; Input.markStylus(this) }
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

    /** Пальцем пишут: включили в «⋯» или пера у устройства нет (телефон: «Перо» — рисовать и писать пальцем, как на айфоне). */
    override fun fingerWrites(): Boolean = fingerWrites || (!stylusKnown && tool != Tool.HAND && tool != Tool.KEYS)

    private var stylusKnown = false
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
        if (!stylusKnown && stroke.toolType == MotionEvent.TOOL_TYPE_STYLUS) { stylusKnown = true; Input.markStylus(this) }
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
            burstEdit = null
            val r = shapeTool.onStroke(
                stroke, style, note.drawings, Ui.dpf(this, 24f), Ui.dpf(this, 4f), SystemClock.uptimeMillis(),
            )
            note.drawings.removeAll { d -> r.remove.any { it === d } }
            note.drawings += r.add
            changed()
        } else {
            pushUndo()
            burstEdit = null
            note.drawings += NoteInkView.toDrawing(stroke, style)
            changed()
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
        if (!eraseUndoPushed) { pushUndo(); burstEdit = null; eraseUndoPushed = true } // одно движение ластиком — один шаг «Отменить»
        note.drawings.clear()
        note.drawings.addAll(keep)
        ink.eraseHole(x, y, r + widest[0] + 1f) // стёртое у маркера шире круга ластика
        changed()
        ink.invalidate()
    }

    override fun drawings(): List<Drawing> = note.drawings

    override fun pendingStrokes(): List<InkStroke> {
        val out = ArrayList<InkStroke>()
        for (g in commitQueue) out += g.strokes
        current?.let { out += it.strokes }
        return out
    }

    /** Серое слово у курсора: распознанное, ещё не ставшее текстом. */
    override fun interimMark(): NoteInkView.Mark? {
        if (interim.isEmpty() || segs.isEmpty()) return null
        val e = caretEdit()
        val l = e.layout ?: return null
        val off = min(e.selectionStart, e.selectionEnd).coerceIn(0, e.text.length)
        val line = l.getLineForOffset(off)
        val (before, after, placed) = caretContext(e)
        val shown = spacedInsert(before, after, interim, placed)
        return NoteInkView.Mark(
            e.left + e.totalPaddingLeft + l.getPrimaryHorizontal(off),
            (e.top + e.totalPaddingTop + l.getLineBaseline(line)).toFloat(),
            shown, e.textPxAt(e.paraAt(off)),
        )
    }

    private fun tapOnCheckbox(x: Float, y: Float): Boolean {
        for (v in segs) {
            if (v !is NoteEdit || y < v.top || y > v.bottom) continue
            val i = v.checkAt(x - v.left, y - v.top)
            if (i >= 0) { onToggleDone(v, i); return true }
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
     * Перо оторвали и начали следующее слово (0.27.7): прошлое слово сразу становится текстом. Новое слово: штрих начат правее
     * надписи на промежуток ≥ 0,45 высоты букв (между буквами — меньше), или строкой ниже, или заметно левее/выше. Точки над
     * «ё», «й» и перекладины — над словом или внутри него, слово не рвут.
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

    /** Текст перед курсором (до 20 знаков) — подсказка распознаванию. */
    private fun preContext(): String {
        if (segs.isEmpty()) return ""
        val e = caretEdit()
        val a = min(e.selectionStart, e.selectionEnd).coerceIn(0, e.text.length)
        return e.text.subSequence(max(0, a - 40), a).toString().replace(NoteEdit.ZWS, "").replace('\n', ' ').takeLast(20)
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
            if (g === current) { interim = text.orEmpty(); ink.refreshMarks() }
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
                ink.refreshMarks()
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
            burstEdit = null
            note.drawings += ShapeTool.drawingOf(shape, NoteInkView.StrokeStyle("pen", Ui.PEN_INK, Ui.dpf(this, 2.4f)))
            changed()
            return
        }
        if (best.isEmpty()) {
            pushUndo()
            burstEdit = null
            for (s in g.strokes) note.drawings += NoteInkView.toDrawing(s, style)
            hint(g.error?.let { "Не распознано (${HandwritingEngine.shortError(it)}) — оставил рисунком" }
                ?: "Не разобрал надпись — оставил её рисунком")
            changed()
            return
        }
        val bb = NoteInkView.bounds(g.strokes)
        wordHeights += bb[3] - bb[1]
        while (wordHeights.size > 15) wordHeights.removeAt(0)
        ink.fly(g.strokes, style, flyTarget(best))
        insertAtCaret(best)
    }

    /** Куда улетают буквы (0.27.8): место слова у курсора, координаты листа. null — поля с курсором нет. */
    private fun flyTarget(word: String): RectF? {
        if (segs.isEmpty()) return null
        val e = caretEdit()
        val l = e.layout ?: return null
        val off = min(e.selectionStart, e.selectionEnd).coerceIn(0, e.text.length)
        val line = l.getLineForOffset(off)
        val paint = TextPaint(e.paint).apply { textSize = e.textPxAt(e.paraAt(off)) }
        val x0 = e.left + e.totalPaddingLeft + l.getPrimaryHorizontal(off)
        val x1 = x0 + paint.measureText(" $word")
        val base = (e.top + e.totalPaddingTop + l.getLineBaseline(line)).toFloat()
        val fm = paint.fontMetrics
        return RectF(x0, base + fm.ascent * 0.8f, x1, base + fm.descent * 0.6f)
    }

    /** Выполнить действие после того, как недописанные слова станут текстом (чтобы не сбить порядок). */
    private fun runAfterCommit(action: () -> Unit) {
        current?.let { commit(it) }
        if (commitQueue.isEmpty() && current == null) action() else afterCommit.addLast(action)
    }

    /** В заметке ничего нет: ни названия, ни текста, ни картинок, ни рисунков. */
    internal fun isEmptyNote(): Boolean {
        syncModel()
        return note.title.isBlank() && note.drawings.isEmpty() &&
            note.blocks.all { (it is TextLine && it.text.isBlank()) } && current == null
    }

    /** Для проверок: запись по листу сейчас. */
    internal fun model(): Note { syncModel(); return note }

    /** Для проверок: поля текста листа по порядку и поле с курсором. */
    internal fun editors(): List<NoteEdit> = edits
    internal fun caretEditor(): NoteEdit = caretEdit()

    companion object {
        const val EXTRA_ID = "note_id"
        /** Открыть сразу с клавиатурой (новая заметка). */
        const val EXTRA_KEYS = "note_keys"
        /** Отступ на один шаг (dp). */
        const val INDENT_DP = 28f
        /** Маркеры «Формата»: жёлтый, зелёный, голубой, розовый, оранжевый, фиолетовый. */
        val HIGHLIGHTS = intArrayOf(
            Color.argb(140, 255, 214, 10), Color.argb(107, 52, 199, 89), Color.argb(115, 90, 200, 250),
            Color.argb(97, 255, 105, 180), Color.argb(107, 255, 149, 0), Color.argb(97, 175, 82, 222),
        )
        /** Цвета текста: красный, синий, зелёный, оранжевый, фиолетовый. */
        val TEXT_COLORS = intArrayOf(
            Color.rgb(224, 53, 43), Color.rgb(47, 124, 246), Color.rgb(46, 170, 74), Color.rgb(232, 131, 12), Color.rgb(155, 89, 214),
        )
        /** Цвет текста с панели (как маркер на айфоне). */
        val PEN_COLOR_NAMES = listOf("Лиловый", "Розовый", "Оранжевый", "Мятный", "Синий")
        val PEN_COLORS = intArrayOf(
            Color.rgb(155, 89, 214), Color.rgb(224, 69, 123), Color.rgb(232, 131, 12), Color.rgb(24, 160, 146), Color.rgb(47, 124, 246),
        )
        private const val REQ_IMAGE = 41
        private const val REQ_CAMERA = 42

        /** Для проверок: распознавание выделенного без ML Kit (в Robolectric его нет). */
        internal var testRecognizer: ((List<InkStroke>) -> String?)? = null
    }
}
