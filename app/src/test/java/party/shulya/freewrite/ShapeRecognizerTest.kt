package party.shulya.freewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Распознавание фигур на рисунках «от руки»: линии с дрожанием, перехлёст в конце,
 * недорисованный круг. И наоборот — буквы и знаки не должны превращаться в фигуры.
 */
class ShapeRecognizerTest {

    private val minSize = 40f
    private val step = 6f

    // ------------------------------------------------------------------ рисунки «от руки»

    /** Ломаная через точки, с частыми точками (как идут события пера). */
    private fun poly(vararg p: Float): FloatArray {
        val out = ArrayList<Float>()
        out += p[0]; out += p[1]
        var i = 2
        while (i + 1 < p.size) {
            val ax = p[i - 2]; val ay = p[i - 1]; val bx = p[i]; val by = p[i + 1]
            val n = maxOf(1, (hypot(bx - ax, by - ay) / 3f).toInt())
            for (k in 1..n) { out += ax + (bx - ax) * k / n; out += ay + (by - ay) * k / n }
            i += 2
        }
        return out.toFloatArray()
    }

    /** Дуга овала: от угла a0 на sweep радиан (больше 2π — с перехлёстом). */
    private fun arc(cx: Float, cy: Float, rx: Float, ry: Float, a0: Double, sweep: Double, rot: Double = 0.0): FloatArray {
        val n = 160
        val out = FloatArray((n + 1) * 2)
        for (i in 0..n) {
            val t = a0 + sweep * i / n
            val u = rx * cos(t); val w = ry * sin(t)
            out[i * 2] = (cx + u * cos(rot) - w * sin(rot)).toFloat()
            out[i * 2 + 1] = (cy + u * sin(rot) + w * cos(rot)).toFloat()
        }
        return out
    }

    /** Дрожание руки: плавная волна + мелкий шум (амплитуда в пикселях). */
    private fun wobble(s: FloatArray, amp: Float, seed: Int = 1): FloatArray {
        val r = java.util.Random(seed.toLong())
        val out = s.copyOf()
        var len = 0f
        var i = 0
        while (i + 1 < s.size) {
            if (i >= 2) len += hypot(s[i] - s[i - 2], s[i + 1] - s[i - 1])
            out[i] += (amp * (sin(len / 41f + seed) + 0.4f * sin(len / 13f + 2 * seed)) + amp * 0.3f * (r.nextFloat() - 0.5f)).toFloat()
            out[i + 1] += (amp * (cos(len / 37f + 3 * seed) + 0.4f * sin(len / 11f + seed)) + amp * 0.3f * (r.nextFloat() - 0.5f)).toFloat()
            i += 2
        }
        return out
    }

    private fun rec(vararg strokes: FloatArray) = ShapeRecognizer.recognize(strokes.toList(), minSize, step)

    private fun kind(vararg strokes: FloatArray) = rec(*strokes)?.kind

    private fun box(p: FloatArray): FloatArray {
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        var i = 0
        while (i + 1 < p.size) { l = minOf(l, p[i]); t = minOf(t, p[i + 1]); r = maxOf(r, p[i]); b = maxOf(b, p[i + 1]); i += 2 }
        return floatArrayOf(l, t, r, b)
    }

    // ------------------------------------------------------------------ фигуры

    @Test
    fun line_isStraightenedAndLevelled() {
        val s = rec(wobble(poly(100f, 300f, 520f, 318f), 3f))!!
        assertEquals(ShapeRecognizer.Kind.LINE, s.kind)
        assertEquals("почти горизонтальная — выровнена", s.pts[1], s.pts[s.pts.size - 1], 0.01f)
        assertEquals(ShapeRecognizer.Kind.LINE, kind(wobble(poly(100f, 100f, 400f, 280f), 3f, 2)))
    }

    @Test
    fun arrow_oneStroke() {
        // Древко, усик вверх, обратно к кончику, усик вниз — не отрывая пера.
        val s = rec(wobble(poly(100f, 300f, 500f, 290f, 430f, 250f, 500f, 290f, 435f, 335f), 2.5f))!!
        assertEquals(ShapeRecognizer.Kind.ARROW, s.kind)
        val b = box(s.pts)
        assertTrue("кончик справа", b[2] in 495f..510f)
    }

