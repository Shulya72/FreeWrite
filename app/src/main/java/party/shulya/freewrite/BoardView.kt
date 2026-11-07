package party.shulya.freewrite

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Поле доски: бесконечная плоскость с камерой (сдвиг + масштаб).
 *
 * Перо (и мышь) — инструмент: писать, рисовать, стирать, ставить метки.
 * Палец — навигация: один палец двигает доску, два — масштабируют; короткое касание метки
 * переключает её статус. Если сначала легла ладонь, а потом коснулось перо, — работает перо.
 *
 * Выделение: касание пальцем по надписи, штриху, фигуре, стрелке или картинке выбирает её;
 * инструмент «Выделить» — обвести пером или пальцем (лассо) несколько объектов.
 * Выделенное тянут (палец — при любом инструменте), уголки рамки — размер, два пальца на выделенном —
 * масштаб. Долгое нажатие на картинку — удалить.
 */
@SuppressLint("ViewConstructor")
class BoardView(context: Context, private val host: Host) : View(context) {

    interface Host {
        fun board(): Board
        fun tool(): BoardActivity.Tool
        fun fingerWrites(): Boolean
        /** Стиль штриха в пикселях экрана (BoardView переводит в единицы доски). */
        fun strokeStyle(): NoteInkView.StrokeStyle
        fun onStrokeStart(x: Float, y: Float)
        fun onStrokeEnd(stroke: InkStroke, style: NoteInkView.StrokeStyle, tap: Boolean)
        fun onErase(x: Float, y: Float, first: Boolean)
        fun onStatusTap(x: Float, y: Float)
        fun onStickerTap(index: Int)
        /** Перед изменением объектов жестом (для «Отменить»). */
        fun onDragStart()
        /** После жеста — сохранить. */
        fun onDragEnd()
        fun onImageLongPress(index: Int)
        fun onSelectionChanged()
        fun onCameraChanged()
        fun pendingStrokes(): List<InkStroke>
        /** Серое промежуточное слово над недописанной надписью: рамка [l, t, r, b] и текст. */
        fun interim(): Pair<FloatArray, String>?
        fun bitmap(img: BoardImage): Bitmap?
        /** Цвет под точкой экрана — для пипетки. */
        fun pipetteColor(sx: Float, sy: Float): Int?
        fun onPipettePick(sx: Float, sy: Float)
    }

    enum class Kind { STICKER, TEXT, IMAGE, STROKE }
    class Hit(val kind: Kind, val index: Int)

    private enum class Mode { NONE, PAN, PINCH, INK, ERASE, STATUS, MOVE_SEL, RESIZE, PINCH_SEL, LASSO, PIPETTE }

    private var mode = Mode.NONE
    private var activePointer = MotionEvent.INVALID_POINTER_ID
    private var activeIsPen = false
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var moved = false
    private var lastX = 0f
    private var lastY = 0f
    private var pinchA = -1
    private var pinchB = -1
    private var pinchD0 = 1f
    private var pinchZ0 = 1f
    private val pinchWorld = PointF()
    private var live: InkStroke? = null
    private var liveStyle: NoteInkView.StrokeStyle? = null
    private var clockOffset = 0L
    private var hover: PointF? = null
    private val fading = ArrayList<Pair<List<InkStroke>, Long>>()
    private val slop = Ui.dpf(context, 8f)
    /** Рисуем без служебных значков (рамки выделения, кружка пипетки) — для снимка пипетки. */
    private var plain = false

