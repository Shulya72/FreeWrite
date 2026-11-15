package party.shulya.freewrite

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import org.json.JSONObject
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

/**
 * Склейка таблиц и раздел-блок (0.26.3, Владимир: «несущие слева, ненесущие справа — присоединяю, они склеиваются,
 * одного масштаба; примечание длинное — соседняя сдвигается; рамка раздела растёт с ними — один единый блок»).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TableDockTest {

    private val ctx get() = RuntimeEnvironment.getApplication()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Before
    fun setUp() {
        Input.markStylus(ctx)
        Profile.save(ctx, "Владимир", "Шульмин")
    }

    private fun board(): Board {
        val b = Board.create("КЖ 6.1.1")
        b.frames += BoardFrame(0f, 0f, 1800f, 700f, "КЖ 6.1.1")
        val a = BoardTable(60f, 80f, 1f, "Несущие стены и колонны").apply { kind = TableKinds.KJ_VERT.id }
        val c = BoardTable(900f, 80f, 1f, "Ненесущие стены и колонны").apply { kind = TableKinds.KJ_VERT.id }
        b.tables += a; b.tables += c
        for ((o, z) in listOf("1", "2").withIndex()) {
            b.recs += BoardRec(a.id, o + 1.0).apply { zah = z; work = "Бетон" }
            b.recs += BoardRec(c.id, o + 1.0).apply { zah = z; work = "Опалубка" }
        }
        return b
    }

    @Test
    fun gluedTableFollowsWidthScaleAndFrameGrows() {
        val b = board()
        val draw = TableDraw()
        val (a, c) = b.tables
        val fr = b.frames[0]
        c.dock = a.id
        TableDock.arrange(b, draw)
        fun spotX() = a.x + (draw.layout(b, a).w + TableDock.GAP) * a.scale
        assertEquals(spotX(), c.x, 0.1f); assertEquals(a.y, c.y, 0.01f)
        // Рамка уже под обе таблицы.
        val ownW = 1800f
        val cr = draw.bounds(b, c, RectF())
        assertEquals(maxOf(ownW, cr.right + TableDock.PAD), fr.w, 0.6f)
        // Длинное примечание у левой — правая отъехала, рамка выросла.
        val x0 = c.x
        b.recs.first { it.t == a.id }.note = "Замечание технадзора: переделать армирование узла примыкания, акт 14 от 12.11, повторная приёмка"
        TableDock.arrange(b, draw)
        assertTrue("правая отъехала: ${c.x} > $x0", c.x > x0 + 50f)
        assertEquals(spotX(), c.x, 0.1f)
        assertTrue("рамка выросла", fr.w > ownW + 50f)
        assertEquals(ownW, fr.ow, 0.01f)
        // Примечание убрали — правая подъехала, рамка — к своему размеру (если таблицы в неё помещаются).
        b.recs.first { it.t == a.id }.note = ""
        TableDock.arrange(b, draw)
        assertEquals(x0, c.x, 0.1f)
        val right = draw.bounds(b, c, RectF()).right + TableDock.PAD
        assertEquals(maxOf(ownW, right), fr.w, 0.6f)
        // Масштаб левой — у всей склейки.
        a.scale = 0.8f
        TableDock.arrange(b, draw)
        assertEquals(0.8f, c.scale, 1e-4f)
        assertEquals(spotX(), c.x, 0.1f)
    }

    @Test
    fun notGluedNeighboursInSectionDoNotOverlap() {
        val b = board()
        val draw = TableDraw()
        val (a, c) = b.tables
        c.x = a.x + draw.layout(b, a).w * 0.5f // наехала на левую
        TableDock.arrange(b, draw)
        val ar = draw.bounds(b, a, RectF())
        assertTrue("отодвинута: ${c.x} ≥ ${ar.right}", c.x >= ar.right + TableDock.GAP - 0.1f)
        assertEquals("в разделе, хоть и за своим краем рамки", b.frames[0].id, c.sec)
        assertTrue("рамка — по таблицам", b.frames[0].w >= draw.bounds(b, c, RectF()).right + TableDock.PAD - 0.6f)
    }

    @Test
    fun jsonKeepsGlueSectionAndOwnSize() {
        val b = board()
        val (a, c) = b.tables
        c.dock = a.id; c.sec = b.frames[0].id
        val t2 = IdTables.parseTable(JSONObject(IdTables.tableJson(c)))
        assertEquals(a.id, t2.dock); assertEquals(b.frames[0].id, t2.sec)
        assertTrue("у неприклеенной полей нет", !IdTables.tableJson(a).contains("\"dk\""))
        val fr = b.frames[0].apply { w = 2400f; ow = 1800f; oh = 0f }
        val f2 = Board.parseFrame(JSONObject(Board.frameJson(fr)))
        assertEquals(1800f, f2.ow, 0.01f); assertEquals(0f, f2.oh, 0.01f)
        assertEquals(0f, Board.parseFrame(JSONObject("""{"id":"f1","x":0,"y":0,"w":10,"h":10,"title":"Р"}""")).ow, 0f)
        // Старая доска (без отметок) читается как раньше.
        val saved = b.toJsonString()
        val back = Board.fromJson(JSONObject(saved))
        assertEquals(a.id, back.tables[1].dock)
    }

    @Test
    fun magnetOnScreenGluesAndFollowsDrag() {
        val b0 = board()
        BoardStore(ctx).save(b0)
        val act = Robolectric.buildActivity(BoardActivity::class.java, Intent(ctx, BoardActivity::class.java).putExtra(BoardActivity.EXTRA_ID, b0.id)).setup().get()
        idle()
        val view = BoardActivity::class.java.getDeclaredField("view").apply { isAccessible = true }.get(act) as BoardView
        val b = act.board()
        view.fitAll(); idle()
        val (a, c) = b.tables
        fun sx(x: Float) = (x - b.camX) * b.camZ
        fun sy(y: Float) = (y - b.camY) * b.camZ
        act.setTool(BoardActivity.Tool.SELECT)
        // Правую — к правому краю левой (не вплотную: на 25 единиц правее и на 12 ниже) — приклеилась.
        view.select(listOf(c))
        val spot = TableDock.dockedSpot(b, view.tableDraw, a, FloatArray(2))
        val from = sx(c.x + 40f) to sy(c.y + 10f)
        val to = sx(spot[0] + 25f + 40f) to sy(spot[1] + 12f + 10f)
        drag(view, from, to)
        assertEquals(a.id, c.dock)
        assertEquals(spot[0], c.x, 0.5f); assertEquals(a.y, c.y, 0.5f)
        shot(view, "tables-glued.png")
        // Тянем левую — правая едет вместе.
        afterPen()
        view.select(listOf(a))
        val ax0 = a.x
        drag(view, sx(a.x + 40f) to sy(a.y + 10f), sx(a.x + 40f + 200f) to sy(a.y + 10f + 100f))
        assertEquals(ax0 + 200f, a.x, 2f)
        assertEquals(TableDock.dockedSpot(b, view.tableDraw, a, FloatArray(2))[0], c.x, 0.5f)
        assertEquals(a.y, c.y, 0.5f)
        // «Отклеить» на панели — отклеилась на месте; снова прижали — приклеилась.
        view.select(listOf(c)); idle()
        act.window.decorView.findViewWithTag<View>("sel_undock").performClick(); idle()
        assertEquals("", c.dock)
        afterPen()
        view.select(listOf(c))
        drag(view, sx(c.x + 40f) to sy(c.y + 10f), sx(c.x + 40f + 30f) to sy(c.y + 10f + 8f))
        assertEquals(a.id, c.dock)
        // Оттащили правую далеко — отклеилась.
        afterPen()
        view.select(listOf(c))
        drag(view, sx(c.x + 40f) to sy(c.y + 10f), sx(c.x + 40f + 400f) to sy(c.y + 10f + 300f))
        assertEquals("", c.dock)
    }

    private fun afterPen() { Thread.sleep(1000); idle() }

    private fun event(down: Long, t: Long, action: Int, x: Float, y: Float): MotionEvent {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 0.5f; size = 0.05f })
        return MotionEvent.obtain(down, t, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
    }

    private fun drag(v: View, a: Pair<Float, Float>, b: Pair<Float, Float>) {
        val d = SystemClock.uptimeMillis()
        v.dispatchTouchEvent(event(d, d, MotionEvent.ACTION_DOWN, a.first, a.second))
        for (i in 1..12) v.dispatchTouchEvent(event(d, d + i * 20L, MotionEvent.ACTION_MOVE, a.first + (b.first - a.first) * i / 12, a.second + (b.second - a.second) * i / 12))
        v.dispatchTouchEvent(event(d, d + 300, MotionEvent.ACTION_UP, b.first, b.second))
        idle()
    }

    private fun shot(v: View, name: String) {
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        File("build/screenshots").mkdirs()
        File("build/screenshots/$name").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