    @Test
    fun arrow_shaftThenHead() {
        val shaft = wobble(poly(100f, 300f, 500f, 304f), 2.5f)
        val head = wobble(poly(445f, 255f, 502f, 302f, 442f, 348f), 2f, 3)
        assertEquals(ShapeRecognizer.Kind.ARROW, kind(shaft, head))
        // Усики отдельными штрихами, стрелка влево.
        val left = poly(500f, 300f, 120f, 300f)
        assertEquals(ShapeRecognizer.Kind.ARROW, kind(left, poly(175f, 255f, 118f, 300f), poly(118f, 302f, 178f, 345f)))
        // Стрелка вниз.
        assertEquals(ShapeRecognizer.Kind.ARROW, kind(poly(300f, 100f, 300f, 450f), poly(260f, 395f, 300f, 452f, 342f, 398f)))
    }

    @Test
    fun arrow_double() {
        val s = rec(poly(100f, 300f, 500f, 300f), poly(150f, 255f, 98f, 300f, 152f, 346f), poly(450f, 255f, 502f, 300f, 448f, 346f))
        assertEquals(ShapeRecognizer.Kind.DOUBLE_ARROW, s?.kind)
    }

    /** Кривая с наконечником: точки линии (x, y подряд) + усики у конца по касательной. */
    private fun withHead(body: FloatArray, barb: Float, oneStroke: Boolean): List<FloatArray> {
        val n = body.size / 2
        val tx = body[n * 2 - 2]; val ty = body[n * 2 - 1]
        val px = body[n * 2 - 12]; val py = body[n * 2 - 11]
        val l = hypot(tx - px, ty - py)
        val dx = (tx - px) / l; val dy = (ty - py) / l
        fun rot(a: Double): FloatArray {
            val c = cos(a).toFloat(); val s = sin(a).toFloat()
            val bx = -dx; val by = -dy
            return floatArrayOf(tx + barb * (bx * c - by * s), ty + barb * (bx * s + by * c))
        }
        val l1 = rot(Math.toRadians(32.0)); val r1 = rot(Math.toRadians(-30.0))
        return if (oneStroke) {
            listOf(body + poly(tx, ty, l1[0], l1[1], tx, ty, r1[0], r1[1]).copyOfRange(2, poly(tx, ty, l1[0], l1[1], tx, ty, r1[0], r1[1]).size))
        } else {
            listOf(body, poly(l1[0], l1[1], tx + 2, ty + 1, r1[0], r1[1]))
        }
    }

    @Test
    fun arrow_curved() {
        // Дуга четверть круга со стрелкой одним штрихом.
        val arcBody = wobble(arc(300f, 400f, 260f, 260f, PI, PI / 2), 2f)
        val a = rec(*withHead(arcBody, 55f, true).toTypedArray())!!
        assertEquals(ShapeRecognizer.Kind.ARROW, a.kind)
        // Древко осталось дугой: середина далеко от прямой «начало — кончик».
        val mid = a.pts.size / 4 * 2
        val x0 = a.pts[0]; val y0 = a.pts[1]
        val dev = abs((560f - x0) * (a.pts[mid + 1] - y0) - (140f - y0) * (a.pts[mid] - x0)) / hypot(560f - x0, 140f - y0)
        assertTrue("дуга, а не прямая (отклонение $dev)", dev > 40f)
        // Волна со стрелкой.
        val wave = FloatArray(160) { k -> val i = k / 2; if (k % 2 == 0) 100f + i * 6f else (300 + 50 * sin(i / 80.0 * 2 * PI)).toFloat() }
        assertEquals(ShapeRecognizer.Kind.ARROW, kind(*withHead(wobble(wave, 1.5f, 3), 45f, true).toTypedArray()))
        // Дуга, потом наконечник отдельным штрихом.
        assertEquals(ShapeRecognizer.Kind.ARROW, kind(*withHead(wobble(arc(300f, 300f, 220f, 150f, 0.0, PI * 0.9), 2f, 5), 50f, false).toTypedArray()))
        // Просто дуга без наконечника — не фигура.
        assertNull(kind(wobble(arc(300f, 400f, 260f, 260f, PI, PI / 2), 2f)))
    }

