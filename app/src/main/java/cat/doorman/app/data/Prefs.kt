package cat.doorman.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import cat.doorman.app.limits.Allowances
import cat.doorman.app.limits.Limits
import cat.doorman.app.limits.DayRecord
import cat.doorman.app.limits.Journal
import cat.doorman.app.limits.LimitsCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import org.json.JSONObject

private val Context.dataStore by preferencesDataStore(name = "doorman")

/**
 * Everything the user has decided. Nothing here leaves the device.
 */
class Prefs(private val context: Context) {

    private object Keys {
        /** Legacy; only read, to migrate installs made before per-screen decisions. */
        val CONFIGURED = booleanPreferencesKey("configured")
        val ENABLED_SCREENS = stringSetPreferencesKey("enabled_screens")
        val DECIDED_SCREENS = stringSetPreferencesKey("decided_screens")
        val WAIT_SECONDS = intPreferencesKey("wait_seconds")
        val PASS_MINUTES = intPreferencesKey("pass_minutes")
        val CHANGE_DELAY_SECONDS = intPreferencesKey("change_delay_seconds")
        val PASS_PACKAGE = stringPreferencesKey("pass_package")
        val PASS_EXPIRES_AT = longPreferencesKey("pass_expires_at")

        /** Package -> whether its card is open, for apps with rules. */
        val CARDS_OPEN = stringPreferencesKey("cards_open")

        /** Target id -> mode, as {"ig_reels":"5d"}. See ModeCodec. */
        val MODES = stringPreferencesKey("modes")

        /** Target id -> {"p":periodKey,"m":millis}: the allowance ledger. */
        val SPENT = stringPreferencesKey("spent")

        /** One row per day of what actually happened. See [Journal]. */
        val JOURNAL = stringPreferencesKey("journal")

        /** Whether the weekly report is kept at all. */
        val WEEKLY_REPORT = booleanPreferencesKey("weekly_report")

        /** Whether the first-run walkthrough has been finished or skipped. */
        val TUTORIAL_SEEN = booleanPreferencesKey("tutorial_seen")

        /**
         * Removed. Doorman used to record which apps it had watched the user
         * open, to offer them in the picker.
         *
         * It was accurate about what it did and still the wrong thing: an app
         * with accessibility access keeping a list of everything you open reads
         * as being watched, whatever the list is for. Feeling surveilled is a
         * cost even when the data never leaves the phone, and Doorman asks for
         * a lot of trust already. The picker lists every installed app instead,
         * which needs no record of anything.
         *
         * Kept only so the old list can be deleted from installs that have one.
         */
        val SEEN_APPS = stringPreferencesKey("seen_apps")
    }

    /**
     * How strictly each known screen is held.
     *
     * [screenDefaults] is every known screen id mapped to the value it ships
     * with, so screens added by a later update can adopt their default while
     * every explicit choice is preserved. See [ScreenPreferences].
     */
    fun screenLimits(screenDefaults: Map<String, Boolean>): Flow<Map<String, Limits>> =
        context.dataStore.data.map { prefs ->
            ScreenPreferences.effectiveLimits(
                screenDefaults = screenDefaults,
                decided = decidedIn(prefs),
                enabled = prefs[Keys.ENABLED_SCREENS].orEmpty(),
                stored = decodeModes(prefs[Keys.MODES]),
            )
        }

    /**
     * Modes for things with no shipped default: whole apps, including games and
     * anything else the user has added. Absent means [BlockMode.Off].
     */
    val appLimits: Flow<Map<String, Limits>> =
        context.dataStore.data.map { prefs ->
            decodeModes(prefs[Keys.MODES]).filterKeys { it.contains('.') }
        }

    /**
     * Deletes the record of watched apps left by earlier versions.
     *
     * An upgrade that merely stopped adding to it would leave the old list
     * sitting in storage, which is not the same as not having kept one.
     */
    suspend fun forgetWatchedApps() {
        context.dataStore.edit { prefs ->
            if (prefs[Keys.SEEN_APPS] != null) prefs.remove(Keys.SEEN_APPS)
        }
    }

    /**
     * Which app cards the user has opened or closed.
     *
     * Only explicit choices are stored. An app with no entry falls back to
     * whether it is installed, so a phone without TikTok does not open on a
     * screenful of TikTok settings, and one with it does not hide them.
     */
    val openCards: Flow<Map<String, Boolean>> =
        context.dataStore.data.map { prefs ->
            val raw = prefs[Keys.CARDS_OPEN] ?: return@map emptyMap()
            runCatching {
                val json = JSONObject(raw)
                json.keys().asSequence().associateWith { json.optBoolean(it) }
            }.getOrDefault(emptyMap())
        }

    suspend fun setCardOpen(packageName: String, open: Boolean) {
        context.dataStore.edit { prefs ->
            val json = runCatching { JSONObject(prefs[Keys.CARDS_OPEN] ?: "{}") }
                .getOrDefault(JSONObject())
            json.put(packageName, open)
            prefs[Keys.CARDS_OPEN] = json.toString()
        }
    }

