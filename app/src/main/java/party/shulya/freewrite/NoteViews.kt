package party.shulya.freewrite

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Задать высоту строки текста ровно в px (чтобы текст ложился на линовку). */
fun TextView.setExactLineHeight(px: Int) {
    if (Build.VERSION.SDK_INT >= 28) {
        lineHeight = px
    } else {
        val fm = paint.fontMetricsInt
        setLineSpacing((px - (fm.descent - fm.ascent)).toFloat(), 1f)
    }
    includeFontPadding = false
}

/**
 * Лист записи: вертикальный список блоков на бумаге с линовкой.
 * Линии рисуются точно под строками текста (у видов с тегом RULED) и продолжаются
 * с шагом lineGap ниже последней строки — как в бумажном ежедневнике.
 */
class RuledSheet(context: Context) : LinearLayout(context) {
    var lineGap = Ui.dpf(context, 36f)
    private val linePaint = Paint().apply { color = Ui.LINE; strokeWidth = Ui.dpf(context, 1f) }
    private val marginPaint = Paint().apply { color = Ui.MARGIN; strokeWidth = Ui.dpf(context, 1f) }
    private val under = Ui.dpf(context, 4f)

    init {
        orientation = VERTICAL
        setWillNotDraw(false)
        clipToPadding = false // уголки выбранной картинки заходят на поле листа
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = paddingLeft - Ui.dpf(context, 10f)
        val right = (width - paddingRight).toFloat() + Ui.dpf(context, 10f)
        val marginX = paddingLeft - Ui.dpf(context, 16f)
        canvas.drawLine(marginX, 0f, marginX, height.toFloat(), marginPaint)
        var lastY = -1f
        forEachRuledText(this, 0f) { tv, top ->
            val layout = tv.layout ?: return@forEachRuledText
            val descent = tv.paint.fontMetrics.descent
            for (i in 0 until layout.lineCount) {
                val y = top + tv.paddingTop + layout.getLineBaseline(i) + descent + under
                canvas.drawLine(left, y, right, y, linePaint)
                lastY = max(lastY, y)
            }
        }
        var y = if (lastY < 0) paddingTop + lineGap else lastY + lineGap
        while (y < height - paddingBottom / 2f) {
            canvas.drawLine(left, y, right, y, linePaint)
            y += lineGap
        }
    }

    private fun forEachRuledText(group: ViewGroup, offsetY: Float, fn: (TextView, Float) -> Unit) {
        for (i in 0 until group.childCount) {
            val c = group.getChildAt(i)
            if (c.visibility != VISIBLE) continue
            val top = offsetY + c.top
            if (c is TextView && c.tag == RULED) fn(c, top)
            else if (c is ViewGroup) forEachRuledText(c, top, fn)
        }
    }

    companion object {
        const val RULED = "ruled"
    }
}

/**
 * Прозрачный слой пера поверх листа.
 *
 * Перо (и мышь) рисуют здесь. Касания пальцем передаются листу под слоем: так работают прокрутка
 * с инерцией, нажатия на галочки и перенос курсора. Если сначала легла ладонь, а потом коснулось
 * перо, жест ладони отменяется и начинается штрих пера.
 * Координаты штрихов — в системе листа (контента), чтобы рисунок двигался вместе с текстом.
 */
@SuppressLint("ViewConstructor")
class NoteInkView(context: Context, private val host: Host) : View(context) {

    interface Host {
        /** Где начало листа относительно этого слоя (учитывает прокрутку). */
        fun contentOffset(): PointF
        /** Передать касание пальца листу. */
        fun forwardToSheet(ev: MotionEvent)
        fun passThrough(): Boolean
        /** Включена пипетка: касание (пером или пальцем) берёт цвет. */
        fun pipetteMode(): Boolean
        fun pipetteColor(x: Float, y: Float): Int?
        fun onPipettePick(x: Float, y: Float)
        fun fingerWrites(): Boolean
        fun isEraser(): Boolean
        fun strokeStyle(): StrokeStyle
        fun onStrokeStart(x: Float, y: Float)
        fun onStrokeEnd(stroke: InkStroke, style: StrokeStyle, tap: Boolean)
        fun onErase(x: Float, y: Float)
        fun drawings(): List<Drawing>
        fun pendingStrokes(): List<InkStroke>
    }

