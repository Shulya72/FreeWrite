package party.shulya.freewrite

/**
 * Починка ссылок при чтении доски (0.27.6, Владимир: «ведомость от 18-го стала пустая, хотя в таблицах у схем горит
 * «На подписи у заказчика от …»»).
 *
 * Причина: в 0.27.5 номера новых предметов повторялись от запуска к запуску (см. [Ids]). Когда номер ведомости совпал с номером
 * штриха или строки, при чтении доски ведомость получала новый номер, а её схемы оставались привязаны к старому — ведомость
 * пустела, схемы «висели» ни к чему. То же могло случиться с таблицей (строки и области плана «висят»). Здесь их возвращаем:
 *  - схемы без ведомости — в ту пустую ведомость, чей номер («З-14») записан в истории этих схем (добавление в сборку,
 *    передача, ответы пишутся с номером ведомости) и которая заведена не позже этой истории;
 *  - строки и области без таблицы — в таблицу, где лежит лист плана этих областей;
 *  - пустые копии таблицы (то же название и место — следы той же ошибки на других планшетах) убираются, если у оригинала
 *    есть содержимое; копии ведомости сливаются в одну — ту, чьё состояние совпадает с историей схем (0.28.1);
 *  - ведомости «в сборке», чьи схемы по истории переданы в ней, возвращается дата передачи (0.28.1).
 * Ничего не угадывается: нет однозначного ответа — предметы остаются как есть.
 */
object BoardRepair {

    class Result {
        var recs = 0
        var items = 0
        var copies = 0
        var states = 0
        val any get() = recs + items + copies + states > 0
        override fun toString() = "строки $recs, схемы ведомостей $items, копии $copies, дата передачи по истории $states"
    }

    fun run(b: Board): Result {
        val res = Result()
        if (b.tables.isNotEmpty()) tables(b, res)
        if (b.vedoms.isNotEmpty()) vedoms(b, res)
        if (res.any) runCatching { Trail.add("Починка ссылок доски: $res") }
        return res
    }

    // ---------------------------------------------------------------- таблицы

    private fun sameTable(p: BoardTable, q: BoardTable) =
        p.x == q.x && p.y == q.y && p.scale == q.scale && p.title == q.title && p.kind == q.kind

