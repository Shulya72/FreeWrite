package party.shulya.freewrite

import android.graphics.RectF
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.max

/**
 * Блоки таблиц и раздел-блок.
 *
 * 0.26.3 (Владимир: «несущие слева, ненесущие справа — присоединяю, и они как будто склеиваются, одного масштаба; примечание
 * длинное — таблица шире, соседняя сдвигается; таблицы в рамке раздела за неё не выходят — рамка растёт с ними»): склейка
 * вбок, цепочкой.
 *
 * 0.32.3 (Владимир: «привязка со всех сторон, чтоб делать блок таблиц — этажность и секционность: вправо или влево
 * прикрепляю таблицы других секций, вверх или вниз — другие этажи этих же секций»): **блок — сетка**. У каждой таблицы блока
 * своя клетка: столбец [BoardTable.gc] (секция) и строка [BoardTable.gr] (этаж); [BoardTable.block] — номер первой таблицы
 * блока (она стоит на месте, остальные раскладываются от неё).
 *
 * Правила (после каждой своей правки, [arrange] из `BoardActivity.changed`, не при приёме правок коллег — так планшеты не
 * спорят из-за разной ширины шрифта):
 * 1. Блок раскладывается сеткой: ширина столбца — по самой широкой таблице в нём, высота строки — по самой высокой; таблица —
 *    в левом верхнем углу своей клетки, зазор [GAP]; масштаб у всего блока один (первой таблицы). Пустые столбцы и строки
 *    не занимают места. Таблица шире — соседний столбец отъезжает, у́же — подъезжает; так же по высоте.
 * 2. В одном разделе блоки и отдельные таблицы не наезжают друг на друга: правый отодвигается вправо.
 * 3. Рамка раздела растёт, чтобы таблицы внутри помещались целиком (с полем [PAD]); таблицы ужались — рамка обратно, но не
 *    меньше своего размера ([BoardFrame.ow]/[BoardFrame.oh]).
 *
 * Магнит ([afterMove]): отпустили таблицу (или целый блок) у любой стороны другой таблицы — приклеилась с этой стороны.
 * Клетка занята — для одной таблицы вставляется новый столбец (строка), остальные сдвигаются; блок к блоку — только если все
 * клетки свободны. Блок двигается целиком за любую свою таблицу; вынуть таблицу — «Отклеить» ([detach]).
 */
object TableDock {
    /** Зазор между таблицами блока (единицы таблицы, × масштаб блока). */
    const val GAP = 24f

    /** Поле рамки раздела вокруг таблиц (единицы доски). */
    const val PAD = 40f

    /** С какой стороны от таблицы встаёт приклеенная. */
    enum class Side(val word: String) { RIGHT("справа"), LEFT("слева"), BELOW("снизу"), ABOVE("сверху") }

    /** Куда приклеится: к таблице [anchor] со стороны [side]. */
    class Spot(val anchor: BoardTable, val side: Side)

    private fun idSet() = Collections.newSetFromMap(IdentityHashMap<BoardTable, Boolean>())

    /** Таблица в блоке. */
    fun inBlock(t: BoardTable) = t.block.isNotEmpty()

    /** Все таблицы блока [t] (не в блоке — она одна). */
    fun blockOf(b: Board, t: BoardTable): List<BoardTable> =
        if (t.block.isEmpty()) listOf(t) else b.tables.filter { it.block == t.block }.ifEmpty { listOf(t) }

    /** Остальные таблицы блока — едут и меняют размер вместе с [t]. */
    fun mates(b: Board, t: BoardTable): List<BoardTable> = if (t.block.isEmpty()) emptyList() else b.tables.filter { it !== t && it.block == t.block }

    /** Первая таблица блока — она стоит на месте, остальные раскладываются от неё. */
    fun rootOf(b: Board, t: BoardTable): BoardTable =
        if (t.block.isEmpty()) t else b.tables.firstOrNull { it.id == t.block && it.block == t.block } ?: t

    /** Размер блока: столбцов × строк (занятых). */
    fun size(b: Board, t: BoardTable): Pair<Int, Int> {
        val m = blockOf(b, t)
        return m.map { it.gc }.distinct().size to m.map { it.gr }.distinct().size
    }