    private val painter = InkPainter()
    private val gridPaint = Paint().apply { color = Ui.LINE; strokeWidth = 0f; style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Ui.ACCENT }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val hoverDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val hoverRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.argb(140, 133, 146, 168); strokeWidth = Ui.dpf(context, 1f)
    }
    private val rect = RectF()
    private val rect2 = RectF()
    private val lassoPath = Path()

    private val longPress = Runnable {
        if ((mode == Mode.PAN || mode == Mode.MOVE_SEL) && !moved) {
            val h = hitAt(wx(downX), wy(downY))
            if (h?.kind == Kind.IMAGE) {
                if (mode == Mode.MOVE_SEL && editStarted) host.onDragEnd()
                editStarted = false
                mode = Mode.NONE
                host.onImageLongPress(h.index)
            }
        }
    }

    init {
        if (Build.VERSION.SDK_INT >= 24) {
            pointerIcon = android.view.PointerIcon.getSystemIcon(context, android.view.PointerIcon.TYPE_NULL)
        }
    }

    // ---------------------------------------------------------------- камера

    private val b get() = host.board()
    fun wx(sx: Float) = sx / b.camZ + b.camX
    fun wy(sy: Float) = sy / b.camZ + b.camY
    fun sx(x: Float) = (x - b.camX) * b.camZ
    fun sy(y: Float) = (y - b.camY) * b.camZ

    /** Показать всё, что есть на доске. */
    fun fitAll() {
        val bb = contentBounds()
        if (bb == null || width == 0) {
            b.camZ = 1f
            b.camX = -Ui.dpf(context, 40f)
            b.camY = -Ui.dpf(context, 40f)
        } else {
            val pad = Ui.dpf(context, 40f)
            val z = min(width / (bb.width() + pad * 2), height / (bb.height() + pad * 2)).coerceIn(0.1f, 2f)
            b.camZ = z
            b.camX = bb.centerX() - width / 2f / z
            b.camY = bb.centerY() - height / 2f / z
        }
        host.onCameraChanged()
        invalidate()
    }

    fun contentBounds(): RectF? {
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var bt = -Float.MAX_VALUE
        fun add(x: Float, y: Float) { l = min(l, x); t = min(t, y); r = max(r, x); bt = max(bt, y) }
        for (d in b.strokes) { var i = 0; while (i + 1 < d.pts.size) { add(d.pts[i], d.pts[i + 1]); i += 3 } }
        for (x in b.texts) { add(x.x, x.y - x.size); add(x.x + textWidth(x), x.y + x.size * 0.3f) }
        for (m in b.images) { add(m.x, m.y); add(m.x + m.w, m.y + m.h) }
        for (s in b.stickers) { add(s.x, s.y); add(s.x + s.w, s.y + s.h) }
        return if (l <= r) RectF(l, t, r, bt) else null
    }

    fun fade(strokes: List<InkStroke>) {
        fading += strokes to SystemClock.uptimeMillis()
        postInvalidateOnAnimation()
    }

    fun textWidth(t: BoardText): Float {
        textPaint.textSize = t.size
        return textPaint.measureText(t.text)
    }

    /** Что лежит в точке доски (сверху вниз: метки, тексты, картинки). Штрихи — см. [selectableAt]. */
    fun hitAt(x: Float, y: Float): Hit? {
        for (i in b.stickers.indices.reversed()) {
            val s = b.stickers[i]
            if (x >= s.x && x <= s.x + s.w && y >= s.y && y <= s.y + s.h) return Hit(Kind.STICKER, i)
        }
        for (i in b.texts.indices.reversed()) {
            val t = b.texts[i]
            if (x >= t.x && x <= t.x + textWidth(t) && y >= t.y - t.size && y <= t.y + t.size * 0.3f) return Hit(Kind.TEXT, i)
        }
        for (i in b.images.indices.reversed()) {
            val m = b.images[i]
            if (x >= m.x && x <= m.x + m.w && y >= m.y && y <= m.y + m.h) return Hit(Kind.IMAGE, i)
        }
        return null
    }

    /** Штрих (рисунок, фигура, стрелка) рядом с точкой доски. */
    fun strokeAt(x: Float, y: Float): Int {
        val r = Ui.dpf(context, 12f) / b.camZ
        for (i in b.strokes.indices.reversed()) {
            val d = b.strokes[i]
            val rr = r + d.width / 2f
            boundsOf(d, rect2)
            if (x < rect2.left - rr || x > rect2.right + rr || y < rect2.top - rr || y > rect2.bottom + rr) continue
            val p = d.pts
            if (p.size < 6) {
                if (p.size >= 2 && hypot(p[0] - x, p[1] - y) <= rr) return i
                continue
            }
            var k = 3
            while (k + 1 < p.size) {
                if (segDist(x, y, p[k - 3], p[k - 2], p[k], p[k + 1]) <= rr) return i
                k += 3
            }
        }
        return -1
    }

    /** То, что можно выделить, в точке: метки, надписи, штрихи (поверх картинок), картинки. */
    fun selectableAt(x: Float, y: Float): Any? {
        val h = hitAt(x, y)
        if (h != null && h.kind != Kind.IMAGE) return objectOf(h)
        val s = strokeAt(x, y)
        if (s >= 0) return b.strokes[s]
        return h?.let { objectOf(it) }
    }

    private fun segDist(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay
        val l2 = dx * dx + dy * dy
        if (l2 <= 1e-6f) return hypot(px - ax, py - ay)
        val t = (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0f, 1f)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    fun objectOf(h: Hit): Any? = when (h.kind) {
        Kind.STICKER -> b.stickers.getOrNull(h.index)
        Kind.TEXT -> b.texts.getOrNull(h.index)
        Kind.IMAGE -> b.images.getOrNull(h.index)
        Kind.STROKE -> b.strokes.getOrNull(h.index)
    }

    // ---------------------------------------------------------------- выделение

    /** Выделенные объекты (сами объекты доски: BoardText, BoardImage, BoardSticker, Drawing). */
    val selection = ArrayList<Any>()

    fun select(objs: List<Any>) {
        selection.clear()
        selection.addAll(objs.filter { exists(it) })
        host.onSelectionChanged()
        invalidate()
    }

    fun clearSelection() {
        if (selection.isEmpty()) return
        select(emptyList())
    }

    /** Убрать из выделения то, чего уже нет на доске (стёрли, отменили). */
    fun pruneSelection() {
        if (selection.isEmpty()) return
        if (selection.all { exists(it) }) return
        select(selection.toList())
    }

    private fun exists(o: Any): Boolean = when (o) {
        is BoardText -> b.texts.any { it === o }
        is BoardImage -> b.images.any { it === o }
        is BoardSticker -> b.stickers.any { it === o }
        is Drawing -> b.strokes.any { it === o }
        else -> false
    }

    fun boundsOf(o: Any, out: RectF): RectF {
        when (o) {
            is BoardText -> out.set(o.x, o.y - o.size, o.x + textWidth(o), o.y + o.size * 0.3f)
            is BoardImage -> out.set(o.x, o.y, o.x + o.w, o.y + o.h)
            is BoardSticker -> out.set(o.x, o.y, o.x + o.w, o.y + o.h)
            is Drawing -> {
                var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var bt = -Float.MAX_VALUE
                var i = 0
                while (i + 1 < o.pts.size) {
                    l = min(l, o.pts[i]); t = min(t, o.pts[i + 1]); r = max(r, o.pts[i]); bt = max(bt, o.pts[i + 1])
                    i += 3
                }
                val w = o.width / 2f
                if (l <= r) out.set(l - w, t - w, r + w, bt + w) else out.setEmpty()
            }
            else -> out.setEmpty()
        }
        return out
    }

    fun selectionBounds(): RectF? {
        if (selection.isEmpty()) return null
        val out = RectF()
        var first = true
        for (o in selection) {
            boundsOf(o, rect2)
            if (first) { out.set(rect2); first = false } else out.union(rect2)
        }
        return out
    }

    // Исходное положение выделенного в начале жеста: от него считаем сдвиг и масштаб без накопления ошибок.
    private class Orig(val obj: Any, val a: FloatArray, val w: Float)
    private val origs = ArrayList<Orig>()
    private val origBounds = RectF()
    private var editStarted = false
    private val anchor = PointF()
    private val corner = PointF()
    private val dragStart = PointF()
    private val pinchMid0 = PointF()

    private fun captureOrigs() {
        origs.clear()
        for (o in selection) {
            origs += when (o) {
                is BoardText -> Orig(o, floatArrayOf(o.x, o.y, o.size), 0f)
                is BoardImage -> Orig(o, floatArrayOf(o.x, o.y, o.w, o.h), 0f)
                is BoardSticker -> Orig(o, floatArrayOf(o.x, o.y, o.scale), 0f)
                is Drawing -> Orig(o, o.pts.copyOf(), o.width)
                else -> continue
            }
        }
        selectionBounds()?.let { origBounds.set(it) }
    }

    /** Положение = опора + (исходное − опора) × s + сдвиг. */
    private fun applyTransform(ax: Float, ay: Float, s: Float, dx: Float, dy: Float) {
        fun tx(v: Float) = ax + (v - ax) * s + dx
        fun ty(v: Float) = ay + (v - ay) * s + dy
        for (or in origs) {
            val a = or.a
            when (val o = or.obj) {
                is BoardText -> { o.x = tx(a[0]); o.y = ty(a[1]); o.size = a[2] * s }
                is BoardImage -> { o.x = tx(a[0]); o.y = ty(a[1]); o.w = a[2] * s; o.h = a[3] * s }
                is BoardSticker -> { o.x = tx(a[0]); o.y = ty(a[1]); o.scale = a[2] * s }
                is Drawing -> {
                    var i = 0
                    while (i + 1 < a.size && i + 1 < o.pts.size) {
                        o.pts[i] = tx(a[i]); o.pts[i + 1] = ty(a[i + 1])
                        i += 3
                    }
                    o.width = or.w * s
                }
            }
        }
        invalidate()
    }

    /** Самый маленький допустимый масштаб: выделенное не меньше 24 dp на экране. */
    private fun minScale(): Float {
        val size = max(origBounds.width(), origBounds.height())
        return if (size <= 0f) 1f else (Ui.dpf(context, 24f) / b.camZ) / size
    }

    /**
     * Уголок рамки выделения под точкой экрана. Уголки нарисованы чуть снаружи рамки,
     * а возвращается сам угол рамки (от него и от противоположного угла считается размер).
     */
    private fun handleAt(sxp: Float, syp: Float): PointF? {
        val bb = selectionBounds() ?: return null
        val pad = Ui.dpf(context, 4f) / b.camZ
        val r = Ui.dpf(context, 26f)
        var best: PointF? = null
        var bestD = r
        for (right in booleanArrayOf(false, true)) for (bottom in booleanArrayOf(false, true)) {
            val hx = if (right) bb.right + pad else bb.left - pad
            val hy = if (bottom) bb.bottom + pad else bb.top - pad
            val d = hypot(sx(hx) - sxp, sy(hy) - syp)
            if (d <= bestD) { bestD = d; best = PointF(if (right) bb.right else bb.left, if (bottom) bb.bottom else bb.top) }
        }
        return best
    }

    private fun insideSelection(x: Float, y: Float): Boolean {
        val bb = selectionBounds() ?: return false
        val pad = Ui.dpf(context, 6f) / b.camZ
        return x >= bb.left - pad && x <= bb.right + pad && y >= bb.top - pad && y <= bb.bottom + pad
    }

    // Лассо — обводка пером или пальцем в инструменте «Выделить».
    private val lasso = ArrayList<PointF>()

    private fun lassoSelect() {
        if (lasso.size < 3) return
        val out = ArrayList<Any>()
        fun inside(x: Float, y: Float): Boolean {
            var c = false
            var j = lasso.size - 1
            for (i in lasso.indices) {
                val pi = lasso[i]; val pj = lasso[j]
                if ((pi.y > y) != (pj.y > y) && x < (pj.x - pi.x) * (y - pi.y) / (pj.y - pi.y) + pi.x) c = !c
                j = i
            }
            return c
        }
        for (o in b.images) { boundsOf(o, rect2); if (inside(rect2.centerX(), rect2.centerY())) out += o }
        for (o in b.strokes) { boundsOf(o, rect2); if (inside(rect2.centerX(), rect2.centerY())) out += o }
        for (o in b.texts) { boundsOf(o, rect2); if (inside(rect2.centerX(), rect2.centerY())) out += o }
        for (o in b.stickers) { boundsOf(o, rect2); if (inside(rect2.centerX(), rect2.centerY())) out += o }
        select(out)
    }

    // ---------------------------------------------------------------- касания

    private fun isPen(tool: Int) = tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER

    private fun penLike(tool: Int) = isPen(tool) || tool == MotionEvent.TOOL_TYPE_MOUSE || host.fingerWrites()

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> begin(ev, 0)
            MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = ev.actionIndex
                val tool = ev.getToolType(idx)
                if (isPen(tool) && (mode == Mode.PAN || mode == Mode.PINCH || mode == Mode.NONE || (mode == Mode.MOVE_SEL && !activeIsPen))) {
                    if (mode == Mode.MOVE_SEL && editStarted) host.onDragEnd()
                    editStarted = false
                    begin(ev, idx) // ладонь или пальцы лежали — работает перо
                } else if (!penLike(tool) && !activeIsPen && mode == Mode.MOVE_SEL) {
                    startSelPinch(ev) // два пальца на выделенном — меняем его размер
                } else if (!penLike(tool) && !activeIsPen && (mode == Mode.PAN || mode == Mode.LASSO)) {
                    lasso.clear()
                    startPinch(ev)
                }
            }
            MotionEvent.ACTION_MOVE -> move(ev)
            MotionEvent.ACTION_POINTER_UP -> {
                val id = ev.getPointerId(ev.actionIndex)
                if (mode == Mode.PINCH_SEL && (id == pinchA || id == pinchB)) {
                    if (editStarted) host.onDragEnd()
                    editStarted = false
                    mode = Mode.NONE
                } else if (mode == Mode.PINCH && (id == pinchA || id == pinchB)) {
                    val other = if (id == pinchA) pinchB else pinchA
                    val oi = ev.findPointerIndex(other)
                    mode = if (oi >= 0) Mode.PAN else Mode.NONE
                    activePointer = other
                    if (oi >= 0) { lastX = ev.getX(oi); lastY = ev.getY(oi) }
                    moved = true
                } else if (id == activePointer) {
                    finish(ev, ev.actionIndex)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (ev.getPointerId(ev.actionIndex) == activePointer) finish(ev, ev.actionIndex)
                mode = Mode.NONE
                removeCallbacks(longPress)
            }
            MotionEvent.ACTION_CANCEL -> {
                if (editStarted) host.onDragEnd()
                editStarted = false
                live = null
                lasso.clear()
                mode = Mode.NONE
                removeCallbacks(longPress)
                invalidate()
            }
        }
        return true
    }

    private fun begin(ev: MotionEvent, idx: Int) {
        removeCallbacks(longPress)
        val tool = ev.getToolType(idx)
        activePointer = ev.getPointerId(idx)
        activeIsPen = isPen(tool)
        downX = ev.getX(idx); downY = ev.getY(idx)
        lastX = downX; lastY = downY
        downTime = SystemClock.uptimeMillis()
        moved = false
        hover = null
        editStarted = false
        val x = wx(downX)
        val y = wy(downY)
        val t = host.tool()
        val pen = penLike(tool)

        if (t == BoardActivity.Tool.PIPETTE) {
            mode = Mode.PIPETTE
            hover = PointF(downX, downY)
            invalidate()
            return
        }
        // Уголок рамки выделения — пером или пальцем, при любом инструменте.
        if (selection.isNotEmpty()) {
            val c = handleAt(downX, downY)
            if (c != null) {
                parent?.requestDisallowInterceptTouchEvent(true)
                captureOrigs()
                corner.set(c.x, c.y)
                anchor.set(origBounds.left + origBounds.right - c.x, origBounds.top + origBounds.bottom - c.y)
                mode = Mode.RESIZE
                return
            }
        }
        if (t == BoardActivity.Tool.SELECT) {
            // «Выделить»: внутри выделенного — двигать; в любом другом месте — лассо
            // (даже поверх чертежа или линии: иначе на плотной доске ничего не обвести).
            parent?.requestDisallowInterceptTouchEvent(true)
            if (insideSelection(x, y)) {
                captureOrigs()
                dragStart.set(x, y)
                mode = Mode.MOVE_SEL
                if (!pen) postDelayed(longPress, 550)
                return
            }
            lasso.clear()
            lasso += PointF(x, y)
            mode = Mode.LASSO
            return
        }
        if (!pen) {
            // Палец: выделенное — двигать, иначе — двигать доску.
            if (insideSelection(x, y)) {
                captureOrigs()
                dragStart.set(x, y)
                mode = Mode.MOVE_SEL
            } else {
                mode = Mode.PAN
            }
            postDelayed(longPress, 550)
            return
        }
        parent?.requestDisallowInterceptTouchEvent(true)
        requestUnbufferedDispatch(ev)
        if (tool == MotionEvent.TOOL_TYPE_ERASER || t == BoardActivity.Tool.ERASER) {
            mode = Mode.ERASE
            host.onErase(x, y, true)
            return
        }
        if (t == BoardActivity.Tool.STATUS) {
            mode = Mode.STATUS
            return
        }
        mode = Mode.INK
        host.onStrokeStart(x, y)
        clockOffset = System.currentTimeMillis() - SystemClock.uptimeMillis()
        val st = host.strokeStyle()
        liveStyle = NoteInkView.StrokeStyle(st.kind, st.color, st.width / b.camZ)
        live = InkStroke(tool).also { it.points.add(InkPoint(x, y, ev.eventTime + clockOffset, pressure(ev, idx, -1))) }
        invalidate()
    }

    private fun pressure(ev: MotionEvent, idx: Int, h: Int): Float {
        if (!isPen(ev.getToolType(idx))) return 0.5f
        val p = if (h < 0) ev.getPressure(idx) else ev.getHistoricalPressure(idx, h)
        return (p / 0.6f).coerceIn(0f, 1f)
    }

    private fun move(ev: MotionEvent) {
        if (mode == Mode.PINCH) { pinch(ev); return }
        if (mode == Mode.PINCH_SEL) { pinchSel(ev); return }
        val idx = ev.findPointerIndex(activePointer)
        if (idx < 0) return
        val sxp = ev.getX(idx)
        val syp = ev.getY(idx)
        if (!moved && hypot(sxp - downX, syp - downY) > slop) {
            moved = true
            removeCallbacks(longPress)
        }
        when (mode) {
            Mode.PAN -> {
                b.camX -= (sxp - lastX) / b.camZ
                b.camY -= (syp - lastY) / b.camZ
                lastX = sxp; lastY = syp
                host.onCameraChanged()
                invalidate()
            }
            Mode.MOVE_SEL -> {
                if (!moved) return
                if (!editStarted) { editStarted = true; host.onDragStart() }
                applyTransform(0f, 0f, 1f, wx(sxp) - dragStart.x, wy(syp) - dragStart.y)
            }
            Mode.RESIZE -> {
                if (!moved) return
                if (!editStarted) { editStarted = true; host.onDragStart() }
                val dx = corner.x - anchor.x
                val dy = corner.y - anchor.y
                val l2 = dx * dx + dy * dy
                if (l2 > 0f) {
                    val s = (((wx(sxp) - anchor.x) * dx + (wy(syp) - anchor.y) * dy) / l2).coerceAtLeast(minScale())
                    applyTransform(anchor.x, anchor.y, s, 0f, 0f)
                }
            }
            Mode.LASSO -> {
                val x = wx(sxp); val y = wy(syp)
                val last = lasso.lastOrNull()
                if (last == null || hypot(x - last.x, y - last.y) * b.camZ > Ui.dpf(context, 3f)) lasso += PointF(x, y)
                invalidate()
            }
            Mode.PIPETTE -> {
                hover = PointF(sxp, syp)
                invalidate()
            }
            Mode.ERASE -> {
                for (h in 0 until ev.historySize) host.onErase(wx(ev.getHistoricalX(idx, h)), wy(ev.getHistoricalY(idx, h)), false)
                host.onErase(wx(sxp), wy(syp), false)
            }
            Mode.INK -> {
                val s = live ?: return
                for (h in 0 until ev.historySize) {
                    add(s, wx(ev.getHistoricalX(idx, h)), wy(ev.getHistoricalY(idx, h)), ev.getHistoricalEventTime(h), pressure(ev, idx, h))
                }
                add(s, wx(sxp), wy(syp), ev.eventTime, pressure(ev, idx, -1))
                invalidate()
            }
            else -> Unit
        }
    }

    private fun add(s: InkStroke, x: Float, y: Float, t: Long, p: Float) {
        val last = s.points[s.points.lastIndex]
        if (last.x == x && last.y == y) return
        s.points.add(InkPoint(x, y, t + clockOffset, p))
    }

    private fun finish(ev: MotionEvent, idx: Int) {
        removeCallbacks(longPress)
        val sxp = ev.getX(idx)
        val syp = ev.getY(idx)
        val quick = SystemClock.uptimeMillis() - downTime < 350 && hypot(sxp - downX, syp - downY) < slop
        val t = host.tool()
        when (mode) {
            Mode.INK -> {
                val s = live
                if (s != null) {
                    add(s, wx(sxp), wy(syp), ev.eventTime, pressure(ev, idx, -1))
                    live = null
                    host.onStrokeEnd(s, liveStyle ?: host.strokeStyle(), quick)
                }
            }
            Mode.STATUS -> if (quick) host.onStatusTap(wx(sxp), wy(syp))
            Mode.PAN -> if (quick && !moved) tapAt(wx(sxp), wy(syp))
            Mode.MOVE_SEL, Mode.RESIZE -> {
                if (editStarted) {
                    host.onDragEnd()
                } else if (quick && mode == Mode.MOVE_SEL) {
                    // Касание по выделенному: «Выделить» — выбрать то, что под пером; палец — снять выделение.
                    val x = wx(sxp); val y = wy(syp)
                    if (t == BoardActivity.Tool.SELECT) {
                        val o = selectableAt(x, y)
                        if (o != null) select(listOf(o)) else clearSelection()
                    } else {
                        tapAt(x, y)
                    }
                }
            }
            Mode.LASSO -> {
                // Короткое касание — выделить то, что под пером/пальцем; обводка — всё внутри.
                // (замкнутая обводка кончается там же, где началась, — поэтому смотрим, двигалось ли перо)
                if (!moved || lasso.size < 3) {
                    val o = selectableAt(wx(sxp), wy(syp))
                    if (o != null) select(listOf(o)) else clearSelection()
                } else {
                    lassoSelect()
                }
                lasso.clear()
            }
            Mode.PIPETTE -> {
                hover = null
                host.onPipettePick(sxp, syp)
            }
            else -> Unit
        }
        editStarted = false
        mode = Mode.NONE
        invalidate()
    }

    /** Короткое касание пальцем: метка — следующий статус, объект — выделить, пусто — снять выделение. */
    private fun tapAt(x: Float, y: Float) {
        val h = hitAt(x, y)
        if (h?.kind == Kind.STICKER && host.tool() != BoardActivity.Tool.SELECT) {
            clearSelection()
            host.onStickerTap(h.index)
            return
        }
        val o = selectableAt(x, y)
        when {
            o == null -> clearSelection()
            selection.size == 1 && selection[0] === o -> clearSelection()
            else -> select(listOf(o))
        }
    }

    private fun twoFingers(ev: MotionEvent): Pair<Int, Int>? {
        var a = -1
        var c = -1
        for (i in 0 until ev.pointerCount) {
            if (isPen(ev.getToolType(i))) continue
            if (a < 0) a = i else if (c < 0) c = i
        }
        return if (a < 0 || c < 0) null else a to c
    }

    private fun startPinch(ev: MotionEvent) {
        removeCallbacks(longPress)
        val (a, c) = twoFingers(ev) ?: return
        pinchA = ev.getPointerId(a)
        pinchB = ev.getPointerId(c)
        pinchD0 = max(1f, hypot(ev.getX(a) - ev.getX(c), ev.getY(a) - ev.getY(c)))
        pinchZ0 = b.camZ
        val mx = (ev.getX(a) + ev.getX(c)) / 2f
        val my = (ev.getY(a) + ev.getY(c)) / 2f
        pinchWorld.set(wx(mx), wy(my))
        mode = Mode.PINCH
        moved = true
    }

    private fun pinch(ev: MotionEvent) {
        val a = ev.findPointerIndex(pinchA)
        val c = ev.findPointerIndex(pinchB)
        if (a < 0 || c < 0) return
        val d = max(1f, hypot(ev.getX(a) - ev.getX(c), ev.getY(a) - ev.getY(c)))
        val z = (pinchZ0 * d / pinchD0).coerceIn(0.1f, 5f)
        val mx = (ev.getX(a) + ev.getX(c)) / 2f
        val my = (ev.getY(a) + ev.getY(c)) / 2f
        b.camZ = z
        b.camX = pinchWorld.x - mx / z
        b.camY = pinchWorld.y - my / z
        host.onCameraChanged()
        invalidate()
    }

    private fun startSelPinch(ev: MotionEvent) {
        removeCallbacks(longPress)
        val (a, c) = twoFingers(ev) ?: return
        pinchA = ev.getPointerId(a)
        pinchB = ev.getPointerId(c)
        pinchD0 = max(1f, hypot(ev.getX(a) - ev.getX(c), ev.getY(a) - ev.getY(c)))
        pinchMid0.set(wx((ev.getX(a) + ev.getX(c)) / 2f), wy((ev.getY(a) + ev.getY(c)) / 2f))
        // Двигали одним пальцем — продолжаем от текущего положения.
        captureOrigs()
        if (!editStarted) { editStarted = true; host.onDragStart() }
        mode = Mode.PINCH_SEL
        moved = true
    }

    private fun pinchSel(ev: MotionEvent) {
        val a = ev.findPointerIndex(pinchA)
        val c = ev.findPointerIndex(pinchB)
        if (a < 0 || c < 0) return
        val d = max(1f, hypot(ev.getX(a) - ev.getX(c), ev.getY(a) - ev.getY(c)))
        val k = (d / pinchD0).coerceAtLeast(minScale())
        val mx = wx((ev.getX(a) + ev.getX(c)) / 2f)
        val my = wy((ev.getY(a) + ev.getY(c)) / 2f)
        applyTransform(origBounds.centerX(), origBounds.centerY(), k, mx - pinchMid0.x, my - pinchMid0.y)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        // Колёсико мыши (на компьютере/эмуляторе): прокрутка, с Ctrl — масштаб.
        if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
            val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if ((event.metaState and android.view.KeyEvent.META_CTRL_ON) != 0) {
                val wxp = wx(event.x); val wyp = wy(event.y)
                b.camZ = (b.camZ * (1f + v * 0.1f)).coerceIn(0.1f, 5f)
                b.camX = wxp - event.x / b.camZ
                b.camY = wyp - event.y / b.camZ
            } else {
                b.camY -= v * Ui.dpf(context, 60f) / b.camZ
            }
            host.onCameraChanged()
            invalidate()
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        if (!isPen(event.getToolType(0)) || host.tool() == BoardActivity.Tool.SELECT) return super.onHoverEvent(event)
        hover = if (event.actionMasked == MotionEvent.ACTION_HOVER_EXIT) null else PointF(event.x, event.y)
        invalidate()
        return true
    }

    /** Снимок доски без служебных значков — пипетка берёт из него цвет пикселя. */
    fun plainSnapshot(): ColorKit.Snapshot? {
        plain = true
        try {
            return ColorKit.snapshot(this)
        } finally {
            plain = false
        }
    }

    // ---------------------------------------------------------------- рисование

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Ui.PAPER)
        val z = b.camZ
        canvas.save()
        canvas.scale(z, z)
        canvas.translate(-b.camX, -b.camY)
        drawGrid(canvas, z)
        for (m in b.images) drawImage(canvas, m, z)
        for (d in b.strokes) painter.drawing(canvas, d)
        for (t in b.texts) {
            textPaint.color = t.color
            textPaint.textSize = t.size
            canvas.drawText(t.text, t.x, t.y, textPaint)
        }
        for (s in b.stickers) drawSticker(canvas, s)
        if (!plain) drawSelection(canvas, z)
        val pendingStyle = NoteInkView.StrokeStyle("write", Ui.INK, Ui.dpf(context, 2.6f) / z)
        for (s in host.pendingStrokes()) painter.stroke(canvas, s, pendingStyle, 255)
        live?.let { painter.stroke(canvas, it, liveStyle ?: pendingStyle, 255) }
        val now = SystemClock.uptimeMillis()
        fading.removeAll { now - it.second > NoteInkView.FADE_MS }
        for ((strokes, t0) in fading) {
            val a = (255 * (1f - (now - t0).toFloat() / NoteInkView.FADE_MS)).toInt().coerceIn(0, 255)
            for (s in strokes) painter.stroke(canvas, s, pendingStyle, a)
        }
        host.interim()?.let { (bb, text) ->
            val h = bb[3] - bb[1]
            textPaint.color = Ui.MUTED
            textPaint.textSize = max(h * 0.75f, Ui.dpf(context, 14f) / z)
            canvas.drawText(text, bb[0], bb[1] - Ui.dpf(context, 8f) / z, textPaint)
        }
        if (!plain && lasso.size > 1) {
            lassoPath.reset()
            lassoPath.moveTo(lasso[0].x, lasso[0].y)
            for (i in 1 until lasso.size) lassoPath.lineTo(lasso[i].x, lasso[i].y)
            dashPaint.strokeWidth = Ui.dpf(context, 1.5f) / z
            dashPaint.pathEffect = DashPathEffect(floatArrayOf(Ui.dpf(context, 6f) / z, Ui.dpf(context, 4f) / z), 0f)
            canvas.drawPath(lassoPath, dashPaint)
        }
        canvas.restore()
        if (!plain) hover?.let {
            when {
                host.tool() == BoardActivity.Tool.PIPETTE -> pipetteRing(context, canvas, it.x, it.y, host.pipetteColor(it.x, it.y))
                host.tool() == BoardActivity.Tool.ERASER -> canvas.drawCircle(it.x, it.y, Ui.dpf(context, 14f), hoverRing)
                else -> {
                    hoverDot.color = host.strokeStyle().color
                    hoverDot.alpha = 170
                    canvas.drawCircle(it.x, it.y, Ui.dpf(context, 2.5f), hoverDot)
                }
            }
        }
        if (fading.isNotEmpty()) postInvalidateOnAnimation()
    }

    private fun drawGrid(canvas: Canvas, z: Float) {
        var step = 40f
        while (step * z < Ui.dpf(context, 14f)) step *= 5f
        val l = b.camX; val t = b.camY
        val r = l + width / z; val bt = t + height / z
        var x = Math.floor((l / step).toDouble()).toFloat() * step
        while (x < r) { canvas.drawLine(x, t, x, bt, gridPaint); x += step }
        var y = Math.floor((t / step).toDouble()).toFloat() * step
        while (y < bt) { canvas.drawLine(l, y, r, y, gridPaint); y += step }
    }

    private fun drawImage(canvas: Canvas, m: BoardImage, z: Float) {
        val frame = Ui.dpf(context, 5f) / z
        rect.set(m.x - frame, m.y - frame, m.x + m.w + frame, m.y + m.h + frame)
        fillPaint.color = Color.WHITE
        canvas.drawRect(rect, fillPaint)
        linePaint.color = Ui.RULE
        linePaint.strokeWidth = 0f
        canvas.drawRect(rect, linePaint)
        val bmp = host.bitmap(m)
        rect.set(m.x, m.y, m.x + m.w, m.y + m.h)
        if (bmp != null) {
            canvas.drawBitmap(bmp, null, rect, bitmapPaint)
        } else {
            fillPaint.color = Ui.DESK
            canvas.drawRect(rect, fillPaint)
        }
    }

    /** Рамка выделения и уголки, за которые меняют размер; при нескольких объектах — тонкие рамки каждого. */
    private fun drawSelection(canvas: Canvas, z: Float) {
        val bb = selectionBounds() ?: return
        if (selection.size > 1) {
            dashPaint.strokeWidth = Ui.dpf(context, 1f) / z
            dashPaint.pathEffect = DashPathEffect(floatArrayOf(Ui.dpf(context, 4f) / z, Ui.dpf(context, 3f) / z), 0f)
            for (o in selection) canvas.drawRect(boundsOf(o, rect2), dashPaint)
        }
        val pad = Ui.dpf(context, 4f) / z
        rect.set(bb.left - pad, bb.top - pad, bb.right + pad, bb.bottom + pad)
        linePaint.color = Ui.ACCENT
        linePaint.strokeWidth = Ui.dpf(context, 2f) / z
        canvas.drawRect(rect, linePaint)
        val r = Ui.dpf(context, 8f) / z
        for (cx in floatArrayOf(rect.left, rect.right)) for (cy in floatArrayOf(rect.top, rect.bottom)) {
            fillPaint.color = Color.WHITE
            canvas.drawCircle(cx, cy, r, fillPaint)
            canvas.drawCircle(cx, cy, r, linePaint)
        }
    }

    private fun drawSticker(canvas: Canvas, s: BoardSticker) {
        val (label, fg, bg) = STATUS[s.status.coerceIn(0, 2)]
        rect.set(s.x, s.y, s.x + s.w, s.y + s.h)
        fillPaint.color = bg
        canvas.drawRoundRect(rect, s.h / 2f, s.h / 2f, fillPaint)
        linePaint.color = fg
        linePaint.strokeWidth = 1.5f * s.scale
        canvas.drawRoundRect(rect, s.h / 2f, s.h / 2f, linePaint)
        textPaint.color = fg
        textPaint.textSize = 16f * s.scale
        textPaint.isFakeBoldText = true
        val tw = textPaint.measureText(label)
        canvas.drawText(label, s.x + (s.w - tw) / 2f, s.y + s.h / 2f + textPaint.textSize * 0.36f, textPaint)
        textPaint.isFakeBoldText = false
    }

    companion object {
        /** Подпись, цвет текста, цвет фона — для статусов 0, 1, 2. */
        val STATUS = listOf(
            Triple("Не сдано", Ui.BAD, Ui.BAD_SOFT),
            Triple("В работе", Ui.WARN, Ui.WARN_SOFT),
            Triple("Сдано", Ui.OK, Ui.OK_SOFT),
        )
    }
}
