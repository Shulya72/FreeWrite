package party.shulya.freewrite

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import java.util.IdentityHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow

/**
 * Плитки штрихов (0.16, FW-009) — чтобы большая доска (тысячи штрихов, например перенесённая из Nebo)
 * двигалась и приближалась плавно.
 *
 * Штрихи рисуются заранее, в фоне, в квадратные картинки [SIZE]×[SIZE] пикселей — по уровням масштаба через √2
 * (плитка нарисована не мельче, чем показывается, — штрихи чёткие). Кадр собирается из готовых плиток: его цена
 * не зависит от числа штрихов. Картинки, заливки, надписи, метки и выноски рисуются как раньше — их немного.
 *
 * Что изменилось на доске, узнаём сами, без участия остального кода: каждый кадр — быстрая сумма по штрихам
 * (номер, номер изменения точек, цвет, толщина); не совпала — сравниваем по штрихам и перерисовываем только
 * плитки там, где штрих появился, пропал или поменялся. Выделенные штрихи в плитки не входят (их двигают —
 * они рисуются поверх, как раньше).
 *
 * Пока плитка не готова: старая (до правки) — на её месте штрихи рисуются напрямую; ещё не рисованная — берём
 * готовые плитки соседнего уровня (чуть мягче), а при снимке (пипетка, кадр под пером) — рисуем напрямую.
 *
 * Все вызовы — из главного потока, кроме отрисовки плиток (в фоне, по неизменяемым копиям штрихов).
 */
class BoardTiles(private val onReady: () -> Unit) {

    companion object {
        const val SIZE = 512

        /** Уровень плиток для масштаба z: масштаб уровня 2^(k/2) ≥ z (не мельче, чем на экране). */
        fun level(z: Float): Int = ceil(2.0 * ln(z.toDouble()) / ln(2.0) - 1e-4).toInt()

        fun scale(k: Int): Float = 2.0.pow(k / 2.0).toFloat()

        /** Сколько плиток с картинками держать в памяти (по 1 МБ). Видимых — до ~50. */
        const val MAX_BITMAPS = 110

        /** Сколько всего записей о плитках (пустые — без картинки — почти ничего не весят). */
        private const val MAX_TILES = 3000

        /** Сразу столько правок — проще перерисовать всё. */
        private const val MANY_DIRTY = 300

        /** Номер плитки: уровень (8 бит) и номера по x и y (по 28 бит, со сдвигом — бывают отрицательные). */
        private fun key(k: Int, ix: Int, iy: Int): Long {
            val m = 0xFFFFFFFL
            val kx = (ix + (1 shl 27)).toLong() and m
            val ky = (iy + (1 shl 27)).toLong() and m
            return ((k + 64).toLong() shl 56) or (kx shl 28) or ky
        }
    }

    /** Неизменяемая копия штриха — её и рисуют в фоне. */
    private class Item(
        val d: Drawing,
        val version: Int,
        val color: Int,
        val width: Float,
        val kind: String,
        val uniform: Boolean,
        val pts: FloatArray,
        val l: Float, val t: Float, val r: Float, val b: Float,
    ) {
        fun hits(x0: Float, y0: Float, x1: Float, y1: Float) = r >= x0 && l <= x1 && b >= y0 && t <= y1
    }

    private class Tile(val k: Int, val ix: Int, val iy: Int) {
        var bmp: Bitmap? = null
        /** Нарисована по текущим штрихам (пустая — тоже готова: bmp == null). */
        var valid = false
        /** Хоть раз нарисована (для «старая плитка — рисуем напрямую»). */
        var rendered = false
        /** Номер правки: ответ из фона для старого номера выбрасывается. */
        var gen = 0
        var inFlight = false
        var used = 0L
        /** Сколько раз подряд не удалось нарисовать (ошибка) — после двух рисуем напрямую, не мучаем фон. */
        var failures = 0
    }

    private val main = Handler(Looper.getMainLooper())
    private val exec: ExecutorService = Executors.newFixedThreadPool(2) { r -> Thread(r, "board-tiles").apply { priority = Thread.NORM_PRIORITY - 1 } }
    @Volatile private var closed = false

    private val tiles = HashMap<Long, Tile>()
    private var items = IdentityHashMap<Drawing, Item>()
    private var order: Array<Item> = emptyArray()
    private var checksum = Long.MIN_VALUE
    private var frame = 0L
    private var inFlight = 0
    private val want = ArrayList<Tile>()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()

    /** Сколько плиток с картинками сейчас в памяти (для проверок и замеров). */
    val bitmapCount: Int get() = tiles.values.count { it.bmp != null }

    // ------------------------------------------------------------ правки

