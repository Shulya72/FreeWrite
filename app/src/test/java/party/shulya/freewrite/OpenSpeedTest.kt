package party.shulya.freewrite

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/**
 * Скорость открытия доски (Владимир, 16.11: «нажимаю на общую доску — приложение зависает на 2–3 секунды, раньше открывалась
 * сразу»). Доска как у него: таблицы с планами и разметкой, история, ведомость, 11 тыс. штрихов (как из Nebo), отметки общей
 * доски. Печатает BENCH: чтение файла, разбор JSON, сборка доски, открытие экрана, первый кадр.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OpenSpeedTest {

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

    private fun board(): Board {
        val b = Board.create("Общая")
        val rnd = java.util.Random(3)
        for (k in 0 until 11000) {
            val x0 = rnd.nextFloat() * 14000f; val y0 = rnd.nextFloat() * 22000f
            val n = 20 + rnd.nextInt(40)
            val pts = FloatArray(n * 3) { i -> when (i % 3) { 0 -> x0 + (i / 3) * 1.7f; 1 -> y0 + ((i / 3) % 7) * 1.3f; else -> 0.5f } }
            b.strokes += Drawing("pen", Color.BLUE, 1.2f, pts)
        }
        val plan = Bitmap.createBitmap(1200, 850, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val (f, w, h) = BoardImages.importBitmap(ctx, plan)
        val igs = b.statuses(BoardStatus.ID).map { it.id }
        for (ti in 0 until 4) {
            val t = BoardTable(100f + ti * 2600f, 100f, 1.2f, "Таблица ${ti + 1}").apply { kind = TableKinds.KJ_VERT.id }
            b.tables += t
            var o = 0.0
            for (z in 1..12) for (work in listOf("Опалубка", "Бетон")) for (k in 0 until 6) {
                b.recs += BoardRec(t.id, ++o).apply {
                    zah = z.toString(); this.work = work; con = "Стена $k"; this.igs = igs[(z + k) % igs.size]
                    vp["w"] = IdTables.volKey(10.0 + k); nsch = "ИС-$z-$k"; aosr = "АОСР 6.1.1-0$z$k"; who = "АР-Старт"
                }
            }
            val sh = PlanSheet(f, w, h)
            t.sheets += sh
            for (i in 0 until 120) {
                val cx = 0.05f + (i % 12) * 0.08f; val cy = 0.08f + (i / 12) * 0.09f
                val zn = BoardZone(t.id, sh.id, "", if (i < 80) (i % 12 + 1).toString() else "", ring(cx, cy, 0.025f, 120), Color.GRAY)
                zn.e = true
                if (i % 6 == 0) zn.holes = listOf(ring(cx, cy, 0.008f, 24))
                b.zones += zn
            }
        }
        val v = BoardVedom(100f, 6000f, 1.2f, Vedoms.CUST, "З-1").apply { made = "2026-11-16" }
        b.vedoms += v
        Vedoms.add(b, v, b.recs.take(40), "Владимир")
        // История: правки статусов.
        for ((i, r) in b.recs.withIndex()) for (k in 0 until 4) Vedoms.set(b, r, TableCol.IGS, igs[(i + k) % igs.size], "Владимир", "", "", 1_700_000_000_000L + i * 1000L + k)
        return b
    }

    @Test
    fun openSharedBoard() {
        val b0 = board()
        val store = BoardStore(ctx)
        store.save(b0) // с отметками общей доски
        val file = File(File(ctx.filesDir, "boards"), "${b0.id}.json")
        println("BENCH open file-size MB=${"%.2f".format(file.length() / 1e6)} strokes=${b0.strokes.size} zones=${b0.zones.size} recs=${b0.recs.size} events=${b0.events.size}")
        repeat(2) { store.load(b0.id) } // прогрев
        var t = System.nanoTime()
        val text = file.readText()
        val read = ms(t)
        t = System.nanoTime()
        val json = JSONObject(text)
        val parse = ms(t)
        t = System.nanoTime()
        Board.fromJson(json)
        val build = ms(t)
        t = System.nanoTime()
        repeat(3) { store.load(b0.id) }
        val load = ms(t) / 3
        // Где время при открытии экрана: снимаем стек главного потока каждые 2 мс (наши функции — по счёту).
        val me = Thread.currentThread()
        val hits = java.util.concurrent.ConcurrentHashMap<String, Int>()
        val sampling = java.util.concurrent.atomic.AtomicBoolean(true)
        val sampler = Thread {
            while (sampling.get()) {
                val st = me.stackTrace
                val seen = HashSet<String>()
                for (fr in st) if (fr.className.startsWith("party.shulya.freewrite") && !fr.className.contains("Test")) {
                    val k = fr.className.substringAfterLast('.') + "." + fr.methodName
                    if (seen.add(k)) hits.merge(k, 1, Int::plus)
                }
                Thread.sleep(2)
            }
        }
        sampler.start()
        t = System.nanoTime()
        val a = Robolectric.buildActivity(BoardActivity::class.java, Intent(ctx, BoardActivity::class.java).putExtra(BoardActivity.EXTRA_ID, b0.id)).setup().get()
        val setup = ms(t)
        sampling.set(false); sampler.join()
        for ((k, n) in hits.entries.sortedByDescending { it.value }.take(25)) println("BENCH open-hot $k samples=$n")
        val view = BoardActivity::class.java.getDeclaredField("view").apply { isAccessible = true }.get(a) as BoardView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        t = System.nanoTime()
        view.draw(Canvas(bmp))
        val first = ms(t)
        idle()
        println("BENCH open read ms=${"%.0f".format(read)} json ms=${"%.0f".format(parse)} build ms=${"%.0f".format(build)} " +
            "load ms=${"%.0f".format(load)} activity ms=${"%.0f".format(setup)} first-frame ms=${"%.0f".format(first)}")

        // После обновления приложения общая доска один раз перечитывается с сервера целиком (0.25.2): то же содержимое —
        // сверка отпечатков всех предметов. Сколько это держит экран.
        val loaded = store.load(b0.id)!!
        val rows = Remote.pending(loaded.also { it.sync.pushedRev = "" }).map { Remote.Row(it.id, it.rev, it.kind, it.data, 1L) }
        loaded.sync.pushedRev = loaded.sync.objs.values.maxOfOrNull { it.rev }.orEmpty()
        t = System.nanoTime()
        val changed = Remote.apply(loaded, rows, null, refresh = true)
        println("BENCH open-refresh rows=${rows.size} changed=$changed ms=${"%.0f".format(ms(t))}")

        // Как на планшете: большая доска — экран сразу («Открываю доску…»), доска читается в фоне и потом появляется.
        BoardActivity.asyncOpen = true
        try {
            t = System.nanoTime()
            val ctl = Robolectric.buildActivity(BoardActivity::class.java, Intent(ctx, BoardActivity::class.java).putExtra(BoardActivity.EXTRA_ID, b0.id)).setup()
            val shown = ms(t)
            val a2 = ctl.get()
            org.junit.Assert.assertFalse("пока читается — экран «Открываю доску…»", a2.isOpened)
            org.junit.Assert.assertNotNull(a2.window.decorView.findViewWithTag<android.view.View>("board_opening"))
            val deadline = System.currentTimeMillis() + 20_000
            while (!a2.isOpened && System.currentTimeMillis() < deadline) { Thread.sleep(10); idle() }
            val ready = ms(t)
            org.junit.Assert.assertTrue("доска открылась", a2.isOpened)
            org.junit.Assert.assertEquals(b0.strokes.size, a2.board().strokes.size)
            org.junit.Assert.assertNull(a2.window.decorView.findViewWithTag<android.view.View>("board_opening"))
            println("BENCH open-async screen-shown ms=${"%.0f".format(shown)} board-ready ms=${"%.0f".format(ready)}")
            ctl.pause().stop().destroy()
        } finally {
            BoardActivity.asyncOpen = false
        }
    }
}
