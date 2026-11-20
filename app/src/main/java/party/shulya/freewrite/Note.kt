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

/**
 * Блок записи: абзац текста, строка-задача (пункт чек-листа) или картинка. С 0.30 у абзаца — стиль ([ParaStyle]), у абзаца и
 * пункта — отступ и оформление кусков ([TextSpan]: жирный, курсив, маркер, цвет…).
 */
sealed class Block {
    class Para(
        override var text: String,
        var style: String = ParaStyle.BODY,
        override var indent: Int = 0,
        override val spans: MutableList<TextSpan> = ArrayList(),
    ) : Block(), TextLine
    class Task(
        override var text: String,
        var done: Boolean,
        override var indent: Int = 0,
        override val spans: MutableList<TextSpan> = ArrayList(),
    ) : Block(), TextLine
    /**
     * Картинка. width/height — размер файла в пикселях.
     * wf — ширина на листе в долях ширины текста (0 — как вставлена: не шире 560 dp);
     * xa — где стоит по горизонтали: 0 — у левого края, 0,5 — по центру, 1 — у правого.
     */
    class Image(val file: String, val width: Int, val height: Int, var wf: Float = 0f, var xa: Float = 0f) : Block()
}

/** Рисунок ручкой или маркером: точки в координатах листа (x, y, нажим). */
class Drawing(val kind: String, var color: Int, var width: Float, val pts: FloatArray) {
    /** Номер рисунка на доске — по нему сливаются правки с доской коллеги. Копия получает новый номер. */
    var id: String = Ids.next()

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
        h = 31 * h + id.hashCode()
        for (v in pts) h = 31 * h + v.toRawBits()
        jsonCache?.let { if (h == jsonSum) return it }
        val sb = StringBuilder(pts.size * 6 + 80)
        sb.append("{\"id\":").append(JSONObject.quote(id))
        sb.append(",\"kind\":").append(JSONObject.quote(kind)).append(",\"color\":").append(color)
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
    /** Строка в кавычках; простые (номера, отметки правок) — без лишней строки-копии, как JSONObject.quote. */
    fun quote(sb: StringBuilder, s: String) {
        for (c in s) {
            if (c < ' ' || c == '"' || c == '\\' || c == '/' || c == '\u2028' || c == '\u2029') {
                sb.append(JSONObject.quote(s))
                return
            }
        }
        sb.append('"').append(s).append('"')
    }

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

    /** Папка заметки (0.28): номер из [NoteFolders]; пусто — без папки. */
    var folder: String = ""

    /** Закреплена — стоит в списке сверху (0.28). */
    var pinned: Boolean = false

    /** Разлиновка листа (0.30): [NotePaper.LINES] — в линейку (как было), [NotePaper.GRID] — в клетку, [NotePaper.NONE] — без. */
    var paper: String = NotePaper.LINES

    /**
     * Раскладка листа, по которой лежат рисунки (0.30): 1 — до 0.30 (название и дата другой высоты, строки не по клеткам) —
     * при первом открытии рисунки сдвигаются вслед за своими строками ([NoteActivity]); 2 — лист по клеткам.
     */
    var layoutV: Int = 2

