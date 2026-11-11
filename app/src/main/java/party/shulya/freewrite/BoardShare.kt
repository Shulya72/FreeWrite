package party.shulya.freewrite

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Общая доска через файл.
 *
 * «Поделиться» — доска упаковывается в файл `.freewrite` (zip: `board.json` + `images/…`) и уходит
 * через Telegram, WhatsApp, почту. Коллега открывает файл в FreeWrite: новой доски у него ещё нет —
 * появится; есть — правки вливаются. По каждому предмету побеждает более поздняя правка, удаление —
 * тоже правка. Поэтому можно работать вдвоём и пересылать файл туда-обратно: ничего не затирается
 * целиком, теряется только одна из двух правок одного и того же предмета (остаётся поздняя).
 */
object BoardShare {
    const val EXT = ".freewrite"
    const val MIME = "application/octet-stream"
    private const val BOARD_ENTRY = "board.json"
    private const val MAX_JSON = 256L shl 20
    private val SAFE_NAME = Regex("[A-Za-z0-9_-][A-Za-z0-9._-]{0,119}")

    /** Посылка: доска строкой и картинки, которые положить в файл. */
    class Package(val boardId: String, val title: String, val json: String, val files: List<String>)

    class Stats {
        var added = 0
        var changed = 0
        var removed = 0
        var renamed = false
        val total get() = added + changed + removed
    }

    class Result(
        val boardId: String,
        val title: String,
        val isNew: Boolean,
        val stats: Stats,
        val items: Int,
        val imagesCopied: Int,
        val imagesMissing: Int,
    ) {
        fun message(): String {
            val sb = StringBuilder()
            if (isNew) {
                sb.append("Новая доска «").append(title).append("» добавлена")
                if (items > 0) sb.append(": предметов — ").append(items)
                sb.append('.')
            } else if (stats.total == 0 && !stats.renamed) {
                sb.append("Доска «").append(title).append("»: изменений нет — у вас уже всё есть.")
            } else {
                sb.append("Доска «").append(title).append("» обновлена: ")
                val parts = ArrayList<String>()
                if (stats.added > 0) parts += "новых — ${stats.added}"
                if (stats.changed > 0) parts += "изменено — ${stats.changed}"
                if (stats.removed > 0) parts += "удалено — ${stats.removed}"
                if (stats.renamed) parts += "новое название"
                sb.append(parts.joinToString(", ")).append('.')
            }
            if (imagesCopied > 0) sb.append("\nКартинок получено: ").append(imagesCopied).append('.')
            if (imagesMissing > 0) {
                sb.append("\nНе хватает картинок: ").append(imagesMissing)
                    .append(" — попросите прислать доску целиком, с картинками.")
            }
            return sb.toString()
        }
    }

    private fun imageDir(context: Context) = File(context.filesDir, "note_images").apply { mkdirs() }

    // ---------------------------------------------------------------- отправка

    /** Сколько весят картинки доски: все и те, что коллегам ещё не отправлялись (байт). */
    fun imageSizes(context: Context, board: Board): Pair<Long, Long> {
        val dir = imageDir(context)
        var all = 0L
        var fresh = 0L
        for (f in board.images.map { it.file }.distinct()) {
            val len = File(dir, f).length()
            all += len
            if (f !in board.sync.peerImages) fresh += len
        }
        return all to fresh
    }

    /** Подготовить посылку (быстро, в главном потоке). Доска должна быть отмечена ([Board.stamp]). */
    fun prepare(board: Board, onlyNew: Boolean): Package {
        val files = board.images.map { it.file }.distinct().filter { !onlyNew || it !in board.sync.peerImages }
        return Package(board.id, board.title, board.toJsonString(withCamera = true), files)
    }

