package party.shulya.freewrite

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

    /** Название части по ключу (ключи частей у всех видов разные). */
    fun partName(key: String): String = ALL.flatMap { it.parts }.firstOrNull { it.key == key }?.name ?: key

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
