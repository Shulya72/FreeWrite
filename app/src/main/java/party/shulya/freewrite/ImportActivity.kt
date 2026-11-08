package party.shulya.freewrite

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Открыть файл доски (`.freewrite`): из Telegram, WhatsApp, «Файлов» или кнопкой в Ежедневнике.
 * Новая доска — добавляется, знакомая — в неё вливаются правки коллеги. Потом — «Открыть доску».
 */
class ImportActivity : Activity() {

    private lateinit var message: TextView
    private lateinit var buttons: LinearLayout
    private var started = false

    private fun dp(v: Float) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        val uri = sourceUri(intent)
        if (uri == null) {
            showError("Не нашёл файл. Откройте файл доски (.freewrite) ещё раз.")
            return
        }
        if (started) return
        started = true
        Thread {
            val r = runCatching {
                (contentResolver.openInputStream(uri) ?: throw IllegalArgumentException("Файл не открылся")).use {
                    BoardShare.import(this, it)
                }
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                r.onSuccess { done(it) }.onFailure { t ->
                    android.util.Log.w("FreeWrite", "import", t)
                    val why = if (t is IllegalArgumentException) t.message else "файл повреждён или это не доска FreeWrite (${t.javaClass.simpleName})"
                    showError("Не удалось открыть: $why.")
                }
            }
        }.start()
    }

    @Suppress("DEPRECATION")
    private fun sourceUri(i: Intent?): Uri? {
        i ?: return null
        val clip = i.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
        return if (i.action == Intent.ACTION_SEND) (i.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri) ?: clip
        else i.data ?: clip
    }

    private fun buildUi(): View {
        val outer = FrameLayout(this).apply { setBackgroundColor(Ui.DESK) }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(this@ImportActivity, Ui.PAPER, Ui.RULE, 16f)
            setPadding(dp(22f), dp(20f), dp(22f), dp(18f))
        }
        card.addView(Ui.text(this, "Файл доски FreeWrite", 20f, Ui.INK, bold = true))
        message = Ui.text(this, "Открываю и сверяю с вашими досками…", 16f, Ui.INK2).apply {
            tag = "import_message"
            setPadding(0, dp(10f), 0, dp(14f))
        }
        card.addView(message)
        buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        card.addView(buttons, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        // По ширине экрана, но не шире 520 dp (на телефоне — с полями по краям).
        val w = minOf(dp(520f), resources.displayMetrics.widthPixels - dp(32f))
        outer.addView(card, FrameLayout.LayoutParams(w, Ui.WRAP).apply { gravity = Gravity.CENTER })
        return outer
    }

    private fun button(text: String, primary: Boolean, tag: String, onClick: () -> Unit): TextView =
        Ui.text(this, text, 15f, if (primary) Color.WHITE else Ui.ACCENT, bold = true).apply {
            this.tag = tag
            background = if (primary) Ui.rounded(this@ImportActivity, Ui.ACCENT, null, 10f)
            else Ui.rounded(this@ImportActivity, Ui.PAPER, Ui.RULE, 10f)
            setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
            setOnClickListener { onClick() }
        }

    private fun addButton(v: View) {
        buttons.addView(v, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { marginStart = dp(10f) })
    }

    private fun done(r: BoardShare.Result) {
        message.text = r.message()
        buttons.removeAllViews()
        addButton(button("Закрыть", false, "import_close") { finish() })
        addButton(button("Открыть доску", true, "import_open") { openBoard(r.boardId) })
    }

    private fun showError(text: String) {
        message.text = text
        message.setTextColor(Ui.BAD)
        buttons.removeAllViews()
        addButton(button("Закрыть", true, "import_close") { finish() })
    }

    /** Открыть доску в FreeWrite «с чистого листа»: Ежедневник, поверх — доска (старый экран доски закрывается). */
    private fun openBoard(id: String) {
        val home = Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val board = Intent(this, BoardActivity::class.java).putExtra(BoardActivity.EXTRA_ID, id)
        startActivities(arrayOf(home, board))
        finish()
    }
}
