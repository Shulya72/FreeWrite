package party.shulya.freewrite

import android.content.Context
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import android.graphics.RectF
import kotlin.math.max

/**
 * Текст на доске: x — левый край, y — линия букв (baseline) первой строки, size — размер шрифта
 * в единицах доски. Может быть в несколько строк (перевод строки в text); шаг строк — [LINE] × size.
 */
class BoardText(var x: Float, var y: Float, var size: Float, var text: String, var color: Int) {
    /** Номер предмета: по нему сливаются правки с доской коллеги (у каждого предмета доски свой). */
    var id: String = Ids.next()

    // Ширина и число строк считаются один раз (пока не поменялись текст и размер) — для быстрой доски.
    internal var wText: String? = null
    internal var wSize = 0f
    internal var w = 0f
    internal var nLines = 1

    companion object {
        const val LINE = 1.25f
    }
}

/** Картинка на доске (файл — в общей папке картинок). */
class BoardImage(val file: String, var x: Float, var y: Float, var w: Float, var h: Float, val pxW: Int, val pxH: Int) {
    var id: String = Ids.next()

    /**
     * Обрезка (0.13): видимая часть картинки — доли (0…1) от левого, верхнего, правого и нижнего края.
     * x, y, w, h — место видимой части на доске; скрытое не теряется, его можно вернуть.
     */
    var cl = 0f
    var ct = 0f
    var cr = 1f
    var cb = 1f

    /** Закреплена булавкой: не выделяется, не двигается, не удаляется — только рисовать и писать поверх. */
    var locked = false

    val cropped get() = cl > 0f || ct > 0f || cr < 1f || cb < 1f

    /** Где на доске была бы картинка целиком (без обрезки). */
    fun fullW() = w / (cr - cl)
    fun fullH() = h / (cb - ct)
    fun fullX() = x - cl * fullW()
    fun fullY() = y - ct * fullH()

    /** Видимая часть — рамка [l, t, r, b] на доске (в пределах картинки целиком). */
    fun setVisible(l: Float, t: Float, r: Float, b: Float) {
        val fw = fullW(); val fh = fullH(); val fx = fullX(); val fy = fullY()
        cl = ((l - fx) / fw).coerceIn(0f, 1f - MIN_CROP); cr = ((r - fx) / fw).coerceIn(cl + MIN_CROP, 1f)
        ct = ((t - fy) / fh).coerceIn(0f, 1f - MIN_CROP); cb = ((b - fy) / fh).coerceIn(ct + MIN_CROP, 1f)
        x = fx + cl * fw; y = fy + ct * fh; w = (cr - cl) * fw; h = (cb - ct) * fh
    }

    /** Убрать обрезку: картинка снова целиком, на прежнем месте. */
    fun uncrop() {
        val fw = fullW(); val fh = fullH(); val fx = fullX(); val fy = fullY()
        cl = 0f; ct = 0f; cr = 1f; cb = 1f
        x = fx; y = fy; w = fw; h = fh
    }

    companion object {
        /** Меньше 2 % стороны не обрезаем. */
        const val MIN_CROP = 0.02f
    }
}

/**
 * Статус из списка доски (его настраивает Владимир: «Сдали опалубку», «Забетонировано»…).
 * Порядок в списке — порядок переключения касанием.
 */
class BoardStatus(val id: String, var name: String, var color: Int)

/** Метка статуса. sid — id статуса из списка доски. scale — размер (метка ставится «в рост» текущего масштаба). */
class BoardSticker(var x: Float, var y: Float, var sid: String, var scale: Float) {
    var id: String = Ids.next()

    val h: Float get() = 36f * scale

    /** Ширина под подпись: не меньше 128, длинное название — шире. */
    fun width(label: String): Float = max(128f, label.length * 9.4f + 40f) * scale
}