    class StrokeStyle(val kind: String, val color: Int, val width: Float)

    private enum class Mode { NONE, FORWARD, INK, ERASE, PIPETTE }

    private var mode = Mode.NONE
    private var activePointer = MotionEvent.INVALID_POINTER_ID
    private var live: InkStroke? = null
    private var liveStyle: StrokeStyle? = null
    private var downTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var clockOffset = 0L
    private var hover: PointF? = null
    private val fading = ArrayList<Pair<List<InkStroke>, Long>>()
    private var fadingStyle = StrokeStyle("pen", Ui.INK, Ui.dpf(context, 2.6f))

    // Перо над экраном: маленькая точка цвета чернил (как у пера Samsung),
    // у ластика — тонкий серый круг размером со стирающую область.
    private val hoverDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val hoverRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(140, 133, 146, 168)
        strokeWidth = Ui.dpf(context, 1f)
    }

    init {
        // Не показывать системный указатель пера поверх нашего — иначе два курсора.
        if (Build.VERSION.SDK_INT >= 24) {
            pointerIcon = android.view.PointerIcon.getSystemIcon(context, android.view.PointerIcon.TYPE_NULL)
        }
    }

    /** Плавно убрать штрихи, которые стали текстом. */
    fun fade(strokes: List<InkStroke>, style: StrokeStyle) {
        fadingStyle = style
        fading += strokes to SystemClock.uptimeMillis()
        postInvalidateOnAnimation()
    }

    private fun isPen(tool: Int) = tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER

    private fun inks(tool: Int): Boolean =
        !host.passThrough() && (isPen(tool) || tool == MotionEvent.TOOL_TYPE_MOUSE || host.fingerWrites())

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (host.pipetteMode() || mode == Mode.PIPETTE) return pipetteTouch(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val tool = ev.getToolType(0)
                if (inks(tool)) {
                    startInk(ev, 0)
                } else {
                    mode = Mode.FORWARD
                    host.forwardToSheet(ev)
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = ev.actionIndex
                if ((mode == Mode.FORWARD || mode == Mode.NONE) && isPen(ev.getToolType(idx)) && !host.passThrough()) {
                    // Ладонь легла раньше пера (или осталась лежать после прошлого штриха):
                    // отменяем жест ладони, пишем пером.
                    if (mode == Mode.FORWARD) {
                        val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                        host.forwardToSheet(cancel)
                        cancel.recycle()
                    }
                    startInk(ev, idx)
                } else if (mode == Mode.FORWARD) {
                    host.forwardToSheet(ev)
                }
            }
            MotionEvent.ACTION_MOVE -> when (mode) {
                Mode.FORWARD -> host.forwardToSheet(ev)
                Mode.INK, Mode.ERASE -> moveInk(ev)
                Mode.NONE, Mode.PIPETTE -> Unit
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (mode == Mode.FORWARD) {
                    host.forwardToSheet(ev)
                    if (ev.actionMasked == MotionEvent.ACTION_UP) mode = Mode.NONE
                } else if ((mode == Mode.INK || mode == Mode.ERASE) && ev.getPointerId(ev.actionIndex) == activePointer) {
                    finishInk(ev, ev.actionIndex)
                }
                if (ev.actionMasked == MotionEvent.ACTION_UP) mode = Mode.NONE
            }
            MotionEvent.ACTION_CANCEL -> {
                if (mode == Mode.FORWARD) host.forwardToSheet(ev)
                live = null
                mode = Mode.NONE
                invalidate()
            }
        }
        return true
    }

    /** Пипетка: под пальцем/пером кружок с цветом, при отпускании цвет берётся. */
    private fun pipetteTouch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                mode = Mode.PIPETTE
                parent?.requestDisallowInterceptTouchEvent(true)
                hover = PointF(ev.x, ev.y)
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                mode = Mode.NONE
                hover = null
                host.onPipettePick(ev.x, ev.y)
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                mode = Mode.NONE
                hover = null
                invalidate()
            }
        }
        return true
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        if (host.pipetteMode()) {
            hover = if (event.actionMasked == MotionEvent.ACTION_HOVER_EXIT) null else PointF(event.x, event.y)
            invalidate()
            return true
        }
        if (!isPen(event.getToolType(0)) || host.passThrough()) return super.onHoverEvent(event)
        hover = if (event.actionMasked == MotionEvent.ACTION_HOVER_EXIT) null else PointF(event.x, event.y)
        invalidate()
        return true
    }

    private fun startInk(ev: MotionEvent, idx: Int) {
        parent?.requestDisallowInterceptTouchEvent(true)
        requestUnbufferedDispatch(ev)
        activePointer = ev.getPointerId(idx)
        val o = host.contentOffset()
        val x = ev.getX(idx) - o.x
        val y = ev.getY(idx) - o.y
        downTime = SystemClock.uptimeMillis()
        downX = ev.getX(idx)
        downY = ev.getY(idx)
        hover = null
        if (host.isEraser() || ev.getToolType(idx) == MotionEvent.TOOL_TYPE_ERASER) {
            mode = Mode.ERASE
            host.onErase(x, y)
            return
        }
        mode = Mode.INK
        host.onStrokeStart(x, y)
        clockOffset = System.currentTimeMillis() - SystemClock.uptimeMillis()
        val s = InkStroke(ev.getToolType(idx))
        s.points.add(InkPoint(x, y, ev.eventTime + clockOffset, pressureOf(ev, idx, -1)))
        live = s
        liveStyle = host.strokeStyle()
        invalidate()
    }

    private fun pressureOf(ev: MotionEvent, idx: Int, h: Int): Float {
        if (!isPen(ev.getToolType(idx))) return 0.5f
        val p = if (h < 0) ev.getPressure(idx) else ev.getHistoricalPressure(idx, h)
        // На MatePad Mini нажим доходит примерно до 0,6 — растягиваем до 0..1.
        return (p / 0.6f).coerceIn(0f, 1f)
    }

    private fun moveInk(ev: MotionEvent) {
        val idx = ev.findPointerIndex(activePointer)
        if (idx < 0) return
        val o = host.contentOffset()
        if (mode == Mode.ERASE) {
            for (h in 0 until ev.historySize) host.onErase(ev.getHistoricalX(idx, h) - o.x, ev.getHistoricalY(idx, h) - o.y)
            host.onErase(ev.getX(idx) - o.x, ev.getY(idx) - o.y)
            return
        }
        val s = live ?: return
        for (h in 0 until ev.historySize) {
            add(s, ev.getHistoricalX(idx, h) - o.x, ev.getHistoricalY(idx, h) - o.y, ev.getHistoricalEventTime(h), pressureOf(ev, idx, h))
        }
        add(s, ev.getX(idx) - o.x, ev.getY(idx) - o.y, ev.eventTime, pressureOf(ev, idx, -1))
        invalidate()
    }

    private fun add(s: InkStroke, x: Float, y: Float, t: Long, p: Float) {
        val last = s.points[s.points.lastIndex]
        if (last.x == x && last.y == y) return
        s.points.add(InkPoint(x, y, t + clockOffset, p))
    }

    private fun finishInk(ev: MotionEvent, idx: Int) {
        if (mode == Mode.ERASE) {
            mode = Mode.NONE
            return
        }
        val s = live ?: return
        val o = host.contentOffset()
        add(s, ev.getX(idx) - o.x, ev.getY(idx) - o.y, ev.eventTime, pressureOf(ev, idx, -1))
        val tap = SystemClock.uptimeMillis() - downTime < 280 && hypot(ev.getX(idx) - downX, ev.getY(idx) - downY) < Ui.dpf(context, 4f)
        live = null
        mode = Mode.NONE
        host.onStrokeEnd(s, liveStyle ?: host.strokeStyle(), tap)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val o = host.contentOffset()
        canvas.save()
        canvas.translate(o.x, o.y)
        for (d in host.drawings()) drawDrawing(canvas, d)
        val pendingStyle = StrokeStyle("write", Ui.INK, Ui.dpf(context, 2.6f))
        for (s in host.pendingStrokes()) drawStroke(canvas, s, pendingStyle, 255)
        live?.let { drawStroke(canvas, it, liveStyle ?: pendingStyle, 255) }
        val now = SystemClock.uptimeMillis()
        fading.removeAll { now - it.second > FADE_MS }
        for ((strokes, t0) in fading) {
            val a = (255 * (1f - (now - t0).toFloat() / FADE_MS)).toInt().coerceIn(0, 255)
            for (s in strokes) drawStroke(canvas, s, fadingStyle, a)
        }
        canvas.restore()
        hover?.let {
            if (host.pipetteMode() || mode == Mode.PIPETTE) {
                drawPipetteRing(canvas, it.x, it.y, host.pipetteColor(it.x, it.y))
            } else if (host.isEraser()) {
                canvas.drawCircle(it.x, it.y, Ui.dpf(context, 14f), hoverRing)
            } else {
                hoverDot.color = host.strokeStyle().color
                hoverDot.alpha = 170
                canvas.drawCircle(it.x, it.y, Ui.dpf(context, 2.5f), hoverDot)
            }
        }
        if (fading.isNotEmpty()) postInvalidateOnAnimation()
    }

    private val painter = InkPainter()

    private fun drawPipetteRing(canvas: Canvas, x: Float, y: Float, color: Int?) = pipetteRing(context, canvas, x, y, color)

    private fun drawStroke(canvas: Canvas, s: InkStroke, style: StrokeStyle, alpha: Int) = painter.stroke(canvas, s, style, alpha)

    private fun drawDrawing(canvas: Canvas, d: Drawing) = painter.drawing(canvas, d)

    companion object {
        const val FADE_MS = 260L

        fun toDrawing(s: InkStroke, style: StrokeStyle): Drawing {
            val arr = FloatArray(s.points.size * 3)
            s.points.forEachIndexed { i, p ->
                arr[i * 3] = p.x; arr[i * 3 + 1] = p.y; arr[i * 3 + 2] = p.pressure
            }
            val kind = if (style.kind == "write" || style.kind == "shape") "pen" else style.kind
            return Drawing(kind, style.color, style.width, arr)
        }

        /** Рамка штрихов: [left, top, right, bottom]. */
        fun bounds(strokes: List<InkStroke>): FloatArray {
            var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
            for (s in strokes) for (p in s.points) { l = min(l, p.x); t = min(t, p.y); r = max(r, p.x); b = max(b, p.y) }
            return floatArrayOf(l, t, r, b)
        }
    }
}

