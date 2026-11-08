package party.shulya.freewrite

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Скорость большой доски (шаг 2 плана). Печатает замеры (строки BENCH в журнале сборки) и проверяет
 * главное: рисуется только видимое, снимки «Отменить» не копируют неизменённые рисунки, откат и
 * сохранение ничего не теряют, у картинок есть уменьшенные копии.
 * Замеры — на компьютере сборки; планшет медленнее в несколько раз, но соотношения те же.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w800dp-h1280dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PerformanceTest {

    private val ctx get() = RuntimeEnvironment.getApplication()

    @org.junit.Before
    fun tabletWithPen() = Input.markStylus(ctx)

    private fun ms(t0: Long) = (System.nanoTime() - t0) / 1e6

    @Test
    fun bigBoard() {
        var t = System.nanoTime()
        val b = TestBoard.generate(ctx, images = 120, strokes = 4000, texts = 800, stickers = 200, callouts = 100, fills = 100)
        println("BENCH generate ms=${ms(t)}")

        // Уменьшенные копии картинок на диске.
        val dir = File(ctx.filesDir, "note_images")
        for (l in BoardImages.LEVELS) assertTrue("копия $l", BoardImages.levelFile(dir, b.images[0].file, l).exists())

        val store = BoardStore(ctx)
        t = System.nanoTime(); store.save(b); println("BENCH save ms=${ms(t)}")
        t = System.nanoTime(); store.save(b); println("BENCH save-again ms=${ms(t)}")
        println("BENCH json KB=${File(File(ctx.filesDir, "boards"), b.id + ".json").length() / 1024}")
        t = System.nanoTime(); val loaded = store.load(b.id)!!; println("BENCH load ms=${ms(t)}")
        assertEquals("сохранение и чтение без потерь", b.toJsonString(false), loaded.toJsonString(false))
        t = System.nanoTime(); val list = BoardStore(ctx).list(); println("BENCH home-list ms=${ms(t)}")
        assertEquals(b.images.size + b.strokes.size + b.texts.size + b.stickers.size + b.fills.size + b.callouts.size,
            list.first { it.id == b.id }.items)

        // Снимки «Отменить»: неизменённые рисунки — те же строки (не копии).
        loaded.snapshot()
        t = System.nanoTime()
        repeat(5) { loaded.snapshot() }
        println("BENCH undo-snapshot ms=${ms(t) / 5}")
        val s1 = loaded.snapshot()
        loaded.strokes[0].pts[0] += 100f
        loaded.strokes[0].changed()
        val s2 = loaded.snapshot()
        assertTrue("неизменённый рисунок не копируется", s1.strokes[1] === s2.strokes[1])
        assertTrue("изменённый — новая строка", s1.strokes[0] !== s2.strokes[0])
        t = System.nanoTime()
        val back = loaded.restored(s1)
        println("BENCH undo-restore ms=${ms(t)}")
        assertEquals("откат точный", b.toJsonString(false), back.toJsonString(false))
        assertTrue("неизменённые рисунки берутся как есть", back.strokes[1] === loaded.strokes[1])

        // Отрисовка: вся доска и приближенный участок.
        val controller = Robolectric.buildActivity(
            BoardActivity::class.java, Intent(ctx, BoardActivity::class.java).putExtra(BoardActivity.EXTRA_ID, b.id),
        ).setup()
        shadowOf(Looper.getMainLooper()).idle()
        val view = find(controller.get().window.decorView)!!
        view.fitAll()
        shadowOf(Looper.getMainLooper()).idle()
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        warm(view, canvas)
        t = System.nanoTime(); repeat(5) { view.draw(canvas) }; println("BENCH draw-all ms=${ms(t) / 5} drawn=${view.drawnCount}/${view.totalCount}")
        File("build/screenshots").mkdirs()
        File("build/screenshots/board-speed.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // Кадр во время письма пером (доска под пером — готовой картинкой).
        val down = android.os.SystemClock.uptimeMillis()
        fun ev(action: Int, x: Float, y: Float, dt: Long): android.view.MotionEvent {
            val props = arrayOf(android.view.MotionEvent.PointerProperties().apply { id = 0; toolType = android.view.MotionEvent.TOOL_TYPE_STYLUS })
            val coords = arrayOf(android.view.MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 0.4f })
            return android.view.MotionEvent.obtain(down, down + dt, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_STYLUS, 0)
        }
        view.dispatchTouchEvent(ev(android.view.MotionEvent.ACTION_DOWN, 300f, 900f, 0))
        t = System.nanoTime()
        view.draw(canvas) // первый кадр штриха снимает картинку доски
        println("BENCH ink-first-frame ms=${ms(t)}")
        t = System.nanoTime()
        for (i in 1..10) {
            view.dispatchTouchEvent(ev(android.view.MotionEvent.ACTION_MOVE, 300f + i * 8f, 900f + (i % 3) * 5f, i * 8L))
            view.draw(canvas)
        }
        println("BENCH ink-frame ms=${ms(t) / 10}")
        view.dispatchTouchEvent(ev(android.view.MotionEvent.ACTION_UP, 390f, 900f, 100))
        shadowOf(Looper.getMainLooper()).idle()
        val bd = controller.get().board()
        bd.toJsonString(stampDevice = "bench1") // первый раз отмечаются все предметы
        t = System.nanoTime(); bd.toJsonString(stampDevice = "bench1"); bd.metaJson(); println("BENCH save-on-screen ms=${ms(t)}")
        t = System.nanoTime(); val marks = bd.stamp("bench1"); println("BENCH stamp ms=${ms(t)} marks=$marks")
        assertEquals("без правок отмечать нечего", 0, marks)
        val board = controller.get().board()
        board.camZ = 1.5f; board.camX = 3000f; board.camY = 2000f
        view.invalidate()
        warm(view, canvas)
        t = System.nanoTime(); repeat(5) { view.draw(canvas) }; println("BENCH draw-zoomed ms=${ms(t) / 5} drawn=${view.drawnCount}/${view.totalCount}")
        File("build/screenshots/board-speed-zoomed.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("рисуется только видимое: ${view.drawnCount} из ${view.totalCount}", view.drawnCount < view.totalCount / 10)
        controller.pause()
    }

    /** Большой чертёж: оригинал JPEG копируется как есть, есть копии; при сильном приближении — чёткий кусок. */
    @Test
    fun bigDrawingKeepsOriginalAndLoadsDetail() {
        val src = File(ctx.cacheDir, "plan.jpg")
        val big = Bitmap.createBitmap(3200, 2200, Bitmap.Config.ARGB_8888)
        Canvas(big).apply {
            drawColor(android.graphics.Color.WHITE)
            val p = android.graphics.Paint().apply { color = android.graphics.Color.BLACK; textSize = 14f }
            for (i in 0 until 60) drawText("мелкий текст чертежа $i", 40f + (i % 6) * 520f, 60f + (i / 6) * 200f, p)
        }
        src.outputStream().use { big.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        big.recycle()
        val (file, w, h) = BoardImages.import(ctx, android.net.Uri.fromFile(src))!!
        assertEquals(3200, w)
        assertEquals(2200, h)
        val dir = File(ctx.filesDir, "note_images")
        assertEquals("оригинал без пережатия", src.length(), File(dir, file).length())
        for (l in BoardImages.LEVELS) assertTrue(BoardImages.levelFile(dir, file, l).exists())

        val b = Board.create("Чертёж")
        b.images += BoardImage(file, 0f, 0f, 1600f, 1100f, w, h)
        BoardStore(ctx).save(b)
        val controller = Robolectric.buildActivity(
            BoardActivity::class.java, Intent(ctx, BoardActivity::class.java).putExtra(BoardActivity.EXTRA_ID, b.id),
        ).setup()
        shadowOf(Looper.getMainLooper()).idle()
        val view = find(controller.get().window.decorView)!!
        val board = controller.get().board()
        board.camZ = 4f; board.camX = 400f; board.camY = 300f // сильно приблизили
        view.invalidate()
        val canvas = Canvas(Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888))
        warm(view, canvas)
        val d = view.images!!.detail(b.images[0].let { board.images[0] })
        assertTrue("чёткий кусок оригинала загружен", d != null)
        assertTrue("кусок не больше видимой части с запасом: ${d!!.region}", d.region.width() < w)
        println("BENCH detail region=${d.region} sample=${d.sample}")
        controller.pause()
    }

    /** Нарисовать, дождаться фоновой загрузки картинок, нарисовать ещё раз. */
    private fun warm(view: BoardView, canvas: Canvas) {
        repeat(40) {
            view.draw(canvas)
            Thread.sleep(50)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun find(v: View): BoardView? {
        if (v is BoardView) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
        return null
    }
}