    /** Весь текст заметки одной строкой — для поиска. */
    val searchText: String
        get() = buildString {
            append(title)
            for (b in blocks) when (b) {
                is Block.Para -> append('\n').append(b.text)
                is Block.Task -> append('\n').append(b.text)
                else -> {}
            }
        }

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
        o.put("v", layoutV)
        o.put("id", id)
        o.put("title", title)
        o.put("created", created)
        o.put("updated", updated)
        o.put("caret", caret)
        o.put("caretOff", caretOffset)
        if (folder.isNotEmpty()) o.put("folder", folder)
        if (pinned) o.put("pinned", true)
        if (paper != NotePaper.LINES) o.put("paper", paper)
        val bl = JSONArray()
        for (b in blocks) {
            val j = JSONObject()
            when (b) {
                is Block.Para -> { j.put("t", "p"); j.put("text", b.text); if (b.style.isNotEmpty()) j.put("st", b.style); lineJson(j, b) }
                is Block.Task -> { j.put("t", "task"); j.put("text", b.text); j.put("done", b.done); lineJson(j, b) }
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

    /**
     * Текст файла записи (0.27.6): то же, что [toJson], но рисунки — готовыми строками [Drawing.json] (переводятся в текст один
     * раз, пока не менялись). Раньше каждая точка каждого рисунка заново шла через JSONArray — после каждого штриха.
     */
    fun toJsonString(): String {
        val sb = StringBuilder(4096 + drawings.size * 400)
        sb.append("{\"v\":").append(layoutV).append(",\"id\":").append(JSONObject.quote(id))
        sb.append(",\"title\":").append(JSONObject.quote(title))
        sb.append(",\"created\":").append(created).append(",\"updated\":").append(updated)
        sb.append(",\"caret\":").append(caret).append(",\"caretOff\":").append(caretOffset)
        if (folder.isNotEmpty()) sb.append(",\"folder\":").append(JSONObject.quote(folder))
        if (pinned) sb.append(",\"pinned\":true")
        if (paper != NotePaper.LINES) sb.append(",\"paper\":").append(JSONObject.quote(paper))
        sb.append(",\"blocks\":[")
        for ((i, b) in blocks.withIndex()) {
            if (i > 0) sb.append(',')
            when (b) {
                is Block.Para -> {
                    sb.append("{\"t\":\"p\",\"text\":").append(JSONObject.quote(b.text))
                    if (b.style.isNotEmpty()) sb.append(",\"st\":").append(JSONObject.quote(b.style))
                    lineJson(sb, b); sb.append('}')
                }
                is Block.Task -> {
                    sb.append("{\"t\":\"task\",\"text\":").append(JSONObject.quote(b.text)).append(",\"done\":").append(b.done)
                    lineJson(sb, b); sb.append('}')
                }
                is Block.Image -> {
                    sb.append("{\"t\":\"img\",\"file\":").append(JSONObject.quote(b.file)).append(",\"w\":").append(b.width).append(",\"h\":").append(b.height)
                    if (b.wf > 0f) sb.append(",\"wf\":").append(b.wf.toDouble())
                    if (b.xa > 0f) sb.append(",\"xa\":").append(b.xa.toDouble())
                    sb.append('}')
                }
            }
        }
        sb.append("],\"drawings\":[")
        for ((i, d) in drawings.withIndex()) {
            if (i > 0) sb.append(',')
            sb.append(d.json())
        }
        sb.append("]}")
        return sb.toString()
    }

    /** Отступ и оформление строки — в JSON только если есть (строка без них — как до 0.30). */
    private fun lineJson(j: JSONObject, l: TextLine) {
        if (l.indent > 0) j.put("in", l.indent)
        if (l.spans.isNotEmpty()) j.put("sp", Rich.jsonArray(l.spans))
    }

    private fun lineJson(sb: StringBuilder, l: TextLine) {
        if (l.indent > 0) sb.append(",\"in\":").append(l.indent)
        if (l.spans.isNotEmpty()) { sb.append(",\"sp\":"); Rich.json(sb, l.spans) }
    }

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
                val text = j.optString("text")
                val indent = j.optInt("in").coerceIn(0, ParaStyle.MAX_INDENT)
                when (j.optString("t")) {
                    "task" -> blocks += Block.Task(text, j.optBoolean("done"), indent, Rich.parse(j.optJSONArray("sp"), text.length))
                    "img" -> blocks += Block.Image(
                        j.optString("file"), j.optInt("w"), j.optInt("h"),
                        j.optDouble("wf", 0.0).toFloat(), j.optDouble("xa", 0.0).toFloat(),
                    )
                    else -> blocks += Block.Para(
                        text, j.optString("st").takeIf { it in ParaStyle.ALL } ?: ParaStyle.BODY, indent,
                        Rich.parse(j.optJSONArray("sp"), text.length),
                    )
                }
            }
            if (blocks.isEmpty()) blocks += Block.Para("")
            val drawings = ArrayList<Drawing>()
            val dr = o.optJSONArray("drawings") ?: JSONArray()
            for (i in 0 until dr.length()) {
                val j = dr.getJSONObject(i)
                val pa = j.optJSONArray("pts") ?: JSONArray()
                val pts = FloatArray(pa.length()) { pa.optDouble(it).toFloat() }
                drawings += Drawing(j.optString("kind", "pen"), j.optInt("color"), j.optDouble("w", 3.0).toFloat(), pts).apply {
                    j.optString("id").takeIf { it.isNotEmpty() }?.let { id = it } // с 0.27.6 номер рисунка тоже в файле
                }
            }
            return Note(
                id = o.getString("id"),
                title = o.optString("title", "Запись"),
                created = o.optLong("created"),
                updated = o.optLong("updated"),
                blocks = blocks,
                drawings = drawings,
                caret = o.optInt("caret", blocks.size - 1).coerceIn(0, blocks.size - 1),
            ).apply {
                caretOffset = o.optInt("caretOff", -1)
                folder = o.optString("folder")
                pinned = o.optBoolean("pinned")
                paper = o.optString("paper", NotePaper.LINES).takeIf { it in NotePaper.ALL } ?: NotePaper.LINES
                layoutV = o.optInt("v", 1)
            }
        }
    }
}

