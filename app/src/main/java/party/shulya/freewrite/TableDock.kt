package party.shulya.freewrite

import android.graphics.RectF
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.max

/**
 * Склейка таблиц и раздел-блок (0.26.3, Владимир: «несущие слева, ненесущие справа — присоединяю, и они как будто
 * склеиваются, одного масштаба, увеличиваются и уменьшаются вместе; примечание длинное — таблица шире, соседняя
 * сдвигается; таблицы в рамке раздела за неё не выходят — рамка растёт с ними, один единый блок»).
 *
 * Правила (применяются после каждой своей правки, [arrange] из `BoardActivity.changed`, не при приёме правок коллег —
 * так планшеты не спорят из-за разной ширины шрифта):
 * 1. Склеенная таблица ([BoardTable.dock] — номер таблицы слева) стоит вплотную справа от неё: тот же верх, тот же
 *    масштаб, зазор [GAP]. Левая шире — правая отъезжает, у́же — подъезжает.
 * 2. В одном разделе таблицы не наезжают друг на друга: правая отодвигается вправо.
 * 3. Рамка раздела растёт, чтобы таблицы внутри помещались целиком (с полем [PAD]); таблицы ужались — рамка обратно, но не
 *    меньше своего размера ([BoardFrame.ow]/[BoardFrame.oh]).
 *
 * Магнит ([afterMove]): отпустили таблицу у правого края другой — приклеилась; оттащили — отклеилась.
 */
object TableDock {
    /** Зазор между склеенными таблицами (единицы таблицы, × масштаб левой). */
    const val GAP = 24f

    /** Поле рамки раздела вокруг таблиц (единицы доски). */
    const val PAD = 40f

    private fun idSet() = Collections.newSetFromMap(IdentityHashMap<BoardTable, Boolean>())

    /** Таблица слева, к которой приклеена [t] (null — не приклеена или её нет). */
    fun anchorOf(b: Board, t: BoardTable): BoardTable? =
        if (t.dock.isEmpty()) null else b.tables.firstOrNull { it.id == t.dock && it !== t }

    /** Первая таблица склейки (самая левая). */
    fun root(b: Board, t: BoardTable): BoardTable {
        var cur = t
        val seen = idSet()
        while (seen.add(cur)) cur = anchorOf(b, cur) ?: return cur
        return t // круг — считаем началом саму таблицу
    }

    /** Все приклеенные к [t] справа (и к ним — дальше), по порядку. */
    fun followers(b: Board, t: BoardTable): List<BoardTable> {
        val out = ArrayList<BoardTable>()
        val seen = idSet(); seen.add(t)
        val queue = ArrayDeque<BoardTable>(); queue.add(t)
        while (queue.isNotEmpty()) {
            val a = queue.removeFirst()
            for (f in b.tables) if (f.dock == a.id && f !== a && seen.add(f)) { out += f; queue.add(f) }
        }
        return out
    }

    /** Место приклеенной справа от [a]: x, y. */
    fun dockedSpot(b: Board, draw: TableDraw, a: BoardTable, out: FloatArray): FloatArray {
        out[0] = a.x + (draw.layout(b, a).w + GAP) * a.scale
        out[1] = a.y
        return out
    }

    /** Свой прямоугольник раздела (без роста под таблицы). */
    private fun ownRect(fr: BoardFrame, out: RectF): RectF {
        out.set(fr.x, fr.y, fr.x + (if (fr.ow > 0f) fr.ow else fr.w), fr.y + (if (fr.oh > 0f) fr.oh else fr.h))
        return out
    }

    /**
     * Таблицы раздела: поставленные в него ([BoardTable.sec]), без отметки — левый верх внутри своего прямоугольника
     * рамки (не выросшего, чтобы рамка не «глотала» соседей); и все приклеенные к ним.
     */
    fun members(b: Board, fr: BoardFrame): List<BoardTable> {
        val own = ownRect(fr, RectF())
        val out = ArrayList<BoardTable>()
        val seen = idSet()
        for (t in b.tables) {
            val inside = if (t.sec.isNotEmpty() && b.frames.any { it.id == t.sec }) t.sec == fr.id
            else t.dock.isEmpty() && own.contains(t.x, t.y) && smallestAt(b, t.x, t.y, own = true) === fr
            if (inside && seen.add(t)) out += t
        }
        for (t in ArrayList(out)) for (f in followers(b, t)) if (seen.add(f)) out += f
        return out
    }

    /** Самая маленькая рамка, в чьём (своём или нынешнем) прямоугольнике точка. */
    private fun smallestAt(b: Board, x: Float, y: Float, own: Boolean): BoardFrame? {
        val r = RectF()
        var best: BoardFrame? = null
        for (fr in b.frames) {
            if (own) ownRect(fr, r) else r.set(fr.x, fr.y, fr.x + fr.w, fr.y + fr.h)
            if (!r.contains(x, y)) continue
            if (best == null || r.width() * r.height() < best.w * best.h) best = fr
        }
        return best
    }

