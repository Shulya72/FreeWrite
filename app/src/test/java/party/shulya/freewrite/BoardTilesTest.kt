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
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.random.Random

/**
 * Плитки штрихов (FW-009): доска из готовых плиток выглядит так же, как нарисованная напрямую;
 * правка (перенос, стирание, выделение) видна сразу, ещё до перерисовки плитки; при смене масштаба
 * пока своя плитка рисуется — подставляется соседний уровень. Рисуем «как на планшете» (asyncTiles).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w800dp-h1280dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BoardTilesTest {

    private val ctx get() = RuntimeEnvironment.getApplication()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Before
    fun setUp() = Input.markStylus(ctx)

    private fun field(o: Any, name: String): Any? = o.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(o)

    /** Доска с плотной «рукописью»: n коротких штрихов разных цветов на поле 4000×6000. */
    private fun board(n: Int): Board {
        val b = Board.create("Плитки")
        val rnd = Random(7)
        val colors = intArrayOf(Color.BLACK, Color.rgb(255, 26, 64), Color.rgb(26, 140, 255), Color.rgb(43, 217, 101))
        repeat(n) {
            val x = rnd.nextFloat() * 4000f; val y = rnd.nextFloat() * 6000f
            val m = 4 + rnd.nextInt(8)
            val pts = FloatArray(m * 3)
            var px = x; var py = y
            for (i in 0 until m) { pts[i * 3] = px; pts[i * 3 + 1] = py; pts[i * 3 + 2] = 0.5f; px += rnd.nextFloat() * 14f - 4f; py += rnd.nextFloat() * 10f - 5f }
            b.strokes += Drawing("pen", colors[it % colors.size], 2.5f, pts)
        }
        // Один крупный приметный штрих — его будем двигать и стирать.
        b.strokes += Drawing("pen", Color.rgb(200, 0, 160), 12f, floatArrayOf(1000f, 1000f, 0.5f, 1200f, 1000f, 0.5f, 1200f, 1100f, 0.5f))
        BoardStore(ctx).save(b)
        return b
    }

    private class Screen(val a: BoardActivity, val view: BoardView) {
        val bmp: Bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        fun draw(): Bitmap { bmp.eraseColor(Color.TRANSPARENT); view.draw(Canvas(bmp)); return bmp }
    }

    private fun open(b: Board): Screen {
        val a = Robolectric.buildActivity(BoardActivity::class.java, Intent(ctx, BoardActivity::class.java).putExtra(BoardActivity.EXTRA_ID, b.id)).setup().get()
        idle()
        val v = field(a, "view") as BoardView
        v.asyncTiles = true
        return Screen(a, v)
    }

    private fun Screen.settle() {
        var n = 0
        do { draw(); Thread.sleep(20); idle(); n++ } while ((view.tilesBusy || n < 3) && n < 500)
        draw()
    }

    /** Доля пикселей, заметно разных в двух картинках. */
    private fun diff(x: Bitmap, y: Bitmap): Double {
        var bad = 0
        for (j in 0 until x.height step 2) for (i in 0 until x.width step 2) {
            val p = x.getPixel(i, j); val q = y.getPixel(i, j)
            if (abs(Color.red(p) - Color.red(q)) + abs(Color.green(p) - Color.green(q)) + abs(Color.blue(p) - Color.blue(q)) > 90) bad++
        }
        return bad.toDouble() / ((x.width / 2) * (x.height / 2))
    }

    private fun save(b: Bitmap, name: String) {
        File("build/screenshots").mkdirs()
        FileOutputStream(File("build/screenshots/$name")).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun Screen.ink(x: Float, y: Float): Boolean {
        val bd = a.board()
        val p = draw().getPixel(((x - bd.camX) * bd.camZ).toInt(), ((y - bd.camY) * bd.camZ).toInt())
        return Color.red(p) > 150 && Color.green(p) < 60 && Color.blue(p) > 110 // приметный малиновый
    }

    @Test
    fun tilesLookLikeDirectDrawing() {
        val b = board(6000)
        val s = open(b)
        val bd = s.a.board()
        for (z in floatArrayOf(1f, 0.5f, 0.18f)) {
            bd.camZ = z; bd.camX = 600f; bd.camY = 700f
            s.view.invalidate()
            s.settle()
            assertEquals("при z=$z всё из плиток", 0, s.view.liveCount)
            val tiles = s.draw().copy(Bitmap.Config.ARGB_8888, false)
            s.view.useTiles = false
            val direct = s.draw().copy(Bitmap.Config.ARGB_8888, false)
            s.view.useTiles = true
            save(tiles, "tiles-z$z.png")
            val d = diff(tiles, direct)
            assertTrue("плитки при z=$z отличаются от прямой отрисовки: ${"%.4f".format(d)}", d < 0.01)
        }
        s.a.finish()
    }

    @Test
    fun editsShowAtOnce() {
        val b = board(2000)
        val s = open(b)
        val bd = s.a.board()
        bd.camZ = 1f; bd.camX = 800f; bd.camY = 800f
        s.view.invalidate()
        s.settle()
        assertTrue(s.ink(1100f, 1000f))
        val big = bd.strokes.last()

        // Перенос: сразу на новом месте и пропал со старого — плитка ещё не перерисована.
        for (i in big.pts.indices step 3) big.pts[i + 1] += 300f
        big.changed()
        s.view.invalidate()
        assertTrue("на старом месте пусто сразу", !s.ink(1100f, 1000f))
        assertTrue("на новом месте сразу", s.ink(1100f, 1300f))
        s.settle()
        assertEquals(0, s.view.liveCount)
        assertTrue(!s.ink(1100f, 1000f) && s.ink(1100f, 1300f))

        // Выделенный рисуется поверх и не мешает плиткам.
        s.view.select(listOf(big))
        assertTrue(s.ink(1100f, 1300f))
        s.view.clearSelection()
        s.settle()
        assertTrue(s.ink(1100f, 1300f))

        // Стёрли — сразу пусто.
        bd.strokes.remove(big)
        s.view.invalidate()
        assertTrue("стёртый не виден сразу", !s.ink(1100f, 1300f))
        s.settle()
        assertTrue(!s.ink(1100f, 1300f))
        s.a.finish()
    }

    @Test
    fun zoomUsesNeighbourLevelWhileTileIsDrawn() {
        val b = board(3000)
        val s = open(b)
        val bd = s.a.board()
        bd.camZ = 1f; bd.camX = 800f; bd.camY = 800f
        s.view.invalidate()
        s.settle()
        // Щипок: масштаб другого уровня — в первом кадре своих плиток нет, видны соседние (не пусто).
        bd.camZ = 0.8f
        s.view.invalidate()
        assertTrue("приметный штрих виден и до готовности плиток", s.ink(1100f, 1000f))
        s.settle()
        assertEquals(0, s.view.liveCount)
        assertTrue(s.ink(1100f, 1000f))
        s.a.finish()
    }

    @Test
    fun levelsAreNotSmallerThanScreen() {
        for (z in floatArrayOf(0.1f, 0.13f, 0.5f, 0.71f, 1f, 1.2f, 3f, 5f)) {
            val k = BoardTiles.level(z)
            val sc = BoardTiles.scale(k)
            assertTrue("z=$z → уровень $k (масштаб $sc)", sc >= z * 0.999f && sc < z * 1.415f)
        }
    }
}