/**
 * Заливка — залитая область (захватка, закрашенная стена): контур (x, y подряд), цвет, прозрачность.
 * Рисуется над картинками и под надписями — чертёж под заливкой виден.
 */
class BoardFill(val pts: FloatArray, var color: Int, var alpha: Float = DEFAULT_ALPHA) {
    var id: String = Ids.next()

    fun copy() = BoardFill(pts.copyOf(), color, alpha)

    /** Номер изменения точек: кто двигает точки — вызывает [changed]. */
    var version = 0
        private set
    private var bbVersion = -1
    private val bb = RectF()

    /** Готовый контур (строит доска); null — построить заново. */
    var path: android.graphics.Path? = null

    fun changed() {
        version++
        path = null
    }

    fun bounds(): RectF {
        if (bbVersion != version) {
            var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
            var i = 0
            while (i + 1 < pts.size) {
                val x = pts[i]; val y = pts[i + 1]
                if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y
                i += 2
            }
            if (l <= r) bb.set(l, t, r, b) else bb.setEmpty()
            bbVersion = version
        }
        return bb
    }

    private var jsonSum = 0L
    private var jsonCache: String? = null

    /** Запись в JSON; готовая строка переиспользуется, пока заливка не менялась (по контрольной сумме). */
    fun json(): String {
        var h = 1125899906842597L
        h = 31 * h + color
        h = 31 * h + alpha.toRawBits()
        h = 31 * h + id.hashCode()
        for (v in pts) h = 31 * h + v.toRawBits()
        jsonCache?.let { if (h == jsonSum) return it }
        val sb = StringBuilder(pts.size * 6 + 64)
        sb.append("{\"id\":").append(JSONObject.quote(id)).append(",\"color\":").append(color).append(",\"alpha\":").append(alpha.toDouble()).append(",\"pts\":")
        JsonNum.array(sb, pts)
        sb.append('}')
        return sb.toString().also { jsonCache = it; jsonSum = h }
    }

    companion object {
        const val DEFAULT_ALPHA = 0.35f
    }
}

/**
 * Мультивыноска: рамка из двух строк — надпись («Захватка №1 от 11.09.26») и статус (кнопка цвета
 * статуса; касание — следующий статус) — и указатели к местам на чертеже.
 * x, y — левый верхний угол рамки; scale — размер (ставится «в рост» масштаба); leaders — концы
 * указателей (x, y подряд).
 */
class BoardCallout(var x: Float, var y: Float, var scale: Float, var title: String, var sid: String) {
    var id: String = Ids.next()
    val leaders = ArrayList<Float>()

    // Ширина рамки считается один раз (пока не поменялись надпись, статус и размер).
    internal var wKey: String? = null
    internal var w = 0f

    val leaderCount: Int get() = leaders.size / 2

    fun addLeader(px: Float, py: Float) { leaders += px; leaders += py }

    fun copy(): BoardCallout = BoardCallout(x, y, scale, title, sid).also { it.leaders.addAll(leaders) }
}