    // ---- Сетка блока ----

    /** Раскладка сетки блока: левый край и ширина каждого занятого столбца, верх и высота каждой строки. */
    class Grid(val colX: Map<Int, Float>, val colW: Map<Int, Float>, val rowY: Map<Int, Float>, val rowH: Map<Int, Float>)

    /** Сетка блока от первой таблицы [root] (её место — место её клетки), масштаб [scale]. */
    private fun grid(b: Board, draw: TableDraw, members: List<BoardTable>, root: BoardTable, scale: Float): Grid {
        val colW = HashMap<Int, Float>(); val rowH = HashMap<Int, Float>()
        for (m in members) {
            val l = draw.layout(b, m)
            colW[m.gc] = max(colW[m.gc] ?: 0f, l.w * scale)
            rowH[m.gr] = max(rowH[m.gr] ?: 0f, l.h * scale)
        }
        val gap = GAP * scale
        fun line(keys: List<Int>, size: Map<Int, Float>, at: Int, start: Float): Map<Int, Float> {
            val out = HashMap<Int, Float>()
            val i0 = keys.indexOf(at).coerceAtLeast(0)
            out[keys[i0]] = start
            for (i in i0 + 1 until keys.size) out[keys[i]] = out[keys[i - 1]]!! + size[keys[i - 1]]!! + gap
            for (i in i0 - 1 downTo 0) out[keys[i]] = out[keys[i + 1]]!! - gap - size[keys[i]]!!
            return out
        }
        val cols = colW.keys.sorted(); val rows = rowH.keys.sorted()
        return Grid(line(cols, colW, root.gc, root.x), colW, line(rows, rowH, root.gr, root.y), rowH)
    }

    /** Клетка таблицы в её блоке (не в блоке — сама таблица). По ней видно, где встанет приклеенная. */
    fun cellRect(b: Board, draw: TableDraw, a: BoardTable, out: RectF): RectF {
        if (!inBlock(a)) return draw.bounds(b, a, out)
        val root = rootOf(b, a)
        val g = grid(b, draw, blockOf(b, a), root, root.scale)
        val x = g.colX[a.gc] ?: a.x; val y = g.rowY[a.gr] ?: a.y
        out.set(x, y, x + (g.colW[a.gc] ?: 0f), y + (g.rowH[a.gr] ?: 0f))
        return out
    }

    /** Весь блок [t] (не в блоке — сама таблица): по нынешним местам таблиц. */
    fun blockBounds(b: Board, draw: TableDraw, t: BoardTable, out: RectF): RectF {
        val tb = RectF()
        out.setEmpty()
        for (m in blockOf(b, t)) { draw.bounds(b, m, tb); if (out.isEmpty) out.set(tb) else out.union(tb) }
        return out
    }

    // ---- Правка блоков ----

    /** Вынуть таблицу из блока (на месте). Была первой — первой становится верхняя левая из оставшихся. */
    fun detach(b: Board, t: BoardTable) {
        if (!inBlock(t)) return
        val others = mates(b, t)
        val wasRoot = t.id == t.block
        t.block = ""; t.gc = 0; t.gr = 0
        if (others.size == 1) { others[0].block = ""; others[0].gc = 0; others[0].gr = 0; return }
        if (wasRoot && others.isNotEmpty()) {
            val nr = others.minWith(compareBy<BoardTable>({ it.gr }, { it.gc }, { it.id }))
            for (o in others) o.block = nr.id
        }
    }

    /** Клетка, куда встанет блок [moving] (его крайняя клетка) у таблицы [a] со стороны [side]: сдвиг клеток (столбцы, строки). */
    private fun offset(moving: List<BoardTable>, a: BoardTable, side: Side): Pair<Int, Int> {
        val single = moving.size == 1 && !inBlock(moving[0])
        val minC = if (single) 0 else moving.minOf { it.gc }; val maxC = if (single) 0 else moving.maxOf { it.gc }
        val minR = if (single) 0 else moving.minOf { it.gr }; val maxR = if (single) 0 else moving.maxOf { it.gr }
        return when (side) {
            Side.RIGHT -> (a.gc + 1 - minC) to (a.gr - minR)
            Side.LEFT -> (a.gc - 1 - maxC) to (a.gr - minR)
            Side.BELOW -> (a.gc - minC) to (a.gr + 1 - minR)
            Side.ABOVE -> (a.gc - minC) to (a.gr - 1 - maxR)
        }
    }

