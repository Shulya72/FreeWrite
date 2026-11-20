package party.shulya.freewrite

import android.graphics.Paint
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LineHeightSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import org.json.JSONArray

/**
 * Оформление куска строки (0.30, «Aa» как в Заметках на айфоне): [start, end) в знаках строки, вид [kind] — жирный, курсив,
 * подчёркнутый, зачёркнутый, маркер ([value] — цвет), цвет текста ([value]), шрифт с засечками.
 */
class TextSpan(var start: Int, var end: Int, val kind: String, val value: Int = 0) {
    fun copy() = TextSpan(start, end, kind, value)
    override fun equals(other: Any?) = other is TextSpan && other.start == start && other.end == end && other.kind == kind && other.value == value
    override fun hashCode() = ((start * 31 + end) * 31 + kind.hashCode()) * 31 + value
}

/** Строка текста записи: абзац или пункт чек-листа — с отступом и оформлением кусков. */
interface TextLine {
    var text: String
    var indent: Int
    val spans: MutableList<TextSpan>
}

/** Стили абзаца (0.30): «Название», «Заголовок», «Подзаголовок», «Основной», моноширинный, цитата, списки. */
object ParaStyle {
    const val BODY = ""
    const val TITLE = "title"
    const val H1 = "h1"
    const val H2 = "h2"
    const val MONO = "mono"
    const val QUOTE = "quote"
    const val BULLET = "bullet"
    const val DASH = "dash"
    const val NUM = "num"
    val ALL = listOf(BODY, TITLE, H1, H2, MONO, QUOTE, BULLET, DASH, NUM)

    fun isList(s: String) = s == BULLET || s == DASH || s == NUM

    /** Сколько клеток разлиновки занимает строка текста этого стиля: «Название» — две, остальное — одну. */
    fun cells(s: String) = if (s == TITLE) 2 else 1

    /** Размер шрифта (sp). */
    fun textSp(s: String) = when (s) {
        TITLE -> 30f
        H1 -> 24f
        H2 -> 20.5f
        MONO -> 17f
        else -> 19f
    }

    /** Enter в конце строки этого стиля: какой стиль у новой строки (списки и цитата продолжаются, заголовки — нет). */
    fun next(s: String) = if (isList(s) || s == QUOTE || s == MONO) s else BODY

    const val MAX_INDENT = 4
}

/** Работа с оформлением строк: правка текста, разрез, склейка, «Aa», запись в файл и показ. */
object Rich {
    const val B = "b"
    const val I = "i"
    const val U = "u"
    const val S = "s"
    const val HL = "hl"
    const val FC = "fc"
    const val SERIF = "serif"
    private val KINDS = setOf(B, I, U, S, HL, FC, SERIF)
    /** Виды, у которых есть значение (цвет): у одного куска — один цвет. */
    private val VALUED = setOf(HL, FC)

    /**
     * Текст строки стал другим: оформление сдвигается вместе с текстом. Изменённый кусок ищем по общему началу и концу.
     * Вставка в конце оформленного куска (и внутри) продолжает оформление — как на айфоне: дописанное — тем же жирным.
     */
    fun adjust(spans: MutableList<TextSpan>, old: String, new: String) {
        if (spans.isEmpty() || old == new) return
        var p = 0
        val max = minOf(old.length, new.length)
        while (p < max && old[p] == new[p]) p++
        var so = old.length; var sn = new.length
        while (so > p && sn > p && old[so - 1] == new[sn - 1]) { so--; sn-- }
        // В старом тексте заменён кусок [p, so), в новом на его месте [p, sn).
        val delta = sn - so
        val it = spans.iterator()
        while (it.hasNext()) {
            val s = it.next()
            s.start = map(s.start, p, so, sn, delta, isEnd = false)
            s.end = map(s.end, p, so, sn, delta, isEnd = true)
            if (s.end <= s.start) it.remove()
        }
        normalize(spans, new.length)
    }