/** Доска: бесконечное поле. Координаты — «единицы доски»; камера — что видно на экране. */
class Board(
    val id: String,
    var title: String,
    val created: Long,
    var updated: Long,
) {
    val strokes = ArrayList<Drawing>()
    val texts = ArrayList<BoardText>()
    val images = ArrayList<BoardImage>()
    val stickers = ArrayList<BoardSticker>()
    val fills = ArrayList<BoardFill>()
    val callouts = ArrayList<BoardCallout>()
    val statuses = ArrayList<BoardStatus>()
    var camX = 0f
    var camY = 0f
    var camZ = 1f

    /** Учёт правок для обмена с коллегами: когда менялся каждый предмет, что удалено. */
    var sync = BoardSync()

    /** Статус по id (если такого уже нет — первый в списке). */
    fun status(id: String): BoardStatus {
        if (statuses.isEmpty()) statuses.addAll(defaultStatuses())
        return statuses.firstOrNull { it.id == id } ?: statuses[0]
    }

    /** Следующий статус по порядку списка (после последнего — снова первый). */
    fun nextStatus(id: String): String {
        if (statuses.isEmpty()) statuses.addAll(defaultStatuses())
        val i = statuses.indexOfFirst { it.id == id }
        return statuses[(i + 1).mod(statuses.size)].id
    }

    /** Сколько меток и выносок с этим статусом. */
    fun countStatus(id: String) = stickers.count { it.sid == id } + callouts.count { it.sid == id }

    val isEmpty: Boolean
        get() = strokes.isEmpty() && texts.isEmpty() && images.isEmpty() && stickers.isEmpty() &&
            fills.isEmpty() && callouts.isEmpty()

    /**
     * Доска в JSON одной строкой. Тяжёлые части (рисунки, заливки) берутся готовыми строками —
     * поэтому сохранение и снимок для «Отменить» быстрые даже на большой доске.
     */
    fun toJsonString(withCamera: Boolean = true, stampDevice: String? = null, now: Long = System.currentTimeMillis()): String {
        // stampDevice — заодно отметить правки ([stamp]) тем же проходом: строки предметов уже готовы.
        val st = stampDevice?.let { Stamper(it, now) }
        if (st != null) {
            st.check(TITLE_ID, fingerprint(title))
            st.check(ORDER_ID, fingerprint(statuses.joinToString(",") { it.id }))
            for (s in statuses) st.check(s.id, fingerprint(s.name + "\u0001" + s.color))
        }
        val sb = StringBuilder(64 * 1024)
        sb.append("{\"v\":2,\"kind\":\"board\",\"id\":").append(JSONObject.quote(id))
        sb.append(",\"title\":").append(JSONObject.quote(title))
        sb.append(",\"created\":").append(created).append(",\"updated\":").append(updated)
        if (withCamera) {
            sb.append(",\"camX\":").append(camX.toDouble()).append(",\"camY\":").append(camY.toDouble())
            sb.append(",\"camZ\":").append(camZ.toDouble())
        }
        sb.append(",\"statuses\":").append(statusesJson())
        fun <T> list(name: String, items: List<T>, id: (T) -> String, f: (T) -> String) {
            sb.append(",\"").append(name).append("\":[")
            for ((i, it) in items.withIndex()) {
                if (i > 0) sb.append(',')
                val j = f(it)
                st?.check(id(it), fingerprint(j))
                sb.append(j)
            }
            sb.append(']')
        }
        list("strokes", strokes, { it.id }) { it.json() }
        list("texts", texts, { it.id }) { textJson(it) }
        list("images", images, { it.id }) { imageJson(it) }
        list("stickers", stickers, { it.id }) { stickerJson(it) }
        list("fills", fills, { it.id }) { it.json() }
        list("callouts", callouts, { it.id }) { calloutJson(it) }
        st?.finish()
        sb.append(",\"sync\":")
        sync.json(sb)
        sb.append('}')
        return sb.toString()
    }

    /**
     * Все предметы доски одной строкой каждый: номер → «отпечаток» содержимого (меняется при любой
     * правке). Сюда же входят статусы, их порядок («~order») и название доски («~title»).
     */
    fun forEachEntry(f: (String, Long) -> Unit) {
        f(TITLE_ID, fingerprint(title))
        f(ORDER_ID, fingerprint(statuses.joinToString(",") { it.id }))
        for (st in statuses) f(st.id, fingerprint(st.name + "\u0001" + st.color))
        for (d in strokes) f(d.id, fingerprint(d.json()))
        for (t in texts) f(t.id, fingerprint(textJson(t)))
        for (m in images) f(m.id, fingerprint(imageJson(m)))
        for (s in stickers) f(s.id, fingerprint(stickerJson(s)))
        for (fl in fills) f(fl.id, fingerprint(fl.json()))
        for (c in callouts) f(c.id, fingerprint(calloutJson(c)))
    }

    fun entryHashes(): HashMap<String, Long> {
        val m = HashMap<String, Long>(objectCount() * 2 + 16)
        forEachEntry { id, h -> m[id] = h }
        return m
    }

    fun objectCount() = strokes.size + texts.size + images.size + stickers.size + fills.size + callouts.size + statuses.size

    /**
     * Отметить правки перед сохранением: у изменённых и новых предметов — новая отметка времени,
     * пропавшие — в список удалённых. Возвращает, сколько отметок поставлено.
     * Так ловится любая правка (перенос, цвет, отмена…), ничего не надо отмечать вручную.
     */
    fun stamp(device: String, now: Long = System.currentTimeMillis()): Int {
        val st = Stamper(device, now)
        forEachEntry(st::check)
        return st.finish()
    }

    private inner class Stamper(val device: String, val now: Long) {
        var n = 0
        var present = 0

        fun check(id: String, h: Long) {
            present++
            val e = sync.objs[id]
            if (e == null || e.hash != h) {
                sync.objs[id] = BoardSync.Entry(h, sync.next(device, now))
                sync.tomb.remove(id)
                n++
            }
        }

        fun finish(): Int {
            // Что-то удалили (записей больше, чем предметов) — найти что (редко, поэтому только тогда).
            if (sync.objs.size > present) {
                val seen = entryHashes().keys
                val gone = sync.objs.keys.filter { it !in seen }
                for (id in gone) {
                    sync.objs.remove(id)
                    sync.tomb[id] = sync.next(device, now)
                    n++
                }
            }
            return n
        }
    }

    /**
     * У каждого предмета — свой номер. Предметам старых досок (до 0.9) номер даётся по содержимому:
     * одна и та же доска на двух планшетах получит одинаковые номера. Повтор номера — новый номер.
     */
    fun fixIds() {
        val used = HashSet<String>(objectCount() * 2 + 16)
        fun fix(id: String, content: () -> String): String? {
            if (id.isNotEmpty() && used.add(id)) return null
            var k = if (id.isEmpty()) "L" + java.lang.Long.toHexString(fingerprint(content())) else Ids.next()
            var i = 1
            while (!used.add(k)) k = k.substringBefore('-') + "-" + i++
            return k
        }
        for (st in statuses) used += st.id
        for (d in strokes) fix(d.id) { d.json() }?.let { d.id = it }
        for (t in texts) fix(t.id) { textJson(t) }?.let { t.id = it }
        for (m in images) fix(m.id) { imageJson(m) }?.let { m.id = it }
        for (s in stickers) fix(s.id) { stickerJson(s) }?.let { s.id = it }
        for (fl in fills) fix(fl.id) { fl.json() }?.let { fl.id = it }
        for (c in callouts) fix(c.id) { calloutJson(c) }?.let { c.id = it }
    }

    fun toJson(withCamera: Boolean = true): JSONObject = JSONObject(toJsonString(withCamera))

    fun statusesJson(): String = JSONArray().apply {
        for (s in statuses) put(JSONObject().apply { put("id", s.id); put("name", s.name); put("color", s.color) })
    }.toString()

    /** Краткая сводка для Ежедневника (чтобы не читать всю доску ради списка). */
    fun metaJson(): String = JSONObject().apply {
        put("id", id); put("title", title); put("created", created); put("updated", updated)
        put("items", strokes.size + texts.size + images.size + stickers.size + fills.size + callouts.size)
        if (sync.isShared) put("shared", true)
        put("statuses", JSONArray().apply {
            for (st in statuses) put(JSONObject().apply { put("name", st.name); put("color", st.color); put("n", countStatus(st.id)) })
        })
    }.toString()

    /** Снимок для «Отменить»: строки объектов; у неизменённых рисунков строки общие с прошлыми снимками. */
    fun snapshot() = BoardSnapshot(
        title, statusesJson(),
        Array(strokes.size) { strokes[it].json() },
        Array(texts.size) { textJson(texts[it]) },
        Array(images.size) { imageJson(images[it]) },
        Array(stickers.size) { stickerJson(stickers[it]) },
        Array(fills.size) { fills[it].json() },
        Array(callouts.size) { calloutJson(callouts[it]) },
    )

    /** Вернуть доску к снимку. Неизменённые рисунки и заливки берутся как есть, без разбора JSON. */
    fun restored(snap: BoardSnapshot): Board {
        val b = Board(id, snap.title, created, updated)
        b.camX = camX; b.camY = camY; b.camZ = camZ
        b.sync = sync
        val sa = JSONArray(snap.statuses)
        for (i in 0 until sa.length()) b.statuses += parseStatus(sa.getJSONObject(i))
        if (b.statuses.isEmpty()) b.statuses.addAll(defaultStatuses())
        val ds = java.util.IdentityHashMap<String, Drawing>()
        for (d in strokes) ds[d.json()] = d
        for (j in snap.strokes) b.strokes += ds[j] ?: parseDrawing(JSONObject(j))
        val fs = java.util.IdentityHashMap<String, BoardFill>()
        for (f in fills) fs[f.json()] = f
        for (j in snap.fills) (fs[j] ?: parseFill(JSONObject(j)))?.let { b.fills += it }
        for (j in snap.texts) b.texts += parseText(JSONObject(j))
        for (j in snap.images) b.images += parseImage(JSONObject(j))
        for (j in snap.stickers) b.stickers += parseSticker(JSONObject(j))
        for (j in snap.callouts) b.callouts += parseCallout(JSONObject(j), b.statuses[0].id)
        return b
    }

    companion object {
        /** Особые «предметы» для обмена: название доски и порядок статусов. */
        const val TITLE_ID = "~title"
        const val ORDER_ID = "~order"

        /** Отпечаток строки: длина и hashCode (у готовых строк рисунков hashCode уже посчитан). */
        fun fingerprint(s: String): Long = (s.length.toLong() shl 32) xor (s.hashCode().toLong() and 0xffffffffL)

        /** Статусы новой доски (Владимир добавляет свои в «Статусы»). id s0–s2 — как у старых меток 0, 1, 2. */
        fun defaultStatuses() = listOf(
            BoardStatus("s0", "Не сдано", Ui.BAD),
            BoardStatus("s1", "В работе", Ui.WARN),
            BoardStatus("s2", "Сдано", Ui.OK),
        )

        fun newStatusId(): String = "s" + UUID.randomUUID().toString().replace("-", "").take(10)

        fun create(title: String): Board {
            val now = System.currentTimeMillis()
            return Board(UUID.randomUUID().toString(), title, now, now).apply { statuses.addAll(defaultStatuses()) }
        }

        private fun floats(a: JSONArray?): FloatArray = if (a == null) FloatArray(0) else FloatArray(a.length()) { a.optDouble(it).toFloat() }

        private fun f(v: Float): String { val sb = StringBuilder(); JsonNum.num(sb, v); return sb.toString() }

        /** Доля 0…1 с пятью знаками (обрезка картинки). */
        private fun frac(v: Float): String = String.format(java.util.Locale.US, "%.5f", v)

        private fun q(id: String) = JSONObject.quote(id)

        fun textJson(t: BoardText): String =
            "{\"id\":${q(t.id)},\"x\":${f(t.x)},\"y\":${f(t.y)},\"size\":${t.size.toDouble()},\"text\":${JSONObject.quote(t.text)},\"color\":${t.color}}"

        fun imageJson(m: BoardImage): String {
            val sb = StringBuilder(160)
            sb.append("{\"id\":${q(m.id)},\"file\":${JSONObject.quote(m.file)},\"x\":${f(m.x)},\"y\":${f(m.y)},\"w\":${m.w.toDouble()},\"h\":${m.h.toDouble()},\"pxW\":${m.pxW},\"pxH\":${m.pxH}")
            // Новые поля — только если есть: у обычных картинок строка та же, что до 0.13.
            if (m.cropped) sb.append(",\"crop\":[${frac(m.cl)},${frac(m.ct)},${frac(m.cr)},${frac(m.cb)}]")
            if (m.locked) sb.append(",\"lk\":1")
            sb.append('}')
            return sb.toString()
        }

        fun stickerJson(s: BoardSticker): String =
            "{\"id\":${q(s.id)},\"x\":${f(s.x)},\"y\":${f(s.y)},\"sid\":${JSONObject.quote(s.sid)},\"scale\":${s.scale.toDouble()}}"

        fun calloutJson(c: BoardCallout): String {
            val sb = StringBuilder(96 + c.leaders.size * 6)
            sb.append("{\"id\":${q(c.id)},\"x\":${f(c.x)},\"y\":${f(c.y)},\"scale\":${c.scale.toDouble()},\"title\":${JSONObject.quote(c.title)}")
            sb.append(",\"sid\":${JSONObject.quote(c.sid)},\"leaders\":")
            JsonNum.array(sb, c.leaders)
            sb.append('}')
            return sb.toString()
        }

        fun parseStatus(j: JSONObject) = BoardStatus(j.optString("id", newStatusId()), j.optString("name", "Статус"), j.optInt("color", Ui.INK2))

        /** Номер из JSON; у досок до 0.9 номера нет — пусто (номер по содержимому даст [fixIds]). */
        private fun jid(j: JSONObject) = j.optString("id", "")

        fun parseDrawing(j: JSONObject) =
            Drawing(j.optString("kind", "pen"), j.optInt("color"), j.optDouble("w", 3.0).toFloat(), floats(j.optJSONArray("pts")))
                .also { it.id = jid(j) }

        fun parseText(j: JSONObject) = BoardText(j.optDouble("x").toFloat(), j.optDouble("y").toFloat(), j.optDouble("size", 24.0).toFloat(),
            j.optString("text"), j.optInt("color", Ui.INK)).also { it.id = jid(j) }

        fun parseImage(j: JSONObject) = BoardImage(j.optString("file"), j.optDouble("x").toFloat(), j.optDouble("y").toFloat(),
            j.optDouble("w").toFloat(), j.optDouble("h").toFloat(), j.optInt("pxW"), j.optInt("pxH")).also { m ->
            m.id = jid(j)
            j.optJSONArray("crop")?.let { c ->
                if (c.length() == 4) {
                    val l = c.optDouble(0, 0.0).toFloat(); val t = c.optDouble(1, 0.0).toFloat()
                    val r = c.optDouble(2, 1.0).toFloat(); val bt = c.optDouble(3, 1.0).toFloat()
                    if (l >= 0f && t >= 0f && r <= 1f && bt <= 1f && r - l >= BoardImage.MIN_CROP / 2 && bt - t >= BoardImage.MIN_CROP / 2) {
                        m.cl = l; m.ct = t; m.cr = r; m.cb = bt
                    }
                }
            }
            m.locked = j.optInt("lk") == 1
        }

        fun parseSticker(j: JSONObject): BoardSticker {
            // До 0.7.0 статус был числом 0/1/2 — это s0/s1/s2 списка по умолчанию.
            val sid = j.optString("sid", "").ifEmpty { "s" + j.optInt("status").coerceIn(0, 2) }
            return BoardSticker(j.optDouble("x").toFloat(), j.optDouble("y").toFloat(), sid, j.optDouble("scale", 1.0).toFloat())
                .also { it.id = jid(j) }
        }

        fun parseFill(j: JSONObject): BoardFill? {
            val p = floats(j.optJSONArray("pts"))
            if (p.size < 6) return null
            return BoardFill(p, j.optInt("color", Ui.INK), j.optDouble("alpha", BoardFill.DEFAULT_ALPHA.toDouble()).toFloat())
                .also { it.id = jid(j) }
        }

        fun parseCallout(j: JSONObject, defaultSid: String): BoardCallout {
            val c = BoardCallout(j.optDouble("x").toFloat(), j.optDouble("y").toFloat(), j.optDouble("scale", 1.0).toFloat(),
                j.optString("title"), j.optString("sid", defaultSid))
            c.id = jid(j)
            for (v in floats(j.optJSONArray("leaders"))) c.leaders += v
            return c
        }

        fun fromJson(o: JSONObject): Board {
            val b = Board(o.getString("id"), o.optString("title", "Доска"), o.optLong("created"), o.optLong("updated"))
            b.camX = o.optDouble("camX", 0.0).toFloat()
            b.camY = o.optDouble("camY", 0.0).toFloat()
            b.camZ = o.optDouble("camZ", 1.0).toFloat().coerceIn(0.1f, 5f)
            o.optJSONArray("statuses")?.let { a -> for (i in 0 until a.length()) b.statuses += parseStatus(a.getJSONObject(i)) }
            if (b.statuses.isEmpty()) b.statuses.addAll(defaultStatuses()) // доски до 0.7.0
            o.optJSONArray("strokes")?.let { a -> for (i in 0 until a.length()) b.strokes += parseDrawing(a.getJSONObject(i)) }
            o.optJSONArray("texts")?.let { a -> for (i in 0 until a.length()) b.texts += parseText(a.getJSONObject(i)) }
            o.optJSONArray("images")?.let { a -> for (i in 0 until a.length()) b.images += parseImage(a.getJSONObject(i)) }
            o.optJSONArray("stickers")?.let { a -> for (i in 0 until a.length()) b.stickers += parseSticker(a.getJSONObject(i)) }
            o.optJSONArray("fills")?.let { a -> for (i in 0 until a.length()) parseFill(a.getJSONObject(i))?.let { b.fills += it } }
            o.optJSONArray("callouts")?.let { a -> for (i in 0 until a.length()) b.callouts += parseCallout(a.getJSONObject(i), b.statuses[0].id) }
            b.sync = BoardSync.parse(o.optJSONObject("sync"))
            b.fixIds()
            return b
        }

        /** Цвета статуса: текст (потемнее — чтобы читался и у жёлтого) и фон (светлый оттенок). */
        fun statusFg(color: Int): Int {
            val l = Color.luminance(color)
            val k = if (l > 0.45f) 0.55f else if (l > 0.25f) 0.8f else 1f
            return Color.rgb((Color.red(color) * k).toInt(), (Color.green(color) * k).toInt(), (Color.blue(color) * k).toInt())
        }

        fun statusBg(color: Int): Int {
            fun mix(c: Int) = (c + (255 - c) * 0.78f).toInt()
            return Color.rgb(mix(Color.red(color)), mix(Color.green(color)), mix(Color.blue(color)))
        }
    }
}

