package party.shulya.freewrite

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.widget.EditText
import android.widget.LinearLayout

/**
 * Кто работает на этом устройстве: имя и фамилия. Нужны, чтобы на общей доске было видно,
 * от кого пришла правка. Хранятся только на устройстве и уходят на сервер вместе с правками.
 */
object Profile {
    private const val PREFS = "profile"

    fun first(ctx: Context): String = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("first", "").orEmpty()
    fun last(ctx: Context): String = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("last", "").orEmpty()

    /** «Иван Петров» (пусто — ещё не представились). */
    fun name(ctx: Context): String = listOf(first(ctx), last(ctx)).filter { it.isNotBlank() }.joinToString(" ")

    fun isSet(ctx: Context) = first(ctx).isNotBlank()

    fun save(ctx: Context, first: String, last: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("first", first.trim().take(40)).putString("last", last.trim().take(40)).apply()
    }

    /** Отказался представляться при первом запуске — больше не спрашивать сразу при открытии. */
    fun skipped(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("skipped", false)

    private fun markSkipped(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("skipped", true).apply()
    }

    /**
     * Окно «Как вас зовут?». required — для общей доски (без имени дальше не идём: done не вызывается
     * при отмене). done — после сохранения.
     */
    fun ask(activity: Activity, required: Boolean, done: () -> Unit = {}) {
        val dp = { v: Float -> Ui.dp(activity, v) }
        val first = EditText(activity).apply {
            hint = "Имя"; isSingleLine = true; tag = "profile_first"
            setText(first(activity))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS or
                android.text.InputType.TYPE_TEXT_VARIATION_PERSON_NAME
        }
        val last = EditText(activity).apply {
            hint = "Фамилия"; isSingleLine = true; tag = "profile_last"
            setText(last(activity))
            inputType = first.inputType
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22f), dp(6f), dp(22f), 0)
            addView(Ui.text(activity, if (required) "Коллеги увидят, от кого пришла правка на общей доске."
            else "Для общих досок: коллеги увидят, от кого пришла правка. Можно указать позже.", 14f, Ui.INK2))
            addView(first)
            addView(last)
        }
        val b = AlertDialog.Builder(activity)
            .setTitle("Как вас зовут?")
            .setView(box)
            .setPositiveButton("Готово", null)
            .setNegativeButton(if (required) "Отмена" else "Позже") { _, _ -> if (!required) markSkipped(activity) }
        val dialog = b.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (first.text.isBlank()) {
                    first.error = "Напишите имя"
                    return@setOnClickListener
                }
                save(activity, first.text.toString(), last.text.toString())
                dialog.dismiss()
                done()
            }
        }
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        first.requestFocus()
    }

    /** Имя уже есть — сразу done; нет — спросить (обязательно). */
    fun require(activity: Activity, done: () -> Unit) {
        if (isSet(activity)) done() else ask(activity, required = true, done = done)
    }
}

/**
 * Перо или палец. Как в Excalidraw: пока устройство ни разу не видело перо (обычный телефон),
 * палец работает выбранным инструментом, а двумя пальцами доску двигают и приближают.
 * Как только коснулись пером (планшет с пером), перо — инструмент, палец — двигать доску.
 */
object Input {
    private const val PREFS = "input"

    fun stylusSeen(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("stylus", false)

    fun markStylus(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.getBoolean("stylus", false)) p.edit().putBoolean("stylus", true).apply()
    }

    /** Телефон (узкий экран): инструменты — внизу, сверху — короткая полоса. */
    fun isPhone(ctx: Context) = ctx.resources.configuration.smallestScreenWidthDp in 1 until 600
}
