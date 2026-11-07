package party.shulya.freewrite

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Цвета для рисования: палитра из 24 цветов, три последних цвета на панели инструментов
 * (общие для записи и доски) и пипетка — взять цвет с листа/доски.
 */
object ColorKit {

    /** Палитра: тёмные, яркие, глубокие и бледные (для маркера и заливок). */
    val PALETTE = intArrayOf(
        Ui.INK, Color.rgb(0, 0, 0), Color.rgb(107, 114, 128), Ui.PEN_BLUE, Ui.PEN_RED, Ui.OK,
        Color.rgb(232, 113, 10), Color.rgb(242, 194, 0), Color.rgb(124, 181, 24), Color.rgb(15, 157, 142), Color.rgb(14, 165, 233), Color.rgb(124, 58, 237),
        Color.rgb(219, 39, 119), Color.rgb(139, 90, 43), Color.rgb(30, 58, 138), Color.rgb(127, 29, 29), Color.rgb(20, 83, 45), Color.rgb(107, 107, 31),
        Color.rgb(255, 236, 140), Color.rgb(200, 242, 194), Color.rgb(191, 227, 255), Color.rgb(255, 201, 217), Color.rgb(255, 216, 168), Color.rgb(227, 211, 255),
    )

    private const val PREFS = "colors"
    private const val KEY_RECENT = "recent"

    /** Три последних цвета (сначала — самый свежий). */
    fun recent(ctx: Context): List<Int> {
        val saved = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_RECENT, null)
        val list = saved?.split(',')?.mapNotNull { it.toIntOrNull() }.orEmpty().toMutableList()
        for (c in listOf(Ui.INK, Ui.PEN_BLUE, Ui.PEN_RED)) if (list.size < 3 && c !in list) list += c
        return list.take(3)
    }

    /** Запомнить выбранный цвет: он встаёт первым среди последних. */
    fun remember(ctx: Context, color: Int) {
        val list = recent(ctx).toMutableList()
        list.remove(color)
        list.add(0, color)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_RECENT, list.take(3).joinToString(","))
            .apply()
    }

    /** Кружок цвета (для панели и палитры). */
    fun swatch(ctx: Context, color: Int, selected: Boolean): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        val light = Color.luminance(color) > 0.8f
        when {
            selected -> setStroke(Ui.dp(ctx, 3f), Ui.ACCENT)
            light -> setStroke(Ui.dp(ctx, 1f), Ui.RULE)
        }
    }

    /**
     * Окно палитры: 24 цвета и «Пипетка».
     * @param onColor выбран цвет
     * @param onPipette нажали «Пипетка» — экран включает режим пипетки
     */
    fun showPalette(activity: Activity, current: Int, onColor: (Int) -> Unit, onPipette: () -> Unit) {
        val ctx: Context = activity
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(ctx, 20f), Ui.dp(ctx, 12f), Ui.dp(ctx, 20f), Ui.dp(ctx, 4f))
        }
        val grid = GridLayout(ctx).apply { columnCount = 6 }
        var dialog: AlertDialog? = null
        for (c in PALETTE) {
            val v = View(ctx).apply {
                background = swatch(ctx, c, c == current)
                contentDescription = "Цвет"
                tag = "palette_color"
                setOnClickListener {
                    dialog?.dismiss()
                    onColor(c)
                }
            }
            val size = Ui.dp(ctx, 40f)
            grid.addView(v, GridLayout.LayoutParams().apply {
                width = size; height = size
                setMargins(Ui.dp(ctx, 6f), Ui.dp(ctx, 6f), Ui.dp(ctx, 6f), Ui.dp(ctx, 6f))
            })
        }
        box.addView(grid)
        val pipette = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(ctx, Ui.ACCENT_SOFT, null, 12f)
            setPadding(Ui.dp(ctx, 14f), Ui.dp(ctx, 10f), Ui.dp(ctx, 16f), Ui.dp(ctx, 10f))
            isClickable = true
            tag = "palette_pipette"
            addView(android.widget.ImageView(ctx).apply {
                setImageResource(R.drawable.ic_pipette)
                imageTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT)
            }, LinearLayout.LayoutParams(Ui.dp(ctx, 24f), Ui.dp(ctx, 24f)))
            addView(TextView(ctx).apply {
                text = "Пипетка — взять цвет с листа или фото"
                textSize = 14f
                setTextColor(Ui.ACCENT)
                setPadding(Ui.dp(ctx, 10f), 0, 0, 0)
            })
            setOnClickListener {
                dialog?.dismiss()
                onPipette()
            }
        }
        box.addView(pipette, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = Ui.dp(ctx, 10f) })
        dialog = AlertDialog.Builder(ctx)
            .setTitle("Цвет")
            .setView(box)
            .setNegativeButton("Закрыть", null)
            .show()
    }

    /** Снимок вида в уменьшенном виде — для пипетки (цвет пикселя под пером). */
    class Snapshot(val bitmap: Bitmap, val scale: Float) {
        /** Цвет в точке вида (x, y), без прозрачности; null — за краем. */
        fun colorAt(x: Float, y: Float): Int? {
            val px = (x * scale).roundToInt()
            val py = (y * scale).roundToInt()
            if (px !in 0 until bitmap.width || py !in 0 until bitmap.height) return null
            return bitmap.getPixel(px, py) or (0xFF shl 24)
        }
    }

    fun snapshot(view: View, scale: Float = 0.5f): Snapshot? {
        if (view.width <= 0 || view.height <= 0) return null
        return try {
            val w = (view.width * scale).roundToInt().coerceAtLeast(1)
            val h = (view.height * scale).roundToInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.scale(scale, scale)
            view.draw(c)
            Snapshot(bmp, scale)
        } catch (_: Throwable) {
            null
        }
    }

    /** Ближайший к точке рисунок (в пределах r) — его цвет берёт пипетка раньше, чем пиксель. */
    fun drawingNear(list: List<Drawing>, x: Float, y: Float, r: Float): Drawing? {
        var best: Drawing? = null
        var bestD = Float.MAX_VALUE
        for (d in list.asReversed()) {
            var i = 0
            val rr = r + d.width / 2f
            while (i + 1 < d.pts.size) {
                val dx = d.pts[i] - x
                val dy = d.pts[i + 1] - y
                val dd = dx * dx + dy * dy
                if (dd < rr * rr && dd < bestD) { bestD = dd; best = d }
                i += 3
            }
            if (best != null) return best
        }
        return null
    }
}