/** Снимок доски для «Отменить»: строки объектов (у неизменённых рисунков — общие с прошлыми снимками). */
class BoardSnapshot(
    val title: String,
    val statuses: String,
    val strokes: Array<String>,
    val texts: Array<String>,
    val images: Array<String>,
    val stickers: Array<String>,
    val fills: Array<String>,
    val callouts: Array<String>,
)

/** Сводка доски для Ежедневника: название, даты, сколько предметов, счётчики статусов. */
class BoardMeta(
    val id: String,
    var title: String,
    val created: Long,
    val updated: Long,
    val items: Int,
    /** Название, цвет, сколько меток и выносок. */
    val counts: List<Triple<String, Int, Int>>,
    /** Общая доска (на сервере). */
    val shared: Boolean = false,
) {
    companion object {
        fun parse(o: JSONObject): BoardMeta {
            val a = o.optJSONArray("statuses") ?: JSONArray()
            val counts = (0 until a.length()).map { val j = a.getJSONObject(it); Triple(j.optString("name"), j.optInt("color"), j.optInt("n")) }
            return BoardMeta(o.getString("id"), o.optString("title", "Доска"), o.optLong("created"), o.optLong("updated"),
                o.optInt("items"), counts, o.optBoolean("shared"))
        }
    }
}

