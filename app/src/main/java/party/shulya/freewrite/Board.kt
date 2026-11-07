package party.shulya.freewrite

import android.content.Context
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/** Текст на доске: x — левый край, y — линия букв (baseline), size — размер шрифта в единицах доски. */
class BoardText(var x: Float, var y: Float, var size: Float, var text: String, var color: Int)

/** Картинка на доске (файл — в общей папке картинок). */
class BoardImage(val file: String, var x: Float, var y: Float, var w: Float, var h: Float, val pxW: Int, val pxH: Int)

/**
 * Статус из списка доски (его настраивает Владимир: «Сдали опалубку», «Забетонировано»…).
 * Порядок в списке — порядок переключения касанием.
 */
class BoardStatus(val id: String, var name: String, var color: Int)

/** Метка статуса. sid — id статуса из списка доски. scale — размер (метка ставится «в рост» текущего масштаба). */
class BoardSticker(var x: Float, var y: Float, var sid: String, var scale: Float) {
    val h: Float get() = 36f * scale

    /** Ширина под подпись: не меньше 128, длинное название — шире. */
    fun width(label: String): Float = max(128f, label.length * 9.4f + 40f) * scale
}

/**
 * Заливка — залитая область (захватка, закрашенная стена): контур (x, y подряд), цвет, прозрачность.
 * Рисуется над картинками и под надписями — чертёж под заливкой виден.
 */
class BoardFill(val pts: FloatArray, var color: Int, var alpha: Float = DEFAULT_ALPHA) {
    fun copy() = BoardFill(pts.copyOf(), color, alpha)

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
    val leaders = ArrayList<Float>()

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

    private fun pts(a: FloatArray) = JSONArray().apply { for (v in a) put((v * 10f).roundToInt() / 10.0) }

    fun toJson(withCamera: Boolean = true): JSONObject {
        val o = JSONObject()
        o.put("v", 2); o.put("kind", "board")
        o.put("id", id); o.put("title", title); o.put("created", created); o.put("updated", updated)
        if (withCamera) { o.put("camX", camX.toDouble()); o.put("camY", camY.toDouble()); o.put("camZ", camZ.toDouble()) }
        o.put("statuses", JSONArray().apply {
            for (s in statuses) put(JSONObject().apply { put("id", s.id); put("name", s.name); put("color", s.color) })
        })
        o.put("strokes", JSONArray().apply {
            for (d in strokes) put(JSONObject().apply {
                put("kind", d.kind); put("color", d.color); put("w", d.width.toDouble()); put("pts", pts(d.pts))
            })
        })
        o.put("texts", JSONArray().apply {
            for (t in texts) put(JSONObject().apply {
                put("x", t.x.toDouble()); put("y", t.y.toDouble()); put("size", t.size.toDouble()); put("text", t.text); put("color", t.color)
            })
        })
        o.put("images", JSONArray().apply {
            for (m in images) put(JSONObject().apply {
                put("file", m.file); put("x", m.x.toDouble()); put("y", m.y.toDouble()); put("w", m.w.toDouble()); put("h", m.h.toDouble())
                put("pxW", m.pxW); put("pxH", m.pxH)
            })
        })
        o.put("stickers", JSONArray().apply {
            for (s in stickers) put(JSONObject().apply {
                put("x", s.x.toDouble()); put("y", s.y.toDouble()); put("sid", s.sid); put("scale", s.scale.toDouble())
            })
        })
        o.put("fills", JSONArray().apply {
            for (f in fills) put(JSONObject().apply { put("color", f.color); put("alpha", f.alpha.toDouble()); put("pts", pts(f.pts)) })
        })
        o.put("callouts", JSONArray().apply {
            for (c in callouts) put(JSONObject().apply {
                put("x", c.x.toDouble()); put("y", c.y.toDouble()); put("scale", c.scale.toDouble())
                put("title", c.title); put("sid", c.sid); put("leaders", pts(c.leaders.toFloatArray()))
            })
        })
        return o
    }

