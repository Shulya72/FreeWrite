package party.shulya.freewrite

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Экран «Доска»: бесконечное поле. Пером — писать (надпись становится текстом на том же месте),
 * рисовать, стирать, ставить метки статуса. Пальцем — двигать и масштабировать, касание метки
 * переключает статус, долгое нажатие на картинку — удалить.
 * Касание пальцем по надписи, штриху, стрелке или картинке — выделить; «Выделить» — обвести лассо.
 * Выделенное: тянуть — двигать, уголки и два пальца — размер; панель «Изменить / Копия / Цвет / Удалить».
 */
class BoardActivity : Activity(), BoardView.Host {

    enum class Tool { HAND, WRITE, TEXT, PEN, MARKER, SHAPE, FILL, ERASER, SELECT, STATUS, ROW, TABLE, CALLOUT, FRAME, PIPETTE }

    private lateinit var store: BoardStore
    private lateinit var noteStore: NoteStore
    internal lateinit var board: Board
    private lateinit var engine: HandwritingEngine
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var pdf: PdfInsert

    private lateinit var root: LinearLayout
    internal lateinit var view: BoardView
    private lateinit var titleView: TextView
    private lateinit var zoomLabel: TextView
    private lateinit var hintView: TextView
    private lateinit var banner: LinearLayout
    private lateinit var bannerText: TextView
    private lateinit var bannerButton: TextView
    private lateinit var selBar: LinearLayout
    /** Панель режима: обрезка картинки или заливка по точкам (вверху посередине). */
    private lateinit var modeBar: LinearLayout
    /** Легенда статусов: числа и подсветка одного статуса. */
    private lateinit var legend: LinearLayout
    /** «Заливка» по точкам: поставленные точки x, y, x, y… */
    private val fillPts = ArrayList<Float>()
    private val toolButtons = HashMap<Tool, Ui.ToolButton>()
    private val quickColors = ArrayList<View>()
    private var toolBeforePipette = Tool.PEN
    private var pipetteForSelection = false
    private var pipetteSnap: ColorKit.Snapshot? = null

    private var tool = Tool.PEN
    private var penColor = Ui.INK
    private var fingerWrites = false
    /** На этом устройстве уже касались пером (планшет с пером). Нет — палец работает инструментом. */
    private var stylus = false
    private var fingerBox: CheckBox? = null
    private var bottomTools: LinearLayout? = null
    private lateinit var images: BoardImages
    private val saver = Executors.newSingleThreadExecutor()
    private val undoStack = ArrayDeque<BoardSnapshot>()
    private val redoStack = ArrayDeque<BoardSnapshot>()
    private var showFrameMs = false
    private val frameTicker = object : Runnable {
        override fun run() {
            updateZoom()
            if (showFrameMs) handler.postDelayed(this, 500)
        }
    }
    private var pauseMs = 1000L
    private val shapeTool = ShapeTool()
    /** Сколько раз в эту доску вливали файлы к моменту загрузки — чтобы заметить новый файл коллеги. */
    private var loadedImports = 0
    /** Синхронизация с сервером (null — в этой сборке сервер не настроен). */
    private var remote: RemoteSync? = null
    private lateinit var subtitleView: TextView
    private var syncState: RemoteSync.State? = null

    // ---------------------------------------------------------------- письмо в текст на месте

    private inner class Group {
        val strokes = ArrayList<InkStroke>()
        var candidates: List<Candidate>? = null
        var resultFor = -1
        var committing = false
        var error: String? = null
    }

    private var current: Group? = null
    private val committing = ArrayList<Group>()
    private val interimRunnable = Runnable { current?.let { recognize(it) } }
    private val commitRunnable = Runnable { current?.let { commit(it) } }
    private val saveRunnable = Runnable { saveNow() }
    private val bannerTicker = object : Runnable {
        override fun run() {
            updateBanner()
            if (engine.language("ru").state == HandwritingEngine.State.DOWNLOADING) handler.postDelayed(this, 1000)
        }
    }

    private fun dp(v: Float) = Ui.dp(this, v)