    /** Time already spent, per target. */
    val spent: Flow<Map<String, Allowances.Spent>> =
        context.dataStore.data.map { prefs -> decodeSpent(prefs[Keys.SPENT]) }

    suspend fun setLimits(targetId: String, limits: Limits) {
        context.dataStore.edit { prefs ->
            val all = decodeModes(prefs[Keys.MODES]) + (targetId to limits)
            prefs[Keys.MODES] = encodeModes(all)
            // Keep the old on/off record in step, so a downgrade or a stale
            // read never resurrects a decision the user has since changed.
            prefs[Keys.DECIDED_SCREENS] = decidedIn(prefs) + targetId
            val enabled = prefs[Keys.ENABLED_SCREENS].orEmpty()
            prefs[Keys.ENABLED_SCREENS] =
                if (limits.isOff) enabled - targetId else enabled + targetId
        }
    }

    /** Adds time to the ledger for several targets at once. */
    /**
     * What actually happened, day by day, for the weekly report.
     *
     * Separate from [spent], which is a live budget that resets every hour, day
     * or week and cannot answer a question about last month. This is history,
     * and it is the only part of Doorman that keeps any.
     */
    val journal: Flow<List<DayRecord>> =
        context.dataStore.data.map { decodeJournal(it[Keys.JOURNAL]) }

