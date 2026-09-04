package cat.doorman.app.report

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings
import cat.doorman.app.limits.Journal
import java.time.LocalDate
import java.time.ZoneId

/**
 * Two finished weeks of time spent in the apps Doorman holds.
 *
 * Null everywhere means "not available", never zero: a week with no data and a
 * week with nothing in it are different facts, and reporting the first as the
 * second would be Doorman claiming credit for a silence it cannot hear.
 */
data class WatchedUse(val lastWeekMillis: Long, val weekBeforeMillis: Long?)

/**
 * Android's own usage figures, borrowed rather than collected.
 *
 * This answers the one question Doorman cannot answer about itself. Doorman
 * only sees the screens it watches, and a blocked screen spends no minutes at
 * all, so its own numbers can never say whether somebody is on their phone
 * less -- only whether it stopped them more often, which is not the same thing
 * and can even move the other way.
 *
 * Asked for only when somebody opens the report, and refusable with no loss:
 * every other figure still works. Usage access is a wide permission -- it can
 * read the time spent in every installed app -- and Doorman is the wrong app to
 * take it lightly. It is read at the moment the report is drawn, only for the
 * apps the user chose to hold, never stored, and never sent anywhere; Doorman
 * has no network permission at all.
 */
object UsageAccess {

    fun granted(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * There is no dialog for this one. Usage access is granted from a list in
     * Android's own settings, so the most an app can do is open the list.
     */
    fun settingsIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

    fun lastTwoWeeks(context: Context, packages: Set<String>, today: LocalDate): WatchedUse? {
        if (packages.isEmpty() || !granted(context)) return null
        val usage = context.getSystemService(UsageStatsManager::class.java) ?: return null
        val lastMonday = Journal.weekOf(today).minusWeeks(1)
        return runCatching {
            val lastWeek = total(usage, packages, lastMonday) ?: return null
            WatchedUse(
                lastWeekMillis = lastWeek,
                weekBeforeMillis = total(usage, packages, lastMonday.minusWeeks(1)),
            )
        }.getOrNull()
    }

    /**
     * Null when Android has kept nothing for that week.
     *
     * Daily buckets, summed by hand, rather than queryAndAggregateUsageStats.
     * The aggregating call is free to answer from weekly or monthly buckets
     * that reach outside the dates asked for, and it then reports time spent
     * outside the week as time spent inside it -- which is how a fortnight of
     * ordinary use becomes thirty-eight hours. A daily bucket belongs to
     * exactly one day, so keeping only the ones that start inside the window
     * counts every minute once.
     *
     * The cost is honesty about retention: Android keeps daily buckets for
     * about a week, so the week before last is often simply gone. That is
     * reported as "no history", never as zero.
     */
    private fun total(
        usage: UsageStatsManager,
        packages: Set<String>,
        monday: LocalDate,
    ): Long? {
        val zone = ZoneId.systemDefault()
        val from = monday.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = monday.plusWeeks(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val buckets = usage.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, from, to)
            ?.filter { it.firstTimeStamp in from until to }
            .orEmpty()
        if (buckets.isEmpty()) return null
        return buckets.filter { it.packageName in packages }.sumOf { it.totalTimeInForeground }
    }
}
