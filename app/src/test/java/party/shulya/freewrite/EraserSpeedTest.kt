package party.shulya.freewrite

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.InputDevice
import android.view.MotionEvent
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
 * Ластик без подтормаживаний (Владимир, 19.11, после 0.27.6: «стирает как нужно, но как будто подтормаживает — тык-тык-тык»).
 * С 0.27.6 ластик режет линию почти на каждой точке пути (раньше штрих стирался целиком один раз). Замер: доска как у Владимира
 * (таблицы с планами, разметка, ведомость, 2000 штрихов + плотная надпись) и длинная запись — сколько стоит одно движение.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EraserSpeedTest {

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

    private fun line(x0: Float, x1: Float, y: Float): Drawing {
        val n = ((x1 - x0) / 3f).toInt()
        val p = FloatArray(n * 3) { i -> when (i % 3) { 0 -> x0 + (i / 3) * 3f; 1 -> y + 4f * sin((i / 3) * 0.3f); else -> 0.5f } }
        return Drawing("pen", Color.BLUE, 3f, p)
    }

    private fun board(): Board {
        val b = Board.create("Ластик")
        val rnd = java.util.Random(7)
        for (k in 0 until 2000) {
            val x0 = rnd.nextFloat() * 7000f; val y0 = rnd.nextFloat() * 3000f
            val pts = FloatArray(24 * 3) { i -> when (i % 3) { 0 -> x0 + (i / 3) * 5f; 1 -> y0 + ((i / 3) % 4) * 6f; else -> 0.5f } }
            b.strokes += Drawing("pen", Color.BLUE, 3f, pts)
        }
        // Плотная надпись, которую стирают: 40 строк поперёк пути ластика.
        for (k in 0 until 40) b.strokes += line(3000f, 4200f, 3300f + k * 18f)
        val plan = Bitmap.createBitmap(1024, 724, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val (f, w, h) = BoardImages.importBitmap(ctx, plan)
        val igs = b.statuses(BoardStatus.ID).map { it.id }
        for (ti in 0 until 3) {
            val t = BoardTable(100f + ti * 2600f, 100f, 1.2f, "Таблица ${ti + 1}").apply { kind = TableKinds.KJ_VERT.id }
            b.tables += t
            var o = 0.0
            for (z in 1..10) for (work in listOf("Опалубка", "Бетон")) for (k in 0 until 7) {
                b.recs += BoardRec(t.id, ++o).apply {
                    zah = z.toString(); this.work = work; con = "Стена $k"; this.igs = igs[(z + k) % igs.size]
                    vp["w"] = IdTables.volKey(10.0 + k)
                }
            }
            val sh = PlanSheet(f, w, h)
            t.sheets += sh
            for (i in 0 until 120) {
                val cx = 0.05f + (i % 12) * 0.08f; val cy = 0.08f + (i / 12) * 0.09f
                b.zones += BoardZone(t.id, sh.id, "", if (i < 80) (i % 10 + 1).toString() else "", ring(cx, cy, 0.025f, 48), Color.GRAY).apply { e = true }
            }
        }
        b.frames += BoardFrame(0f, 0f, 8000f, 2800f, "КЖ 6.1.1")
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

    /** Где стоит время на главном потоке (наши функции — по счёту снимков стека). */
    private fun sample(tag: String, f: () -> Unit) {
        val me = Thread.currentThread()
        val hits = java.util.concurrent.ConcurrentHashMap<String, Int>()
        val on = java.util.concurrent.atomic.AtomicBoolean(true)
        val th = Thread {
            while (on.get()) {
                val seen = HashSet<String>()
                for (fr in me.stackTrace) if (fr.className.startsWith("party.shulya.freewrite") && !fr.className.contains("Test")) {
                    val k = fr.className.substringAfterLast('.') + "." + fr.methodName
                    if (seen.add(k)) hits.merge(k, 1, Int::plus)
                }
                Thread.sleep(1)
            }
        }
        th.start()
        try { f() } finally { on.set(false); th.join() }
        for ((k, n) in hits.entries.sortedByDescending { it.value }.take(14)) println("BENCH $tag-hot $k samples=$n")
    }

    @Test
    fun boardEraserMove() {
        val b0 = board()
        BoardStore(ctx).save(b0)
        val a = Robolectric.buildActivity(BoardActivity::class.java, Intent(ctx, BoardActivity::class.java).putExtra(BoardActivity.EXTRA_ID, b0.id)).setup().get()
        idle()
        val view = BoardActivity::class.java.getDeclaredField("view").apply { isAccessible = true }.get(a) as BoardView
        view.asyncTiles = true // как на планшете: плитки — в фоне
        val b = a.board()
        view.fitRect(2900f, 3200f, 4300f, 4100f); idle()
        val canvas = Canvas(Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888))
        repeat(30) { view.draw(canvas); Thread.sleep(40); idle() }
        a.setTool(BoardActivity.Tool.ERASER, quiet = true)
        fun sx(x: Float) = (x - b.camX) * b.camZ
        fun sy(y: Float) = (y - b.camY) * b.camZ
        // Обычный кадр без ластика (то же место) — для сравнения.
        var base = 0.0
        repeat(40) { val t = System.nanoTime(); view.invalidate(); view.draw(canvas); base += ms(t) }
        println("BENCH erase-board-baseline frame-ms=${"%.2f".format(base / 40)}")
        val before = b.strokes.size
        var total = 0.0; var worst = 0.0; var moves = 0
        // Ластик сверху вниз поперёк 40 строк, потом обратно — 4 прохода.
        sample("erase-board") {
            for (pass in 0 until 4) {
                val x = 3200f + pass * 230f
                val d = android.os.SystemClock.uptimeMillis()
                view.dispatchTouchEvent(ev(d, d, MotionEvent.ACTION_DOWN, sx(x), sy(3280f)))
                for (m in 1..80) {
                    val t = System.nanoTime()
                    view.dispatchTouchEvent(ev(d, d + m * 8L, MotionEvent.ACTION_MOVE, sx(x + (m % 5) * 3f), sy(3280f + m * 9.5f)))
                    if (view.isDirty) view.draw(canvas)
                    val dt = ms(t)
                    total += dt; moves++; if (dt > worst) worst = dt
                }
                view.dispatchTouchEvent(ev(d, d + 700, MotionEvent.ACTION_UP, sx(x), sy(4040f)))
                idle()
            }
        }
        println("BENCH erase-board moves=$moves avg-ms=${"%.2f".format(total / moves)} worst-ms=${"%.1f".format(worst)} strokes ${before} -> ${b.strokes.size}")
        assertTrue("строки разрезаны", b.strokes.size > before)
        a.undo(); idle()
        a.undo(); idle()
        a.undo(); idle()
        a.undo(); idle()
        assertTrue("«Отменить» — по одному шагу на проход", a.board().strokes.size == before)
    }

    @Test
    fun noteEraserMove() {
        val n = Note.create("Планёрка")
        for (k in 0 until 60) for (w in 0 until 6) {
            val p = FloatArray(70 * 3) { i -> when (i % 3) { 0 -> 60f + w * 180f + (i / 3) * 2f; 1 -> 600f + k * 40f + 12f * sin((i / 3) * 0.4f); else -> 0.5f } }
            n.drawings += Drawing("pen", Color.rgb(20, 40, 90), 5f, p)
        }
        NoteStore(ctx).save(n)
        val a = Robolectric.buildActivity(NoteActivity::class.java, Intent(ctx, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_ID, n.id)).setup().get()
        idle()
        val ink = NoteActivity::class.java.getDeclaredField("ink").apply { isAccessible = true }.get(a) as NoteInkView
        val layer = ink.layer!!
        val canvas = Canvas(Bitmap.createBitmap(layer.width, layer.height, Bitmap.Config.ARGB_8888))
        a.window.decorView.findViewWithTag<android.view.View>("tool_eraser").performClick()
        val o = a.contentOffset()
        var total = 0.0; var worst = 0.0; var moves = 0
        sample("erase-note") {
            for (pass in 0 until 3) {
                val x = 200f + pass * 300f
                val d = android.os.SystemClock.uptimeMillis()
                ink.dispatchTouchEvent(ev(d, d, MotionEvent.ACTION_DOWN, x + o.x, 580f + o.y))
                for (m in 1..80) {
                    val t = System.nanoTime()
                    ink.dispatchTouchEvent(ev(d, d + m * 8L, MotionEvent.ACTION_MOVE, x + o.x + (m % 5) * 3f, 580f + o.y + m * 12f))
                    if (layer.isDirty) layer.draw(canvas)
                    if (ink.isDirty) ink.draw(canvas)
                    val dt = ms(t)
                    total += dt; moves++; if (dt > worst) worst = dt
                }
                ink.dispatchTouchEvent(ev(d, d + 700, MotionEvent.ACTION_UP, x + o.x, 1600f + o.y))
                idle()
            }
        }
        println("BENCH erase-note moves=$moves avg-ms=${"%.2f".format(total / moves)} worst-ms=${"%.1f".format(worst)} drawings ${360} -> ${a.drawings().size}")
        assertTrue(a.drawings().size > 360)
    }

    @Test
    fun erasedTilesLookLikeFreshDrawing() {
        val b0 = Board.create("Вырезы")
        for (k in 0 until 30) b0.strokes += line(100f, 1300f, 200f + k * 22f)
        BoardStore(ctx).save(b0)
        val a = Robolectric.buildActivity(BoardActivity::class.java, Intent(ctx, BoardActivity::class.java).putExtra(BoardActivity.EXTRA_ID, b0.id)).setup().get()
        idle()
        val view = BoardActivity::class.java.getDeclaredField("view").apply { isAccessible = true }.get(a) as BoardView
        view.asyncTiles = true
        val b = a.board()
        view.fitRect(0f, 100f, 1400f, 950f); idle()
        val bmpA = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        repeat(30) { view.draw(Canvas(bmpA)); Thread.sleep(40); idle() }
        a.setTool(BoardActivity.Tool.ERASER, quiet = true)
        fun sx(x: Float) = (x - b.camX) * b.camZ
        fun sy(y: Float) = (y - b.camY) * b.camZ
        val d = android.os.SystemClock.uptimeMillis()
        view.dispatchTouchEvent(ev(d, d, MotionEvent.ACTION_DOWN, sx(500f), sy(180f)))
        for (m in 1..70) {
            view.dispatchTouchEvent(ev(d, d + m * 8L, MotionEvent.ACTION_MOVE, sx(500f + m * 6f), sy(180f + m * 10f)))
            if (view.isDirty) view.draw(Canvas(bmpA))
        }
        // Ластик ещё на экране: кадр из плиток с вырезами — как честная прорисовка нынешних штрихов.
        view.invalidate(); view.draw(Canvas(bmpA))
        view.useTiles = false
        val bmpB = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.invalidate(); view.draw(Canvas(bmpB))
        view.useTiles = true
        var diff = 0; var ink = 0
        for (y in 0 until bmpA.height step 2) for (x in 0 until bmpA.width step 2) {
            val p = bmpA.getPixel(x, y); val q = bmpB.getPixel(x, y)
            if (Color.blue(q) > Color.red(q) + 60) ink++
            if (Math.abs(Color.red(p) - Color.red(q)) > 60 || Math.abs(Color.blue(p) - Color.blue(q)) > 60) diff++
        }
        println("BENCH erase-holes diff=$diff ink=$ink")
        assertTrue("стёрто", b.strokes.size > 30)
        assertTrue("вырезы как честная прорисовка: $diff из $ink", diff < ink / 50)
        view.dispatchTouchEvent(ev(d, d + 800, MotionEvent.ACTION_UP, sx(920f), sy(880f)))
        idle()
    }
}
