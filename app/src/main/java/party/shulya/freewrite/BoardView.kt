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
 *
 * Статусы: касание метки или нижней строки выноски — следующий статус по списку доски,
 * долгое нажатие — список всех статусов. «Выноска»: провести пером от места на чертеже туда, где
 * будет рамка (или от рамки — ещё один указатель). Концы указателей выделенной выноски можно тянуть;
 * конец, утащенный на рамку, убирает указатель.
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
        /** Касание нижней строки выноски — следующий статус. */
        fun onCalloutStatusTap(c: BoardCallout)
        /** Долгое нажатие на метку или строку статуса выноски — список статусов. */
        fun onStatusMenu(obj: Any)
        /** «Выноска»: провели пером от места (tx, ty) до рамки (bx, by); moved = false — просто касание. */
        fun onCalloutCreate(tx: Float, ty: Float, bx: Float, by: Float, moved: Boolean)
        /** Перед изменением объектов жестом (для «Отменить»). */
        fun onDragStart()
        /** После жеста — сохранить. */
        fun onDragEnd()
        /** Жест отменён (это была ладонь): всё вернули на место — забыть запомненное для «Отменить». */
        fun onDragCancel()
        fun onImageLongPress(index: Int)
        fun onSelectionChanged()
        fun onCameraChanged()
        fun pendingStrokes(): List<InkStroke>
        /** Серое промежуточное слово над недописанной надписью: рамка [l, t, r, b] и текст. */
        fun interim(): Pair<FloatArray, String>?
        /** Цвет под точкой экрана — для пипетки. */
        fun pipetteColor(sx: Float, sy: Float): Int?
        fun onPipettePick(sx: Float, sy: Float)
        /** Штрих отменён вторым пальцем (начали двигать доску) — ничего не рисовать. */
        fun onStrokeCancel() {}
        /** «Текст»: касание — написать надпись с клавиатуры (или изменить ту, что под пальцем). */
        fun onTextTap(x: Float, y: Float) {}
        /** На доске впервые коснулись пером — устройство с пером. */
        fun onPenSeen() {}
        /** Нажали булавку закреплённой картинки. */
        fun onPinTap(img: BoardImage) {}
        /** Касание булавки закреплённой таблицы (0.23.1). */
        fun onTablePinTap(t: BoardTable) {}
        /** Касание части строки ИД ([RowPart]): галочка — отметить/снять, ИГС — следующий статус. */
        fun onRowTap(row: BoardRow, part: Int) {}
        /** «Заливка» по точкам: уже поставленные точки (x, y, x, y…) для показа; null — точек нет. */
        fun fillDraft(): FloatArray? = null
        /** «Раздел»: провели рамку от угла до угла (рамка на доске). */
        fun onFrameCreate(l: Float, t: Float, r: Float, b: Float) {}
        /** «Заливка» пальцем: точка выбрана (под лупой, с привязкой). */
        fun onFillPoint(x: Float, y: Float) {}
        /** Касание таблицы ИД: шапка, ячейка (галочка, ИГС, текст, дата), «+ строка». */
        fun onTableTap(t: BoardTable, h: TableHit) {}
        /** Подержали строку таблицы ИД — меню строки. */
        fun onTableLongPress(t: BoardTable, h: TableHit) {}
        /** Обводка захватки на плане таблицы (0.23): касание (tap) или протяжка от (x0, y0) до (x1, y1), координаты доски. */
        fun onPlanGesture(x0: Float, y0: Float, x1: Float, y1: Float, tap: Boolean) {}
        /** Коснулись пустого места доски (снять активную ячейку таблицы). */
        fun onEmptyTap() {}
        /** Касание ведомости: строка (ответ, схема), «+ схемы», «Передать». */
        fun onVedomTap(v: BoardVedom, h: VedomHit) {}
        /** Подержали строку ведомости — меню позиции. */
        fun onVedomLongPress(v: BoardVedom, h: VedomHit) {}
    }

    enum class Kind { STICKER, TEXT, IMAGE, STROKE, CALLOUT, FILL, ROW, TABLE, VEDOM }

    /** Таблицы ИД (0.20): раскладка, рисование, что под пальцем. */
    val tableDraw = TableDraw()

    /** Ведомости (0.21). */
    val vedomDraw = VedomDraw()

    /** Последнее касание доски — пером (или мышью), а не пальцем: в таблице перо попадает в ячейку, палец открывает карточку строки. */
    var lastTapByPen = false
        private set

    /** Выделенное можно двигать и менять размер (строки таблицы стоят на своих местах — их только выделяют). */
    private fun movable() = selection.any { it !is BoardRec && it !is BoardVItem && !(it is BoardTable && it.locked) }

    /** Части строки ИД: название, «Чек-лист», «DWG», «ИГС» (статус). */
    object RowPart { const val TITLE = 0; const val CHECK = 1; const val DWG = 2; const val STATUS = 3 }
    class Hit(val kind: Kind, val index: Int)

    private enum class Mode { NONE, PAN, PINCH, INK, ERASE, STATUS, MOVE_SEL, RESIZE, PINCH_SEL, LASSO, PIPETTE, CALLOUT_DRAG, LEADER, PALM, CROP, FRAME_DRAG, POINT, PLAN, FRAME_EDGE }

    // ---- Точная точка (0.14.1): лупа и привязка, как в DWG FastView ----
    // Палец закрывает угол, в который целятся: пока палец на экране, в углу экрана — лупа с перекрестием,
    // палец ведёт точку, отпустил — точка. Рядом с углом заливки, картинки или раздела точка прилипает к нему.
    private val aim = PointF()
    private val aimFinger = PointF()
    private var aimSnapped = false
    private val loupePath = Path()
    private val snapColor = Color.rgb(30, 150, 70)

    /** Привязка: угол заливки (или уже поставленная точка), угол картинки или раздела ближе 18 dp — точка в него. */
    fun snapPoint(x: Float, y: Float, out: PointF): Boolean {
        val r = Ui.dpf(context, 18f) / b.camZ
        var best = r
        var found = false
        fun c(px: Float, py: Float) {
            val d = hypot(px - x, py - y)
            if (d < best) { best = d; out.set(px, py); found = true }
        }
        fun near(l: Float, t: Float, rr: Float, bt: Float) = x >= l - r && x <= rr + r && y >= t - r && y <= bt + r
        host.fillDraft()?.let { p -> var i = 0; while (i + 1 < p.size) { c(p[i], p[i + 1]); i += 2 } }
        // Обводка на плане таблицы (0.23.1): поставленные точки и углы областей этого листа.
        planEdit?.let { pe ->
            val sr = tableDraw.sheetRect(b, pe.t, RectF())
            if (sr != null) {
                val q = FloatArray(2)
                var i = 0
                while (i + 1 < pe.points.size) { tableDraw.fromSheet(pe.sheet, sr, pe.points[i], pe.points[i + 1], q); c(q[0], q[1]); i += 2 }
                for (pc in pe.pieces) { i = 0; while (i + 1 < pc.size) { tableDraw.fromSheet(pe.sheet, sr, pc[i], pc[i + 1], q); c(q[0], q[1]); i += 2 } }
                for (zn in b.zones) if (zn.t == pe.t.id && zn.s == pe.sheet.id) { i = 0; while (i + 1 < zn.pts.size) { tableDraw.fromSheet(pe.sheet, sr, zn.pts[i], zn.pts[i + 1], q); c(q[0], q[1]); i += 2 } }
            }
        }
        for (f in b.fills) {
            val bb = f.bounds()
            if (!near(bb.left, bb.top, bb.right, bb.bottom)) continue
            var i = 0
            while (i + 1 < f.pts.size) { c(f.pts[i], f.pts[i + 1]); i += 2 }
        }
        for (m in b.images) if (near(m.x, m.y, m.x + m.w, m.y + m.h)) {
            c(m.x, m.y); c(m.x + m.w, m.y); c(m.x, m.y + m.h); c(m.x + m.w, m.y + m.h)
        }
        for (fr in b.frames) if (near(fr.x, fr.y, fr.x + fr.w, fr.y + fr.h)) {
            c(fr.x, fr.y); c(fr.x + fr.w, fr.y); c(fr.x, fr.y + fr.h); c(fr.x + fr.w, fr.y + fr.h)
        }
        if (!found) out.set(x, y)
        return found
    }

    private fun updateAim(px: Float, py: Float) {
        aimFinger.set(px, py)
        aimSnapped = snapPoint(wx(px), wy(py), aim)
    }

    // ---- Легенда (0.14): подсветить метки и выноски одного статуса, остальное притушить ----
    var highlight: String? = null

    /** Строки таблиц, отмеченные для показа (контроль, открытая карточка). */
    var markedRecs: Set<BoardRec>? = null

    /** Позиции ведомостей, отмеченные для показа (открытая карточка позиции). */
    var markedItems: Set<BoardVItem>? = null

    /** Заливки захватки, подсвеченные из таблицы (касание номера захватки, 0.22). */
    var markedFills: Set<BoardFill>? = null

    /** Области захватки, подсвеченные на плане таблицы (0.23). */
    var markedZones: Set<BoardZone>? = null

    /** Обводка захватки на листе плана (0.23): касания по листу идут в неё. */
    var planEdit: PlanEdit? = null

    /** Обводка пальцем с лупой (по точкам, прямоугольник, круг). */
    private var planAim = false

    /** Картинки листов планов для рисования (место считается каждый кадр из таблицы). */
    private val planImgs = HashMap<String, BoardImage>()
    private val planRect = RectF()
    private val planPt = FloatArray(2)
    private val zonePath = android.graphics.Path()

    /** Активная ячейка таблицы: перо пишет где угодно — текст встаёт в неё (рамка цвета акцента). */
    var activeCell: Pair<BoardRec, String>? = null
        set(v) { field = v; sceneValid = false; invalidate() }

    // ---- Разделы (0.14) ----
    private val frameDown = PointF()
    private val frameNow = PointF()
    private val frameText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = Ui.dpf(context, 13f); color = Ui.INK; typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    /** Ярлык раздела с названием — над левым верхним углом рамки (на экране, всегда одного размера). */
    fun frameLabelRect(f: BoardFrame, out: RectF): RectF {
        val pad = Ui.dpf(context, 7f)
        val h = Ui.dpf(context, 24f)
        val w = frameText.measureText(f.title.ifEmpty { "Раздел" }) + pad * 2
        val l = sx(f.x); val bt = sy(f.y) - Ui.dpf(context, 3f)
        out.set(l, bt - h, l + w, bt)
        return out
    }

    /** Раздел под точкой экрана: по ярлыку (border = true — и по краю рамки). */
    fun frameAt(px: Float, py: Float, border: Boolean): BoardFrame? {
        val r = RectF()
        for (i in b.frames.indices.reversed()) {
            val f = b.frames[i]
            if (frameLabelRect(f, r).contains(px, py)) return f
        }
        if (!border) return null
        val d = Ui.dpf(context, 10f)
        for (i in b.frames.indices.reversed()) {
            val f = b.frames[i]
            val l = sx(f.x); val t = sy(f.y); val rr = sx(f.x + f.w); val bt = sy(f.y + f.h)
            val inX = px >= l - d && px <= rr + d
            val inY = py >= t - d && py <= bt + d
            if (!inX || !inY) continue
            if (kotlin.math.abs(px - l) <= d || kotlin.math.abs(px - rr) <= d || kotlin.math.abs(py - t) <= d || kotlin.math.abs(py - bt) <= d) return f
        }
        return null
    }

    /** Что лежит внутри раздела (середина предмета внутри рамки). */
    fun frameContents(f: BoardFrame): List<Any> {
        val out = ArrayList<Any>()
        val r = RectF()
        fun inside(o: Any) { boundsOf(o, r); if (r.centerX() >= f.x && r.centerX() <= f.x + f.w && r.centerY() >= f.y && r.centerY() <= f.y + f.h) out += o }
        b.images.forEach { inside(it) }; b.fills.forEach { inside(it) }; b.strokes.forEach { inside(it) }
        b.texts.forEach { inside(it) }; b.stickers.forEach { inside(it) }; b.callouts.forEach { inside(it) }
        b.frames.forEach { if (it !== f) inside(it) }
        // Строка ИД — по левому краю (там название): длинная строка может выходить за рамку справа.
        for (r in b.rows) { val ax = r.x + 20f * r.scale; val ay = r.y + r.h / 2f; if (ax >= f.x && ax <= f.x + f.w && ay >= f.y && ay <= f.y + f.h) out += r }
        // Таблица — по левому краю названия.
        for (t in b.tables) { if (t.locked) continue; val ax = t.x + 20f * t.scale; val ay = t.y + 19f * t.scale; if (ax >= f.x && ax <= f.x + f.w && ay >= f.y && ay <= f.y + f.h) out += t }
        for (v in b.vedoms) { val ax = v.x + 20f * v.scale; val ay = v.y + 19f * v.scale; if (ax >= f.x && ax <= f.x + f.w && ay >= f.y && ay <= f.y + f.h) out += v }
        return out
    }

    /** Что нарисовано и написано на листе плана таблицы (середина предмета внутри листа). */
    fun planContents(t: BoardTable): List<Any> {
        val sr = tableDraw.sheetRect(b, t, RectF()) ?: return emptyList()
        val out = ArrayList<Any>()
        val r = RectF()
        fun inside(o: Any) { boundsOf(o, r); if (sr.contains(r.centerX(), r.centerY())) out += o }
        b.strokes.forEach { inside(it) }; b.texts.forEach { inside(it) }; b.fills.forEach { inside(it) }
        b.stickers.forEach { inside(it) }; b.callouts.forEach { inside(it) }
        return out
    }

    /** Показать на экране рамку доски (с полями). */
    fun fitRect(l: Float, t: Float, r: Float, bt: Float) {
        if (width == 0 || r <= l || bt <= t) return
        val pad = Ui.dpf(context, 48f)
        val z = min(width / ((r - l) + pad * 2 / b.camZ), height / ((bt - t) + pad * 2 / b.camZ)).coerceIn(MIN_ZOOM, 3f)
        b.camZ = z
        b.camX = (l + r) / 2f - width / 2f / z
        b.camY = (t + bt) / 2f - height / 2f / z
        host.onCameraChanged()
        invalidate()
    }

    // ---- Обрезка картинки (0.13) ----
    /** Картинку обрезают: видна целиком (скрытое — бледно), рамка с ручками по краям и углам. */
    var cropping: BoardImage? = null
        set(v) { field = v; sceneValid = false; invalidate() }
    /** Какие края тянем: 1 — левый, 2 — верхний, 4 — правый, 8 — нижний. */
    private var cropEdges = 0
    private val cropStart = RectF()

    /** Ручка рамки обрезки под точкой экрана: какие края она двигает (0 — никакие). */
    private fun cropHandleAt(m: BoardImage, px: Float, py: Float): Int {
        val l = sx(m.x); val t = sy(m.y); val r = sx(m.x + m.w); val bt = sy(m.y + m.h)
        val rr = Ui.dpf(context, 26f)
        fun near(ax: Float, ay: Float) = hypot(ax - px, ay - py) <= rr
        if (near(l, t)) return 1 or 2
        if (near(r, t)) return 4 or 2
        if (near(l, bt)) return 1 or 8
        if (near(r, bt)) return 4 or 8
        val inY = py >= t - rr && py <= bt + rr
        val inX = px >= l - rr && px <= r + rr
        if (inY && kotlin.math.abs(px - l) <= rr) return 1
        if (inY && kotlin.math.abs(px - r) <= rr) return 4
        if (inX && kotlin.math.abs(py - t) <= rr) return 2
        if (inX && kotlin.math.abs(py - bt) <= rr) return 8
        return 0
    }

    // ---- Стороны раздела (0.23.1): выделенный раздел — 8 ручек, каждая сторона тянется отдельно ----
    private var frameEdges = 0
    private val frameStart = RectF()

    private fun singleFrame(): BoardFrame? = (selection.singleOrNull() as? BoardFrame)?.takeIf { exists(it) }

    /** Ручка раздела под точкой экрана: какие стороны она двигает (1 — левая, 2 — верхняя, 4 — правая, 8 — нижняя). */
    private fun frameEdgeAt(f: BoardFrame, px: Float, py: Float): Int {
        val l = sx(f.x); val t = sy(f.y); val r = sx(f.x + f.w); val bt = sy(f.y + f.h)
        val rr = Ui.dpf(context, 24f)
        fun near(ax: Float, ay: Float) = hypot(ax - px, ay - py) <= rr
        if (near(l, t)) return 1 or 2
        if (near(r, t)) return 4 or 2
        if (near(l, bt)) return 1 or 8
        if (near(r, bt)) return 4 or 8
        val mx = (l + r) / 2f; val my = (t + bt) / 2f
        if (near(l, my)) return 1
        if (near(r, my)) return 4
        if (near(mx, t)) return 2
        if (near(mx, bt)) return 8
        return 0
    }

    // ---- Булавка закреплённой картинки ----
    private val pinR get() = Ui.dpf(context, 15f)
    private val pinIcon by lazy { context.getDrawable(R.drawable.ic_pin)!!.mutate().apply { setTint(Ui.ACCENT) } }

    /** Где на экране булавка картинки (правый верхний угол); null — картинка на экране слишком мала. */
    private fun pinCenter(m: BoardImage, out: PointF): Boolean {
        if (m.w * b.camZ < pinR * 4f || m.h * b.camZ < pinR * 3f) return false
        out.set(sx(m.x + m.w) - pinR * 1.5f, sy(m.y) + pinR * 1.5f)
        return true
    }

    /** Булавка закреплённой таблицы (0.23.1) — на правом верхнем углу таблицы. */
    private fun tablePinCenter(t: BoardTable, out: PointF): Boolean {
        tableDraw.bounds(b, t, rect2)
        if (rect2.width() * b.camZ < pinR * 4f) return false
        out.set(sx(rect2.right) - pinR * 0.3f, sy(rect2.top) + pinR * 0.3f)
        return true
    }

    /** Булавка под точкой экрана: закреплённая картинка или таблица. */
    fun pinAt(px: Float, py: Float): Any? {
        val c = PointF()
        for (i in b.tables.indices.reversed()) {
            val t = b.tables[i]
            if (!t.locked || !tablePinCenter(t, c)) continue
            if (hypot(c.x - px, c.y - py) <= pinR * 1.7f) return t
        }
        for (i in b.images.indices.reversed()) {
            val m = b.images[i]
            if (!m.locked || !pinCenter(m, c)) continue
            if (hypot(c.x - px, c.y - py) <= pinR * 1.7f) return m
        }
        return null
    }

    private fun pinTap(o: Any) { if (o is BoardTable) host.onTablePinTap(o) else if (o is BoardImage) host.onPinTap(o) }

    // ---- Защита от ладони ----
    // Пока перо над экраном (и ещё немного после), касания пальцем — это ладонь: их не слушаем.
    // Если ладонь легла раньше и успела что-то сдвинуть, а следом появилось перо, — сдвиг отменяется.
    private var lastPenTime = -100_000L
    private var fingerDownTime = -100_000L
    private val downCam = FloatArray(3)

    private var penReported = false

    private fun notePen() {
        lastPenTime = SystemClock.uptimeMillis()
        fingerArmed = false
        if (!penReported) { penReported = true; host.onPenSeen() }
    }

    private fun penNear() = SystemClock.uptimeMillis() - lastPenTime < PEN_GRACE_MS

    /** Касание шире ~17 мм — это ладонь (если планшет сообщает размер касания). */
    private fun palmSized(ev: MotionEvent, idx: Int) = ev.getTouchMajor(idx) > Ui.dpf(context, 110f)

    /**
     * Перо пришло, пока палец ещё на экране, — палец был ладонью. Перенос предметов отменяется
     * всегда (пальцем и пером одновременно предметы не двигают), сдвиг доски — если ладонь легла недавно.
     */
    private fun revertFingerIfRecent() {
        val recent = SystemClock.uptimeMillis() - fingerDownTime < PALM_REVERT_MS
        when (mode) {
            Mode.PAN, Mode.PINCH -> if (recent) {
                b.camX = downCam[0]; b.camY = downCam[1]; b.camZ = downCam[2]
                host.onCameraChanged()
            }
            Mode.MOVE_SEL, Mode.RESIZE, Mode.PINCH_SEL -> revertEdit()
            Mode.LEADER -> {
                // Конец указателя — на прежнее место.
                val c = leaderCallout
                if (c != null && leaderIndex * 2 + 1 < c.leaders.size) {
                    c.leaders[leaderIndex * 2] = leaderStart.x
                    c.leaders[leaderIndex * 2 + 1] = leaderStart.y
                }
                if (editStarted) host.onDragCancel()
                leaderCallout = null
            }
            else -> Unit
        }
        editStarted = false
        removeCallbacks(longPress)
        invalidate()
    }

    /** Вернуть выделенное в исходное положение (отмена жеста). */
    private fun revertEdit() {
        if (origs.isNotEmpty()) applyTransform(0f, 0f, 1f, 0f, 0f)
        if (editStarted) host.onDragCancel()
        editStarted = false
    }

    private var mode = Mode.NONE

    /** Никакого жеста сейчас нет — доску можно менять извне (правки коллег с сервера). */
    val isIdle: Boolean get() = mode == Mode.NONE
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
            val x = wx(downX); val y = wy(downY)
            val h = hitAt(x, y)
            // Метку и строку ИД подержали — «поднялась»: тянется за пальцем, уголки — размер, два пальца — масштаб.
            // Отпустили, не двигая, — на статусе список статусов (как раньше).
            if (h?.kind == Kind.VEDOM) {
                // Подержали строку ведомости — меню позиции; шапку — ведомость «поднялась».
                val v = b.vedoms[h.index]
                val vh = vedomDraw.hit(b, v, x, y)
                if (vh != null && vh.part == VedomHit.CELL) {
                    if (mode == Mode.MOVE_SEL && editStarted) host.onDragEnd()
                    editStarted = false
                    mode = Mode.NONE
                    performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    host.onVedomLongPress(v, vh)
                    return@Runnable
                }
            }
            if (h?.kind == Kind.TABLE) {
                // Подержали строку таблицы — меню строки; шапку — таблица «поднялась», тянется за пальцем.
                val t = b.tables[h.index]
                val th = tableDraw.hit(b, t, x, y)
                if (th != null && (th.part == TableHit.CELL || th.part == TableHit.ADD)) {
                    if (mode == Mode.MOVE_SEL && editStarted) host.onDragEnd()
                    editStarted = false
                    mode = Mode.NONE
                    performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    host.onTableLongPress(t, th)
                    return@Runnable
                }
            }
            val lift: Any? = when (h?.kind) {
                Kind.STICKER -> b.stickers.getOrNull(h.index); Kind.ROW -> b.rows.getOrNull(h.index); Kind.TABLE -> b.tables.getOrNull(h.index)
                Kind.VEDOM -> b.vedoms.getOrNull(h.index)
                else -> null
            }
            if (lift is BoardTable && lift.locked) {
                // Закреплённая таблица не поднимается — только выделяется (на панели — «Открепить»).
                mode = Mode.NONE
                select(listOf(lift))
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                return@Runnable
            }
            if (lift != null) {
                if (mode == Mode.PAN || !(selection.size == 1 && selection[0] === lift)) {
                    select(listOf(lift))
                    captureOrigs()
                    dragStart.set(x, y)
                    editStarted = false
                    mode = Mode.MOVE_SEL
                }
                fingerArmed = true
                liftMenu = if (lift is BoardSticker || (lift is BoardRow && rowPartAt(lift, x) == RowPart.STATUS)) lift else null
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                invalidate()
                return@Runnable
            }
            val status: Any? = when {
                h?.kind == Kind.STICKER -> b.stickers.getOrNull(h.index)
                h?.kind == Kind.CALLOUT && inStatusRow(b.callouts[h.index], x, y) -> b.callouts[h.index]
                else -> null
            }
            if (status != null || h?.kind == Kind.IMAGE) {
                if (mode == Mode.MOVE_SEL && editStarted) host.onDragEnd()
                editStarted = false
                mode = Mode.NONE
                if (status != null) host.onStatusMenu(status) else host.onImageLongPress(h!!.index)
            }
        }
    }

    init {
        if (Build.VERSION.SDK_INT >= 24) {
            pointerIcon = android.view.PointerIcon.getSystemIcon(context, android.view.PointerIcon.TYPE_NULL)
        }
    }

    companion object {
        /** Сколько после пера (касания или наведения) пальцы считаются ладонью. */
        const val PEN_GRACE_MS = 700L
        /** Если перо пришло так скоро после касания пальцем, сдвиг доски пальцем отменяется. */
        const val PALM_REVERT_MS = 1500L

        /**
         * Насколько можно отдалить доску (1 %) и приблизить (500 %). Доска бесконечная: отдалить далеко нужно, чтобы
         * большая доска (например, из Nebo — 3,5 × 5,5 м) не занимала весь экран и вокруг было видно свободное поле.
         * Издалека штрихи — готовыми плитками ([BoardTiles]), так что это не тормозит.
         */
        const val MIN_ZOOM = 0.01f
        const val MAX_ZOOM = 5f

        /** Строка ИД: шрифт и галочка при размере 1 (высота строки — 40). */
        const val ROW_FONT = 15f
        const val ROW_BOX = 22f
    }

    /** Загрузчик картинок (уровни, фон, память) — задаёт экран доски. */
    var images: BoardImages? = null

    /** Сколько мс в среднем занимает кадр и сколько объектов в нём нарисовано (для замеров). */
    var frameMs = 0f
        private set
    var drawnCount = 0
        private set
    var totalCount = 0
        private set

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
            val z = min(width / (bb.width() + pad * 2), height / (bb.height() + pad * 2)).coerceIn(MIN_ZOOM, 2f)
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
        for (x in b.texts) { add(x.x, x.y - x.size); add(x.x + textWidth(x), textBottom(x)) }
        for (m in b.images) { add(m.x, m.y); add(m.x + m.w, m.y + m.h) }
        for (fr in b.frames) { add(fr.x, fr.y); add(fr.x + fr.w, fr.y + fr.h) }
        for (s in b.stickers) { add(s.x, s.y); add(s.x + stickerW(s), s.y + s.h) }
        for (r in b.rows) { add(r.x, r.y); add(r.x + rowW(r), r.y + r.h) }
        for (t in b.tables) { tableDraw.bounds(b, t, rect2); add(rect2.left, rect2.top); add(rect2.right, rect2.bottom) }
        for (v in b.vedoms) { vedomDraw.bounds(b, v, rect2); add(rect2.left, rect2.top); add(rect2.right, rect2.bottom) }
        for (f in b.fills) { var i = 0; while (i + 1 < f.pts.size) { add(f.pts[i], f.pts[i + 1]); i += 2 } }
        for (c in b.callouts) {
            calloutRect(c, rect2); add(rect2.left, rect2.top); add(rect2.right, rect2.bottom)
            var i = 0; while (i + 1 < c.leaders.size) { add(c.leaders[i], c.leaders[i + 1]); i += 2 }
        }
        return if (l <= r) RectF(l, t, r, bt) else null
    }

    fun fade(strokes: List<InkStroke>) {
        fading += strokes to SystemClock.uptimeMillis()
        postInvalidateOnAnimation()
    }

    /** Ширина надписи (самой длинной строки). */
    fun textWidth(t: BoardText): Float {
        if (t.wText != t.text || t.wSize != t.size) {
            textPaint.textSize = t.size
            var w = 0f
            var n = 0
            for (line in t.text.split('\n')) { w = max(w, textPaint.measureText(line)); n++ }
            t.w = w
            t.nLines = n
            t.wText = t.text
            t.wSize = t.size
        }
        return t.w
    }

    /** Низ надписи (с учётом всех строк). */
    fun textBottom(t: BoardText): Float {
        textWidth(t)
        return t.y + (t.nLines - 1) * t.size * BoardText.LINE + t.size * 0.3f
    }

    /** Ширина одной строки текста при размере size. */
    fun lineWidth(size: Float, line: String): Float {
        textPaint.textSize = size
        return textPaint.measureText(line)
    }

    /** Ширина метки — под название её статуса. */
    fun stickerW(s: BoardSticker): Float = s.width(b.status(s.sid).name)

    // ---------------------------------------------------------------- выноски: размеры

    private fun titleSize(c: BoardCallout) = 15f * c.scale
    private fun statusSize(c: BoardCallout) = 14f * c.scale
    private fun rowH(c: BoardCallout) = 30f * c.scale

    /** Рамка выноски: две строки — надпись и статус. */
    fun calloutRect(c: BoardCallout, out: RectF): RectF {
        val name = b.status(c.sid).name
        val key = c.title + '\u0001' + name + '\u0001' + c.scale
        if (c.wKey != key) {
            textPaint.textSize = titleSize(c)
            val tw = textPaint.measureText(c.title.ifEmpty { " " })
            textPaint.textSize = statusSize(c)
            textPaint.isFakeBoldText = true
            val sw = textPaint.measureText(name)
            textPaint.isFakeBoldText = false
            val pad = 12f * c.scale
            c.w = max(150f * c.scale, max(tw, sw) + pad * 2)
            c.wKey = key
        }
        out.set(c.x, c.y, c.x + c.w, c.y + rowH(c) * 2)
        return out
    }

    /** Точка внутри нижней строки (статус) выноски. */
    fun inStatusRow(c: BoardCallout, x: Float, y: Float): Boolean {
        calloutRect(c, rect2)
        return x >= rect2.left && x <= rect2.right && y >= rect2.top + rowH(c) && y <= rect2.bottom
    }

    fun calloutAt(x: Float, y: Float): Int {
        val pad = Ui.dpf(context, 4f) / b.camZ
        for (i in b.callouts.indices.reversed()) {
            calloutRect(b.callouts[i], rect2)
            if (x >= rect2.left - pad && x <= rect2.right + pad && y >= rect2.top - pad && y <= rect2.bottom + pad) return i
        }
        return -1
    }

    /** Заливка, внутри которой точка (верхняя). */
    fun fillAt(x: Float, y: Float): Int {
        for (i in b.fills.indices.reversed()) if (insidePolygon(b.fills[i].pts, x, y)) return i
        return -1
    }

    /** Заливка, край которой рядом с точкой (для ластика). */
    fun fillBorderAt(x: Float, y: Float, r: Float): Int {
        for (i in b.fills.indices.reversed()) {
            val p = b.fills[i].pts
            val n = p.size / 2
            for (k in 0 until n) {
                val j = (k + 1) % n
                if (segDist(x, y, p[k * 2], p[k * 2 + 1], p[j * 2], p[j * 2 + 1]) <= r) return i
            }
        }
        return -1
    }

    private fun insidePolygon(p: FloatArray, x: Float, y: Float): Boolean {
        val n = p.size / 2
        if (n < 3) return false
        var c = false
        var j = n - 1
        for (i in 0 until n) {
            val xi = p[i * 2]; val yi = p[i * 2 + 1]; val xj = p[j * 2]; val yj = p[j * 2 + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) c = !c
            j = i
        }
        return c
    }

    /** Что лежит в точке доски (сверху вниз: метки, выноски, тексты, картинки). Штрихи — см. [selectableAt]. */
    fun hitAt(x: Float, y: Float): Hit? {
        for (i in b.vedoms.indices.reversed()) {
            vedomDraw.bounds(b, b.vedoms[i], rect2)
            if (rect2.contains(x, y)) return Hit(Kind.VEDOM, i)
        }
        for (i in b.tables.indices.reversed()) {
            tableDraw.bounds(b, b.tables[i], rect2)
            if (rect2.contains(x, y)) return Hit(Kind.TABLE, i)
        }
        for (i in b.rows.indices.reversed()) {
            val r = b.rows[i]
            if (x >= r.x && x <= r.x + rowW(r) && y >= r.y && y <= r.y + r.h) return Hit(Kind.ROW, i)
        }
        for (i in b.stickers.indices.reversed()) {
            val s = b.stickers[i]
            if (x >= s.x && x <= s.x + stickerW(s) && y >= s.y && y <= s.y + s.h) return Hit(Kind.STICKER, i)
        }
        val ci = calloutAt(x, y)
        if (ci >= 0) return Hit(Kind.CALLOUT, ci)
        for (i in b.texts.indices.reversed()) {
            val t = b.texts[i]
            if (x >= t.x && x <= t.x + textWidth(t) && y >= t.y - t.size && y <= textBottom(t)) return Hit(Kind.TEXT, i)
        }
        for (i in b.images.indices.reversed()) {
            val m = b.images[i]
            if (m.locked) continue // закреплённую не выделить и не удалить
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

    /** То, что можно выделить, в точке: метки, выноски, надписи, штрихи, заливки (поверх картинок), картинки. */
    fun selectableAt(x: Float, y: Float): Any? {
        frameAt(sx(x), sy(y), border = false)?.let { return it } // ярлык раздела
        val h = hitAt(x, y)
        if (h != null && h.kind != Kind.IMAGE) return objectOf(h)
        val s = strokeAt(x, y)
        if (s >= 0) return b.strokes[s]
        val f = fillAt(x, y)
        if (f >= 0) return b.fills[f]
        frameAt(sx(x), sy(y), border = true)?.let { return it } // край рамки раздела
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
        Kind.CALLOUT -> b.callouts.getOrNull(h.index)
        Kind.FILL -> b.fills.getOrNull(h.index)
        Kind.ROW -> b.rows.getOrNull(h.index)
        Kind.TABLE -> b.tables.getOrNull(h.index)
        Kind.VEDOM -> b.vedoms.getOrNull(h.index)
    }

    // ---------------------------------------------------------------- выделение

    /** Выделенные объекты (сами объекты доски: BoardText, BoardImage, BoardSticker, Drawing). */
    val selection = ArrayList<Any>()

    /**
     * Выделенное можно тащить одним пальцем, только если его только что выделили касанием пальца.
     * Вставленную картинку или выделенное лассо ладонь не утащит (тащить — пером в «Выделить»
     * или сначала коснуться пальцем). Перо рядом — снова нельзя.
     */
    private var fingerArmed = false

    fun select(objs: List<Any>) {
        fingerArmed = false
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
        is BoardFill -> b.fills.any { it === o }
        is BoardCallout -> b.callouts.any { it === o }
        is BoardFrame -> b.frames.any { it === o }
        is BoardRow -> b.rows.any { it === o }
        is BoardTable -> b.tables.any { it === o }
        is BoardRec -> b.recs.any { it === o } && IdTables.tableOf(b, o) != null
        is BoardVedom -> b.vedoms.any { it === o }
        is BoardVItem -> b.vitems.any { it === o } && Vedoms.vedom(b, o.v) != null
        else -> false
    }

    fun boundsOf(o: Any, out: RectF): RectF {
        when (o) {
            is BoardText -> out.set(o.x, o.y - o.size, o.x + textWidth(o), textBottom(o))
            is BoardImage -> out.set(o.x, o.y, o.x + o.w, o.y + o.h)
            is BoardSticker -> out.set(o.x, o.y, o.x + stickerW(o), o.y + o.h)
            is BoardCallout -> calloutRect(o, out)
            is BoardFill -> out.set(o.bounds())
            is Drawing -> out.set(o.bounds())
            is BoardFrame -> out.set(o.x, o.y, o.x + o.w, o.y + o.h)
            is BoardRow -> out.set(o.x, o.y, o.x + rowW(o), o.y + o.h)
            is BoardTable -> tableDraw.bounds(b, o, out)
            is BoardRec -> IdTables.tableOf(b, o)?.let { t -> tableDraw.rowRect(b, t, o, out) } ?: out.setEmpty()
            is BoardVedom -> vedomDraw.bounds(b, o, out)
            is BoardVItem -> Vedoms.vedom(b, o.v)?.let { v -> vedomDraw.rowRect(b, v, o, out) } ?: out.setEmpty()
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
    /** Подняли удержанием метку или ИГС строки: отпустили, не двигая, — список статусов. */
    private var liftMenu: Any? = null

    /** withContents — раздел переносится вместе со всем, что внутри (при переносе, не при изменении размера). */
    private fun captureOrigs(withContents: Boolean = false) {
        origs.clear()
        val objs = ArrayList<Any>(selection)
        if (withContents) {
            val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
            seen.addAll(selection)
            for (f in selection.filterIsInstance<BoardFrame>()) for (o in frameContents(f)) if (seen.add(o)) objs += o
        }
        // Рисунки и надписи на листе плана таблицы (0.23.1) едут вместе с таблицей — и при переносе, и при размере.
        run {
            val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
            seen.addAll(objs)
            for (t in selection.filterIsInstance<BoardTable>()) {
                if (t.locked) continue
                for (o in planContents(t)) if (seen.add(o)) objs += o
            }
        }
        for (o in objs) {
            if (o is BoardTable && o.locked) continue
            origs += when (o) {
                is BoardText -> Orig(o, floatArrayOf(o.x, o.y, o.size), 0f)
                is BoardImage -> Orig(o, floatArrayOf(o.x, o.y, o.w, o.h), 0f)
                is BoardSticker -> Orig(o, floatArrayOf(o.x, o.y, o.scale), 0f)
                is BoardRow -> Orig(o, floatArrayOf(o.x, o.y, o.scale), 0f)
                is BoardTable -> Orig(o, floatArrayOf(o.x, o.y, o.scale), 0f)
                is BoardVedom -> Orig(o, floatArrayOf(o.x, o.y, o.scale), 0f)
                is Drawing -> Orig(o, o.pts.copyOf(), o.width)
                is BoardFill -> Orig(o, o.pts.copyOf(), 0f)
                is BoardCallout -> Orig(o, floatArrayOf(o.x, o.y, o.scale) + o.leaders.toFloatArray(), 0f)
                is BoardFrame -> Orig(o, floatArrayOf(o.x, o.y, o.w, o.h), 0f)
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
                is BoardFrame -> { o.x = tx(a[0]); o.y = ty(a[1]); o.w = a[2] * s; o.h = a[3] * s }
                is BoardSticker -> { o.x = tx(a[0]); o.y = ty(a[1]); o.scale = a[2] * s }
                is BoardRow -> { o.x = tx(a[0]); o.y = ty(a[1]); o.scale = a[2] * s }
                is BoardTable -> { o.x = tx(a[0]); o.y = ty(a[1]); o.scale = a[2] * s }
                is BoardVedom -> { o.x = tx(a[0]); o.y = ty(a[1]); o.scale = a[2] * s }
                is Drawing -> {
                    var i = 0
                    while (i + 1 < a.size && i + 1 < o.pts.size) {
                        o.pts[i] = tx(a[i]); o.pts[i + 1] = ty(a[i + 1])
                        i += 3
                    }
                    o.width = or.w * s
                    o.changed()
                }
                is BoardFill -> {
                    var i = 0
                    while (i + 1 < a.size && i + 1 < o.pts.size) {
                        o.pts[i] = tx(a[i]); o.pts[i + 1] = ty(a[i + 1])
                        i += 2
                    }
                    o.changed()
                }
                is BoardCallout -> {
                    o.x = tx(a[0]); o.y = ty(a[1]); o.scale = a[2] * s
                    // Одну выноску двигают — указатели остаются на своих местах чертежа;
                    // вместе с другими объектами — едут вместе со всем.
                    if (origs.size > 1) {
                        var i = 3
                        while (i + 1 < a.size && i - 2 < o.leaders.size) {
                            o.leaders[i - 3] = tx(a[i]); o.leaders[i - 2] = ty(a[i + 1])
                            i += 2
                        }
                    }
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
        // Ярлык выделенного раздела (он над рамкой) — тоже «внутри»: за него раздел и тянут.
        if (selection.any { it is BoardFrame && frameLabelRect(it, rect).contains(sx(x), sy(y)) }) return true
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
        for (o in b.images) { if (o.locked) continue; boundsOf(o, rect2); if (inside(rect2.centerX(), rect2.centerY())) out += o }
        for (o in b.fills) { boundsOf(o, rect2); if (inside(rect2.centerX(), rect2.centerY())) out += o }
        for (o in b.callouts) { boundsOf(o, rect2); if (inside(rect2.centerX(), rect2.centerY())) out += o }
        for (o in b.strokes) { boundsOf(o, rect2); if (inside(rect2.centerX(), rect2.centerY())) out += o }
        for (o in b.texts) { boundsOf(o, rect2); if (inside(rect2.centerX(), rect2.centerY())) out += o }
        for (o in b.stickers) { boundsOf(o, rect2); if (inside(rect2.centerX(), rect2.centerY())) out += o }
        for (o in b.frames) { boundsOf(o, rect2); if (inside(rect2.centerX(), rect2.centerY())) out += o }
        for (o in b.rows) { boundsOf(o, rect2); if (inside(rect2.centerX(), rect2.centerY())) out += o }
        for (o in b.tables) {
            boundsOf(o, rect2)
            if (inside(rect2.left + 20f * o.scale, rect2.top + 19f * o.scale)) { if (!o.locked) out += o; continue }
            // Обвели номера захваток — выделены строки (ТЗ, раздел 14.3): статус всем, «Не выполнено»…
            for (r in IdTables.rowsOf(b, o)) {
                val rr = tableDraw.rowRect(b, o, r, rect2) ?: continue
                if (inside(rr.left + 30f * o.scale, rr.centerY())) out += r
            }
        }
        for (o in b.vedoms) {
            boundsOf(o, rect2)
            if (inside(rect2.left + 20f * o.scale, rect2.top + 19f * o.scale)) { out += o; continue }
            // Обвели строки ведомости — ответ всем выделенным разом.
            for (i in Vedoms.items(b, o)) {
                val rr = vedomDraw.rowRect(b, o, i, rect2) ?: continue
                if (inside(rr.left + 17f * o.scale, rr.centerY())) out += i
            }
        }
        select(out)
    }

    // ---------------------------------------------------------------- касания

    private fun isPen(tool: Int) = tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER

    private fun penLike(tool: Int) = isPen(tool) || tool == MotionEvent.TOOL_TYPE_MOUSE || host.fingerWrites()

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        for (i in 0 until ev.pointerCount) if (isPen(ev.getToolType(i))) { notePen(); break }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> begin(ev, 0)
            MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = ev.actionIndex
                val tool = ev.getToolType(idx)
                val finger = !isPen(tool)
                val fingerMode = !activeIsPen && (mode == Mode.MOVE_SEL || mode == Mode.RESIZE || mode == Mode.PINCH_SEL || mode == Mode.LEADER)
                if (isPen(tool) && (mode == Mode.PAN || mode == Mode.PINCH || mode == Mode.NONE || mode == Mode.PALM || fingerMode)) {
                    revertFingerIfRecent()
                    begin(ev, idx) // ладонь или пальцы лежали — работает перо
                } else if (finger && !host.fingerWrites() && (penNear() || palmSized(ev, idx) || mode == Mode.PALM)) {
                    // второй палец при пере рядом — тоже ладонь
                } else if (finger && !activeIsPen && mode == Mode.PLAN) {
                    // Обводили пальцем, лёг второй — двигают или приближают доску.
                    planEdit?.let { it.from = null; it.to = null }
                    cancelTouchAction()
                    startPinch(ev)
                } else if (finger && !activeIsPen && mode == Mode.MOVE_SEL) {
                    startSelPinch(ev) // два пальца на выделенном — меняем его размер
                } else if (finger && !activeIsPen && (mode == Mode.PAN || mode == Mode.LASSO)) {
                    lasso.clear()
                    startPinch(ev)
                } else if (finger && !activeIsPen && host.fingerWrites() &&
                    (mode == Mode.INK || mode == Mode.ERASE || mode == Mode.CALLOUT_DRAG || mode == Mode.FRAME_DRAG || mode == Mode.POINT || mode == Mode.STATUS || mode == Mode.NONE)) {
                    // Палец рисовал, лёг второй — значит, двигают или приближают доску: штрих не нужен.
                    cancelTouchAction()
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
                // Система отменила жест (например, распознала ладонь) — сдвинутое возвращаем на место.
                if (mode == Mode.MOVE_SEL || mode == Mode.RESIZE || mode == Mode.PINCH_SEL) revertEdit()
                else if (editStarted) host.onDragEnd()
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

    /** Отменить начатое пальцем (штрих, выноску, касание метки) — пришёл второй палец. */
    private fun cancelTouchAction() {
        removeCallbacks(longPress)
        if (mode == Mode.INK && live != null) {
            live = null
            host.onStrokeCancel()
        }
        calloutFrom = null
        lasso.clear()
        mode = Mode.NONE
        sceneValid = false
        invalidate()
    }

    private fun begin(ev: MotionEvent, idx: Int) {
        removeCallbacks(longPress)
        val tool = ev.getToolType(idx)
        activePointer = ev.getPointerId(idx)
        activeIsPen = isPen(tool)
        lastTapByPen = activeIsPen || tool == MotionEvent.TOOL_TYPE_MOUSE
        downX = ev.getX(idx); downY = ev.getY(idx)
        lastX = downX; lastY = downY
        downTime = SystemClock.uptimeMillis()
        moved = false
        hover = null
        editStarted = false
        val x = wx(downX)
        val y = wy(downY)
        val t = host.tool()
        // «Рука» — пером и пальцем двигать доску и выделять; иначе перо (или палец без пера) — инструмент.
        val hand = t == BoardActivity.Tool.HAND
        val pen = penLike(tool) && !hand
        if (!isPen(tool) && tool != MotionEvent.TOOL_TYPE_MOUSE) {
            fingerDownTime = downTime
            downCam[0] = b.camX; downCam[1] = b.camY; downCam[2] = b.camZ
            if (!host.fingerWrites() && (penNear() || palmSized(ev, idx))) {
                mode = Mode.PALM // перо рядом или касание широкое — это ладонь
                return
            }
        }

        if (t == BoardActivity.Tool.PIPETTE) {
            mode = Mode.PIPETTE
            hover = PointF(downX, downY)
            invalidate()
            return
        }
        // Обрезка: ручки рамки — тянуть края (пером или пальцем); в остальном месте — двигать доску.
        cropping?.let { m ->
            if (!exists(m)) { cropping = null; return@let }
            parent?.requestDisallowInterceptTouchEvent(true)
            val e = cropHandleAt(m, downX, downY)
            if (e != 0) {
                cropEdges = e
                cropStart.set(m.x, m.y, m.x + m.w, m.y + m.h)
                mode = Mode.CROP
            } else {
                mode = Mode.PAN
            }
            return
        }
        // Обводка захватки: касания по листу плана — в обводку (пером и пальцем), вне листа — как обычно.
        planEdit?.let { pe ->
            val r = tableDraw.sheetRect(b, pe.t, planRect)
            if (r != null && b.tables.any { it === pe.t } && r.contains(x, y)) {
                parent?.requestDisallowInterceptTouchEvent(true)
                mode = Mode.PLAN
                // Пальцем по точкам, прямоугольником или кругом — лупа с перекрестием и привязкой (палец закрывает угол).
                planAim = !isPen(tool) && tool != MotionEvent.TOOL_TYPE_MOUSE &&
                    (pe.tool == PlanEdit.POINTS || pe.tool == PlanEdit.RECT || pe.tool == PlanEdit.CIRCLE)
                if (planAim) { updateAim(downX, downY); sceneValid = false }
                val p0 = if (planAim) PointF(aim.x, aim.y) else PointF(x, y)
                pe.from = p0; pe.to = PointF(p0.x, p0.y)
                pe.path.clear(); pe.path += x; pe.path += y
                invalidate()
                return
            }
        }
        // Конец указателя выделенной выноски — тянуть.
        val sc = selection.singleOrNull() as? BoardCallout
        if (sc != null) {
            val r = Ui.dpf(context, 24f)
            for (i in 0 until sc.leaderCount) {
                if (hypot(sx(sc.leaders[i * 2]) - downX, sy(sc.leaders[i * 2 + 1]) - downY) <= r) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    leaderCallout = sc
                    leaderIndex = i
                    leaderStart.set(sc.leaders[i * 2], sc.leaders[i * 2 + 1])
                    mode = Mode.LEADER
                    return
                }
            }
        }
        // Выделенный раздел: углы и середины сторон — менять рамку (стороны по отдельности, без масштаба содержимого).
        singleFrame()?.let { f ->
            val e = frameEdgeAt(f, downX, downY)
            if (e != 0) {
                parent?.requestDisallowInterceptTouchEvent(true)
                frameEdges = e
                frameStart.set(f.x, f.y, f.x + f.w, f.y + f.h)
                mode = Mode.FRAME_EDGE
                return
            }
        }
        // Уголок рамки выделения — пером или пальцем, при любом инструменте.
        if (selection.isNotEmpty() && movable()) {
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
            if (insideSelection(x, y) && movable()) {
                captureOrigs(withContents = true)
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
            // Палец («Рука»): выделенное (если его выделили касанием) — двигать, иначе — двигать доску.
            if ((fingerArmed || (hand && isPen(tool))) && insideSelection(x, y) && movable()) {
                captureOrigs(withContents = true)
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
        // Начали писать, рисовать или обводить пером — выделение снимаем, чтобы ладонь его не утащила.
        if (selection.isNotEmpty()) clearSelection()
        if (t == BoardActivity.Tool.CALLOUT && tool != MotionEvent.TOOL_TYPE_ERASER) {
            val ci = calloutAt(x, y)
            calloutFrom = if (ci >= 0) b.callouts[ci] else null
            calloutDown.set(x, y)
            calloutNow.set(x, y)
            mode = Mode.CALLOUT_DRAG
            return
        }
        if (t == BoardActivity.Tool.FILL && !isPen(tool) && tool != MotionEvent.TOOL_TYPE_MOUSE) {
            // Палец ставит точки заливки: лупа и привязка (палец закрывает угол).
            mode = Mode.POINT
            updateAim(downX, downY)
            sceneValid = false
            invalidate()
            return
        }
        if (t == BoardActivity.Tool.FRAME && tool != MotionEvent.TOOL_TYPE_ERASER) {
            frameDown.set(x, y)
            frameNow.set(x, y)
            mode = Mode.FRAME_DRAG
            return
        }
        if (tool == MotionEvent.TOOL_TYPE_ERASER || t == BoardActivity.Tool.ERASER) {
            mode = Mode.ERASE
            host.onErase(x, y, true)
            return
        }
        if (t == BoardActivity.Tool.STATUS || t == BoardActivity.Tool.TEXT || t == BoardActivity.Tool.ROW || t == BoardActivity.Tool.TABLE) {
            mode = Mode.STATUS // касание: метка, строка ИД или надпись
            return
        }
        mode = Mode.INK
        sceneValid = false // картинка доски снимется заново в первом кадре штриха
        host.onStrokeStart(x, y)
        clockOffset = System.currentTimeMillis() - SystemClock.uptimeMillis()
        val st = host.strokeStyle()
        liveStyle = NoteInkView.StrokeStyle(st.kind, st.color, st.width / b.camZ)
        live = InkStroke(tool).also { it.points.add(InkPoint(x, y, ev.eventTime + clockOffset, pressure(ev, idx, -1))) }
        invalidate()
    }

    // «Выноска»: откуда ведут линию (место на чертеже или рамка выноски) и где перо сейчас.
    private var calloutFrom: BoardCallout? = null
    private val calloutDown = PointF()
    private val calloutNow = PointF()
    private var leaderCallout: BoardCallout? = null
    private val leaderStart = PointF()
    private var leaderIndex = -1

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
            Mode.FRAME_EDGE -> {
                if (!moved) return
                val f = singleFrame() ?: return
                if (!editStarted) { editStarted = true; host.onDragStart() }
                val dx = wx(sxp) - wx(downX); val dy = wy(syp) - wy(downY)
                val min = Ui.dpf(context, 40f) / b.camZ
                var l = frameStart.left; var t = frameStart.top; var r = frameStart.right; var bt = frameStart.bottom
                if (frameEdges and 1 != 0) l = min(frameStart.left + dx, r - min)
                if (frameEdges and 4 != 0) r = max(frameStart.right + dx, l + min)
                if (frameEdges and 2 != 0) t = min(frameStart.top + dy, bt - min)
                if (frameEdges and 8 != 0) bt = max(frameStart.bottom + dy, t + min)
                f.x = l; f.y = t; f.w = r - l; f.h = bt - t
                invalidate()
            }
            Mode.CROP -> {
                if (!moved) return
                val m = cropping ?: return
                if (!editStarted) { editStarted = true; host.onDragStart() }
                val dx = wx(sxp) - wx(downX)
                val dy = wy(syp) - wy(downY)
                val minW = m.fullW() * BoardImage.MIN_CROP * 2
                val minH = m.fullH() * BoardImage.MIN_CROP * 2
                var l = cropStart.left; var t = cropStart.top; var r = cropStart.right; var bt = cropStart.bottom
                if (cropEdges and 1 != 0) l = min(l + dx, r - minW)
                if (cropEdges and 4 != 0) r = max(r + dx, l + minW)
                if (cropEdges and 2 != 0) t = min(t + dy, bt - minH)
                if (cropEdges and 8 != 0) bt = max(bt + dy, t + minH)
                m.setVisible(l, t, r, bt)
                invalidate()
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
            Mode.CALLOUT_DRAG -> {
                calloutNow.set(wx(sxp), wy(syp))
                invalidate()
            }
            Mode.FRAME_DRAG -> {
                frameNow.set(wx(sxp), wy(syp))
                invalidate()
            }
            Mode.PLAN -> {
                val pe = planEdit
                if (pe != null) {
                    if (planAim) { updateAim(sxp, syp); pe.to?.set(aim.x, aim.y) } else pe.to?.set(wx(sxp), wy(syp))
                    if (pe.tool == PlanEdit.BRUSH) { pe.path += wx(sxp); pe.path += wy(syp) }
                }
                invalidate()
            }
            Mode.POINT -> {
                updateAim(sxp, syp)
                invalidate()
            }
            Mode.LEADER -> {
                if (!moved) return
                val c = leaderCallout ?: return
                if (!editStarted) { editStarted = true; host.onDragStart() }
                if (leaderIndex * 2 + 1 < c.leaders.size) {
                    c.leaders[leaderIndex * 2] = wx(sxp)
                    c.leaders[leaderIndex * 2 + 1] = wy(syp)
                }
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
            Mode.STATUS -> if (quick) {
                if (t == BoardActivity.Tool.TEXT) host.onTextTap(wx(sxp), wy(syp)) else host.onStatusTap(wx(sxp), wy(syp))
            }
            Mode.PAN -> if (quick && !moved && cropping == null) tapAt(wx(sxp), wy(syp))
            Mode.CROP -> if (editStarted) host.onDragEnd()
            Mode.FRAME_EDGE -> if (editStarted) host.onDragEnd()
            Mode.PLAN -> planEdit?.let { pe ->
                val f = pe.from
                if (planAim) updateAim(sxp, syp)
                val ex = if (planAim) aim.x else wx(sxp); val ey = if (planAim) aim.y else wy(syp)
                if (pe.tool == PlanEdit.BRUSH) { pe.path += wx(sxp); pe.path += wy(syp) }
                pe.from = null; pe.to = null
                planAim = false
                // Пальцем с лупой точку ставят и после движения (палец ведёт перекрестие).
                val tap = !moved || (pe.tool == PlanEdit.POINTS && f != null)
                if (f != null) host.onPlanGesture(if (pe.tool == PlanEdit.POINTS) ex else f.x, if (pe.tool == PlanEdit.POINTS) ey else f.y, ex, ey, tap)
                invalidate()
            }
            Mode.POINT -> {
                updateAim(sxp, syp)
                host.onFillPoint(aim.x, aim.y)
            }
            Mode.FRAME_DRAG -> {
                val x = wx(sxp); val y = wy(syp)
                if (kotlin.math.abs(x - frameDown.x) * b.camZ > Ui.dpf(context, 40f) && kotlin.math.abs(y - frameDown.y) * b.camZ > Ui.dpf(context, 40f)) {
                    host.onFrameCreate(min(x, frameDown.x), min(y, frameDown.y), max(x, frameDown.x), max(y, frameDown.y))
                } else {
                    host.onFrameCreate(0f, 0f, 0f, 0f) // слишком маленькая — подсказать
                }
            }
            Mode.MOVE_SEL, Mode.RESIZE -> {
                if (editStarted) {
                    host.onDragEnd()
                } else if (liftMenu != null && mode == Mode.MOVE_SEL) {
                    host.onStatusMenu(liftMenu!!)
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
                    val pin = pinAt(sxp, syp)
                    val o = if (pin != null) null else selectableAt(wx(sxp), wy(syp))
                    if (pin != null) pinTap(pin) else if (o != null) select(listOf(o)) else clearSelection()
                } else {
                    lassoSelect()
                }
                lasso.clear()
            }
            Mode.PIPETTE -> {
                hover = null
                host.onPipettePick(sxp, syp)
            }
            Mode.CALLOUT_DRAG -> {
                val x = wx(sxp); val y = wy(syp)
                val from = calloutFrom
                calloutFrom = null
                if (from != null && exists(from)) {
                    if (moved && calloutAt(x, y) < 0) {
                        // От рамки — ещё один указатель.
                        host.onDragStart()
                        from.addLeader(x, y)
                        host.onDragEnd()
                        select(listOf(from))
                    } else {
                        select(listOf(from))
                    }
                } else {
                    host.onCalloutCreate(calloutDown.x, calloutDown.y, x, y, moved)
                }
            }
            Mode.LEADER -> {
                val c = leaderCallout
                if (c != null && editStarted) {
                    // Конец указателя утащили на рамку — убираем указатель.
                    calloutRect(c, rect2)
                    val x = wx(sxp); val y = wy(syp)
                    if (rect2.contains(x, y) && leaderIndex * 2 + 1 < c.leaders.size) {
                        c.leaders.removeAt(leaderIndex * 2 + 1)
                        c.leaders.removeAt(leaderIndex * 2)
                    }
                    host.onDragEnd()
                }
                leaderCallout = null
            }
            else -> Unit
        }
        editStarted = false
        liftMenu = null
        mode = Mode.NONE
        sceneValid = false
        origs.clear() // исходные положения нужны только во время жеста
        invalidate()
    }

    /**
     * Короткое касание пальцем: метка или строка статуса выноски — следующий статус,
     * объект — выделить, пусто — снять выделение.
     */
    private fun tapAt(x: Float, y: Float) {
        pinAt(sx(x), sy(y))?.let { pinTap(it); return }
        val h = hitAt(x, y)
        if (h?.kind == Kind.STICKER && host.tool() != BoardActivity.Tool.SELECT) {
            clearSelection()
            host.onStickerTap(h.index)
            return
        }
        if (h?.kind == Kind.VEDOM && host.tool() != BoardActivity.Tool.SELECT) {
            val v = b.vedoms[h.index]
            val vh = vedomDraw.hit(b, v, x, y)
            if (vh != null && vh.part != VedomHit.TITLE && vh.part != VedomHit.HEAD) {
                host.onVedomTap(v, vh)
                return
            }
        }
        if (h?.kind == Kind.TABLE && host.tool() != BoardActivity.Tool.SELECT) {
            val t = b.tables[h.index]
            val th = tableDraw.hit(b, t, x, y)
            if (th != null && th.part != TableHit.TITLE && th.part != TableHit.HEAD) {
                host.onTableTap(t, th)
                return
            }
        }
        if (h?.kind == Kind.ROW && host.tool() != BoardActivity.Tool.SELECT) {
            val r = b.rows[h.index]
            clearSelection()
            host.onRowTap(r, rowPartAt(r, x))
            return
        }
        if (h?.kind == Kind.CALLOUT && host.tool() != BoardActivity.Tool.SELECT) {
            val c = b.callouts[h.index]
            if (inStatusRow(c, x, y)) {
                host.onCalloutStatusTap(c)
                return
            }
        }
        val o = selectableAt(x, y)
        when {
            o == null -> {
                clearSelection(); host.onEmptyTap()
                if (markedRecs != null || markedItems != null || markedFills != null || markedZones != null) {
                    markedRecs = null; markedItems = null; markedFills = null; markedZones = null; invalidate()
                }
            }
            // Уже выделено (например, только что вставленная картинка): первое касание пальцем
            // разрешает тащить, второе — снимает выделение.
            selection.size == 1 && selection[0] === o -> if (fingerArmed) clearSelection() else fingerArmed = true
            else -> { select(listOf(o)); fingerArmed = true }
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
        val z = (pinchZ0 * d / pinchD0).coerceIn(MIN_ZOOM, MAX_ZOOM)
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
                b.camZ = (b.camZ * (1f + v * 0.1f)).coerceIn(MIN_ZOOM, MAX_ZOOM)
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
        if (isPen(event.getToolType(0))) {
            notePen()
            // Ладонь уже лежит и что-то двигает, а перо подошло — это была ладонь.
            if (!activeIsPen && mode != Mode.NONE && mode != Mode.PALM) {
                revertFingerIfRecent()
                mode = Mode.PALM
            }
        }
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

    // Видимая часть доски (в единицах доски) — рисуем только то, что в неё попадает.
    private val vis = RectF()

    private fun visible(l: Float, t: Float, r: Float, bt: Float) = r >= vis.left && l <= vis.right && bt >= vis.top && t <= vis.bottom

    // Пока ведут пером, всё остальное не меняется: доска рисуется один раз в картинку,
    // а каждый кадр — эта картинка плюс сам штрих. Письмо остаётся плавным даже на плотной доске.
    private var scene: Bitmap? = null
    private var sceneValid = false
    private var capturing = false

    private fun renderScene(): Boolean {
        if (width <= 0 || height <= 0) return false
        val bmp = scene?.takeIf { it.width == width && it.height == height }
            ?: try { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) } catch (_: Throwable) { return false }
        scene = bmp
        capturing = true
        try {
            onDraw(Canvas(bmp))
        } finally {
            capturing = false
        }
        sceneValid = true
        return true
    }

    /** Плитки штрихов (FW-009): штрихи рисуются заранее в картинки, кадр собирается из них. */
    private val tiles = BoardTiles { invalidate() }
    private val selDrawings = HashSet<Drawing>()
    private val liveClip = RectF()

    /** Штрихи прямоугольника r напрямую (плитка там ещё не готова) — с обрезкой по r. */
    private fun drawStrokesIn(canvas: Canvas, r: RectF, fast: Boolean, minW: Float) {
        liveClip.set(max(r.left, vis.left), max(r.top, vis.top), min(r.right, vis.right), min(r.bottom, vis.bottom))
        if (liveClip.isEmpty) return
        canvas.save()
        canvas.clipRect(liveClip)
        for (d in b.strokes) {
            if (selDrawings.isNotEmpty() && d in selDrawings) continue
            val bb = d.bounds()
            if (bb.right < liveClip.left || bb.left > liveClip.right || bb.bottom < liveClip.top || bb.top > liveClip.bottom) continue
            painter.drawing(canvas, d, 255, fast, minW)
            liveCount++
        }
        canvas.restore()
    }

    /** Плитки включены (выключаются только в замерах — сравнить с прежней отрисовкой). */
    var useTiles = true

    /** Для проверок: рисовать в картинку так же, как на экране планшета (не готовые плитки — не ждать). */
    var asyncTiles = false

    /** Сколько штрихов в последнем кадре нарисовано напрямую, мимо плиток (для проверок). */
    var liveCount = 0
        private set

    /** Экран доски закрывается — остановить фоновую отрисовку плиток. */
    fun release() = tiles.close()

    /** Плитки ещё рисуются (для проверок — дождаться). */
    val tilesBusy: Boolean get() = tiles.busy

    override fun onDraw(canvas: Canvas) {
        val t0 = System.nanoTime()
        val z = b.camZ
        if (mode == Mode.POINT && !plain && !capturing && (sceneValid || renderScene())) {
            // Точная точка: доска готовой картинкой, поверх — точки, резинка до пальца и лупа.
            canvas.drawBitmap(scene!!, 0f, 0f, null)
            canvas.save()
            canvas.scale(z, z)
            canvas.translate(-b.camX, -b.camY)
            drawFillDraft(canvas, z)
            drawAim(canvas, z)
            canvas.restore()
            drawLoupe(canvas)
            return
        }
        if (mode == Mode.INK && !plain && !capturing && fading.isEmpty() && (sceneValid || renderScene())) {
            canvas.drawBitmap(scene!!, 0f, 0f, null)
            canvas.save()
            canvas.scale(z, z)
            canvas.translate(-b.camX, -b.camY)
            val pendingStyle = NoteInkView.StrokeStyle("write", Ui.INK, Ui.dpf(context, 2.6f) / z)
            live?.let { painter.stroke(canvas, it, liveStyle ?: pendingStyle, 255) }
            drawInterim(canvas, z)
            drawFillDraft(canvas, z)
            canvas.restore()
            val ms = (System.nanoTime() - t0) / 1e6f
            frameMs = if (frameMs == 0f) ms else frameMs * 0.8f + ms * 0.2f
            return
        }
        canvas.drawColor(Ui.PAPER)
        vis.set(b.camX, b.camY, b.camX + width / z, b.camY + height / z)
        var drawn = 0
        canvas.save()
        canvas.scale(z, z)
        canvas.translate(-b.camX, -b.camY)
        drawGrid(canvas, z)
        for (m in b.images) if (visible(m.x, m.y, m.x + m.w, m.y + m.h)) { drawImage(canvas, m, z); drawn++ }
        for (f in b.fills) { val r = f.bounds(); if (visible(r.left, r.top, r.right, r.bottom)) { drawFill(canvas, f, z); drawn++ } }
        // Номера захваток на заливках и подсветка заливок захватки (0.22).
        markedFills?.let { mf -> for (f in mf) { val r = f.bounds(); if (b.fills.any { it === f } && visible(r.left, r.top, r.right, r.bottom)) drawFillMark(canvas, f, z) } }
        for (f in b.fills) if (f.lt.isNotEmpty()) { val r = f.bounds(); if (visible(r.left, r.top, r.right, r.bottom)) drawFillLabel(canvas, f, z) }
        for (fr in b.frames) if (visible(fr.x, fr.y, fr.x + fr.w, fr.y + fr.h)) {
            linePaint.color = Ui.INK2
            linePaint.alpha = 170
            linePaint.strokeWidth = Ui.dpf(context, 1.5f) / z
            canvas.drawRect(fr.x, fr.y, fr.x + fr.w, fr.y + fr.h, linePaint)
            linePaint.alpha = 255
            drawn++
        }
        // Таблицы и ведомости (0.23.1) — под штрихами: рисунки пером на плане таблицы видны поверх.
        for (t in b.tables) {
            tableDraw.bounds(b, t, rect2)
            if (visible(rect2.left, rect2.top, rect2.right, rect2.bottom)) {
                tableDraw.draw(canvas, b, t, z, vis, marked = markedRecs, selected = selection.takeIf { s -> s.any { it is BoardRec } }); drawn++
                if (t.sheets.isNotEmpty()) drawPlanSheet(canvas, t, z)
            }
        }
        for (v in b.vedoms) {
            vedomDraw.bounds(b, v, rect2)
            if (visible(rect2.left, rect2.top, rect2.right, rect2.bottom)) {
                vedomDraw.draw(canvas, b, v, z, vis, selected = selection.takeIf { s -> s.any { it is BoardVItem } }, marked = markedItems); drawn++
            }
        }
        // Издалека ручка рисуется одним контуром ровной толщины — на глаз не отличить, а в разы быстрее.
        val fast = z < 0.6f
        val minW = BoardTiles.MIN_PX / z // издалека штрих не тоньше ~0,8 пикселя — написанное видно, а не пропадает
        // Штрихи — готовыми плитками (FW-009): кадр не зависит от числа штрихов. Выделенные — поверх, напрямую.
        selDrawings.clear()
        liveCount = 0
        for (o in selection) if (o is Drawing) selDrawings += o
        if (useTiles) {
            val seen = tiles.sync(b.strokes, selDrawings, vis)
            drawn += seen
            if ((capturing || plain) && seen < 600) {
                // Снимок (кадр под пером, пипетка) при немногих штрихах — напрямую быстрее, чем из плиток.
                drawStrokesIn(canvas, vis, fast, minW)
            } else {
                val complete = capturing || plain || (!canvas.isHardwareAccelerated && !asyncTiles)
                tiles.draw(canvas, vis, z, complete) { r -> drawStrokesIn(canvas, r, fast, minW) }
            }
        } else {
            drawStrokesIn(canvas, vis, fast, minW)
            drawn += b.strokes.size
        }
        if (selDrawings.isNotEmpty()) for (d in b.strokes) {
            if (d !in selDrawings) continue
            val r = d.bounds()
            if (!visible(r.left, r.top, r.right, r.bottom)) continue
            painter.drawing(canvas, d, 255, fast, minW)
        }
        for (t in b.texts) {
            if (t.size * z < 2f) continue // буквы меньше 2 пикселей не видны
            if (!visible(t.x, t.y - t.size, t.x + textWidth(t), textBottom(t))) continue
            textPaint.color = t.color
            textPaint.textSize = t.size
            if (t.nLines == 1) {
                canvas.drawText(t.text, t.x, t.y, textPaint)
            } else {
                var ly = t.y
                for (line in t.text.split('\n')) { canvas.drawText(line, t.x, ly, textPaint); ly += t.size * BoardText.LINE }
            }
            drawn++
        }
        for (c in b.callouts) if (calloutVisible(c)) { drawCallout(canvas, c, z); drawn++ }
        for (s in b.stickers) if (visible(s.x, s.y, s.x + stickerW(s), s.y + s.h)) { drawSticker(canvas, s); drawn++ }
        for (r in b.rows) if (visible(r.x, r.y, r.x + rowW(r), r.y + r.h)) { drawRow(canvas, r, z); drawn++ }
        activeCell?.let { (r, col) ->
            val t = IdTables.tableOf(b, r) ?: return@let
            val c = tableDraw.cellRect(b, t, r, col, rect2) ?: return@let
            fillPaint.color = Color.argb(40, 212, 71, 12)
            canvas.drawRect(c, fillPaint)
            linePaint.color = Ui.ACCENT
            linePaint.strokeWidth = Ui.dpf(context, 2.5f) / z
            canvas.drawRect(c, linePaint)
        }
        highlight?.let { sid ->
            // Подсветка статуса: всё притушить, метки и выноски этого статуса — поверх.
            fillPaint.color = Ui.PAPER
            fillPaint.alpha = 190
            canvas.drawRect(vis, fillPaint)
            fillPaint.alpha = 255
            for (c in b.callouts) if (c.sid == sid && calloutVisible(c)) drawCallout(canvas, c, z)
            for (s in b.stickers) if (s.sid == sid && visible(s.x, s.y, s.x + stickerW(s), s.y + s.h)) drawSticker(canvas, s)
            for (r in b.rows) if (r.sid == sid && visible(r.x, r.y, r.x + rowW(r), r.y + r.h)) drawRow(canvas, r, z)
            for (t in b.tables) {
                tableDraw.bounds(b, t, rect2)
                if (visible(rect2.left, rect2.top, rect2.right, rect2.bottom)) tableDraw.draw(canvas, b, t, z, vis, hl = sid)
            }
        }
        if (mode == Mode.CALLOUT_DRAG && hypot(calloutNow.x - calloutDown.x, calloutNow.y - calloutDown.y) * z > slop) {
            dashPaint.strokeWidth = Ui.dpf(context, 1.5f) / z
            dashPaint.pathEffect = DashPathEffect(floatArrayOf(Ui.dpf(context, 6f) / z, Ui.dpf(context, 4f) / z), 0f)
            canvas.drawLine(calloutDown.x, calloutDown.y, calloutNow.x, calloutNow.y, dashPaint)
        }
        if (!plain) drawSelection(canvas, z)
        if (!plain) cropping?.let { if (exists(it)) drawCropFrame(canvas, it, z) }
        if (!plain && mode == Mode.FRAME_DRAG) {
            dashPaint.strokeWidth = Ui.dpf(context, 1.5f) / z
            dashPaint.pathEffect = DashPathEffect(floatArrayOf(Ui.dpf(context, 6f) / z, Ui.dpf(context, 4f) / z), 0f)
            canvas.drawRect(min(frameDown.x, frameNow.x), min(frameDown.y, frameNow.y), max(frameDown.x, frameNow.x), max(frameDown.y, frameNow.y), dashPaint)
        }
        if (!plain && !capturing) drawFillDraft(canvas, z)
        val pendingStyle = NoteInkView.StrokeStyle("write", Ui.INK, Ui.dpf(context, 2.6f) / z)
        for (s in host.pendingStrokes()) painter.stroke(canvas, s, pendingStyle, 255)
        if (!capturing) live?.let { painter.stroke(canvas, it, liveStyle ?: pendingStyle, 255) }
        val now = SystemClock.uptimeMillis()
        fading.removeAll { now - it.second > NoteInkView.FADE_MS }
        for ((strokes, t0) in fading) {
            val a = (255 * (1f - (now - t0).toFloat() / NoteInkView.FADE_MS)).toInt().coerceIn(0, 255)
            for (s in strokes) painter.stroke(canvas, s, pendingStyle, a)
        }
        if (!capturing) drawInterim(canvas, z)
        if (!plain && lasso.size > 1) {
            lassoPath.reset()
            lassoPath.moveTo(lasso[0].x, lasso[0].y)
            for (i in 1 until lasso.size) lassoPath.lineTo(lasso[i].x, lasso[i].y)
            dashPaint.strokeWidth = Ui.dpf(context, 1.5f) / z
            dashPaint.pathEffect = DashPathEffect(floatArrayOf(Ui.dpf(context, 6f) / z, Ui.dpf(context, 4f) / z), 0f)
            canvas.drawPath(lassoPath, dashPaint)
        }
        canvas.restore()
        if (!plain) drawFrameLabels(canvas)
        if (!plain) drawPins(canvas)
        // Обводка плана пальцем (0.23.1): перекрестие (с привязкой) и лупа, как у заливки по точкам.
        if (!plain && !capturing && mode == Mode.PLAN && planAim && (sceneValid || renderScene())) {
            canvas.save()
            canvas.scale(z, z)
            canvas.translate(-b.camX, -b.camY)
            drawAim(canvas, z)
            canvas.restore()
            drawLoupe(canvas)
        }
        if (!plain && !capturing) hover?.let {
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
        drawnCount = drawn
        totalCount = b.images.size + b.fills.size + b.strokes.size + b.texts.size + b.callouts.size + b.stickers.size + b.rows.size + b.tables.size + b.vedoms.size
        val ms = (System.nanoTime() - t0) / 1e6f
        frameMs = if (frameMs == 0f) ms else frameMs * 0.8f + ms * 0.2f
        if (!plain && !gestureActive()) requestDetails()
    }

    /** Серое промежуточное слово над недописанной надписью. */
    private fun drawInterim(canvas: Canvas, z: Float) {
        host.interim()?.let { (bb, text) ->
            val h = bb[3] - bb[1]
            textPaint.color = Ui.MUTED
            textPaint.textSize = max(h * 0.75f, Ui.dpf(context, 14f) / z)
            canvas.drawText(text, bb[0], bb[1] - Ui.dpf(context, 8f) / z, textPaint)
        }
    }

    /** Идёт жест (двигают, масштабируют, рисуют) — тяжёлые загрузки откладываем. */
    private fun gestureActive() = mode != Mode.NONE && mode != Mode.STATUS

    private fun calloutVisible(c: BoardCallout): Boolean {
        calloutRect(c, rect2)
        var l = rect2.left; var t = rect2.top; var r = rect2.right; var bt = rect2.bottom
        var i = 0
        while (i + 1 < c.leaders.size) {
            l = min(l, c.leaders[i]); r = max(r, c.leaders[i]); t = min(t, c.leaders[i + 1]); bt = max(bt, c.leaders[i + 1])
            i += 2
        }
        return visible(l, t, r, bt)
    }

    /**
     * Картинки, приближенные сильнее копии 2048 px, — заказать чёткий кусок оригинала под экран.
     * Делается, когда жест закончился (камера стоит).
     */
    private fun requestDetails() {
        val imgs = images ?: return
        val z = b.camZ
        for (m in b.images + planImages()) {
            if (!visible(m.x, m.y, m.x + m.w, m.y + m.h)) continue
            val big = max(m.pxW, m.pxH)
            if (big <= BoardImages.LEVELS.last() || m.w <= 0f || m.pxW <= 0) continue
            // Обрезанная картинка: считаем от картинки целиком, кусок — только из видимой части.
            val fx = m.fullX(); val fy = m.fullY(); val fw = m.fullW(); val fh = m.fullH()
            val screenW = fw * z
            if (screenW <= BoardImages.LEVELS.last() * m.pxW.toFloat() / big * 1.05f) continue
            // Видимая часть картинки в пикселях оригинала.
            val kx = m.pxW / fw
            val ky = m.pxH / fh
            val l = ((max(vis.left, m.x) - fx) * kx).toInt()
            val t = ((max(vis.top, m.y) - fy) * ky).toInt()
            val r = ((min(vis.right, m.x + m.w) - fx) * kx).toInt()
            val bt = ((min(vis.bottom, m.y + m.h) - fy) * ky).toInt()
            if (r <= l || bt <= t) continue
            // Во сколько раз можно уменьшить кусок, чтобы он был не мельче экрана.
            val pxPerScreen = kx / z
            var sample = 1
            while (sample * 2 <= pxPerScreen) sample *= 2
            imgs.requestDetail(m, android.graphics.Rect(l, t, r, bt), sample)
        }
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

    private val srcRect = android.graphics.Rect()

    /** Картинка листа плана таблицы на её нынешнем месте (null — листов нет). */
    private fun planImage(t: BoardTable): BoardImage? {
        val sh = TablePlan.current(t) ?: return null
        val r = tableDraw.sheetRect(b, t, planRect) ?: return null
        val m = planImgs[sh.id]?.takeIf { it.file == sh.file } ?: BoardImage(sh.file, 0f, 0f, 1f, 1f, sh.pxW, sh.pxH).also { it.id = sh.id; planImgs[sh.id] = it }
        m.cl = sh.cl; m.ct = sh.ct; m.cr = sh.cr; m.cb = sh.cb
        m.x = r.left; m.y = r.top; m.w = r.width(); m.h = r.height()
        return m
    }

    private fun planImages(): List<BoardImage> = b.tables.filter { it.sheets.isNotEmpty() }.mapNotNull { t ->
        planImage(t)?.takeIf { visible(it.x, it.y, it.x + it.w, it.y + it.h) }
    }

    /**
     * Лист плана под таблицей (0.23): картинка (уровни и чёткий кусок — как у картинок доски), области захваток цветом
     * (по ИГС или свой), подсвеченные — рамкой акцента; при обводке — черновик.
     */
    private fun drawPlanSheet(canvas: Canvas, t: BoardTable, z: Float) {
        val m = planImage(t) ?: return
        val sh = TablePlan.current(t) ?: return
        val r = RectF(m.x, m.y, m.x + m.w, m.y + m.h)
        if (!visible(r.left, r.top, r.right, r.bottom)) return
        drawImage(canvas, m, z, frameAround = false) // лист — внутри таблицы, без своей рамки
        canvas.save()
        canvas.clipRect(r)
        val pe = planEdit?.takeIf { it.t === t && it.sheet.id == sh.id }
        for (zn in b.zones) {
            if (zn.t != t.id || zn.s != sh.id) continue
            zonePath(zn.pts, sh, r)
            val c = TablePlan.zoneColor(b, t, zn)
            val dim = pe != null && zn.zkey() != IdTables.zkey(pe.uch, pe.zah)
            fillPaint.color = c; fillPaint.alpha = if (dim) 45 else 100
            canvas.drawPath(zonePath, fillPaint)
            fillPaint.alpha = 255
            linePaint.color = c; linePaint.alpha = if (dim) 110 else 230
            linePaint.strokeWidth = Ui.dpf(context, 1.5f) / z
            canvas.drawPath(zonePath, linePaint)
            linePaint.alpha = 255
        }
        markedZones?.let { mz ->
            for (zn in mz) {
                if (zn.t != t.id || zn.s != sh.id || b.zones.none { it === zn }) continue
                zonePath(zn.pts, sh, r)
                linePaint.color = Ui.ACCENT
                linePaint.strokeWidth = Ui.dpf(context, 4f) / z
                canvas.drawPath(zonePath, linePaint)
            }
        }
        if (pe != null) drawPlanDraft(canvas, pe, sh, r, z)
        canvas.restore()
    }

    private fun zonePath(pts: FloatArray, sh: PlanSheet, r: RectF) {
        zonePath.rewind()
        var i = 0
        while (i + 1 < pts.size) {
            tableDraw.fromSheet(sh, r, pts[i], pts[i + 1], planPt)
            if (i == 0) zonePath.moveTo(planPt[0], planPt[1]) else zonePath.lineTo(planPt[0], planPt[1])
            i += 2
        }
        zonePath.close()
    }

    /** Черновик обводки: готовые куски, протяжка прямоугольника/круга, точки. */
    private fun drawPlanDraft(canvas: Canvas, pe: PlanEdit, sh: PlanSheet, r: RectF, z: Float) {
        val acc = pe.color ?: Ui.ACCENT
        for (pc in pe.pieces) {
            zonePath(pc, sh, r)
            fillPaint.color = acc; fillPaint.alpha = 70
            canvas.drawPath(zonePath, fillPaint)
            fillPaint.alpha = 255
            linePaint.color = acc; linePaint.strokeWidth = Ui.dpf(context, 2.5f) / z
            canvas.drawPath(zonePath, linePaint)
        }
        if (pe.tool == PlanEdit.BRUSH && pe.path.size >= 4) {
            // След кисти — полупрозрачной широкой линией.
            val p = Path()
            p.moveTo(pe.path[0], pe.path[1])
            var i = 2
            while (i + 1 < pe.path.size) { p.lineTo(pe.path[i], pe.path[i + 1]); i += 2 }
            val bp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
                color = acc; alpha = 80; strokeWidth = Ui.dpf(context, PlanEdit.BRUSH_DP) * 2f / z
            }
            canvas.drawPath(p, bp)
        }
        val f = pe.from; val to = pe.to
        if (f != null && to != null && (pe.tool == PlanEdit.RECT || pe.tool == PlanEdit.CIRCLE)) {
            dashPaint.strokeWidth = Ui.dpf(context, 2f) / z
            dashPaint.pathEffect = DashPathEffect(floatArrayOf(Ui.dpf(context, 6f) / z, Ui.dpf(context, 4f) / z), 0f)
            val l = min(f.x, to.x); val tp = min(f.y, to.y); val rr = max(f.x, to.x); val bt = max(f.y, to.y)
            if (pe.tool == PlanEdit.RECT) canvas.drawRect(l, tp, rr, bt, dashPaint) else canvas.drawOval(l, tp, rr, bt, dashPaint)
        }
        if (pe.tool == PlanEdit.POINTS && pe.points.size >= 2 && to != null) {
            // Резинка от последней точки до перекрестия.
            tableDraw.fromSheet(sh, r, pe.points[pe.points.size - 2], pe.points[pe.points.size - 1], planPt)
            dashPaint.strokeWidth = Ui.dpf(context, 1.5f) / z
            dashPaint.pathEffect = DashPathEffect(floatArrayOf(Ui.dpf(context, 6f) / z, Ui.dpf(context, 4f) / z), 0f)
            canvas.drawLine(planPt[0], planPt[1], to.x, to.y, dashPaint)
        }
        if (pe.points.size >= 2) {
            val n = pe.points.size / 2
            linePaint.color = acc; linePaint.strokeWidth = Ui.dpf(context, 2.5f) / z
            for (i in 0 until n) {
                tableDraw.fromSheet(sh, r, pe.points[2 * i], pe.points[2 * i + 1], planPt)
                val x = planPt[0]; val y = planPt[1]
                if (i > 0) {
                    tableDraw.fromSheet(sh, r, pe.points[2 * i - 2], pe.points[2 * i - 1], planPt)
                    canvas.drawLine(planPt[0], planPt[1], x, y, linePaint)
                }
            }
            for (i in 0 until n) {
                tableDraw.fromSheet(sh, r, pe.points[2 * i], pe.points[2 * i + 1], planPt)
                val rad = Ui.dpf(context, if (i == 0) 9f else 6f) / z
                fillPaint.color = Color.WHITE
                canvas.drawCircle(planPt[0], planPt[1], rad, fillPaint)
                canvas.drawCircle(planPt[0], planPt[1], rad, linePaint)
            }
        }
    }

    private fun drawImage(canvas: Canvas, m: BoardImage, z: Float, frameAround: Boolean = true) {
        val fullScreenW = m.fullW() * z
        val bmp = if (m.w * z < 3f) null else images?.get(m, fullScreenW)
        // Обрезают — картинка видна целиком, скрытое бледно.
        if (m === cropping && bmp != null) {
            rect2.set(m.fullX(), m.fullY(), m.fullX() + m.fullW(), m.fullY() + m.fullH())
            bitmapPaint.alpha = 80
            canvas.drawBitmap(bmp, null, rect2, bitmapPaint)
            bitmapPaint.alpha = 255
        }
        if (frameAround) {
            val frame = Ui.dpf(context, 5f) / z
            rect.set(m.x - frame, m.y - frame, m.x + m.w + frame, m.y + m.h + frame)
            fillPaint.color = Color.WHITE
            canvas.drawRect(rect, fillPaint)
            linePaint.color = Ui.RULE
            linePaint.strokeWidth = 0f
            canvas.drawRect(rect, linePaint)
        }
        rect.set(m.x, m.y, m.x + m.w, m.y + m.h)
        if (bmp != null) {
            if (m.cropped) {
                srcRect.set((m.cl * bmp.width).toInt(), (m.ct * bmp.height).toInt(),
                    kotlin.math.ceil(m.cr * bmp.width).toInt(), kotlin.math.ceil(m.cb * bmp.height).toInt())
                canvas.drawBitmap(bmp, srcRect, rect, bitmapPaint)
            } else {
                canvas.drawBitmap(bmp, null, rect, bitmapPaint)
            }
        } else {
            fillPaint.color = Ui.DESK
            canvas.drawRect(rect, fillPaint)
        }
        // Чёткий кусок оригинала поверх (если картинку сильно приблизили); у обрезанной — только в видимой части.
        val d = images?.detail(m)
        if (d != null && m.pxW > 0 && m.pxH > 0) {
            val kx = m.fullW() / m.pxW
            val ky = m.fullH() / m.pxH
            val fx = m.fullX(); val fy = m.fullY()
            rect2.set(fx + d.region.left * kx, fy + d.region.top * ky, fx + d.region.right * kx, fy + d.region.bottom * ky)
            if (visible(rect2.left, rect2.top, rect2.right, rect2.bottom) && RectF.intersects(rect2, rect)) {
                if (m.cropped) {
                    canvas.save()
                    canvas.clipRect(rect)
                    canvas.drawBitmap(d.bitmap, null, rect2, bitmapPaint)
                    canvas.restore()
                } else {
                    canvas.drawBitmap(d.bitmap, null, rect2, bitmapPaint)
                }
            }
        }
    }

    /** Рамка обрезки: края и восемь ручек (углы — кружки, середины сторон — планки). */
    private fun drawCropFrame(canvas: Canvas, m: BoardImage, z: Float) {
        rect.set(m.x, m.y, m.x + m.w, m.y + m.h)
        linePaint.color = Ui.ACCENT
        linePaint.strokeWidth = Ui.dpf(context, 2f) / z
        canvas.drawRect(rect, linePaint)
        val r = Ui.dpf(context, 9f) / z
        for (cx in floatArrayOf(rect.left, rect.right)) for (cy in floatArrayOf(rect.top, rect.bottom)) {
            fillPaint.color = Color.WHITE
            canvas.drawCircle(cx, cy, r, fillPaint)
            canvas.drawCircle(cx, cy, r, linePaint)
        }
        val bar = Ui.dpf(context, 22f) / z
        val th = Ui.dpf(context, 6f) / z
        fillPaint.color = Ui.ACCENT
        val mx = rect.centerX(); val my = rect.centerY()
        canvas.drawRoundRect(mx - bar / 2, rect.top - th / 2, mx + bar / 2, rect.top + th / 2, th, th, fillPaint)
        canvas.drawRoundRect(mx - bar / 2, rect.bottom - th / 2, mx + bar / 2, rect.bottom + th / 2, th, th, fillPaint)
        canvas.drawRoundRect(rect.left - th / 2, my - bar / 2, rect.left + th / 2, my + bar / 2, th, th, fillPaint)
        canvas.drawRoundRect(rect.right - th / 2, my - bar / 2, rect.right + th / 2, my + bar / 2, th, th, fillPaint)
    }

    /** Ярлыки разделов с названиями (на экране, всегда читаются — и издалека). */
    private fun drawFrameLabels(canvas: Canvas) {
        val r = RectF()
        val pad = Ui.dpf(context, 7f)
        for (f in b.frames) {
            if (!visible(f.x, f.y - Ui.dpf(context, 30f) / b.camZ, f.x + f.w, f.y + f.h)) continue
            frameLabelRect(f, r)
            val sel = selection.any { it === f }
            fillPaint.color = if (sel) Ui.ACCENT else Ui.PAPER
            canvas.drawRoundRect(r, Ui.dpf(context, 6f), Ui.dpf(context, 6f), fillPaint)
            linePaint.color = if (sel) Ui.ACCENT else Ui.INK2
            linePaint.strokeWidth = Ui.dpf(context, 1f)
            canvas.drawRoundRect(r, Ui.dpf(context, 6f), Ui.dpf(context, 6f), linePaint)
            frameText.color = if (sel) Color.WHITE else Ui.INK
            canvas.drawText(f.title.ifEmpty { "Раздел" }, r.left + pad, r.bottom - Ui.dpf(context, 7.5f), frameText)
        }
    }

    /** Булавки закреплённых картинок (в координатах экрана — всегда одного размера). */
    private fun drawPins(canvas: Canvas) {
        val c = PointF()
        val r = pinR
        val pins = ArrayList<PointF>()
        for (m in b.images) if (m.locked && visible(m.x, m.y, m.x + m.w, m.y + m.h) && pinCenter(m, c)) pins += PointF(c.x, c.y)
        for (t in b.tables) if (t.locked) {
            tableDraw.bounds(b, t, rect2)
            if (visible(rect2.left, rect2.top, rect2.right, rect2.bottom) && tablePinCenter(t, c)) pins += PointF(c.x, c.y)
        }
        for (pc in pins) {
            c.set(pc.x, pc.y)
            fillPaint.color = Color.WHITE
            canvas.drawCircle(c.x, c.y, r, fillPaint)
            linePaint.color = Ui.ACCENT
            linePaint.strokeWidth = Ui.dpf(context, 1.5f)
            canvas.drawCircle(c.x, c.y, r, linePaint)
            val ir = (r * 0.68f).toInt()
            pinIcon.setBounds((c.x - ir).toInt(), (c.y - ir).toInt(), (c.x + ir).toInt(), (c.y + ir).toInt())
            pinIcon.draw(canvas)
        }
    }

    /** Куда ляжет точка: резинка от последней точки, перекрестие; прилипла — зелёный квадрат. */
    private fun drawAim(canvas: Canvas, z: Float) {
        val p = host.fillDraft()
        val color = host.strokeStyle().color
        if (p != null && p.size >= 2) {
            dashPaint.strokeWidth = Ui.dpf(context, 1.5f) / z
            dashPaint.pathEffect = DashPathEffect(floatArrayOf(Ui.dpf(context, 6f) / z, Ui.dpf(context, 4f) / z), 0f)
            canvas.drawLine(p[p.size - 2], p[p.size - 1], aim.x, aim.y, dashPaint)
        }
        val c = Ui.dpf(context, 9f) / z
        linePaint.color = color
        linePaint.strokeWidth = Ui.dpf(context, 1.5f) / z
        canvas.drawLine(aim.x - c, aim.y, aim.x + c, aim.y, linePaint)
        canvas.drawLine(aim.x, aim.y - c, aim.x, aim.y + c, linePaint)
        if (aimSnapped) {
            val q = Ui.dpf(context, 7f) / z
            linePaint.color = snapColor
            linePaint.strokeWidth = Ui.dpf(context, 2f) / z
            canvas.drawRect(aim.x - q, aim.y - q, aim.x + q, aim.y + q, linePaint)
        }
    }

    /**
     * Лупа: круг в верхнем углу экрана — с другой стороны от пальца (палец под лупой — внизу),
     * в 2,5 раза крупнее, перекрестие — ровно в точке, куда она ляжет.
     */
    private fun drawLoupe(canvas: Canvas) {
        val bmp = scene ?: return
        val r = Ui.dpf(context, 72f)
        val m = Ui.dpf(context, 14f)
        val k = 2.5f
        val cx = if (aimFinger.x > width / 2f) m + r else width - m - r
        var cy = m + r
        if (hypot(aimFinger.x - cx, aimFinger.y - cy) < r + Ui.dpf(context, 60f)) cy = height - m - r
        val ax = sx(aim.x); val ay = sy(aim.y)
        canvas.save()
        loupePath.reset()
        loupePath.addCircle(cx, cy, r, Path.Direction.CW)
        canvas.clipPath(loupePath)
        canvas.drawColor(Ui.PAPER)
        canvas.translate(cx - ax * k, cy - ay * k)
        canvas.scale(k, k)
        canvas.drawBitmap(bmp, 0f, 0f, bitmapPaint)
        canvas.scale(b.camZ, b.camZ)
        canvas.translate(-b.camX, -b.camY)
        drawFillDraft(canvas, b.camZ * k)
        drawAim(canvas, b.camZ * k)
        canvas.restore()
        // Перекрестие во всю лупу (с просветом в середине) и рамка.
        val gap = Ui.dpf(context, 12f)
        linePaint.color = Ui.INK
        linePaint.alpha = 150
        linePaint.strokeWidth = Ui.dpf(context, 1f)
        canvas.drawLine(cx - r, cy, cx - gap, cy, linePaint); canvas.drawLine(cx + gap, cy, cx + r, cy, linePaint)
        canvas.drawLine(cx, cy - r, cx, cy - gap, linePaint); canvas.drawLine(cx, cy + gap, cx, cy + r, linePaint)
        linePaint.alpha = 255
        linePaint.color = if (aimSnapped) snapColor else Ui.ACCENT
        linePaint.strokeWidth = Ui.dpf(context, 2.5f)
        canvas.drawCircle(cx, cy, r, linePaint)
    }

    /** «Заливка» по точкам: поставленные точки, линии между ними и будущая заливка. */
    private fun drawFillDraft(canvas: Canvas, z: Float) {
        val p = host.fillDraft() ?: return
        if (p.size < 2) return
        val color = host.strokeStyle().color
        val path = Path()
        path.moveTo(p[0], p[1])
        var i = 2
        while (i + 1 < p.size) { path.lineTo(p[i], p[i + 1]); i += 2 }
        if (p.size >= 6) {
            val closed = Path(path).apply { close() }
            fillPaint.color = color
            fillPaint.alpha = 70
            canvas.drawPath(closed, fillPaint)
            fillPaint.alpha = 255
        }
        linePaint.color = color
        linePaint.strokeWidth = Ui.dpf(context, 2f) / z
        canvas.drawPath(path, linePaint)
        // Первая точка — крупное кольцо: коснуться её — замкнуть и залить.
        val dot = Ui.dpf(context, 4f) / z
        i = 0
        while (i + 1 < p.size) {
            fillPaint.color = if (i == 0) Color.WHITE else color
            val rr = if (i == 0) dot * 2.6f else dot
            canvas.drawCircle(p[i], p[i + 1], rr, fillPaint)
            if (i == 0) canvas.drawCircle(p[i], p[i + 1], rr, linePaint)
            i += 2
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
        if (!movable()) return
        val r = Ui.dpf(context, 8f) / z
        val f = singleFrame()
        if (f != null) {
            // Раздел: ручки на углах и серединах сторон — прямо на рамке раздела.
            rect.set(f.x, f.y, f.x + f.w, f.y + f.h)
            val mx = rect.centerX(); val my = rect.centerY()
            for ((cx, cy) in listOf(rect.left to rect.top, rect.right to rect.top, rect.left to rect.bottom, rect.right to rect.bottom,
                    mx to rect.top, mx to rect.bottom, rect.left to my, rect.right to my)) {
                fillPaint.color = Color.WHITE
                canvas.drawCircle(cx, cy, r, fillPaint)
                canvas.drawCircle(cx, cy, r, linePaint)
            }
            return
        }
        for (cx in floatArrayOf(rect.left, rect.right)) for (cy in floatArrayOf(rect.top, rect.bottom)) {
            fillPaint.color = Color.WHITE
            canvas.drawCircle(cx, cy, r, fillPaint)
            canvas.drawCircle(cx, cy, r, linePaint)
        }
    }

    /** Заливка: полупрозрачная область и тонкий край того же цвета. */
    private fun drawFill(canvas: Canvas, f: BoardFill, z: Float) {
        val p = f.pts
        if (p.size < 6) return
        val fillPath = f.path ?: Path().also { path ->
            path.moveTo(p[0], p[1])
            var i = 2
            while (i + 1 < p.size) { path.lineTo(p[i], p[i + 1]); i += 2 }
            path.close()
            f.path = path
        }
        fillPaint.color = f.color
        fillPaint.alpha = (255 * f.alpha).toInt().coerceIn(0, 255)
        canvas.drawPath(fillPath, fillPaint)
        fillPaint.alpha = 255
        linePaint.color = f.color
        linePaint.alpha = 200
        linePaint.strokeWidth = Ui.dpf(context, 1.5f) / z
        canvas.drawPath(fillPath, linePaint)
        linePaint.alpha = 255
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = android.graphics.Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER; style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND; color = Color.WHITE
    }

    /** Середина заливки для подписи: центр тяжести многоугольника (если он внутри), иначе середина рамки. */
    fun fillCenter(f: BoardFill, out: PointF): PointF {
        val p = f.pts
        val n = p.size / 2
        var a = 0.0; var cx = 0.0; var cy = 0.0
        for (i in 0 until n) {
            val j = (i + 1) % n
            val cr = p[i * 2].toDouble() * p[j * 2 + 1] - p[j * 2].toDouble() * p[i * 2 + 1]
            a += cr; cx += (p[i * 2] + p[j * 2]) * cr; cy += (p[i * 2 + 1] + p[j * 2 + 1]) * cr
        }
        if (kotlin.math.abs(a) > 1e-6) {
            val x = (cx / (3 * a)).toFloat(); val y = (cy / (3 * a)).toFloat()
            if (insidePolygon(p, x, y)) { out.set(x, y); return out }
        }
        val r = f.bounds()
        out.set(r.centerX(), r.centerY())
        return out
    }

    private val labelPt = PointF()

    /** Номер захватки на привязанной заливке: крупно по размеру заливки, цветом заливки с белой обводкой. */
    private fun drawFillLabel(canvas: Canvas, f: BoardFill, z: Float) {
        if (!f.ln || f.lz.isBlank()) return
        if (b.tables.none { it.id == f.lt }) return
        val r = f.bounds()
        val minSide = min(r.width(), r.height())
        if (minSide * z < Ui.dpf(context, 22f)) return // на экране мелко — не подписываем
        var size = (minSide * 0.32f).coerceIn(Ui.dpf(context, 14f) / z, Ui.dpf(context, 64f) / z).coerceAtMost(minSide * 0.6f)
        val text = f.label()
        // С участком подпись длиннее («LM1 · 3») — не шире заливки.
        labelPaint.textSize = size
        val tw = labelPaint.measureText(text)
        if (tw > r.width() * 0.9f) size = max(size * r.width() * 0.9f / tw, Ui.dpf(context, 10f) / z)
        val c = fillCenter(f, labelPt)
        labelPaint.textSize = size; haloPaint.textSize = size
        haloPaint.strokeWidth = size * 0.18f
        labelPaint.color = Board.statusFg(f.color)
        val y = c.y + size * 0.36f
        canvas.drawText(text, c.x, y, haloPaint)
        canvas.drawText(text, c.x, y, labelPaint)
    }

    /** Подсветка заливки захватки: толстый контур цвета акцента. */
    private fun drawFillMark(canvas: Canvas, f: BoardFill, z: Float) {
        val path = f.path ?: return
        linePaint.color = Ui.ACCENT
        linePaint.strokeWidth = Ui.dpf(context, 4f) / z
        canvas.drawPath(path, linePaint)
        fillPaint.color = Ui.ACCENT
        fillPaint.alpha = 28
        canvas.drawPath(path, fillPaint)
        fillPaint.alpha = 255
    }

    private val arrowPath = Path()

    /** Мультивыноска: указатели со стрелками, рамка, надпись и строка статуса цвета статуса. */
    private fun drawCallout(canvas: Canvas, c: BoardCallout, z: Float) {
        val st = b.status(c.sid)
        val fg = Board.statusFg(st.color)
        val box = calloutRect(c, RectF())
        val lw = 1.6f * c.scale
        linePaint.color = Ui.INK2
        linePaint.strokeWidth = lw
        fillPaint.color = Ui.INK2
        for (i in 0 until c.leaderCount) {
            val tx = c.leaders[i * 2]; val ty = c.leaders[i * 2 + 1]
            // Начало указателя — ближайшая к цели точка рамки.
            val ax = tx.coerceIn(box.left, box.right)
            val ay = ty.coerceIn(box.top, box.bottom)
            val ox = if (ax == tx && ay == ty) box.centerX() else ax
            val oy = if (ax == tx && ay == ty) box.bottom else ay
            canvas.drawLine(ox, oy, tx, ty, linePaint)
            val len = hypot(tx - ox, ty - oy)
            if (len > 1f) {
                val ux = (tx - ox) / len; val uy = (ty - oy) / len
                val al = 11f * c.scale; val aw = 4.5f * c.scale
                arrowPath.reset()
                arrowPath.moveTo(tx, ty)
                arrowPath.lineTo(tx - ux * al - uy * aw, ty - uy * al + ux * aw)
                arrowPath.lineTo(tx - ux * al + uy * aw, ty - uy * al - ux * aw)
                arrowPath.close()
                canvas.drawPath(arrowPath, fillPaint)
            }
        }
        val r = 8f * c.scale
        val row = rowH(c)
        fillPaint.color = Color.WHITE
        canvas.drawRoundRect(box, r, r, fillPaint)
        // Нижняя строка — кнопка статуса.
        fillPaint.color = Board.statusBg(st.color)
        rect.set(box.left, box.top + row, box.right, box.bottom)
        canvas.save()
        canvas.clipRect(rect)
        canvas.drawRoundRect(box, r, r, fillPaint)
        canvas.restore()
        linePaint.color = fg
        linePaint.strokeWidth = 1.5f * c.scale
        canvas.drawRoundRect(box, r, r, linePaint)
        canvas.drawLine(box.left, box.top + row, box.right, box.top + row, linePaint)
        val pad = 12f * c.scale
        textPaint.color = Ui.INK
        textPaint.textSize = titleSize(c)
        canvas.drawText(c.title, box.left + pad, box.top + row / 2f + textPaint.textSize * 0.36f, textPaint)
        textPaint.color = fg
        textPaint.textSize = statusSize(c)
        textPaint.isFakeBoldText = true
        canvas.drawText(st.name, box.left + pad, box.top + row * 1.5f + textPaint.textSize * 0.36f, textPaint)
        textPaint.isFakeBoldText = false
        // Концы указателей выделенной выноски — кружки, за которые тянут.
        if (!plain && selection.size == 1 && selection[0] === c) {
            val hr = Ui.dpf(context, 7f) / z
            linePaint.color = Ui.ACCENT
            linePaint.strokeWidth = Ui.dpf(context, 2f) / z
            for (i in 0 until c.leaderCount) {
                fillPaint.color = Color.WHITE
                canvas.drawCircle(c.leaders[i * 2], c.leaders[i * 2 + 1], hr, fillPaint)
                canvas.drawCircle(c.leaders[i * 2], c.leaders[i * 2 + 1], hr, linePaint)
            }
        }
    }

    // ---------------------------------------------------------------- строка ИД (0.18, FW-011)

    private val rowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * Части строки ИД (в единицах при размере 1, от левого края): конец названия, «Чек-лист», «DWG», «ИГС».
     * Считаются по шрифту один раз, пока не поменялись название и статус.
     */
    private fun rowLayout(r: BoardRow): FloatArray {
        val st = b.status(r.sid).name
        val key = r.title + "\u0001" + st
        if (r.wKey == key) return r.parts
        rowPaint.textSize = ROW_FONT
        rowPaint.isFakeBoldText = true
        val pad = 12f
        var x = 0f
        r.parts[0] = 0f
        x += if (r.title.isEmpty()) 0f else pad + rowPaint.measureText(r.title) + pad
        r.parts[1] = x
        rowPaint.isFakeBoldText = false
        x += pad + rowPaint.measureText("Чек-лист") + 8f + ROW_BOX + pad
        r.parts[2] = x
        x += pad + rowPaint.measureText("DWG") + 8f + ROW_BOX + pad
        r.parts[3] = x
        rowPaint.isFakeBoldText = true
        x += pad + rowPaint.measureText("ИГС") + 8f + rowPaint.measureText(st) + 28f + pad
        rowPaint.isFakeBoldText = false
        r.parts[4] = x
        r.wKey = key
        return r.parts
    }

    fun rowW(r: BoardRow): Float = rowLayout(r)[4] * r.scale

    /** Какая часть строки под точкой доски ([RowPart]). */
    fun rowPartAt(r: BoardRow, x: Float): Int {
        val p = rowLayout(r)
        val u = (x - r.x) / r.scale
        return when {
            u < p[1] -> RowPart.TITLE
            u < p[2] -> RowPart.CHECK
            u < p[3] -> RowPart.DWG
            else -> RowPart.STATUS
        }
    }

    private fun drawRow(canvas: Canvas, r: BoardRow, z: Float) {
        val p = rowLayout(r)
        val k = r.scale
        val h = r.h
        val x0 = r.x; val y0 = r.y
        val cy = y0 + h / 2f
        rect.set(x0, y0, x0 + p[4] * k, y0 + h)
        fillPaint.color = Color.WHITE
        canvas.drawRoundRect(rect, 8f * k, 8f * k, fillPaint)
        linePaint.color = Ui.RULE
        linePaint.strokeWidth = 1.5f * k
        canvas.drawRoundRect(rect, 8f * k, 8f * k, linePaint)
        if (ROW_FONT * k * z < 3f) return // издалека — только рамка
        rowPaint.textSize = ROW_FONT * k
        val base = cy + rowPaint.textSize * 0.36f
        // Название.
        if (p[1] > 0f) {
            rowPaint.color = Ui.INK
            rowPaint.isFakeBoldText = true
            canvas.drawText(r.title, x0 + 12f * k, base, rowPaint)
            rowPaint.isFakeBoldText = false
            linePaint.color = Ui.RULE
            canvas.drawLine(x0 + p[1] * k, y0 + 6f * k, x0 + p[1] * k, y0 + h - 6f * k, linePaint)
        }
        // Галочки «Чек-лист» и «DWG»: зелёная ✓ — есть, красный ✗ — нет.
        fun check(from: Float, label: String, on: Boolean) {
            rowPaint.color = Ui.INK2
            canvas.drawText(label, x0 + (from + 12f) * k, base, rowPaint)
            val bx = x0 + (from + 12f + rowPaint.measureText(label) / k + 8f) * k
            val s = ROW_BOX * k
            rect.set(bx, cy - s / 2f, bx + s, cy + s / 2f)
            val c = if (on) Ui.OK else Ui.BAD
            fillPaint.color = Board.statusBg(c)
            canvas.drawRoundRect(rect, 5f * k, 5f * k, fillPaint)
            linePaint.color = c
            linePaint.strokeWidth = 1.5f * k
            canvas.drawRoundRect(rect, 5f * k, 5f * k, linePaint)
            linePaint.strokeWidth = 2.6f * k
            linePaint.strokeCap = Paint.Cap.ROUND
            if (on) {
                canvas.drawLine(bx + s * 0.22f, cy, bx + s * 0.42f, cy + s * 0.22f, linePaint)
                canvas.drawLine(bx + s * 0.42f, cy + s * 0.22f, bx + s * 0.8f, cy - s * 0.24f, linePaint)
            } else {
                canvas.drawLine(bx + s * 0.28f, cy - s * 0.22f, bx + s * 0.72f, cy + s * 0.22f, linePaint)
                canvas.drawLine(bx + s * 0.72f, cy - s * 0.22f, bx + s * 0.28f, cy + s * 0.22f, linePaint)
            }
            linePaint.strokeCap = Paint.Cap.BUTT
        }
        check(p[1], "Чек-лист", r.check)
        check(p[2], "DWG", r.dwg)
        // ИГС — статус из списка ИД, цветной «таблеткой».
        val st = b.status(r.sid)
        rowPaint.color = Ui.INK2
        rowPaint.isFakeBoldText = true
        val lx = x0 + (p[3] + 12f) * k
        canvas.drawText("ИГС", lx, base, rowPaint)
        val px = lx + rowPaint.measureText("ИГС") + 8f * k
        val pw = rowPaint.measureText(st.name) + 28f * k
        rect.set(px, y0 + 6f * k, px + pw, y0 + h - 6f * k)
        val fg = Board.statusFg(st.color)
        fillPaint.color = Board.statusBg(st.color)
        canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, fillPaint)
        linePaint.color = fg
        linePaint.strokeWidth = 1.5f * k
        canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, linePaint)
        rowPaint.color = fg
        canvas.drawText(st.name, px + 14f * k, base, rowPaint)
        rowPaint.isFakeBoldText = false
    }

    private fun drawSticker(canvas: Canvas, s: BoardSticker) {
        val st = b.status(s.sid)
        val label = st.name
        val fg = Board.statusFg(st.color)
        val bg = Board.statusBg(st.color)
        val w = s.width(label)
        rect.set(s.x, s.y, s.x + w, s.y + s.h)
        fillPaint.color = bg
        canvas.drawRoundRect(rect, s.h / 2f, s.h / 2f, fillPaint)
        linePaint.color = fg
        linePaint.strokeWidth = 1.5f * s.scale
        canvas.drawRoundRect(rect, s.h / 2f, s.h / 2f, linePaint)
        textPaint.color = fg
        textPaint.textSize = 16f * s.scale
        textPaint.isFakeBoldText = true
        val tw = textPaint.measureText(label)
        canvas.drawText(label, s.x + (w - tw) / 2f, s.y + s.h / 2f + textPaint.textSize * 0.36f, textPaint)
        textPaint.isFakeBoldText = false
    }
}
