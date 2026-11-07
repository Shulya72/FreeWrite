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

    enum class Tool { WRITE, PEN, MARKER, SHAPE, FILL, ERASER, SELECT, STATUS, CALLOUT, PIPETTE }

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
        if (intent.getBooleanExtra(EXTRA_SHOW_SPEED, false)) {
            showFrameMs = true
            view.post { view.fitAll(); handler.post(frameTicker) }
        }
    }

    override fun onPause() {
        current?.let { commit(it) }
        saveNow(sync = true)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        engine.close()
        io.shutdown()
        saver.shutdown() // уже начатые сохранения дописываются
        images.close()
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
        addTool(row, Tool.FILL, R.drawable.ic_fill, "Заливка")
        addTool(row, Tool.ERASER, R.drawable.ic_eraser, "Ластик")
        addTool(row, Tool.SELECT, R.drawable.ic_lasso, "Выделить")
        addTool(row, Tool.STATUS, R.drawable.ic_status, "Метка")
        addTool(row, Tool.CALLOUT, R.drawable.ic_callout, "Выноска")
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
        row.addView(Ui.ToolButton(this, R.drawable.ic_statuses, "Статусы").apply {
            tag = "board_statuses"
            setOnClickListener { statusesDialog() }
        })
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
        if (t == Tool.STATUS) hint("Коснитесь пером — метка статуса. Касание метки — следующий статус, подержать — список", 3500)
        if (t == Tool.FILL) hint("Обведите пером захватку — она зальётся выбранным цветом", 3500)
        if (t == Tool.CALLOUT) hint("Проведите пером от места на чертеже туда, где будет надпись. От надписи — ещё указатель", 4000)
        if (t == Tool.SELECT) hint("Обведите, что выделить. Касание — один объект. Доску двигать — двумя пальцами", 3500)
        if (t == Tool.SHAPE) hint("Нарисуйте линию, стрелку, квадрат, круг или треугольник — станет ровной фигурой", 3500)
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

    private fun hint(text: String, ms: Long = 2600) {
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
    private fun pushUndo() {
        undoStack.addLast(board.snapshot())
        while (undoStack.size > 60) undoStack.removeFirst()
        redoStack.clear()
    }

    private fun restore(snap: BoardSnapshot) {
        board = board.restored(snap)
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
        redoStack.addLast(board.snapshot())
        restore(undoStack.removeLast())
    }

    private fun redo() {
        if (redoStack.isEmpty() || committing.isNotEmpty() || current != null) return
        undoStack.addLast(board.snapshot())
        restore(redoStack.removeLast())
    }

    private fun changed() {
        view.pruneSelection()
        pipetteSnap = null
        view.invalidate()
        handler.removeCallbacks(saveRunnable)
        handler.postDelayed(saveRunnable, 500)
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
        val json = board.toJsonString()
        val meta = board.metaJson()
        val write = Runnable {
            try {
                store.write(id, seq, json, meta)
            } catch (t: Throwable) {
                handler.post { Toast.makeText(this, "Не удалось сохранить: ${t.message}", Toast.LENGTH_LONG).show() }
            }
        }
        if (sync) write.run() else try { saver.execute(write) } catch (_: Throwable) { write.run() }
    }

    // ---------------------------------------------------------------- картинки

    private fun pickImage() {
        current?.let { commit(it) }
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
        if (requestCode != REQ_IMAGE || resultCode != RESULT_OK || data == null) return
        val uris = ArrayList<android.net.Uri>()
        data.clipData?.let { c -> for (k in 0 until c.itemCount) c.getItemAt(k).uri?.let { uris += it } }
        if (uris.isEmpty()) data.data?.let { uris += it }
        if (uris.isEmpty()) return
        hint(if (uris.size == 1) "Добавляю картинку…" else "Добавляю картинки: ${uris.size}…", 1500)
        io.execute {
            val blocks = uris.mapNotNull { u -> try { BoardImages.import(this, u) } catch (t: Throwable) { null } }
            handler.post { placeImages(blocks, uris.size) }
        }
    }

    /** Разложить новые картинки рядами (до 4 в ряд) в середине экрана и выделить их. */
    private fun placeImages(blocks: List<Triple<String, Int, Int>>, asked: Int) {
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
        if (blocks.size < asked) hint("Добавлено ${blocks.size} из $asked — остальные не открылись")
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
    override fun fingerWrites(): Boolean = fingerWrites

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
        if (style.kind == "write") {
            val g = current ?: Group().also { current = it }
            g.strokes += stroke
            handler.removeCallbacks(interimRunnable); handler.removeCallbacks(commitRunnable)
            handler.postDelayed(interimRunnable, 180)
            handler.postDelayed(commitRunnable, pauseMs)
        } else if (style.kind == "fill") {
            createFill(stroke)
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
        if (keep.size != board.strokes.size || fi >= 0 || (h != null && h.kind != BoardView.Kind.IMAGE)) {
            if (!eraseUndoPushed) { pushUndo(); eraseUndoPushed = true }
            if (keep.size != board.strokes.size) { board.strokes.clear(); board.strokes.addAll(keep); changedAny = true }
            when (h?.kind) {
                BoardView.Kind.TEXT -> { board.texts.removeAt(h.index); changedAny = true }
                BoardView.Kind.STICKER -> { board.stickers.removeAt(h.index); changedAny = true }
                BoardView.Kind.CALLOUT -> { board.callouts.removeAt(h.index); changedAny = true }
                else -> Unit
            }
            // Заливка стирается касанием её края (внутри можно стирать рисунки поверх неё).
            if (fi >= 0 && fi < board.fills.size) { board.fills.removeAt(fi); changedAny = true }
        }
        if (changedAny) changed()
    }

    override fun onStatusTap(x: Float, y: Float) {
        val h = view.hitAt(x, y)
        if (h?.kind == BoardView.Kind.CALLOUT) {
            val c = board.callouts[h.index]
            if (view.inStatusRow(c, x, y)) onCalloutStatusTap(c) else view.select(listOf(c))
            return
        }
        pushUndo()
        if (h?.kind == BoardView.Kind.STICKER) {
            val s = board.stickers[h.index]
            s.sid = board.nextStatus(s.sid)
        } else {
            // Метка ставится «в рост» текущего масштаба: на экране около 128×36 dp; статус — первый в списке.
            val scale = resources.displayMetrics.density / board.camZ
            val s = BoardSticker(0f, 0f, board.status("").id, scale)
            s.x = x - view.stickerW(s) / 2f; s.y = y - s.h / 2f
            board.stickers += s
        }
        changed()
    }

    override fun onStickerTap(index: Int) {
        val s = board.stickers.getOrNull(index) ?: return
        pushUndo()
        s.sid = board.nextStatus(s.sid)
        changed()
    }

    override fun onCalloutStatusTap(c: BoardCallout) {
        pushUndo()
        c.sid = board.nextStatus(c.sid)
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
            hint("Ещё указатель — проведите пером от выноски. Касание строки статуса — следующий статус", 4000)
        }
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
            sel[0] is BoardCallout -> "Выноска"
            sel[0] is BoardFill -> "Заливка"
            else -> "Рисунок"
        }
        val buttons = ArrayList<TextView>()
        if (sel.size == 1 && sel[0] is BoardText) {
            buttons += Ui.pill(this, "Изменить", Ui.INK, Ui.DESK).apply { tag = "sel_edit"; setOnClickListener { editText(sel[0] as BoardText) } }
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
                    setTool(Tool.CALLOUT)
                    hint("Проведите пером от выноски к месту на чертеже", 3500)
                }
            }
        }
        if (sel.size == 1 && sel[0] is BoardSticker) {
            buttons += Ui.pill(this, "Статус", Ui.INK, Ui.DESK).apply { tag = "sel_status"; setOnClickListener { chooseStatus(sel[0]) } }
        }
        if (sel.any { it is Drawing && isClosed(it) }) {
            buttons += Ui.pill(this, "Залить", Ui.INK, Ui.DESK).apply { tag = "sel_fill"; setOnClickListener { fillSelectedShapes() } }
        }
        buttons += Ui.pill(this, "Копия", Ui.INK, Ui.DESK).apply { tag = "sel_copy"; setOnClickListener { duplicateSelection() } }
        if (sel.any { it is BoardText || it is Drawing || it is BoardFill }) {
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
                is BoardSticker -> BoardSticker(o.x + off, o.y + off, o.sid, o.scale).also { board.stickers += it; copies += it }
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
        pushUndo()
        board.texts.removeAll { t -> sel.any { it === t } }
        board.images.removeAll { m -> sel.any { it === m } }
        board.stickers.removeAll { s -> sel.any { it === s } }
        board.strokes.removeAll { d -> sel.any { it === d } }
        board.fills.removeAll { f -> sel.any { it === f } }
        board.callouts.removeAll { c -> sel.any { it === c } }
        view.clearSelection()
        changed()
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
        val current = when (obj) { is BoardSticker -> obj.sid; is BoardCallout -> obj.sid; else -> return }
        val names = board.statuses.map { (if (it.id == current) "● " else "   ") + it.name } + "Настроить статусы…"
        AlertDialog.Builder(this)
            .setTitle("Статус")
            .setItems(names.toTypedArray()) { _, which ->
                if (which == board.statuses.size) { statusesDialog(); return@setItems }
                val sid = board.statuses[which].id
                pushUndo()
                when (obj) { is BoardSticker -> obj.sid = sid; is BoardCallout -> obj.sid = sid }
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
            box.addView(Ui.text(this, "Касание статуса на метке или выноске переключает их по этому списку сверху вниз.", 13f, Ui.MUTED)
                .apply { setPadding(0, 0, 0, dp(8f)) })
            for ((i, st) in board.statuses.withIndex()) {
                val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; tag = "status_row" }
                row.addView(dot(st.color), LinearLayout.LayoutParams(dp(18f), dp(18f)))
                row.addView(Ui.text(this, st.name, 16f, Ui.INK).apply { setPadding(dp(10f), 0, dp(6f), 0) },
                    LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
                row.addView(Ui.mono(this, board.countStatus(st.id).toString(), 13f))
                row.addView(small("▲") {
                    if (i > 0) { pushUndo(); board.statuses.add(i - 1, board.statuses.removeAt(i)); changed(); render() }
                })
                row.addView(small("▼") {
                    if (i < board.statuses.size - 1) { pushUndo(); board.statuses.add(i + 1, board.statuses.removeAt(i)); changed(); render() }
                })
                row.addView(small("✎") { editStatusDialog(st) { render() } })
                row.addView(small("✕", Ui.BAD) { deleteStatus(st) { render() } })
                box.addView(row, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
            }
            box.addView(Ui.pill(this, "+ Добавить статус", Color.WHITE, Ui.ACCENT).apply {
                tag = "status_add"
                setOnClickListener { editStatusDialog(null) { render() } }
            }, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { topMargin = dp(10f); bottomMargin = dp(6f) })
        }
        render()
        dialog.show()
    }

    /** Новый статус (st = null) или правка: название и цвет из палитры. */
    private fun editStatusDialog(st: BoardStatus?, done: (BoardStatus) -> Unit) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20f), dp(8f), dp(20f), 0) }
        val name = EditText(this).apply {
            setText(st?.name.orEmpty())
            hint = "Например: Сдали опалубку"
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
        AlertDialog.Builder(this)
            .setTitle(if (st == null) "Новый статус" else "Статус")
            .setView(box)
            .setPositiveButton("Готово") { _, _ ->
                val n = name.text.toString().trim()
                if (n.isEmpty()) return@setPositiveButton
                pushUndo()
                val result = if (st == null) BoardStatus(Board.newStatusId(), n, color).also { board.statuses += it }
                else st.also { it.name = n; it.color = color }
                changed()
                done(result)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun deleteStatus(st: BoardStatus, done: () -> Unit) {
        if (board.statuses.size <= 1) { hint("Должен остаться хотя бы один статус"); return }
        val used = board.countStatus(st.id)
        val rest = board.statuses.first { it.id != st.id }
        val msg = if (used == 0) "Удалить статус «${st.name}»?"
        else "Статус «${st.name}» стоит у $used меток и выносок. Удалить? Они станут «${rest.name}»."
        AlertDialog.Builder(this)
            .setMessage(msg)
            .setPositiveButton("Удалить") { _, _ ->
                pushUndo()
                for (s in board.stickers) if (s.sid == st.id) s.sid = rest.id
                for (c in board.callouts) if (c.sid == st.id) c.sid = rest.id
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
        box.addView(Ui.text(this, "Статус", 13f, Ui.MUTED).apply { setPadding(0, dp(12f), 0, dp(4f)) })
        var sid = c?.sid ?: board.status("").id
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(list)
        lateinit var render: () -> Unit
        render = {
            list.removeAllViews()
            for (st in board.statuses) {
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
                setOnClickListener { editStatusDialog(null) { st -> sid = st.id; render() } }
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
        /** Сразу показывать скорость отрисовки (тестовая доска). */
        const val EXTRA_SHOW_SPEED = "show_speed"
        private const val REQ_IMAGE = 42
    }
}