    @Test
    fun rectangle_withOvershoot() {
        // Начали с середины верхней стороны, обошли и чуть заехали дальше начала.
        val r = wobble(poly(300f, 100f, 520f, 102f, 518f, 350f, 82f, 348f, 80f, 98f, 340f, 104f), 3f)
        val s = rec(r)!!
        assertEquals(ShapeRecognizer.Kind.RECTANGLE, s.kind)
        val b = box(s.pts)
        assertEquals(440f, b[2] - b[0], 20f)
        assertEquals(250f, b[3] - b[1], 20f)
    }

    @Test
    fun square_fromCorner_slightlyTilted() {
        val a = Math.toRadians(4.0)
        fun p(x: Float, y: Float) = floatArrayOf((200 + x * cos(a) - y * sin(a)).toFloat(), (200 + x * sin(a) + y * cos(a)).toFloat())
        val c = listOf(p(0f, 0f), p(300f, 0f), p(300f, 300f), p(0f, 300f), p(0f, 6f)).flatMap { it.toList() }.toFloatArray()
        val s = rec(wobble(poly(*c), 3f, 4))!!
        assertEquals(ShapeRecognizer.Kind.SQUARE, s.kind)
        val b = box(s.pts)
        assertEquals("ровный: стороны по осям", b[2] - b[0], b[3] - b[1], 1f)
    }

    @Test
    fun rectangle_rotated() {
        val a = Math.toRadians(30.0)
        fun p(x: Float, y: Float) = floatArrayOf((300 + x * cos(a) - y * sin(a)).toFloat(), (200 + x * sin(a) + y * cos(a)).toFloat())
        val c = listOf(p(0f, 0f), p(360f, 0f), p(360f, 180f), p(0f, 180f), p(0f, 0f)).flatMap { it.toList() }.toFloatArray()
        val s = rec(wobble(poly(*c), 2.5f, 5))!!
        assertEquals(ShapeRecognizer.Kind.RECTANGLE, s.kind)
        val ang = Math.toDegrees(atan2((s.pts[3] - s.pts[1]).toDouble(), (s.pts[2] - s.pts[0]).toDouble()))
        assertTrue("наклон сохранён (≈30°), а не выровнен: $ang", abs(abs(ang) - 30) < 6 || abs(abs(ang) - 60) < 6)
    }

    @Test
    fun rectangle_twoStrokes() {
        val a = poly(100f, 100f, 450f, 100f, 450f, 300f)
        val b = poly(452f, 304f, 100f, 300f, 102f, 104f)
        assertEquals(ShapeRecognizer.Kind.RECTANGLE, kind(wobble(a, 2f), wobble(b, 2f, 7)))
    }

    @Test
    fun circle_overshootAndGap() {
        assertEquals(ShapeRecognizer.Kind.CIRCLE, kind(wobble(arc(300f, 300f, 150f, 150f, 0.3, 2 * PI + 0.35), 3f)))
        // Не дотянули до начала.
        assertEquals(ShapeRecognizer.Kind.CIRCLE, kind(wobble(arc(300f, 300f, 140f, 132f, 1.0, 2 * PI - 0.45), 3f, 6)))
    }

    @Test
    fun ellipse_levelledAndTilted() {
        val s = rec(wobble(arc(300f, 300f, 220f, 110f, -1.0, 2 * PI + 0.2, Math.toRadians(5.0)), 3f))!!
        assertEquals(ShapeRecognizer.Kind.ELLIPSE, s.kind)
        val b = box(s.pts)
        assertEquals("выровнен: ширина ≈ 440", 440f, b[2] - b[0], 20f)
        assertEquals(ShapeRecognizer.Kind.ELLIPSE, kind(wobble(arc(300f, 300f, 220f, 100f, 0.0, 2 * PI + 0.2, Math.toRadians(40.0)), 3f, 8)))
    }

