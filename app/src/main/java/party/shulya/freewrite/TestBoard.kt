package party.shulya.freewrite

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.sin

/**
 * Тестовая доска для проверки скорости (шаг 2 плана): много «чертежей» и объектов поверх них.
 * Создаётся из «Проверки планшета»; работает долго — запускать в фоновом потоке.
 */
object TestBoard {

    const val TITLE = "Тест скорости"

    fun generate(
        context: Context,
        images: Int = 500,
        strokes: Int = 5000,
        texts: Int = 1000,
        stickers: Int = 300,
        callouts: Int = 200,
        fills: Int = 200,
        progress: (String) -> Unit = {},
    ): Board {
        val r = java.util.Random(7)
        val b = Board.create(TITLE)
        val cols = 25
        val w = 1200f
        val h = 850f
        val gap = 150f
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(90, 100, 120); strokeWidth = 2f }
        val wall = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(40, 45, 60); strokeWidth = 9f; style = Paint.Style.STROKE }
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(40, 45, 60); textSize = 40f }
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(90, 100, 120); textSize = 16f }
        val bmp = Bitmap.createBitmap(w.toInt(), h.toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        for (i in 0 until images) {
            if (i % 10 == 0) progress("Чертежи: $i из $images")
            // «Чертёж»: сетка осей, стены, подписи мелким шрифтом.
            c.drawColor(Color.WHITE)
            var x = 60f
            while (x < w) { c.drawLine(x, 40f, x, h - 40f, line); x += 120f }
            var y = 60f
            while (y < h) { c.drawLine(40f, y, w - 40f, y, line); y += 110f }
            repeat(6) {
                val l = 80f + r.nextFloat() * 800f
                val t = 80f + r.nextFloat() * 500f
                c.drawRect(l, t, l + 120f + r.nextFloat() * 260f, t + 80f + r.nextFloat() * 200f, wall)
            }
            c.drawText("Лист ${i + 1}", 60f, 50f, label)
            repeat(12) { c.drawText("Ось ${('А' + it % 20)}-${it + 1}, отм. +${r.nextInt(30)}.${r.nextInt(900) + 100}", 70f + it % 3 * 360f, 120f + it / 3 * 180f, small) }
            val (file, pw, ph) = BoardImages.importBitmap(context, bmp)
            b.images += BoardImage(file, (i % cols) * (w + gap), (i / cols) * (h + gap), w, h, pw, ph)
        }
        bmp.recycle()
        progress("Надписи и рисунки…")
        val rows = (images + cols - 1) / cols
        val fieldW = cols * (w + gap)
        val fieldH = rows.coerceAtLeast(1) * (h + gap)
        val colors = intArrayOf(Ui.INK, Ui.PEN_BLUE, Ui.PEN_RED, Ui.OK)
        for (i in 0 until strokes) {
            val x0 = r.nextFloat() * fieldW
            val y0 = r.nextFloat() * fieldH
            val n = 30 + r.nextInt(30)
            val amp = 5f + r.nextFloat() * 20f
            val pts = FloatArray(n * 3) { k ->
                val j = k / 3
                when (k % 3) {
                    0 -> x0 + j * 4f
                    1 -> y0 + amp * sin(j / 5.0).toFloat()
                    else -> 0.3f + r.nextFloat() * 0.4f
                }
            }
            b.strokes += Drawing("pen", colors[i % colors.size], 3f, pts)
        }
        for (i in 0 until texts) {
            b.texts += BoardText(r.nextFloat() * fieldW, r.nextFloat() * fieldH, 28f + r.nextInt(20), "Захватка №${i + 1}", Ui.INK)
        }
        val ids = b.statuses.map { it.id }
        for (i in 0 until stickers) {
            b.stickers += BoardSticker(r.nextFloat() * fieldW, r.nextFloat() * fieldH, ids[i % ids.size], 1.5f)
        }
        for (i in 0 until fills) {
            val l = r.nextFloat() * fieldW
            val t = r.nextFloat() * fieldH
            val fw = 100f + r.nextFloat() * 300f
            val fh = 60f + r.nextFloat() * 200f
            b.fills += BoardFill(floatArrayOf(l, t, l + fw, t, l + fw, t + fh, l, t + fh), ColorKit.PALETTE[18 + i % 6])
        }
        for (i in 0 until callouts) {
            val x = r.nextFloat() * fieldW
            val y = r.nextFloat() * fieldH
            val co = BoardCallout(x, y, 1.6f, "Захватка №${i + 1} от ${1 + i % 28}.09.26", ids[i % ids.size])
            co.addLeader(x - 150f - r.nextFloat() * 200f, y + 150f + r.nextFloat() * 200f)
            if (i % 2 == 0) co.addLeader(x + 300f + r.nextFloat() * 200f, y + 200f + r.nextFloat() * 200f)
            b.callouts += co
        }
        progress("Сохраняю…")
        BoardStore(context).save(b)
        return b
    }
}