    companion object {
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

        fun fromJson(o: JSONObject): Board {
            val b = Board(o.getString("id"), o.optString("title", "Доска"), o.optLong("created"), o.optLong("updated"))
            b.camX = o.optDouble("camX", 0.0).toFloat()
            b.camY = o.optDouble("camY", 0.0).toFloat()
            b.camZ = o.optDouble("camZ", 1.0).toFloat().coerceIn(0.1f, 5f)
            o.optJSONArray("statuses")?.let { a ->
                for (i in 0 until a.length()) {
                    val j = a.getJSONObject(i)
                    b.statuses += BoardStatus(j.optString("id", newStatusId()), j.optString("name", "Статус"), j.optInt("color", Ui.INK2))
                }
            }
            if (b.statuses.isEmpty()) b.statuses.addAll(defaultStatuses()) // доски до 0.7.0
            o.optJSONArray("strokes")?.let { a ->
                for (i in 0 until a.length()) {
                    val j = a.getJSONObject(i)
                    b.strokes += Drawing(j.optString("kind", "pen"), j.optInt("color"), j.optDouble("w", 3.0).toFloat(), floats(j.optJSONArray("pts")))
                }
            }
            o.optJSONArray("texts")?.let { a ->
                for (i in 0 until a.length()) {
                    val j = a.getJSONObject(i)
                    b.texts += BoardText(j.optDouble("x").toFloat(), j.optDouble("y").toFloat(), j.optDouble("size", 24.0).toFloat(),
                        j.optString("text"), j.optInt("color", Ui.INK))
                }
            }
            o.optJSONArray("images")?.let { a ->
                for (i in 0 until a.length()) {
                    val j = a.getJSONObject(i)
                    b.images += BoardImage(j.optString("file"), j.optDouble("x").toFloat(), j.optDouble("y").toFloat(),
                        j.optDouble("w").toFloat(), j.optDouble("h").toFloat(), j.optInt("pxW"), j.optInt("pxH"))
                }
            }
            o.optJSONArray("stickers")?.let { a ->
                for (i in 0 until a.length()) {
                    val j = a.getJSONObject(i)
                    // До 0.7.0 статус был числом 0/1/2 — это s0/s1/s2 списка по умолчанию.
                    val sid = j.optString("sid", "").ifEmpty { "s" + j.optInt("status").coerceIn(0, 2) }
                    b.stickers += BoardSticker(j.optDouble("x").toFloat(), j.optDouble("y").toFloat(), sid, j.optDouble("scale", 1.0).toFloat())
                }
            }
            o.optJSONArray("fills")?.let { a ->
                for (i in 0 until a.length()) {
                    val j = a.getJSONObject(i)
                    val p = floats(j.optJSONArray("pts"))
                    if (p.size >= 6) b.fills += BoardFill(p, j.optInt("color", Ui.INK), j.optDouble("alpha", BoardFill.DEFAULT_ALPHA.toDouble()).toFloat())
                }
            }
            o.optJSONArray("callouts")?.let { a ->
                for (i in 0 until a.length()) {
                    val j = a.getJSONObject(i)
                    val c = BoardCallout(j.optDouble("x").toFloat(), j.optDouble("y").toFloat(), j.optDouble("scale", 1.0).toFloat(),
                        j.optString("title"), j.optString("sid", b.statuses[0].id))
                    for (v in floats(j.optJSONArray("leaders"))) c.leaders += v
                    b.callouts += c
                }
            }
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

/** Хранение досок: по файлу JSON на доску; картинки — в общей папке с картинками записей. */
class BoardStore(context: Context) {
    private val dir = File(context.filesDir, "boards").apply { mkdirs() }
    private val imageDir = File(context.filesDir, "note_images").apply { mkdirs() }

    fun list(): List<Board> = dir.listFiles { f -> f.name.endsWith(".json") }
        .orEmpty()
        .mapNotNull { f -> runCatching { Board.fromJson(JSONObject(f.readText())) }.getOrNull() }
        .sortedByDescending { it.created }

    fun load(id: String): Board? =
        runCatching { Board.fromJson(JSONObject(File(dir, "$id.json").readText())) }.getOrNull()

    fun save(board: Board) {
        board.updated = System.currentTimeMillis()
        val tmp = File(dir, "${board.id}.json.tmp")
        tmp.writeText(board.toJson().toString())
        val target = File(dir, "${board.id}.json")
        if (!tmp.renameTo(target)) {
            target.writeText(tmp.readText())
            tmp.delete()
        }
    }

    fun delete(board: Board) {
        for (m in board.images) File(imageDir, m.file).delete()
        File(dir, "${board.id}.json").delete()
    }
}
