package party.shulya.freewrite

import android.graphics.Color
import org.json.JSONObject

/**
 * Лист плана внутри таблицы ИД (0.23, Владимир: «сверху захватки, а снизу план-схема, на которой отображаются захватки»).
 *
 * Картинка — файл в общей папке картинок (как у картинок доски: уровни 256/1024/2048, обмен с коллегами, сервер).
 * Обрезка [cl]…[cb] — видимая часть в долях картинки целиком (при вставке отрезают рамку листа, штамп, разрезы).
 * Место и размер на доске не хранятся: лист стоит под строками таблицы по её ширине и двигается вместе с ней.
 */
class PlanSheet(val file: String, val pxW: Int, val pxH: Int) {
    var id: String = Ids.next()
    /** Название вкладки («План на отм. +3.300»); пусто — «Лист N». */
    var name = ""
    var cl = 0f
    var ct = 0f
    var cr = 1f
    var cb = 1f
    /** PDF, из которого лист (0.26): файл в общей папке картинок и номер листа — для поиска элементов по линиям. */
    var pdf = ""
    var page = 0
    /** Швы (ножницы и швы-зигзаги чертежа): отрезки x0, y0, x1, y1 в долях картинки — рисуются зигзагом. */
    val seams = ArrayList<FloatArray>()

    val cropped get() = cl > 0f || ct > 0f || cr < 1f || cb < 1f

    /** Высота видимой части к ширине. */
    fun aspect(): Float {
        val w = pxW * (cr - cl); val h = pxH * (cb - ct)
        return if (w > 0f && h > 0f) h / w else 0.7f
    }

    fun setCrop(l: Float, t: Float, r: Float, b: Float) {
        cl = l.coerceIn(0f, 1f - BoardImage.MIN_CROP); cr = r.coerceIn(cl + BoardImage.MIN_CROP, 1f)
        ct = t.coerceIn(0f, 1f - BoardImage.MIN_CROP); cb = b.coerceIn(ct + BoardImage.MIN_CROP, 1f)
    }

    fun copy(): PlanSheet = PlanSheet(file, pxW, pxH).also {
        it.id = id; it.name = name; it.cl = cl; it.ct = ct; it.cr = cr; it.cb = cb
        it.pdf = pdf; it.page = page; for (s in seams) it.seams += s.copyOf()
    }

    /** Высота картинки к ширине (для площадей в долях). */
    fun ratio(): Float = if (pxW > 0 && pxH > 0) pxH.toFloat() / pxW else 0.7f
}

/**
 * Область захватки на листе плана (0.23): таблица [t], лист [s], участок [uch] и захватка [zah] (как в строках таблицы),
 * контур [pts] — пары x, y в долях картинки целиком (обрезка листа область не сдвигает), свой цвет [color].
 * Цвет на плане — по ИГС (самая отстающая схема захватки) или свой: переключатель у таблицы.
 */
class BoardZone(var t: String, var s: String, var uch: String, var zah: String, val pts: FloatArray, var color: Int) {
    var id: String = Ids.next()
    /**
     * Элемент разметки (0.26): стена, пилон, кусок плиты — найден по PDF или размечен вручную. Без захватки ([zah] пусто) —
     * серый «ещё не залито»; отмечен в захватке — её цветом. Процент готовности — по площади элементов.
     */
    var e = false
    /** Дыры (отверстия плиты) — кольца x, y в долях картинки. */
    var holes: List<FloatArray> = emptyList()

    fun zkey(): String = IdTables.zkey(uch, zah)
    fun label(): String = IdTables.zahLabel(uch, zah)
    fun copy(): BoardZone = BoardZone(t, s, uch, zah, pts.copyOf(), color).also { it.id = id; it.e = e; it.holes = holes.map { h -> h.copyOf() } }

    /** Элемент разметки без захватки. */
    val free get() = e && zah.isEmpty() && uch.isEmpty()

    /** Площадь в долях картинки (y — в масштабе [ratio] высоты к ширине). */
    fun area(ratio: Float): Float = PlanElements.area(pts, holes, ratio)

    /** Рамка контура в долях картинки: [l, t, r, b]. */
    fun box(): FloatArray {
        var l = 1f; var tp = 1f; var r = 0f; var b = 0f
        var i = 0
        while (i + 1 < pts.size) { l = minOf(l, pts[i]); r = maxOf(r, pts[i]); tp = minOf(tp, pts[i + 1]); b = maxOf(b, pts[i + 1]); i += 2 }
        return floatArrayOf(l, tp, r, b)
    }

    /** Точка (доли картинки) внутри контура. */
    fun contains(x: Float, y: Float): Boolean = inRing(pts, x, y) && holes.none { inRing(it, x, y) }

