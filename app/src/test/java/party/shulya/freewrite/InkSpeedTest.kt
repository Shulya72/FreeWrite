package party.shulya.freewrite

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.cos
import kotlin.math.sin

/**
 * Скорость письма ручкой на доске (Владимир, 16.11: «начинаю писать на доске — жёстко фризит, линии подтормаживают; в начале
 * было классно»). Доска как у него: три таблицы с планом (120 элементов, 80 в захватках), 400 строк, ведомость, 2000 штрихов.
 * Печатает замеры BENCH по шагам штриха: начало (первый кадр), движение пера (кадр), конец (запись штриха), сохранение.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InkSpeedTest {

    private val ctx get() = RuntimeEnvironment.getApplication()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun ms(t0: Long) = (System.nanoTime() - t0) / 1e6

    @Before
    fun setUp() {
        Input.markStylus(ctx)
        Profile.save(ctx, "Владимир", "Шульмин")
    }

    private fun ring(cx: Float, cy: Float, r: Float, n: Int): FloatArray {
        val p = FloatArray(n * 2)
        for (i in 0 until n) {
            val a = i * 2 * Math.PI / n
            p[i * 2] = cx + (r * cos(a)).toFloat() * (1f + 0.15f * (i % 3))
            p[i * 2 + 1] = cy + (r * 0.6f * sin(a)).toFloat()
        }
        return p
    }

    /** Тяжёлая доска (heavy) или доска «как в начале» — только штрихи. */
    private fun board(heavy: Boolean): Board {
        val b = Board.create(if (heavy) "Тяжёлая" else "Лёгкая")
        val rnd = java.util.Random(7)
        // Штрихи: короткие надписи вокруг таблиц.
        for (k in 0 until 2000) {
            val x0 = rnd.nextFloat() * 7000f; val y0 = rnd.nextFloat() * 3000f
            val pts = FloatArray(24 * 3) { i -> when (i % 3) { 0 -> x0 + (i / 3) * 5f; 1 -> y0 + ((i / 3) % 4) * 6f; else -> 0.5f } }
            b.strokes += Drawing("pen", Color.BLUE, 3f, pts)
        }
        if (!heavy) return b
        val plan = Bitmap.createBitmap(2048, 1448, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            val c = Canvas(this); val p = android.graphics.Paint().apply { color = Color.DKGRAY; strokeWidth = 3f }
            for (i in 0 until 300) c.drawLine(rnd.nextFloat() * 2048, rnd.nextFloat() * 1448, rnd.nextFloat() * 2048, rnd.nextFloat() * 1448, p)
        }
        val (f, w, h) = BoardImages.importBitmap(ctx, plan)
        val igs = b.statuses(BoardStatus.ID).map { it.id }
        for (ti in 0 until 3) {
            val t = BoardTable(100f + ti * 2600f, 100f, 1.2f, "Таблица ${ti + 1}").apply { kind = TableKinds.KJ_VERT.id }
            b.tables += t
            var o = 0.0
            for (z in 1..10) for (work in listOf("Опалубка", "Бетон")) for (k in 0 until 7) {
                b.recs += BoardRec(t.id, ++o).apply {
                    zah = z.toString(); this.work = work; con = "Стена $k"; this.igs = igs[(z + k) % igs.size]
                    vp["w"] = IdTables.volKey(10.0 + k); nsch = "ИС-$z-$k"; aosr = "АОСР 6.1.1-0$z$k"
                }
            }
            val sh = PlanSheet(f, w, h)
            t.sheets += sh
            for (i in 0 until 120) {
                val cx = 0.05f + (i % 12) * 0.08f; val cy = 0.08f + (i / 12) * 0.09f
                val zn = BoardZone(t.id, sh.id, "", if (i < 80) (i % 10 + 1).toString() else "", ring(cx, cy, 0.025f, 48), Color.GRAY)
                zn.e = true
                if (i % 6 == 0) zn.holes = listOf(ring(cx, cy, 0.008f, 16))
                b.zones += zn
            }
        }
        val v = BoardVedom(100f, 6000f, 1.2f, Vedoms.CUST, "З-1").apply { made = "2026-11-16" }
        b.vedoms += v
        Vedoms.add(b, v, b.recs.take(30), "Владимир")
        return b
    }

    private fun ev(down: Long, t: Long, action: Int, x: Float, y: Float): MotionEvent {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 0.5f })
        return MotionEvent.obtain(down, t, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
    }

    private class Open(val a: BoardActivity, val view: BoardView, val canvas: Canvas, val layer: Canvas)

    private fun open(b0: Board, heavy: Boolean): Open {
        BoardStore(ctx).save(b0)
        val a = Robolectric.buildActivity(BoardActivity::class.java, Intent(ctx, BoardActivity::class.java).putExtra(BoardActivity.EXTRA_ID, b0.id)).setup().get()
        idle()
        val view = BoardActivity::class.java.getDeclaredField("view").apply { isAccessible = true }.get(a) as BoardView
        val b = a.board()
        // Вид как при работе: таблица с планом во весь экран.
        if (heavy) view.tableDraw.bounds(b, b.tables[0], android.graphics.RectF()).let { view.fitRect(it.left, it.top, it.right, it.bottom) }
        else view.fitRect(0f, 0f, 2600f, 1600f)
        idle()
        val canvas = Canvas(Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888))
        repeat(40) { view.draw(canvas); Thread.sleep(50); idle() } // картинки и плитки догружаются в фоне
        a.setTool(BoardActivity.Tool.PEN, quiet = true)
        return Open(a, view, canvas, Canvas(Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)))
    }

    /**
     * Как на планшете: каждый кадр рисуется то, что просило перерисовки — доска (если её «испортили») и слой живой линии.
     * Возвращает, перерисовывалась ли доска.
     */
    private fun frame(o: Open): Boolean {
        val boardDirty = o.view.isDirty
        if (boardDirty) o.view.draw(o.canvas)
        if (o.view.inkLayer.isDirty) o.view.inkLayer.draw(o.layer)
        return boardDirty
    }

    private fun measure(heavy: Boolean) {
        val o = open(board(heavy), heavy)
        val view = o.view
        val b = o.a.board()
        val tag = if (heavy) "heavy" else "light"
        var start = 0.0; var move = 0.0; var moves = 0; var end = 0.0; var boardFrames = 0
        val strokes = 30
        for (s in 0 until strokes) {
            val x0 = view.width * (0.2f + 0.07f * s); val y0 = view.height * 0.45f
            val d = SystemClock.uptimeMillis()
            var t = System.nanoTime()
            view.dispatchTouchEvent(ev(d, d, MotionEvent.ACTION_DOWN, x0, y0))
            if (frame(o)) boardFrames++ // первый кадр штриха
            start += ms(t)
            for (m in 1..30) {
                val e = ev(d, d + m * 8L, MotionEvent.ACTION_MOVE, x0 + m * 6f, y0 + (m % 5) * 4f)
                t = System.nanoTime()
                view.dispatchTouchEvent(e)
                if (frame(o)) boardFrames++
                move += ms(t); moves++
                e.recycle()
            }
            t = System.nanoTime()
            view.dispatchTouchEvent(ev(d, d + 260, MotionEvent.ACTION_UP, x0 + 180f, y0))
            if (frame(o)) boardFrames++
            end += ms(t)
            idle()
        }
        if (heavy) {
            fun tm(name: String, f: () -> Unit) { val q = System.nanoTime(); repeat(10) { f() }; println("BENCH end-part $name ms=${"%.2f".format(ms(q) / 10)}") }
            tm("pushUndo") { o.a.pushUndo() }
            tm("changed") { o.a.changed() }
            tm("arrange") { TableDock.arrange(b, view.tableDraw) }
            tm("toDrawing") { NoteInkView.toDrawing(InkStroke(MotionEvent.TOOL_TYPE_STYLUS).also { st -> for (k in 0 until 40) st.points.add(InkPoint(k * 3f, k * 2f, k.toLong(), 0.5f)) }, NoteInkView.StrokeStyle("pen", Color.BLUE, 3f)) }
        }
        // Перо замерло — доска один раз перерисовывается со всеми свежими штрихами.
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(BoardView.HOLD_MS + 50))
        var t = System.nanoTime()
        val redrawn = frame(o)
        val pause = ms(t)
        t0 = System.nanoTime()
        val parts = b.toJsonParts(stampDevice = "bench")
        val save = ms(t0)
        t0 = System.nanoTime()
        repeat(5) { b.snapshot() }
        val snap = ms(t0) / 5
        println("BENCH ink-$tag start-frame ms=${"%.2f".format(start / strokes)} move-frame ms=${"%.2f".format(move / moves)} " +
            "stroke-end ms=${"%.1f".format(end / strokes)} board-redraws-while-writing=$boardFrames pause-redraw=$redrawn ms=${"%.1f".format(pause)} " +
            "save-main ms=${"%.1f".format(save)} snapshot ms=${"%.1f".format(snap)} parts=${parts.size}")
        assertEquals("пока пишут, доска не перерисовывается", 0, boardFrames)
        assertTrue("в паузе доска перерисовалась", redrawn)
    }

    /** Свежие штрихи видны сразу (слой), в паузе — уже доской; палец, «Отменить», другой инструмент — доска сразу. */
    @Test
    fun freshStrokesShownByLayerThenByBoard() {
        val o = open(board(heavy = false), heavy = false)
        val view = o.view; val a = o.a
        val layerBmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        fun layerInk(): Int {
            layerBmp.eraseColor(Color.TRANSPARENT)
            view.inkLayer.draw(Canvas(layerBmp))
            var n = 0
            val px = IntArray(layerBmp.width * layerBmp.height)
            layerBmp.getPixels(px, 0, layerBmp.width, 0, 0, layerBmp.width, layerBmp.height)
            for (p in px) if (Color.alpha(p) > 0) n++
            return n
        }
        fun stroke(x0: Float, y0: Float) {
            val d = SystemClock.uptimeMillis()
            view.dispatchTouchEvent(ev(d, d, MotionEvent.ACTION_DOWN, x0, y0))
            for (m in 1..20) view.dispatchTouchEvent(ev(d, d + m * 8L, MotionEvent.ACTION_MOVE, x0 + m * 8f, y0 + (m % 3) * 5f))
            view.dispatchTouchEvent(ev(d, d + 200, MotionEvent.ACTION_UP, x0 + 160f, y0))
        }
        val n0 = a.board().strokes.size
        view.draw(o.canvas) // доска нарисована, «грязи» нет
        stroke(view.width * 0.3f, view.height * 0.5f)
        stroke(view.width * 0.3f, view.height * 0.6f)
        assertEquals(n0 + 2, a.board().strokes.size) // штрихи сразу в доске (сохранятся, уйдут коллегам)
        assertFalse("доска ждёт паузы", view.isDirty)
        assertTrue("свежие штрихи рисует слой", layerInk() > 200)
        // Пауза — доска перерисовалась и сама рисует штрихи; слой пуст.
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(BoardView.HOLD_MS + 50))
        assertTrue(view.isDirty)
        view.draw(o.canvas)
        assertEquals(0, layerInk())
        // Ещё штрих, и сразу «Отменить» — доска сразу без него, слой пуст.
        stroke(view.width * 0.5f, view.height * 0.5f)
        assertTrue(layerInk() > 100)
        a.undo(); idle()
        assertEquals(n0 + 2, a.board().strokes.size)
        assertTrue("после «Отменить» доска рисуется сразу", view.isDirty)
        view.draw(o.canvas)
        assertEquals(0, layerInk())
        // Штрих, потом палец (двигают доску) — доска сразу.
        stroke(view.width * 0.5f, view.height * 0.7f)
        val d = SystemClock.uptimeMillis()
        val fp = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
        val fc = arrayOf(MotionEvent.PointerCoords().apply { x = view.width * 0.8f; y = view.height * 0.3f })
        Thread.sleep(800) // палец после пера — не ладонь
        view.dispatchTouchEvent(MotionEvent.obtain(d + 900, d + 900, MotionEvent.ACTION_DOWN, 1, fp, fc, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0))
        assertTrue("палец — доска сразу", view.isDirty)
        view.dispatchTouchEvent(MotionEvent.obtain(d + 900, d + 960, MotionEvent.ACTION_UP, 1, fp, fc, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0))
        view.draw(o.canvas)
        assertEquals(0, layerInk())
        // Штрих и сразу другой инструмент — доска сразу.
        stroke(view.width * 0.5f, view.height * 0.8f)
        a.setTool(BoardActivity.Tool.ERASER, quiet = true)
        assertTrue(view.isDirty)
    }

    private var t0 = 0L

    @Test
    fun inkOnLightBoard() = measure(heavy = false)

    @Test
    fun inkOnHeavyBoard() = measure(heavy = true)
}
