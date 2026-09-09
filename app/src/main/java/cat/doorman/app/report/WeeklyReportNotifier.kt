package cat.doorman.app.report

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import cat.doorman.app.R
import cat.doorman.app.limits.WeekSummary
import cat.doorman.app.ui.MainActivity
import cat.doorman.app.ui.durationText
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * The Monday morning nudge, and the alarm that brings it.
 *
 * One notification a week is the whole budget. Doorman is an app about not
 * being pulled at, and an app about not being pulled at that pulls at you has
 * lost the argument before it starts.
 */
object WeeklyReportNotifier {

    const val EXTRA_OPEN_REPORT = "cat.doorman.app.OPEN_REPORT"

    private const val CHANNEL = "weekly_report"
    private const val NOTIFICATION_ID = 1
    private const val REQUEST_ALARM = 100
    private const val REQUEST_OPEN = 101

    private const val SERVICE_CHANNEL = "service_status"
    private const val SERVICE_OFF_ID = 2
    private const val REQUEST_ACCESSIBILITY = 102

    /**
     * Books the next Monday.
     *
     * Idempotent: the same [PendingIntent] replaces rather than stacks, so this
     * can be called from anywhere that notices the app is alive without ever
     * producing two reports. It is called from [MainActivity] and from the
     * accessibility service connecting, which between them cover a reboot --
     * cheaper and quieter than asking for RECEIVE_BOOT_COMPLETED, since the
     * service has to come back for Doorman to work at all.
     *
     * The alarm is inexact on purpose. An exact one needs a permission the user
     * would have to be talked into, and it would buy nothing: nobody needs
     * their weekly summary to the second, and an inexact alarm lets Android
     * deliver it alongside whatever else it was waking up for.
     */
    fun schedule(context: Context, now: LocalDateTime = LocalDateTime.now()) {
        val at = WeeklySchedule.nextFireAt(now)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        // The channel is made here rather than at delivery so that switching
        // the report on is enough for it to appear in Android's own notification
        // settings. A channel that only exists after the first notification
        // cannot be tuned before the first notification.
        createChannel(context)
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        alarms.setWindow(
            AlarmManager.RTC_WAKEUP,
            at,
            TimeUnit.HOURS.toMillis(1),
            alarmIntent(context),
        )
    }

    /** Called when the report is switched off, so the alarm stops with it. */
    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(alarmIntent(context))
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    fun notify(context: Context, week: WeekSummary) {
        val manager = NotificationManagerCompat.from(context)
        createChannel(context)

        val res = context.resources
        val minutes = TimeUnit.MILLISECONDS.toMinutes(week.spentMillis).toInt()
        val body = buildString {
            // Zero gets its own sentence rather than a plural. Catalan and
            // Spanish have no zero quantity, and "stopped you 0 times" is the
            // sound of a form letter -- the one week the news is unambiguously
            // good is the worst week to sound like one.
            if (week.stops == 0) {
                append(res.getString(R.string.notify_week_none))
            } else {
                append(
                    res.getQuantityString(
                        R.plurals.notify_week_stops,
                        week.stops,
                        week.stops,
                        durationText(res, minutes),
                    ),
                )
            }
            // The loosenings question, asked only when there were any. A
            // fortnight of "you loosened 0 limits" trains somebody to stop
            // reading the sentence that matters.
            if (week.loosenings > 0) {
                append(" ")
                append(
                    res.getQuantityString(
                        R.plurals.notify_week_loosenings,
                        week.loosenings,
                        week.loosenings,
                    ),
                )
            }
        }

        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(context.getString(R.string.notify_week_title))
            .setContentText(body + topAppLine(context, week))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body + topAppLine(context, week)))
            .setContentIntent(openReportIntent(context))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        // areNotificationsEnabled covers both the Android 13 permission and a
        // channel the user has silenced by hand. Posting anyway would throw on
        // the first and be a lie on the second.
        if (manager.areNotificationsEnabled()) {
            @Suppress("MissingPermission")
            manager.notify(NOTIFICATION_ID, notification)
        }
    }

    /**
     * "Doorman is off." Posted only by [UpdateReceiver], only when it is true.
     *
     * Its own channel, so somebody who silences the weekly report does not
     * also silence the one notification that tells them nothing is being held.
     */
    fun notifyServiceOff(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        val channel = NotificationChannel(
            SERVICE_CHANNEL,
            context.getString(R.string.notify_channel_service),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = context.getString(R.string.notify_channel_service_help) }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)

        val open = PendingIntent.getActivity(
            context,
            REQUEST_ACCESSIBILITY,
            Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val body = context.getString(R.string.notify_service_off_body)
        val notification = NotificationCompat.Builder(context, SERVICE_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(context.getString(R.string.notify_service_off_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        if (manager.areNotificationsEnabled()) {
            @Suppress("MissingPermission")
            manager.notify(SERVICE_OFF_ID, notification)
        }
    }

    /** "Most of it in Instagram: 4 h 12 min." Only when there is something to say. */
    private fun topAppLine(context: Context, week: WeekSummary): String {
        val (pkg, millis) = week.apps.maxByOrNull { it.value } ?: return ""
        if (millis < 60_000L) return ""
        val label = runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis).toInt()
        return " " + context.getString(R.string.notify_week_top_app, label, durationText(context.resources, minutes))
    }

    private fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL,
            context.getString(R.string.notify_channel_weekly),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notify_channel_weekly_help)
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }

    private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_ALARM,
        Intent(context, WeeklyReportReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun openReportIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_OPEN,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_OPEN_REPORT, true),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