/**
 * Хранение досок: по файлу JSON на доску (+ короткая сводка `.meta` для списка);
 * картинки — в общей папке с картинками записей.
 * Запись можно вести из фонового потока ([write]): старый снимок не затрёт более новый (номер seq).
 */
class BoardStore(context: Context) {
    private val dir = File(context.filesDir, "boards").apply { mkdirs() }
    private val imageDir = File(context.filesDir, "note_images").apply { mkdirs() }

    /** Номер этого планшета для отметок правок. */
    val device: String = BoardSync.device(context)

    fun exists(id: String) = File(dir, "$id.json").exists()

    /** Список досок для Ежедневника — по сводкам; у старых досок сводка создаётся один раз. */
    fun list(): List<BoardMeta> = dir.listFiles { f -> f.name.endsWith(".json") }
        .orEmpty()
        .mapNotNull { f ->
            val id = f.name.removeSuffix(".json")
            val meta = File(dir, "$id.meta")
            runCatching { BoardMeta.parse(JSONObject(meta.readText())) }.getOrNull()
                ?: load(id)?.let { b -> runCatching { meta.writeText(b.metaJson()) }; BoardMeta.parse(JSONObject(b.metaJson())) }
        }
        .sortedByDescending { it.created }

    fun load(id: String): Board? =
        runCatching { Board.fromJson(JSONObject(File(dir, "$id.json").readText())) }.getOrNull()