    /** Блок [moving] встанет к [a] без наложения клеток (одна таблица встаёт всегда — вставкой столбца или строки). */
    private fun fits(b: Board, moving: List<BoardTable>, a: BoardTable, side: Side): Boolean {
        if (moving.size == 1) return true
        if (!inBlock(a)) return true
        val (dc, dr) = offset(moving, a, side)
        val taken = HashSet<Long>()
        for (m in blockOf(b, a)) taken += key(m.gc, m.gr)
        return moving.none { key(it.gc + dc, it.gr + dr) in taken }
    }

    private fun key(c: Int, r: Int) = (c.toLong() shl 32) or (r.toLong() and 0xffffffffL)

    /**
     * Приклеить таблицу [t] (вместе с её блоком) к таблице [a] со стороны [side]. Одна таблица в занятую клетку — вставляется
     * новый столбец (строка), остальные сдвигаются; блок к блоку — только если все клетки свободны (иначе false, ничего не меняется).
     */
    fun attach(b: Board, t: BoardTable, a: BoardTable, side: Side): Boolean {
        if (t === a) return false
        val moving = blockOf(b, t)
        if (moving.any { it === a }) return false
        if (!fits(b, moving, a, side)) return false
        if (!inBlock(a)) { a.block = a.id; a.gc = 0; a.gr = 0 }
        val (dc, dr) = offset(moving, a, side)
        val root = rootOf(b, a)
        if (moving.size == 1) {
            val members = blockOf(b, a)
            fun target() = when (side) {
                Side.RIGHT -> (a.gc + 1) to a.gr
                Side.LEFT -> (a.gc - 1) to a.gr
                Side.BELOW -> a.gc to (a.gr + 1)
                Side.ABOVE -> a.gc to (a.gr - 1)
            }
            var (tc, tr) = target()
            if (members.any { it !== t && it.gc == tc && it.gr == tr }) {
                // Клетка занята — вставить столбец (строку): справа/снизу — после таблицы, слева/сверху — перед ней
                // (сама таблица и всё за ней сдвигаются на одну клетку).
                when (side) {
                    Side.RIGHT -> { val ins = a.gc + 1; for (m in members) if (m.gc >= ins) m.gc++ }
                    Side.LEFT -> { val ins = a.gc; for (m in members) if (m.gc >= ins) m.gc++ }
                    Side.BELOW -> { val ins = a.gr + 1; for (m in members) if (m.gr >= ins) m.gr++ }
                    Side.ABOVE -> { val ins = a.gr; for (m in members) if (m.gr >= ins) m.gr++ }
                }
                val p = target(); tc = p.first; tr = p.second
            }
            t.gc = tc; t.gr = tr
            t.block = root.block
        } else {
            for (m in moving) { m.gc += dc; m.gr += dr; m.block = root.block }
        }
        val sec = root.sec.ifEmpty { b.frames.firstOrNull { fr -> members(b, fr).any { it === root } }?.id ?: "" }
        for (m in blockOf(b, root)) { m.scale = root.scale; m.sec = sec }
        return true
    }

    /** Блоки в порядок: у каждого есть первая таблица, клетки не повторяются, блок из одной таблицы — не блок. */
    private fun normalize(b: Board): Boolean {
        var changed = false
        val groups = LinkedHashMap<String, MutableList<BoardTable>>()
        for (t in b.tables) if (t.block.isNotEmpty()) groups.getOrPut(t.block) { ArrayList() } += t
        for ((key, ms) in groups) {
            if (ms.size == 1) { val t = ms[0]; t.block = ""; t.gc = 0; t.gr = 0; changed = true; continue }
            if (ms.none { it.id == key }) {
                // Первой таблицы нет (удалили, вынули на другом планшете) — первой становится верхняя левая.
                val nr = ms.minWith(compareBy<BoardTable>({ it.gr }, { it.gc }, { it.id }))
                for (m in ms) m.block = nr.id
                changed = true
            }
            // Две таблицы в одной клетке (правили на двух планшетах сразу) — вторая (по номеру) встаёт новым столбцом справа.
            while (true) {
                val seen = HashMap<Long, BoardTable>()
                val dup = ms.sortedWith(compareBy<BoardTable>({ it.gr }, { it.gc }, { it.id })).firstOrNull { seen.putIfAbsent(key(it.gc, it.gr), it) != null } ?: break
                val c = dup.gc
                for (m in ms) if (m !== dup && m.gc > c) m.gc++
                dup.gc = c + 1
                changed = true
            }
        }
        return changed
    }

