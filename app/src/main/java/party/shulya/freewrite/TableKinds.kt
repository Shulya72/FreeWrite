package party.shulya.freewrite

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Виды таблиц ИД (0.24, Владимир): кнопка «Таблица ИД» открывает «проводник» — разделы (пока только КЖ) и виды таблиц
 * в них. Вид задаёт, на какие части делится «Объём по схеме» (у вертикальных — Стены и Колонны, у плит — Плита и Балки),
 * виды работ по умолчанию и скрытые столбцы. Таблица без вида — как раньше (один объём).
 */
object TableKinds {
    /** Часть объёма: ключ (в строке — `vp[key]`, столбец — «vol.key») и название. Ключи разные у всех видов. */
    class Part(val key: String, val name: String)

    class Kind(val id: String, val section: String, val name: String, val parts: List<Part>, val works: List<String>,
               /** Скрытые по умолчанию столбцы. */
               val hidden: Set<String>)

    class Section(val code: String, val name: String, val kinds: List<Kind>)

    /** Таблица без вида («Пустая таблица» в проводнике) — один объём, как до 0.24. */
    const val CUSTOM = "custom"

    val KJ_VERT = Kind("kj_vert", "КЖ", "Вертикальные элементы", listOf(Part("w", "Стены"), Part("c", "Колонны")),
        listOf("Опалубка", "Бетон"), setOf(TableCol.CON))
    val KJ_SLAB = Kind("kj_slab", "КЖ", "Плиты перекрытия", listOf(Part("p", "Плита"), Part("b", "Балки")),
        listOf("Опалубка", "Бетон"), setOf(TableCol.CON))

    /** Разделы «проводника» (Владимир: «пока оставь только КЖ, остальное добавим по ходу»). */
    val SECTIONS = listOf(Section("КЖ", "Конструкции железобетонные", listOf(KJ_VERT, KJ_SLAB)))

    val ALL: List<Kind> = SECTIONS.flatMap { it.kinds }

    fun of(id: String): Kind? = ALL.firstOrNull { it.id == id }

    /** Свои виды (0.30.1, «Редактор таблиц»): номер начинается с «u:», устройство вида лежит в самой таблице ([BoardTable.ownKind]). */
    const val USER = "u:"
    fun isUser(id: String) = id.startsWith(USER)
    fun newUserId() = USER + Ids.next().take(8)
    /** Ключ новой части объёма своего вида — разный у всех видов (по нему части не путаются в общих листах Excel и на сайте). */
    fun newPartKey() = "q" + Ids.next().take(6)

    /** Названия частей своих видов, что встречались (таблицы на досках, шаблоны) — для общих листов. */
    private val userParts = HashMap<String, String>()
    fun register(k: Kind) { for (p in k.parts) userParts.putIfAbsent(p.key, p.name) }

    /** Название части по ключу (ключи частей у всех видов разные). */
    fun partName(key: String): String = ALL.flatMap { it.parts }.firstOrNull { it.key == key }?.name ?: userParts[key] ?: key

    /** Все части по порядку: встроенные, потом своих видов этой доски — для общих листов Excel. */
    fun partsOf(b: Board): List<Part> {
        val out = ArrayList(ALL_PARTS)
        val seen = out.mapTo(HashSet()) { it.key }
        for (t in b.tables) if (isUser(t.kind)) t.ownKind?.parts?.forEach { if (seen.add(it.key)) out += it }
        return out
    }

    /** Свой вид в JSON таблицы и шаблона: {"n": название, "s": раздел, "p": [[ключ, часть]…], "w": [виды работ]}. */
    fun kindJson(sb: StringBuilder, k: Kind) {
        sb.append("{\"n\":").append(JSONObject.quote(k.name)).append(",\"s\":").append(JSONObject.quote(k.section))
        sb.append(",\"p\":[")
        for ((i, p) in k.parts.withIndex()) { if (i > 0) sb.append(','); sb.append('[').append(JSONObject.quote(p.key)).append(',').append(JSONObject.quote(p.name)).append(']') }
        sb.append("],\"w\":[")
        for ((i, w) in k.works.withIndex()) { if (i > 0) sb.append(','); sb.append(JSONObject.quote(w)) }
        sb.append("]}")
    }