    @Test
    fun triangle_rhombus_pentagon() {
        assertEquals(ShapeRecognizer.Kind.TRIANGLE, kind(wobble(poly(300f, 100f, 480f, 400f, 120f, 404f, 296f, 104f), 3f)))
        assertEquals(ShapeRecognizer.Kind.RHOMBUS, kind(wobble(poly(300f, 100f, 470f, 260f, 300f, 420f, 130f, 260f, 302f, 104f), 3f)))
        val pent = (0..5).flatMap { i ->
            val t = -PI / 2 + 2 * PI * i / 5
            listOf((300 + 180 * cos(t)).toFloat(), (300 + 180 * sin(t)).toFloat())
        }.toFloatArray()
        assertEquals(ShapeRecognizer.Kind.POLYGON, kind(wobble(poly(*pent), 2.5f)))
    }

    @Test
    fun result_isDenseForEraser() {
        val s = rec(poly(100f, 100f, 500f, 100f, 500f, 400f, 100f, 400f, 100f, 100f))!!
        var i = 2
        while (i + 1 < s.pts.size) {
            assertTrue(hypot(s.pts[i] - s.pts[i - 2], s.pts[i + 1] - s.pts[i - 1]) <= step + 0.5f)
            i += 2
        }
    }

    // ------------------------------------------------------------------ не фигуры

    @Test
    fun handwriting_isNotAShape() {
        // Слитное письмо «ииии»: волны с петлями.
        val n = 200
        val word = FloatArray(n * 2) { k ->
            val i = k / 2
            val t = i / n.toDouble() * 8 * PI
            if (k % 2 == 0) (100 + i * 2.2 + 18 * sin(t * 1.5)).toFloat() else (300 - 45 * abs(sin(t))).toFloat()
        }
        assertNull("слитное письмо", kind(word))
        // Спираль.
        val sp = FloatArray(400) { k ->
            val i = k / 2
            val t = i / 200.0 * 5 * PI
            val r = 10 + 25 * t
            if (k % 2 == 0) (300 + r * cos(t)).toFloat() else (300 + r * sin(t)).toFloat()
        }
        assertNull("спираль", kind(sp))
        // «S».
        assertNull("S", kind(wobble(arc(300f, 200f, 90f, 90f, -0.3, -PI * 1.4) + arc(300f, 380f, 90f, 90f, -PI / 2, PI * 1.4), 2f)))
        // Зигзаг.
        assertNull("зигзаг", kind(poly(100f, 300f, 160f, 200f, 220f, 300f, 280f, 200f, 340f, 300f, 400f, 200f)))
        // Волнистая черта.
        val wave = FloatArray(200) { k -> val i = k / 2; if (k % 2 == 0) (100 + i * 4f) else (300 + 35 * sin(i / 100.0 * 4 * PI)).toFloat() }
        assertNull("волна", kind(wave))
        // Двойка.
        assertNull("2", kind(arc(300f, 200f, 90f, 80f, PI, PI * 1.25) + poly(340f, 260f, 200f, 420f, 420f, 420f)))
    }

    @Test
    fun signsAndLetters_areNotShapes() {
        assertNull("галочка", kind(poly(100f, 250f, 180f, 330f, 380f, 80f)))
        assertNull("Г", kind(poly(100f, 450f, 100f, 100f, 360f, 100f)))
        assertNull("7", kind(poly(100f, 100f, 330f, 100f, 180f, 450f)))
        assertNull("<", kind(poly(400f, 100f, 100f, 260f, 400f, 420f)))
        assertNull("Z", kind(poly(100f, 100f, 400f, 100f, 100f, 400f, 400f, 400f)))
        assertNull("Т", kind(poly(100f, 100f, 400f, 100f), poly(250f, 100f, 250f, 430f)))
        assertNull("+", kind(poly(100f, 250f, 400f, 250f), poly(250f, 100f, 250f, 400f)))
        assertNull("=", kind(poly(100f, 200f, 400f, 200f), poly(100f, 280f, 400f, 280f)))
        assertNull("Х", kind(poly(100f, 100f, 400f, 420f), poly(400f, 100f, 100f, 420f)))
        assertNull("Л", kind(poly(100f, 420f, 250f, 100f, 400f, 420f)))
        assertNull("маленький кружок — это буква «о»", kind(arc(100f, 100f, 12f, 12f, 0.0, 2 * PI)))
    }
}
