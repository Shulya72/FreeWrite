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
 *
 * Списков два (0.18, FW-011): [ID] — исполнительная документация (кнопки-метки и ИГС в строке ИД),
 * [CON] — конструкции (выноски). У старых досок все статусы — ИД.
 */
class BoardStatus(val id: String, var name: String, var color: Int, val group: String = ID) {
    companion object {
        const val ID = "id"
        const val CON = "con"
    }
}

/**
 * Строка ИД у захватки (0.18, FW-011): «Захватка 1 — Опалубка: Чек-лист ✓/✗, DWG ✓/✗, ИГС [статус]».
 * Касание галочки — отметить/снять, касание ИГС — следующий статус (список ИД), удержание — поднять и тащить.
 * Размер — scale (как у метки), раздел — по рамке раздела, в которой стоит.
 */
class BoardRow(var x: Float, var y: Float, var scale: Float, var title: String, var check: Boolean, var dwg: Boolean, var sid: String) {
    var id: String = Ids.next()

    val h: Float get() = 40f * scale

    // Ширины частей считает BoardView (по шрифту), пока не поменялись название, статус и размер.
    internal var wKey: String? = null
    internal val parts = FloatArray(5) // границы: начало, конец названия, Чек-лист, DWG, ИГС (конец)

    fun copy(): BoardRow = BoardRow(x, y, scale, title, check, dwg, sid)

    companion object {
        /** Следующая захватка: первое число в названии +1 («Захватка 1 — Опалубка» → «Захватка 2 — Опалубка»). */
        fun nextTitle(t: String): String {
            val m = Regex("\\d+").find(t) ?: return t
            val n = m.value.toLongOrNull() ?: return t
            return t.substring(0, m.range.first) + (n + 1) + t.substring(m.range.last + 1)
        }
    }
}

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
/**
 * Раздел (0.14): рамка с названием — «Секция 3», «Корпус 1». Список разделов — быстрый переход.
 * Перенос раздела переносит и всё, что внутри.
 */
