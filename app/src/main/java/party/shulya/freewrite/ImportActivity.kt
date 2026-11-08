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
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/**
 * Присланное в FreeWrite — из Telegram, WhatsApp, «Файлов» или кнопкой в Ежедневнике:
 *  - файл доски (`.freewrite`): новая доска добавляется, в знакомую вливаются правки коллеги;
 *  - PDF или картинки («Поделиться → FreeWrite»): спрашиваем, на какую доску положить, и открываем её.
 */
class ImportActivity : Activity() {

    private lateinit var heading: TextView
    private lateinit var message: TextView
    private lateinit var list: LinearLayout
    private lateinit var buttons: LinearLayout
    private var started = false

    private fun dp(v: Float) = Ui.dp(this, v)

    /** Что пришло: доска (уже влита) или файлы, которые положить на доску. */
    private sealed class Received {
        class Board(val result: BoardShare.Result) : Received()
        class Insert(val files: List<File>, val name: String?, val pdf: Boolean) : Received()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        val uris = sourceUris(intent)
        if (uris.isEmpty()) {
            showError("Не нашёл файл. Откройте файл доски (.freewrite) ещё раз.")
            return
        }
        if (started) return
        started = true
        message.text = if (looksLikeInsert(intent)) "Открываю…" else "Открываю и сверяю с вашими досками…"
        Thread {
            val r = runCatching { receive(uris) }
            runOnUiThread {
                if (isDestroyed) { r.getOrNull()?.let { (it as? Received.Insert)?.files?.forEach(File::delete) }; return@runOnUiThread }
                r.onSuccess { got ->
                    when (got) {
                        is Received.Board -> done(got.result)
                        is Received.Insert -> chooseBoard(got)
                    }
                }.onFailure { t ->
                    android.util.Log.w("FreeWrite", "import", t)
                    val why = if (t is IllegalArgumentException) t.message else "файл повреждён или это не доска FreeWrite (${t.javaClass.simpleName})"
                    showError("Не удалось открыть: $why.")
                }
            }
        }.start()
    }

    @Suppress("DEPRECATION")
    private fun sourceUris(i: Intent?): List<Uri> {
        i ?: return emptyList()
        val out = ArrayList<Uri>()
        when (i.action) {
            Intent.ACTION_SEND -> (i.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)?.let { out += it }
            Intent.ACTION_SEND_MULTIPLE -> i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { out += it }
            else -> i.data?.let { out += it }
        }
        if (out.isEmpty()) i.clipData?.let { c -> for (k in 0 until c.itemCount) c.getItemAt(k).uri?.let { out += it } }
        return out.distinct()
    }

    private fun looksLikeInsert(i: Intent): Boolean {
        val t = i.type.orEmpty()
        return t == "application/pdf" || t.startsWith("image/") || i.action == Intent.ACTION_SEND_MULTIPLE
    }

    /** Фоновый поток: взять копии присланного и понять, что это. */
    private fun receive(uris: List<Uri>): Received {
        val name = BoardPdf.displayName(this, uris[0])
        val files = ArrayList<File>()
        try {
            for (u in uris.take(BoardPdf.MAX_PAGES)) files += BoardPdf.copyIn(this, u, ".in")
            val kinds = files.map { BoardPdf.kindOf(it) }
            if (kinds.any { it != BoardPdf.Kind.OTHER }) {
                // PDF и картинки — положить на доску; прочее среди них пропускаем.
                val keep = files.filterIndexed { k, _ -> kinds[k] != BoardPdf.Kind.OTHER }
                files.filter { it !in keep }.forEach { it.delete() }
                return Received.Insert(keep, name, kinds.any { it == BoardPdf.Kind.PDF })
            }
            if (looksLikeInsert(intent)) throw IllegalArgumentException("это не PDF и не картинка")
            val r = files[0].inputStream().use { BoardShare.import(this, it) }
            files.forEach { it.delete() }
            return Received.Board(r)
        } catch (t: Throwable) {
            files.forEach { it.delete() }
            throw t
        }
    }

    private fun buildUi(): View {
        val outer = FrameLayout(this).apply { setBackgroundColor(Ui.DESK) }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(this@ImportActivity, Ui.PAPER, Ui.RULE, 16f)
            setPadding(dp(22f), dp(20f), dp(22f), dp(18f))
        }
        heading = Ui.text(this, if (looksLikeInsert(intent)) "На доску FreeWrite" else "Файл доски FreeWrite", 20f, Ui.INK, bold = true)
        card.addView(heading)
        message = Ui.text(this, "Открываю и сверяю с вашими досками…", 16f, Ui.INK2).apply {
            tag = "import_message"
            setPadding(0, dp(10f), 0, dp(14f))
        }
        card.addView(message)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // Длинный список досок прокручивается, окно не выше ~60 % экрана.
        val scroll = object : ScrollView(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val maxH = (resources.displayMetrics.heightPixels * 0.6f).toInt()
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST))
            }
        }.apply { addView(list) }
        card.addView(scroll, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
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

    // ---------------------------------------------------------------- PDF и картинки на доску

    private fun chooseBoard(got: Received.Insert) {
        val what = when {
            got.pdf -> "PDF" + (got.name?.let { " «$it»" } ?: "")
            got.files.size == 1 -> "Картинку"
            else -> "Картинки (${got.files.size})"
        }
        message.text = "$what — на какую доску положить?"
        val store = BoardStore(this)
        val boards = store.list()
        list.removeAllViews()
        val newTitle = got.name?.substringBeforeLast('.')?.trim()?.takeIf { it.isNotEmpty() && got.pdf }
            ?: ("Доска " + Ui.shortDate(System.currentTimeMillis()))
        list.addView(row("＋ Новая доска «$newTitle»", null, primary = true, tag = "insert_new") {
            val b = Board.create(newTitle)
            store.save(b)
            openBoard(b.id, got)
        })
        for ((i, m) in boards.withIndex()) {
            val sub = buildString {
                append(if (m.items == 0) "пустая" else "предметов: ${m.items}")
                if (m.shared) append(" · общая")
            }
            list.addView(row(m.title, sub, primary = false, tag = "insert_board_$i") { openBoard(m.id, got) })
        }
        buttons.removeAllViews()
        addButton(button("Отмена", false, "import_close") { got.files.forEach(File::delete); finish() })
    }

    private fun row(text: String, sub: String?, primary: Boolean, tag: String, onClick: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            this.tag = tag
            background = if (primary) Ui.rounded(this@ImportActivity, Ui.ACCENT_SOFT, Ui.ACCENT, 10f)
            else Ui.rounded(this@ImportActivity, Ui.PAPER, Ui.RULE, 10f)
            setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
            addView(Ui.text(this@ImportActivity, text, 16f, if (primary) Ui.ACCENT else Ui.INK, bold = true))
            if (sub != null) addView(Ui.text(this@ImportActivity, sub, 13f, Ui.MUTED))
            isClickable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { bottomMargin = dp(8f) }
        }

    /** Открыть доску в FreeWrite «с чистого листа»: Ежедневник, поверх — доска (старый экран доски закрывается). */
    private fun openBoard(id: String, insert: Received.Insert? = null) {
        val home = Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val board = Intent(this, BoardActivity::class.java).putExtra(BoardActivity.EXTRA_ID, id)
        if (insert != null) {
            board.putStringArrayListExtra(BoardActivity.EXTRA_INSERT, ArrayList(insert.files.map { it.path }))
            insert.name?.let { board.putExtra(BoardActivity.EXTRA_INSERT_NAME, it) }
        }
        startActivities(arrayOf(home, board))
        finish()
    }
}