    /**
     * Сверить штрихи доски с тем, что нарисовано в плитках. selected — выделенные (в плитки не входят).
     * Возвращает, сколько штрихов видно в прямоугольнике [vis] (для замеров).
     */
    fun sync(strokes: List<Drawing>, selected: Set<Drawing>, vis: RectF): Int {
        var h = strokes.size.toLong()
        var seen = 0
        for (d in strokes) {
            if (selected.isNotEmpty() && d in selected) continue
            h = h * 31 + d.id.hashCode()
            h = h * 31 + d.version
            h = h * 31 + d.color
            h = h * 31 + d.width.toRawBits()
            val bb = d.bounds()
            if (bb.right >= vis.left && bb.left <= vis.right && bb.bottom >= vis.top && bb.top <= vis.bottom) seen++
        }
        h = h * 31 + selected.size
        if (h != checksum) {
            rebuild(strokes, selected)
            checksum = h
        }
        return seen
    }

    private fun rebuild(strokes: List<Drawing>, selected: Set<Drawing>) {
        val old = items
        val fresh = IdentityHashMap<Drawing, Item>(strokes.size * 2)
        val list = ArrayList<Item>(strokes.size)
        val dirty = ArrayList<RectF>()
        var all = false
        for (d in strokes) {
            if (selected.isNotEmpty() && d in selected) continue
            val was = old.remove(d)
            val it = if (was != null && was.version == d.version && was.color == d.color && was.width == d.width) {
                was
            } else {
                val bb = d.bounds()
                val n = Item(d, d.version, d.color, d.width, d.kind, d.uniform, d.pts.copyOf(), bb.left, bb.top, bb.right, bb.bottom)
                if (!all) {
                    if (was != null) dirty += RectF(was.l, was.t, was.r, was.b)
                    dirty += RectF(n.l, n.t, n.r, n.b)
                    if (dirty.size > MANY_DIRTY) all = true
                }
                n
            }
            fresh[d] = it
            list += it
        }
        for (gone in old.values) {
            if (all) break
            dirty += RectF(gone.l, gone.t, gone.r, gone.b)
            if (dirty.size > MANY_DIRTY) all = true
        }
        items = fresh
        order = list.toTypedArray()
        if (all) {
            for (t in tiles.values) stale(t)
        } else if (dirty.isNotEmpty()) {
            for (t in tiles.values) {
                if (!t.valid && !t.inFlight) continue
                val s = SIZE / scale(t.k)
                val x0 = t.ix * s; val y0 = t.iy * s
                for (r in dirty) if (r.right >= x0 && r.left <= x0 + s && r.bottom >= y0 && r.top <= y0 + s) { stale(t); break }
            }
        }
    }

    private fun stale(t: Tile) {
        t.valid = false
        t.gen++
        t.failures = 0
    }

    /** Забыть всё нарисованное (другая доска). */
    fun reset() {
        tiles.clear()
        items = IdentityHashMap()
        order = emptyArray()
        checksum = Long.MIN_VALUE
    }

    // ------------------------------------------------------------ кадр

    /**
     * Нарисовать штрихи видимой части [vis] при масштабе z (canvas уже в координатах доски).
     * complete — снимок (пипетка, кадр под пером): чего нет в плитках — нарисовать напрямую, без пропусков.
     * live(rect) — нарисовать штрихи прямоугольника напрямую (по-старому) с обрезкой.
     */
    fun draw(canvas: Canvas, vis: RectF, z: Float, complete: Boolean, live: (RectF) -> Unit) {
        frame++
        val k = level(z)
        val s = SIZE / scale(k)
        val ix0 = floor(vis.left / s).toInt(); val ix1 = floor(vis.right / s).toInt()
        val iy0 = floor(vis.top / s).toInt(); val iy1 = floor(vis.bottom / s).toInt()
        want.clear()
        for (iy in iy0..iy1) for (ix in ix0..ix1) {
            val t = tiles.getOrPut(key(k, ix, iy)) { Tile(k, ix, iy) }
            t.used = frame
            rect.set(ix * s, iy * s, (ix + 1) * s, (iy + 1) * s)
            if (t.valid) {
                t.bmp?.let { canvas.drawBitmap(it, null, rect, bitmapPaint) }
                continue
            }
            if (!t.inFlight && t.failures < 2) want += t
            when {
                t.rendered || complete || t.failures >= 2 -> live(RectF(rect)) // старая плитка или снимок — по-старому, но верно
                !fallback(canvas, k, rect) && complete -> live(RectF(rect))
            }
        }
        // Запас вокруг экрана — когда видимое готово.
        if (want.isEmpty()) for (iy in iy0 - 1..iy1 + 1) for (ix in ix0 - 1..ix1 + 1) {
            if (ix in ix0..ix1 && iy in iy0..iy1) continue
            val t = tiles.getOrPut(key(k, ix, iy)) { Tile(k, ix, iy) }
            t.used = frame
            if (!t.valid && !t.inFlight && t.failures < 2) want += t
        }
        pump(vis)
        trim()
    }

