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
import java.io.File

/**
 * Блоки таблиц и раздел-блок.
 * 0.26.3 (Владимир: «несущие слева, ненесущие справа — присоединяю, они склеиваются, одного масштаба; примечание длинное —
 * соседняя сдвигается; рамка раздела растёт с ними — один единый блок»).
 * 0.32.3 (Владимир: «привязка со всех сторон — блок таблиц: вправо или влево другие секции, вверх или вниз другие этажи»).
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

    private fun table(b: Board, x: Float, y: Float, title: String, rows: Int = 2): BoardTable {
        val t = BoardTable(x, y, 1f, title).apply { kind = TableKinds.KJ_VERT.id }
        b.tables += t
        for (i in 1..rows) b.recs += BoardRec(t.id, i.toDouble()).apply { zah = "$i"; work = "Бетон" }
        return t
    }

    private fun board(): Board {
        val b = Board.create("КЖ 6.1.1")
        b.frames += BoardFrame(0f, 0f, 1800f, 700f, "КЖ 6.1.1")
        table(b, 60f, 80f, "Несущие стены и колонны")
        table(b, 900f, 80f, "Ненесущие стены и колонны")
        return b
    }

    private fun w(b: Board, d: TableDraw, t: BoardTable) = d.layout(b, t).w * t.scale
    private fun h(b: Board, d: TableDraw, t: BoardTable) = d.layout(b, t).h * t.scale

    @Test
    fun blockOfSectionsAndFloorsLaysOutAsGrid() {
        val b = Board.create("Корпус 1")
        val d = TableDraw()
        val s1f1 = table(b, 100f, 100f, "Секция 1 · этаж 1")
        val s2f1 = table(b, 3000f, 100f, "Секция 2 · этаж 1", rows = 6) // выше соседки
        val s1f2 = table(b, 100f, 3000f, "Секция 1 · этаж 2")
        val s2f2 = table(b, 3000f, 3000f, "Секция 2 · этаж 2")
        assertTrue(TableDock.attach(b, s2f1, s1f1, TableDock.Side.RIGHT))
        assertTrue(TableDock.attach(b, s1f2, s1f1, TableDock.Side.BELOW))
        assertTrue(TableDock.attach(b, s2f2, s2f1, TableDock.Side.BELOW))
        TableDock.arrange(b, d)
        val gap = TableDock.GAP
        // Первая стоит на месте, секции — столбцами, этажи — рядами.
        assertEquals(100f, s1f1.x, 0.01f); assertEquals(100f, s1f1.y, 0.01f)
        assertEquals(listOf(0 to 0, 1 to 0, 0 to 1, 1 to 1), listOf(s1f1, s2f1, s1f2, s2f2).map { it.gc to it.gr })
        val col1 = 100f + maxOf(w(b, d, s1f1), w(b, d, s1f2)) + gap
        val row1 = 100f + maxOf(h(b, d, s1f1), h(b, d, s2f1)) + gap
        assertEquals(col1, s2f1.x, 0.1f); assertEquals(100f, s2f1.y, 0.1f)
        assertEquals(100f, s1f2.x, 0.1f); assertEquals("этаж 2 — под самой высокой таблицей этажа 1", row1, s1f2.y, 0.1f)
        assertEquals(col1, s2f2.x, 0.1f); assertEquals(row1, s2f2.y, 0.1f)
        assertEquals(2 to 2, TableDock.size(b, s2f2))
        // Длинное примечание у таблицы второго этажа первой секции — вся вторая секция отъехала.
        val x0 = s2f1.x
        b.recs.first { it.t == s1f2.id }.note = "Замечание технадзора: переделать армирование узла примыкания, акт 14 от 12.11, повторная приёмка"
        TableDock.arrange(b, d)
        assertTrue("секция 2 отъехала: ${s2f1.x} > $x0", s2f1.x > x0 + 50f)
        assertEquals(s2f1.x, s2f2.x, 0.1f)
        // Слева и сверху: первая таблица остаётся на месте, новые — левее и выше.
        val s0f1 = table(b, 5000f, 100f, "Секция 0 · этаж 1")
        val s1f0 = table(b, 5000f, 3000f, "Секция 1 · подвал")
        assertTrue(TableDock.attach(b, s0f1, s1f1, TableDock.Side.LEFT))
        assertTrue(TableDock.attach(b, s1f0, s1f1, TableDock.Side.ABOVE))
        TableDock.arrange(b, d)
        assertEquals(100f, s1f1.x, 0.01f); assertEquals(100f, s1f1.y, 0.01f)
        assertEquals(100f - gap - w(b, d, s0f1), s0f1.x, 0.1f); assertEquals(100f, s0f1.y, 0.1f)
        assertEquals(100f, s1f0.x, 0.1f); assertEquals(100f - gap - h(b, d, s1f0), s1f0.y, 0.1f)
        // Справа от первой уже занято — новая секция вставляется между, вторая отъезжает дальше.
        val mid = table(b, 5000f, 5000f, "Секция 1А · этаж 1")
        assertTrue(TableDock.attach(b, mid, s1f1, TableDock.Side.RIGHT))
        TableDock.arrange(b, d)
        assertEquals(1, mid.gc); assertEquals(2, s2f1.gc); assertEquals(2, s2f2.gc)
        assertTrue("вставленная — между", s1f1.x < mid.x && mid.x < s2f1.x)
        assertEquals(s2f1.x, s2f2.x, 0.1f)
        // Масштаб первой — у всего блока.
        s1f1.scale = 0.8f
        TableDock.arrange(b, d)
        for (t in b.tables) assertEquals(0.8f, t.scale, 1e-4f)
        assertEquals(100f + maxOf(w(b, d, s1f1), w(b, d, s1f2)) + gap * 0.8f, mid.x, 0.1f)
    }

    @Test
    fun blockInSectionGrowsFrameAndStaysInside() {
        val b = board()
        val d = TableDraw()
        val (a, c) = b.tables
        val fr = b.frames[0]
        TableDock.attach(b, c, a, TableDock.Side.RIGHT)
        val e = table(b, 60f, 2000f, "Стены подвала")
        TableDock.attach(b, e, a, TableDock.Side.BELOW)
        TableDock.arrange(b, d)
        assertEquals(fr.id, e.sec)
        val all = TableDock.blockBounds(b, d, a, RectF())
        assertEquals(maxOf(1800f, all.right + TableDock.PAD), fr.w, 0.6f)
        assertEquals(maxOf(700f, all.bottom + TableDock.PAD), fr.h, 0.6f)
        assertEquals(700f, fr.oh, 0.01f)
        // Приклеили слева — блок вылез бы за левый край рамки: сдвигается внутрь целиком.
        val l = table(b, 60f, 5000f, "Лестница")
        TableDock.attach(b, l, a, TableDock.Side.LEFT)
        TableDock.arrange(b, d)
        assertTrue("слева внутри рамки: ${l.x}", l.x >= fr.x)
        assertEquals(a.x, l.x + w(b, d, l) + TableDock.GAP, 0.1f)
        assertEquals(a.y, l.y, 0.1f)
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
    fun oldSideGlueBecomesBlockAndJsonKeepsIt() {
        val b = board()
        val d = TableDraw()
        val (a, c) = b.tables
        // Склейка 0.26.3 (в файле `dk`) — читается и при первой правке становится блоком: та же таблица справа.
        c.dock = a.id
        val back = Board.fromJson(JSONObject(b.toJsonString()))
        val (a2, c2) = back.tables
        assertEquals(a.id, c2.dock)
        TableDock.arrange(back, d)
        assertEquals("", c2.dock)
        assertEquals(a2.id, a2.block); assertEquals(a2.id, c2.block)
        assertEquals(1 to 0, c2.gc to c2.gr)
        assertEquals(a2.x + w(back, d, a2) + TableDock.GAP, c2.x, 0.1f); assertEquals(a2.y, c2.y, 0.1f)
        // Блок, раздел и свой размер рамки — в JSON; у таблицы не в блоке полей нет.
        c2.sec = back.frames[0].id
        val t2 = IdTables.parseTable(JSONObject(IdTables.tableJson(c2)))
        assertEquals(a2.id, t2.block); assertEquals(1, t2.gc); assertEquals(0, t2.gr); assertEquals(back.frames[0].id, t2.sec)
        val lone = BoardTable(0f, 0f, 1f, "Одна")
        assertFalse(IdTables.tableJson(lone).contains("\"gb\"")); assertFalse(IdTables.tableJson(lone).contains("\"dk\""))
        val fr = back.frames[0].apply { w = 2400f; ow = 1800f; oh = 0f }
        val f2 = Board.parseFrame(JSONObject(Board.frameJson(fr)))
        assertEquals(1800f, f2.ow, 0.01f); assertEquals(0f, f2.oh, 0.01f)
        assertEquals(0f, Board.parseFrame(JSONObject("""{"id":"f1","x":0,"y":0,"w":10,"h":10,"title":"Р"}""")).ow, 0f)
        // Цепочка из трёх (с веткой: две таблицы справа от одной) — все в блок, без наложений.
        val e = table(back, 4000f, 80f, "Третья"); val f = table(back, 4000f, 900f, "Четвёртая")
        for (t in listOf(a2, c2)) { t.block = ""; t.gc = 0; t.gr = 0 }
        c2.dock = a2.id; e.dock = c2.id; f.dock = c2.id
        TableDock.arrange(back, d)
        assertEquals(listOf(0, 1, 2, 3), listOf(a2, c2, e, f).map { it.gc }.sorted())
        assertTrue(listOf(a2, c2, e, f).all { it.block == a2.id && it.dock.isEmpty() })
    }

    @Test
    fun detachAndRepairKeepBlockConsistent() {
        val b = Board.create("Корпус 2")
        val d = TableDraw()
        val a = table(b, 0f, 0f, "А"); val c = table(b, 0f, 0f, "Б"); val e = table(b, 0f, 0f, "В")
        TableDock.attach(b, c, a, TableDock.Side.RIGHT)
        TableDock.attach(b, e, a, TableDock.Side.BELOW)
        TableDock.arrange(b, d)
        val cx = c.x; val cy = c.y
        // Первую вынули — первой стала верхняя левая из оставшихся, она стоит на месте.
        TableDock.detach(b, a)
        assertEquals("", a.block)
        assertEquals(c.id, c.block); assertEquals(c.id, e.block)
        TableDock.arrange(b, d)
        assertEquals(cx, c.x, 0.01f); assertEquals(cy, c.y, 0.01f)
        // Осталась одна — уже не блок.
        TableDock.detach(b, e)
        assertEquals("", c.block)
        // Первую удалили на другом планшете — первой стала верхняя левая; две таблицы в одной клетке — вторая справа.
        val p = table(b, 0f, 0f, "Г"); val q = table(b, 0f, 0f, "Д"); val r = table(b, 0f, 0f, "Е")
        TableDock.attach(b, q, p, TableDock.Side.RIGHT); TableDock.attach(b, r, p, TableDock.Side.BELOW)
        b.tables.remove(p)
        r.gc = q.gc; r.gr = q.gr
        TableDock.arrange(b, d)
        assertTrue(q.block.isNotEmpty() && q.block == r.block && b.tables.any { it.id == q.block })
        assertTrue("клетки разные", q.gc to q.gr != r.gc to r.gr)
        assertFalse(RectF.intersects(d.bounds(b, q, RectF()), d.bounds(b, r, RectF())))
        // Блок к блоку — только если клетки свободны.
        val x1 = table(b, 0f, 8000f, "Х1"); val x2 = table(b, 0f, 8000f, "Х2")
        TableDock.attach(b, x2, x1, TableDock.Side.BELOW)
        val m1 = table(b, 0f, 9000f, "М1"); val m2 = table(b, 0f, 9000f, "М2")
        TableDock.attach(b, m2, m1, TableDock.Side.RIGHT)
        assertFalse("под Х1 занято Х2 — блок М не встаёт", TableDock.attach(b, m1, x1, TableDock.Side.BELOW))
        assertEquals(m1.id, m2.block)
        assertTrue(TableDock.attach(b, m1, x1, TableDock.Side.RIGHT))
        assertEquals(listOf(x1.block), listOf(x2, m1, m2).map { it.block }.distinct())
        assertEquals(listOf(1 to 0, 2 to 0), listOf(m1, m2).map { it.gc to it.gr })
    }

    @Test
    fun magnetOnScreenAllSidesAndBlockMovesWhole() {
        val b0 = board()
        table(b0, 60f, 1400f, "Стены подвала")
        BoardStore(ctx).save(b0)
        val act = Robolectric.buildActivity(BoardActivity::class.java, Intent(ctx, BoardActivity::class.java).putExtra(BoardActivity.EXTRA_ID, b0.id)).setup().get()
        idle()
        val view = BoardActivity::class.java.getDeclaredField("view").apply { isAccessible = true }.get(act) as BoardView
        val b = act.board()
        val draw = view.tableDraw
        val (a, c, e) = b.tables
        fun sx(x: Float) = (x - b.camX) * b.camZ
        fun sy(y: Float) = (y - b.camY) * b.camZ
        act.setTool(BoardActivity.Tool.SELECT)
        // Правую — к правому краю левой (на 25 единиц правее и на 12 ниже) — приклеилась справа. Берём за середину заголовка:
        // у уголка — размер, а не перенос.
        val spotX = a.x + w(b, draw, a) + TableDock.GAP
        view.fitRect(a.x - 40f, a.y - 200f, spotX + 900f, a.y + 1600f); idle()
        view.select(listOf(c))
        drag(view, sx(c.x + 300f) to sy(c.y + 20f), sx(spotX + 25f + 300f) to sy(a.y + 12f + 20f))
        assertEquals(a.id, c.block); assertEquals(1 to 0, c.gc to c.gr)
        assertEquals(spotX, c.x, 0.5f); assertEquals(a.y, c.y, 0.5f)
        // Третью — под левую (на 10 правее и 15 ниже места) — приклеилась снизу, под самой высокой таблицей ряда.
        afterPen()
        val row = TableDock.cellRect(b, draw, a, RectF())
        view.fitRect(a.x - 40f, a.y - 200f, spotX + 900f, e.y + 800f); idle()
        view.select(listOf(e))
        drag(view, sx(e.x + 300f) to sy(e.y + 20f), sx(a.x + 10f + 300f) to sy(row.bottom + TableDock.GAP + 15f + 20f), shotMid = "tables-block-hint.png")
        assertEquals(a.id, e.block); assertEquals(0 to 1, e.gc to e.gr)
        assertEquals(a.x, e.x, 0.5f); assertEquals(row.bottom + TableDock.GAP, e.y, 0.5f)
        shot(view, "tables-block.png")
        // Тянем правую верхнюю — едет весь блок.
        afterPen()
        view.select(listOf(c)); idle()
        shot(view, "tables-block-selected.png")
        val ax0 = a.x; val ay0 = a.y; val ex0 = e.x; val ey0 = e.y
        drag(view, sx(c.x + 300f) to sy(c.y + 20f), sx(c.x + 300f + 200f) to sy(c.y + 20f + 100f))
        assertEquals(ax0 + 200f, a.x, 2f); assertEquals(ay0 + 100f, a.y, 2f)
        assertEquals(ex0 + 200f, e.x, 2f); assertEquals(ey0 + 100f, e.y, 2f)
        assertEquals(a.x + w(b, draw, a) + TableDock.GAP, c.x, 0.5f)
        // «Отклеить» нижнюю — вынута, верхние двое — по-прежнему блок; «Отменить» — снова в блоке.
        view.select(listOf(e)); idle()
        act.window.decorView.findViewWithTag<View>("sel_undock").performClick(); idle()
        assertEquals("", e.block)
        assertEquals(a.block, c.block); assertTrue(a.block.isNotEmpty())
        act.undo(); idle()
        val e2 = act.board().tables.first { it.id == e.id }
        assertEquals(act.board().tables.first { it.id == a.id }.block, e2.block)
    }

    private fun afterPen() { Thread.sleep(1000); idle() }

    private fun event(down: Long, t: Long, action: Int, x: Float, y: Float): MotionEvent {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 0.5f; size = 0.05f })
        return MotionEvent.obtain(down, t, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
    }

    private fun drag(v: View, a: Pair<Float, Float>, b: Pair<Float, Float>, shotMid: String? = null) {
        val d = SystemClock.uptimeMillis()
        v.dispatchTouchEvent(event(d, d, MotionEvent.ACTION_DOWN, a.first, a.second))
        for (i in 1..12) v.dispatchTouchEvent(event(d, d + i * 20L, MotionEvent.ACTION_MOVE, a.first + (b.first - a.first) * i / 12, a.second + (b.second - a.second) * i / 12))
        if (shotMid != null) { idle(); shot(v, shotMid) }
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