    /**
     * Whether Doorman keeps a record of how the week went.
     *
     * On by default, because the report is only ever a count of what Doorman
     * did and the app is no use to somebody who cannot see whether it is
     * working. Off means off: nothing new is written, and the report has no
     * door in the navigation bar. What was already recorded is left alone
     * rather than deleted, so turning the switch back on after a mistaken tap
     * does not cost the user their history; it ages out on its own within
     * [Journal.KEEP_DAYS] days.
     */
    val weeklyReport: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.WEEKLY_REPORT] ?: true }

    /**
     * Whether the walkthrough has been shown.
     *
     * Skipping counts as seeing it: somebody who closed it once meant it, and
     * an introduction that comes back is not an introduction.
     */
    val tutorialSeen: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.TUTORIAL_SEEN] ?: false }

    suspend fun setTutorialSeen(seen: Boolean) {
        context.dataStore.edit { it[Keys.TUTORIAL_SEEN] = seen }
    }

    suspend fun setWeeklyReport(on: Boolean) {
        context.dataStore.edit { it[Keys.WEEKLY_REPORT] = on }
    }

    /**
     * Adds to today's row and forgets anything too old to matter.
     *
     * Pruning here rather than on a schedule: this is the only place the record
     * is written, so it is the only place it can grow, and a record that tidies
     * itself needs no job to remember to run.
     */
    suspend fun recordToday(stops: Int = 0, spentMillis: Long = 0L, loosenings: Int = 0) {
        val today = java.time.LocalDate.now()
        context.dataStore.edit { prefs ->
            // Checked here, inside the write, rather than at each call site:
            // there is one place history can be created and so one place the
            // switch has to be honoured for it to mean anything.
            if (prefs[Keys.WEEKLY_REPORT] == false) return@edit
            val updated = Journal.record(
                days = decodeJournal(prefs[Keys.JOURNAL]),
                date = today,
                stops = stops,
                spentMillis = spentMillis,
                loosenings = loosenings,
            )
            prefs[Keys.JOURNAL] = journalJson.encodeToString(Journal.prune(updated, today))
        }
    }

    private fun decodeJournal(raw: String?): List<DayRecord> {
        if (raw.isNullOrBlank()) return emptyList()
        // A record that cannot be read is a record we start again, never a
        // crash: history is the least important thing in the app and the report
        // is not worth failing to open Doorman over.
        return runCatching { journalJson.decodeFromString<List<DayRecord>>(raw) }
            .getOrDefault(emptyList())
    }

    suspend fun chargeSpent(charges: Map<String, Allowances.Spent>) {
        if (charges.isEmpty()) return
        context.dataStore.edit { prefs ->
            prefs[Keys.SPENT] = encodeSpent(decodeSpent(prefs[Keys.SPENT]) + charges)
        }
    }

    private fun decodeModes(raw: String?): Map<String, Limits> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            json.keys().asSequence().mapNotNull { key ->
                LimitsCodec.decode(json.optString(key))?.let { key to it }
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    private fun encodeModes(all: Map<String, Limits>): String {
        val json = JSONObject()
        all.forEach { (id, limits) -> json.put(id, LimitsCodec.encode(limits)) }
        return json.toString()
    }

    private fun decodeSpent(raw: String?): Map<String, Allowances.Spent> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            json.keys().asSequence().mapNotNull { key ->
                val entry = json.optJSONObject(key) ?: return@mapNotNull null
                key to Allowances.Spent(entry.optString("p"), entry.optLong("m"))
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    private fun encodeSpent(spent: Map<String, Allowances.Spent>): String {
        val json = JSONObject()
        spent.forEach { (id, s) ->
            json.put(id, JSONObject().put("p", s.periodKey).put("m", s.millis))
        }
        return json.toString()
    }

    /**
     * Installs predating per-screen decisions only recorded a flat "configured"
     * flag, so the screens they had switched on are the ones we can honestly say
     * they chose. Anything else falls back to its default.
     */
    private fun decidedIn(prefs: androidx.datastore.preferences.core.Preferences): Set<String> =
        prefs[Keys.DECIDED_SCREENS]
            ?: if (prefs[Keys.CONFIGURED] == true) prefs[Keys.ENABLED_SCREENS].orEmpty() else emptySet()

    suspend fun setScreenEnabled(screenId: String, enabled: Boolean) {
        context.dataStore.edit { prefs ->
            val currentlyEnabled = prefs[Keys.ENABLED_SCREENS].orEmpty()
            prefs[Keys.DECIDED_SCREENS] = decidedIn(prefs) + screenId
            prefs[Keys.ENABLED_SCREENS] =
                if (enabled) currentlyEnabled + screenId else currentlyEnabled - screenId
        }
    }

    /**
     * A preset is a decision about every screen at once.
     *
     * It has to write modes as well as the old on/off sets. A stored mode wins
     * over everything else when the effective mode is worked out, so a preset
     * that only touched the old sets would appear to do nothing on any screen
     * the user had already given an allowance.
     */
    suspend fun setEnabledScreens(screenIds: Set<String>, allScreenIds: Set<String>) {
        context.dataStore.edit { prefs ->
            prefs[Keys.DECIDED_SCREENS] = allScreenIds
            prefs[Keys.ENABLED_SCREENS] = screenIds
            val kept = decodeModes(prefs[Keys.MODES]).filterKeys { it !in allScreenIds }
            prefs[Keys.MODES] = encodeModes(
                kept + allScreenIds.associateWith {
                    if (it in screenIds) Limits.BLOCKED else Limits.OFF
                },
            )
        }
    }

    val waitSeconds: Flow<Int> =
        context.dataStore.data.map { it[Keys.WAIT_SECONDS] ?: DEFAULT_WAIT_SECONDS }

    val passMinutes: Flow<Int> =
        context.dataStore.data.map { it[Keys.PASS_MINUTES] ?: DEFAULT_PASS_MINUTES }

    suspend fun setWaitSeconds(seconds: Int) {
        context.dataStore.edit { it[Keys.WAIT_SECONDS] = seconds }
    }

    suspend fun setPassMinutes(minutes: Int) {
        context.dataStore.edit { it[Keys.PASS_MINUTES] = minutes }
    }

    /** The one pass that may be active, as (package, expiry). */
    /**
     * How long weakening a block waits before it takes effect.
     *
     * Switching a block *on* is never delayed: friction belongs on the decision
     * you would regret, not on the one you would thank yourself for.
     */
    val changeDelaySeconds: Flow<Int> =
        context.dataStore.data.map { it[Keys.CHANGE_DELAY_SECONDS] ?: DEFAULT_CHANGE_DELAY_SECONDS }

    suspend fun setChangeDelaySeconds(seconds: Int) {
        context.dataStore.edit { it[Keys.CHANGE_DELAY_SECONDS] = seconds }
    }

    val activePass: Flow<Pass?> =
        context.dataStore.data.map { prefs ->
            val pkg = prefs[Keys.PASS_PACKAGE] ?: return@map null
            val expiresAt = prefs[Keys.PASS_EXPIRES_AT] ?: return@map null
            Pass(pkg, expiresAt)
        }

    suspend fun grantPass(packageName: String, durationMillis: Long, now: Long) {
        context.dataStore.edit { prefs ->
            prefs[Keys.PASS_PACKAGE] = packageName
            prefs[Keys.PASS_EXPIRES_AT] = now + durationMillis
        }
    }

    suspend fun clearPass() {
        context.dataStore.edit { prefs ->
            prefs.remove(Keys.PASS_PACKAGE)
            prefs.remove(Keys.PASS_EXPIRES_AT)
        }
    }

    data class Pass(val packageName: String, val expiresAt: Long) {
        fun isActiveAt(now: Long): Boolean = now < expiresAt
    }

    companion object {
        const val DEFAULT_WAIT_SECONDS = 30
        const val DEFAULT_PASS_MINUTES = 2
        /**
         * No wait, until the user asks for one.
         *
         * The wait is the app's best idea and still the wrong thing to meet
         * first: setting Doorman up means changing a dozen switches, and making
         * someone sit out half a minute on each one before they have felt any
         * benefit is how an app gets uninstalled during setup. It is one tap
         * away, explained where it sits, and the people who need it are the
         * ones who go looking for it.
         */
        const val DEFAULT_CHANGE_DELAY_SECONDS = 0
        const val MAX_SEEN_APPS = 60
        /**
         * The wait is set on a dial now rather than picked from these, but the
         * ends of the range still have to be stated somewhere: nothing, and
         * five minutes. Past five it stops being a pause for thought.
         */
        const val MIN_CHANGE_DELAY_SECONDS = 0
        const val MAX_CHANGE_DELAY_SECONDS = 300
    }
}

private val journalJson = Json { ignoreUnknownKeys = true }
