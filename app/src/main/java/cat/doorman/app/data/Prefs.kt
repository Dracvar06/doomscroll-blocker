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
import cat.doorman.app.limits.LimitsCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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
        val CHANGE_DELAY_CHOICES = listOf(0, 10, 30, 120)
    }
}
