package cat.doorman.app.rules

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.Json

object RuleLoader {

    private val json = Json { ignoreUnknownKeys = true }

    fun load(context: Context): RuleSet = runCatching {
        val text = context.assets.open("rules.json").bufferedReader().use { it.readText() }
        json.decodeFromString<RuleSet>(text)
    }.onFailure {
        Log.e("Doorman", "could not read rules.json; blocking nothing", it)
    }.getOrDefault(RuleSet())

    /** Every known screen mapped to the value it ships with. */
    fun screenDefaults(rules: RuleSet): Map<String, Boolean> =
        rules.apps.values
            .flatMap { it.screens }
            .associate { it.id to it.defaultEnabled }

    fun defaultEnabledScreenIds(rules: RuleSet): Set<String> =
        screenDefaults(rules).filterValues { it }.keys
}