/** Разлиновка листа заметки (0.30): шаг линейки и клетки — строка текста. */
object NotePaper {
    const val NONE = "none"
    const val LINES = "lines"
    const val GRID = "grid"
    val ALL = listOf(NONE, LINES, GRID)

    /**
     * Для новых заметок («Для всех новых заметок» в окне «Разлиновка»); пока не выбирали — без разметки, как на айфоне (0.31;
     * выбор 0.30 — под старым ключом — не в счёт: тогда лист был другим).
     */
    fun default(ctx: Context): String =
        ctx.getSharedPreferences("notes", Context.MODE_PRIVATE).getString("paper31", NONE)?.takeIf { it in ALL } ?: NONE

    fun setDefault(ctx: Context, p: String) = ctx.getSharedPreferences("notes", Context.MODE_PRIVATE).edit().putString("paper31", p).apply()

    fun label(p: String) = when (p) { NONE -> "Без разметки"; GRID -> "В клетку"; else -> "В линейку" }
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

    fun save(note: Note) = write(note.id, prepare(note))

    /** Текст файла записи (главный поток: запись не должна меняться, пока его собирают). */
    fun prepare(note: Note): String = note.toJsonString() // время правки ставит лист заметки (0.28), не сохранение


    /** Записать готовый текст (можно в фоне). */
    fun write(id: String, text: String) {
        val tmp = File(dir, "$id.json.tmp")
        tmp.writeText(text)
        val target = File(dir, "$id.json")
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

/** Папка заметок (0.28): номер, название. */
class NoteFolder(val id: String, var name: String)

/** Папки заметок — один файл `note_folders.json` рядом с заметками. */
class NoteFolders(context: Context) {
    private val file = File(context.filesDir, "note_folders.json")

    fun list(): List<NoteFolder> = runCatching {
        val a = JSONArray(file.readText())
        (0 until a.length()).map { a.getJSONObject(it).let { o -> NoteFolder(o.getString("id"), o.optString("name")) } }
    }.getOrDefault(emptyList())

    private fun write(all: List<NoteFolder>) {
        val a = JSONArray()
        for (f in all) a.put(JSONObject().put("id", f.id).put("name", f.name))
        val tmp = File(file.path + ".tmp")
        tmp.writeText(a.toString())
        if (!tmp.renameTo(file)) { file.writeText(a.toString()); tmp.delete() }
    }

    fun add(name: String): NoteFolder = NoteFolder(Ids.next(), name).also { write(list() + it) }

    fun rename(id: String, name: String) = write(list().map { if (it.id == id) NoteFolder(id, name) else it })

    /** Удалить папку: заметки из неё остаются, просто без папки. */
    fun delete(id: String, store: NoteStore) {
        write(list().filter { it.id != id })
        for (n in store.list()) if (n.folder == id) { n.folder = ""; store.save(n) }
    }
}
