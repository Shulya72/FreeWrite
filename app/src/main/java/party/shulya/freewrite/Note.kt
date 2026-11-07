package party.shulya.freewrite

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/** Блок записи: абзац текста, строка-задача или картинка. */
sealed class Block {
    class Para(var text: String) : Block()
    class Task(var text: String, var done: Boolean) : Block()
    /**
     * Картинка. width/height — размер файла в пикселях.
     * wf — ширина на листе в долях ширины текста (0 — как вставлена: не шире 560 dp);
     * xa — где стоит по горизонтали: 0 — у левого края, 0,5 — по центру, 1 — у правого.
     */
    class Image(val file: String, val width: Int, val height: Int, var wf: Float = 0f, var xa: Float = 0f) : Block()
}

/** Рисунок ручкой или маркером: точки в координатах листа (x, y, нажим). */
class Drawing(val kind: String, var color: Int, var width: Float, val pts: FloatArray) {
    fun copy(): Drawing = Drawing(kind, color, width, pts.copyOf())

    // ---- для быстрой доски: рамка, готовый контур и JSON считаются один раз ----

    /** Номер изменения точек. Кто двигает точки — вызывает [changed]: сбрасываются рамка и контур. */
    var version = 0
        private set
    private var bbVersion = -1
    private val bb = android.graphics.RectF()

    /** Готовый контур (строит InkPainter); null — построить заново. */
    var path: android.graphics.Path? = null

    /** Нажим везде одинаковый (ровные фигуры) — рисуется одним контуром, без пересчёта толщины. */
    val uniform: Boolean by lazy {
        var u = true
        var i = 5
        while (i < pts.size && u) { if (pts[i] != pts[2]) u = false; i += 3 }
        u
    }

    fun changed() {
        version++
        path = null
    }

    /** Рамка рисунка с запасом на толщину линии. */
    fun bounds(): android.graphics.RectF {
        if (bbVersion != version) {
            var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
            var i = 0
            while (i + 1 < pts.size) {
                val x = pts[i]; val y = pts[i + 1]
                if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y
                i += 3
            }
            val w = width
            if (l <= r) bb.set(l - w, t - w, r + w, b + w) else bb.setEmpty()
            bbVersion = version
        }
        return bb
    }

    private var jsonSum = 0L
    private var jsonCache: String? = null

    /**
     * Запись рисунка в JSON доски. Готовая строка переиспользуется, пока рисунок не менялся
     * (проверка по контрольной сумме всех чисел — без риска сохранить старое).
     */
    fun json(): String {
        var h = 1125899906842597L
        h = 31 * h + color
        h = 31 * h + width.toRawBits()
        h = 31 * h + kind.hashCode()
        for (v in pts) h = 31 * h + v.toRawBits()
        jsonCache?.let { if (h == jsonSum) return it }
        val sb = StringBuilder(pts.size * 6 + 64)
        sb.append("{\"kind\":").append(JSONObject.quote(kind)).append(",\"color\":").append(color)
        sb.append(",\"w\":").append(width.toDouble()).append(",\"pts\":")
        JsonNum.array(sb, pts)
        sb.append('}')
        val j = sb.toString()
        jsonCache = j
        jsonSum = h
        return j
    }
}

/** Быстрая запись чисел в JSON (округление до 0,1 — как раньше). */
object JsonNum {
    fun num(sb: StringBuilder, v: Float) {
        var r = (v * 10f).roundToInt()
        if (r < 0) { sb.append('-'); r = -r }
        sb.append(r / 10).append('.').append(r % 10)
    }

    fun array(sb: StringBuilder, a: FloatArray) {
        sb.append('[')
        for (i in a.indices) {
            if (i > 0) sb.append(',')
            num(sb, a[i])
        }
        sb.append(']')
    }

    fun array(sb: StringBuilder, a: List<Float>) {
        sb.append('[')
        for (i in a.indices) {
            if (i > 0) sb.append(',')
            num(sb, a[i])
        }
        sb.append(']')
    }
}

