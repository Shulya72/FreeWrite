package party.shulya.freewrite

import android.content.Context
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Locale

/** Короткие случайные номера предметов (12 знаков: буквы и цифры без похожих 0/o, 1/l). */
object Ids {
    private const val ABC = "abcdefghijkmnpqrstuvwxyz23456789"
    private val rnd = SecureRandom()

    fun next(n: Int = 12): String {
        val c = CharArray(n)
        for (i in 0 until n) c[i] = ABC[rnd.nextInt(ABC.length)]
        return String(c)
    }
}

/**
 * Учёт правок доски для обмена с коллегами (общая доска через файл).
 *
 * У каждого предмета — отпечаток содержимого и отметка последней правки (rev). Отметка — «гибридные
 * часы»: время в миллисекундах, счётчик и номер планшета, например `1762500000000.0003.k7m2pq`.
 * Строки сравниваются по алфавиту = по времени. После приёма чужого файла часы не идут назад:
 * следующая своя правка всегда позже всего, что уже видели.
 *
 * При слиянии по каждому предмету побеждает более поздняя правка (удаление — тоже правка).
 */
class BoardSync {
    class Entry(val hash: Long, val rev: String)

    /** Предметы доски: номер → отпечаток и отметка правки. */
    val objs = HashMap<String, Entry>()

    /** Удалённые предметы: номер → когда удалён (чтобы удаление дошло до коллеги, а не «воскресло»). */
    val tomb = HashMap<String, String>()

    /** Картинки, которые у коллег уже есть (отправляли или получили) — «только новое» их не шлёт. */
    val peerImages = HashSet<String>()

    // ---- общая доска на сервере (0.10) ----

    /** Ключ доски на сервере; пусто — доска не на сервере. */
    var remoteKey = ""
    /** Код-приглашение для коллег. */
    var remoteInvite = ""
    /** До какого номера правки (seq сервера) правки уже получены. */
    var remoteSeq = 0L
    /** Все свои правки с отметкой не позже этой уже на сервере. */
    var pushedRev = ""
    /** Картинки, которые уже есть на сервере. */
    val uploadedImages = HashSet<String>()
    /** Участники общей доски: номер планшета → имя (из правок с сервера). */
    val members = HashMap<String, String>()

    /** Отметки правок, только что полученных с сервера (их не надо отправлять обратно). Не сохраняется. */
    val seenRemote = HashMap<String, String>()

    val isShared: Boolean get() = remoteKey.isNotEmpty()

    var clockMs = 0L
        private set
    var clockN = 0
        private set

    /** Новая отметка правки — позже всех прежних. */
    fun next(device: String, now: Long = System.currentTimeMillis()): String {
        if (now > clockMs) {
            clockMs = now; clockN = 0
        } else {
            clockN++
            if (clockN > 9999) { clockMs++; clockN = 0 }
        }
        return rev(clockMs, clockN, device)
    }

    /** Учесть чужую отметку: свои следующие правки будут позже неё. */
    fun observe(rev: String) {
        val ms = rev.substringBefore('.').toLongOrNull() ?: return
        val n = rev.substringAfter('.', "").substringBefore('.').toIntOrNull() ?: 0
        if (ms > clockMs || (ms == clockMs && n > clockN)) { clockMs = ms; clockN = n }
    }

    fun rev(id: String): String? = objs[id]?.rev ?: tomb[id]

    fun json(sb: StringBuilder) {
        sb.append("{\"ms\":").append(clockMs).append(",\"n\":").append(clockN).append(",\"o\":{")
        var first = true
        for ((id, e) in objs) {
            if (!first) sb.append(',')
            first = false
            sb.append(JSONObject.quote(id)).append(":[").append(e.hash).append(',').append(JSONObject.quote(e.rev)).append(']')
        }
        sb.append("},\"t\":{")
        first = true
        for ((id, r) in tomb) {
            if (!first) sb.append(',')
            first = false
            sb.append(JSONObject.quote(id)).append(':').append(JSONObject.quote(r))
        }
        sb.append("},\"pi\":[")
        first = true
        for (f in peerImages) {
            if (!first) sb.append(',')
            first = false
            sb.append(JSONObject.quote(f))
        }
        sb.append(']')
        if (members.isNotEmpty()) {
            sb.append(",\"mb\":{")
            first = true
            for ((d, n) in members) {
                if (!first) sb.append(',')
                first = false
                sb.append(JSONObject.quote(d)).append(':').append(JSONObject.quote(n))
            }
            sb.append('}')
        }
        if (isShared) {
            sb.append(",\"r\":{\"k\":").append(JSONObject.quote(remoteKey))
            sb.append(",\"i\":").append(JSONObject.quote(remoteInvite))
            sb.append(",\"s\":").append(remoteSeq)
            sb.append(",\"p\":").append(JSONObject.quote(pushedRev))
            sb.append(",\"u\":[")
            first = true
            for (f in uploadedImages) {
                if (!first) sb.append(',')
                first = false
                sb.append(JSONObject.quote(f))
            }
            sb.append("]}")
        }
        sb.append('}')
    }

    companion object {
        fun rev(ms: Long, n: Int, device: String): String = String.format(Locale.ROOT, "%013d.%04d.%s", ms, n, device)

        fun parse(o: JSONObject?): BoardSync {
            val s = BoardSync()
            if (o == null) return s
            s.clockMs = o.optLong("ms")
            s.clockN = o.optInt("n")
            o.optJSONObject("o")?.let { m ->
                val it = m.keys()
                while (it.hasNext()) {
                    val id = it.next()
                    val a = m.optJSONArray(id) ?: continue
                    s.objs[id] = Entry(a.optLong(0), a.optString(1, ""))
                }
            }
            o.optJSONObject("t")?.let { m ->
                val it = m.keys()
                while (it.hasNext()) { val id = it.next(); s.tomb[id] = m.optString(id, "") }
            }
            o.optJSONArray("pi")?.let { a -> for (i in 0 until a.length()) s.peerImages += a.optString(i) }
            o.optJSONObject("mb")?.let { m ->
                val it = m.keys()
                while (it.hasNext()) { val d = it.next(); s.members[d] = m.optString(d) }
            }
            o.optJSONObject("r")?.let { r ->
                s.remoteKey = r.optString("k", "")
                s.remoteInvite = r.optString("i", "")
                s.remoteSeq = r.optLong("s")
                s.pushedRev = r.optString("p", "")
                r.optJSONArray("u")?.let { a -> for (i in 0 until a.length()) s.uploadedImages += a.optString(i) }
            }
            return s
        }

        /** Номер этого планшета (6 знаков, создаётся один раз) — входит в отметки правок. */
        fun device(context: Context): String {
            val prefs = context.getSharedPreferences("sync", Context.MODE_PRIVATE)
            prefs.getString("device", null)?.let { return it }
            val d = Ids.next(6)
            prefs.edit().putString("device", d).apply()
            return d
        }
    }
}