    /** Сохранить сейчас (в этом потоке). */
    fun save(board: Board) {
        board.updated = System.currentTimeMillis()
        write(board.id, nextSeq(), board.toJsonString(stampDevice = device), board.metaJson())
    }

    /**
     * Сохранить доску, в которую влили файл коллеги. Открытый экран этой доски заметит это
     * ([importCount]) и перечитает доску, а не затрёт её своей старой копией.
     */
    fun saveImported(board: Board) {
        board.updated = System.currentTimeMillis()
        write(board.id, nextSeq(), board.toJsonString(), board.metaJson())
        synchronized(lock) { imports[board.id] = (imports[board.id] ?: 0) + 1 }
    }

    /** Записать готовые строки доски. seq — номер снимка: более старый, чем уже записанный, пропускается. */
    fun write(id: String, seq: Long, json: String, meta: String) {
        synchronized(lock) {
            if (seq < (written[id] ?: Long.MIN_VALUE)) return
            val tmp = File(dir, "$id.json.tmp")
            tmp.writeText(json)
            val target = File(dir, "$id.json")
            if (!tmp.renameTo(target)) {
                target.writeText(tmp.readText())
                tmp.delete()
            }
            runCatching { File(dir, "$id.meta").writeText(meta) }
            written[id] = seq
        }
    }

    fun rename(id: String, title: String) {
        val b = load(id) ?: return
        b.title = title
        save(b)
    }

    fun delete(id: String) {
        load(id)?.let { b -> for (m in b.images) BoardImages.deleteFiles(imageDir, m.file) }
        File(dir, "$id.json").delete()
        File(dir, "$id.meta").delete()
    }

    companion object {
        private val lock = Any()
        private val written = HashMap<String, Long>()
        private val imports = HashMap<String, Int>()
        private val seq = java.util.concurrent.atomic.AtomicLong(0)
        fun nextSeq(): Long = seq.incrementAndGet()

        /** Сколько раз в доску вливали файлы (пока приложение запущено). */
        fun importCount(id: String): Int = synchronized(lock) { imports[id] ?: 0 }
    }
}
