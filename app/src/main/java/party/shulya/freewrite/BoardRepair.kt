package party.shulya.freewrite

/**
 * Проверка доски после ошибки 0.27.5 (номера новых предметов повторялись от запуска к запуску, см. [Ids]): ведомость или
 * таблица получала новый номер, её схемы и строки оставались привязаны к старому и пропадали с глаз; у ведомости появлялись
 * копии (переданная и старая «в сборке»).
 *
 * С 0.28.2 приложение само ничего не исправляет (Владимир: «какого хрена приложение решает, что удалить и что восстановить»).
 * [check] находит, что спряталось или сбилось, и описывает это простыми словами; исправляет [apply] — только когда человек
 * нажал «Исправить» в окне «Проверка доски» (`BoardActivity.offerRepair`). Статусы и даты схем не меняются, позиции
 * ведомостей и строки таблиц не удаляются.
 *
 * Что находит:
 *  - строки и области плана, чья таблица пропала, — в таблицу, где лежит лист плана этих областей;
 *  - схемы, чья ведомость пропала, — в ведомость с тем же номером («З-14») по истории этих схем, заведённую не позже этой
 *    истории (в том числе в ведомость, где уже есть другие схемы: так возвращаются схемы, добавленные в переданную копию);
 *  - копии одной ведомости (вид, номер, место) — оставить ту, чьё состояние совпадает с историей схем, схемы всех копий — в ней;
 *  - ведомость «в сборке», чьи схемы по истории переданы в ней, — вернуть дату передачи.
 * Нет однозначного ответа — ничего не предлагается.
 */
object BoardRepair {

    /** Что исправить. Ссылки на предметы доски, для которой проверяли, — применять сразу ([run]). */
    class Plan {
        internal val recT = LinkedHashMap<BoardRec, String>()
        internal val zoneT = LinkedHashMap<BoardZone, String>()
        internal val dockT = LinkedHashMap<BoardTable, String>()
        internal val fillT = LinkedHashMap<BoardFill, String>()
        internal val itemV = LinkedHashMap<BoardVItem, String>()
        /** Позиция → позиция той же схемы, у которой взять ответ (у этой ответа нет). */
        internal val itemRes = LinkedHashMap<BoardVItem, BoardVItem>()
        internal val vedomDrop = LinkedHashSet<BoardVedom>()
        internal val tableDrop = LinkedHashSet<BoardTable>()
        internal val made = LinkedHashMap<BoardVedom, String>()
        internal val given = LinkedHashMap<BoardVedom, String>()

        /** Что будет сделано — по строке на таблицу или ведомость, для окна «Проверка доски». */
        val lines = ArrayList<String>()
        val isEmpty get() = lines.isEmpty()

        var recs = 0
        var items = 0
        var copies = 0
        var states = 0
        override fun toString() = "строки $recs, схемы ведомостей $items, копии $copies, дата передачи по истории $states"
    }

    /** Проверить и сразу исправить (окно «Исправить», проверки). */
    fun run(b: Board): Plan = check(b).also { if (!it.isEmpty) apply(b, it) }

    fun check(b: Board): Plan {
        val p = Plan()
        if (b.tables.isNotEmpty()) tables(b, p)
        if (b.vedoms.isNotEmpty()) vedoms(b, p)
        return p
    }

    fun apply(b: Board, p: Plan) {
        for ((r, t) in p.recT) r.t = t
        for ((z, t) in p.zoneT) z.t = t
        for ((t, d) in p.dockT) t.dock = d
        for ((f, t) in p.fillT) f.lt = t
        for ((i, src) in p.itemRes) { i.res = src.res; i.rd = src.rd }
        for ((i, v) in p.itemV) i.v = v
        for ((v, m) in p.made) v.made = m
        if (p.vedomDrop.isNotEmpty()) b.vedoms.removeAll(p.vedomDrop)
        if (p.tableDrop.isNotEmpty()) b.tables.removeAll(p.tableDrop)
        for ((v, d) in p.given) v.given = d
        runCatching { Trail.add("Проверка доски — исправлено: $p") }
    }

    // ---------------------------------------------------------------- таблицы

    private fun sameTable(p: BoardTable, q: BoardTable) =
        p.x == q.x && p.y == q.y && p.scale == q.scale && p.title == q.title && p.kind == q.kind

    private fun tables(b: Board, p: Plan) {
        relinkTables(b, p)
        // Пустая копия таблицы поверх таблицы со строками (та же на том же месте) — убрать.
        val withRows = b.recs.mapTo(HashSet()) { p.recT[it] ?: it.t }
        val withZones = b.zones.mapTo(HashSet()) { p.zoneT[it] ?: it.t }
        for (c in b.tables) {
            if (c.id in withRows || c.id in withZones) continue
            val o = b.tables.firstOrNull { it !== c && it.id in withRows && sameTable(it, c) } ?: continue
            p.tableDrop += c
            p.copies++
            p.lines += "Таблица «${IdTables.fullTitle(b, o)}» лежит на доске ещё и пустой копией поверх себя — убрать пустую копию."
        }
    }