/**
 * Картинка в записи.
 *
 * Касание пальцем — выбрать: рамка с уголками. Тянуть за уголок — размер (пропорции сохраняются,
 * противоположный край стоит на месте), за саму картинку — сдвинуть вбок или перенести выше/ниже
 * по тексту (место выбирается, когда отпустили). Долгое нажатие — удалить.
 * Вид шире текста на EDGE_DP с каждой стороны (отрицательные поля), чтобы уголки у края листа
 * были видны и за них было удобно браться; картинка внутри стоит по block.xa и шириной block.wf.
 */
@SuppressLint("ViewConstructor")
class NoteImageView(
    context: Context,
    val block: Block.Image,
    private val bitmap: android.graphics.Bitmap?,
    private val host: Host,
) : View(context) {

    interface Host {
        fun isImageSelected(b: Block.Image): Boolean
        fun onImageTap(v: NoteImageView)
        fun onImageLongPress(v: NoteImageView)
        /** Начали менять размер или двигать — запомнить для «Отменить». */
        fun onImageEditStart(v: NoteImageView)
        fun onImageResized(v: NoteImageView)
        /** Отпустили после перетаскивания: translationY — на сколько сдвинута по вертикали. */
        fun onImageDropped(v: NoteImageView)
    }

    private enum class Mode { NONE, TAP, RESIZE, MOVE }

    private val pad = Ui.dpf(context, 8f)
    private val edge = Ui.dpf(context, EDGE_DP)
    private val autoMax = Ui.dpf(context, 560f)
    private val minW = Ui.dpf(context, 80f)
    private val grab = Ui.dpf(context, 28f)
    private val slop = Ui.dpf(context, 8f)
    private val aspect = if (block.width > 0 && block.height > 0) block.height.toFloat() / block.width else 0.75f
    private val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val frame = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        color = Ui.ACCENT
        strokeWidth = Ui.dpf(context, 2f)
    }
    private val r = android.graphics.RectF()

    private var mode = Mode.NONE
    private var downRawX = 0f
    private var downRawY = 0f
    private var startW = 0f
    private var startLeft = 0f
    private var cornerRight = true
    private var cornerBottom = true
    private var edited = false
    private val longPress = Runnable {
        if (mode == Mode.TAP || (mode == Mode.MOVE && !edited)) {
            mode = Mode.NONE
            parent?.requestDisallowInterceptTouchEvent(false)
            host.onImageLongPress(this)
        }
    }

    init {
        contentDescription = "Картинка"
    }

    /** Ширина картинки на листе (px) при ширине текста W. */
    fun imageWidth(W: Int): Float {
        if (W <= 0) return 0f
        return if (block.wf > 0f) (block.wf * W).coerceIn(min(minW, W.toFloat()), W.toFloat())
        else min(W.toFloat(), min(autoMax, if (block.width > 0) block.width.toFloat() else autoMax))
    }

    /** Ширина текста (без запаса по краям). */
    private fun textWidth(viewWidth: Int = width): Int = (viewWidth - 2 * edge).toInt().coerceAtLeast(0)

    /** Где картинка внутри вида. */
    fun imageRect(out: android.graphics.RectF = r): android.graphics.RectF {
        val tw = textWidth()
        val w = imageWidth(tw)
        val left = edge + block.xa.coerceIn(0f, 1f) * (tw - w)
        out.set(left, pad, left + w, pad + w * aspect)
        return out
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (imageWidth(textWidth(w)) * aspect + 2 * pad).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val rect = imageRect()
        if (bitmap != null) {
            canvas.drawBitmap(bitmap, null, rect, paint)
        } else {
            fill.color = Ui.DESK
            canvas.drawRect(rect, fill)
        }
        if (host.isImageSelected(block)) {
            val p = Ui.dpf(context, 2f)
            canvas.drawRect(rect.left - p, rect.top - p, rect.right + p, rect.bottom + p, frame)
            val hr = Ui.dpf(context, 8f)
            for (x in floatArrayOf(rect.left, rect.right)) for (y in floatArrayOf(rect.top, rect.bottom)) {
                fill.color = Color.WHITE
                canvas.drawCircle(x, y, hr, fill)
                canvas.drawCircle(x, y, hr, frame)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val rect = imageRect()
                downRawX = ev.rawX
                downRawY = ev.rawY
                edited = false
                startW = rect.width()
                startLeft = rect.left
                if (host.isImageSelected(block)) {
                    var corner = false
                    for (x in floatArrayOf(rect.left, rect.right)) for (y in floatArrayOf(rect.top, rect.bottom)) {
                        if (!corner && hypot(ev.x - x, ev.y - y) <= grab) {
                            corner = true
                            cornerRight = x == rect.right
                            cornerBottom = y == rect.bottom
                        }
                    }
                    if (corner) {
                        mode = Mode.RESIZE
                    } else if (rect.contains(ev.x, ev.y)) {
                        mode = Mode.MOVE
                        postDelayed(longPress, 550)
                    } else {
                        return false
                    }
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                if (!rect.contains(ev.x, ev.y)) return false
                mode = Mode.TAP
                postDelayed(longPress, 550)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX - downRawX
                val dy = ev.rawY - downRawY
                val far = hypot(dx, dy) > slop
                when (mode) {
                    Mode.TAP -> if (far) removeCallbacks(longPress)
                    Mode.RESIZE -> if (far || edited) {
                        if (!edited) { edited = true; host.onImageEditStart(this) }
                        resizeBy(dx, dy)
                    }
                    Mode.MOVE -> if (far || edited) {
                        removeCallbacks(longPress)
                        if (!edited) { edited = true; host.onImageEditStart(this); elevation = Ui.dpf(context, 8f) }
                        val w = imageWidth(textWidth())
                        val free = textWidth() - w
                        val left = (startLeft - edge + dx).coerceIn(0f, max(0f, free))
                        block.xa = if (free > 0.5f) left / free else 0f
                        translationY = dy
                        invalidate()
                    }
                    Mode.NONE -> Unit
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                parent?.requestDisallowInterceptTouchEvent(false)
                val wasTap = mode == Mode.TAP && ev.actionMasked == MotionEvent.ACTION_UP &&
                    hypot(ev.rawX - downRawX, ev.rawY - downRawY) <= slop
                val m = mode
                mode = Mode.NONE
                elevation = 0f
                when {
                    wasTap -> host.onImageTap(this)
                    m == Mode.MOVE && !edited && ev.actionMasked == MotionEvent.ACTION_UP -> host.onImageTap(this)
                    m == Mode.RESIZE && edited -> host.onImageResized(this)
                    m == Mode.MOVE && edited -> host.onImageDropped(this)
                }
            }
        }
        return true
    }

    /** Уголок сдвинули на (dx, dy): ширина меняется вдоль диагонали, противоположный край на месте. */
    private fun resizeBy(dx: Float, dy: Float) {
        val W = textWidth().toFloat()
        if (W <= 0f) return
        val sx = if (cornerRight) 1f else -1f
        val sy = if (cornerBottom) 1f else -1f
        val h = startW * aspect
        val diag = hypot(startW, h)
        val t = (dx * sx * startW + dy * sy * h) / diag // насколько угол ушёл наружу по диагонали
        val newW = (startW + t * startW / diag).coerceIn(min(minW, W), W)
        val l0 = startLeft - edge // левый край относительно текста
        val left = if (cornerRight) min(l0, W - newW) else (l0 + startW - newW).coerceAtLeast(0f)
        block.wf = newW / W
        block.xa = if (W - newW > 0.5f) (left / (W - newW)).coerceIn(0f, 1f) else 0f
        requestLayout()
        invalidate()
    }

    companion object {
        /** Запас по краям вида картинки (dp) — для уголков у края листа. */
        const val EDGE_DP = 14f
    }
}

private val pipettePaint = Paint(Paint.ANTI_ALIAS_FLAG)

/**
 * Кружок пипетки: сверху над точкой (чтобы палец не закрывал), залит цветом под точкой,
 * в центре — крестик-прицел.
 */
fun pipetteRing(context: Context, canvas: Canvas, x: Float, y: Float, color: Int?) {
    val r = Ui.dpf(context, 22f)
    val cy = y - Ui.dpf(context, 46f)
    pipettePaint.style = Paint.Style.FILL
    pipettePaint.color = color ?: Color.TRANSPARENT
    canvas.drawCircle(x, cy, r, pipettePaint)
    pipettePaint.style = Paint.Style.STROKE
    pipettePaint.strokeWidth = Ui.dpf(context, 3f)
    pipettePaint.color = Color.WHITE
    canvas.drawCircle(x, cy, r, pipettePaint)
    pipettePaint.strokeWidth = Ui.dpf(context, 1f)
    pipettePaint.color = Ui.INK2
    canvas.drawCircle(x, cy, r + Ui.dpf(context, 1.5f), pipettePaint)
    val c = Ui.dpf(context, 6f)
    canvas.drawLine(x - c, y, x + c, y, pipettePaint)
    canvas.drawLine(x, y - c, x, y + c, pipettePaint)
}
