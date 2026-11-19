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
 *  - пустые копии ведомости или таблицы (тот же номер или название, то же место — следы той же ошибки на других планшетах) —
 *    убираются, если у оригинала есть содержимое.
 * Ничего не угадывается: нет однозначного ответа — предметы остаются как есть.
 */
object BoardRepair {

    class Result {
        var recs = 0
        var items = 0
        var copies = 0
        val any get() = recs + items + copies > 0
        override fun toString() = "строки $recs, схемы ведомостей $items, пустые копии $copies"
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
        // Пустые копии ведомости с содержимым.
        val withItems = b.vitems.mapTo(HashSet()) { it.v }
        val copies = b.vedoms.filter { c ->
            c.id !in withItems && b.vedoms.any { o -> o !== c && o.id in withItems && sameVedom(o, c) }
        }
        if (copies.isNotEmpty()) { b.vedoms.removeAll(copies.toSet()); res.copies += copies.size }
    }
}
