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
import cat.doorman.app.limits.BlockMode
import cat.doorman.app.limits.ModeCodec
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

        /** Target id -> mode, as {"ig_reels":"5d"}. See ModeCodec. */
        val MODES = stringPreferencesKey("modes")

        /** Target id -> {"p":periodKey,"m":millis}: the allowance ledger. */
        val SPENT = stringPreferencesKey("spent")

        /** Package -> when it was last in front, for the app picker. */
        val SEEN_APPS = stringPreferencesKey("seen_apps")
    }

    /**
     * How strictly each known screen is held.
     *
     * [screenDefaults] is every known screen id mapped to the value it ships
     * with, so screens added by a later update can adopt their default while
     * every explicit choice is preserved. See [ScreenPreferences].
     */
    fun screenModes(screenDefaults: Map<String, Boolean>): Flow<Map<String, BlockMode>> =
        context.dataStore.data.map { prefs ->
            ScreenPreferences.effectiveModes(
                screenDefaults = screenDefaults,
                decided = decidedIn(prefs),
                enabled = prefs[Keys.ENABLED_SCREENS].orEmpty(),
                storedModes = decodeModes(prefs[Keys.MODES]),
            )
        }

    /**
     * Modes for things with no shipped default: whole apps, including games and
     * anything else the user has added. Absent means [BlockMode.Off].
     */
    val appModes: Flow<Map<String, BlockMode>> =
        context.dataStore.data.map { prefs ->
            decodeModes(prefs[Keys.MODES]).filterKeys { it.contains('.') }
        }

    /** Time already spent, per target. */
    val spent: Flow<Map<String, Allowances.Spent>> =
        context.dataStore.data.map { prefs -> decodeSpent(prefs[Keys.SPENT]) }

    /**
     * Apps Doorman has watched the user open.
     *
     * Doorman cannot show a list of installed apps without asking for
     * permission to enumerate them, and it has no business knowing what else is
     * on the phone. It does see which app is in front, because that is the job.
     * So the picker offers the apps actually used, which is the shorter and more
     * useful list anyway.
     */
    val seenApps: Flow<Map<String, String>> =
        context.dataStore.data.map { prefs ->
            val raw = prefs[Keys.SEEN_APPS] ?: return@map emptyMap()
            runCatching {
                val json = JSONObject(raw)
                json.keys().asSequence().associateWith { key ->
                    json.optJSONObject(key)?.optString("l").orEmpty().ifEmpty { key }
                }
            }.getOrDefault(emptyMap())
        }

    suspend fun recordSeenApp(packageName: String, now: Long, label: String? = null) {
        context.dataStore.edit { prefs ->
            val json = runCatching { JSONObject(prefs[Keys.SEEN_APPS] ?: "{}") }
                .getOrDefault(JSONObject())
            json.put(
                packageName,
                JSONObject().put("t", now).put("l", label ?: packageName),
            )
            // Bounded, oldest first: this is a convenience list, not a history.
            if (json.length() > MAX_SEEN_APPS) {
                val oldest = json.keys().asSequence()
                    .sortedBy { json.optJSONObject(it)?.optLong("t") ?: 0L }
                    .take(json.length() - MAX_SEEN_APPS)
                    .toList()
                oldest.forEach { json.remove(it) }
            }
            prefs[Keys.SEEN_APPS] = json.toString()
        }
    }

    suspend fun forgetSeenApp(packageName: String) {
        context.dataStore.edit { prefs ->
            val json = runCatching { JSONObject(prefs[Keys.SEEN_APPS] ?: "{}") }
                .getOrDefault(JSONObject())
            json.remove(packageName)
            prefs[Keys.SEEN_APPS] = json.toString()
            val modes = decodeModes(prefs[Keys.MODES]) - packageName
            prefs[Keys.MODES] = encodeModes(modes)
        }
    }

    suspend fun setMode(targetId: String, mode: BlockMode) {
        context.dataStore.edit { prefs ->
            val modes = decodeModes(prefs[Keys.MODES]) + (targetId to mode)
            prefs[Keys.MODES] = encodeModes(modes)
            // Keep the old on/off record in step, so a downgrade or a stale
            // read never resurrects a decision the user has since changed.
            prefs[Keys.DECIDED_SCREENS] = decidedIn(prefs) + targetId
            val enabled = prefs[Keys.ENABLED_SCREENS].orEmpty()
            prefs[Keys.ENABLED_SCREENS] =
                if (mode == BlockMode.Off) enabled - targetId else enabled + targetId
        }
    }

    /** Adds time to the ledger for several targets at once. */
    suspend fun chargeSpent(charges: Map<String, Allowances.Spent>) {
        if (charges.isEmpty()) return
        context.dataStore.edit { prefs ->
            prefs[Keys.SPENT] = encodeSpent(decodeSpent(prefs[Keys.SPENT]) + charges)
        }
    }

    private fun decodeModes(raw: String?): Map<String, BlockMode> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            json.keys().asSequence().mapNotNull { key ->
                ModeCodec.decode(json.optString(key))?.let { key to it }
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    private fun encodeModes(modes: Map<String, BlockMode>): String {
        val json = JSONObject()
        modes.forEach { (id, mode) -> json.put(id, ModeCodec.encode(mode)) }
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
                    if (it in screenIds) BlockMode.Blocked else BlockMode.Off
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
        const val DEFAULT_CHANGE_DELAY_SECONDS = 30
        const val MAX_SEEN_APPS = 60
        val CHANGE_DELAY_CHOICES = listOf(0, 10, 30, 120)
    }
}
