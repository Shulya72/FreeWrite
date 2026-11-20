package party.shulya.freewrite

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.Instant
import java.time.ZoneId

/**
 * Оповещения календаря (0.29): «за час приходит уведомление». Один будильник — на ближайшее оповещение всех записей; сработал —
 * показать всё, что подошло, и поставить следующий. После перезагрузки, смены времени и обновления приложения — заново
 * ([PlanAlarmReceiver]). Пропущенные больше чем на 30 минут (планшет был выключен) не показываются — чтобы не сыпались пачкой.
 */
object PlanAlarms {
    const val CHANNEL = "plan"
    const val ACTION = "party.shulya.freewrite.PLAN_ALARM"
    private const val PREFS = "plan"
    private const val MISSED_MS = 30 * 60_000L
    private const val EARLY_MS = 30_000L

    /** Подменяется в проверках. */
    @JvmStatic var clock: () -> Long = { System.currentTimeMillis() }
    var zone: ZoneId = ZoneId.systemDefault()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun pending(ctx: Context): PendingIntent = PendingIntent.getBroadcast(ctx, 7301,
        Intent(ctx, PlanAlarmReceiver::class.java).setAction(ACTION), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** Поставить будильник на ближайшее оповещение (или снять, если оповещений нет). Возвращает время или null. */
    fun reschedule(ctx: Context, items: List<PlanItem> = PlanStore(ctx).items): Long? {
        val now = clock()
        val p = prefs(ctx)
        var fired = p.getLong("fired", 0L)
        if (fired <= 0L || fired > now + 86_400_000L) { fired = now; p.edit().putLong("fired", now).apply() }
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val next = Plan.nextAlert(items, maxOf(fired, now - MISSED_MS), zone)
        if (next == null) { am.cancel(pending(ctx)); return null }
        val at = maxOf(next.at, now + 1000)
        runCatching {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(ctx))
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(ctx))
        }.onFailure {
            runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(ctx)) }
            runCatching { Trail.add("Задачи: будильник без точного времени — ${it.message}") }
        }
        return at
    }

    /** Будильник сработал: показать подошедшие оповещения, поставить следующий. Возвращает, сколько показали. */
    fun fire(ctx: Context): Int {
        val now = clock()
        val p = prefs(ctx)
        val fired = p.getLong("fired", 0L).takeIf { it > 0 } ?: (now - MISSED_MS)
        val store = PlanStore(ctx)
        val upTo = now + EARLY_MS
        val list = Plan.due(store.items, maxOf(fired, now - MISSED_MS), upTo, zone)
        for (d in list) notify(ctx, d)
        p.edit().putLong("fired", maxOf(fired, upTo)).apply()
        reschedule(ctx, store.items)
        return list.size
    }

    fun channel(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Задачи и события", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Оповещения календаря ViaDesk"
                enableVibration(true)
            })
        }
    }

    /** Текст уведомления: «12:00–13:00 · Участок 3» и «через 1 ч» / «завтра». */
    fun text(d: Plan.Due): String {
        val o = PlanOcc(d.item, d.day)
        val parts = ArrayList<String>()
        val today = Instant.ofEpochMilli(clock()).atZone(zone).toLocalDate()
        val whenDay = if (d.day == today) "" else Plan.relDays(d.day, today).replaceFirstChar { it.uppercase() } + ", "
        parts += whenDay + Plan.timeText(o)
        if (d.item.place.isNotEmpty()) parts += d.item.place
        return parts.joinToString(" · ")
    }

    private fun notify(ctx: Context, d: Plan.Due) {
        channel(ctx)
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val open = Intent(ctx, HomeActivity::class.java)
            .putExtra(HomeActivity.EXTRA_PLAN_DAY, d.day.toString())
            .putExtra(HomeActivity.EXTRA_PLAN_ITEM, d.item.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(ctx, d.key, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(if (d.item.isTask) R.drawable.ic_task else R.drawable.ic_calendar)
            .setContentTitle(d.item.title.ifBlank { if (d.item.isTask) "Задача" else "Событие" })
            .setContentText(text(d))
            .setColor(Plan.cal(d.item.cal).color)
            .setCategory(if (d.item.isTask) Notification.CATEGORY_REMINDER else Notification.CATEGORY_EVENT)
            .setWhen(PlanOcc(d.item, d.day).start.atZone(zone).toInstant().toEpochMilli())
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        runCatching { nm.notify(d.key, n) }.onFailure { runCatching { Trail.add("Задачи: уведомление не показано — ${it.message}") } }
    }
}

/** Будильник календаря, перезагрузка, смена времени, обновление приложения. */
class PlanAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runCatching {
            if (intent.action == PlanAlarms.ACTION) PlanAlarms.fire(context) else PlanAlarms.reschedule(context)
        }.onFailure { runCatching { Trail.add("Задачи: ошибка оповещения — $it") } }
    }
}