    /** Склейки 0.26.3 (цепочка «справа от таблицы», `dk`) — в блоки, справа от той таблицы. */
    private fun migrateLegacy(b: Board): Boolean {
        if (b.tables.none { it.dock.isNotEmpty() }) return false
        var progress = true
        while (progress) {
            progress = false
            for (t in b.tables) {
                if (t.dock.isEmpty()) continue
                val a = b.tables.firstOrNull { it.id == t.dock && it !== t }
                if (a == null) { t.dock = ""; progress = true; continue }
                if (a.dock.isNotEmpty()) continue // сначала та, что левее
                t.dock = ""
                detach(b, t)
                attach(b, t, a, Side.RIGHT)
                progress = true
            }
        }
        for (t in b.tables) t.dock = "" // остались только круги
        return true
    }

    // ---- Разделы ----

    /** Свой прямоугольник раздела (без роста под таблицы). */
    private fun ownRect(fr: BoardFrame, out: RectF): RectF {
        out.set(fr.x, fr.y, fr.x + (if (fr.ow > 0f) fr.ow else fr.w), fr.y + (if (fr.oh > 0f) fr.oh else fr.h))
        return out
    }

    /**
     * Таблицы раздела: поставленные в него ([BoardTable.sec]), без отметки — левый верх внутри своего прямоугольника
     * рамки (не выросшего, чтобы рамка не «глотала» соседей); и все таблицы их блоков.
     */
    fun members(b: Board, fr: BoardFrame): List<BoardTable> {
        val own = ownRect(fr, RectF())
        val out = ArrayList<BoardTable>()
        val seen = idSet()
        for (t in b.tables) {
            val inside = if (t.sec.isNotEmpty() && b.frames.any { it.id == t.sec }) t.sec == fr.id
            else (t.block.isEmpty() || t.block == t.id) && own.contains(t.x, t.y) && smallestAt(b, t.x, t.y, own = true) === fr
            if (inside && seen.add(t)) out += t
        }
        for (t in ArrayList(out)) for (f in mates(b, t)) if (seen.add(f)) out += f
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

    /** Применить правила блоков и разделов. true — что-то сдвинулось. */
    fun arrange(b: Board, draw: TableDraw): Boolean {
        if (b.tables.isEmpty()) return false
        var changed = migrateLegacy(b)
        changed = normalize(b) || changed
        for (t in b.tables) if (t.sec.isNotEmpty() && b.frames.none { it.id == t.sec }) { t.sec = ""; changed = true }
        // 1. Блоки — сеткой от первой таблицы, один масштаб и раздел.
        val done = HashSet<String>()
        for (t in b.tables) {
            if (t.block.isEmpty() || !done.add(t.block)) continue
            val ms = blockOf(b, t)
            val root = rootOf(b, t)
            for (m in ms) {
                if (abs(m.scale - root.scale) > 1e-4f) { m.scale = root.scale; changed = true }
                if (m.sec != root.sec) { m.sec = root.sec; changed = true }
            }
            val g = grid(b, draw, ms, root, root.scale)
            for (m in ms) {
                val x = g.colX[m.gc] ?: continue; val y = g.rowY[m.gr] ?: continue
                if (abs(m.x - x) > 0.05f || abs(m.y - y) > 0.05f) { m.x = x; m.y = y; changed = true }
            }
        }
        // 2. В одном разделе — не наезжать: правый (блок целиком или таблица) отодвигается.
        val ra = RectF(); val rb = RectF()
        for (fr in b.frames) {
            val units = ArrayList<List<BoardTable>>()
            val seen = HashSet<String>()
            for (t in members(b, fr)) {
                if (t.block.isEmpty()) units += listOf(t)
                else if (seen.add(t.block)) units += blockOf(b, t)
            }
            // Блок, приклеенный влево или вверх, вылез за левый или верхний край рамки — сдвигается внутрь целиком (рамка растёт
            // только вправо и вниз). Таблиц, что и так внутри, это не касается.
            for (u in units) {
                blockBounds(b, draw, u[0], rb)
                val dx = if (rb.left < fr.x - 0.05f) fr.x + PAD - rb.left else 0f
                val dy = if (rb.top < fr.y - 0.05f) fr.y + PAD - rb.top else 0f
                if (dx != 0f || dy != 0f) { for (m in u) { m.x += dx; m.y += dy }; changed = true }
            }
            if (units.size < 2) continue
            fun left(u: List<BoardTable>) = u.minOf { it.x }
            val sorted = units.sortedBy { left(it) }
            for (i in sorted.indices) for (j in i + 1 until sorted.size) {
                val ua = sorted[i]; val uc = sorted[j]
                if (left(uc) < left(ua)) continue
                blockBounds(b, draw, ua[0], ra); blockBounds(b, draw, uc[0], rb)
                if (rb.top >= ra.bottom || rb.bottom <= ra.top) continue // одна под другой
                val need = ra.right + GAP * ua[0].scale
                if (rb.left < need - 0.05f) {
                    val dx = need - rb.left
                    for (m in uc) { m.x += dx; m.sec = fr.id } // ушла за свой край рамки — всё равно в разделе
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

    // ---- Магнит ----

    /**
     * Куда приклеится [t] (с её блоком), если отпустить здесь: край блока в [snap] (единицы доски) от стороны клетки другой
     * таблицы — справа, слева, снизу или сверху. Не к своему блоку и не к закреплённым; блок — только туда, где клетки свободны.
     */
    fun candidate(b: Board, draw: TableDraw, t: BoardTable, snap: Float): Spot? {
        val moving = blockOf(b, t)
        val own = idSet().apply { addAll(moving) }
        val rb = blockBounds(b, draw, t, RectF())
        val ra = RectF()
        var best: Spot? = null
        var bestD = Float.MAX_VALUE
        for (a in b.tables) {
            if (a in own || a.locked) continue
            cellRect(b, draw, a, ra)
            val gap = GAP * rootOf(b, a).scale
            val cross = snap * 1.5f
            for (side in Side.values()) {
                val (d1, d2) = when (side) {
                    Side.RIGHT -> abs(rb.left - (ra.right + gap)) to abs(rb.top - ra.top)
                    Side.LEFT -> abs(rb.right - (ra.left - gap)) to abs(rb.top - ra.top)
                    Side.BELOW -> abs(rb.top - (ra.bottom + gap)) to abs(rb.left - ra.left)
                    Side.ABOVE -> abs(rb.bottom - (ra.top - gap)) to abs(rb.left - ra.left)
                }
                if (d1 > snap || d2 > cross) continue
                val d = d1 + d2
                if (d < bestD && fits(b, moving, a, side)) { bestD = d; best = Spot(a, side) }
            }
        }
        return best
    }

    /**
     * Таблицу отпустили после переноса или изменения размера ([origScale] — масштаб до жеста): у стороны другой таблицы —
     * приклеить (вернуть сторону); размер меняли у таблицы блока — у всего блока (через первую). Раздел — по месту.
     */
    fun afterMove(b: Board, draw: TableDraw, t: BoardTable, origScale: Float?, snap: Float): Side? {
        val scaled = origScale != null && abs(origScale - t.scale) > 1e-4f
        if (scaled && inBlock(t)) {
            rootOf(b, t).scale = t.scale // блок — одного масштаба: размер всего блока
            return null
        }
        val s = candidate(b, draw, t, snap)
        if (s != null && attach(b, t, s.anchor, s.side)) return s.side
        val r = blockBounds(b, draw, t, RectF())
        val sec = smallestAt(b, r.left, r.top, own = false)?.id ?: ""
        for (m in blockOf(b, t)) m.sec = sec
        return null
    }
}