/** Одна запись ежедневника. */
class Note(
    val id: String,
    var title: String,
    val created: Long,
    var updated: Long,
    val blocks: MutableList<Block>,
    val drawings: MutableList<Drawing>,
    var caret: Int,
) {
    /** Позиция курсора внутри строки caret; -1 — в конце строки. */
    var caretOffset: Int = -1

    val tasksDone: Int get() = blocks.count { it is Block.Task && it.done && it.text.isNotBlank() }
    val tasksOpen: Int get() = blocks.count { it is Block.Task && !it.done && it.text.isNotBlank() }

    /** Первая непустая строка текста — для списка в ежедневнике. */
    val preview: String
        get() = blocks.firstNotNullOfOrNull {
            when (it) {
                is Block.Para -> it.text.trim().ifEmpty { null }
                is Block.Task -> it.text.trim().ifEmpty { null }
                else -> null
            }
        }.orEmpty()

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("v", 1)
        o.put("id", id)
        o.put("title", title)
        o.put("created", created)
        o.put("updated", updated)
        o.put("caret", caret)
        o.put("caretOff", caretOffset)
        val bl = JSONArray()
        for (b in blocks) {
            val j = JSONObject()
            when (b) {
                is Block.Para -> { j.put("t", "p"); j.put("text", b.text) }
                is Block.Task -> { j.put("t", "task"); j.put("text", b.text); j.put("done", b.done) }
                is Block.Image -> {
                    j.put("t", "img"); j.put("file", b.file); j.put("w", b.width); j.put("h", b.height)
                    if (b.wf > 0f) j.put("wf", b.wf.toDouble())
                    if (b.xa > 0f) j.put("xa", b.xa.toDouble())
                }
            }
            bl.put(j)
        }
        o.put("blocks", bl)
        val dr = JSONArray()
        for (d in drawings) {
            val j = JSONObject()
            j.put("kind", d.kind)
            j.put("color", d.color)
            j.put("w", d.width.toDouble())
            val pts = JSONArray()
            for (v in d.pts) pts.put((v * 10f).roundToInt() / 10.0)
            j.put("pts", pts)
            dr.put(j)
        }
        o.put("drawings", dr)
        return o
    }

    fun copy(): Note = fromJson(toJson())

    companion object {
        fun create(title: String): Note {
            val now = System.currentTimeMillis()
            return Note(UUID.randomUUID().toString(), title, now, now, mutableListOf(Block.Para("")), mutableListOf(), 0)
        }

        fun fromJson(o: JSONObject): Note {
            val blocks = ArrayList<Block>()
            val bl = o.optJSONArray("blocks") ?: JSONArray()
            for (i in 0 until bl.length()) {
                val j = bl.getJSONObject(i)
                when (j.optString("t")) {
                    "task" -> blocks += Block.Task(j.optString("text"), j.optBoolean("done"))
                    "img" -> blocks += Block.Image(
                        j.optString("file"), j.optInt("w"), j.optInt("h"),
                        j.optDouble("wf", 0.0).toFloat(), j.optDouble("xa", 0.0).toFloat(),
                    )
                    else -> blocks += Block.Para(j.optString("text"))
                }
            }
            if (blocks.isEmpty()) blocks += Block.Para("")
            val drawings = ArrayList<Drawing>()
            val dr = o.optJSONArray("drawings") ?: JSONArray()
            for (i in 0 until dr.length()) {
                val j = dr.getJSONObject(i)
                val pa = j.optJSONArray("pts") ?: JSONArray()
                val pts = FloatArray(pa.length()) { pa.optDouble(it).toFloat() }
                drawings += Drawing(j.optString("kind", "pen"), j.optInt("color"), j.optDouble("w", 3.0).toFloat(), pts)
            }
            return Note(
                id = o.getString("id"),
                title = o.optString("title", "Запись"),
                created = o.optLong("created"),
                updated = o.optLong("updated"),
                blocks = blocks,
                drawings = drawings,
                caret = o.optInt("caret", blocks.size - 1).coerceIn(0, blocks.size - 1),
            ).apply { caretOffset = o.optInt("caretOff", -1) }
        }
    }
}

/** Хранение записей: по файлу JSON на запись, картинки — отдельными файлами. */
class NoteStore(context: Context) {
    private val dir = File(context.filesDir, "notes").apply { mkdirs() }
    val imageDir = File(context.filesDir, "note_images").apply { mkdirs() }

    fun list(): List<Note> = dir.listFiles { f -> f.name.endsWith(".json") }
        .orEmpty()
        .mapNotNull { f -> runCatching { Note.fromJson(JSONObject(f.readText())) }.getOrNull() }
        .sortedByDescending { it.created }

    fun load(id: String): Note? =
        runCatching { Note.fromJson(JSONObject(File(dir, "$id.json").readText())) }.getOrNull()

    fun save(note: Note) {
        note.updated = System.currentTimeMillis()
        val tmp = File(dir, "${note.id}.json.tmp")
        tmp.writeText(note.toJson().toString())
        val target = File(dir, "${note.id}.json")
        if (!tmp.renameTo(target)) {
            target.writeText(tmp.readText())
            tmp.delete()
        }
    }

    fun delete(note: Note) {
        for (b in note.blocks) if (b is Block.Image) File(imageDir, b.file).delete()
        File(dir, "${note.id}.json").delete()
    }

    /**
     * Скопировать картинку из галереи к себе, уменьшив до 2000 px по длинной стороне.
     * Возвращает блок-картинку или null, если файл не читается.
     */
    fun importImage(context: Context, uri: Uri): Block.Image? {
        val resolver = context.contentResolver
        // Сначала только размер. В этом режиме decodeStream всегда возвращает null — это нормально,
        // размер приходит в bounds.outWidth/outHeight.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        (resolver.openInputStream(uri) ?: return null).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > 2000) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = (resolver.openInputStream(uri) ?: return null).use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        // Фото с камеры хранят поворот отдельно (EXIF) — поворачиваем, чтобы не легло боком.
        val degrees = try {
            resolver.openInputStream(uri)?.use {
                when (android.media.ExifInterface(it).getAttributeInt(
                    android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL,
                )) {
                    android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        } catch (_: Throwable) {
            0f
        }
        if (degrees != 0f) {
            val m = android.graphics.Matrix().apply { postRotate(degrees) }
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (rotated !== bmp) bmp.recycle()
            bmp = rotated
        }
        val name = UUID.randomUUID().toString() + ".jpg"
        File(imageDir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        val block = Block.Image(name, bmp.width, bmp.height)
        bmp.recycle()
        return block
    }

    /** Картинка для показа на листе: не шире maxWidth пикселей. */
    fun loadImage(block: Block.Image, maxWidth: Int): Bitmap? {
        val f = File(imageDir, block.file)
        if (!f.exists()) return null
        var sample = 1
        while (block.width / sample > maxWidth * 2) sample *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
