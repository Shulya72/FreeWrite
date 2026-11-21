package party.shulya.freewrite

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.LineHeightSpan
import android.text.style.MetricAffectingSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.EditText
import kotlin.math.max
import kotlin.math.min

/** Свойства абзаца в редакторе заметки (0.31): пункт чек-листа, стиль, отступ, отмечен ли пункт. */
class PAttr(var task: Boolean = false, var style: String = ParaStyle.BODY, var indent: Int = 0, var done: Boolean = false) {
    fun copy() = PAttr(task, style, indent, done)

    /** Строка после Enter: чек-лист, списки, цитата продолжаются (пункт — не отмечен), заголовки — обычным текстом. */
    fun next() = PAttr(task, if (task) ParaStyle.BODY else ParaStyle.next(style), indent, false)

    /** Пустая строка, которую Enter вставил над непустой: тот же список или чек-лист, иначе обычный текст. */
    fun above() = PAttr(task, if (listy) style else ParaStyle.BODY, indent, false)

    /** Чек-лист, список или цитата: Enter в пустом пункте и «Стереть» в его начале снимают их. */
    val listy: Boolean get() = task || ParaStyle.isList(style) || style == ParaStyle.QUOTE

    override fun equals(other: Any?) = other is PAttr && other.task == task && other.style == style && other.indent == indent && other.done == done
    override fun hashCode() = ((style.hashCode() * 31 + indent) * 2 + (if (task) 1 else 0)) * 2 + (if (done) 1 else 0)
}

/** Строка заметки для редактора и обратно: текст, оформление кусков, свойства абзаца. */
class ELine(val text: String, val spans: List<TextSpan>, val attr: PAttr) {
    override fun equals(other: Any?) = other is ELine && other.text == text && other.spans == spans && other.attr == attr
    override fun hashCode() = (text.hashCode() * 31 + spans.hashCode()) * 31 + attr.hashCode()
}

/**
 * Редактор заметки (0.31, Владимир: «заметки вообще не похожи на айфоновские», прислал видео с экрана айфона): кусок заметки —
 * одно сплошное поле текста, как в Заметках на айфоне. Курсор ставится куда угодно, выделение — через несколько строк,
 * «Вырезать / Скопировать / Вставить» — системные. Строка поля — абзац записи ([paras] — его свойства: чек-лист, стиль,
 * отступ); у пункта чек-листа слева кружок (касание — отметить), у списков — «•», «–», номер, у цитаты — черта.
 *
 * Enter продолжает список и чек-лист, в пустом пункте — снимает его (с отступом — сначала отступ); «Стереть» в начале
 * пункта — снимает список, иначе склеивает с верхней строкой. В первом куске первая строка — название (крупно, жирно).
 * Разлиновка: в клетку и в линейку каждая строка — ровно клетка ([Host.cellPx]), без разметки — своей высоты.
 *
 * Пустая последняя строка не получает оформления абзаца (так устроена разметка текста Android) — в неё ставится невидимый
 * знак [ZW]; при выгрузке ([lines]) он выбрасывается.
 */
@SuppressLint("ViewConstructor", "AppCompatCustomView")
class NoteEdit(context: Context, private val host: Host, val first: Boolean) : EditText(context) {

    interface Host {
        /** Высота клетки разлиновки (px); 0 — без разметки, строки своей высоты. */
        fun cellPx(): Int
        /** Сейчас поменяется текст (с позиции [start]). */
        fun beforeEdit(e: NoteEdit, start: Int)
        /** Текст поменялся (оформление абзацев уже обновлено). */
        fun afterEdit(e: NoteEdit)
        /** Коснулись кружка пункта [i] чек-листа. */
        fun onToggleDone(e: NoteEdit, i: Int)
        /** «Стереть» в самом начале куска: соединить с тем, что выше. true — сделано. */
        fun onBackspaceAtStart(e: NoteEdit): Boolean
        /** Курсор или выделение переехали. */
        fun onSelection(e: NoteEdit, start: Int, end: Int)
        /** Следующая правка — отдельным шагом «Отменить» (не вместе с набором букв). */
        fun newStep() {}
    }

    /** Свойства абзацев — по одному на строку поля. */
    val paras = ArrayList<PAttr>()