    private fun relinkTables(b: Board, p: Plan) {
        val ids = b.tables.mapTo(HashSet()) { it.id }
        val lost = LinkedHashSet<String>()
        for (r in b.recs) if (r.t.isNotEmpty() && r.t !in ids) lost += r.t
        for (z in b.zones) if (z.t.isNotEmpty() && z.t !in ids) lost += z.t
        if (lost.isEmpty()) return
        val bySheet = HashMap<String, MutableList<BoardTable>>()
        for (t in b.tables) for (s in t.sheets) bySheet.getOrPut(s.id) { ArrayList() } += t
        for (old in lost) {
            val found = LinkedHashSet<BoardTable>()
            for (z in b.zones) if (z.t == old) bySheet[z.s]?.let { found += it }
            if (found.isEmpty()) continue
            // Несколько таблиц с этим листом — только если это копии одной таблицы (та же на том же месте).
            val first = found.first()
            if (found.any { !sameTable(it, first) }) continue
            val target = found.firstOrNull { t -> b.recs.any { it.t == t.id } } ?: first
            var n = 0
            for (r in b.recs) if (r.t == old) { p.recT[r] = target.id; n++ }
            for (z in b.zones) if (z.t == old) p.zoneT[z] = target.id
            for (t in b.tables) if (t.dock == old) p.dockT[t] = target.id
            for (f in b.fills) if (f.lt == old) p.fillT[f] = target.id
            p.recs += n
            p.lines += "Таблица «${IdTables.fullTitle(b, target)}»: " +
                (if (n > 0) "вернуть ${Ui.plural(n, "строку", "строки", "строк")} и разметку плана" else "вернуть разметку плана") +
                " — они от неё отвязались и не видны."
        }
    }

    // ---------------------------------------------------------------- ведомости

    private fun copyKey(v: BoardVedom) = listOf(v.kind, v.num, v.x, v.y, v.scale)

    private fun state(v: BoardVedom, given: String = v.given): String =
        if (given.isEmpty()) {
            if (v.kind == Vedoms.LIST) "список" else "«Сборка" + (if (v.made.isNotEmpty()) " от " + IdTables.dateShort(v.made) else "") + "»"
        } else "«" + Vedoms.giveLabel(v.kind) + " от " + IdTables.dateShort(given) + "»"

    private fun vedoms(b: Board, p: Plan) {
        val vOf = HashMap<BoardVItem, String>() // позиция → ведомость после исправления
        for (i in b.vitems) vOf[i] = i.v
        fun recsIn(v: BoardVedom): HashMap<String, BoardVItem> {
            val m = HashMap<String, BoardVItem>()
            for (i in b.vitems) if (vOf[i] == v.id) m.putIfAbsent(i.r, i)
            return m
        }

        // 1. Копии одной ведомости: остаётся та, чьё состояние совпадает с историей схем (история молчит — переданная).
        val survivor = HashMap<BoardVedom, BoardVedom>()
        for (copies in b.vedoms.groupBy { copyKey(it) }.values) {
            if (copies.size < 2) continue
            val ids = copies.mapTo(HashSet()) { it.id }
            val items = b.vitems.filter { it.v in ids }
            val hist = historyGiven(b, copies[0], items)
            val keep = copies.sortedWith(compareByDescending<BoardVedom> {
                if (hist != null) it.given == hist else it.given.isNotEmpty()
            }.thenByDescending { it.given }.thenBy { it.id }).first()
            val have = recsIn(keep)
            for (c in copies) {
                if (c === keep) continue
                survivor[c] = keep
                p.vedomDrop += c
                for (i in items) {
                    if (i.v != c.id) continue
                    val same = have[i.r]
                    if (same == null) { vOf[i] = keep.id; p.itemV[i] = keep.id; have[i.r] = i }
                    else if (same.res.isEmpty() && i.res.isNotEmpty() && same !in p.itemRes) p.itemRes[same] = i
                }
            }
            if (keep.made.isEmpty()) copies.map { it.made }.filter { it.isNotEmpty() }.minOrNull()?.let { p.made[keep] = it }
            p.copies += copies.size - 1
            p.lines += "Ведомость «${keep.num}» лежит на доске " + (if (copies.size == 2) "двумя копиями" else "${copies.size} копиями") +
                " друг на друге (" + copies.joinToString(" и ") { state(it) } + ") — оставить одну, ${state(keep)}, схемы всех копий — в ней."
        }

        // 2. Схемы без ведомости — в ведомость с тем же номером по истории этих схем.
        val ids = b.vedoms.mapTo(HashSet()) { it.id }
        val lost = b.vitems.filter { it.v !in ids }.groupBy { it.v }
        val back = LinkedHashMap<BoardVedom, Int>()
        for ((_, items) in lost) {
            val recIds = items.mapTo(HashSet()) { it.r }
            // По истории схем: номер ведомости → сколько схем группы, самое раннее событие.
            val recsBy = HashMap<String, HashSet<String>>()
            val firstAt = HashMap<String, Long>()
            for (e in b.events) {
                if (e.vn.isEmpty() || e.r !in recIds) continue
                recsBy.getOrPut(e.vn) { HashSet() } += e.r
                val f = firstAt[e.vn]
                if (f == null || e.at < f) firstAt[e.vn] = e.at
            }
            if (recsBy.isEmpty()) continue
            val candidates = b.vedoms.filter { v ->
                v.kind != Vedoms.LIST && recsBy[v.num] != null && (v.made.isEmpty() || IdTables.isoOf(firstAt[v.num]!!) >= v.made)
            }
            val best = candidates.maxByOrNull { recsBy[it.num]!!.size } ?: continue
            // Схемы этой ведомости — хотя бы половина группы; с этим номером — одна ведомость (или копии одной).
            if (recsBy[best.num]!!.size * 2 < recIds.size) continue
            if (candidates.any { it.num == best.num && copyKey(it) != copyKey(best) }) continue
            val target = survivor[best] ?: best
            val have = recsIn(target)
            var n = 0
            for (i in items) {
                val same = have[i.r]
                if (same == null) { vOf[i] = target.id; p.itemV[i] = target.id; have[i.r] = i; n++ }
                else if (same.res.isEmpty() && i.res.isNotEmpty() && same !in p.itemRes) p.itemRes[same] = i
            }
            if (n > 0) { back.merge(target, n, Int::plus); p.items += n }
        }
        for ((v, n) in back) p.lines += "Ведомость «${v.num}»: вернуть ${Ui.plural(n, "схему", "схемы", "схем")} — по истории " +
            (if (n == 1) "она лежит" else "они лежат") + " в этой ведомости, но " + (if (n == 1) "не видна" else "не видны") + "."

        // 3. Ведомость «в сборке», хотя по истории её схемы в ней переданы, — вернуть дату передачи.
        for (v in b.vedoms) {
            if (v in p.vedomDrop || !v.isDraft || v.kind == Vedoms.LIST) continue
            val items = b.vitems.filter { vOf[it] == v.id }
            if (items.isEmpty()) continue
            val d = historyGiven(b, v, items)?.takeIf { it.isNotEmpty() } ?: continue
            p.given[v] = d
            p.states++
            p.lines += "Ведомость «${v.num}»: на доске ${state(v)}, а по истории схем её " +
                Vedoms.giveVerb(v.kind).lowercase() + " ${IdTables.dateShort(d)} — вернуть ${state(v, d)}."
        }
    }

