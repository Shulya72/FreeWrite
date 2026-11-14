package party.shulya.freewrite

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import org.junit.Assert.assertEquals
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
 * Скорость доски с размеченными планами (0.26.2, Владимир: «приложение начало подтормаживать»). Доска как у него:
 * три таблицы с планом, на каждом 120 элементов (стены, пилоны, плита с отверстиями), 80 отмечены в 10 захваток,
 * 400 строк. Печатает замеры BENCH и проверяет, что цвета областей и сводка те же.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlanSpeedTest {

    private val ctx get() = RuntimeEnvironment.getApplication()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun ms(t0: Long) = (System.nanoTime() - t0) / 1e6

    @Before
    fun setUp() {
        Input.markStylus(ctx)
        Profile.save(ctx, "Владимир", "Шульмин")
    }

    /** Многоугольник-«стена» из n точек около (cx, cy), доли листа. */
    private fun ring(cx: Float, cy: Float, r: Float, n: Int): FloatArray {
        val p = FloatArray(n * 2)
        for (i in 0 until n) {
            val a = i * 2 * Math.PI / n
            p[i * 2] = cx + (r * cos(a)).toFloat() * (1f + 0.15f * (i % 3))
            p[i * 2 + 1] = cy + (r * 0.6f * sin(a)).toFloat()
        }
        return p
    }

    private fun board(): Board {
        val b = Board.create("Скорость плана")
        val white = Bitmap.createBitmap(1600, 1100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val (f, w, h) = BoardImages.importBitmap(ctx, white)
        val igs = b.statuses(BoardStatus.ID).map { it.id }
        for (ti in 0 until 3) {
            val t = BoardTable(100f + ti * 2600f, 100f, 1.2f, "Таблица ${ti + 1}").apply { kind = TableKinds.KJ_VERT.id }
            b.tables += t
            var o = 0.0
            for (z in 1..10) for (work in listOf("Опалубка", "Бетон")) for (k in 0 until 7) {
                b.recs += BoardRec(t.id, ++o).apply { zah = z.toString(); this.work = work; con = "Стена $k"; this.igs = igs[(z + k) % igs.size] }
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
        return b
    }

    @Test
    fun planBoardFrameSpeed() {
        val b = board()
        BoardStore(ctx).save(b)
        val a = Robolectric.buildActivity(BoardActivity::class.java, Intent(ctx, BoardActivity::class.java).putExtra(BoardActivity.EXTRA_ID, b.id)).setup().get()
        idle()
        val view = BoardActivity::class.java.getDeclaredField("view").apply { isAccessible = true }.get(a) as BoardView
        val bd = a.board()
        view.fitAll(); idle()
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        repeat(30) { view.draw(canvas); Thread.sleep(30); idle() }

        // Раскладка таблицы, когда ничего не менялось (на кадре она нужна несколько раз на каждую таблицу).
        val t0 = bd.tables[0]
        var t = System.nanoTime()
        repeat(200) { view.tableDraw.layout(bd, t0) }
        val layoutMs = ms(t) / 200
        println("BENCH plan-layout-cached ms=${"%.3f".format(layoutMs)}")
        t = System.nanoTime()
        repeat(10) { view.draw(canvas) }
        val frameMs = ms(t) / 10
        println("BENCH plan-frame-all ms=${"%.2f".format(frameMs)}")
        // Ближе — один план во весь экран.
        val r = view.tableDraw.sheetRect(bd, t0, android.graphics.RectF())!!
        view.fitRect(r.left, r.top, r.right, r.bottom); idle()
        repeat(10) { view.draw(canvas); Thread.sleep(20); idle() }
        t = System.nanoTime()
        repeat(10) { view.draw(canvas) }
        println("BENCH plan-frame-sheet ms=${"%.2f".format(ms(t) / 10)}")
        // Для сравнения: тот же кадр без областей и без листа.
        val zones = ArrayList(bd.zones)
        bd.zones.clear()
        repeat(3) { view.draw(canvas) }
        t = System.nanoTime(); repeat(10) { view.draw(canvas) }
        println("BENCH plan-frame-sheet-nozones ms=${"%.2f".format(ms(t) / 10)}")
        bd.zones.addAll(zones)
        view.fitAll(); idle()
        repeat(3) { view.draw(canvas) }
        val layoutFrame = System.nanoTime(); repeat(10) { view.draw(canvas) }
        val all2 = ms(layoutFrame) / 10
        bd.zones.clear(); repeat(3) { view.draw(canvas) }
        t = System.nanoTime(); repeat(10) { view.draw(canvas) }
        println("BENCH plan-frame-all-again ms=${"%.2f".format(all2)} nozones ms=${"%.2f".format(ms(t) / 10)}")
        bd.zones.addAll(zones)

        // Смысл тот же: цвет области — самая отстающая схема захватки, процент — по площади.
        val list = bd.statuses(BoardStatus.ID)
        for (zn in bd.zones.filter { it.t == t0.id && !it.free }.take(10)) {
            val rows = IdTables.zahRows(bd, t0, zn.uch, zn.zah)
            val worst = rows.minByOrNull { r0 -> list.indexOfFirst { it.id == r0.igs }.let { if (it < 0) 0 else it } }!!
            assertEquals(IdTables.status(bd, BoardStatus.ID, worst.igs).color, TablePlan.zoneColor(bd, t0, zn))
        }
        assertTrue((TablePlan.percent(bd, t0) ?: 0) in 50..80)
        // Мягкая граница (компьютер сборки медленнее разработчика): кадр всей доски не дольше 250 мс.
        assertTrue("кадр $frameMs мс", frameMs < 250)
    }
}
