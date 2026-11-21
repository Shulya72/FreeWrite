package party.shulya.freewrite

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import kotlin.math.max

/** Голосовой ввод: окно скачивания модели и полоска громкости. */
object VoiceUi {

    /** Последнее окно скачивания (для проверок). */
    internal var lastDialog: AlertDialog? = null

    /**
     * Модели ещё нет: объяснить и предложить скачать. [onReady] — модель скачана, пока окно открыто (можно сразу
     * начинать запись).
     */
    fun offerModel(a: Activity, onReady: () -> Unit) {
        if (VoiceModel.isRunning) { progress(a, onReady); return }
        lastDialog = AlertDialog.Builder(a)
            .setTitle("Голосовой ввод")
            .setMessage(
                "Речь распознаётся прямо на планшете — без интернета и бесплатно (модель GigaAM от Сбера). " +
                    "Знаки препинания и заглавные буквы ставятся сами.\n\n" +
                    "Один раз нужно скачать модель — ${VoiceModel.totalMb} МБ. Лучше по Wi-Fi.",
            )
            .setPositiveButton("Скачать") { _, _ ->
                VoiceModel.download(a)
                progress(a, onReady)
            }
            .setNegativeButton("Не сейчас", null)
            .show()
    }

    /** Окно хода скачивания. «Свернуть» — качается дальше, по готовности — сообщение. */
    fun progress(a: Activity, onReady: () -> Unit) {
        val pad = Ui.dp(a, 22f)
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, Ui.dp(a, 12f), pad, 0)
        }
        val text = Ui.text(a, "Подключаюсь к github.com…", 15f, Ui.INK).apply { tag = "vdl_text" }
        val bar = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply {
            tag = "vdl_bar"
            max = 1000
            isIndeterminate = false
        }
        box.addView(text)
        box.addView(bar, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = Ui.dp(a, 12f) })
        box.addView(Ui.text(a, "Скачивание не мешает работать: окно можно свернуть.", 12f, Ui.MUTED).apply {
            setPadding(0, Ui.dp(a, 10f), 0, 0)
        })
        var closedByUser = false
        val d = AlertDialog.Builder(a)
            .setTitle("Модель распознавания речи")
            .setView(box)
            .setPositiveButton("Свернуть", null)
            .setNegativeButton("Остановить", null)
            .create()
        lateinit var listener: (VoiceModel.State) -> Unit
        fun show(s: VoiceModel.State) {
            when (s) {
                is VoiceModel.State.Running -> {
                    text.text = "Скачано ${s.done / 1_000_000} из ${s.total / 1_000_000} МБ"
                    bar.progress = (s.done * 1000 / max(1L, s.total)).toInt()
                }
                is VoiceModel.State.Failed -> {
                    text.text = s.message
                    d.getButton(AlertDialog.BUTTON_POSITIVE)?.text = "Повторить"
                    d.getButton(AlertDialog.BUTTON_NEGATIVE)?.text = "Закрыть"
                }
                VoiceModel.State.Done -> {
                    VoiceModel.unlisten(listener)
                    if (d.isShowing) d.dismiss()
                    if (!a.isFinishing && !a.isDestroyed && !closedByUser) onReady()
                    else Toast.makeText(a.applicationContext, "Модель распознавания речи скачана — нажмите микрофон в заметке", Toast.LENGTH_LONG).show()
                }
                VoiceModel.State.Idle -> {}
            }
        }
        listener = { show(it) }
        VoiceModel.listen(listener)
        d.setOnShowListener {
            show(VoiceModel.state.takeIf { VoiceModel.isRunning || it is VoiceModel.State.Failed } ?: VoiceModel.State.Running(0, VoiceModel.totalBytes))
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (VoiceModel.state is VoiceModel.State.Failed && !VoiceModel.isRunning) {
                    VoiceModel.download(a)
                    text.text = "Подключаюсь к github.com…"
                    d.getButton(AlertDialog.BUTTON_POSITIVE)?.text = "Свернуть"
                    d.getButton(AlertDialog.BUTTON_NEGATIVE)?.text = "Остановить"
                } else {
                    closedByUser = true // качается дальше; по готовности — сообщение
                    d.dismiss()
                }
            }
            d.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                if (VoiceModel.isRunning) VoiceModel.cancel()
                VoiceModel.unlisten(listener)
                d.dismiss()
            }
        }
        d.setOnCancelListener { closedByUser = true }
        d.show()
        lastDialog = d
    }
}

/** Полоска громкости: столбики, последние значения справа. */
class VoiceLevelView(ctx: Context) : View(ctx) {
    private val values = FloatArray(7)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.BAD }
    private val r = RectF()

    var level: Float = 0f
        set(v) {
            field = v
            System.arraycopy(values, 1, values, 0, values.size - 1)
            values[values.size - 1] = v
            invalidate()
        }

    var active = true
        set(v) { field = v; paint.color = if (v) Ui.BAD else Ui.MUTED; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val n = values.size
        val gap = width / (n * 2f)
        val w = gap
        for (i in 0 until n) {
            val h = max(height * 0.18f, height * values[i])
            val x = i * 2 * gap + gap / 2
            r.set(x, (height - h) / 2, x + w, (height + h) / 2)
            canvas.drawRoundRect(r, w / 2, w / 2, paint)
        }
    }
}
