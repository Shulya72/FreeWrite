package party.shulya.freewrite

import android.content.Context
import org.json.JSONObject
import java.util.Locale

/** Короткие случайные номера предметов (12 знаков: буквы и цифры без похожих 0/o, 1/l). */
object Ids {
    private const val ABC = "abcdefghijkmnpqrstuvwxyz23456789"

    /**
     * Номера предметов (0.27.6). В 0.27.5 номера брались из ThreadLocalRandom ради скорости — а на Android он заготавливается
     * один раз при включении планшета (в «зиготе», общей заготовке всех приложений), и каждый запуск FreeWrite выдавал ту же
     * череду номеров заново. Новые предметы получали номера прежних: ведомость теряла свои схемы, удаление одного предмета
     * удаляло и «тёзку» на общей доске. Теперь основа — 64 случайных бита из SecureRandom, один раз на запуск, дальше —
     * счётчик, перемешанный SplitMix64: так же быстро, номера разные в каждом запуске и на каждом планшете.
     */
    private val state = java.util.concurrent.atomic.AtomicLong(java.security.SecureRandom().nextLong())

    private fun mix(): Long {
        var z = state.addAndGet(0x9E3779B97F4A7C15uL.toLong())
        z = (z xor (z ushr 30)) * 0xBF58476D1CE4E5B9uL.toLong()
        z = (z xor (z ushr 27)) * 0x94D049BB133111EBuL.toLong()
        return z xor (z ushr 31)
    }