    private fun map(x: Int, p: Int, so: Int, sn: Int, delta: Int, isEnd: Boolean): Int = when {
        x < p -> x
        x == p && so == p -> sn        // чистая вставка в точке x: кусок, что кончался здесь, растёт; что начинался — сдвигается
        x >= so -> x + delta
        else -> if (isEnd) sn else p   // внутри заменённого куска
    }

    /** Кусок строки [from, to) — оформление со сдвигом к началу (для разреза строки Enter-ом). */
    fun slice(spans: List<TextSpan>, from: Int, to: Int): MutableList<TextSpan> {
        val out = ArrayList<TextSpan>()
        for (s in spans) {
            val a = maxOf(s.start, from); val b = minOf(s.end, to)
            if (b > a) out += TextSpan(a - from, b - from, s.kind, s.value)
        }
        return out
    }

    /** Приклеить оформление строки, стоящей после [shift] знаков (склейка строк). */
    fun appendShifted(target: MutableList<TextSpan>, spans: List<TextSpan>, shift: Int, len: Int) {
        for (s in spans) target += TextSpan(s.start + shift, s.end + shift, s.kind, s.value)
        normalize(target, len)
    }

    /** Весь кусок [from, to) уже в этом виде (у вида с цветом — именно этого цвета, value = null — любого). */
    fun covered(spans: List<TextSpan>, kind: String, from: Int, to: Int, value: Int? = null): Boolean {
        if (to <= from) return false
        var x = from
        val list = spans.filter { it.kind == kind && (value == null || it.value == value) }.sortedBy { it.start }
        for (s in list) {
            if (s.start > x) return false
            if (s.end > x) x = s.end
            if (x >= to) return true
        }
        return x >= to
    }

    /** Убрать вид [kind] с куска [from, to) (куски по краям остаются). */
    fun clear(spans: MutableList<TextSpan>, kind: String, from: Int, to: Int) {
        val add = ArrayList<TextSpan>()
        val it = spans.iterator()
        while (it.hasNext()) {
            val s = it.next()
            if (s.kind != kind || s.end <= from || s.start >= to) continue
            it.remove()
            if (s.start < from) add += TextSpan(s.start, from, s.kind, s.value)
            if (s.end > to) add += TextSpan(to, s.end, s.kind, s.value)
        }
        spans += add
    }

    /** Поставить вид на кусок (у цвета — заменить прежний цвет). */
    fun set(spans: MutableList<TextSpan>, kind: String, from: Int, to: Int, len: Int, value: Int = 0) {
        if (to <= from) return
        if (kind in VALUED) clear(spans, kind, from, to)
        spans += TextSpan(from, to, kind, value)
        normalize(spans, len)
    }

    /** Ж/К/Ч/З и шрифт: весь кусок уже такой — снять, иначе — поставить. Возвращает: стало включено. */
    fun toggle(spans: MutableList<TextSpan>, kind: String, from: Int, to: Int, len: Int): Boolean {
        if (to <= from) return false
        return if (covered(spans, kind, from, to)) { clear(spans, kind, from, to); normalize(spans, len); false }
        else { set(spans, kind, from, to, len); true }
    }

    /** Обрезать по длине, выбросить пустые и незнакомые, склеить соседние одинаковые — запись в файл всегда одна и та же. */
    fun normalize(spans: MutableList<TextSpan>, len: Int) {
        val it = spans.iterator()
        while (it.hasNext()) {
            val s = it.next()
            s.start = s.start.coerceIn(0, len); s.end = s.end.coerceIn(0, len)
            if (s.end <= s.start || s.kind !in KINDS) it.remove()
        }
        if (spans.size < 2) return
        val merged = ArrayList<TextSpan>()
        for ((k, group) in spans.groupBy { it.kind to it.value }) {
            val list = group.sortedBy { it.start }
            var cur = TextSpan(list[0].start, list[0].end, k.first, k.second)
            for (s in list.drop(1)) {
                if (s.start <= cur.end) cur.end = maxOf(cur.end, s.end)
                else { merged += cur; cur = TextSpan(s.start, s.end, k.first, k.second) }
            }
            merged += cur
        }
        merged.sortWith(compareBy({ it.start }, { it.end }, { it.kind }, { it.value }))
        spans.clear(); spans += merged
    }

