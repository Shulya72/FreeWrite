package party.shulya.freewrite

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Цвета и мелкие помощники интерфейса — по макету «рабочий журнал». */
object Ui {
    val DESK = Color.rgb(230, 234, 240)
    val PAPER = Color.rgb(252, 253, 254)
    val INK = Color.rgb(22, 34, 58)
    val INK2 = Color.rgb(75, 88, 115)
    val MUTED = Color.rgb(133, 146, 168)
    val RULE = Color.rgb(211, 218, 229)
    val LINE = Color.argb(30, 40, 80, 140)
    val MARGIN = Color.argb(56, 212, 71, 12)
    val ACCENT = Color.rgb(212, 71, 12)
    val ACCENT_SOFT = Color.rgb(253, 230, 216)
    val PEN_BLUE = Color.rgb(31, 79, 209)
    val PEN_RED = Color.rgb(194, 40, 43)
    val OK = Color.rgb(35, 128, 58)
    val OK_SOFT = Color.rgb(225, 242, 228)
    val BAD = Color.rgb(190, 42, 42)
    val BAD_SOFT = Color.rgb(251, 226, 226)
    val WARN = Color.rgb(168, 106, 16)
    val WARN_SOFT = Color.rgb(252, 240, 212)

    const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    fun dp(ctx: Context, v: Float): Int = (v * ctx.resources.displayMetrics.density).roundToInt()
    fun dpf(ctx: Context, v: Float): Float = v * ctx.resources.displayMetrics.density