    /** 12 знаков из 32 — 60 случайных бит. */
    fun next(n: Int = 12): String {
        val c = CharArray(n)
        var bits = 0L
        var left = 0
        for (i in 0 until n) {
            if (left < 5) { bits = mix(); left = 60 }
            c[i] = ABC[(bits and 31L).toInt()]
            bits = bits ushr 5
            left -= 5
        }
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
    val objs: HashMap<String, Entry> = LinkedHashMap() // порядок как в файле: сохранить → прочитать → сохранить даёт тот же файл

    /** Удалённые предметы: номер → когда удалён (чтобы удаление дошло до коллеги, а не «воскресло»). */
    val tomb: HashMap<String, String> = LinkedHashMap()

    /** Картинки, которые у коллег уже есть (отправляли или получили) — «только новое» их не шлёт. */
    val peerImages: HashSet<String> = LinkedHashSet()

    // ---- общая доска на сервере (0.10) ----

    /** Ключ доски на сервере; пусто — доска не на сервере. */
    var remoteKey = ""
    /** Код-приглашение для коллег. */
    var remoteInvite = ""
    /** До какого номера правки (seq сервера) правки уже получены. */
    var remoteSeq = 0L
    /** Все свои правки с отметкой не позже этой уже на сервере. */
    var pushedRev = ""
    /**
     * Какая версия приложения (versionCode) последней прочитала доску с сервера целиком (0 — до 0.25.2).
     * Меньше [Remote.appVersion] — при следующей синхронизации доска перечитывается целиком.
     */
    var readBy = 0
    /** Картинки, которые уже есть на сервере. */
    val uploadedImages: HashSet<String> = LinkedHashSet()
    /** Участники общей доски: номер планшета → имя (из правок с сервера). */
    val members: HashMap<String, String> = LinkedHashMap()

    /** Отметки правок, только что полученных с сервера (их не надо отправлять обратно). Не сохраняется. */
    val seenRemote: HashMap<String, String> = LinkedHashMap()

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

    fun json(sb: StringBuilder) = snap().json(sb)

    /**
     * Снимок учёта правок для записи в файл (0.32.6). Снимается в главном потоке за миллисекунды (только ссылки), а в текст
     * переводится уже в потоке записи: у общей доски записей тысячи, и раньше перевод в текст держал экран при каждом
     * сохранении и уходе с доски (отчёт 26.11: 1–1,3 с).
     */
    fun snap(): Snap {
        val oIds = arrayOfNulls<String>(objs.size); val oEnt = arrayOfNulls<Entry>(objs.size)
        var i = 0
        for ((id, e) in objs) { if (i >= oIds.size) break; oIds[i] = id; oEnt[i] = e; i++ }
        val tIds = arrayOfNulls<String>(tomb.size); val tRev = arrayOfNulls<String>(tomb.size)
        var j = 0
        for ((id, r) in tomb) { if (j >= tIds.size) break; tIds[j] = id; tRev[j] = r; j++ }
        return Snap(clockMs, clockN, oIds, oEnt, i, tIds, tRev, j, peerImages.toTypedArray(), members.entries.map { it.key to it.value },
            isShared, remoteKey, remoteInvite, remoteSeq, pushedRev, readBy, uploadedImages.toTypedArray())
    }

    /** Снимок [snap]: неизменяемый, переводится в текст в любом потоке. */
    class Snap internal constructor(
        private val ms: Long, private val n: Int,
        private val oIds: Array<String?>, private val oEnt: Array<Entry?>, private val oN: Int,
        private val tIds: Array<String?>, private val tRev: Array<String?>, private val tN: Int,
        private val peer: Array<String>, private val mb: List<Pair<String, String>>,
        private val shared: Boolean, private val key: String, private val invite: String, private val seq: Long,
        private val pushed: String, private val readBy: Int, private val uploaded: Array<String>,
    ) {
        /** Сколько символов примерно займёт (для запаса буфера). */
        val sizeHint: Int get() = oN * 48 + tN * 44 + 256

        fun json(sb: StringBuilder) {
            sb.append("{\"ms\":").append(ms).append(",\"n\":").append(n).append(",\"o\":{")
            for (i in 0 until oN) {
                if (i > 0) sb.append(',')
                val e = oEnt[i]!!
                JsonNum.quote(sb, oIds[i]!!); sb.append(":[").append(e.hash).append(','); JsonNum.quote(sb, e.rev); sb.append(']')
            }
            sb.append("},\"t\":{")
            for (i in 0 until tN) {
                if (i > 0) sb.append(',')
                JsonNum.quote(sb, tIds[i]!!); sb.append(':'); JsonNum.quote(sb, tRev[i]!!)
            }
            sb.append("},\"pi\":[")
            for ((i, f) in peer.withIndex()) {
                if (i > 0) sb.append(',')
                sb.append(JSONObject.quote(f))
            }
            sb.append(']')
            if (mb.isNotEmpty()) {
                sb.append(",\"mb\":{")
                for ((i, p) in mb.withIndex()) {
                    if (i > 0) sb.append(',')
                    sb.append(JSONObject.quote(p.first)).append(':').append(JSONObject.quote(p.second))
                }
                sb.append('}')
            }
            if (shared) {
                sb.append(",\"r\":{\"k\":").append(JSONObject.quote(key))
                sb.append(",\"i\":").append(JSONObject.quote(invite))
                sb.append(",\"s\":").append(seq)
                sb.append(",\"p\":").append(JSONObject.quote(pushed))
                if (readBy > 0) sb.append(",\"v\":").append(readBy)
                sb.append(",\"u\":[")
                for ((i, f) in uploaded.withIndex()) {
                    if (i > 0) sb.append(',')
                    sb.append(JSONObject.quote(f))
                }
                sb.append("]}")
            }
            sb.append('}')
        }
    }

    /**
     * Убрать отметки удалений, которые уже не нужны (0.32.6). Отметка удаления нужна, пока удаление не дошло до сервера: иначе
     * коллега «воскресил» бы предмет. У общей доски сервер сам хранит, что предмет удалён (принимает только более поздние
     * правки), — дошедшие до него отметки (не позже [pushedRev]) лишние. Раньше они копились без конца (и ещё раз — со всеми
     * удалениями с сервера при каждом обновлении приложения), а доска с каждым сохранением переписывала их в файл.
     * Ничего на доске от этого не меняется. Возвращает, сколько убрано.
     */
    fun pruneTombs(): Int {
        if (!isShared || pushedRev.isEmpty() || tomb.isEmpty()) return 0
        val before = tomb.size
        val it = tomb.entries.iterator()
        while (it.hasNext()) if (it.next().value <= pushedRev) it.remove()
        return before - tomb.size
    }

    /** Есть ли свои правки, которых ещё нет на сервере (быстро, без строк предметов). */
    fun hasPending(): Boolean {
        val after = pushedRev
        for ((id, e) in objs) if (e.rev.isNotEmpty() && e.rev > after && seenRemote[id] != e.rev) return true
        for ((id, r) in tomb) if (r.isNotEmpty() && r > after && seenRemote[id] != r) return true
        return false
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
                s.readBy = r.optInt("v")
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
