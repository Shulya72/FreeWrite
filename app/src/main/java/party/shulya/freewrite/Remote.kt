package party.shulya.freewrite

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Запрос к серверу общей доски (в тестах — сервер в памяти). */
interface SyncTransport {
    fun call(req: JSONObject): JSONObject
}

/** Ошибка сервера: код HTTP, короткий код и сообщение по-русски (как его прислал сервер). */
class SyncException(val status: Int, val code: String, message: String) : Exception(message)

/** Сервер в Яндекс Облаке: POST JSON на адрес функции. */
class HttpTransport(private val url: String) : SyncTransport {
    override fun call(req: JSONObject): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15_000
            c.readTimeout = 60_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val bytes = req.toString().toByteArray(Charsets.UTF_8)
            c.setFixedLengthStreamingMode(bytes.size)
            c.outputStream.use { it.write(bytes) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            val o = runCatching { JSONObject(text) }.getOrNull()
                ?: throw SyncException(code, "http", "Сервер ответил $code")
            if (code !in 200..299) throw SyncException(code, o.optString("error"), o.optString("message", "Ошибка $code"))
            return o
        } finally {
            c.disconnect()
        }
    }
}

/**
 * Общая доска на сервере: что отправить, как влить полученное, обмен по частям.
 *
 * Правила те же, что у обмена файлом (BoardShare.merge): у каждого предмета номер и отметка правки,
 * побеждает более поздняя правка, удаление — тоже правка. Сервер нумерует принятые правки (seq):
 * приложение спрашивает «что нового после N» и помнит N (BoardSync.remoteSeq). Свои правки
 * отправляются по порядку отметок; всё, что не позже BoardSync.pushedRev, уже на сервере.
 */
object Remote {
    const val API = 1
    const val CHUNK = 1 shl 20
    const val MAX_BATCH = 1000
    const val MAX_BATCH_BYTES = 1_500_000
    private const val MAX_PULL_PAGES = 200

    /** Правка для сервера: data = null — удаление. */
    class Change(val id: String, val rev: String, val kind: String, val data: String?)

    /** Правка с сервера (author — имя того, кто правил, если он представился). */
    class Row(val id: String, val rev: String, val kind: String, val data: String?, val seq: Long, val author: String = "")

    /** Номер планшета из отметки правки. */
    fun deviceOf(rev: String): String = rev.substringAfterLast('.', "")

    /** Время правки из отметки (мс). */
    fun timeOf(rev: String): Long = rev.substringBefore('.').toLongOrNull() ?: 0L

    class Pulled(val rows: List<Row>, val next: Long, val seq: Long, val title: String)

    // ---------------------------------------------------------------- что отправить

    fun statusJson(s: BoardStatus): String =
        JSONObject().put("id", s.id).put("name", s.name).put("color", s.color)
            .also { if (s.group != BoardStatus.ID) it.put("g", s.group) }.toString()

    /** Свои правки, которых ещё нет на сервере, — по порядку отметок. Доска должна быть отмечена (stamp). */
    fun pending(board: Board): List<Change> {
        val s = board.sync
        val after = s.pushedRev
        val out = ArrayList<Change>()
        fun add(id: String, kind: String, data: () -> String) {
            val e = s.objs[id] ?: return
            if (e.rev.isEmpty() || e.rev <= after || s.seenRemote[id] == e.rev) return
            out += Change(id, e.rev, kind, data())
        }
        add(Board.TITLE_ID, "meta") { JSONObject().put("title", board.title).toString() }
        add(Board.ORDER_ID, "meta") { JSONObject().put("order", JSONArray(board.statuses.map { it.id })).toString() }
        for (st in board.statuses) add(st.id, "status") { statusJson(st) }
        for (d in board.strokes) add(d.id, "stroke") { d.json() }
        for (t in board.texts) add(t.id, "text") { Board.textJson(t) }
        for (m in board.images) add(m.id, "image") { Board.imageJson(m) }
        for (k in board.stickers) add(k.id, "sticker") { Board.stickerJson(k) }
        for (f in board.fills) add(f.id, "fill") { f.json() }
        for (c in board.callouts) add(c.id, "callout") { Board.calloutJson(c) }
        for (fr in board.frames) add(fr.id, "frame") { Board.frameJson(fr) }
        for (r in board.rows) add(r.id, "row") { Board.rowJson(r) }
        for ((id, r) in s.tomb) {
            if (r.isNotEmpty() && r > after && s.seenRemote[id] != r) out += Change(id, r, "", null)
        }
        out.sortBy { it.rev }
        return out
    }

    /** У предметов без отметки (пришли файлом без истории) — своя отметка, чтобы их можно было отправить. */
    fun ensureRevs(board: Board, device: String) {
        val s = board.sync
        val now = System.currentTimeMillis()
        for ((id, e) in s.objs.entries.toList()) if (e.rev.isEmpty()) s.objs[id] = BoardSync.Entry(e.hash, s.next(device, now))
    }