    private var ready = false
    private var loading = false
    private val dm = resources.displayMetrics
    private fun dp(v: Float) = v * dm.density
    private fun spPx(style: String) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, ParaStyle.textSp(style), dm)

    private val indentPx get() = dp(NoteActivity.INDENT_DP).toInt()
    private val listW get() = dp(30f).toInt()
    private val taskW get() = dp(34f).toInt()
    private val quoteW get() = dp(16f).toInt()
    private val under get() = dp(4f).toInt()

    init {
        background = null
        setPadding(0, 0, 0, 0)
        gravity = Gravity.TOP or Gravity.START
        includeFontPadding = false
        setLineSpacing(0f, 1f)
        setTextColor(Ui.INK)
        textSize = ParaStyle.textSp(ParaStyle.BODY)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        isSingleLine = false
        setHorizontallyScrolling(false)
        highlightColor = Color.argb(70, Color.red(Ui.ACCENT), Color.green(Ui.ACCENT), Color.blue(Ui.ACCENT))
        setHintTextColor(Ui.MUTED)
        if (Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        if (Build.VERSION.SDK_INT >= 29) textCursorDrawable = GradientDrawable().apply {
            setColor(Ui.ACCENT)
            setSize(dp(2f).toInt(), 1)
        }
        if (first) hint = "Название"
        addTextChangedListener(watcher())
        ready = true
        updateEmptyLook()
    }

    // ---------------------------------------------------------------- абзацы

    /** Начала строк поля. */
    fun starts(s: CharSequence = text): IntArray {
        var n = 1
        for (i in 0 until s.length) if (s[i] == '\n') n++
        val out = IntArray(n)
        var k = 1
        for (i in 0 until s.length) if (s[i] == '\n') out[k++] = i + 1
        return out
    }

    private fun paraOf(s: CharSequence, off: Int): Int {
        var n = 0
        val m = min(off, s.length)
        for (i in 0 until m) if (s[i] == '\n') n++
        return n
    }

    fun paraAt(off: Int): Int = paraOf(text, off)
    fun paraStart(i: Int): Int = starts().getOrElse(i) { text.length }

    /** Конец строки i (без переноса). */
    fun paraEnd(i: Int): Int {
        val st = starts()
        return if (i + 1 < st.size) st[i + 1] - 1 else text.length
    }

    /** Текст строки i без невидимых знаков. */
    fun paraText(i: Int): String = text.subSequence(paraStart(i), paraEnd(i)).toString().replace(ZWS, "")

    /** Строки, которых касается выделение. */
    fun selParas(): IntRange {
        val a = min(selectionStart, selectionEnd).coerceAtLeast(0)
        val b = max(selectionStart, selectionEnd).coerceAtLeast(0)
        return paraAt(a)..paraAt(b)
    }

    /** Курсор: строка и место в ней (без невидимых знаков). */
    fun caretAt(off: Int = selectionEnd): Pair<Int, Int> {
        val o = off.coerceIn(0, text.length)
        val i = paraAt(o)
        val ps = paraStart(i)
        var k = 0
        for (j in ps until o) if (text[j] != ZW) k++
        return i to k
    }

    /** Место в поле для строки i и места [off] в ней (без невидимых знаков); off < 0 — конец строки. */
    fun rawOffset(i: Int, off: Int): Int {
        val st = starts()
        if (st.isEmpty()) return 0
        val ii = i.coerceIn(0, st.size - 1)
        val ps = st[ii]
        val pe = if (ii + 1 < st.size) st[ii + 1] - 1 else text.length
        if (off < 0) return pe
        var k = 0
        var j = ps
        while (j < pe && text[j] == ZW) j++
        while (j < pe && k < off) { if (text[j] != ZW) k++; j++ }
        return j
    }

    private fun lineIsEmpty(s: CharSequence, ps: Int): Boolean {
        var j = ps
        while (j < s.length && s[j] != '\n') { if (s[j] != ZW) return false; j++ }
        return true
    }

    /** Курсор в начале строки (до него в строке — только невидимый знак). */
    private fun atLineStart(s: CharSequence, ps: Int, off: Int): Boolean {
        if (off < ps) return false
        for (j in ps until off) if (s[j] != ZW) return false
        return true
    }

    // ---------------------------------------------------------------- правка текста

    private var chPara = 0
    private var chRemoved = 0
    private var chAtStart = false
    private var chWasEmpty = false
    private var edPara = 0
    private var edAdded = 0
    private var edStart = 0
    private var edCount = 0
    private var exitList = false
    private var expectSel = -1

    private fun watcher() = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
            if (loading) return
            chPara = paraOf(s, start)
            var r = 0
            for (j in start until min(s.length, start + count)) if (s[j] == '\n') r++
            chRemoved = r
            var ps = start
            while (ps > 0 && s[ps - 1] != '\n') ps--
            chAtStart = atLineStart(s, ps, start)
            chWasEmpty = lineIsEmpty(s, ps)
            host.beforeEdit(this@NoteEdit, start)
        }

        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
            if (loading) return
            if (paras.isEmpty()) paras += PAttr()
            var added = 0
            var onlyBreaks = true
            for (j in start until start + count) {
                val c = s[j]
                if (c == '\n') added++ else if (c != ZW) onlyBreaks = false
            }
            val p = chPara.coerceIn(0, paras.size - 1)
            val base = paras[p]
            repeat(chRemoved) { if (p + 1 < paras.size) paras.removeAt(p + 1) }
            if (added > 0) {
                if (chAtStart && before == 0 && !chWasEmpty && onlyBreaks) {
                    // Enter в начале непустой строки: пустые строки встают над ней, сама строка своё оформление сохраняет.
                    val above = base.above()
                    repeat(added) { paras.add(p, above.copy()) }
                } else {
                    for (k in 1..added) paras.add(p + k, base.next())
                }
            }
            edPara = p; edAdded = added; edStart = start; edCount = count
            exitList = added == 1 && count == 1 && before == 0 && chWasEmpty && (base.listy || base.indent > 0)
            expectSel = start + count
        }

        override fun afterTextChanged(e: Editable) {
            if (loading) return
            afterChange(e)
        }
    }

    private fun afterChange(e: Editable) {
        syncCount(e)
        val p = edPara
        if (exitList) {
            // Enter в пустом пункте списка (как на айфоне): строка не добавляется, пункт перестаёт быть пунктом
            // (с отступом — сначала отступ меньше).
            exitList = false
            quiet { e.delete(edStart, edStart + 1) }
            if (p + 1 < paras.size) paras.removeAt(p + 1)
            val a = paras[p]
            if (a.indent > 0) a.indent -= 1 else { a.task = false; a.done = false; a.style = ParaStyle.BODY }
            redecorate(e, p, p)
            ensureZw(e)
            updateEmptyLook()
            host.afterEdit(this)
            return
        }
        applyPending(e, edStart, edStart + edCount)
        if (first && paras.isNotEmpty()) paras[0].apply { task = false; done = false; style = ParaStyle.TITLE; indent = 0 }
        redecorate(e, p, p + edAdded)
        ensureZw(e)
        updateEmptyLook()
        host.afterEdit(this)
    }

    /** Правка без слежения (свои служебные правки: невидимый знак, выход из списка). */
    private inline fun quiet(f: () -> Unit) {
        val was = loading
        loading = true
        try { f() } finally { loading = was }
    }

    private fun syncCount(e: CharSequence) {
        var n = 1
        for (i in 0 until e.length) if (e[i] == '\n') n++
        while (paras.size < n) paras += PAttr()
        while (paras.size > n) paras.removeAt(paras.size - 1)
    }

    /**
     * Невидимый знак в пустой последней строке (иначе у неё нет высоты клетки, отступа и кружка чек-листа). Пустое название
     * новой заметки — без знака: в нём видна подсказка «Название».
     */
    private fun ensureZw(e: Editable) {
        val st = starts(e)
        val ps = st.last()
        if (ps < e.length) return
        if (first && st.size == 1) return
        quiet { e.insert(ps, ZWS) }
    }

    /** Пустое название — поле само крупное и жирное (у пустой строки нет оформления абзаца). */
    private fun updateEmptyLook() {
        if (!ready) return
        val bigTitle = first && text.isEmpty()
        val size = if (bigTitle) ParaStyle.textSp(ParaStyle.TITLE) else ParaStyle.textSp(ParaStyle.BODY)
        if (textSize != TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, size, dm)) textSize = size
        val tf = if (bigTitle) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        if (typeface != tf) typeface = tf
        val cell = host.cellPx()
        minHeight = if (cell > 0) cell * (if (bigTitle) ParaStyle.cells(ParaStyle.TITLE) else 1) else 0
    }

    // ---------------------------------------------------------------- «Стереть»

    /**
     * «Стереть» у начала строки (как на айфоне): у пункта с отступом — отступ меньше, у списка, чек-листа, цитаты — снять их;
     * иначе — склеить с верхней строкой, в начале куска — с тем, что выше. false — обычное стирание буквы.
     */
    fun specialBackspace(): Boolean {
        val a = selectionStart
        if (a < 0 || a != selectionEnd) return false
        val e = text
        val i = paraAt(a)
        val ps = paraStart(i)
        if (!atLineStart(e, ps, a)) return false
        val p = paras.getOrNull(i) ?: return false
        host.newStep()
        if (p.indent > 0 && !(first && i == 0)) {
            host.beforeEdit(this, a)
            p.indent -= 1
            attrsChanged(i..i)
            host.afterEdit(this)
            return true
        }
        if (p.listy) {
            host.beforeEdit(this, a)
            p.task = false; p.done = false; p.style = ParaStyle.BODY
            attrsChanged(i..i)
            host.afterEdit(this)
            return true
        }
        if (i == 0) {
            host.onBackspaceAtStart(this)
            return true
        }
        e.delete(ps - 1, a)
        return true
    }

    /** «Стереть» с панели пера: выделенное, букву перед курсором или по правилам начала строки. */
    fun backspace() {
        val a = selectionStart; val b = selectionEnd
        if (a < 0) return
        if (a != b) { text.delete(min(a, b), max(a, b)); return }
        if (specialBackspace()) return
        if (a == 0) return
        val cut = Character.offsetByCodePoints(text, a, -1)
        text.delete(cut, a)
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val ic = super.onCreateInputConnection(outAttrs) ?: return null
        return object : InputConnectionWrapper(ic, true) {
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (beforeLength == 1 && afterLength == 0 && specialBackspace()) return true
                return super.deleteSurroundingText(beforeLength, afterLength)
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_DEL && event.action == KeyEvent.ACTION_DOWN && specialBackspace()) return true
                return super.sendKeyEvent(event)
            }

            // Заглавная буква в начале пункта: невидимый знак перед ней — не буква.
            override fun getCursorCapsMode(reqModes: Int): Int {
                val a = selectionStart
                if (a < 0) return super.getCursorCapsMode(reqModes)
                val before = text.subSequence(0, a).toString().replace(ZWS, "")
                return TextUtils.getCapsMode(before, before.length, reqModes)
            }
        }
    }

    /**
     * Вставка из другого приложения: своё оформление (Ж/К/Ч/З, цвета, засечки) остаётся, чужое (размеры, шрифты, ссылки,
     * отступы) — убирается, иначе текст выглядел бы не так, как сохранится.
     */
    override fun onTextContextMenuItem(id: Int): Boolean {
        if (id != android.R.id.paste && id != android.R.id.pasteAsPlainText) return super.onTextContextMenuItem(id)
        val a = min(selectionStart, selectionEnd).coerceAtLeast(0)
        val len0 = text.length - (max(selectionStart, selectionEnd) - a)
        val r = super.onTextContextMenuItem(id)
        val e = text
        val b = (a + e.length - len0).coerceIn(a, e.length)
        if (b > a) for (sp in e.getSpans(a, b, Any::class.java)) {
            if (sp is Deco || kindsOf(e, sp).isNotEmpty()) continue
            if (sp !is android.text.style.CharacterStyle && sp !is android.text.style.ParagraphStyle) continue
            if (sp is android.text.style.SuggestionSpan || sp.javaClass.simpleName == "SpellCheckSpan") continue
            if ((e.getSpanFlags(sp) and Spanned.SPAN_COMPOSING) != 0) continue
            e.removeSpan(sp)
        }
        return r
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DEL && specialBackspace()) return true
        return super.onKeyDown(keyCode, event)
    }

    // ---------------------------------------------------------------- курсор, чек-лист

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        if (!ready || loading) return
        if (!(selStart == selEnd && selStart == expectSel)) clearPending()
        expectSel = -1
        host.onSelection(this, selStart, selEnd)
    }

    private var checkDown = -1

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                checkDown = checkAt(ev.x, ev.y)
                if (checkDown >= 0) return true
            }
            MotionEvent.ACTION_MOVE -> if (checkDown >= 0) return true
            MotionEvent.ACTION_UP -> if (checkDown >= 0) {
                val i = checkDown
                checkDown = -1
                if (checkAt(ev.x, ev.y) == i) host.onToggleDone(this, i)
                return true
            }
            MotionEvent.ACTION_CANCEL -> if (checkDown >= 0) { checkDown = -1; return true }
        }
        return super.onTouchEvent(ev)
    }

    /** Кружок пункта чек-листа под точкой (координаты поля): номер строки или -1. */
    fun checkAt(x: Float, y: Float): Int {
        val l = layout ?: return -1
        val line = l.getLineForVertical((y - totalPaddingTop + scrollY).toInt())
        if (y < totalPaddingTop - dp(4f) || y > totalPaddingTop + l.getLineBottom(line) + dp(4f)) return -1
        val off = l.getLineStart(line)
        val i = paraAt(off)
        val a = paras.getOrNull(i) ?: return -1
        if (!a.task || off != paraStart(i)) return -1
        val x0 = totalPaddingLeft + indentPx * a.indent
        return if (x >= x0 - dp(8f) && x <= x0 + taskW + dp(2f)) i else -1
    }

    // ---------------------------------------------------------------- оформление кусков

    private val pendingOn = HashMap<String, Int>()
    private val pendingOff = HashSet<String>()

    private fun clearPending() { if (ready) { pendingOn.clear(); pendingOff.clear() } }

    /** Ж/К/Ч/З, засечки, цвет для того, что напечатают дальше (нажали без выделения). */
    val pending: Map<String, Int> get() = pendingOn

    private fun applyPending(e: Editable, a: Int, b: Int) {
        if (b <= a || (pendingOn.isEmpty() && pendingOff.isEmpty())) return
        for ((k, v) in pendingOn) { if (k in Rich.VALUED) removeKind(e, k, a, b); addKind(e, k, a, b, v) }
        for (k in pendingOff) removeKind(e, k, a, b)
    }

    /** Виды оформления у знака (своё оформление абзаца и знаки набора с клавиатуры — не в счёт). */
    private fun kindsOf(e: Spanned, sp: Any): List<Pair<String, Int>> {
        if (sp is Deco) return emptyList()
        if ((e.getSpanFlags(sp) and Spanned.SPAN_COMPOSING) != 0) return emptyList()
        return when {
            sp is StyleSpan -> when (sp.style) {
                Typeface.BOLD -> listOf(Rich.B to 0)
                Typeface.ITALIC -> listOf(Rich.I to 0)
                Typeface.BOLD_ITALIC -> listOf(Rich.B to 0, Rich.I to 0)
                else -> emptyList()
            }
            sp.javaClass == UnderlineSpan::class.java -> listOf(Rich.U to 0)
            sp is StrikethroughSpan -> listOf(Rich.S to 0)
            sp is BackgroundColorSpan -> listOf(Rich.HL to sp.backgroundColor)
            sp is ForegroundColorSpan -> listOf(Rich.FC to sp.foregroundColor)
            sp is TypefaceSpan && sp.family == "serif" -> listOf(Rich.SERIF to 0)
            else -> emptyList()
        }
    }

    private fun spanOf(kind: String, value: Int): Any? = when (kind) {
        Rich.B -> StyleSpan(Typeface.BOLD)
        Rich.I -> StyleSpan(Typeface.ITALIC)
        Rich.U -> UnderlineSpan()
        Rich.S -> StrikethroughSpan()
        Rich.HL -> BackgroundColorSpan(value)
        Rich.FC -> ForegroundColorSpan(value)
        Rich.SERIF -> TypefaceSpan("serif")
        else -> null
    }

    private fun addKind(e: Editable, kind: String, a: Int, b: Int, value: Int = 0) {
        if (b <= a) return
        spanOf(kind, value)?.let { e.setSpan(it, a, b, Spanned.SPAN_EXCLUSIVE_INCLUSIVE) }
    }

    /** Снять вид [kind] с куска [a, b): куски по краям остаются, другие виды того же знака — тоже. */
    private fun removeKind(e: Editable, kind: String, a: Int, b: Int) {
        if (b <= a) return
        for (sp in e.getSpans(a, b, Any::class.java)) {
            val kinds = kindsOf(e, sp)
            if (kinds.none { it.first == kind }) continue
            val s = e.getSpanStart(sp); val t = e.getSpanEnd(sp)
            if (t <= a || s >= b) continue
            e.removeSpan(sp)
            for ((k, v) in kinds) {
                if (s < a) addKind(e, k, s, a, v)
                if (t > b) addKind(e, k, b, t, v)
                if (k != kind) addKind(e, k, max(s, a), min(t, b), v)
            }
        }
    }

    /** Весь кусок [a, b) в этом виде ([value] — у цвета именно этот; null — любой). */
    fun covered(kind: String, a: Int, b: Int, value: Int? = null): Boolean {
        if (b <= a) return false
        val e = text
        val iv = ArrayList<IntArray>()
        for (sp in e.getSpans(a, b, Any::class.java)) {
            if (kindsOf(e, sp).none { it.first == kind && (value == null || it.second == value) }) continue
            iv += intArrayOf(e.getSpanStart(sp), e.getSpanEnd(sp))
        }
        iv.sortBy { it[0] }
        val merged = ArrayList<IntArray>()
        for (r in iv) {
            val last = merged.lastOrNull()
            if (last != null && r[0] <= last[1]) last[1] = max(last[1], r[1]) else merged += intArrayOf(r[0], r[1])
        }
        // Каждый видимый знак куска — под оформлением (невидимые знаки не в счёт; одни невидимые — не оформлено).
        var any = false
        var k = 0
        for (j in a until b) {
            if (e[j] == ZW) continue
            any = true
            while (k < merged.size && merged[k][1] <= j) k++
            if (k >= merged.size || merged[k][0] > j) return false
        }
        return any
    }

    /** Ж/К/Ч/З и засечки: на выделенное — включить или снять; без выделения — для того, что напечатают дальше. */
    fun toggleKind(kind: String): Boolean {
        val a = min(selectionStart, selectionEnd); val b = max(selectionStart, selectionEnd)
        if (a < 0) return false
        if (a == b) {
            val now = if (kind in pendingOn) true else if (kind in pendingOff) false else (a > 0 && covered(kind, a - 1, a))
            pendingOn.remove(kind); pendingOff.remove(kind)
            if (now) pendingOff += kind else pendingOn[kind] = 0
            expectSel = a
            return !now
        }
        val on = !covered(kind, a, b)
        if (on) addKind(text, kind, a, b) else removeKind(text, kind, a, b)
        return on
    }

    /** Маркер или цвет текста: на выделенное (0 — снять); без выделения — для того, что напечатают дальше. */
    fun setColorKind(kind: String, color: Int) {
        val a = min(selectionStart, selectionEnd); val b = max(selectionStart, selectionEnd)
        if (a < 0) return
        if (a == b) {
            pendingOn.remove(kind); pendingOff.remove(kind)
            if (color == 0) pendingOff += kind else pendingOn[kind] = color
            expectSel = a
            return
        }
        removeKind(text, kind, a, b)
        if (color != 0) addKind(text, kind, a, b, color)
    }

    /** Вид включён у выделенного (без выделения — у знака перед курсором или для того, что напечатают). */
    fun kindOn(kind: String): Boolean {
        val a = min(selectionStart, selectionEnd); val b = max(selectionStart, selectionEnd)
        if (a < 0) return false
        if (a == b) {
            if (kind in pendingOn) return true
            if (kind in pendingOff) return false
            return a > 0 && text[a - 1] != '\n' && covered(kind, a - 1, a)
        }
        return covered(kind, a, b)
    }

    /** Свойства строк поменяли: оформление этих строк заново. */
    fun attrsChanged(range: IntRange) {
        if (first && paras.isNotEmpty()) paras[0].apply { task = false; done = false; style = ParaStyle.TITLE; indent = 0 }
        redecorate(text, range.first, range.last)
        ensureZw(text)
    }

    /** Разлиновку сменили: высота строк заново. */
    fun relayout() {
        redecorate(text, 0, paras.size - 1)
        updateEmptyLook()
    }

    // ---------------------------------------------------------------- оформление абзацев

    /** Своё оформление абзаца (не путать с оформлением кусков, которое ставит человек). Не копируется в буфер обмена. */
    private interface Deco

    /** Размер, жирность, моноширинный шрифт, цвет абзаца. */
    private class DFont(val size: Float, val bold: Boolean, val mono: Boolean, val color: Int) : MetricAffectingSpan(), Deco {
        override fun updateMeasureState(tp: TextPaint) = font(tp)
        override fun updateDrawState(tp: TextPaint) {
            font(tp)
            if (color != 0) tp.color = color
        }

        private fun font(tp: TextPaint) {
            if (size > 0f) tp.textSize = size
            val old = tp.typeface
            val st = (old?.style ?: 0) or (if (bold) Typeface.BOLD else 0)
            val fam = if (mono) Typeface.MONOSPACE else (old ?: Typeface.DEFAULT)
            tp.typeface = Typeface.create(fam, st)
        }
    }

    private val markPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.INK2; textAlign = Paint.Align.RIGHT }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Ui.MUTED; strokeWidth = dp(1.6f) }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Ui.ACCENT }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = dp(2f); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val barPaint = Paint().apply { color = Ui.CHIP_LINE }
    private val bulletPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Ui.INK }
    private val tick = Path()

    /** Поле слева у строки, отметки списков, кружок чек-листа, черта цитаты; высота строки — клетка или своя. */
    private inner class DLook(val a: PAttr, val num: Int, val style: String) : LeadingMarginSpan, LineHeightSpan, android.text.style.UpdateLayout, Deco {
        override fun getLeadingMargin(first: Boolean): Int = indentPx * a.indent + when {
            a.task -> taskW
            ParaStyle.isList(style) -> listW
            style == ParaStyle.QUOTE -> quoteW
            else -> 0
        }

        override fun drawLeadingMargin(
            c: Canvas, p: Paint, x: Int, dir: Int, top: Int, baseline: Int, bottom: Int,
            text: CharSequence, start: Int, end: Int, first: Boolean, layout: Layout?,
        ) {
            val x0 = x + indentPx * a.indent
            val body = spPx(ParaStyle.BODY)
            if (a.task) {
                if (!first) return
                val r = dp(10.5f)
                val cx = x0 + dp(11.5f)
                val cy = baseline - body * 0.33f
                if (a.done) {
                    c.drawCircle(cx, cy, r, dotPaint)
                    tick.reset()
                    tick.moveTo(cx - r * 0.45f, cy + r * 0.02f)
                    tick.lineTo(cx - r * 0.1f, cy + r * 0.38f)
                    tick.lineTo(cx + r * 0.48f, cy - r * 0.36f)
                    c.drawPath(tick, tickPaint)
                } else c.drawCircle(cx, cy, r, ringPaint)
                return
            }
            when (style) {
                ParaStyle.QUOTE -> c.drawRect(x0 + dp(1f), top.toFloat(), x0 + dp(4f), bottom.toFloat(), barPaint)
                ParaStyle.BULLET -> if (first) c.drawCircle(x0 + listW - dp(13f), baseline - body * 0.33f, dp(2.9f), bulletPaint)
                ParaStyle.DASH, ParaStyle.NUM -> if (first) {
                    markPaint.textSize = body
                    c.drawText(if (style == ParaStyle.DASH) "–" else "$num.", x0 + listW - dp(8f), baseline.toFloat(), markPaint)
                }
            }
        }

        override fun chooseHeight(text: CharSequence, start: Int, end: Int, spanstartv: Int, lineHeight: Int, fm: Paint.FontMetricsInt) {
            val cell = host.cellPx()
            if (cell > 0) {
                val d = fm.descent
                fm.descent = d + under
                fm.ascent = fm.descent - cell * ParaStyle.cells(style)
            } else {
                // Без разметки: немного воздуха между строками, как на айфоне.
                fm.descent += ((fm.descent - fm.ascent) * 0.2f).toInt()
            }
            fm.bottom = fm.descent
            fm.top = fm.ascent
        }
    }

    /** Номера пунктов «1.» — по порядку внутри списка одного уровня (вложенные пункты номер не сбивают). */
    private fun numbers(): IntArray {
        val out = IntArray(paras.size)
        val last = IntArray(ParaStyle.MAX_INDENT + 2)
        for ((i, a) in paras.withIndex()) {
            val lv = a.indent.coerceIn(0, ParaStyle.MAX_INDENT)
            if (!a.task && a.style == ParaStyle.NUM) {
                last[lv] += 1
                out[i] = last[lv]
                for (k in lv + 1 until last.size) last[k] = 0
            } else {
                // Обычная строка того же уровня или левее — список кончился.
                for (k in lv until last.size) last[k] = 0
            }
        }
        return out
    }

    private fun redecorate(e: Editable, from: Int, to: Int) {
        val n = paras.size
        if (n == 0) return
        val a = from.coerceIn(0, n - 1)
        var b = to.coerceIn(a, n - 1)
        // Номера дальше по списку могли сдвинуться.
        while (b + 1 < n && (paras[b + 1].indent > 0 || (!paras[b + 1].task && paras[b + 1].style == ParaStyle.NUM))) b++
        val st = starts(e)
        val nums = numbers()
        for (i in a..b) decorate(e, i, st, nums)
    }

    private fun decorate(e: Editable, i: Int, st: IntArray, nums: IntArray) {
        if (i >= st.size) return
        val ps = st[i]
        val pe = if (i + 1 < st.size) st[i + 1] else e.length
        for (d in e.getSpans(ps, pe, Deco::class.java)) {
            val s = e.getSpanStart(d)
            if (s >= ps && (s < pe || ps == pe)) e.removeSpan(d)
        }
        if (pe <= ps) return
        val a = paras[i]
        val style = if (a.task) ParaStyle.BODY else a.style
        val flags = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        e.setSpan(DLook(a.copy(), nums.getOrElse(i) { 1 }, style), ps, pe, flags)
        val color = when {
            a.task && a.done -> Ui.INK2
            style == ParaStyle.QUOTE -> Ui.INK2
            else -> 0
        }
        val bold = style == ParaStyle.TITLE || style == ParaStyle.H1 || style == ParaStyle.H2
        if (style != ParaStyle.BODY || color != 0) {
            e.setSpan(DFont(if (style == ParaStyle.BODY || style == ParaStyle.QUOTE) 0f else spPx(style), bold, style == ParaStyle.MONO, color), ps, pe, flags)
        }
    }

    // ---------------------------------------------------------------- загрузка и выгрузка

    /** Показать строки (оформление кусков и свойства абзацев). */
    fun load(lines: List<ELine>) {
        quiet {
            val sb = SpannableStringBuilder()
            paras.clear()
            for ((k, l) in lines.withIndex()) {
                if (k > 0) sb.append('\n')
                val st = sb.length
                sb.append(l.text)
                for (t in l.spans) {
                    if (t.end <= t.start || st + t.end > sb.length) continue
                    spanOf(t.kind, t.value)?.let { sb.setSpan(it, st + t.start, st + t.end, Spanned.SPAN_EXCLUSIVE_INCLUSIVE) }
                }
                paras += l.attr.copy()
            }
            if (paras.isEmpty()) paras += PAttr()
            if (first) paras[0].apply { task = false; done = false; style = ParaStyle.TITLE; indent = 0 }
            setText(sb, BufferType.EDITABLE)
            val e = text
            redecorate(e, 0, paras.size - 1)
            ensureZw(e)
        }
        clearPending()
        updateEmptyLook()
    }

    /** Строки поля: текст без невидимых знаков, оформление кусков, свойства абзацев. */
    fun lines(): List<ELine> {
        val e = text
        val s = e.toString()
        val out = ArrayList<ELine>(paras.size)
        var start = 0
        var i = 0
        while (true) {
            val nl = s.indexOf('\n', start)
            val end = if (nl < 0) s.length else nl
            out += exportLine(e, s, start, end, paras.getOrElse(i) { PAttr() })
            if (nl < 0) break
            start = nl + 1
            i++
        }
        return out
    }

    private fun exportLine(e: Spanned, s: String, a: Int, b: Int, attr: PAttr): ELine {
        val raw = s.substring(a, b)
        val map = IntArray(raw.length + 1)
        val sb = StringBuilder(raw.length)
        for (j in raw.indices) { map[j] = sb.length; if (raw[j] != ZW) sb.append(raw[j]) }
        map[raw.length] = sb.length
        val spans = ArrayList<TextSpan>()
        if (b > a) for (sp in e.getSpans(a, b, Any::class.java)) {
            val kinds = kindsOf(e, sp)
            if (kinds.isEmpty()) continue
            val x = max(a, e.getSpanStart(sp)) - a
            val y = min(b, e.getSpanEnd(sp)) - a
            if (y <= x) continue
            for ((k, v) in kinds) spans += TextSpan(map[x], map[y], k, v)
        }
        Rich.normalize(spans, sb.length)
        return ELine(sb.toString(), spans, attr.copy())
    }

    /** Размер шрифта строки i (px). */
    fun textPxAt(i: Int): Float {
        val a = paras.getOrNull(i) ?: return spPx(ParaStyle.BODY)
        return spPx(if (a.task || a.style == ParaStyle.QUOTE) ParaStyle.BODY else a.style)
    }

    /** Двухклеточные строки (название): верх и низ каждой строки в координатах поля — линейка через их середину не идёт. */
    fun tallBands(): List<FloatArray> {
        val l = layout ?: return emptyList()
        val out = ArrayList<FloatArray>()
        val st = starts()
        for ((i, a) in paras.withIndex()) {
            if (a.task || ParaStyle.cells(a.style) < 2 || i >= st.size) continue
            val ps = st[i]
            val pe = if (i + 1 < st.size) st[i + 1] - 1 else text.length
            for (line in l.getLineForOffset(ps)..l.getLineForOffset(pe)) {
                out += floatArrayOf((totalPaddingTop + l.getLineTop(line)).toFloat(), (totalPaddingTop + l.getLineBottom(line)).toFloat())
            }
        }
        if (first && text.isEmpty()) out += floatArrayOf(0f, height.toFloat())
        return out
    }

    /** Переставить строки, которых касается выделение, на одну вверх (-1) или вниз (+1). Название не двигается. */
    fun moveLines(dir: Int): Boolean {
        val r = selParas()
        val ls = lines().toMutableList()
        val top = if (first) 1 else 0
        if (r.first < top) return false
        if (dir < 0 && r.first <= top) return false
        if (dir > 0 && r.last >= ls.size - 1) return false
        val (ca, oa) = caretAt(min(selectionStart, selectionEnd))
        val (cb, ob) = caretAt(max(selectionStart, selectionEnd))
        if (dir < 0) {
            val x = ls.removeAt(r.first - 1)
            ls.add(r.last, x)
        } else {
            val x = ls.removeAt(r.last + 1)
            ls.add(r.first, x)
        }
        load(ls)
        val a = rawOffset(ca + dir, oa)
        val b = rawOffset(cb + dir, ob)
        setSelection(a.coerceIn(0, text.length), b.coerceIn(0, text.length))
        return true
    }

    companion object {
        /** Невидимый знак пустой последней строки. */
        const val ZW = '​'
        const val ZWS = "​"
    }
}