    fun parseKind(id: String, j: JSONObject): Kind {
        val parts = ArrayList<Part>()
        j.optJSONArray("p")?.let { a -> for (i in 0 until a.length()) a.optJSONArray(i)?.let { p -> if (p.optString(0).isNotEmpty()) parts += Part(p.optString(0), p.optString(1)) } }
        val works = ArrayList<String>()
        j.optJSONArray("w")?.let { a -> for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotBlank() }?.let { works += it } }
        return Kind(id, j.optString("s"), j.optString("n").ifBlank { "Своя таблица" }, parts, works, emptySet()).also { register(it) }
    }

    /** Все части по порядку видов — для общих листов Excel и сайта. */
    val ALL_PARTS: List<Part> = ALL.flatMap { it.parts }

    /** Столбец части объёма. */
    fun col(part: String) = TableCol.VOL + "." + part

    fun partOf(k: String): String? = if (k.startsWith(TableCol.VOL + ".")) k.substring(TableCol.VOL.length + 1) else null

    /**
     * Таблицы до 0.24 (вид не записан) — «Вертикальные элементы» (Владимир: «старые таблицы переведи в стены, объёмы
     * обнули, я протыкаю их снова»). Данные не переписываются: старый «Объём по схеме» у таких таблиц просто не читается
     * (части — свои поля), поэтому планшет коллеги со старой копией доски не затрёт новые объёмы.
     */
    val LEGACY = KJ_VERT
}

/**
 * Шаблон своей таблицы (0.30.1, «Редактор таблиц», Владимир: «заходил бы в редактор и составлял свою таблицу: обратная засыпка,
 * сети — разработка, песчаное основание, труба…»): название, раздел проводника, столбцы по порядку (встроенные — показать,
 * скрыть, переименовать; свои — с видом), виды работ, части объёма. Ставится на доску копией: таблица несёт вид с собой.
 */