    /** Разбить на запросы: не больше 1000 правок и ~1,5 МБ в одном. */
    fun batches(changes: List<Change>): List<List<Change>> {
        val out = ArrayList<List<Change>>()
        var cur = ArrayList<Change>()
        var size = 0
        for (c in changes) {
            val sz = (c.data?.length ?: 0) + c.id.length + 64
            if (cur.isNotEmpty() && (cur.size >= MAX_BATCH || size + sz > MAX_BATCH_BYTES)) {
                out += cur; cur = ArrayList(); size = 0
            }
            cur += c; size += sz
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    // ---------------------------------------------------------------- как влить полученное

    /**
     * Влить правки с сервера в доску (в главном потоке). Свои правки новее — остаются (уйдут на сервер).
     * Возвращает, сколько предметов изменилось.
     */
    fun apply(board: Board, rows: List<Row>, authors: MutableMap<String, Int>? = null): Int {
        val s = board.sync
        val take = LinkedHashMap<String, Row>()
        for (r in rows) {
            s.observe(r.rev)
            if (r.author.isNotBlank()) deviceOf(r.rev).takeIf { it.isNotEmpty() }?.let { s.members[it] = r.author }
            val lr = s.rev(r.id)
            if (lr != null && r.rev <= lr) {
                if (r.rev == lr) s.seenRemote[r.id] = r.rev
                continue
            }
            val prev = take[r.id]
            if (prev == null || r.rev > prev.rev) take[r.id] = r
        }
        if (take.isEmpty()) return 0
        val before = board.entryHashes()
        for (r in take.values) runCatching { applyOne(board, r) }.onFailure {
            android.util.Log.w("FreeWrite", "remote row ${r.kind}", it)
        }
        val after = board.entryHashes()
        var n = 0
        for (r in take.values) {
            s.seenRemote[r.id] = r.rev
            val who = r.author.ifBlank { "коллега" }
            if (r.data == null) {
                s.objs.remove(r.id)
                s.tomb[r.id] = r.rev
                if (before[r.id] != null && after[r.id] == null) { n++; authors?.merge(who, 1, Int::plus) }
            } else {
                val h = after[r.id] ?: continue
                s.objs[r.id] = BoardSync.Entry(h, r.rev)
                s.tomb.remove(r.id)
                if (!r.id.startsWith("~") && before[r.id] != h) { n++; authors?.merge(who, 1, Int::plus) }
            }
        }
        return n
    }

    private fun <T> put(list: ArrayList<T>, obj: T, id: (T) -> String) {
        val k = id(obj)
        val i = list.indexOfFirst { id(it) == k }
        if (i >= 0) list[i] = obj else list += obj
    }

    private fun removeById(board: Board, id: String) {
        board.strokes.removeAll { it.id == id }
        board.texts.removeAll { it.id == id }
        board.images.removeAll { it.id == id }
        board.stickers.removeAll { it.id == id }
        board.fills.removeAll { it.id == id }
        board.callouts.removeAll { it.id == id }
        board.frames.removeAll { it.id == id }
        board.rows.removeAll { it.id == id }
        if (board.statuses.size > 1) board.statuses.removeAll { it.id == id }
    }

    private fun applyOne(board: Board, r: Row) {
        val data = r.data
        if (r.id == Board.TITLE_ID) {
            if (data != null) JSONObject(data).optString("title").takeIf { it.isNotEmpty() }?.let { board.title = it }
            return
        }
        if (r.id == Board.ORDER_ID) {
            if (data == null) return
            val a = JSONObject(data).optJSONArray("order") ?: return
            val pos = HashMap<String, Int>()
            for (i in 0 until a.length()) pos[a.optString(i)] = i
            val sorted = board.statuses.withIndex()
                .sortedWith(compareBy({ pos[it.value.id] ?: Int.MAX_VALUE }, { it.index })).map { it.value }
            board.statuses.clear(); board.statuses.addAll(sorted)
            return
        }
        if (data == null) {
            removeById(board, r.id)
            return
        }
        val j = JSONObject(data)
        when (r.kind) {
            "status" -> put(board.statuses, Board.parseStatus(j).let { BoardStatus(r.id, it.name, it.color, it.group) }) { it.id }
            "stroke" -> put(board.strokes, Board.parseDrawing(j).also { it.id = r.id }) { it.id }
            "text" -> put(board.texts, Board.parseText(j).also { it.id = r.id }) { it.id }
            "image" -> put(board.images, Board.parseImage(j).also { it.id = r.id }) { it.id }
            "sticker" -> put(board.stickers, Board.parseSticker(j).also { it.id = r.id }) { it.id }
            "fill" -> Board.parseFill(j)?.also { it.id = r.id }?.let { f -> put(board.fills, f) { it.id } }
            "callout" -> {
                val def = board.statuses.firstOrNull { it.group == BoardStatus.CON }?.id ?: board.statuses.firstOrNull()?.id ?: "s0"
                put(board.callouts, Board.parseCallout(j, def).also { it.id = r.id }) { it.id }
            }
            "frame" -> put(board.frames, Board.parseFrame(j).also { it.id = r.id }) { it.id }
            "row" -> {
                val def = board.statuses.firstOrNull { it.group == BoardStatus.ID }?.id ?: "s0"
                put(board.rows, Board.parseRow(j, def).also { it.id = r.id }) { it.id }
            }
            else -> {} // вид из будущей версии — пропускаем
        }
    }

    // ---------------------------------------------------------------- запросы

    private fun req(op: String, board: String? = null, key: String? = null): JSONObject =
        JSONObject().put("op", op).also { if (board != null) it.put("board", board); if (key != null) it.put("key", key) }

    fun ping(t: SyncTransport): JSONObject = t.call(req("ping"))

    /** Выложить доску: (ключ, код-приглашение). */
    fun create(t: SyncTransport, boardId: String, title: String, device: String): Pair<String, String> {
        val o = t.call(req("create", boardId).put("title", title).put("device", device))
        return o.getString("key") to o.getString("invite")
    }

    class Joined(val boardId: String, val key: String, val title: String, val invite: String, val seq: Long)

    fun join(t: SyncTransport, invite: String): Joined {
        val o = t.call(req("join").put("invite", invite))
        return Joined(o.getString("board"), o.getString("key"), o.optString("title"), o.optString("invite", invite), o.optLong("seq"))
    }

    /** Отправить правки пачками. Возвращает отметку последней отправленной правки (или null). */
    fun push(t: SyncTransport, boardId: String, key: String, device: String, changes: List<Change>,
             author: String = "", onBatch: (String) -> Unit = {}): String? {
        var last: String? = null
        for (b in batches(changes)) {
            val arr = JSONArray()
            for (c in b) {
                val o = JSONObject().put("id", c.id).put("rev", c.rev).put("k", c.kind)
                if (c.data == null) o.put("x", 1) else o.put("d", c.data)
                arr.put(o)
            }
            t.call(req("push", boardId, key).put("device", device).put("author", author).put("changes", arr))
            last = b.last().rev
            onBatch(last)
        }
        return last
    }

    /** Получить все правки после since (по страницам). */
    fun pull(t: SyncTransport, boardId: String, key: String, since: Long): Pulled {
        val rows = ArrayList<Row>()
        var cur = since
        var seq = since
        var title = ""
        for (page in 0 until MAX_PULL_PAGES) {
            val o = t.call(req("pull", boardId, key).put("since", cur))
            seq = o.optLong("seq")
            title = o.optString("title")
            val a = o.optJSONArray("changes") ?: JSONArray()
            for (i in 0 until a.length()) {
                val c = a.getJSONObject(i)
                rows += Row(c.getString("id"), c.getString("rev"), c.optString("k"),
                    if (c.optInt("x") == 1) null else c.optString("d"), c.optLong("s"), c.optString("a"))
            }
            cur = maxOf(cur, o.optLong("next", cur))
            if (!o.optBoolean("more")) break
        }
        return Pulled(rows, cur, seq, title)
    }

    // ---------------------------------------------------------------- картинки

    /** Какие из картинок уже есть на сервере. */
    fun imagesOnServer(t: SyncTransport, boardId: String, key: String, files: List<String>): Set<String> {
        val have = HashSet<String>()
        for (part in files.chunked(500)) {
            val a = t.call(req("img_has", boardId, key).put("files", JSONArray(part))).optJSONArray("have") ?: continue
            for (i in 0 until a.length()) have += a.optString(i)
        }
        return have
    }

    /** Выгрузить картинку частями по 1 МБ. */
    fun uploadImage(t: SyncTransport, boardId: String, key: String, file: File) {
        val size = file.length()
        require(size > 0) { "пустая картинка" }
        val parts = ((size + CHUNK - 1) / CHUNK).toInt()
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(CHUNK)
            for (p in 0 until parts) {
                var n = 0
                while (n < CHUNK) {
                    val r = input.read(buf, n, CHUNK - n)
                    if (r < 0) break
                    n += r
                }
                val data = Base64.encodeToString(buf, 0, n, Base64.NO_WRAP)
                val o = t.call(req("img_put", boardId, key).put("file", file.name).put("part", p)
                    .put("parts", parts).put("size", size).put("data", data))
                if (o.optBoolean("exists")) return
            }
        }
    }

    /** Скачать картинку целиком в target (сначала во временный файл). */
    fun downloadImage(t: SyncTransport, boardId: String, key: String, name: String, target: File) {
        val tmp = File(target.parentFile, target.name + ".part")
        try {
            tmp.outputStream().buffered().use { out ->
                var parts = 1
                var p = 0
                while (p < parts) {
                    val o = t.call(req("img_get", boardId, key).put("file", name).put("part", p))
                    parts = o.optInt("parts", 1)
                    out.write(Base64.decode(o.getString("data"), Base64.NO_WRAP))
                    p++
                }
            }
            if (!tmp.renameTo(target)) throw java.io.IOException("не удалось сохранить картинку")
        } finally {
            tmp.delete()
        }
    }

    /** Код для показа: ABCD-EFGH. */
    fun prettyInvite(code: String): String = if (code.length == 8) code.substring(0, 4) + "-" + code.substring(4) else code
}