class BoardFrame(var x: Float, var y: Float, var w: Float, var h: Float, var title: String) {
    var id: String = Ids.next()
}

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
    val frames = ArrayList<BoardFrame>()
    val rows = ArrayList<BoardRow>()
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

    /** Статусы одного списка ([BoardStatus.ID] или [BoardStatus.CON]) по порядку. */
    fun statuses(group: String): List<BoardStatus> {
        if (statuses.isEmpty()) statuses.addAll(defaultStatuses())
        return statuses.filter { it.group == group }
    }

    /** Первый статус списка (новая метка, строка, выноска). Пустой список — первый вообще. */
    fun firstStatus(group: String): String = statuses(group).firstOrNull()?.id ?: status("").id

    /**
     * Следующий статус по порядку своего списка (после последнего — снова первый). Метка и ИГС строки ходят
     * по списку ИД, выноска — по списку конструкций; статус из чужого списка (старая доска) — первый своего.
     */
    fun nextStatus(id: String, group: String): String {
        val list = statuses(group).ifEmpty { statuses }
        val i = list.indexOfFirst { it.id == id }
        return list[(i + 1).mod(list.size)].id
    }

    /** Сколько меток, строк ИД и выносок с этим статусом (в разделе fr — только внутри него). */
    fun countStatus(id: String, fr: BoardFrame? = null): Int {
        if (fr == null) return stickers.count { it.sid == id } + callouts.count { it.sid == id } + rows.count { it.sid == id }
        return stickers.count { it.sid == id && sectionOf(it) === fr } + callouts.count { it.sid == id && sectionOf(it) === fr } +
            rows.count { it.sid == id && sectionOf(it) === fr }
    }

    /**
     * Раздел, в котором стоит предмет: самая маленькая рамка раздела, внутри которой его середина
     * (у выноски — куда указывает первый указатель). null — вне разделов.
     */
    fun sectionOf(o: Any): BoardFrame? {
        val (x, y) = when (o) {
            is BoardSticker -> (o.x + 64f * o.scale) to (o.y + o.h / 2f)
            is BoardRow -> (o.x + 20f * o.scale) to (o.y + o.h / 2f)
            is BoardCallout -> if (o.leaders.size >= 2) o.leaders[0] to o.leaders[1] else o.x to o.y
            is BoardText -> o.x to o.y
            is BoardImage -> (o.x + o.w / 2f) to (o.y + o.h / 2f)
            else -> return null
        }
        return sectionAt(x, y)
    }

    fun sectionAt(x: Float, y: Float): BoardFrame? {
        var best: BoardFrame? = null
        for (fr in frames) {
            if (x < fr.x || x > fr.x + fr.w || y < fr.y || y > fr.y + fr.h) continue
            if (best == null || fr.w * fr.h < best.w * best.h) best = fr
        }
        return best
    }

    /**
     * Статусы старой доски (до 0.18): список становится списком ИД; нетронутый старый список по умолчанию
     * («Не сдано / В работе / Сдано», ни одна метка его не использует) заменяется новым списком ИД; список
     * конструкций добавляется. Номера у статусов по умолчанию одинаковые на всех планшетах (i0…, c0…) —
     * общая доска сходится.
     */
    fun upgradeStatuses() {
        if (statuses.any { it.group == BoardStatus.CON }) return
        val old = listOf("s0" to "Не сдано", "s1" to "В работе", "s2" to "Сдано")
        val untouched = statuses.size == 3 && statuses.zip(old).all { (st, o) -> st.id == o.first && st.name == o.second }
        val used = HashSet<String>().apply { stickers.forEach { add(it.sid) }; callouts.forEach { add(it.sid) }; rows.forEach { add(it.sid) } }
        if (statuses.isEmpty() || (untouched && statuses.none { it.id in used })) {
            statuses.clear()
            statuses.addAll(idStatuses())
        }
        statuses.addAll(conStatuses())
    }

    val isEmpty: Boolean
        get() = strokes.isEmpty() && texts.isEmpty() && images.isEmpty() && stickers.isEmpty() &&
            fills.isEmpty() && callouts.isEmpty() && frames.isEmpty() && rows.isEmpty()

    /**
     * Доска в JSON одной строкой. Тяжёлые части (рисунки, заливки) берутся готовыми строками —
     * поэтому сохранение и снимок для «Отменить» быстрые даже на большой доске.
     */
    fun toJsonString(withCamera: Boolean = true, stampDevice: String? = null, now: Long = System.currentTimeMillis()): String {
        val parts = toJsonParts(withCamera, stampDevice, now)
        var n = 0
        for (p in parts) n += p.length
        val sb = StringBuilder(n)
        for (p in parts) sb.append(p)
        return sb.toString()
    }

    /**
     * Доска JSON по частям — те же строки, что [toJsonString], только не склеенные (FW-009): склейка и запись
     * большой доски (мегабайты) идут в фоне, здесь — только готовые строки предметов и отметки правок.
     */
    fun toJsonParts(withCamera: Boolean = true, stampDevice: String? = null, now: Long = System.currentTimeMillis()): List<String> {
        // stampDevice — заодно отметить правки ([stamp]) тем же проходом: строки предметов уже готовы.
        val st = stampDevice?.let { Stamper(it, now) }
        if (st != null) {
            st.check(TITLE_ID, fingerprint(title))
            st.check(ORDER_ID, fingerprint(statuses.joinToString(",") { it.id }))
            for (s in statuses) st.check(s.id, statusPrint(s))
        }
        val out = ArrayList<String>(objectCount() * 2 + 32)
        val sb = StringBuilder(1024)
        sb.append("{\"v\":2,\"kind\":\"board\",\"id\":").append(JSONObject.quote(id))
        sb.append(",\"title\":").append(JSONObject.quote(title))
        sb.append(",\"created\":").append(created).append(",\"updated\":").append(updated)
        if (withCamera) {
            sb.append(",\"camX\":").append(camX.toDouble()).append(",\"camY\":").append(camY.toDouble())
            sb.append(",\"camZ\":").append(camZ.toDouble())
        }
        sb.append(",\"statuses\":").append(statusesJson())
        out += sb.toString()
        fun <T> list(name: String, items: List<T>, id: (T) -> String, f: (T) -> String) {
            out += ",\"$name\":["
            for ((i, it) in items.withIndex()) {
                if (i > 0) out += ","
                val j = f(it)
                st?.check(id(it), fingerprint(j))
                out += j
            }
            out += "]"
        }
        list("strokes", strokes, { it.id }) { it.json() }
        list("texts", texts, { it.id }) { textJson(it) }
        list("images", images, { it.id }) { imageJson(it) }
        list("stickers", stickers, { it.id }) { stickerJson(it) }
        list("fills", fills, { it.id }) { it.json() }
        list("callouts", callouts, { it.id }) { calloutJson(it) }
        if (frames.isNotEmpty()) list("frames", frames, { it.id }) { frameJson(it) }
        if (rows.isNotEmpty()) list("rows", rows, { it.id }) { rowJson(it) }
        st?.finish()
        val tail = StringBuilder(sync.objs.size * 48 + 64)
        tail.append(",\"sync\":")
        sync.json(tail)
        tail.append('}')
        out += tail.toString()
        return out
    }

    /**
     * Все предметы доски одной строкой каждый: номер → «отпечаток» содержимого (меняется при любой
     * правке). Сюда же входят статусы, их порядок («~order») и название доски («~title»).
     */
    fun forEachEntry(f: (String, Long) -> Unit) {
        f(TITLE_ID, fingerprint(title))
        f(ORDER_ID, fingerprint(statuses.joinToString(",") { it.id }))
        for (st in statuses) f(st.id, statusPrint(st))
        for (d in strokes) f(d.id, fingerprint(d.json()))
        for (t in texts) f(t.id, fingerprint(textJson(t)))
        for (m in images) f(m.id, fingerprint(imageJson(m)))
        for (s in stickers) f(s.id, fingerprint(stickerJson(s)))
        for (fl in fills) f(fl.id, fingerprint(fl.json()))
        for (c in callouts) f(c.id, fingerprint(calloutJson(c)))
        for (fr in frames) f(fr.id, fingerprint(frameJson(fr)))
        for (r in rows) f(r.id, fingerprint(rowJson(r)))
    }

    fun entryHashes(): HashMap<String, Long> {
        val m = HashMap<String, Long>(objectCount() * 2 + 16)
        forEachEntry { id, h -> m[id] = h }
        return m
    }

    fun objectCount() = strokes.size + texts.size + images.size + stickers.size + fills.size + callouts.size + frames.size + rows.size + statuses.size

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
        for (fr in frames) fix(fr.id) { frameJson(fr) }?.let { fr.id = it }
        for (r in rows) fix(r.id) { rowJson(r) }?.let { r.id = it }
    }

    fun toJson(withCamera: Boolean = true): JSONObject = JSONObject(toJsonString(withCamera))

    fun statusesJson(): String = JSONArray().apply {
        for (s in statuses) put(JSONObject().apply {
            put("id", s.id); put("name", s.name); put("color", s.color)
            if (s.group != BoardStatus.ID) put("g", s.group) // у статусов ИД — как до 0.18
        })
    }.toString()

    /** Краткая сводка для Ежедневника (чтобы не читать всю доску ради списка). */
    fun metaJson(): String = JSONObject().apply {
        put("id", id); put("title", title); put("created", created); put("updated", updated)
        put("items", strokes.size + texts.size + images.size + stickers.size + fills.size + callouts.size + rows.size)
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
        Array(frames.size) { frameJson(frames[it]) },
        Array(rows.size) { rowJson(rows[it]) },
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
        for (j in snap.callouts) b.callouts += parseCallout(JSONObject(j), b.firstStatus(BoardStatus.CON))
        for (j in snap.frames) b.frames += parseFrame(JSONObject(j))
        for (j in snap.rows) b.rows += parseRow(JSONObject(j), b.firstStatus(BoardStatus.ID))
        return b
    }

    companion object {
        /** Особые «предметы» для обмена: название доски и порядок статусов. */
        const val TITLE_ID = "~title"
        const val ORDER_ID = "~order"

        /** Отпечаток строки: длина и hashCode (у готовых строк рисунков hashCode уже посчитан). */
        fun fingerprint(s: String): Long = (s.length.toLong() shl 32) xor (s.hashCode().toLong() and 0xffffffffL)

        /** Статусы новой доски: список ИД и список конструкций (Владимир правит их в «Статусы»). */
        fun defaultStatuses() = idStatuses() + conStatuses()

        /** Исполнительная документация (FW-011, список Владимира 08.11.2026). Номера одинаковые на всех планшетах. */
        fun idStatuses() = listOf(
            BoardStatus("i0", "Конструкция не принята", Ui.BAD),
            BoardStatus("i1", "В оформлении", Color.rgb(230, 126, 34)),
            BoardStatus("i2", "На подписи у Заказчика", Color.rgb(214, 170, 0)),
            BoardStatus("i3", "Передано в ПТО", Color.rgb(41, 121, 255)),
            BoardStatus("i4", "Скан в архиве", Ui.OK),
        )

        /** Конструкции (выноски) — для начала; Владимир поправит под себя. */
        fun conStatuses() = listOf(
            BoardStatus("c0", "Не начато", Color.rgb(140, 140, 140), BoardStatus.CON),
            BoardStatus("c1", "Опалубка", Color.rgb(230, 126, 34), BoardStatus.CON),
            BoardStatus("c2", "Армирование", Color.rgb(214, 170, 0), BoardStatus.CON),
            BoardStatus("c3", "Бетонирование", Color.rgb(41, 121, 255), BoardStatus.CON),
            BoardStatus("c4", "Принято", Ui.OK, BoardStatus.CON),
        )

        /** Отпечаток статуса для обмена (у статусов ИД — как до 0.18). */
        fun statusPrint(s: BoardStatus): Long =
            fingerprint(s.name + "\u0001" + s.color + if (s.group != BoardStatus.ID) "\u0001" + s.group else "")

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

        fun frameJson(fr: BoardFrame): String =
            "{\"id\":${q(fr.id)},\"x\":${f(fr.x)},\"y\":${f(fr.y)},\"w\":${f(fr.w)},\"h\":${f(fr.h)},\"title\":${JSONObject.quote(fr.title)}}"

        fun parseFrame(j: JSONObject) = BoardFrame(j.optDouble("x").toFloat(), j.optDouble("y").toFloat(),
            j.optDouble("w", 400.0).toFloat().coerceAtLeast(1f), j.optDouble("h", 300.0).toFloat().coerceAtLeast(1f),
            j.optString("title", "Раздел")).also { it.id = jid(j) }

        fun parseStatus(j: JSONObject) = BoardStatus(j.optString("id", newStatusId()), j.optString("name", "Статус"), j.optInt("color", Ui.INK2),
            if (j.optString("g") == BoardStatus.CON) BoardStatus.CON else BoardStatus.ID)

        fun rowJson(r: BoardRow): String =
            "{\"id\":${q(r.id)},\"x\":${f(r.x)},\"y\":${f(r.y)},\"scale\":${r.scale.toDouble()},\"title\":${JSONObject.quote(r.title)}" +
                ",\"ck\":${if (r.check) 1 else 0},\"dwg\":${if (r.dwg) 1 else 0},\"sid\":${JSONObject.quote(r.sid)}}"

        fun parseRow(j: JSONObject, defaultSid: String) = BoardRow(j.optDouble("x").toFloat(), j.optDouble("y").toFloat(),
            j.optDouble("scale", 1.0).toFloat(), j.optString("title"), j.optInt("ck") == 1, j.optInt("dwg") == 1,
            j.optString("sid", defaultSid)).also { it.id = jid(j) }

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
            b.camZ = o.optDouble("camZ", 1.0).toFloat().coerceIn(BoardView.MIN_ZOOM, BoardView.MAX_ZOOM)
            o.optJSONArray("statuses")?.let { a -> for (i in 0 until a.length()) b.statuses += parseStatus(a.getJSONObject(i)) }
            if (b.statuses.isEmpty()) b.statuses.addAll(defaultStatuses()) // доски до 0.7.0
            o.optJSONArray("strokes")?.let { a -> for (i in 0 until a.length()) b.strokes += parseDrawing(a.getJSONObject(i)) }
            o.optJSONArray("texts")?.let { a -> for (i in 0 until a.length()) b.texts += parseText(a.getJSONObject(i)) }
            o.optJSONArray("images")?.let { a -> for (i in 0 until a.length()) b.images += parseImage(a.getJSONObject(i)) }
            o.optJSONArray("stickers")?.let { a -> for (i in 0 until a.length()) b.stickers += parseSticker(a.getJSONObject(i)) }
            o.optJSONArray("fills")?.let { a -> for (i in 0 until a.length()) parseFill(a.getJSONObject(i))?.let { b.fills += it } }
            o.optJSONArray("callouts")?.let { a -> for (i in 0 until a.length()) b.callouts += parseCallout(a.getJSONObject(i), b.statuses[0].id) }
            o.optJSONArray("frames")?.let { a -> for (i in 0 until a.length()) b.frames += parseFrame(a.getJSONObject(i)) }
            o.optJSONArray("rows")?.let { a -> for (i in 0 until a.length()) b.rows += parseRow(a.getJSONObject(i), b.statuses[0].id) }
            b.upgradeStatuses()
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
    val frames: Array<String> = emptyArray(),
    val rows: Array<String> = emptyArray(),
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
        write(board.id, nextSeq(), board.toJsonParts(stampDevice = device), board.metaJson())
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
    fun write(id: String, seq: Long, json: String, meta: String) = write(id, seq, listOf(json), meta)

    /** То же по частям ([Board.toJsonParts]): склеиваются прямо в файл, без строки в мегабайты. */
    fun write(id: String, seq: Long, parts: List<String>, meta: String) {
        synchronized(lock) {
            if (seq < (written[id] ?: Long.MIN_VALUE)) return
            val tmp = File(dir, "$id.json.tmp")
            tmp.bufferedWriter(Charsets.UTF_8, 1 shl 16).use { w -> for (p in parts) w.write(p) }
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