class TableTemplate(
    val id: String,
    var name: String,
    var section: String,
    /** Столбцы по порядку: все встроенные (скрытые — со [TableCol.hidden]), «Объём» ([TableCol.VOL] — на его месте части), свои. */
    val cols: MutableList<TableCol>,
    val works: MutableList<String>,
    val parts: MutableList<TableKinds.Part>,
) {
    fun toKind() = TableKinds.Kind(id, section, name, parts.toList(), works.toList(), emptySet())

    /** Столбцы для новой таблицы (копия). */
    fun tableCols(): List<TableCol> = cols.map { it.copy() }

    fun copy(newId: String = id) = TableTemplate(newId, name, section, cols.mapTo(ArrayList()) { it.copy() }, ArrayList(works),
        parts.mapTo(ArrayList()) { TableKinds.Part(it.key, it.name) })

    fun json(): String {
        val sb = StringBuilder(400)
        sb.append("{\"id\":").append(JSONObject.quote(id)).append(",\"kd\":")
        TableKinds.kindJson(sb, toKind())
        sb.append(",\"cols\":[")
        for ((i, c) in cols.withIndex()) { if (i > 0) sb.append(','); IdTables.colJson(sb, c) }
        sb.append("]}")
        return sb.toString()
    }

    companion object {
        /** Столбцы по умолчанию: обычный порядок, «Объём» одним столбцом (части встают на его место). */
        fun defaultCols(hidden: Set<String> = emptySet()): MutableList<TableCol> =
            TableCol.ORDER.mapTo(ArrayList()) { TableCol(it, hidden = it in hidden) }

        fun parse(j: JSONObject): TableTemplate? {
            val id = j.optString("id").takeIf { TableKinds.isUser(it) } ?: return null
            val k = TableKinds.parseKind(id, j.optJSONObject("kd") ?: JSONObject())
            val cols = ArrayList<TableCol>()
            val seen = HashSet<String>()
            j.optJSONArray("cols")?.let { a ->
                for (i in 0 until a.length()) a.optJSONObject(i)?.let { c ->
                    val key = c.optString("k")
                    if (key.isNotEmpty() && seen.add(key) && (key in TableCol.ORDER || TableCol.isCustomKey(key))) cols += IdTables.parseCol(c, key)
                }
            }
            // Встроенный столбец, которого нет (шаблон из прошлой версии), — в конец, скрытым.
            for (key in TableCol.ORDER) if (key !in seen) cols += TableCol(key, hidden = true)
            return TableTemplate(id, k.name, k.section, cols, k.works.toMutableList(), k.parts.toMutableList())
        }

        /** Шаблон с готового вида (встроенного или своего) — «На основе». Части и их ключи — те же. */
        fun fromKind(k: TableKinds.Kind, newId: String = TableKinds.newUserId()): TableTemplate =
            TableTemplate(newId, k.name, k.section, defaultCols(k.hidden), k.works.toMutableList(), k.parts.toMutableList())

        /**
         * Шаблон с таблицы на доске («Сохранить как шаблон»): её столбцы (части объёма — снова одним «Объёмом»), вид работ —
         * из вида и строк, части — как у таблицы (ключи те же — данные строк читаются как раньше).
         */
        fun fromTable(b: Board, t: BoardTable, newId: String = if (TableKinds.isUser(t.kind)) t.kind else TableKinds.newUserId()): TableTemplate {
            val k = t.kindDef()
            val cols = ArrayList<TableCol>()
            var volAt = -1
            for (c in t.allCols()) {
                if (c.part != null) { if (volAt < 0) { volAt = cols.size; cols += TableCol(TableCol.VOL, hidden = c.hidden) }; continue }
                if (cols.none { it.k == c.k }) cols += c.copy().apply { if (!custom && name == TableCol.defaultName(this.k)) name = "" }
            }
            for (key in TableCol.ORDER) if (cols.none { it.k == key }) cols += TableCol(key, hidden = true)
            val works = LinkedHashSet<String>()
            k?.works?.let { works += it }
            for (r in IdTables.allRows(b, t)) if (r.work.isNotBlank() && works.none { IdTables.norm(it) == IdTables.norm(r.work) }) works += r.work.trim()
            val name = if (k != null && t.kind != TableKinds.CUSTOM) k.name else t.title.ifBlank { "Своя таблица" }
            return TableTemplate(newId, name, k?.section?.takeIf { TableKinds.isUser(t.kind) } ?: "Мои таблицы", cols, works.toMutableList(),
                t.parts().toMutableList())
        }
    }
}

/** Библиотека своих шаблонов на этом планшете: файл `table_templates.json`. */
class TableTemplates(ctx: Context) {
    private val file = File(ctx.filesDir, "table_templates.json")

    fun list(): List<TableTemplate> = runCatching {
        val a = JSONArray(file.readText())
        (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let { o -> TableTemplate.parse(o) } }
    }.getOrDefault(emptyList())

    fun get(id: String): TableTemplate? = list().firstOrNull { it.id == id }

    fun save(t: TableTemplate) {
        val all = list().filter { it.id != t.id } + t
        write(all)
        TableKinds.register(t.toKind())
    }

    fun delete(id: String) = write(list().filter { it.id != id })

    private fun write(all: List<TableTemplate>) {
        val text = all.joinToString(",", "[", "]") { it.json() }
        val tmp = File(file.path + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) { file.writeText(text); tmp.delete() }
    }

    companion object {
        /** Свои виды таблиц на доске, которых нет в библиотеке (поставил коллега) — «На этой доске» в проводнике. */
        fun onBoard(b: Board, library: List<TableTemplate>): List<TableTemplate> {
            val have = library.mapTo(HashSet()) { it.id }
            val out = LinkedHashMap<String, TableTemplate>()
            for (t in b.tables) if (TableKinds.isUser(t.kind) && t.kind !in have && t.kind !in out && t.ownKind != null) out[t.kind] = TableTemplate.fromTable(b, t, t.kind)
            return out.values.toList()
        }
    }
}