    /**
     * Что говорит история схем [items] о ведомости [v] (по её номеру): дата передачи, "" — в сборке, null — не понять.
     * Передача — события с номером ведомости «унесли заказчику» / «сдали в ПТО» или дата «Отдал заказчику» / «Сдано в ПТО»
     * (не раньше, чем ведомость завели); возврат в сборку — «снова в сборке» позже передачи. Дата — та, что стоит у схем.
     * Переданной считаем, если так у половины схем и больше.
     */
    private fun historyGiven(b: Board, v: BoardVedom, items: List<BoardVItem>): String? {
        val dateKey = when (v.kind) { Vedoms.CUST -> TableCol.DCUST; Vedoms.PTO -> TableCol.DPTO; else -> return null }
        if (v.num.isEmpty()) return null
        val counted = items.filter { !(v.kind == Vedoms.CUST && it.via == Vedoms.AN) }.mapTo(HashSet()) { it.r }
        if (counted.isEmpty()) return null
        val verb = Vedoms.giveVerb(v.kind).lowercase()
        val gaveAt = HashMap<String, Long>()
        val againAt = HashMap<String, Long>()
        for (e in b.events) {
            if (e.vn != v.num || e.r !in counted) continue
            if (e.note == Vedoms.AGAIN_NOTE) {
                if (e.at > (againAt[e.r] ?: Long.MIN_VALUE)) againAt[e.r] = e.at
                continue
            }
            val gave = e.note == verb || e.k == dateKey && e.note.isEmpty() && e.to.isNotEmpty()
            if (!gave || v.made.isNotEmpty() && IdTables.isoOf(e.at) < v.made) continue
            if (e.at > (gaveAt[e.r] ?: Long.MIN_VALUE)) gaveAt[e.r] = e.at
        }
        if (gaveAt.isEmpty()) return null
        val recs = HashMap<String, BoardRec>()
        for (r in b.recs) if (r.id in counted) recs.putIfAbsent(r.id, r)
        val dates = HashMap<String, Int>()
        var back = 0
        for ((rid, at) in gaveAt) {
            if ((againAt[rid] ?: Long.MIN_VALUE) > at) { back++; continue }
            val d = recs[rid]?.get(dateKey).orEmpty()
            if (d.isNotEmpty()) dates.merge(d, 1, Int::plus)
        }
        val best = dates.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key }).firstOrNull()
        return when {
            best != null && best.value * 2 >= counted.size -> best.key
            back * 2 >= counted.size -> ""
            else -> null
        }
    }
}