    fun rounded(ctx: Context, fill: Int, stroke: Int? = null, radiusDp: Float = 12f): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dpf(ctx, radiusDp)
            if (stroke != null) setStroke(dp(ctx, 1f), stroke)
        }

    fun text(ctx: Context, value: String, sp: Float, color: Int = INK, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            text = value
            textSize = sp
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    fun mono(ctx: Context, value: String, sp: Float, color: Int = MUTED): TextView =
        text(ctx, value, sp, color).apply { typeface = Typeface.MONOSPACE }

    /** Кнопка инструмента: значок и подпись под ним. Выбранная — с оранжевым фоном. */
    class ToolButton(ctx: Context, iconRes: Int, label: String) : LinearLayout(ctx) {
        private val icon = ImageView(ctx)
        private val caption = TextView(ctx)
        var selectedTool = false
            set(value) {
                field = value
                val c = if (value) ACCENT else INK2
                icon.imageTintList = ColorStateList.valueOf(c)
                caption.setTextColor(c)
                background = if (value) rounded(context, ACCENT_SOFT, null, 10f) else null
            }

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(ctx, 7f), dp(ctx, 5f), dp(ctx, 7f), dp(ctx, 4f))
            minimumWidth = dp(ctx, 54f)
            isClickable = true
            isFocusable = true
            contentDescription = label
            icon.setImageResource(iconRes)
            addView(icon, LayoutParams(dp(ctx, 24f), dp(ctx, 24f)))
            caption.text = label
            caption.textSize = 11f
            caption.gravity = Gravity.CENTER
            addView(caption, LayoutParams(WRAP, WRAP))
            selectedTool = false
        }
    }

    /**
     * Ряд, который переносит элементы на следующую строку, если не помещаются (узкий экран
     * телефона). Элементы в строке выровнены по нижнему краю.
     */
    class FlowRow(ctx: Context) : ViewGroup(ctx) {
        var hGap = 0
        var vGap = 0

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val maxW = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
            val limit = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE else maxW
            var x = 0; var y = 0; var rowH = 0; var widest = 0
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                if (c.visibility == GONE) continue
                c.measure(MeasureSpec.makeMeasureSpec(maxOf(0, maxW), if (limit == Int.MAX_VALUE) MeasureSpec.UNSPECIFIED else MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                if (x > 0 && x + c.measuredWidth > limit) { x = 0; y += rowH + vGap; rowH = 0 }
                x += c.measuredWidth + hGap
                rowH = maxOf(rowH, c.measuredHeight)
                widest = maxOf(widest, x - hGap)
            }
            setMeasuredDimension(resolveSize(widest + paddingLeft + paddingRight, widthMeasureSpec),
                resolveSize(y + rowH + paddingTop + paddingBottom, heightMeasureSpec))
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val limit = r - l - paddingLeft - paddingRight
            // Сначала разбить на строки, потом расставить (по нижнему краю строки).
            val rows = ArrayList<MutableList<View>>()
            var cur = ArrayList<View>(); var x = 0
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                if (c.visibility == GONE) continue
                if (cur.isNotEmpty() && x + c.measuredWidth > limit) { rows += cur; cur = ArrayList(); x = 0 }
                cur += c; x += c.measuredWidth + hGap
            }
            if (cur.isNotEmpty()) rows += cur
            var y = paddingTop
            for (row in rows) {
                val h = row.maxOf { it.measuredHeight }
                var cx = paddingLeft
                for (c in row) {
                    val top = y + h - c.measuredHeight
                    c.layout(cx, top, cx + c.measuredWidth, top + c.measuredHeight)
                    cx += c.measuredWidth + hGap
                }
                y += h + vGap
            }
        }
    }

    fun separator(ctx: Context): View = View(ctx).apply {
        setBackgroundColor(RULE)
        layoutParams = LinearLayout.LayoutParams(dp(ctx, 1f), MATCH).apply {
            setMargins(dp(ctx, 4f), dp(ctx, 6f), dp(ctx, 4f), dp(ctx, 6f))
        }
    }

    /** Полоса инструментов, которая прокручивается вбок на узких экранах. */
    fun toolbar(ctx: Context, row: LinearLayout): HorizontalScrollView = HorizontalScrollView(ctx).apply {
        isHorizontalScrollBarEnabled = false
        setBackgroundColor(PAPER)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(ctx, 10f), dp(ctx, 4f), dp(ctx, 10f), dp(ctx, 4f))
        addView(row, ViewGroup.LayoutParams(WRAP, WRAP))
    }

    /** Кнопка-«таблетка» с текстом (для плавающих панелей). */
    fun pill(ctx: Context, value: String, fg: Int, bg: Int): TextView = text(ctx, value, 14f, fg, bold = true).apply {
        background = rounded(ctx, bg, null, 999f)
        setPadding(dp(ctx, 14f), dp(ctx, 8f), dp(ctx, 14f), dp(ctx, 8f))
        isClickable = true
        isFocusable = true
    }

    /**
     * Плавающая панель над листом/доской (например, когда выбрана картинка):
     * подсказка слева, кнопки справа.
     */
    fun floatingBar(ctx: Context, label: String, vararg buttons: TextView): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(ctx, PAPER, RULE, 14f)
        elevation = dpf(ctx, 6f)
        setPadding(dp(ctx, 14f), dp(ctx, 6f), dp(ctx, 6f), dp(ctx, 6f))
        addView(text(ctx, label, 13f, INK2).apply { setPadding(0, 0, dp(ctx, 10f), 0) })
        for (b in buttons) addView(b, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(ctx, 6f) })
    }

    fun chip(ctx: Context, value: String, fg: Int, bg: Int): TextView = text(ctx, value, 12f, fg).apply {
        background = rounded(ctx, bg, null, 999f)
        setPadding(dp(ctx, 8f), dp(ctx, 2f), dp(ctx, 8f), dp(ctx, 2f))
    }

    // ---------------------------------------------------------------- даты

    private val ru = Locale("ru")

    fun dayTitle(time: Long): String {
        val now = Calendar.getInstance()
        val c = Calendar.getInstance().apply { timeInMillis = time }
        val sameYear = now.get(Calendar.YEAR) == c.get(Calendar.YEAR)
        val dayDiff = (startOfDay(now.timeInMillis) - startOfDay(time)) / 86_400_000L
        val date = SimpleDateFormat(if (sameYear) "d MMMM, EEEE" else "d MMMM yyyy", ru).format(Date(time))
        return when (dayDiff) {
            0L -> "Сегодня · $date"
            1L -> "Вчера · $date"
            else -> date
        }
    }

    fun startOfDay(time: Long): Long = Calendar.getInstance().apply {
        timeInMillis = time
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun time(time: Long): String = SimpleDateFormat("HH:mm", ru).format(Date(time))

    /** «7 ноября». */
    fun shortDate(time: Long): String = SimpleDateFormat("d MMMM", ru).format(Date(time))

    /** «1 предмет», «3 предмета», «12 предметов». */
    fun plural(n: Int, one: String, few: String, many: String): String {
        val m10 = n % 10; val m100 = n % 100
        val w = when {
            m10 == 1 && m100 != 11 -> one
            m10 in 2..4 && m100 !in 12..14 -> few
            else -> many
        }
        return "$n $w"
    }

    fun longDate(time: Long): String = SimpleDateFormat("EEEE, d MMMM", ru).format(Date(time))
        .replaceFirstChar { it.uppercase() }

    fun noteMeta(time: Long): String = SimpleDateFormat("d MMMM yyyy, HH:mm", ru).format(Date(time))
}
