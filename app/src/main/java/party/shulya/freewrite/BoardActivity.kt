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

    enum class Tool { WRITE, PEN, MARKER, SHAPE, ERASER, SELECT, STATUS, PIPETTE }

    private lateinit var store: BoardStore
    private lateinit var noteStore: NoteStore
    private lateinit var board: Board
    private lateinit var engine: HandwritingEngine
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private lateinit var root: LinearLayout
    private lateinit var view: BoardView
    private lateinit var titleView: TextView
    private lateinit var zoomLabel: TextView
    private lateinit var hintView: TextView
    private lateinit var banner: LinearLayout
    private lateinit var bannerText: TextView
    private lateinit var bannerButton: TextView
    private lateinit var selBar: LinearLayout
    private val toolButtons = HashMap<Tool, Ui.ToolButton>()
    private val quickColors = ArrayList<View>()
    private var toolBeforePipette = Tool.PEN
    private var pipetteForSelection = false
    private var pipetteSnap: ColorKit.Snapshot? = null

    private var tool = Tool.PEN
    private var penColor = Ui.INK
    private var fingerWrites = false
    private val bitmaps = HashMap<String, Bitmap?>()
    private val undoStack = ArrayDeque<String>()
    private val redoStack = ArrayDeque<String>()
    private var pauseMs = 1000L
    private val shapeTool = ShapeTool()

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
        val id = intent.getStringExtra(EXTRA_ID)
        board = id?.let { store.load(it) } ?: Board.create("Доска").also { store.save(it) }
        engine = HandwritingEngine { android.util.Log.i("FreeWrite", it) }
        engine.onChanged = { updateBanner() }
        buildUi()
        setContentView(root)
        engine.start(this)
        setTool(Tool.PEN)
        updateBanner()
        updateZoom()
        if (board.isEmpty && board.camX == 0f && board.camY == 0f) view.post { view.fitAll() }
    }

    override fun onPause() {
        current?.let { commit(it) }
        saveNow()
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
        current?.let { commit(it) }
        val finishNow = Runnable { saveNow(); finish() }
        if (committing.isEmpty()) finishNow.run() else handler.postDelayed(finishNow, 1500)
    }

    // ---------------------------------------------------------------- интерфейс

    private fun buildUi() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.DESK) }
        val row = LinearLayout(this)
        row.addView(Ui.ToolButton(this, R.drawable.ic_back, "Назад").apply {
            minimumWidth = dp(44f)
            setOnClickListener { onBackPressed() }
        })
        titleView = Ui.text(this, board.title, 16f, Ui.INK, bold = true).apply {
            maxWidth = dp(200f); isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(4f), 0, dp(10f), 0)
        }
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(titleView)
            addView(Ui.mono(this@BoardActivity, "доска · бесконечное поле", 11f).apply { setPadding(dp(4f), 0, dp(10f), 0) })
            setOnClickListener { rename() }
        })
        row.addView(Ui.separator(this))
        addTool(row, Tool.WRITE, R.drawable.ic_write, "В текст")
        addTool(row, Tool.PEN, R.drawable.ic_pen, "Ручка")
        addTool(row, Tool.MARKER, R.drawable.ic_marker, "Маркер")
        addTool(row, Tool.SHAPE, R.drawable.ic_shapes, "Фигуры")
        addTool(row, Tool.ERASER, R.drawable.ic_eraser, "Ластик")
        addTool(row, Tool.SELECT, R.drawable.ic_lasso, "Выделить")
        addTool(row, Tool.STATUS, R.drawable.ic_status, "Статус")
        // Три последних цвета и «Цвет» — палитра из 24 цветов и пипетка.
        for (i in 0 until 3) {
            val v = View(this).apply {
                contentDescription = "Цвет"
                tag = "board_quick_color_$i"
                setOnClickListener { setColor(ColorKit.recent(this@BoardActivity)[i], remember = false) }
            }
            row.addView(v, LinearLayout.LayoutParams(dp(24f), dp(24f)).apply { setMargins(dp(5f), 0, dp(5f), 0) })
            quickColors += v
        }
        row.addView(Ui.ToolButton(this, R.drawable.ic_palette, "Цвет").apply {
            tag = "board_tool_palette"
            setOnClickListener { ColorKit.showPalette(this@BoardActivity, penColor, { setColor(it) }, { startPipette(forSelection = false) }) }
        })
        row.addView(Ui.separator(this))
        row.addView(Ui.ToolButton(this, R.drawable.ic_image, "Картинка").apply { setOnClickListener { pickImage() } })
        row.addView(Ui.ToolButton(this, R.drawable.ic_undo, "Отменить").apply { setOnClickListener { undo() } })
        row.addView(Ui.ToolButton(this, R.drawable.ic_redo, "Вернуть").apply { setOnClickListener { redo() } })
        row.addView(Ui.ToolButton(this, R.drawable.ic_fit, "Всё").apply { setOnClickListener { view.fitAll() } })
        row.addView(Ui.separator(this))
        row.addView(CheckBox(this).apply {
            text = "Палец пишет"; textSize = 13f; setTextColor(Ui.INK2)
            buttonTintList = ColorStateList.valueOf(Ui.ACCENT)
            setOnCheckedChangeListener { _, on -> fingerWrites = on }
        })
        root.addView(Ui.toolbar(this, row), LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        root.addView(View(this).apply { setBackgroundColor(Ui.RULE) }, LinearLayout.LayoutParams(Ui.MATCH, dp(1f)))

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
        area.addView(view, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        zoomLabel = Ui.mono(this, "100%", 12f, Ui.INK2).apply {
            background = Ui.rounded(this@BoardActivity, Ui.PAPER, Ui.RULE, 8f)
            setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
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
        area.addView(selBar, FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; topMargin = dp(10f)
        })
        root.addView(area, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
        handler.postDelayed({ hint("Пальцем — двигать и масштабировать, пером — писать и рисовать", 3500) }, 300)
    }

    private fun addTool(row: LinearLayout, t: Tool, icon: Int, label: String) {
        val b = Ui.ToolButton(this, icon, label).apply { setOnClickListener { setTool(t) } }
        b.tag = "board_tool_" + t.name.lowercase()
        toolButtons[t] = b
        row.addView(b)
    }

    private fun setTool(t: Tool) {
        if (t != Tool.WRITE) current?.let { commit(it) }
        tool = t
        for ((k, v) in toolButtons) v.selectedTool = k == t
        refreshColors()
        if (t != Tool.SHAPE) shapeTool.reset()
        if (t == Tool.STATUS) hint("Коснитесь пером: метка «Не сдано». Пальцем по метке — следующий статус")
        if (t == Tool.SELECT) hint("Обведите, что выделить. Касание — один объект. Доску двигать — двумя пальцами", 3500)
        if (t == Tool.SHAPE) hint("Нарисуйте линию, стрелку, квадрат, круг или треугольник — станет ровной фигурой", 3500)
    }

    private fun colorTool(t: Tool) = t == Tool.PEN || t == Tool.MARKER || t == Tool.SHAPE

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

    private fun hint(text: String, ms: Long = 2600) {
        hintView.text = text
        hintView.animate().cancel()
        hintView.alpha = 1f
        hintView.animate().alpha(0f).setStartDelay(ms).setDuration(300).start()
    }

    private fun updateZoom() {
        if (::zoomLabel.isInitialized) zoomLabel.text = "${(board.camZ * 100).roundToInt()}%"
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

    private fun pushUndo() {
        undoStack.addLast(board.toJson(withCamera = false).toString())
        while (undoStack.size > 60) undoStack.removeFirst()
        redoStack.clear()
    }

    private fun restore(json: String) {
        val cam = Triple(board.camX, board.camY, board.camZ)
        board = Board.fromJson(JSONObject(json))
        board.camX = cam.first; board.camY = cam.second; board.camZ = cam.third
        titleView.text = board.title
        view.clearSelection()
        changed()
    }

    private fun undo() {
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
        redoStack.addLast(board.toJson(withCamera = false).toString())
        restore(undoStack.removeLast())
    }

    private fun redo() {
        if (redoStack.isEmpty() || committing.isNotEmpty() || current != null) return
        undoStack.addLast(board.toJson(withCamera = false).toString())
        restore(redoStack.removeLast())
    }

    private fun changed() {
        view.pruneSelection()
        pipetteSnap = null
        view.invalidate()
        handler.removeCallbacks(saveRunnable)
        handler.postDelayed(saveRunnable, 500)
    }

    private fun saveNow() {
        handler.removeCallbacks(saveRunnable)
        try {
            store.save(board)
        } catch (t: Throwable) {
            Toast.makeText(this, "Не удалось сохранить: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ---------------------------------------------------------------- картинки

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
            val block = try { noteStore.importImage(this, uri) } catch (t: Throwable) { null }
            handler.post {
                if (block == null) { hint("Не удалось открыть картинку"); return@post }
                val z = board.camZ
                val maxW = dp(460f) / z
                val k = min(1f, maxW / block.width)
                val w = block.width * k
                val h = block.height * k
                val cx = view.wx(view.width / 2f)
                val cy = view.wy(view.height / 2f)
                pushUndo()
                board.images += BoardImage(block.file, cx - w / 2f, cy - h / 2f, w, h, block.width, block.height)
                changed()
                view.select(listOf(board.images.last()))
            }
        }
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
    override fun fingerWrites(): Boolean = fingerWrites

    override fun strokeStyle(): NoteInkView.StrokeStyle = when (tool) {
        Tool.MARKER -> NoteInkView.StrokeStyle("marker", penColor, Ui.dpf(this, 16f))
        Tool.WRITE -> NoteInkView.StrokeStyle("write", Ui.INK, Ui.dpf(this, 2.6f))
        Tool.SHAPE -> NoteInkView.StrokeStyle("shape", penColor, Ui.dpf(this, 2.4f))
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
        if (style.kind == "write") {
            val g = current ?: Group().also { current = it }
            g.strokes += stroke
            handler.removeCallbacks(interimRunnable); handler.removeCallbacks(commitRunnable)
            handler.postDelayed(interimRunnable, 180)
            handler.postDelayed(commitRunnable, pauseMs)
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
        if (keep.size != board.strokes.size || (h != null && h.kind != BoardView.Kind.IMAGE)) {
            if (!eraseUndoPushed) { pushUndo(); eraseUndoPushed = true }
            if (keep.size != board.strokes.size) { board.strokes.clear(); board.strokes.addAll(keep); changedAny = true }
            when (h?.kind) {
                BoardView.Kind.TEXT -> { board.texts.removeAt(h.index); changedAny = true }
                BoardView.Kind.STICKER -> { board.stickers.removeAt(h.index); changedAny = true }
                else -> Unit
            }
        }
        if (changedAny) changed()
    }

    override fun onStatusTap(x: Float, y: Float) {
        val h = view.hitAt(x, y)
        pushUndo()
        if (h?.kind == BoardView.Kind.STICKER) {
            val s = board.stickers[h.index]
            s.status = (s.status + 1) % 3
        } else {
            // Метка ставится «в рост» текущего масштаба: на экране около 128×36 dp.
            val scale = resources.displayMetrics.density / board.camZ
            val s = BoardSticker(0f, 0f, 0, scale)
            s.x = x - s.w / 2f; s.y = y - s.h / 2f
            board.stickers += s
        }
        changed()
    }

    override fun onStickerTap(index: Int) {
        val s = board.stickers.getOrNull(index) ?: return
        pushUndo()
        s.status = (s.status + 1) % 3
        changed()
    }

    override fun onDragStart() { pushUndo() }
    override fun onDragEnd() { changed() }
    override fun onImageLongPress(index: Int) { confirmDeleteImage(index) }

    override fun onSelectionChanged() {
        if (::selBar.isInitialized) buildSelectionBar()
    }

    // ---------------------------------------------------------------- выделенное: панель и действия

    private fun buildSelectionBar() {
        val sel = view.selection
        selBar.removeAllViews()
        if (sel.isEmpty()) {
            selBar.visibility = View.GONE
            return
        }
        val label = when {
            sel.size > 1 -> "Выделено: ${sel.size}"
            sel[0] is BoardText -> "Надпись"
            sel[0] is BoardImage -> "Картинка"
            sel[0] is BoardSticker -> "Метка"
            else -> "Рисунок"
        }
        val buttons = ArrayList<TextView>()
        if (sel.size == 1 && sel[0] is BoardText) {
            buttons += Ui.pill(this, "Изменить", Ui.INK, Ui.DESK).apply { tag = "sel_edit"; setOnClickListener { editText(sel[0] as BoardText) } }
        }
        buttons += Ui.pill(this, "Копия", Ui.INK, Ui.DESK).apply { tag = "sel_copy"; setOnClickListener { duplicateSelection() } }
        if (sel.any { it is BoardText || it is Drawing }) {
            buttons += Ui.pill(this, "Цвет", Ui.INK, Ui.DESK).apply { tag = "sel_color"; setOnClickListener { recolorSelection() } }
        }
        buttons += Ui.pill(this, "Удалить", Ui.BAD, Ui.BAD_SOFT).apply { tag = "sel_delete"; setOnClickListener { deleteSelection() } }
        buttons += Ui.pill(this, "Готово", Color.WHITE, Ui.ACCENT).apply { tag = "sel_done"; setOnClickListener { view.clearSelection() } }
        val bar = Ui.floatingBar(this, label, *buttons.toTypedArray())
        selBar.addView(bar)
        selBar.visibility = View.VISIBLE
    }

    private fun editText(t: BoardText) {
        val edit = EditText(this).apply { setText(t.text); setSelection(t.text.length) }
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
                is BoardImage -> BoardImage(o.file, o.x + off, o.y + off, o.w, o.h, o.pxW, o.pxH).also { board.images += it; copies += it }
                is BoardSticker -> BoardSticker(o.x + off, o.y + off, o.status, o.scale).also { board.stickers += it; copies += it }
                is Drawing -> o.copy().also { d ->
                    var i = 0
                    while (i + 1 < d.pts.size) { d.pts[i] += off; d.pts[i + 1] += off; i += 3 }
                    board.strokes += d
                    copies += d
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
        }
        penColor = c
        ColorKit.remember(this, c)
        refreshColors()
        changed()
    }

    private fun deleteSelection() {
        val sel = view.selection.toList()
        if (sel.isEmpty()) return
        pushUndo()
        board.texts.removeAll { t -> sel.any { it === t } }
        board.images.removeAll { m -> sel.any { it === m } }
        board.stickers.removeAll { s -> sel.any { it === s } }
        board.strokes.removeAll { d -> sel.any { it === d } }
        view.clearSelection()
        changed()
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
            BoardView.Kind.STICKER -> return BoardView.STATUS[board.stickers[h.index].status.coerceIn(0, 2)].second
            else -> Unit
        }
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

    override fun bitmap(img: BoardImage): Bitmap? = bitmaps.getOrPut(img.file) {
        noteStore.loadImage(Block.Image(img.file, img.pxW, img.pxH), 1600)
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
            val bb = NoteInkView.bounds(g.strokes)
            val h = max(bb[3] - bb[1], Ui.dpf(this, 12f) / board.camZ)
            val size = h * 0.85f
            board.texts += BoardText(bb[0], bb[1] + size * 0.85f, size, best, Ui.INK)
            view.fade(g.strokes)
        }
        changed()
    }

    companion object {
        const val EXTRA_ID = "board_id"
        private const val REQ_IMAGE = 42
    }
}
