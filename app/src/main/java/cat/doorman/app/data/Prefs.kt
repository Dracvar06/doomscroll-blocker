package cat.doorman.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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
    }

    /**
     * Which screens are blocked, for the rules currently shipped.
     *
     * [screenDefaults] is every known screen id mapped to the value it ships
     * with, so screens added by a later update can adopt their default while
     * every explicit choice is preserved. See [ScreenPreferences].
     */
    fun enabledScreenIds(screenDefaults: Map<String, Boolean>): Flow<Set<String>> =
        context.dataStore.data.map { prefs ->
            ScreenPreferences.effectiveEnabled(
                screenDefaults = screenDefaults,
                decided = decidedIn(prefs),
                enabled = prefs[Keys.ENABLED_SCREENS].orEmpty(),
            )
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

    /** A preset is a decision about every screen at once. */
    suspend fun setEnabledScreens(screenIds: Set<String>, allScreenIds: Set<String>) {
        context.dataStore.edit { prefs ->
            prefs[Keys.DECIDED_SCREENS] = allScreenIds
            prefs[Keys.ENABLED_SCREENS] = screenIds
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
        val CHANGE_DELAY_CHOICES = listOf(0, 10, 30, 120)
    }
}