    private fun inRing(p: FloatArray, x: Float, y: Float): Boolean {
        var inside = false
        val n = p.size / 2
        var j = n - 1
        for (i in 0 until n) {
            val xi = p[2 * i]; val yi = p[2 * i + 1]; val xj = p[2 * j]; val yj = p[2 * j + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    /** Расстояние от точки до края области (доли картинки, y — в масштабе [ratio]); внутри — 0. */
    fun distance(x: Float, y: Float, ratio: Float): Float {
        if (contains(x, y)) return 0f
        var best = Float.MAX_VALUE
        for (r in listOf(pts) + holes) {
            val n = r.size / 2
            for (i in 0 until n) {
                val j = (i + 1) % n
                val ax = r[2 * i]; val ay = r[2 * i + 1] * ratio; val bx = r[2 * j]; val by = r[2 * j + 1] * ratio
                val dx = bx - ax; val dy = by - ay; val l2 = dx * dx + dy * dy
                val py = y * ratio
                val t = if (l2 == 0f) 0f else (((x - ax) * dx + (py - ay) * dy) / l2).coerceIn(0f, 1f)
                best = minOf(best, kotlin.math.hypot(x - ax - t * dx, py - ay - t * dy))
            }
        }
        return best
    }
}

object TablePlan {
    /** Цвет областей: "" — по ИГС, [OWN] — свой у каждой захватки. */
    const val OWN = "own"

    /** Свои цвета захваток по порядку (как раскрашивал Владимир: разные, светлые под заливку). */
    val PALETTE = intArrayOf(
        Color.rgb(41, 121, 255), Color.rgb(46, 160, 67), Color.rgb(255, 152, 0), Color.rgb(156, 39, 176),
        Color.rgb(0, 172, 193), Color.rgb(229, 57, 53), Color.rgb(124, 179, 66), Color.rgb(121, 85, 72),
        Color.rgb(233, 30, 99), Color.rgb(63, 81, 181), Color.rgb(255, 193, 7), Color.rgb(0, 150, 136),
    )

    // ---------------------------------------------------------------- JSON

    fun sheetsJson(sb: StringBuilder, t: BoardTable) {
        if (t.sheets.isEmpty()) return
        sb.append(",\"sheets\":[")
        for ((i, s) in t.sheets.withIndex()) {
            if (i > 0) sb.append(',')
            sb.append("{\"id\":").append(JSONObject.quote(s.id)).append(",\"f\":").append(JSONObject.quote(s.file))
            sb.append(",\"pw\":").append(s.pxW).append(",\"ph\":").append(s.pxH)
            if (s.name.isNotEmpty()) sb.append(",\"n\":").append(JSONObject.quote(s.name))
            if (s.cropped) sb.append(",\"crop\":[").append(frac(s.cl)).append(',').append(frac(s.ct)).append(',').append(frac(s.cr)).append(',').append(frac(s.cb)).append(']')
            if (s.pdf.isNotEmpty()) { sb.append(",\"pdf\":").append(JSONObject.quote(s.pdf)); if (s.page > 0) sb.append(",\"pg\":").append(s.page) }
            if (s.seams.isNotEmpty()) { sb.append(",\"seams\":"); rings(sb, s.seams) }
            sb.append('}')
        }
        sb.append(']')
        if (t.zoneColor.isNotEmpty()) sb.append(",\"zc\":").append(JSONObject.quote(t.zoneColor))
    }

    fun parseSheets(j: JSONObject, t: BoardTable) {
        j.optJSONArray("sheets")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val f = o.optString("f")
                if (f.isEmpty() || !BoardShare.isSafeImageName(f)) continue
                val s = PlanSheet(f, o.optInt("pw"), o.optInt("ph"))
                s.id = o.optString("id").ifEmpty { "S" + f.hashCode().toUInt().toString(16) }
                s.name = o.optString("n")
                o.optJSONArray("crop")?.let { c ->
                    if (c.length() == 4) s.setCrop(c.optDouble(0, 0.0).toFloat(), c.optDouble(1, 0.0).toFloat(), c.optDouble(2, 1.0).toFloat(), c.optDouble(3, 1.0).toFloat())
                }
                o.optString("pdf").takeIf { it.isNotEmpty() && BoardShare.isSafeImageName(it) }?.let { s.pdf = it; s.page = o.optInt("pg").coerceAtLeast(0) }
                s.seams += parseRings(o.optJSONArray("seams"), 4)
                t.sheets += s
            }
        }
        t.zoneColor = if (j.optString("zc") == OWN) OWN else ""
    }

    fun zoneJson(z: BoardZone): String {
        val sb = StringBuilder(64 + z.pts.size * 8)
        sb.append("{\"id\":").append(JSONObject.quote(z.id)).append(",\"t\":").append(JSONObject.quote(z.t)).append(",\"s\":").append(JSONObject.quote(z.s))
        if (z.uch.isNotEmpty()) sb.append(",\"u\":").append(JSONObject.quote(z.uch))
        sb.append(",\"z\":").append(JSONObject.quote(z.zah)).append(",\"color\":").append(z.color).append(",\"pts\":[")
        for ((i, v) in z.pts.withIndex()) { if (i > 0) sb.append(','); sb.append(frac(v)) }
        sb.append(']')
        if (z.e) sb.append(",\"e\":1")
        if (z.holes.isNotEmpty()) { sb.append(",\"h\":"); rings(sb, z.holes) }
        sb.append('}')
        return sb.toString()
    }

    private fun rings(sb: StringBuilder, list: List<FloatArray>) {
        sb.append('[')
        for ((k, r) in list.withIndex()) {
            if (k > 0) sb.append(',')
            sb.append('[')
            for ((i, v) in r.withIndex()) { if (i > 0) sb.append(','); sb.append(frac(v)) }
            sb.append(']')
        }
        sb.append(']')
    }

    private fun parseRings(a: org.json.JSONArray?, min: Int): List<FloatArray> {
        if (a == null) return emptyList()
        val out = ArrayList<FloatArray>()
        for (k in 0 until a.length()) {
            val r = a.optJSONArray(k) ?: continue
            val n = r.length() / 2 * 2
            if (n < min) continue
            out += FloatArray(n) { r.optDouble(it, 0.0).toFloat().coerceIn(-0.5f, 1.5f) }
        }
        return out
    }

    fun parseZone(j: JSONObject): BoardZone? {
        val a = j.optJSONArray("pts") ?: return null
        val n = a.length() / 2 * 2
        if (n < 6) return null
        val p = FloatArray(n) { a.optDouble(it, 0.0).toFloat().coerceIn(-0.5f, 1.5f) }
        return BoardZone(j.optString("t"), j.optString("s"), j.optString("u"), j.optString("z"), p, j.optInt("color", PALETTE[0])).also {
            it.id = j.optString("id", "")
            it.e = j.optInt("e") == 1
            it.holes = parseRings(j.optJSONArray("h"), 6)
        }
    }

    private fun frac(v: Float): String {
        val s = String.format(java.util.Locale.US, "%.5f", v).trimEnd('0').trimEnd('.')
        return if (s == "-0") "0" else s
    }

    // ---------------------------------------------------------------- поиск

    fun sheet(t: BoardTable, id: String): PlanSheet? = t.sheets.firstOrNull { it.id == id }

    /** Лист, открытый во вкладке на этом планшете. */
    fun current(t: BoardTable): PlanSheet? = t.sheets.getOrNull(t.tab.coerceIn(0, maxOf(0, t.sheets.size - 1)))

    fun zonesOf(b: Board, t: BoardTable, sheetId: String? = null): List<BoardZone> =
        b.zones.filter { it.t == t.id && (sheetId == null || it.s == sheetId) }

    fun zonesOf(b: Board, t: BoardTable, uch: String, zah: String): List<BoardZone> {
        val k = IdTables.zkey(uch, zah)
        return b.zones.filter { it.t == t.id && it.zkey() == k && sheet(t, it.s) != null }
    }

    /**
     * Самый отстающий статус ИГС захватки (по порядку списка ИГС доски): зелёная — только когда все её схемы сданы.
     * null — строк у захватки нет.
     */
    fun zahStatus(b: Board, t: BoardTable, uch: String, zah: String): BoardStatus? {
        val rows = IdTables.zahRows(b, t, uch, zah)
        if (rows.isEmpty()) return null
        val list = b.statuses(BoardStatus.ID)
        var best: BoardStatus? = null
        var bi = Int.MAX_VALUE
        for (r in rows) {
            val i = list.indexOfFirst { it.id == r.igs }.let { if (it < 0) 0 else it }
            if (i < bi) { bi = i; best = list.getOrNull(i) ?: IdTables.status(b, BoardStatus.ID, r.igs) }
        }
        return best
    }

    /** Цвет области на плане: по ИГС или свой; захватка без строк — серый. */
    fun zoneColor(b: Board, t: BoardTable, z: BoardZone): Int = when {
        z.free -> FREE
        t.zoneColor == OWN -> z.color
        else -> zahStatus(b, t, z.uch, z.zah)?.color ?: NO_ROWS
    }

    val NO_ROWS = Color.rgb(160, 168, 182)

    /** Элемент без захватки — «ещё не залито» (бледно, не бросается в глаза). */
    val FREE = Color.rgb(150, 152, 162)

    /** Свой цвет новой захватки: как у её уже обведённых областей, иначе следующий по порядку захваток таблицы. */
    fun ownColorFor(b: Board, t: BoardTable, uch: String, zah: String): Int {
        zonesOf(b, t, uch, zah).firstOrNull { !it.free }?.let { return it.color }
        val k = IdTables.zkey(uch, zah)
        val i = IdTables.zahList(b, t).indexOfFirst { IdTables.zkey(it.first, it.second) == k }
        val used = b.zones.filter { it.t == t.id && !it.free }.map { it.color }.toSet()
        if (i >= 0) { val c = autoColor(i); if (c !in used) return c }
        var n = 0
        while (n < 400) { val c = autoColor(n); if (c !in used) return c; n++ }
        return autoColor(used.size)
    }

    /**
     * Цвет захватки по номеру (0.26, Владимир: «20–30 захваток — все разного цвета»): сначала 12 привычных, дальше —
     * оттенки через «золотой угол» (соседние номера далеко по кругу), светлее/темнее по очереди.
     */
    fun autoColor(i: Int): Int {
        if (i in PALETTE.indices) return PALETTE[i]
        val k = i - PALETTE.size
        val hue = ((k * 137.508f) + 17f) % 360f
        val sat = if (k % 3 == 2) 0.5f else 0.72f
        val v = if (k % 2 == 0) 0.88f else 0.62f
        return Color.HSVToColor(floatArrayOf(hue, sat, v))
    }

    // ---------------------------------------------------------------- элементы разметки (0.26)

    /** Элементы разметки таблицы (на её листах). */
    fun elements(b: Board, t: BoardTable, sheetId: String? = null): List<BoardZone> =
        b.zones.filter { it.e && it.t == t.id && (sheetId == null || it.s == sheetId) && sheet(t, it.s) != null }

    /**
     * Готовность конструкции, примерно (Владимир: «площадь не нужна, только примерный процент»): площадь элементов в
     * захватках к площади всех элементов разметки. null — разметки нет.
     */
    fun percent(b: Board, t: BoardTable): Int? {
        var all = 0.0; var done = 0.0
        for (z in elements(b, t)) {
            val sh = sheet(t, z.s) ?: continue
            val a = z.area(sh.ratio()).toDouble() * sh.pxW * sh.pxW
            all += a
            if (!z.free) done += a
        }
        if (all <= 0.0) return null
        val p = done / all * 100.0
        return when {
            p > 0.0 && p < 1.0 -> 1
            p < 100.0 && p > 99.0 -> 99
            else -> Math.round(p).toInt()
        }
    }

    /** Подпись области для панели: «Захватка 3 · опалубка — На подписи у заказчика, бетон — Сборка». */
    fun zoneInfo(b: Board, t: BoardTable, uch: String, zah: String): String {
        val rows = IdTables.zahRows(b, t, uch, zah)
        val head = "Захватка ${IdTables.zahLabel(uch, zah)}"
        if (rows.isEmpty()) return "$head · строк в таблице нет"
        return head + " · " + rows.joinToString { r -> r.work.ifBlank { "схема" }.lowercase() + " — " + IdTables.status(b, BoardStatus.ID, r.igs).name }
    }

    /** Сколько захваток (с областями на плане) в каждом статусе — для легенды под планом. */
    fun legend(b: Board, t: BoardTable): List<Pair<BoardStatus?, Int>> {
        val seen = HashSet<String>()
        val counts = LinkedHashMap<String, Int>()
        var none = 0
        for (z in zonesOf(b, t)) {
            if (z.free || sheet(t, z.s) == null || !seen.add(z.zkey())) continue
            val st = zahStatus(b, t, z.uch, z.zah)
            if (st == null) none++ else counts[st.id] = (counts[st.id] ?: 0) + 1
        }
        val out = ArrayList<Pair<BoardStatus?, Int>>()
        for (s in b.statuses(BoardStatus.ID).reversed()) counts[s.id]?.let { out += s to it }
        if (none > 0) out += null to none
        return out
    }

    /** Захватки таблицы без областей на плане (для «Обвести захватку»: ✓ — уже обведена). */
    fun outlined(b: Board, t: BoardTable): Set<String> = zonesOf(b, t).filter { !it.free && sheet(t, it.s) != null }.map { it.zkey() }.toSet()

    /** Цвет захватки (участок + захватка → цвет): заливки на доске и области на плане (область главнее). */
    fun zahColorsAll(b: Board, t: BoardTable): Map<String, Int> {
        val m = HashMap(IdTables.zahColors(b, t))
        for (z in b.zones) if (z.t == t.id && !z.free && sheet(t, z.s) != null) m[z.zkey()] = zoneColor(b, t, z)
        return m
    }

    /** Файлы картинок листов (для обмена и сервера). */
    fun files(b: Board): List<String> = b.tables.flatMap { t -> t.sheets.flatMap { listOfNotNull(it.file, it.pdf.ifEmpty { null }) } }
}