    /** Применить правила склейки и разделов. true — что-то сдвинулось. */
    fun arrange(b: Board, draw: TableDraw): Boolean {
        if (b.tables.isEmpty()) return false
        var changed = false
        // Склейки к пропавшим таблицам и по кругу — снять; отметки пропавших разделов — тоже.
        for (t in b.tables) {
            if (t.dock.isNotEmpty() && (anchorOf(b, t) == null || root(b, t) === t)) { t.dock = ""; changed = true }
            if (t.sec.isNotEmpty() && b.frames.none { it.id == t.sec }) { t.sec = ""; changed = true }
        }
        // 1. Склеенные — вплотную справа, тот же верх и масштаб (от первой таблицы склейки по порядку).
        val spot = FloatArray(2)
        for (r in b.tables.filter { it.dock.isEmpty() }) {
            for (f in followers(b, r)) {
                val a = anchorOf(b, f) ?: continue
                dockedSpot(b, draw, a, spot)
                if (abs(f.scale - a.scale) > 1e-4f || abs(f.x - spot[0]) > 0.05f || abs(f.y - spot[1]) > 0.05f) {
                    f.scale = a.scale; f.x = spot[0]; f.y = spot[1]
                    changed = true
                }
                if (f.sec != a.sec) { f.sec = a.sec; changed = true }
            }
        }
        // 2. В одном разделе — не наезжать: правая отодвигается (вместе с приклеенными к ней).
        val ra = RectF(); val rb = RectF()
        for (fr in b.frames) {
            val ms = members(b, fr).sortedBy { it.x }
            for (i in ms.indices) for (j in i + 1 until ms.size) {
                val a = ms[i]; val c = ms[j]
                if (c.dock == a.id || c.x < a.x) continue
                draw.bounds(b, a, ra); draw.bounds(b, c, rb)
                if (rb.top >= ra.bottom || rb.bottom <= ra.top) continue // одна под другой
                val need = ra.right + GAP * a.scale
                if (c.x < need - 0.05f) {
                    val dx = need - c.x
                    c.x += dx
                    if (c.dock.isEmpty()) c.sec = fr.id // ушла за свой край рамки — всё равно в разделе
                    for (f in followers(b, c)) f.x += dx
                    changed = true
                }
            }
        }
        // 3. Рамка раздела — по таблицам внутри (не меньше своего размера).
        val req = RectF(); val tb = RectF()
        for (fr in b.frames) {
            val ms = members(b, fr)
            val ownW = if (fr.ow > 0f) fr.ow else fr.w
            val ownH = if (fr.oh > 0f) fr.oh else fr.h
            var needW = ownW; var needH = ownH
            if (ms.isNotEmpty()) {
                req.setEmpty()
                for (t in ms) { draw.bounds(b, t, tb); if (req.isEmpty) req.set(tb) else req.union(tb) }
                needW = max(ownW, req.right + PAD - fr.x)
                needH = max(ownH, req.bottom + PAD - fr.y)
            }
            if (abs(needW - fr.w) > 0.5f || abs(needH - fr.h) > 0.5f) {
                fr.w = needW; fr.h = needH
                changed = true
            }
            val nOw = if (needW > ownW + 0.5f) ownW else 0f
            val nOh = if (needH > ownH + 0.5f) ownH else 0f
            if (nOw != fr.ow || nOh != fr.oh) { fr.ow = nOw; fr.oh = nOh; changed = true }
        }
        return changed
    }

    /**
     * Таблица слева, к которой [t] приклеится, если отпустить её здесь: левый верх [t] в [snap] (единицы доски) от места
     * справа от неё. Не к себе и не к тем, кто приклеен к [t].
     */
    fun candidate(b: Board, draw: TableDraw, t: BoardTable, snap: Float): BoardTable? {
        val own = followers(b, t).toHashSet()
        val spot = FloatArray(2)
        var best: BoardTable? = null
        var bestD = Float.MAX_VALUE
        for (a in b.tables) {
            if (a === t || a in own || a.locked) continue
            dockedSpot(b, draw, a, spot)
            val dx = abs(t.x - spot[0]); val dy = abs(t.y - spot[1])
            if (dx > snap || dy > snap * 1.5f) continue
            val d = dx + dy
            if (d < bestD) { bestD = d; best = a }
        }
        return best
    }

    /**
     * Таблицу отпустили после переноса или изменения размера ([origScale] — масштаб до жеста): рядом с правым краем
     * другой — приклеить; приклеенную оттащили — отклеить; размер меняли у приклеенной — у всей склейки (через первую).
     * Раздел — по месту, куда поставили.
     */
    fun afterMove(b: Board, draw: TableDraw, t: BoardTable, origScale: Float?, snap: Float) {
        val scaled = origScale != null && abs(origScale - t.scale) > 1e-4f
        if (scaled && t.dock.isNotEmpty()) {
            root(b, t).scale = t.scale // склейка — одного масштаба: размер всей склейки
            return
        }
        val c = candidate(b, draw, t, snap)
        if (c != null) {
            t.dock = c.id
            t.scale = c.scale
            t.sec = c.sec.ifEmpty { b.frames.firstOrNull { fr -> members(b, fr).any { it === c } }?.id ?: "" }
            return
        }
        if (t.dock.isNotEmpty()) {
            val a = anchorOf(b, t)
            val spot = FloatArray(2)
            if (a == null || abs(t.x - dockedSpot(b, draw, a, spot)[0]) > snap || abs(t.y - spot[1]) > snap * 1.5f) t.dock = ""
        }
        if (t.dock.isEmpty()) t.sec = smallestAt(b, t.x, t.y, own = false)?.id ?: ""
    }
}
