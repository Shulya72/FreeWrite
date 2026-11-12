package party.shulya.freewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

/** «Волшебная заливка» (0.23) на нарисованных в тесте чертежах: помещения, дверь, круг, колонна, штриховка, стена. */
class MagicFillTest {

    private class Img(val w: Int, val h: Int) {
        val lum = ByteArray(w * h) { 255.toByte() }
        fun box(l: Int, t: Int, r: Int, b: Int, v: Int = 0) { for (y in t until b) for (x in l until r) if (x in 0 until w && y in 0 until h) lum[y * w + x] = v.toByte() }
        /** Рамка толщиной th. */
        fun frame(l: Int, t: Int, r: Int, b: Int, th: Int = 3) { box(l, t, r, t + th); box(l, b - th, r, b); box(l, t, l + th, b); box(r - th, t, r, b) }
        fun circle(cx: Int, cy: Int, rad: Int, th: Int = 3) {
            for (y in 0 until h) for (x in 0 until w) {
                val d = Math.hypot((x - cx).toDouble(), (y - cy).toDouble())
                if (d in (rad - th / 2.0)..(rad + th / 2.0)) lum[y * w + x] = 0
            }
        }
        fun find(x: Int, y: Int, gap: Int): MagicFill.Result {
            val ink = MagicFill.inkMask(lum)
            val closed = MagicFill.dilate(ink, w, h, gap)
            return MagicFill.find(ink, closed, w, h, x, y, gap)
        }
    }

    /** Два помещения, между ними стена с дверью 10 px. */
    private fun rooms(): Img = Img(400, 300).apply {
        frame(20, 20, 380, 280)
        box(199, 20, 202, 140); box(199, 150, 202, 280) // дверь y 140…150
    }

    @Test
    fun roomWithDoorNeedsGap() {
        val im = rooms()
        val open = im.find(100, 150, 0)
        assertTrue("open ${open.error} ${open.pts.size}", open.ok); assertFalse("open leaked ${open.area}", open.leaked)
        val closed = im.find(100, 150, 6)
        assertTrue("closed ${closed.error} ${closed.pts.size}", closed.ok); assertFalse("closed leaked", closed.leaked)
        assertTrue("без зазора вытекло в соседнее помещение: ${open.area} vs ${closed.area}", open.area > closed.area * 1.7)
        val a = MagicFill.area(closed.pts)
        assertTrue("площадь помещения ≈ 180×260: $a", a in 42000f..50000f)
        // Контур упрощён: у прямоугольного помещения немного точек.
        assertTrue("точек ${closed.pts.size / 2}", closed.pts.size / 2 <= 24)
    }

    @Test
    fun outsideLeaks() {
        val r = rooms().find(5, 5, 2)
        assertTrue(r.leaked)
    }

    @Test
    fun circleRoom() {
        val im = Img(300, 300).apply { circle(150, 150, 80) }
        val r = im.find(150, 150, 2)
        assertTrue(r.ok); assertFalse(r.leaked)
        val a = MagicFill.area(r.pts)
        val want = (PI * 80 * 80).toFloat()
        assertTrue("круг: $a vs $want", a in want * 0.9f..want * 1.08f)
    }

    @Test
    fun solidAndHatchedColumns() {
        val im = Img(500, 400).apply {
            frame(10, 10, 490, 390)
            box(100, 100, 114, 114) // сплошная колонна 14×14
            frame(300, 200, 324, 224, 2) // пилон 24×24 со штриховкой
            for (k in -24..24 step 4) for (i in 0 until 24) { val x = 300 + i; val y = 200 + i + k; if (y in 200 until 224) box(x, y, x + 1, y + 1) }
        }
        val c = im.find(107, 107, 2)
        assertTrue(c.ok); assertTrue(c.onInk)
        assertTrue("колонна: ${MagicFill.area(c.pts)}", MagicFill.area(c.pts) in 120f..260f)
        val p = im.find(312, 212, 2)
        assertTrue(p.ok); assertTrue(p.onInk)
        assertTrue("пилон со штриховкой: ${MagicFill.area(p.pts)}", MagicFill.area(p.pts) in 380f..700f)
        // Палец чуть мимо колонны (на бумаге в 2 px) — всё равно помещение, не колонна: касание на бумаге.
        val room = im.find(250, 300, 2)
        assertTrue(room.ok); assertFalse(room.onInk)
    }

    @Test
    fun thinLineOrWallIsNotAColumn() {
        val im = rooms()
        val r = im.find(21, 150, 1) // по наружной стене 3 px
        assertFalse(r.ok)
        assertTrue(r.error == MagicFill.ERR_LINE || r.error == MagicFill.ERR_WALL)
    }

    @Test
    fun shapesAndSimplify() {
        val e = MagicFill.ellipse(0.1f, 0.1f, 0.3f, 0.5f)
        assertEquals(96, e.size)
        val sq = MagicFill.rect(0.4f, 0.4f, 0.2f, 0.1f)
        assertEquals(0.2f, sq[0], 1e-6f); assertEquals(0.1f, sq[1], 1e-6f)
        // Прямая с дрожью — две точки.
        val line = FloatArray(40) { if (it % 2 == 0) it.toFloat() else (if (it % 4 == 1) 0.2f else -0.2f) }
        val box = line + floatArrayOf(38f, 30f, 0f, 30f)
        assertTrue(MagicFill.simplify(box, 1f).size / 2 <= 6)
    }

    @Test
    fun bigSheetIsFast() {
        // Лист 2048×1448: сетка помещений; заливка помещения с зазором — быстро.
        val im = Img(2048, 1448)
        for (x in 0..2048 step 160) im.box(x, 0, x + 3, 1448)
        for (y in 0..1448 step 120) im.box(0, y, 2048, y + 3)
        val t0 = System.nanoTime()
        val ink = MagicFill.inkMask(im.lum)
        val closed = MagicFill.dilate(ink, im.w, im.h, 4)
        val r = MagicFill.find(ink, closed, im.w, im.h, 250, 180, 4)
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("BENCH magic fill 2048×1448 gap 4: $ms ms")
        assertTrue(r.ok)
        assertTrue(ms < 3000)
    }
}
