package party.shulya.freewrite

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * Сервер общей доски в памяти — те же правила, что у server/index.py (поздняя правка побеждает,
 * у каждой принятой правки свой номер seq, pull по 500 / 2 МБ, картинки частями).
 */
class FakeServer : SyncTransport {
    class Obj(val id: String, val rev: String, val kind: String, val deleted: Boolean, val body: String, val seq: Long)
    class B(val id: String, val key: String, var title: String, val invite: String) {
        var seq = 0L
        val objs = HashMap<String, Obj>()
    }

    val boards = HashMap<String, B>()
    val invites = HashMap<String, String>()
    val images = HashMap<String, Pair<Int, Long>>()
    val parts = HashMap<String, ByteArray>()
    var calls = 0
    var offline = false

    private fun err(status: Int, code: String, msg: String): Nothing = throw SyncException(status, code, msg)

    private fun auth(r: JSONObject): B {
        val b = boards[r.optString("board")] ?: err(404, "no_board", "Доски нет на сервере")
        if (b.key != r.optString("key")) err(403, "bad_key", "Нет доступа к доске")
        return b
    }

    override fun call(req: JSONObject): JSONObject {
        calls++
        if (offline) throw java.io.IOException("нет сети")
        val r = JSONObject(req.toString()) // как по сети: только JSON
        return when (r.getString("op")) {
            "ping" -> JSONObject().put("ok", true).put("api", 1)
            "create" -> {
                val id = r.getString("board")
                if (boards.containsKey(id)) err(409, "exists", "Доска уже есть на сервере")
                val invite = "WXYZ" + "%04d".format(boards.size).map { "ABCDEFGHJK"[it - '0'] }.joinToString("")
                val b = B(id, "key-" + id.take(8), r.optString("title"), invite)
                boards[id] = b
                invites[invite] = id
                JSONObject().put("board", id).put("key", b.key).put("invite", invite).put("seq", 0)
            }
            "join" -> {
                val code = r.getString("invite").uppercase().replace("-", "").replace(" ", "")
                val b = invites[code]?.let { boards[it] } ?: err(404, "no_invite", "Код не найден — проверьте, нет ли опечатки")
                JSONObject().put("board", b.id).put("key", b.key).put("title", b.title).put("invite", b.invite).put("seq", b.seq)
            }
            "push" -> {
                val b = auth(r)
                val a = r.getJSONArray("changes")
                require(a.length() <= 1000) { "больше 1000 правок" }
                var applied = 0
                for (i in 0 until a.length()) {
                    val c = a.getJSONObject(i)
                    val id = c.getString("id")
                    val rev = c.getString("rev")
                    val old = b.objs[id]
                    if (old != null && rev <= old.rev) continue
                    b.seq++
                    val del = c.optInt("x") == 1
                    val body = if (del) "" else c.getString("d")
                    b.objs[id] = Obj(id, rev, c.optString("k"), del, body, b.seq)
                    if (id == "~title" && !del) b.title = JSONObject(body).optString("title", b.title)
                    applied++
                }
                JSONObject().put("seq", b.seq).put("applied", applied).put("ignored", a.length() - applied)
            }
            "pull" -> {
                val b = auth(r)
                val since = r.optLong("since")
                val rows = b.objs.values.filter { it.seq > since }.sortedBy { it.seq }.take(500)
                val out = JSONArray()
                var size = 0
                var next = since
                for (o in rows) {
                    size += o.body.length + 96
                    if (out.length() > 0 && size > (2 shl 20)) break
                    val e = JSONObject().put("id", o.id).put("rev", o.rev).put("k", o.kind).put("s", o.seq)
                    if (o.deleted) e.put("x", 1) else e.put("d", o.body)
                    out.put(e)
                    next = o.seq
                }
                JSONObject().put("seq", b.seq).put("title", b.title).put("next", next).put("more", next < b.seq).put("changes", out)
            }
            "img_has" -> {
                auth(r)
                val f = r.getJSONArray("files")
                JSONObject().put("have", JSONArray((0 until f.length()).map { f.getString(it) }.filter { images.containsKey(it) }))
            }
            "img_put" -> {
                val b = auth(r)
                val name = r.getString("file")
                val part = r.getInt("part")
                val n = r.getInt("parts")
                if (part == 0 && images.containsKey(name)) return JSONObject().put("ok", true).put("exists", true)
                val raw = Base64.decode(r.getString("data"), Base64.NO_WRAP)
                require(raw.size <= Remote.CHUNK) { "часть больше 1 МБ" }
                parts["$name/$part"] = raw
                if (part == n - 1) images[name] = n to r.getLong("size")
                JSONObject().put("ok", true).also { b.hashCode() }
            }
            "img_get" -> {
                auth(r)
                val name = r.getString("file")
                val info = images[name] ?: err(404, "no_image", "Картинки нет на сервере")
                val raw = parts["$name/${r.optInt("part")}"]!!
                JSONObject().put("data", Base64.encodeToString(raw, Base64.NO_WRAP)).put("parts", info.first).put("size", info.second)
            }
            else -> err(400, "bad_op", "Неизвестный запрос")
        }
    }
}