    /** Подставить готовые плитки соседних уровней (крупнее или мельче) — пока своя рисуется. */
    private fun fallback(canvas: Canvas, k: Int, r: RectF): Boolean {
        for (dk in intArrayOf(-1, 1, -2, 2, -3, 3, -4)) {
            val k2 = k + dk
            val s2 = SIZE / scale(k2)
            val ix0 = floor(r.left / s2).toInt(); val ix1 = floor((r.right - 1e-3f) / s2).toInt()
            val iy0 = floor(r.top / s2).toInt(); val iy1 = floor((r.bottom - 1e-3f) / s2).toInt()
            if ((ix1 - ix0 + 1) * (iy1 - iy0 + 1) > 16) continue
            var ok = true
            loop@ for (iy in iy0..iy1) for (ix in ix0..ix1) {
                val t = tiles[key(k2, ix, iy)]
                if (t == null || !t.valid) { ok = false; break@loop }
            }
            if (!ok) continue
            canvas.save()
            canvas.clipRect(r)
            for (iy in iy0..iy1) for (ix in ix0..ix1) {
                val t = tiles[key(k2, ix, iy)]!!
                t.used = frame
                val b = t.bmp ?: continue
                canvas.drawBitmap(b, null, RectF(ix * s2, iy * s2, (ix + 1) * s2, (iy + 1) * s2), bitmapPaint)
            }
            canvas.restore()
            return true
        }
        return false
    }

    /** Отдать в фон нужные плитки: сначала ближние к середине экрана, не больше двух сразу. */
    private fun pump(vis: RectF) {
        if (closed || want.isEmpty() || inFlight >= 2) return
        val cx = vis.centerX(); val cy = vis.centerY()
        want.sortBy { t ->
            val s = SIZE / scale(t.k)
            hypot((t.ix + 0.5f) * s - cx, (t.iy + 0.5f) * s - cy)
        }
        for (t in want) {
            if (inFlight >= 2) break
            submit(t)
        }
    }

    private fun submit(t: Tile) {
        t.inFlight = true
        inFlight++
        val gen = t.gen
        val snapshot = order
        val k = t.k; val ix = t.ix; val iy = t.iy
        try {
            exec.execute {
                val result = try { render(snapshot, k, ix, iy) } catch (e: Throwable) { e }
                main.post { done(t, gen, result) }
            }
        } catch (_: Throwable) {
            t.inFlight = false
            inFlight--
        }
    }

    private fun done(t: Tile, gen: Int, result: Any?) {
        inFlight--
        t.inFlight = false
        if (closed) return
        if (result is Throwable) {
            // Нехватка памяти — отпустить дальние плитки, попробовать в следующем кадре.
            if (result is OutOfMemoryError) evict(MAX_BITMAPS / 2)
            if (t.gen == gen) t.failures++
            android.util.Log.w("FreeWrite", "tile ${t.k}/${t.ix}/${t.iy}", result)
            onReady()
            return
        }
        if (t.gen == gen) {
            t.bmp = result as Bitmap?
            t.valid = true
            t.rendered = true
        }
        onReady()
    }

    /** Нарисовать одну плитку (в фоне). null — в плитке ничего нет. */
    private fun render(items: Array<Item>, k: Int, ix: Int, iy: Int): Bitmap? {
        val sc = scale(k)
        val s = SIZE / sc
        val x0 = ix * s; val y0 = iy * s; val x1 = x0 + s; val y1 = y0 + s
        val tiny = 1.5f / sc
        val fast = sc < 0.6f
        var bmp: Bitmap? = null
        var canvas: Canvas? = null
        val painter = InkPainter()
        for (it in items) {
            if (closed) return null
            if (!it.hits(x0, y0, x1, y1)) continue
            if (it.r - it.l < tiny && it.b - it.t < tiny) continue
            if (canvas == null) {
                bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
                canvas = Canvas(bmp).apply { scale(sc, sc); translate(-x0, -y0) }
            }
            painter.drawRaw(canvas, it.kind, it.color, it.width, it.pts, it.uniform, fast)
        }
        return bmp
    }

    /** Лишние плитки: картинки дальних — отпустить (кроме нарисованных в этом кадре). */
    private fun trim() {
        if (tiles.size > MAX_TILES) {
            val old = tiles.entries.filter { it.value.used < frame && !it.value.inFlight }.sortedBy { it.value.used }
            for (e in old.take(tiles.size - MAX_TILES / 2)) tiles.remove(e.key)
        }
        evict(MAX_BITMAPS)
    }

    private fun evict(limit: Int) {
        val withBmp = tiles.values.filter { it.bmp != null }
        if (withBmp.size <= limit) return
        for (t in withBmp.filter { it.used < frame }.sortedBy { it.used }.take(withBmp.size - limit)) {
            t.bmp = null
            t.valid = false
            t.rendered = false
        }
    }

    fun close() {
        closed = true
        exec.shutdownNow()
        tiles.clear()
    }

    /** Есть ли ещё не готовые видимые плитки (для проверок: дождаться). */
    val busy: Boolean get() = inFlight > 0 || want.isNotEmpty()
}