    fun copy(spans: List<TextSpan>): MutableList<TextSpan> = spans.mapTo(ArrayList()) { it.copy() }

    /** Слово у места [pos] строки: [начало, конец) — для «Aa» без выделения. Нет слова — вся строка. */
    fun wordAt(text: String, pos: Int): IntRange {
        if (text.isEmpty()) return 0 until 0
        fun w(c: Char) = c.isLetterOrDigit() || c == '-' || c == '_'
        var a = pos.coerceIn(0, text.length); var b = a
        while (a > 0 && w(text[a - 1])) a--
        while (b < text.length && w(text[b])) b++
        return if (b > a) a until b else 0 until text.length
    }

    // ---------------------------------------------------------------- файл

    /** В JSON строки: [[начало, конец, "вид"], [начало, конец, "hl", цвет]…]; пусто — ничего не пишется. */
    fun json(sb: StringBuilder, spans: List<TextSpan>) {
        sb.append('[')
        for ((i, s) in spans.withIndex()) {
            if (i > 0) sb.append(',')
            sb.append('[').append(s.start).append(',').append(s.end).append(",\"").append(s.kind).append('"')
            if (s.kind in VALUED) sb.append(',').append(s.value)
            sb.append(']')
        }
        sb.append(']')
    }

    fun jsonArray(spans: List<TextSpan>): JSONArray = JSONArray().apply {
        for (s in spans) put(JSONArray().put(s.start).put(s.end).put(s.kind).apply { if (s.kind in VALUED) put(s.value) })
    }

    fun parse(a: JSONArray?, len: Int): MutableList<TextSpan> {
        val out = ArrayList<TextSpan>()
        if (a == null) return out
        for (i in 0 until a.length()) {
            val s = a.optJSONArray(i) ?: continue
            out += TextSpan(s.optInt(0), s.optInt(1), s.optString(2), s.optInt(3))
        }
        normalize(out, len)
        return out
    }

    // ---------------------------------------------------------------- показ

    /**
     * Оформление на показываемый текст. [map] — где в показываемом тексте знак строки (у строки с курсором между ними вставлены
     * курсор и серое слово).
     */
    fun apply(sb: SpannableStringBuilder, spans: List<TextSpan>, map: (Int) -> Int = { it }) {
        for (s in spans) {
            val a = map(s.start); val b = if (s.end > s.start) map(s.end - 1) + 1 else a
            if (b <= a || b > sb.length) continue
            val what: Any = when (s.kind) {
                B -> StyleSpan(Typeface.BOLD)
                I -> StyleSpan(Typeface.ITALIC)
                U -> UnderlineSpan()
                S -> StrikethroughSpan()
                HL -> BackgroundColorSpan(s.value)
                FC -> ForegroundColorSpan(s.value)
                SERIF -> TypefaceSpan("serif")
                else -> continue
            }
            sb.setSpan(what, a, b, Spanned.SPAN_EXCLUSIVE_INCLUSIVE)
        }
    }
}

/**
 * Высота строки — ровно [cell] (одна или две клетки разлиновки), низ букв — на [under] выше линии клетки (0.30: каждая строка
 * лежит на линии, лист — сетка клеток). В отличие от `lineHeight`, высота у каждой строки абзаца, и у последней тоже.
 */
class CellHeight(private val cell: Int, private val under: Int) : LineHeightSpan {
    override fun chooseHeight(text: CharSequence, start: Int, end: Int, spanstartv: Int, lineHeight: Int, fm: Paint.FontMetricsInt) {
        val d = fm.descent
        fm.descent = d + under
        fm.ascent = fm.descent - cell
        fm.bottom = fm.descent
        fm.top = fm.ascent
    }
}