    /** Записать файл `.freewrite` в папку для отправки. Долго (картинки) — не в главном потоке. */
    fun write(context: Context, p: Package, now: Long = System.currentTimeMillis()): File {
        val dir = File(context.cacheDir, "shares").apply { mkdirs() }
        dir.listFiles()?.forEach { if (now - it.lastModified() > 24 * 3600_000L) it.delete() } // старые посылки
        val stamp = SimpleDateFormat("dd.MM HH-mm", Locale("ru")).format(Date(now))
        val file = File(dir, safeTitle(p.title) + " " + stamp + EXT)
        val imgDir = imageDir(context)
        ZipOutputStream(BufferedOutputStream(FileOutputStream(file), 1 shl 16)).use { zip ->
            zip.putNextEntry(ZipEntry(BOARD_ENTRY))
            zip.write(p.json.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.setLevel(Deflater.NO_COMPRESSION) // JPEG уже сжат — не тратим время
            for (f in p.files) {
                val src = File(imgDir, f)
                if (!src.isFile || !SAFE_NAME.matches(f)) continue
                zip.putNextEntry(ZipEntry("images/$f"))
                src.inputStream().use { it.copyTo(zip, 1 shl 16) }
                zip.closeEntry()
            }
        }
        return file
    }

    /** Имя картинки без папок и служебных знаков (из файла или с сервера). */
    fun isSafeImageName(name: String) = SAFE_NAME.matches(name)

    /** Название для имени файла: без знаков, которые нельзя в именах файлов. */
    fun safeTitle(t: String): String =
        t.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_").trim().take(60).ifEmpty { "Доска" }

    /** Открыть «Поделиться» Android с готовым файлом (Telegram, WhatsApp, почта…). */
    fun send(activity: Activity, file: File, title: String) {
        val uri = FileProvider.getUriForFile(activity, activity.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Доска «$title» — FreeWrite")
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(Intent.createChooser(send, "Отправить доску «$title»"))
    }

    private fun mb(bytes: Long): String {
        val m = bytes / 1048576.0
        return if (m < 0.1) "меньше 0,1 МБ" else String.format(Locale("ru"), if (m < 10) "%.1f МБ" else "%.0f МБ", m)
    }

    /**
     * «Поделиться» целиком: сохранить (save — с отметкой правок), спросить «целиком / только новое»,
     * если есть картинки, упаковать в фоне и открыть выбор приложения.
     */
    fun offer(activity: Activity, board: Board, save: () -> Unit) {
        save()
        val (all, fresh) = imageSizes(activity, board)
        if (all == 0L || fresh >= all) {
            start(activity, board, onlyNew = false, save = save)
            return
        }
        AlertDialog.Builder(activity)
            .setTitle("Отправить доску «${board.title}»")
            .setItems(arrayOf(
                "Целиком, с картинками — ${mb(all)}\nесли у коллеги этой доски ещё нет",
                "Только правки и новые картинки — ${mb(fresh)}\nесли коллега уже открывал эту доску",
            )) { _, which -> start(activity, board, onlyNew = which == 1, save = save) }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun start(activity: Activity, board: Board, onlyNew: Boolean, save: () -> Unit) {
        val p = prepare(board, onlyNew)
        Toast.makeText(activity, "Готовлю файл доски…", Toast.LENGTH_SHORT).show()
        Thread {
            val r = runCatching { write(activity, p) }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                r.onSuccess { f ->
                    board.sync.peerImages += p.files
                    save()
                    runCatching { send(activity, f, p.title) }.onFailure {
                        Toast.makeText(activity, "Не открылось «Поделиться»: ${it.message}", Toast.LENGTH_LONG).show()
                    }
                }.onFailure {
                    Toast.makeText(activity, "Не удалось подготовить файл: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    // ---------------------------------------------------------------- приём

    /**
     * Прочитать файл доски. Картинки, которых ещё нет, сразу копируются в папку картинок
     * (имена картинок уникальны, одинаковое имя = та же картинка). Возвращает доску и сколько картинок скопировано.
     */
    fun read(context: Context, input: InputStream): Pair<Board, Int> {
        var board: Board? = null
        var copied = 0
        val dir = imageDir(context)
        ZipInputStream(BufferedInputStream(input, 1 shl 16)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory) continue
                val name = e.name
                if (name == BOARD_ENTRY) {
                    val o = JSONObject(readLimited(zip, MAX_JSON))
                    if (o.optString("kind") != "board" || o.optString("id").isEmpty()) throw IllegalArgumentException(NOT_BOARD)
                    board = Board.fromJson(o)
                } else if (name.startsWith("images/")) {
                    val f = name.removePrefix("images/")
                    if (!SAFE_NAME.matches(f)) continue
                    val target = File(dir, f)
                    if (target.exists()) continue
                    val tmp = File(dir, "$f.part")
                    try {
                        tmp.outputStream().use { zip.copyTo(it, 1 shl 16) }
                        if (tmp.renameTo(target)) copied++
                    } finally {
                        tmp.delete()
                    }
                }
            }
        }
        return (board ?: throw IllegalArgumentException(NOT_BOARD)) to copied
    }

    const val NOT_BOARD = "Это не файл доски FreeWrite"

    private fun readLimited(input: InputStream, limit: Long): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1 shl 16)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > limit) throw IllegalArgumentException("Файл доски слишком большой")
            out.write(buf, 0, n)
        }
        return out.toString("UTF-8")
    }

    /**
     * Открыть файл доски: новая доска — добавить, знакомая — влить правки. Сохраняет сразу.
     * Можно звать из фонового потока.
     */
    fun import(context: Context, input: InputStream): Result {
        val store = BoardStore(context)
        val (inc, copied) = read(context, input)
        val local = store.load(inc.id)
        val imgDir = imageDir(context)
        if (local == null) {
            adopt(inc)
            store.saveImported(inc)
            val missing = inc.images.map { it.file }.distinct().count { !File(imgDir, it).exists() }
            val items = inc.objectCount() - inc.statuses.size
            return Result(inc.id, inc.title, true, Stats(), items, copied, missing)
        }
        local.stamp(store.device) // свои несохранённые отметки — до слияния
        val st = merge(local, inc)
        store.saveImported(local)
        val missing = local.images.map { it.file }.distinct().count { !File(imgDir, it).exists() }
        return Result(local.id, local.title, false, st, local.objectCount() - local.statuses.size, copied, missing)
    }

    /**
     * Новая доска из файла: отметки правок — как у отправителя, отпечатки считаем сами
     * (чтобы первое же сохранение не приняло всё за свои правки).
     */
    fun adopt(b: Board) {
        val old = b.sync
        val s = BoardSync()
        b.forEachEntry { id, h -> s.objs[id] = BoardSync.Entry(h, old.objs[id]?.rev ?: "") }
        for ((id, r) in old.tomb) if (id !in s.objs) s.tomb[id] = r
        for (e in old.objs.values) s.observe(e.rev)
        for (r in old.tomb.values) s.observe(r)
        s.peerImages += b.images.map { it.file }
        b.sync = s
    }

    /**
     * Влить доску коллеги в свою. По каждому предмету — более поздняя правка (отметка rev):
     * новый — добавить, изменённый — заменить, удалённый — удалить. Свои предметы и порядок
     * остаются на месте, новые — сверху (в конце списков). Камера — своя.
     */
    fun merge(local: Board, inc: Board): Stats {
        val ls = local.sync
        val ins = inc.sync
        val before = local.entryHashes()
        val incHashes = inc.entryHashes()
        val take = HashSet<String>()
        val drop = HashMap<String, String>()
        val ids = HashSet<String>(incHashes.keys)
        ids += ins.tomb.keys
        for (id in ids) {
            val has = id in incHashes
            val ir = (if (has) ins.objs[id]?.rev else ins.tomb[id]) ?: ""
            val lr = ls.rev(id)
            if (lr != null && ir <= lr) continue // у нас правка не старше
            if (has) take += id else if (ir.isNotEmpty()) drop[id] = ir
        }

        val st = Stats()
        if (Board.TITLE_ID in take && inc.title != local.title) { local.title = inc.title; st.renamed = true }
        val all = before.keys
        mergeList(local.statuses, inc.statuses, { it.id }, take, drop, all)
        if (local.statuses.isEmpty()) local.statuses.addAll(inc.statuses.ifEmpty { Board.defaultStatuses() })
        if (Board.ORDER_ID in take) {
            val pos = HashMap<String, Int>()
            for ((i, s) in inc.statuses.withIndex()) pos[s.id] = i
            val sorted = local.statuses.withIndex().sortedWith(compareBy({ pos[it.value.id] ?: Int.MAX_VALUE }, { it.index })).map { it.value }
            local.statuses.clear(); local.statuses.addAll(sorted)
        }
        mergeList(local.strokes, inc.strokes, { it.id }, take, drop, all)
        mergeList(local.texts, inc.texts, { it.id }, take, drop, all)
        mergeList(local.images, inc.images, { it.id }, take, drop, all)
        mergeList(local.stickers, inc.stickers, { it.id }, take, drop, all)
        mergeList(local.fills, inc.fills, { it.id }, take, drop, all)
        mergeList(local.callouts, inc.callouts, { it.id }, take, drop, all)
        mergeList(local.frames, inc.frames, { it.id }, take, drop, all)
        mergeList(local.rows, inc.rows, { it.id }, take, drop, all)
        mergeList(local.tables, inc.tables, { it.id }, take, drop, all)
        mergeList(local.recs, inc.recs, { it.id }, take, drop, all)

        val after = local.entryHashes()
        for (id in take) {
            val h = after[id] ?: continue
            ls.objs[id] = BoardSync.Entry(h, ins.objs[id]?.rev ?: "")
            ls.tomb.remove(id)
            if (id.startsWith("~")) continue
            val b = before[id]
            if (b == null) st.added++ else if (b != h) st.changed++
        }
        for ((id, r) in drop) {
            ls.objs.remove(id)
            ls.tomb[id] = r
            if (!id.startsWith("~") && before[id] != null && after[id] == null) st.removed++
        }
        for (e in ins.objs.values) ls.observe(e.rev)
        for (r in ins.tomb.values) ls.observe(r)
        ls.peerImages += inc.images.map { it.file }
        return st
    }

    private fun <T> mergeList(
        local: ArrayList<T>, inc: List<T>, id: (T) -> String,
        take: Set<String>, drop: Map<String, String>, localAll: Set<String>,
    ) {
        val incMap = HashMap<String, T>(inc.size * 2 + 4)
        for (o in inc) incMap[id(o)] = o
        val out = ArrayList<T>(local.size + 8)
        val here = HashSet<String>(local.size * 2 + 4)
        for (o in local) {
            val k = id(o)
            here += k
            when {
                k in take -> out += incMap[k] ?: o
                k in drop -> {}
                else -> out += o
            }
        }
        for (o in inc) {
            val k = id(o)
            if (k in take && k !in here && k !in localAll) out += o
        }
        local.clear()
        local.addAll(out)
    }
}