    private fun tables(b: Board, res: Result) {
        val ids = b.tables.mapTo(HashSet()) { it.id }
        val lost = LinkedHashSet<String>()
        for (r in b.recs) if (r.t.isNotEmpty() && r.t !in ids) lost += r.t
        for (z in b.zones) if (z.t.isNotEmpty() && z.t !in ids) lost += z.t
        if (lost.isNotEmpty()) {
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
                for (r in b.recs) if (r.t == old) { r.t = target.id; res.recs++ }
                for (z in b.zones) if (z.t == old) z.t = target.id
                for (t in b.tables) if (t.dock == old) t.dock = target.id
                for (f in b.fills) if (f.lt == old) f.lt = target.id
            }
        }
        // Пустые копии таблицы с содержимым.
        val withRows = b.recs.mapTo(HashSet()) { it.t }
        val copies = b.tables.filter { c ->
            c.id !in withRows && b.zones.none { it.t == c.id } &&
                b.tables.any { o -> o !== c && o.id in withRows && sameTable(o, c) }
        }
        if (copies.isNotEmpty()) { b.tables.removeAll(copies.toSet()); res.copies += copies.size }
    }

    // ---------------------------------------------------------------- ведомости

    private fun sameVedom(p: BoardVedom, q: BoardVedom) =
        p.kind == q.kind && p.num == q.num && p.x == q.x && p.y == q.y && p.scale == q.scale

    private fun vedoms(b: Board, res: Result) {
        val ids = b.vedoms.mapTo(HashSet()) { it.id }
        val lost = b.vitems.filter { it.v !in ids }.groupBy { it.v }
        if (lost.isNotEmpty()) {
            val filled = b.vitems.filter { it.v in ids }.mapTo(HashSet()) { it.v }
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
                    v.id !in filled && v.kind != Vedoms.LIST && recsBy[v.num] != null &&
                        (v.made.isEmpty() || IdTables.isoOf(firstAt[v.num]!!) >= v.made)
                }
                val target = candidates.maxByOrNull { recsBy[it.num]!!.size } ?: continue
                // Схемы этой ведомости — хотя бы половина группы; несколько кандидатов — только копии одной ведомости.
                if (recsBy[target.num]!!.size * 2 < recIds.size) continue
                if (candidates.any { it.num == target.num && !sameVedom(it, target) }) continue
                for (i in items) i.v = target.id
                filled += target.id
                res.items += items.size
            }
        }
        mergeCopies(b, res)
        restoreGiven(b, res)
    }

    /**
     * Копии одной ведомости (тот же вид, номер и место — следы повтора номеров 0.27.5 на разных планшетах) сливаются в одну
     * (0.28.1, Владимир: «снова слетела ведомость на подписи у заказчика от 18.11.26 — теперь там сборка от 17.11.26»).
     * До 0.28.1 оставалась копия со схемами, а пустая убиралась — даже если пустая была переданной, а со схемами — старой,
     * ещё в сборке. Теперь остаётся копия, чьё состояние совпадает с историей схем (переданная — если история о передаче
     * молчит), схемы всех копий переходят к ней. Выбор не зависит от того, к какой копии сейчас привязаны схемы, —
     * на всех планшетах получится одно и то же.
     */
    private fun mergeCopies(b: Board, res: Result) {
        val groups = b.vedoms.groupBy { listOf(it.kind, it.num, it.x, it.y, it.scale) }
        for (copies in groups.values) {
            if (copies.size < 2) continue
            val ids = copies.mapTo(HashSet()) { it.id }
            val items = b.vitems.filter { it.v in ids }
            val hist = historyGiven(b, copies[0], items)
            val keep = copies.sortedWith(compareByDescending<BoardVedom> {
                if (hist != null) it.given == hist else it.given.isNotEmpty()
            }.thenByDescending { it.given }.thenBy { it.id }).first()
            if (keep.made.isEmpty()) copies.map { it.made }.filter { it.isNotEmpty() }.minOrNull()?.let { keep.made = it }
            // Схемы — к оставшейся копии; одна схема дважды — одна позиция (ответ берём, где он есть).
            val byRec = HashMap<String, BoardVItem>()
            for (i in items) if (i.v == keep.id) byRec.putIfAbsent(i.r, i)
            val drop = HashSet<BoardVItem>()
            for (i in items) {
                if (i.v == keep.id) continue
                val same = byRec[i.r]
                if (same == null) { i.v = keep.id; byRec[i.r] = i; res.items++; continue }
                if (same.res.isEmpty() && i.res.isNotEmpty()) { same.res = i.res; same.rd = i.rd }
                drop += i
            }
            if (drop.isNotEmpty()) b.vitems.removeAll(drop)
            val gone = copies.filter { it !== keep }.toSet()
            b.vedoms.removeAll(gone)
            res.copies += gone.size
        }
    }

    /**
     * Ведомость «в сборке», хотя по истории её схемы переданы в ней (заказчику или в ПТО) и обратно в сборку не возвращались,
     * а дата передачи у схем на месте, — вернуть ей дату передачи (её затёрла старая копия ведомости). Статусы и даты схем
     * не меняются: они и так верные.
     */
    private fun restoreGiven(b: Board, res: Result) {
        for (v in b.vedoms) {
            if (!v.isDraft || v.kind == Vedoms.LIST) continue
            val items = b.vitems.filter { it.v == v.id }
            if (items.isEmpty()) continue
            val d = historyGiven(b, v, items) ?: continue
            if (d.isEmpty()) continue
            v.given = d
            res.states++
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