    // ---------------------------------------------------------------- жизненный цикл

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = BoardStore(this)
        noteStore = NoteStore(this)
        stylus = Input.stylusSeen(this)
        val id = intent.getStringExtra(EXTRA_ID)
        board = id?.let { store.load(it) } ?: Board.create("Доска").also { store.save(it) }
        loadedImports = BoardStore.importCount(board.id)
        engine = HandwritingEngine { android.util.Log.i("FreeWrite", it) }
        engine.onChanged = { updateBanner() }
        buildUi()
        setContentView(root)
        RemoteSync.transport()?.let { remote = RemoteSync(this, it, remoteHost) }
        pdf = PdfInsert(this, { hint(it, 3500) }) { blocks, asked ->
            // Лист для плана таблицы (0.23) — под таблицу, иначе — на доску.
            if (planUiMade && planUi.target != null) planUi.onSheets(blocks) else placeImages(blocks, asked, sheets = true)
        }
        updateSubtitle()
        engine.start(this)
        // Без пера (телефон) начинаем с «Руки»: пальцем двигать и выбирать. С пером — с «Ручки».
        setTool(if (stylus) Tool.PEN else Tool.HAND, quiet = true)
        updateBanner()
        updateZoom()
        if ((board.isEmpty && board.camX == 0f && board.camY == 0f) || intent.getBooleanExtra(EXTRA_FIT, false)) view.post { view.fitAll() }
        // Прислали PDF или картинки через «Поделиться → FreeWrite» и выбрали эту доску.
        intent.getStringArrayListExtra(EXTRA_INSERT)?.let { paths -> view.post { insertFiles(paths) } }
        if (intent.getBooleanExtra(EXTRA_SHOW_SPEED, false)) {
            showFrameMs = true
            view.post { view.fitAll(); handler.post(frameTicker) }
        }
    }

    override fun onPause() {
        current?.let { commit(it) }
        saveNow(sync = true)
        remote?.flush()
        remote?.stop()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (BoardStore.importCount(board.id) != loadedImports) reloadAfterImport()
        remote?.start()
    }

    /** Пока экран был свёрнут, в доску влили файл коллеги — перечитать (камера остаётся своя). */
    private fun reloadAfterImport() {
        val b = store.load(board.id) ?: return
        loadedImports = BoardStore.importCount(b.id)
        handler.removeCallbacks(saveRunnable)
        b.camX = board.camX; b.camY = board.camY; b.camZ = board.camZ
        board = b
        undoStack.clear(); redoStack.clear()
        titleView.text = b.title
        view.clearSelection()
        view.invalidate()
        hint("Доска обновлена правками из файла")
        remote?.kick(500)
    }

    // ---------------------------------------------------------------- общая доска на сервере

    private val remoteHost = object : RemoteSync.Host {
        override fun board(): Board = board
        override fun canApply(): Boolean = current == null && committing.isEmpty() && view.isIdle && view.cropping == null
        override fun saveBoard() = saveNow()
        override fun onRemoteApplied(changed: Int, authors: Map<String, Int>) {
            // Отмена после чужих правок вернула бы их назад у всех — поэтому её история сбрасывается.
            undoStack.clear(); redoStack.clear()
            titleView.text = board.title
            view.pruneSelection()
            checkCrop()
            if (legend.visibility == View.VISIBLE) buildLegend()
            view.invalidate()
            val who = authors.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key} — ${it.value}" }
            hint(if (who.isNotEmpty()) "Правки: $who" else "Пришли правки коллег: $changed", 3500)
            if (view.selection.isNotEmpty()) buildSelectionBar()
        }
        override fun onImagesArrived() { view.invalidate() }
        override fun onSyncState(state: RemoteSync.State) {
            syncState = state
            updateSubtitle()
        }
    }

    private fun updateSubtitle() {
        val st = syncState
        if (!board.sync.isShared) {
            subtitleView.text = "доска · бесконечное поле"
            subtitleView.setTextColor(Ui.MUTED)
            return
        }
        val code = board.sync.remoteInvite.takeIf { it.isNotEmpty() }?.let { " · код " + Remote.prettyInvite(it) } ?: ""
        subtitleView.text = "общая доска$code · " + (st?.text ?: "подключаюсь…")
        subtitleView.setTextColor(if (st?.error == true) Ui.BAD else if (st?.busy == true) Ui.WARN else Ui.OK)
    }

    /** «Совместно»: сделать доску общей или показать код для коллег. */
    private fun cloudDialog() {
        val r = remote
        if (r == null) {
            AlertDialog.Builder(this).setTitle("Совместная работа")
                .setMessage("В этой сборке FreeWrite нет адреса сервера. Обновите приложение.")
                .setPositiveButton("Понятно", null).show()
            return
        }
        if (r.publishing) {
            hint("Доска выкладывается на сервер — подождите")
            return
        }
        if (!board.sync.isShared) {
            AlertDialog.Builder(this)
                .setTitle("Совместная работа")
                .setMessage("Доска будет храниться на сервере FreeWrite (Яндекс Облако). Коллеги подключаются " +
                    "по коду и видят правки друг друга через несколько секунд — пока доска открыта и есть интернет. " +
                    "Картинки тоже уйдут на сервер.")
                .setPositiveButton("Сделать общей") { _, _ -> Profile.require(this) {
                    val wait = AlertDialog.Builder(this)
                        .setTitle("Выкладываю доску на сервер…")
                        .setMessage("Обычно несколько секунд. Сейчас появится код для коллег.")
                        .setCancelable(false)
                        .show()
                    r.publish { invite, error ->
                        wait.dismiss()
                        updateSubtitle()
                        if (invite != null) showInvite(invite, first = true)
                        else AlertDialog.Builder(this).setTitle("Не получилось").setMessage(error)
                            .setPositiveButton("Понятно", null).show()
                    }
                } }
                .setNegativeButton("Отмена", null)
                .show()
            return
        }
        showInvite(board.sync.remoteInvite, first = false)
    }

    private fun showInvite(invite: String, first: Boolean) {
        val code = Remote.prettyInvite(invite)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24f), dp(8f), dp(24f), 0)
        }
        box.addView(Ui.text(this, if (first) "Доска теперь общая. Код для коллег:" else "Код для коллег:", 15f, Ui.INK2))
        box.addView(Ui.text(this, code, 30f, Ui.ACCENT, bold = true).apply {
            tag = "invite_code"
            setTextIsSelectable(true)
            setPadding(0, dp(6f), 0, dp(6f))
        })
        box.addView(Ui.text(this, "Коллега: Ежедневник → «Подключиться по коду» → ввести код.", 14f, Ui.INK2))
        syncState?.let { box.addView(Ui.text(this, "Состояние: " + it.text, 13f, if (it.error) Ui.BAD else Ui.MUTED).apply { setPadding(0, dp(8f), 0, 0) }) }
        AlertDialog.Builder(this)
            .setTitle("Общая доска «${board.title}»")
            .setView(box)
            .setPositiveButton("Поделиться кодом") { _, _ ->
                val text = "Подключись к доске «${board.title}» в FreeWrite: Ежедневник → «Подключиться по коду» → $code"
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text)
                }, "Отправить код"))
            }
            .setNeutralButton("Обновить сейчас") { _, _ -> remote?.syncNow() }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    /** «Поделиться»: доска файлом в Telegram, WhatsApp, почту — коллега откроет его в FreeWrite. */
    private fun shareBoard() {
        current?.let { commit(it) }
        BoardShare.offer(this, board) { saveNow(sync = true) }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        engine.close()
        remote?.close()
        pdf.close()
        if (planUiMade) planUi.close()
        io.shutdown()
        saver.shutdown() // уже начатые сохранения дописываются
        images.close()
        view.release()
        super.onDestroy()
    }

    @Deprecated("Activity.onBackPressed")
    override fun onBackPressed() {
        current?.let { commit(it) }
        val finishNow = Runnable { saveNow(); finish() }
        if (committing.isEmpty()) finishNow.run() else handler.postDelayed(finishNow, 1500)
    }

    // ---------------------------------------------------------------- интерфейс

    private fun buildUi() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.DESK) }
        val phone = Input.isPhone(this)
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(Ui.ToolButton(this, R.drawable.ic_back, "Назад").apply {
            minimumWidth = dp(44f)
            setOnClickListener { onBackPressed() }
        })
        titleView = Ui.text(this, board.title, 16f, Ui.INK, bold = true).apply {
            maxWidth = dp(if (phone) 400f else 200f); isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(4f), 0, dp(10f), 0)
        }
        val titleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(titleView)
            subtitleView = Ui.mono(this@BoardActivity, "доска · бесконечное поле", 11f).apply {
                setPadding(dp(4f), 0, dp(10f), 0)
                tag = "board_subtitle"
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
                // У общей доски — код для коллег; нажать — окно с кодом и «Поделиться кодом».
                setOnClickListener { if (board.sync.isShared) showInvite(board.sync.remoteInvite, first = false) else rename() }
            }
            addView(subtitleView)
            setOnClickListener { rename() }
        }
        if (phone) top.addView(titleBox, LinearLayout.LayoutParams(0, Ui.WRAP, 1f)) else top.addView(titleBox)
        val undoButton = Ui.ToolButton(this, R.drawable.ic_undo, "Отменить").apply { tag = "board_undo"; setOnClickListener { undo() } }
        val redoButton = Ui.ToolButton(this, R.drawable.ic_redo, "Вернуть").apply { setOnClickListener { redo() } }
        val cloudButton = Ui.ToolButton(this, R.drawable.ic_cloud, "Совместно").apply {
            tag = "board_cloud"
            setOnClickListener { cloudDialog() }
        }

        val tools = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        // Без пера главное — двигать, метки, выноски, надписи с клавиатуры; с пером — письмо и рисование.
        val order = if (stylus) listOf(Tool.HAND, Tool.WRITE, Tool.PEN, Tool.MARKER, Tool.SHAPE, Tool.TABLE, Tool.STATUS,
            Tool.CALLOUT, Tool.FILL, Tool.ERASER, Tool.SELECT, Tool.TEXT, Tool.FRAME)
        else listOf(Tool.HAND, Tool.TABLE, Tool.STATUS, Tool.CALLOUT, Tool.TEXT, Tool.PEN, Tool.MARKER, Tool.SHAPE,
            Tool.FILL, Tool.ERASER, Tool.SELECT, Tool.FRAME, Tool.WRITE)
        for (t in order) when (t) {
            Tool.HAND -> addTool(tools, t, R.drawable.ic_hand, "Рука")
            Tool.TEXT -> addTool(tools, t, R.drawable.ic_text, "Текст")
            Tool.WRITE -> addTool(tools, t, R.drawable.ic_write, "В текст")
            Tool.PEN -> addTool(tools, t, R.drawable.ic_pen, "Ручка")
            Tool.MARKER -> addTool(tools, t, R.drawable.ic_marker, "Маркер")
            Tool.SHAPE -> addTool(tools, t, R.drawable.ic_shapes, "Фигуры")
            Tool.STATUS -> addTool(tools, t, R.drawable.ic_status, "Метка")
            Tool.ROW -> addTool(tools, t, R.drawable.ic_check, "Строка ИД")
            Tool.TABLE -> addTool(tools, t, R.drawable.ic_table, "Таблица ИД")
            Tool.CALLOUT -> addTool(tools, t, R.drawable.ic_callout, "Выноска")
            Tool.FILL -> addTool(tools, t, R.drawable.ic_fill, "Заливка")
            Tool.ERASER -> addTool(tools, t, R.drawable.ic_eraser, "Ластик")
            Tool.SELECT -> addTool(tools, t, R.drawable.ic_lasso, "Выделить")
            Tool.FRAME -> addTool(tools, t, R.drawable.ic_frame, "Раздел")
            Tool.PIPETTE -> {}
        }
        // Три последних цвета и «Цвет» — палитра из 24 цветов и пипетка.
        for (i in 0 until 3) {
            val v = View(this).apply {
                contentDescription = "Цвет"
                tag = "board_quick_color_$i"
                setOnClickListener { setColor(ColorKit.recent(this@BoardActivity)[i], remember = false) }
            }
            tools.addView(v, LinearLayout.LayoutParams(dp(24f), dp(24f)).apply { setMargins(dp(5f), 0, dp(5f), 0) })
            quickColors += v
        }
        tools.addView(Ui.ToolButton(this, R.drawable.ic_palette, "Цвет").apply {
            tag = "board_tool_palette"
            setOnClickListener { ColorKit.showPalette(this@BoardActivity, penColor, { setColor(it) }, { startPipette(forSelection = false) }) }
        })
        tools.addView(Ui.separator(this))
        tools.addView(Ui.ToolButton(this, R.drawable.ic_image, "Картинка").apply {
            tag = "board_tool_image"
            setOnClickListener { insertMenu() }
        })
        if (!phone) { tools.addView(undoButton); tools.addView(redoButton) }
        tools.addView(Ui.ToolButton(this, R.drawable.ic_fit, "Всё").apply { setOnClickListener { view.fitAll() } })
        tools.addView(Ui.ToolButton(this, R.drawable.ic_statuses, "Статусы").apply {
            tag = "board_statuses"
            setOnClickListener { toggleLegend() }
        })
        tools.addView(Ui.ToolButton(this, R.drawable.ic_sections, "Разделы").apply {
            tag = "board_sections"
            setOnClickListener { sectionsDialog() }
        })
        tools.addView(Ui.ToolButton(this, R.drawable.ic_vedom, "Ведомости").apply {
            tag = "board_vedoms"
            setOnClickListener { vedomUi.listDialog() }
        })
        if (!phone) tools.addView(cloudButton)
        tools.addView(Ui.ToolButton(this, R.drawable.ic_share, "Файлом").apply {
            tag = "board_share"
            setOnClickListener { shareBoard() }
        })
        // «Палец рисует» — только на устройстве с пером (без пера палец и так рисует инструментом).
        fingerBox = CheckBox(this).apply {
            text = "Палец рисует"; textSize = 13f; setTextColor(Ui.INK2)
            buttonTintList = ColorStateList.valueOf(Ui.ACCENT)
            tag = "board_finger_draws"
            visibility = if (stylus) View.VISIBLE else View.GONE
            setOnCheckedChangeListener { _, on -> fingerWrites = on }
        }
        tools.addView(fingerBox)
        if (phone) {
            top.addView(undoButton)
            top.addView(redoButton)
            top.addView(cloudButton)
            top.setBackgroundColor(Ui.PAPER)
            root.addView(top, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        } else {
            top.addView(Ui.separator(this))
            top.addView(tools)
            root.addView(Ui.toolbar(this, top), LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        }
        root.addView(View(this).apply { setBackgroundColor(Ui.RULE) }, LinearLayout.LayoutParams(Ui.MATCH, dp(1f)))
        bottomTools = if (phone) tools else null

        banner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Ui.WARN_SOFT); setPadding(dp(16f), dp(8f), dp(12f), dp(8f))
            visibility = View.GONE
        }
        bannerText = Ui.text(this, "", 14f, Ui.WARN)
        bannerButton = Ui.text(this, "Скачать", 14f, Color.WHITE, bold = true).apply {
            background = Ui.rounded(this@BoardActivity, Ui.ACCENT, null, 10f)
            setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
            setOnClickListener {
                engine.download(engine.language("ru"))
                handler.removeCallbacks(bannerTicker); handler.postDelayed(bannerTicker, 1000)
            }
        }
        banner.addView(bannerText, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        banner.addView(bannerButton)
        root.addView(banner, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))

        val area = FrameLayout(this)
        view = BoardView(this, this)
        images = BoardImages(this) { view.invalidate() }
        view.images = images
        area.addView(view, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        zoomLabel = Ui.mono(this, "100%", 12f, Ui.INK2).apply {
            background = Ui.rounded(this@BoardActivity, Ui.PAPER, Ui.RULE, 8f)
            setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
            tag = "zoom_label"
            // Подержать — показать скорость: мс на кадр и сколько объектов нарисовано.
            setOnLongClickListener {
                showFrameMs = !showFrameMs
                handler.removeCallbacks(frameTicker)
                if (showFrameMs) handler.post(frameTicker) else updateZoom()
                true
            }
        }
        area.addView(zoomLabel, FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply {
            gravity = Gravity.BOTTOM or Gravity.START; setMargins(dp(14f), 0, 0, dp(14f))
        })
        hintView = Ui.text(this, "", 13f, Ui.PAPER).apply {
            background = Ui.rounded(this@BoardActivity, Ui.INK, null, 999f)
            setPadding(dp(14f), dp(8f), dp(14f), dp(8f)); alpha = 0f; gravity = Gravity.CENTER
        }
        area.addView(hintView, FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; bottomMargin = dp(18f)
        })
        // Панель выделенного (содержимое меняется по тому, что выделено).
        selBar = LinearLayout(this).apply {
            tag = "selection_bar"
            visibility = View.GONE
        }
        // На узком экране кнопок больше, чем помещается, — панель прокручивается вбок.
        val selScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            setPadding(dp(8f), 0, dp(8f), 0)
            addView(selBar)
        }
        area.addView(selScroll, FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; topMargin = dp(10f)
        })
        modeBar = LinearLayout(this).apply { tag = "mode_bar"; visibility = View.GONE }
        val modeScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            setPadding(dp(8f), 0, dp(8f), 0)
            addView(modeBar)
        }
        area.addView(modeScroll, FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; topMargin = dp(10f)
        })
        // Легенда статусов (кнопка «Статусы»): справа внизу, над подсказками.
        legend = LinearLayout(this).apply {
            tag = "legend"
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(this@BoardActivity, Ui.PAPER, Ui.RULE, 14f)
            elevation = dp(4f).toFloat()
            setPadding(dp(12f), dp(8f), dp(8f), dp(10f))
            visibility = View.GONE
        }
        val legendScroll = object : android.widget.ScrollView(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val maxH = (resources.displayMetrics.heightPixels * 0.45f).toInt()
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST))
            }
        }.apply { addView(legend); clipToPadding = false }
        area.addView(legendScroll, FrameLayout.LayoutParams(minOf(dp(290f), resources.displayMetrics.widthPixels - dp(24f)), Ui.WRAP).apply {
            gravity = Gravity.BOTTOM or Gravity.END; setMargins(0, 0, dp(12f), dp(60f))
        })
        root.addView(area, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
        bottomTools?.let { tools ->
            // Телефон: инструменты внизу, под большим пальцем.
            root.addView(View(this).apply { setBackgroundColor(Ui.RULE) }, LinearLayout.LayoutParams(Ui.MATCH, dp(1f)))
            root.addView(Ui.toolbar(this, tools).apply { tag = "board_bottom_tools" }, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        }
        handler.postDelayed({ hint("Пальцем — двигать и масштабировать, пером — писать и рисовать", 3500) }, 300)
    }

    private fun addTool(row: LinearLayout, t: Tool, icon: Int, label: String) {
        val b = Ui.ToolButton(this, icon, label).apply { setOnClickListener { setTool(t) } }
        b.tag = "board_tool_" + t.name.lowercase()
        toolButtons[t] = b
        row.addView(b)
    }

    private fun setTool(t: Tool, quiet: Boolean = false) {
        if (t != Tool.WRITE) current?.let { commit(it) }
        if (t != Tool.WRITE) tableUi.deactivate()
        tool = t
        if (t != Tool.FILL && t != Tool.PIPETTE && fillPts.isNotEmpty()) cancelFillDraft()
        if (view.cropping != null) endCrop()
        for ((k, v) in toolButtons) v.selectedTool = k == t
        refreshColors()
        if (t != Tool.SHAPE) shapeTool.reset()
        if (quiet) return
        val two = if (stylus) "" else " Доску двигать — двумя пальцами"
        when (t) {
            Tool.HAND -> hint("Пальцем — двигать доску, касание — выбрать, касание метки — следующий статус. Два пальца — приблизить", 4000)
            Tool.TEXT -> hint("Коснитесь, где написать, — откроется клавиатура", 3000)
            Tool.STATUS -> hint("Коснитесь $penWord — метка статуса ИД. Касание метки — следующий статус, подержать — перетащить", 3500)
            Tool.ROW -> hint("Коснитесь $penWord у захватки — встанет строка ИД: Чек-лист, DWG, ИГС. Касание галочки — отметить, касание ИГС — следующий статус, подержать — перетащить", 5500)
            Tool.TABLE -> hint("Коснитесь $penWord у чертежа — встанет таблица ИД с шифром. В таблице: касание пальцем — карточка строки, " +
                (if (stylus) "пером — статус у ячейки, «В текст» — писать прямо в ячейку" else "«+ строка» внизу"), 5500)
            Tool.FILL -> hint(if (stylus) "Касайтесь пером углов захватки по очереди, касание первой точки — залить. Или обведите одним движением"
                else "Ведите пальцем к углу захватки — в лупе видно точно, отпустите — точка. Касание первой точки — залить.$two", 5000)
            Tool.CALLOUT -> hint("Проведите $penWord от места на чертеже туда, где будет надпись.$two", 4000)
            Tool.FRAME -> hint("Проведите $penWord рамку от угла до угла — получится раздел с названием.$two", 4000)
            Tool.SELECT -> hint("Обведите, что выделить. Касание — один объект. Доску двигать — двумя пальцами", 3500)
            Tool.SHAPE -> hint("Нарисуйте линию, стрелку, квадрат, круг или треугольник — станет ровной фигурой.$two", 3500)
            Tool.PEN, Tool.MARKER, Tool.WRITE, Tool.ERASER -> if (!stylus) hint("Один палец — ${if (t == Tool.ERASER) "стирать" else "рисовать"}, два пальца — двигать и приближать доску", 3000)
            else -> {}
        }
    }

    private fun colorTool(t: Tool) = t == Tool.PEN || t == Tool.MARKER || t == Tool.SHAPE || t == Tool.FILL

    private fun refreshColors() {
        val rec = ColorKit.recent(this)
        for ((i, v) in quickColors.withIndex()) {
            val c = rec[i]
            val sel = c == penColor && colorTool(tool)
            v.background = ColorKit.swatch(this, c, false)
            v.scaleX = if (sel) 1.25f else 1f; v.scaleY = v.scaleX
            v.alpha = if (colorTool(tool)) 1f else 0.55f
        }
    }

    /** Выбран цвет: ручка/маркер/фигуры рисуют им. remember — поставить первым среди последних. */
    private fun setColor(c: Int, remember: Boolean = true) {
        penColor = c
        if (remember) ColorKit.remember(this, c)
        if (!colorTool(tool)) setTool(Tool.PEN) else refreshColors()
    }

    private fun startPipette(forSelection: Boolean) {
        current?.let { commit(it) }
        pipetteForSelection = forSelection
        // Для рисования вернёмся к ручке/маркеру/фигурам — чтобы новый цвет сразу было видно.
        if (tool != Tool.PIPETTE) toolBeforePipette = if (forSelection || colorTool(tool)) tool else Tool.PEN
        pipetteSnap = null
        setTool(Tool.PIPETTE)
        hint("Пипетка: коснитесь пером или пальцем того, чей цвет взять", 3500)
    }

    internal fun hint(text: String, ms: Long = 2600) {
        hintView.text = text
        hintView.animate().cancel()
        hintView.alpha = 1f
        hintView.animate().alpha(0f).setStartDelay(ms).setDuration(300).start()
    }

    private fun updateZoom() {
        if (!::zoomLabel.isInitialized) return
        val z = "${(board.camZ * 100).roundToInt()}%"
        zoomLabel.text = if (showFrameMs && ::view.isInitialized) {
            "$z · ${"%.1f".format(view.frameMs)} мс/кадр · ${view.drawnCount} из ${view.totalCount}"
        } else z
    }

    private fun updateBanner() {
        if (!::banner.isInitialized) return
        val ru = engine.language("ru")
        val needed = tool == Tool.WRITE
        when {
            ru.state == HandwritingEngine.State.READY || !needed -> banner.visibility = View.GONE
            ru.state == HandwritingEngine.State.DOWNLOADING -> {
                banner.visibility = View.VISIBLE
                bannerText.text = "Русская модель: ${engine.stateText(ru)}"
                bannerButton.visibility = View.GONE
            }
            else -> {
                banner.visibility = View.VISIBLE
                bannerText.text = if (ru.state == HandwritingEngine.State.MISSING) {
                    "Для письма в текст нужна русская модель распознавания (около 20 МБ, один раз)."
                } else {
                    "Модель распознавания: ${engine.stateText(ru)}"
                }
                bannerButton.visibility = if (ru.model != null &&
                    (ru.state == HandwritingEngine.State.MISSING || ru.state == HandwritingEngine.State.ERROR)) View.VISIBLE else View.GONE
            }
        }
    }

    private fun rename() {
        val edit = EditText(this).apply { setText(board.title); setSelection(board.title.length); isSingleLine = true }
        val box = FrameLayout(this).apply { setPadding(dp(20f), dp(8f), dp(20f), 0); addView(edit) }
        AlertDialog.Builder(this)
            .setTitle("Название доски")
            .setView(box)
            .setPositiveButton("Готово") { _, _ ->
                val t = edit.text.toString().trim()
                if (t.isNotEmpty()) { pushUndo(); board.title = t; titleView.text = t; changed() }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ---------------------------------------------------------------- отмена и сохранение

    /**
     * Запомнить доску для «Отменить». Снимок — строки объектов; у неизменённых рисунков строки
     * общие с прошлыми снимками, поэтому 60 шагов отмены не занимают 60 копий большой доски.
     */
    internal fun pushUndo() {
        undoStack.addLast(board.snapshot())
        while (undoStack.size > 60) undoStack.removeFirst()
        redoStack.clear()
    }

    private fun restore(snap: BoardSnapshot) {
        if (view.planEdit != null) planUi.endOutline(save = false)
        val tabs = board.tables.associate { it.id to it.tab } // открытая вкладка плана — та же
        board = board.restored(snap)
        for (t in board.tables) tabs[t.id]?.let { t.tab = it }
        titleView.text = board.title
        view.clearSelection()
        checkCrop()
        changed()
    }

    private fun undo() {
        if (fillPts.isNotEmpty()) { removeFillPoint(); return }
        if (view.planEdit != null) { planUi.undoPiece(); return }
        val g = current
        if (g != null && g.strokes.isNotEmpty()) {
            g.strokes.removeAt(g.strokes.lastIndex)
            g.resultFor = -1
            handler.removeCallbacks(interimRunnable); handler.removeCallbacks(commitRunnable)
            if (g.strokes.isEmpty()) current = null else handler.postDelayed(interimRunnable, 150)
            view.invalidate()
            return
        }
        if (committing.isNotEmpty() || undoStack.isEmpty()) return
        redoStack.addLast(board.snapshot())
        restore(undoStack.removeLast())
    }

    private fun redo() {
        if (redoStack.isEmpty() || committing.isNotEmpty() || current != null) return
        undoStack.addLast(board.snapshot())
        restore(redoStack.removeLast())
    }

    internal fun changed() {
        if (::legend.isInitialized && legend.visibility == View.VISIBLE) buildLegend()
        view.pruneSelection()
        pipetteSnap = null
        view.invalidate()
        handler.removeCallbacks(saveRunnable)
        // Большая доска (тысячи штрихов) — сохраняем, когда правки утихнут, а не после каждого штриха.
        handler.postDelayed(saveRunnable, if (board.objectCount() > 3000) 1500 else 500)
        remote?.kick()
    }

    /**
     * Сохранить доску. Строка готовится здесь (быстро: рисунки берутся готовыми строками),
     * запись в файл — в фоне, чтобы не было рывка. sync — записать сразу (при уходе с экрана).
     */
    private fun saveNow(sync: Boolean = false) {
        handler.removeCallbacks(saveRunnable)
        board.updated = System.currentTimeMillis()
        val seq = BoardStore.nextSeq()
        val id = board.id
        // С отметками правок — для обмена с коллегами. Части склеиваются уже при записи, в фоне.
        val parts = board.toJsonParts(stampDevice = store.device)
        val meta = board.metaJson()
        val write = Runnable {
            try {
                store.write(id, seq, parts, meta)
            } catch (t: Throwable) {
                handler.post { Toast.makeText(this, "Не удалось сохранить: ${t.message}", Toast.LENGTH_LONG).show() }
            }
        }
        if (sync) write.run() else try { saver.execute(write) } catch (_: Throwable) { write.run() }
    }

    // ---------------------------------------------------------------- картинки

    /** «Картинка»: фото и чертежи из галереи или листы PDF. */
    private fun insertMenu() {
        current?.let { commit(it) }
        if (planUiMade) planUi.clearTarget()
        if (pdf.busy) { hint("Ещё готовлю листы PDF — подождите"); return }
        AlertDialog.Builder(this)
            .setTitle("Положить на доску")
            .setItems(arrayOf("Картинки — фото, чертежи JPG", "PDF — листы проекта")) { _, which ->
                if (which == 0) pickImage() else pickPdf()
            }
            .show()
    }

    private fun pickPdf() {
        val i = Intent(Intent.ACTION_GET_CONTENT).setType("application/pdf").addCategory(Intent.CATEGORY_OPENABLE)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent.createChooser(i, "Выберите PDF"), REQ_PDF)
        } catch (t: Throwable) {
            hint("Не нашлось приложения для выбора файла")
        }
    }

    /** Открыть выбранный PDF: копия к себе (разрешение на чужой файл временное), дальше — выбор листов. */
    private fun openPdf(uri: android.net.Uri) {
        hint("Открываю PDF…", 3000)
        io.execute {
            val r = runCatching { BoardPdf.displayName(this, uri) to BoardPdf.copyIn(this, uri, ".pdf") }
            handler.post {
                if (isDestroyed) { r.getOrNull()?.second?.delete(); return@post }
                r.onSuccess { (name, file) -> pdf.start(file, name) }
                    .onFailure { hint("Не удалось открыть файл: ${it.message ?: it.javaClass.simpleName}") }
            }
        }
    }

    /** Файлы, присланные через «Поделиться → FreeWrite» (копии в нашей папке): картинки и PDF. */
    private fun insertFiles(paths: List<String>) {
        if (planUiMade) planUi.clearTarget()
        val files = paths.map { java.io.File(it) }.filter { it.isFile && BoardPdf.isIncoming(this, it) }
        if (files.isEmpty()) return
        val (pdfs, rest) = files.partition { BoardPdf.kindOf(it) == BoardPdf.Kind.PDF }
        if (rest.isNotEmpty()) io.execute {
            val blocks = rest.mapNotNull { f -> try { BoardImages.import(this, android.net.Uri.fromFile(f)) } catch (t: Throwable) { null } }
            rest.forEach { it.delete() }
            handler.post { if (!isDestroyed) placeImages(blocks, rest.size) }
        }
        // Один PDF за раз; остальные присланные PDF — после (по одному).
        pdfs.firstOrNull()?.let { pdf.start(it, intent.getStringExtra(EXTRA_INSERT_NAME)) }
        pdfs.drop(1).forEach { it.delete() }
    }

    private fun pickImage() {
        val i = Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true) // можно выбрать сразу несколько чертежей
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent.createChooser(i, "Выберите картинки"), REQ_IMAGE)
        } catch (t: Throwable) {
            hint("Не нашлось приложения для выбора картинки")
        }
    }

    @Deprecated("Activity.onActivityResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if ((requestCode == REQ_PDF || requestCode == REQ_IMAGE) && resultCode != RESULT_OK && planUiMade) planUi.clearTarget()
        if (requestCode == REQ_PDF && resultCode == RESULT_OK) { data?.data?.let { openPdf(it) }; return }
        if (requestCode != REQ_IMAGE || resultCode != RESULT_OK || data == null) return
        val uris = ArrayList<android.net.Uri>()
        data.clipData?.let { c -> for (k in 0 until c.itemCount) c.getItemAt(k).uri?.let { uris += it } }
        if (uris.isEmpty()) data.data?.let { uris += it }
        if (uris.isEmpty()) return
        hint(if (uris.size == 1) "Добавляю картинку…" else "Добавляю картинки: ${uris.size}…", 1500)
        io.execute {
            val blocks = uris.mapNotNull { u -> try { BoardImages.import(this, u) } catch (t: Throwable) { null } }
            handler.post { if (planUiMade && planUi.target != null) planUi.onSheets(blocks) else placeImages(blocks, uris.size) }
        }
    }

    /** Выбрать картинку или PDF для плана таблицы (0.23); результат — в [PlanUi.onSheets]. */
    internal fun pickForPlan(pdf: Boolean) {
        if (pdf && this.pdf.busy) { hint("Ещё готовлю листы PDF — подождите"); planUi.clearTarget(); return }
        if (pdf) pickPdf() else pickImage()
    }

    /** Разложить новые картинки (или листы PDF) рядами (до 4 в ряд) в середине экрана и выделить их. */
    private fun placeImages(blocks: List<Triple<String, Int, Int>>, asked: Int, sheets: Boolean = false) {
        if (blocks.isEmpty()) { hint("Не удалось открыть картинку"); return }
        val z = board.camZ
        val maxW = dp(if (blocks.size == 1) 460f else 320f) / z
        val gap = dp(24f) / z
        val cols = when { blocks.size <= 1 -> 1; blocks.size <= 4 -> 2; blocks.size <= 9 -> 3; else -> 4 }
        val sizes = blocks.map { b -> val k = min(1f, maxW / b.second); b.second * k to b.third * k }
        val rows = sizes.chunked(cols)
        val rowH = rows.map { r -> r.maxOf { it.second } }
        val totalW = rows.maxOf { r -> r.sumOf { it.first.toDouble() }.toFloat() + gap * (r.size - 1) }
        val totalH = rowH.sum() + gap * (rows.size - 1)
        var y = view.wy(view.height / 2f) - totalH / 2f
        val left = view.wx(view.width / 2f) - totalW / 2f
        pushUndo()
        val added = ArrayList<Any>()
        var idx = 0
        for ((ri, r) in rows.withIndex()) {
            var x = left
            for ((w, h) in r) {
                val b = blocks[idx++]
                val img = BoardImage(b.first, x, y, w, h, b.second, b.third)
                board.images += img
                added += img
                x += w + gap
            }
            y += rowH[ri] + gap
        }
        changed()
        view.select(added)
        if (sheets) hint(if (blocks.size < asked) "Положено ${BoardPdf.sheets(blocks.size)} из $asked"
            else "Положено: ${BoardPdf.sheets(blocks.size)}. Пальцами можно приблизить — чертёж чёткий", 4000)
        else if (blocks.size < asked) hint("Добавлено ${blocks.size} из $asked — остальные не открылись")
        else if (blocks.size > 1) hint("Добавлено картинок: ${blocks.size}")
    }

    private fun confirmDeleteImage(i: Int) {
        AlertDialog.Builder(this)
            .setMessage("Удалить картинку с доски?")
            .setPositiveButton("Удалить") { _, _ ->
                if (i in board.images.indices) {
                    pushUndo()
                    board.images.removeAt(i)
                    changed()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ---------------------------------------------------------------- BoardView.Host

    override fun board(): Board = board
    override fun tool(): Tool = tool
    override fun fingerWrites(): Boolean = fingerWrites || !stylus

    override fun onPenSeen() {
        if (stylus) return
        stylus = true
        Input.markStylus(this)
        fingerBox?.visibility = View.VISIBLE
        hint("Перо — инструмент, палец — двигать доску", 3000)
    }

    override fun onStrokeCancel() {
        // Начали двигать доску двумя пальцами — недописанная надпись ждёт дальше.
        val g = current ?: return
        if (g.strokes.isEmpty()) current = null
        else { handler.postDelayed(interimRunnable, 150); handler.postDelayed(commitRunnable, pauseMs) }
        view.invalidate()
    }

    /** После метки, выноски, надписи на телефоне — снова «Рука», чтобы сразу двигать чертёж. */
    internal fun backToHand() {
        if (!stylus && tool != Tool.HAND) setTool(Tool.HAND, quiet = true)
    }

    /** «Пером» на планшете с пером, «пальцем» на телефоне — для подсказок. */
    private val penWord: String get() = if (stylus) "пером" else "пальцем"

    override fun onTextTap(x: Float, y: Float) {
        val h = view.hitAt(x, y)
        if (h?.kind == BoardView.Kind.TEXT) {
            editText(board.texts[h.index])
            backToHand()
            return
        }
        val edit = EditText(this).apply {
            hint = "Надпись"
            tag = "text_input"
            minLines = 2
            gravity = Gravity.TOP or Gravity.START
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val box = FrameLayout(this).apply { setPadding(dp(20f), dp(8f), dp(20f), 0); addView(edit) }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Надпись")
            .setView(box)
            .setPositiveButton("Готово") { _, _ ->
                val text = edit.text.toString().trim()
                if (text.isNotEmpty()) {
                    pushUndo()
                    // Размер — «в рост» текущего масштаба: на экране как обычный текст.
                    val size = dp(20f) / board.camZ
                    board.texts += BoardText(x, y + size * 0.35f, size, text, penColor)
                    changed()
                }
                backToHand()
            }
            .setNegativeButton("Отмена") { _, _ -> backToHand() }
            .create()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        edit.requestFocus()
    }

    override fun strokeStyle(): NoteInkView.StrokeStyle = when (tool) {
        Tool.MARKER -> NoteInkView.StrokeStyle("marker", penColor, Ui.dpf(this, 16f))
        Tool.WRITE -> NoteInkView.StrokeStyle("write", Ui.INK, Ui.dpf(this, 2.6f))
        Tool.SHAPE -> NoteInkView.StrokeStyle("shape", penColor, Ui.dpf(this, 2.4f))
        Tool.FILL -> NoteInkView.StrokeStyle("fill", penColor, Ui.dpf(this, 2f))
        else -> NoteInkView.StrokeStyle("pen", penColor, Ui.dpf(this, 2.4f))
    }

    override fun onStrokeStart(x: Float, y: Float) {
        if (tool != Tool.WRITE) return
        handler.removeCallbacks(interimRunnable)
        handler.removeCallbacks(commitRunnable)
        val g = current
        if (g != null && g.strokes.isNotEmpty() && isFar(g, x, y)) commit(g)
        if (current == null) current = Group()
        updateBanner()
    }

    override fun onStrokeEnd(stroke: InkStroke, style: NoteInkView.StrokeStyle, tap: Boolean) {
        // Касание пером статуса в таблице ИД — список у ячейки (а не точка на доске).
        if (tap && style.kind != "fill") stroke.points.firstOrNull()?.let { p ->
            if (vedomUi.penTap(p.x, p.y) || tableUi.penTap(p.x, p.y)) { view.invalidate(); return }
        }
        if (style.kind == "write") {
            val g = current ?: Group().also { current = it }
            g.strokes += stroke
            handler.removeCallbacks(interimRunnable); handler.removeCallbacks(commitRunnable)
            handler.postDelayed(interimRunnable, 180)
            handler.postDelayed(commitRunnable, pauseMs)
        } else if (style.kind == "fill") {
            // Касание (след меньше 14 dp) — точка угла; обвели — заливка по контуру, как раньше.
            // (Замкнутая обводка кончается там же, где началась, — поэтому смотрим на размер следа.)
            val bb = NoteInkView.bounds(listOf(stroke))
            val small = max(bb[2] - bb[0], bb[3] - bb[1]) * board.camZ < dp(14f)
            if (small) {
                val p0 = stroke.points[0]
                val p = android.graphics.PointF()
                view.snapPoint(p0.x, p0.y, p) // рядом угол заливки или картинки — точно в него
                addFillPoint(p.x, p.y)
            } else {
                cancelFillDraft()
                createFill(stroke)
            }
        } else if (style.kind == "shape") {
            pushUndo()
            val z = board.camZ
            val r = shapeTool.onStroke(
                stroke, style, board.strokes, Ui.dpf(this, 24f) / z, Ui.dpf(this, 4f) / z, android.os.SystemClock.uptimeMillis(),
            )
            board.strokes.removeAll { d -> r.remove.any { it === d } }
            board.strokes += r.add
            changed()
        } else {
            pushUndo()
            board.strokes += NoteInkView.toDrawing(stroke, style)
            changed()
        }
        view.invalidate()
    }

    private var eraseUndoPushed = false

    override fun onErase(x: Float, y: Float, first: Boolean) {
        if (first) eraseUndoPushed = false
        val r = Ui.dpf(this, 14f) / board.camZ
        var changedAny = false
        val keep = board.strokes.filterNot { d ->
            var i = 0
            var hit = false
            while (i + 1 < d.pts.size && !hit) {
                val dx = d.pts[i] - x; val dy = d.pts[i + 1] - y
                val rr = r + d.width / 2
                if (dx * dx + dy * dy < rr * rr) hit = true
                i += 3
            }
            hit
        }
        val h = view.hitAt(x, y)
        val fi = view.fillBorderAt(x, y, r)
        if (keep.size != board.strokes.size || fi >= 0 ||
            (h != null && h.kind != BoardView.Kind.IMAGE && h.kind != BoardView.Kind.TABLE && h.kind != BoardView.Kind.VEDOM)) {
            if (!eraseUndoPushed) { pushUndo(); eraseUndoPushed = true }
            if (keep.size != board.strokes.size) { board.strokes.clear(); board.strokes.addAll(keep); changedAny = true }
            when (h?.kind) {
                BoardView.Kind.TEXT -> { board.texts.removeAt(h.index); changedAny = true }
                BoardView.Kind.STICKER -> { board.stickers.removeAt(h.index); changedAny = true }
                BoardView.Kind.CALLOUT -> { board.callouts.removeAt(h.index); changedAny = true }
                BoardView.Kind.ROW -> { board.rows.removeAt(h.index); changedAny = true }
                else -> Unit
            }
            // Заливка стирается касанием её края (внутри можно стирать рисунки поверх неё).
            if (fi >= 0 && fi < board.fills.size) { board.fills.removeAt(fi); changedAny = true }
        }
        if (changedAny) changed()
    }

    /** Таблицы ИД (ТЗ «Учёт ИГС», шаг 1). */
    internal val tableUi by lazy { TableUi(this) }

    override fun onTableTap(t: BoardTable, h: TableHit) = tableUi.onTap(t, h, view.lastTapByPen)

    override fun onTableLongPress(t: BoardTable, h: TableHit) = tableUi.onLongPress(t, h)

    /** План под таблицей (0.23): листы, обводка захваток. */
    private var planUiMade = false
    internal val planUi by lazy { planUiMade = true; PlanUi(this) }

    override fun onPlanGesture(x0: Float, y0: Float, x1: Float, y1: Float, tap: Boolean) = planUi.onGesture(x0, y0, x1, y1, tap)

    override fun onEmptyTap() = tableUi.deactivate()

    /** Ведомости (ТЗ «Учёт ИГС», шаг 2). */
    internal val vedomUi by lazy { VedomUi(this) }

    override fun onVedomTap(v: BoardVedom, h: VedomHit) = vedomUi.onTap(v, h, view.lastTapByPen)

    override fun onVedomLongPress(v: BoardVedom, h: VedomHit) = vedomUi.onLongPress(v, h)

    override fun onStatusTap(x: Float, y: Float) {
        board.vedoms.lastOrNull { view.vedomDraw.bounds(board, it, android.graphics.RectF()).contains(x, y) }?.let { v ->
            val vh = view.vedomDraw.hit(board, v, x, y) ?: return
            if (vh.part == VedomHit.TITLE || vh.part == VedomHit.HEAD) view.select(listOf(v)) else vedomUi.onTap(v, vh, view.lastTapByPen)
            return
        }
        board.tables.lastOrNull { view.tableDraw.bounds(board, it, android.graphics.RectF()).contains(x, y) }?.let { t ->
            val th = view.tableDraw.hit(board, t, x, y) ?: return
            if (th.part == TableHit.TITLE || th.part == TableHit.HEAD) view.select(listOf(t)) else tableUi.onTap(t, th, view.lastTapByPen)
            return
        }
        if (tool == Tool.TABLE) {
            tableUi.create(x, y)
            return
        }
        val h = view.hitAt(x, y)
        if (h?.kind == BoardView.Kind.ROW) {
            val r = board.rows[h.index]
            onRowTap(r, view.rowPartAt(r, x))
            return
        }
        if (tool == Tool.ROW) {
            newRow(x, y)
            return
        }
        if (h?.kind == BoardView.Kind.CALLOUT) {
            val c = board.callouts[h.index]
            if (view.inStatusRow(c, x, y)) onCalloutStatusTap(c) else view.select(listOf(c))
            return
        }
        pushUndo()
        if (h?.kind == BoardView.Kind.STICKER) {
            val s = board.stickers[h.index]
            s.sid = board.nextStatus(s.sid, BoardStatus.ID)
        } else {
            // Метка ставится «в рост» текущего масштаба: на экране около 128×36 dp; статус — первый в списке ИД.
            val scale = resources.displayMetrics.density / board.camZ
            val s = BoardSticker(0f, 0f, board.firstStatus(BoardStatus.ID), scale)
            s.x = x - view.stickerW(s) / 2f; s.y = y - s.h / 2f
            board.stickers += s
        }
        changed()
        backToHand()
    }

    override fun onStickerTap(index: Int) {
        val s = board.stickers.getOrNull(index) ?: return
        pushUndo()
        s.sid = board.nextStatus(s.sid, BoardStatus.ID)
        changed()
        hint(board.status(s.sid).name, 1200)
    }

    // ---------------------------------------------------------------- строка ИД (0.18, FW-011)

    /** Касание строки ИД: галочка — отметить/снять, ИГС — следующий статус ИД, название — изменить. */
    override fun onRowTap(row: BoardRow, part: Int) {
        when (part) {
            BoardView.RowPart.TITLE -> { rowTitleDialog(row.title) { t -> pushUndo(); row.title = t; changed(); buildSelectionBar() }; return }
            BoardView.RowPart.CHECK -> { pushUndo(); row.check = !row.check; hint(if (row.check) "Чек-лист — есть" else "Чек-лист — нет", 900) }
            BoardView.RowPart.DWG -> { pushUndo(); row.dwg = !row.dwg; hint(if (row.dwg) "DWG — есть" else "DWG — нет", 900) }
            else -> { pushUndo(); row.sid = board.nextStatus(row.sid, BoardStatus.ID); hint("ИГС: " + board.status(row.sid).name, 1200) }
        }
        changed()
    }

    /** Новая строка ИД у захватки: название (предложено следующее по номеру в этом разделе), размер — «в рост» масштаба. */
    private fun newRow(x: Float, y: Float) {
        val sec = board.sectionAt(x, y)
        val last = board.rows.lastOrNull { board.sectionOf(it) === sec && it.title.isNotEmpty() }
        val suggest = last?.let { BoardRow.nextTitle(it.title) } ?: "Захватка 1 — "
        rowTitleDialog(suggest) { t ->
            pushUndo()
            val scale = resources.displayMetrics.density / board.camZ
            val r = BoardRow(x, 0f, scale, t, false, false, board.firstStatus(BoardStatus.ID))
            r.y = y - r.h / 2f
            board.rows += r
            changed()
            backToHand()
        }
    }

    /** Следующая захватка: под строкой — такая же, номер в названии +1, галочки сняты, ИГС — первый статус. */
    private fun nextRow(r: BoardRow) {
        pushUndo()
        val n = BoardRow(r.x, r.y + r.h * 1.25f, r.scale, BoardRow.nextTitle(r.title), false, false, board.firstStatus(BoardStatus.ID))
        board.rows += n
        changed()
        view.select(listOf(n))
    }

    private fun rowTitleDialog(current: String, done: (String) -> Unit) {
        val edit = EditText(this).apply {
            setText(current); setSelection(current.length); isSingleLine = true; tag = "row_title"
            hint = "Например: Захватка 1 — Опалубка"
        }
        val box = FrameLayout(this).apply { setPadding(dp(20f), dp(8f), dp(20f), 0); addView(edit) }
        val d = AlertDialog.Builder(this)
            .setTitle("Строка ИД — название")
            .setView(box)
            .setPositiveButton("Готово") { _, _ -> done(edit.text.toString().trim()) }
            .setNegativeButton("Отмена", null)
            .create()
        d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.show()
        edit.requestFocus()
    }

    override fun onCalloutStatusTap(c: BoardCallout) {
        pushUndo()
        c.sid = board.nextStatus(c.sid, BoardStatus.CON)
        changed()
        hint(board.status(c.sid).name, 1200)
    }

    override fun onStatusMenu(obj: Any) = chooseStatus(obj)

    override fun onCalloutCreate(tx: Float, ty: Float, bx: Float, by: Float, moved: Boolean) {
        calloutDialog(null) { title, sid ->
            val z = board.camZ
            val c = BoardCallout(0f, 0f, resources.displayMetrics.density / z, title, sid)
            c.addLeader(tx, ty)
            val r = view.calloutRect(c, android.graphics.RectF())
            if (moved) {
                c.x = bx - r.width() / 2f
                c.y = by - r.height() / 2f
            } else {
                // Просто касание — рамка выше и правее места.
                c.x = tx + dp(36f) / z
                c.y = ty - dp(36f) / z - r.height()
            }
            pushUndo()
            board.callouts += c
            changed()
            view.select(listOf(c))
            hint("Ещё указатель — «+ Указатель». Касание строки статуса — следующий статус", 4000)
            backToHand()
        }
    }

    // ---------------------------------------------------------------- заливка по точкам

    override fun fillDraft(): FloatArray? = if (fillPts.isEmpty()) null else fillPts.toFloatArray()

    /** Палец выбрал точку под лупой (уже с привязкой). */
    override fun onFillPoint(x: Float, y: Float) = addFillPoint(x, y)

    /** Поставить точку угла. Касание первой точки (если точек уже 3 и больше) — замкнуть и залить. */
    private fun addFillPoint(x: Float, y: Float) {
        val z = board.camZ
        if (fillPts.size >= 6 && Math.hypot((x - fillPts[0]).toDouble(), (y - fillPts[1]).toDouble()) * z < dp(24f)) {
            finishFillDraft()
            return
        }
        val n = fillPts.size
        if (n >= 2 && Math.hypot((x - fillPts[n - 2]).toDouble(), (y - fillPts[n - 1]).toDouble()) * z < dp(5f)) return
        fillPts += x; fillPts += y
        showFillBar()
        view.invalidate()
    }

    private fun removeFillPoint() {
        if (fillPts.size >= 2) { fillPts.removeAt(fillPts.lastIndex); fillPts.removeAt(fillPts.lastIndex) }
        if (fillPts.isEmpty()) hideModeBar() else showFillBar()
        view.invalidate()
    }

    private fun cancelFillDraft() {
        fillPts.clear()
        hideModeBar()
        view.invalidate()
    }

    private fun finishFillDraft() {
        if (fillPts.size < 6) { hint("Нужно хотя бы три угла"); return }
        val pts = fillPts.toFloatArray()
        fillPts.clear()
        hideModeBar()
        pushUndo()
        board.fills += BoardFill(pts, penColor)
        changed()
        hint("Залито. Следующая захватка — снова касайтесь углов" +
            if (board.tables.isNotEmpty()) ". Привязать к таблице — выделите заливку → «Захватка»" else "", 3500)
    }

    private fun showFillBar() {
        val n = fillPts.size / 2
        val buttons = arrayListOf(
            Ui.pill(this, "Убрать точку", Ui.INK, Ui.DESK).apply { tag = "fill_undo"; setOnClickListener { removeFillPoint() } },
            Ui.pill(this, "Отмена", Ui.BAD, Ui.BAD_SOFT).apply { tag = "fill_cancel"; setOnClickListener { cancelFillDraft() } },
        )
        if (n >= 3) buttons += Ui.pill(this, "Залить", Color.WHITE, Ui.ACCENT).apply { tag = "fill_done"; setOnClickListener { finishFillDraft() } }
        val label = if (n < 3) "Углы: $n — нужно хотя бы 3" else "Углы: $n"
        modeBar.removeAllViews()
        modeBar.addView(Ui.floatingBar(this, label, *buttons.toTypedArray()))
        modeBar.visibility = View.VISIBLE
    }

    /** Панель режима (обрезка, точки заливки, ввод в ячейку таблицы). */
    internal fun showModeBar(label: String, buttons: List<TextView>) {
        modeBar.removeAllViews()
        modeBar.addView(Ui.floatingBar(this, label, *buttons.toTypedArray()))
        modeBar.visibility = View.VISIBLE
    }

    /** Инструмент сейчас (для таблиц: в «В текст» касание пером ячейки — писать в неё). */
    internal val activeTool: Tool get() = tool

    internal fun hideModeBar() {
        modeBar.removeAllViews()
        modeBar.visibility = View.GONE
    }

    // ---------------------------------------------------------------- обрезка и булавка

    private fun startCrop(m: BoardImage) {
        view.clearSelection()
        view.cropping = m
        val buttons = arrayOf(
            Ui.pill(this, "Как было", Ui.INK, Ui.DESK).apply {
                tag = "crop_reset"
                setOnClickListener { if (m.cropped) { pushUndo(); m.uncrop(); changed() } }
            },
            Ui.pill(this, "Готово", Color.WHITE, Ui.ACCENT).apply { tag = "crop_done"; setOnClickListener { endCrop(select = true) } },
        )
        modeBar.removeAllViews()
        modeBar.addView(Ui.floatingBar(this, "Обрезка: тяните края и углы", *buttons))
        modeBar.visibility = View.VISIBLE
        hint("Тяните $penWord или пальцем за края и углы рамки — лишнее скроется. Скрытое не пропадает", 4000)
    }

    private fun endCrop(select: Boolean = false) {
        val m = view.cropping ?: return
        view.cropping = null
        hideModeBar()
        if (fillPts.isNotEmpty()) showFillBar()
        if (select && board.images.any { it === m }) view.select(listOf(m))
    }

    /** Картинки, которую обрезали, больше нет (отмена, правки коллег) — выйти из обрезки. */
    private fun checkCrop() {
        val m = view.cropping ?: return
        if (board.images.none { it === m }) endCrop()
    }

    private fun pinSelectedImages() {
        val imgs = view.selection.filterIsInstance<BoardImage>()
        if (imgs.isEmpty()) return
        pushUndo()
        for (m in imgs) m.locked = true
        view.clearSelection()
        changed()
        hint("Закреплено: не сдвинуть и не удалить, можно рисовать поверх. Открепить — нажмите булавку", 4500)
    }

    override fun onPinTap(img: BoardImage) {
        AlertDialog.Builder(this)
            .setTitle("Картинка закреплена")
            .setMessage("Её нельзя выделить, сдвинуть, растянуть или удалить — только рисовать и писать поверх.")
            .setPositiveButton("Открепить") { _, _ ->
                if (board.images.none { it === img }) return@setPositiveButton
                pushUndo()
                img.locked = false
                changed()
                view.select(listOf(img))
            }
            .setNegativeButton("Оставить", null)
            .show()
    }

    // ---------------------------------------------------------------- разделы

    override fun onFrameCreate(l: Float, t: Float, r: Float, b: Float) {
        if (r <= l || b <= t) { hint("Проведите рамку побольше — от угла до угла раздела"); return }
        frameTitleDialog("Раздел ${board.frames.size + 1}", "Новый раздел") { title ->
            pushUndo()
            val f = BoardFrame(l, t, r - l, b - t, title)
            board.frames += f
            changed()
            view.select(listOf(f))
            hint("Раздел «$title». Тянуть за ярлык — переносится вместе со всем внутри. «Разделы» — быстрый переход", 4500)
            backToHand()
        }
    }

    private fun frameTitleDialog(current: String, heading: String, done: (String) -> Unit) {
        val edit = EditText(this).apply {
            setText(current); setSelectAllOnFocus(true); selectAll(); isSingleLine = true; tag = "frame_title"
            hint = "Например: Секция 3, Корпус 1"
        }
        val box = FrameLayout(this).apply { setPadding(dp(20f), dp(8f), dp(20f), 0); addView(edit) }
        val d = AlertDialog.Builder(this)
            .setTitle(heading)
            .setView(box)
            .setPositiveButton("Готово") { _, _ -> done(edit.text.toString().trim().ifEmpty { current }) }
            .setNegativeButton("Отмена", null)
            .create()
        d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.show()
        edit.requestFocus()
    }

    /** «Разделы»: список разделов доски сверху вниз, касание — показать раздел целиком. */
    private fun sectionsDialog() {
        val frames = board.frames.sortedWith(compareBy({ (it.y / 200f).toInt() }, { it.x }))
        if (frames.isEmpty()) {
            AlertDialog.Builder(this).setTitle("Разделы")
                .setMessage("Разделов пока нет. Инструмент «Раздел» — проведите рамку от угла до угла и дайте название: «Секция 3», «Корпус 1». Потом здесь — быстрый переход.")
                .setPositiveButton("Понятно", null).show()
            return
        }
        val names = frames.map { f ->
            val inside = view.frameContents(f).size
            if (inside == 0) f.title else "${f.title}  ·  ${Ui.plural(inside, "предмет", "предмета", "предметов")}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Разделы — перейти")
            .setItems(names) { _, i -> val f = frames[i]; view.clearSelection(); view.fitRect(f.x, f.y, f.x + f.w, f.y + f.h) }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    // ---------------------------------------------------------------- легенда статусов

    private fun toggleLegend() {
        if (legend.visibility == View.VISIBLE) {
            legend.visibility = View.GONE
            view.highlight = null
        } else {
            buildLegend()
            legend.visibility = View.VISIBLE
        }
    }

    /** Раздел, по которому считает легенда (null — вся доска); хранится номер — раздел могут переименовать. */
    private var legendSection: String? = null

    private fun sortedFrames() = board.frames.sortedWith(compareBy({ (it.y / 200f).toInt() }, { it.x }))

    /**
     * Легенда (0.18): статусы ИД (метки и ИГС строк) и конструкций (выноски) с числами — по всей доске или по разделу;
     * у строк ИД — сколько отмечено «Чек-лист» и «DWG». Касание статуса — подсветить только его.
     */
    private fun buildLegend() {
        legend.removeAllViews()
        val hl = view.highlight?.takeIf { id -> board.statuses.any { it.id == id } }
        if (hl == null && view.highlight != null) view.highlight = null
        val fr = board.frames.firstOrNull { it.id == legendSection }
        if (fr == null) legendSection = null
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(Ui.text(this, "Статусы", 15f, Ui.INK, bold = true), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        head.addView(Ui.text(this, "Изменить", 13f, Ui.ACCENT, bold = true).apply {
            tag = "legend_edit"; setPadding(dp(8f), dp(6f), dp(8f), dp(6f)); setOnClickListener { statusesDialog() }
        })
        head.addView(Ui.text(this, "✕", 16f, Ui.INK2).apply {
            tag = "legend_close"; setPadding(dp(8f), dp(4f), dp(6f), dp(4f)); setOnClickListener { toggleLegend() }
        })
        legend.addView(head)
        // Где считать: вся доска или раздел.
        legend.addView(Ui.text(this, "Где: " + (fr?.title ?: "вся доска") + "  ▾", 14f, Ui.ACCENT, bold = true).apply {
            tag = "legend_section"
            setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
            background = Ui.rounded(this@BoardActivity, Ui.ACCENT_SOFT, null, 8f)
            setOnClickListener { chooseLegendSection() }
        }, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { bottomMargin = dp(4f) })
        legend.addView(Ui.text(this, if (hl == null) "Касание — показать только этот статус" else "Остальное притушено. Ещё касание — показать всё",
            12f, Ui.MUTED).apply { setPadding(0, 0, 0, dp(4f)) })
        var idx = 0
        for (group in listOf(BoardStatus.ID, BoardStatus.CON)) {
            legend.addView(Ui.text(this, if (group == BoardStatus.ID) "ИГС — таблицы, метки, строки" else "Конструкции — выноски", 13f, Ui.INK2, bold = true)
                .apply { setPadding(0, dp(8f), 0, dp(2f)) })
            for (st in board.statuses(group)) {
                val on = st.id == hl
                val n = board.countStatus(st.id, fr)
                val row = LinearLayout(this).apply {
                    tag = "legend_row_${idx++}"
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
                    background = if (on) Ui.rounded(this@BoardActivity, Ui.ACCENT_SOFT, Ui.ACCENT, 10f) else null
                    isClickable = true
                    setOnClickListener {
                        view.highlight = if (on) null else st.id
                        buildLegend()
                        if (!on) hint("${st.name}: $n", 1500)
                    }
                }
                row.addView(dot(st.color), LinearLayout.LayoutParams(dp(14f), dp(14f)))
                row.addView(Ui.text(this, st.name, 14f, Ui.INK).apply { setPadding(dp(10f), 0, dp(8f), 0); maxLines = 2 },
                    LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
                row.addView(Ui.text(this, n.toString(), 14f, if (on) Ui.ACCENT else Ui.INK2, bold = true))
                legend.addView(row, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
            }
            if (group == BoardStatus.ID) {
                val rows = board.rows.filter { fr == null || board.sectionOf(it) === fr }
                if (rows.isNotEmpty()) {
                    legend.addView(Ui.text(this, "Строк ИД: ${rows.size} · Чек-лист ✓ ${rows.count { it.check }} · DWG ✓ ${rows.count { it.dwg }}",
                        13f, Ui.INK2).apply { tag = "legend_checks"; setPadding(dp(8f), dp(4f), dp(8f), dp(2f)) })
                }
            }
        }
        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        if (board.tables.isNotEmpty()) actions.addView(Ui.pill(this, "Сводка ИГС", Color.WHITE, Ui.ACCENT).apply {
            tag = "legend_igs"
            setOnClickListener { tableUi.summaryDialog(fr = board.frames.firstOrNull { it.id == legendSection }) }
        }, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { marginEnd = dp(8f) })
        actions.addView(Ui.pill(this, "Сводка по разделам", Ui.INK, Ui.DESK).apply {
            tag = "legend_summary"
            setOnClickListener { summaryDialog() }
        })
        if (hl != null) {
            val n = board.countStatus(hl)
            actions.addView(Ui.pill(this, "Показать все ($n)", Color.WHITE, Ui.ACCENT).apply {
                tag = "legend_show"
                setOnClickListener { showStatus(hl) }
            }, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { marginStart = dp(8f) })
        }
        legend.addView(actions, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { topMargin = dp(8f) })
    }

    private fun chooseLegendSection() {
        val frames = sortedFrames()
        if (frames.isEmpty()) { hint("Разделов пока нет — инструмент «Раздел»: рамка с названием, например «КЖ 6.1.1»", 3500); return }
        val names = listOf("Вся доска") + frames.map { it.title }
        AlertDialog.Builder(this)
            .setTitle("Считать статусы")
            .setItems(names.toTypedArray()) { _, which ->
                legendSection = if (which == 0) null else frames[which - 1].id
                buildLegend()
            }
            .show()
    }

    /**
     * Сводка по разделам: для каждого раздела (и «вне разделов») — сколько каждого статуса ИД и конструкций,
     * сколько строк ИД и галочек. Касание раздела — показать его. «Скопировать для Excel» — таблица через табуляцию.
     */
    private fun summaryDialog() {
        val frames = sortedFrames()
        val ids = board.statuses(BoardStatus.ID)
        val cons = board.statuses(BoardStatus.CON)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16f), dp(8f), dp(16f), dp(8f)) }
        val scopes: List<BoardFrame?> = frames + listOf(null)
        var dialog: AlertDialog? = null
        fun inScope(o: Any, f: BoardFrame?) = board.sectionOf(o) === f
        for ((i, f) in scopes.withIndex()) {
            val rows = board.rows.filter { inScope(it, f) }
            val stickers = board.stickers.filter { inScope(it, f) }
            val callouts = board.callouts.filter { inScope(it, f) }
            if (f == null && rows.isEmpty() && stickers.isEmpty() && callouts.isEmpty()) continue
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                tag = "summary_section_$i"
                setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
                background = Ui.rounded(this@BoardActivity, Ui.PAPER, Ui.RULE, 10f)
                if (f != null) {
                    isClickable = true
                    setOnClickListener { dialog?.dismiss(); view.fitRect(f.x, f.y, f.x + f.w, f.y + f.h) }
                }
            }
            card.addView(Ui.text(this, f?.title ?: "Вне разделов", 16f, Ui.INK, bold = true))
            val sub = buildString {
                append("Строк ИД: ${rows.size}")
                if (rows.isNotEmpty()) append(" · Чек-лист ✓ ${rows.count { it.check }} из ${rows.size} · DWG ✓ ${rows.count { it.dwg }} из ${rows.size}")
                if (stickers.isNotEmpty()) append(" · меток: ${stickers.size}")
            }
            card.addView(Ui.text(this, sub, 13f, Ui.INK2).apply { setPadding(0, dp(2f), 0, dp(4f)) })
            val chips = Ui.FlowRow(this)
            for (st in ids) {
                val n = rows.count { it.sid == st.id } + stickers.count { it.sid == st.id }
                if (n > 0) chips.addView(Ui.chip(this, "${st.name}: $n", Board.statusFg(st.color), Board.statusBg(st.color)))
            }
            for (st in cons) {
                val n = callouts.count { it.sid == st.id }
                if (n > 0) chips.addView(Ui.chip(this, "${st.name}: $n", Board.statusFg(st.color), Board.statusBg(st.color)))
            }
            card.addView(chips)
            box.addView(card, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { bottomMargin = dp(8f) })
        }
        if (box.childCount == 0) box.addView(Ui.text(this, "Пока нет строк ИД, меток и выносок.", 14f, Ui.INK2))
        dialog = AlertDialog.Builder(this)
            .setTitle("Сводка по разделам")
            .setView(android.widget.ScrollView(this).apply { addView(box) })
            .setPositiveButton("Готово", null)
            .setNeutralButton("Скопировать для Excel") { _, _ ->
                getSystemService(android.content.ClipboardManager::class.java)
                    ?.setPrimaryClip(android.content.ClipData.newPlainText("Сводка", summaryTable()))
                hint("Сводка скопирована — вставьте в Excel", 2500)
            }
            .create()
        dialog.show()
    }

    /** Сводка таблицей через табуляцию (вставляется в Excel столбцами): раздел, статусы ИД, галочки, конструкции. */
    fun summaryTable(): String {
        val ids = board.statuses(BoardStatus.ID)
        val cons = board.statuses(BoardStatus.CON)
        val sb = StringBuilder()
        sb.append("Раздел\tСтрок ИД\tЧек-лист ✓\tDWG ✓")
        for (st in ids) sb.append('\t').append(st.name)
        for (st in cons) sb.append('\t').append(st.name)
        sb.append('\n')
        for (f in sortedFrames() + listOf(null)) {
            val rows = board.rows.filter { board.sectionOf(it) === f }
            val stickers = board.stickers.filter { board.sectionOf(it) === f }
            val callouts = board.callouts.filter { board.sectionOf(it) === f }
            if (f == null && rows.isEmpty() && stickers.isEmpty() && callouts.isEmpty()) continue
            sb.append(f?.title ?: "Вне разделов").append('\t').append(rows.size)
                .append('\t').append(rows.count { it.check }).append('\t').append(rows.count { it.dwg })
            for (st in ids) sb.append('\t').append(rows.count { it.sid == st.id } + stickers.count { it.sid == st.id })
            for (st in cons) sb.append('\t').append(callouts.count { it.sid == st.id })
            sb.append('\n')
        }
        return sb.toString()
    }

    /** Камера — на все метки и выноски статуса. */
    private fun showStatus(sid: String) {
        val r = android.graphics.RectF()
        var first = true
        fun add(o: Any) { val b = view.boundsOf(o, android.graphics.RectF()); if (first) { r.set(b); first = false } else r.union(b) }
        board.stickers.filter { it.sid == sid }.forEach { add(it) }
        board.rows.filter { it.sid == sid }.forEach { add(it) }
        board.callouts.filter { it.sid == sid }.forEach { c ->
            add(c)
            var i = 0
            while (i + 1 < c.leaders.size) { r.union(c.leaders[i], c.leaders[i + 1]); i += 2 }
        }
        if (first) { hint("С этим статусом пока ничего нет"); return }
        view.fitRect(r.left, r.top, r.right, r.bottom)
    }

    override fun onDragStart() { pushUndo() }
    override fun onDragEnd() { changed() }
    override fun onDragCancel() {
        if (undoStack.isNotEmpty()) undoStack.removeLast()
        view.invalidate()
    }
    override fun onImageLongPress(index: Int) { confirmDeleteImage(index) }

    override fun onSelectionChanged() {
        if (::selBar.isInitialized) buildSelectionBar()
    }

    // ---------------------------------------------------------------- выделенное: панель и действия

    internal fun buildSelectionBar() {
        val sel = view.selection
        selBar.removeAllViews()
        tableUi.markFromFill(sel)
        if (sel.isEmpty()) {
            selBar.visibility = View.GONE
            return
        }
        val label = tableUi.selectionLabel(sel) ?: vedomUi.selectionLabel(sel) ?: when {
            sel.size > 1 -> "Выделено: ${sel.size}"
            sel[0] is BoardText -> "Надпись"
            sel[0] is BoardImage -> "Картинка"
            sel[0] is BoardSticker -> "Метка"
            sel[0] is BoardRow -> "Строка ИД"
            sel[0] is BoardCallout -> "Выноска"
            sel[0] is BoardFill -> "Заливка"
            sel[0] is BoardFrame -> "Раздел «${(sel[0] as BoardFrame).title}»"
            else -> "Рисунок"
        }
        // Раздел, где стоит метка, строка ИД или выноска; на общей доске — кто и когда правил.
        val sec = if (sel.size == 1 && (sel[0] is BoardSticker || sel[0] is BoardRow || sel[0] is BoardCallout))
            board.sectionOf(sel[0])?.let { " · ${it.title}" } ?: "" else ""
        val label1 = label + sec
        val label2 = if (sel.size == 1 && board.sync.isShared) whoEdited(sel[0])?.let { "$label1 · $it" } ?: label1 else label1
        val buttons = ArrayList<TextView>()
        buttons += tableUi.selectionButtons(sel)
        buttons += vedomUi.selectionButtons(sel)
        if (sel.size == 1 && sel[0] is BoardText) {
            buttons += Ui.pill(this, "Изменить", Ui.INK, Ui.DESK).apply { tag = "sel_edit"; setOnClickListener { editText(sel[0] as BoardText) } }
        }
        if (sel.count { it is BoardText } >= 2) {
            buttons += Ui.pill(this, "Объединить", Ui.INK, Ui.DESK).apply { tag = "sel_merge"; setOnClickListener { mergeSelectedTexts() } }
        }
        if (sel.size == 1 && sel[0] is BoardCallout) {
            val c = sel[0] as BoardCallout
            buttons += Ui.pill(this, "Изменить", Ui.INK, Ui.DESK).apply {
                tag = "sel_edit"
                setOnClickListener {
                    calloutDialog(c) { title, sid ->
                        pushUndo(); c.title = title; c.sid = sid; changed(); buildSelectionBar()
                    }
                }
            }
            buttons += Ui.pill(this, "+ Указатель", Ui.INK, Ui.DESK).apply {
                tag = "sel_leader"
                setOnClickListener {
                    setTool(Tool.CALLOUT, quiet = true)
                    hint("Проведите $penWord от выноски к месту на чертеже", 3500)
                }
            }
        }
        if (sel.size == 1 && sel[0] is BoardSticker) {
            buttons += Ui.pill(this, "Статус", Ui.INK, Ui.DESK).apply { tag = "sel_status"; setOnClickListener { chooseStatus(sel[0]) } }
        }
        if (sel.size == 1 && sel[0] is BoardRow) {
            val r = sel[0] as BoardRow
            buttons += Ui.pill(this, "Название", Ui.INK, Ui.DESK).apply {
                tag = "sel_edit"
                setOnClickListener { rowTitleDialog(r.title) { t -> pushUndo(); r.title = t; changed(); buildSelectionBar() } }
            }
            buttons += Ui.pill(this, "ИГС", Ui.INK, Ui.DESK).apply { tag = "sel_status"; setOnClickListener { chooseStatus(r) } }
            buttons += Ui.pill(this, "+ Следующая", Ui.INK, Ui.DESK).apply { tag = "sel_next"; setOnClickListener { nextRow(r) } }
        }
        if (sel.size == 1 && sel[0] is BoardFrame) {
            val f = sel[0] as BoardFrame
            buttons += Ui.pill(this, "Название", Ui.INK, Ui.DESK).apply {
                tag = "sel_edit"
                setOnClickListener { frameTitleDialog(f.title, "Название раздела") { t -> pushUndo(); f.title = t; changed(); buildSelectionBar() } }
            }
        }
        if (sel.size == 1 && sel[0] is BoardImage) {
            buttons += Ui.pill(this, "Обрезать", Ui.INK, Ui.DESK).apply { tag = "sel_crop"; setOnClickListener { startCrop(sel[0] as BoardImage) } }
        }
        if (sel.any { it is BoardImage }) {
            buttons += Ui.pill(this, "Закрепить", Ui.INK, Ui.DESK).apply { tag = "sel_pin"; setOnClickListener { pinSelectedImages() } }
        }
        if (sel.any { it is Drawing && isClosed(it) }) {
            buttons += Ui.pill(this, "Залить", Ui.INK, Ui.DESK).apply { tag = "sel_fill"; setOnClickListener { fillSelectedShapes() } }
        }
        if (!sel.all { it is BoardFrame } && sel.none { it is BoardTable || it is BoardRec || it is BoardVedom || it is BoardVItem }) {
            buttons += Ui.pill(this, "Копия", Ui.INK, Ui.DESK).apply { tag = "sel_copy"; setOnClickListener { duplicateSelection() } }
        }
        if (sel.any { it is BoardText || it is Drawing || it is BoardFill }) {
            buttons += Ui.pill(this, "Цвет", Ui.INK, Ui.DESK).apply { tag = "sel_color"; setOnClickListener { recolorSelection() } }
        }
        if (sel.none { it is BoardVItem }) buttons += Ui.pill(this, "Удалить", Ui.BAD, Ui.BAD_SOFT).apply { tag = "sel_delete"; setOnClickListener { deleteSelection() } }
        buttons += Ui.pill(this, "Готово", Color.WHITE, Ui.ACCENT).apply { tag = "sel_done"; setOnClickListener { view.clearSelection() } }
        val bar = Ui.floatingBar(this, label2, *buttons.toTypedArray())
        selBar.addView(bar)
        selBar.visibility = View.VISIBLE
    }

    private fun idOf(o: Any): String? = when (o) {
        is Drawing -> o.id
        is BoardText -> o.id
        is BoardImage -> o.id
        is BoardSticker -> o.id
        is BoardFill -> o.id
        is BoardCallout -> o.id
        is BoardFrame -> o.id
        is BoardRow -> o.id
        is BoardTable -> o.id
        is BoardRec -> o.id
        is BoardVedom -> o.id
        is BoardVItem -> o.id
        else -> null
    }

    /** «Иван Петров, 14:05» — кто последним правил предмет (по отметке правки). */
    private fun whoEdited(o: Any): String? {
        val rev = board.sync.objs[idOf(o) ?: return null]?.rev ?: return null
        val dev = Remote.deviceOf(rev)
        val ms = Remote.timeOf(rev)
        if (dev.isEmpty() || ms <= 0) return null
        val name = if (dev == store.device) Profile.name(this).ifEmpty { "вы" } else board.sync.members[dev] ?: "коллега"
        val day = android.text.format.DateUtils.isToday(ms)
        val time = if (day) Ui.time(ms) else Ui.shortDate(ms) + " " + Ui.time(ms)
        return "$name, $time"
    }

    private fun editText(t: BoardText) {
        val edit = EditText(this).apply {
            setText(t.text)
            setSelection(t.text.length)
            // Надпись может быть в несколько строк.
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 2
        }
        val box = FrameLayout(this).apply { setPadding(dp(20f), dp(8f), dp(20f), 0); addView(edit) }
        AlertDialog.Builder(this)
            .setTitle("Надпись")
            .setView(box)
            .setPositiveButton("Готово") { _, _ ->
                val s = edit.text.toString().trim()
                if (s.isNotEmpty() && s != t.text) {
                    pushUndo()
                    t.text = s
                    changed()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    /** Копия выделенного — чуть правее и ниже; выделяется копия. Текст надписей — ещё и в буфер обмена. */
    private fun duplicateSelection() {
        val sel = view.selection.toList()
        if (sel.isEmpty()) return
        pushUndo()
        val off = dp(24f) / board.camZ
        val copies = ArrayList<Any>()
        for (o in sel) {
            when (o) {
                is BoardText -> BoardText(o.x + off, o.y + off, o.size, o.text, o.color).also { board.texts += it; copies += it }
                is BoardImage -> BoardImage(o.file, o.x + off, o.y + off, o.w, o.h, o.pxW, o.pxH).also {
                    it.cl = o.cl; it.ct = o.ct; it.cr = o.cr; it.cb = o.cb
                    board.images += it; copies += it
                }
                is BoardSticker -> BoardSticker(o.x + off, o.y + off, o.sid, o.scale).also { board.stickers += it; copies += it }
                is BoardRow -> o.copy().also { it.x += off; it.y += off; board.rows += it; copies += it }
                is Drawing -> o.copy().also { d ->
                    var i = 0
                    while (i + 1 < d.pts.size) { d.pts[i] += off; d.pts[i + 1] += off; i += 3 }
                    d.changed()
                    board.strokes += d
                    copies += d
                }
                is BoardFill -> o.copy().also { f ->
                    var i = 0
                    while (i + 1 < f.pts.size) { f.pts[i] += off; f.pts[i + 1] += off; i += 2 }
                    f.changed()
                    board.fills += f
                    copies += f
                }
                is BoardCallout -> o.copy().also { c ->
                    c.x += off; c.y += off
                    for (i in c.leaders.indices) c.leaders[i] += off
                    board.callouts += c
                    copies += c
                }
            }
        }
        val texts = sel.filterIsInstance<BoardText>().joinToString("\n") { it.text }
        if (texts.isNotEmpty()) {
            getSystemService(android.content.ClipboardManager::class.java)
                ?.setPrimaryClip(android.content.ClipData.newPlainText("FreeWrite", texts))
        }
        view.select(copies)
        changed()
        hint(if (texts.isNotEmpty()) "Копия создана, текст скопирован" else "Копия создана")
    }

    private fun recolorSelection() {
        ColorKit.showPalette(this, penColor, { c -> applyColorToSelection(c) }, { startPipette(forSelection = true) })
    }

    private fun applyColorToSelection(c: Int) {
        val sel = view.selection
        if (sel.isEmpty()) return
        pushUndo()
        for (o in sel) when (o) {
            is BoardText -> o.color = c
            is Drawing -> o.color = c
            is BoardFill -> o.color = c
        }
        penColor = c
        ColorKit.remember(this, c)
        refreshColors()
        changed()
    }

    private fun deleteSelection() {
        val sel = view.selection.toList()
        if (sel.isEmpty()) return
        if (sel.any { it is BoardTable || it is BoardRec }) { tableUi.confirmDelete(sel) { removeSelection(sel) }; return }
        if (sel.any { it is BoardVedom || it is BoardVItem }) { vedomUi.confirmDelete(sel) { removeSelection(sel) }; return }
        removeSelection(sel)
    }

    private fun removeSelection(sel: List<Any>) {
        pushUndo()
        tableUi.removeSelected(sel)
        vedomUi.removeSelected(sel)
        board.texts.removeAll { t -> sel.any { it === t } }
        board.images.removeAll { m -> sel.any { it === m } }
        board.stickers.removeAll { s -> sel.any { it === s } }
        board.strokes.removeAll { d -> sel.any { it === d } }
        board.fills.removeAll { f -> sel.any { it === f } }
        board.callouts.removeAll { c -> sel.any { it === c } }
        board.frames.removeAll { f -> sel.any { it === f } }
        board.rows.removeAll { r -> sel.any { it === r } }
        view.clearSelection()
        changed()
    }

    // ---------------------------------------------------------------- надписи: склейка

    /**
     * Распознанное слово на доске (bb — рамка рукописи). Если рядом уже есть надпись — слово
     * дописывается в неё: в той же строке сразу после неё — через пробел, под ней с того же края —
     * новой строкой. Иначе — новая надпись. Так написанное подряд остаётся одним текстом.
     */
    internal fun placeText(bb: FloatArray, word: String): BoardText {
        val h = max(bb[3] - bb[1], Ui.dpf(this, 12f) / board.camZ)
        val size = h * 0.85f
        val baseline = bb[1] + size * 0.85f
        for (t in board.texts.asReversed()) {
            if (size < t.size * 0.5f || size > t.size * 2f) continue // совсем другой размер — не склеиваем
            val lh = t.size * BoardText.LINE
            val lines = t.text.split('\n')
            val lastBase = t.y + (lines.size - 1) * lh
            val lastRight = t.x + view.lineWidth(t.size, lines.last())
            // Та же строка: линия букв рядом, слово начинается сразу за концом строки.
            if (kotlin.math.abs(baseline - lastBase) <= 0.6f * t.size &&
                bb[0] >= lastRight - 0.8f * t.size && bb[0] <= lastRight + 3.5f * t.size
            ) {
                t.text = t.text.trimEnd() + " " + word
                return t
            }
            // Строка ниже, с того же левого края.
            val dy = baseline - lastBase
            if (dy >= 0.7f * lh && dy <= 1.9f * lh && kotlin.math.abs(bb[0] - t.x) <= 2.5f * t.size) {
                t.text = t.text + "\n" + word
                return t
            }
        }
        return BoardText(bb[0], baseline, size, word, Ui.INK).also { board.texts += it }
    }

    /** «Объединить»: выделенные надписи — в одну, по строкам сверху вниз, слова слева направо. */
    private fun mergeSelectedTexts() {
        val ts = view.selection.filterIsInstance<BoardText>()
        if (ts.size < 2) return
        class Item(val x: Float, val base: Float, val size: Float, val text: String, val color: Int)
        val items = ts.flatMap { t ->
            t.text.split('\n').mapIndexed { i, l -> Item(t.x, t.y + i * t.size * BoardText.LINE, t.size, l, t.color) }
        }.filter { it.text.isNotBlank() }
        if (items.isEmpty()) return
        val med = items.map { it.size }.sorted()[items.size / 2]
        val rows = ArrayList<ArrayList<Item>>()
        for (it in items.sortedBy { it.base }) {
            val row = rows.lastOrNull()
            if (row != null && it.base - row[0].base <= 0.6f * med) row += it else rows += arrayListOf(it)
        }
        val text = rows.joinToString("\n") { r -> r.sortedBy { it.x }.joinToString(" ") { it.text.trim() } }
        val first = rows[0].minByOrNull { it.x }!!
        pushUndo()
        val at = board.texts.indexOfFirst { t -> ts.any { it === t } }
        board.texts.removeAll { t -> ts.any { it === t } }
        val merged = BoardText(items.minOf { it.x }, rows[0][0].base, med, text, first.color)
        board.texts.add(at.coerceIn(0, board.texts.size), merged)
        changed()
        view.select(listOf(merged))
        hint("Надписи объединены")
    }

    // ---------------------------------------------------------------- заливки

    private fun isClosed(d: Drawing): Boolean {
        val p = d.pts
        if (p.size < 12) return false
        return Math.hypot((p[0] - p[p.size - 3]).toDouble(), (p[1] - p[p.size - 2]).toDouble()) <= d.width * 2 + dp(6f) / board.camZ
    }

    /** Обвели пером область — заливка: ровная фигура, если похоже на прямоугольник/многоугольник/овал, иначе — как обвели. */
    private fun createFill(stroke: InkStroke) {
        val z = board.camZ
        val bb = NoteInkView.bounds(listOf(stroke))
        if (stroke.points.size < 3 || max(bb[2] - bb[0], bb[3] - bb[1]) < dp(24f) / z) {
            hint("Обведите захватку целиком — замкнутым контуром")
            return
        }
        val raw = ShapeTool.xy(stroke)
        val shape = ShapeRecognizer.recognize(listOf(raw), dp(24f) / z, Float.MAX_VALUE)
        val pts = if (shape != null && shape.isClosed) shape.pts else thin(raw, dp(1.5f) / z)
        if (pts.size < 6) return
        pushUndo()
        board.fills += BoardFill(pts, penColor)
        changed()
    }

    /** Убрать лишние точки контура (ближе step друг к другу). */
    private fun thin(p: FloatArray, step: Float): FloatArray {
        val out = ArrayList<Float>()
        var lx = Float.NaN; var ly = Float.NaN
        var i = 0
        while (i + 1 < p.size) {
            if (lx.isNaN() || Math.hypot((p[i] - lx).toDouble(), (p[i + 1] - ly).toDouble()) >= step) {
                out += p[i]; out += p[i + 1]; lx = p[i]; ly = p[i + 1]
            }
            i += 2
        }
        return out.toFloatArray()
    }

    /** «Залить»: замкнутые фигуры из выделенного становятся заливками своего цвета. */
    private fun fillSelectedShapes() {
        val shapes = view.selection.filterIsInstance<Drawing>().filter { isClosed(it) }
        if (shapes.isEmpty()) return
        pushUndo()
        val fills = ArrayList<Any>()
        for (d in shapes) {
            val n = d.pts.size / 3
            val xy = FloatArray(n * 2) { k -> d.pts[(k / 2) * 3 + (k % 2)] }
            val f = BoardFill(xy, d.color)
            val at = board.strokes.indexOfFirst { it === d }
            board.strokes.removeAt(at)
            board.fills += f
            fills += f
        }
        changed()
        view.select(fills)
    }

    // ---------------------------------------------------------------- статусы

    /** Кружок цвета 18 dp. */
    private fun dot(color: Int): View = View(this).apply { background = ColorKit.swatch(this@BoardActivity, color, false) }

    /** Выбрать статус метки или выноски из списка (долгое нажатие или кнопка «Статус»). */
    private fun chooseStatus(obj: Any) {
        val current = when (obj) { is BoardSticker -> obj.sid; is BoardCallout -> obj.sid; is BoardRow -> obj.sid; else -> return }
        val group = if (obj is BoardCallout) BoardStatus.CON else BoardStatus.ID
        val list = board.statuses(group)
        val names = list.map { (if (it.id == current) "● " else "   ") + it.name } + "Настроить статусы…"
        AlertDialog.Builder(this)
            .setTitle(if (obj is BoardRow) "ИГС" else if (group == BoardStatus.CON) "Статус конструкции" else "Статус ИД")
            .setItems(names.toTypedArray()) { _, which ->
                if (which == list.size) { statusesDialog(); return@setItems }
                val sid = list[which].id
                pushUndo()
                when (obj) { is BoardSticker -> obj.sid = sid; is BoardCallout -> obj.sid = sid; is BoardRow -> obj.sid = sid }
                changed()
            }
            .show()
    }

    /**
     * Окно «Статусы»: список доски (порядок = порядок переключения касанием) — цвет, название,
     * сколько меток и выносок; ▲▼ — порядок, ✎ — изменить, ✕ — удалить, «Добавить статус».
     */
    private fun statusesDialog() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16f), dp(8f), dp(16f), 0) }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Статусы доски")
            .setView(android.widget.ScrollView(this).apply { addView(box) })
            .setPositiveButton("Готово", null)
            .create()
        fun small(text: String, color: Int = Ui.INK2, action: () -> Unit) = Ui.text(this, text, 18f, color).apply {
            setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
            isClickable = true
            setOnClickListener { action() }
        }
        lateinit var render: () -> Unit
        render = {
            box.removeAllViews()
            box.addView(Ui.text(this, "Касание статуса переключает его по своему списку сверху вниз.", 13f, Ui.MUTED)
                .apply { setPadding(0, 0, 0, dp(4f)) })
            for (group in listOf(BoardStatus.ID, BoardStatus.CK, BoardStatus.DWG, BoardStatus.CON)) {
                box.addView(Ui.text(this, when (group) {
                    BoardStatus.ID -> "ИГС — таблицы ИД, метки, строки"; BoardStatus.CK -> "Чек-лист"; BoardStatus.DWG -> "DWG"
                    else -> "Конструкции — выноски" },
                    15f, Ui.INK, bold = true).apply { setPadding(0, dp(12f), 0, dp(4f)); tag = "status_group_$group" })
                val list = board.statuses(group)
                for ((k, st) in list.withIndex()) {
                    val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; tag = "status_row" }
                    row.addView(dot(st.color), LinearLayout.LayoutParams(dp(18f), dp(18f)))
                    row.addView(Ui.text(this, st.name, 16f, Ui.INK).apply { setPadding(dp(10f), 0, dp(6f), 0) },
                        LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
                    row.addView(Ui.mono(this, statusUse(st).toString() + if (st.done && group == BoardStatus.ID) " · сдано" else "", 13f))
                    // ▲▼ — порядок внутри своего списка: меняемся местами с соседом того же списка.
                    fun swap(other: BoardStatus) {
                        pushUndo()
                        val i = board.statuses.indexOf(st); val j = board.statuses.indexOf(other)
                        board.statuses[i] = other; board.statuses[j] = st
                        changed(); render()
                    }
                    row.addView(small("▲") { if (k > 0) swap(list[k - 1]) })
                    row.addView(small("▼") { if (k < list.size - 1) swap(list[k + 1]) })
                    row.addView(small("✎") { editStatusDialog(st, group) { render() } })
                    row.addView(small("✕", Ui.BAD) { deleteStatus(st) { render() } })
                    box.addView(row, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
                }
                box.addView(Ui.pill(this, "+ Добавить статус", Color.WHITE, Ui.ACCENT).apply {
                    tag = "status_add_$group"
                    setOnClickListener { editStatusDialog(null, group) { render() } }
                }, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { topMargin = dp(8f); bottomMargin = dp(6f) })
            }
        }
        render()
        dialog.show()
    }

    /** Новый статус (st = null) или правка: название и цвет из палитры. */
    private fun editStatusDialog(st: BoardStatus?, group: String, done: (BoardStatus) -> Unit) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20f), dp(8f), dp(20f), 0) }
        val name = EditText(this).apply {
            setText(st?.name.orEmpty())
            hint = if (group == BoardStatus.CON) "Например: Опалубка" else "Например: В оформлении"
            isSingleLine = true
            tag = "status_name"
        }
        box.addView(name)
        var color = st?.color ?: ColorKit.PALETTE[7]
        val grid = android.widget.GridLayout(this).apply { columnCount = 6 }
        val swatches = ArrayList<Pair<Int, View>>()
        for (c in ColorKit.PALETTE) {
            val v = View(this).apply { tag = "status_color" }
            swatches += c to v
            grid.addView(v, android.widget.GridLayout.LayoutParams().apply {
                width = dp(34f); height = dp(34f); setMargins(dp(5f), dp(5f), dp(5f), dp(5f))
            })
        }
        fun paint() { for ((c, v) in swatches) v.background = ColorKit.swatch(this, c, c == color) }
        for ((c, v) in swatches) v.setOnClickListener { color = c; paint() }
        paint()
        box.addView(grid, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { topMargin = dp(8f) })
        val doneBox = CheckBox(this).apply {
            text = "Это «сдано» (считать в сводке сданной)"; isChecked = st?.done == true; tag = "status_done"
            visibility = if (group == BoardStatus.ID) View.VISIBLE else View.GONE
        }
        box.addView(doneBox)
        AlertDialog.Builder(this)
            .setTitle(if (st == null) "Новый статус" else "Статус")
            .setView(box)
            .setPositiveButton("Готово") { _, _ ->
                val n = name.text.toString().trim()
                if (n.isEmpty()) return@setPositiveButton
                pushUndo()
                val result = if (st == null) BoardStatus(Board.newStatusId(), n, color, group).also { board.statuses += it }
                else st.also { it.name = n; it.color = color }
                if (group == BoardStatus.ID) result.done = doneBox.isChecked
                changed()
                done(result)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    /** Сколько раз статус стоит на доске (Чек-лист и DWG — в строках таблиц). */
    private fun statusUse(st: BoardStatus): Int = when (st.group) {
        BoardStatus.CK -> board.recs.count { it.ck == st.id }
        BoardStatus.DWG -> board.recs.count { it.dwg == st.id }
        else -> board.countStatus(st.id)
    }

    private fun deleteStatus(st: BoardStatus, done: () -> Unit) {
        val same = board.statuses(st.group)
        if (same.size <= 1) { hint("В списке должен остаться хотя бы один статус"); return }
        val used = statusUse(st)
        val rest = same.first { it.id != st.id }
        val msg = if (used == 0) "Удалить статус «${st.name}»?"
        else "Статус «${st.name}» стоит у $used меток, строк и выносок. Удалить? Они станут «${rest.name}»."
        AlertDialog.Builder(this)
            .setMessage(msg)
            .setPositiveButton("Удалить") { _, _ ->
                pushUndo()
                for (s in board.stickers) if (s.sid == st.id) s.sid = rest.id
                for (c in board.callouts) if (c.sid == st.id) c.sid = rest.id
                for (r in board.rows) if (r.sid == st.id) r.sid = rest.id
                for (r in board.recs) when (st.group) {
                    BoardStatus.CK -> if (r.ck == st.id) r.ck = rest.id
                    BoardStatus.DWG -> if (r.dwg == st.id) r.dwg = rest.id
                    BoardStatus.ID -> if (r.igs == st.id) r.igs = rest.id
                }
                board.statuses.removeAll { it.id == st.id }
                changed()
                done()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ---------------------------------------------------------------- выноски

    /**
     * Окно выноски: верхняя строка — надпись, нижняя — статус из списка (или новый).
     * c = null — новая выноска.
     */
    private fun calloutDialog(c: BoardCallout?, done: (String, String) -> Unit) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20f), dp(8f), dp(20f), 0) }
        val title = EditText(this).apply {
            setText(c?.title.orEmpty())
            hint = "Например: Захватка №1 от 11.09.26"
            isSingleLine = true
            tag = "callout_title"
        }
        box.addView(Ui.text(this, "Надпись", 13f, Ui.MUTED))
        box.addView(title)
        box.addView(Ui.text(this, "Статус конструкции", 13f, Ui.MUTED).apply { setPadding(0, dp(12f), 0, dp(4f)) })
        var sid = c?.sid ?: board.firstStatus(BoardStatus.CON)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(list)
        lateinit var render: () -> Unit
        render = {
            list.removeAllViews()
            for (st in board.statuses(BoardStatus.CON)) {
                val row = LinearLayout(this).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
                    background = if (st.id == sid) Ui.rounded(this@BoardActivity, Board.statusBg(st.color), Board.statusFg(st.color), 10f) else null
                    isClickable = true
                    tag = "callout_status"
                    setOnClickListener { sid = st.id; render() }
                }
                row.addView(dot(st.color), LinearLayout.LayoutParams(dp(16f), dp(16f)))
                row.addView(Ui.text(this, st.name, 16f, if (st.id == sid) Board.statusFg(st.color) else Ui.INK, bold = st.id == sid)
                    .apply { setPadding(dp(10f), 0, 0, 0) })
                list.addView(row, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { bottomMargin = dp(2f) })
            }
            list.addView(Ui.text(this, "+ Новый статус", 15f, Ui.ACCENT).apply {
                setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
                isClickable = true
                tag = "callout_new_status"
                setOnClickListener { editStatusDialog(null, BoardStatus.CON) { st -> sid = st.id; render() } }
            })
        }
        render()
        AlertDialog.Builder(this)
            .setTitle(if (c == null) "Выноска" else "Изменить выноску")
            .setView(android.widget.ScrollView(this).apply { addView(box) })
            .setPositiveButton("Готово") { _, _ -> done(title.text.toString().trim(), sid) }
            .setNegativeButton("Отмена", null)
            .show()
        title.requestFocus()
    }

    // ---------------------------------------------------------------- пипетка

    /** Цвет под точкой экрана: штрих или надпись рядом — их цвет, метка — её цвет, иначе пиксель (фото, фон). */
    override fun pipetteColor(sx: Float, sy: Float): Int? {
        val x = view.wx(sx)
        val y = view.wy(sy)
        ColorKit.drawingNear(board.strokes, x, y, Ui.dpf(this, 10f) / board.camZ)?.let { return it.color }
        val h = view.hitAt(x, y)
        when (h?.kind) {
            BoardView.Kind.TEXT -> return board.texts[h.index].color
            BoardView.Kind.STICKER -> return board.status(board.stickers[h.index].sid).color
            BoardView.Kind.CALLOUT -> return board.status(board.callouts[h.index].sid).color
            else -> Unit
        }
        val fi = view.fillAt(x, y)
        if (fi >= 0) return board.fills[fi].color
        val snap = pipetteSnap ?: view.plainSnapshot()?.also { pipetteSnap = it }
        return snap?.colorAt(sx, sy)
    }

    override fun onPipettePick(sx: Float, sy: Float) {
        val c = pipetteColor(sx, sy) ?: return
        pipetteSnap = null
        setTool(toolBeforePipette)
        if (pipetteForSelection && view.selection.isNotEmpty()) {
            applyColorToSelection(c)
        } else {
            penColor = c
            ColorKit.remember(this, c)
            refreshColors()
        }
        pipetteForSelection = false
        hint("Цвет взят")
    }

    override fun onCameraChanged() {
        pipetteSnap = null
        updateZoom()
        handler.removeCallbacks(saveRunnable)
        handler.postDelayed(saveRunnable, 800)
    }

    override fun pendingStrokes(): List<InkStroke> {
        val out = ArrayList<InkStroke>()
        for (g in committing) out += g.strokes
        current?.let { out += it.strokes }
        return out
    }

    override fun interim(): Pair<FloatArray, String>? {
        val g = current ?: return null
        if (g.strokes.isEmpty() || g.resultFor != g.strokes.size) return null
        val text = g.candidates?.firstOrNull()?.text?.trim().orEmpty()
        if (text.isEmpty()) return null
        return NoteInkView.bounds(g.strokes) to text
    }

    // ---------------------------------------------------------------- распознавание

    private fun isFar(g: Group, x: Float, y: Float): Boolean {
        val b = NoteInkView.bounds(g.strokes)
        val h = b[3] - b[1]
        val pad = max(Ui.dpf(this, 60f) / board.camZ, h * 2.2f)
        return x < b[0] - pad || x > b[2] + pad || y < b[1] - pad || y > b[3] + pad
    }

    private fun recognize(g: Group) {
        val ru = engine.language("ru")
        val n = g.strokes.size
        if (n == 0) return
        if (ru.state != HandwritingEngine.State.READY) {
            updateBanner()
            if (g.committing) {
                g.candidates = emptyList(); g.error = "модель распознавания не скачана"; g.resultFor = n
                applyGroup(g)
            }
            return
        }
        val snapshot = ArrayList(g.strokes)
        val bb = NoteInkView.bounds(snapshot)
        val h = max(bb[3] - bb[1], 1f)
        val w = max(bb[2] - bb[0], h)
        engine.recognize(ru, snapshot, "", w * 1.2f, h * 1.15f) { out ->
            if (n < g.resultFor) return@recognize
            g.candidates = if (out.error == null) out.candidates else emptyList()
            g.error = out.error
            g.resultFor = n
            if (g.committing && n == g.strokes.size) applyGroup(g) else view.invalidate()
        }
    }

    private fun commit(g: Group) {
        handler.removeCallbacks(commitRunnable)
        handler.removeCallbacks(interimRunnable)
        if (g === current) current = null
        if (g.strokes.isEmpty() || g.committing) return
        g.committing = true
        committing += g
        if (g.resultFor == g.strokes.size) applyGroup(g) else recognize(g)
    }

    /** Надпись → текст на том же месте. Не распознано — остаётся рисунком, ничего не теряется. */
    private fun applyGroup(g: Group) {
        if (!committing.remove(g)) return
        val best = g.candidates?.firstOrNull()?.text?.trim().orEmpty()
        pushUndo()
        // Написали пером над таблицей ИД — в ячейку (ТЗ, раздел 14.3).
        val gbb = NoteInkView.bounds(g.strokes)
        if (tableUi.penIntoCell((gbb[0] + gbb[2]) / 2f, (gbb[1] + gbb[3]) / 2f, best)) {
            view.fade(g.strokes)
            changed()
            return
        }
        // Крупная стрелка, рамка или круг — это рисунок, а не буква: делаем ровную фигуру.
        val z = board.camZ
        val shape = ShapeTool.fromWriting(g.strokes, best, Ui.dpf(this, 150f) / z, Ui.dpf(this, 4f) / z)
        if (shape != null) {
            board.strokes += ShapeTool.drawingOf(shape, NoteInkView.StrokeStyle("pen", Ui.INK, Ui.dpf(this, 2.4f) / z))
            changed()
            return
        }
        if (best.isEmpty()) {
            val style = NoteInkView.StrokeStyle("write", Ui.INK, Ui.dpf(this, 2.6f) / board.camZ)
            for (s in g.strokes) board.strokes += NoteInkView.toDrawing(s, style)
            hint(g.error?.let { "Не распознано (${HandwritingEngine.shortError(it)}) — оставил рисунком" }
                ?: "Не разобрал надпись — оставил её рисунком")
        } else {
            placeText(NoteInkView.bounds(g.strokes), best)
            view.fade(g.strokes)
        }
        changed()
    }

    companion object {
        const val EXTRA_ID = "board_id"
        /** Сразу показывать скорость отрисовки (тестовая доска). */
        const val EXTRA_SHOW_SPEED = "show_speed"
        /** Присланные файлы (пути копий в кэше), которые положить на доску. */
        const val EXTRA_INSERT = "insert_files"

        /** Показать доску целиком при открытии (только что перенесённая из Nebo). */
        const val EXTRA_FIT = "fit_all"
        /** Имя присланного файла, как его видит человек. */
        const val EXTRA_INSERT_NAME = "insert_name"
        private const val REQ_IMAGE = 42
        private const val REQ_PDF = 43
    }
}
