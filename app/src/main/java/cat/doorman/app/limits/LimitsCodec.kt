package cat.doorman.app.limits

import android.util.Log
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.time.LocalDate

/**
 * Turns limits into stored text and back.
 *
 * kotlinx.serialization rather than org.json, so the round trip can be tested
 * on a plain JVM. Android's org.json is a stub in unit tests, which means a
 * codec written against it can only be checked on a device -- and this codec
 * decides whether someone's settings survive an update, which is not something
 * to find out about later.
 *
 * It also reads the older one-word-per-target strings -- "blocked", "5d",
 * "off" -- that installs from before schedules existed are full of. Dropping
 * those on upgrade would silently unblock every screen someone had chosen.
 */
object LimitsCodec {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    @Serializable
    private data class StoredAllowance(
        @SerialName("m") val minutes: Int,
        @SerialName("p") val period: String,
        @SerialName("d") val days: Int = Days.ALL_MASK,
        /**
         * Held as raw JSON so that a taper this version cannot read costs the
         * taper and nothing else: typed, one bad field would fail the whole
         * allowance, and with it the limit.
         */
        @SerialName("t") val taper: JsonElement? = null,
    )

    @Serializable
    private data class StoredWindow(
        @SerialName("f") val from: Int,
        @SerialName("t") val to: Int,
        @SerialName("d") val days: Int = Days.ALL_MASK,
    )

    @Serializable
    private data class StoredReels(
        @SerialName("n") val reels: Int,
        @SerialName("m") val minutes: Int,
    )

    @Serializable
    private data class Stored(
        val blocked: Boolean = false,
        val allow: List<StoredAllowance> = emptyList(),
        val windows: List<StoredWindow> = emptyList(),
        val reels: StoredReels? = null,
        /** Raw JSON, so a damaged list costs the scope and not the whole limit. */
        val onlyOn: JsonElement? = null,
        val exceptOn: JsonElement? = null,
    )

    fun encode(limits: Limits): String = json.encodeToString(
        Stored(
            blocked = limits.blocked,
            allow = limits.allowances.map {
                StoredAllowance(it.minutes, it.period.name, it.days.mask, it.taper?.let(::encodeTaper))
            },
            windows = limits.windows.map {
                StoredWindow(it.fromMinute, it.toMinute, it.days.mask)
            },
            reels = limits.reels?.let { StoredReels(it.reels, it.minutes) },
            onlyOn = limits.onlyOn?.let { ids -> JsonArray(ids.sorted().map(::JsonPrimitive)) },
            exceptOn = limits.exceptOn?.let { ids -> JsonArray(ids.sorted().map(::JsonPrimitive)) },
        ),
    )

    /**
     * Null for anything unrecognised, rather than a guess. A stored value this
     * version cannot read is most likely one written by a newer version, and
     * inventing limits for it could unblock something the user had held.
     */
    fun decode(raw: String?): Limits? {
        if (raw.isNullOrBlank()) return null
        if (!raw.trimStart().startsWith("{")) return decodeLegacy(raw)
        return runCatching {
            val stored = json.decodeFromString<Stored>(raw)
            val exceptOn = decodeIds(stored.exceptOn, "exceptOn")
            // An exception list that cannot be read cannot say where messages
            // are, so the limit is kept off every screen rather than risk them.
            val onlyOn = if (exceptOn === UNREADABLE) emptySet() else decodeIds(stored.onlyOn, "onlyOn")
            Limits(
                blocked = stored.blocked,
                allowances = stored.allow.mapNotNull { entry ->
                    val period = runCatching { Period.valueOf(entry.period) }.getOrNull()
                        ?: return@mapNotNull null
                    if (entry.minutes <= 0) return@mapNotNull null
                    Allowance(entry.minutes, period, daysOf(entry.days), decodeTaper(entry.taper, entry.minutes))
                },
                windows = stored.windows.mapNotNull { entry ->
                    if (entry.from !in 0 until MINUTES_PER_DAY ||
                        entry.to !in 0 until MINUTES_PER_DAY
                    ) {
                        return@mapNotNull null
                    }
                    Window(entry.from, entry.to, daysOf(entry.days))
                },
                // A quota that reads as nonsense is dropped, not guessed at.
                reels = stored.reels
                    ?.takeIf { it.reels > 0 && it.minutes > 0 }
                    ?.let { ReelQuota(it.reels, it.minutes) },
                onlyOn = if (onlyOn === UNREADABLE) emptySet() else onlyOn,
                exceptOn = if (exceptOn === UNREADABLE) null else exceptOn,
            )
        }.getOrNull()
    }

    /** Marks a list that was there and could not be read; compared by identity. */
    private val UNREADABLE: Set<String> = HashSet()

    /**
     * Null when absent. One that cannot be read comes back as [UNREADABLE],
     * and either way the limit then applies nowhere: widening it to messages
     * would be the one wrong way to guess.
     */
    private fun decodeIds(raw: JsonElement?, name: String): Set<String>? {
        if (raw == null || raw is JsonNull) return null
        return runCatching {
            raw.jsonArray.map { (it as JsonPrimitive).also { p -> require(p.isString) }.content }.toSet()
        }.getOrElse {
            // The log is Android's; on a plain JVM it throws, and that must not cost the limit.
            runCatching { Log.w("Doorman", "unreadable $name, applying the limit nowhere: $raw") }
            UNREADABLE
        }
    }

    private fun encodeTaper(taper: Taper): JsonElement = JsonObject(
        mapOf(
            "g" to JsonPrimitive(taper.goalMinutes),
            "s" to JsonPrimitive(taper.stepMinutes),
            "o" to JsonPrimitive(taper.startedOn.toString()),
        ),
    )

    /**
     * Null for a taper that is missing or does not make sense, which leaves the
     * plain limit at its starting minutes -- never a stricter one.
     */
    private fun decodeTaper(raw: JsonElement?, minutes: Int): Taper? {
        if (raw == null) return null
        return runCatching {
            val fields = raw.jsonObject
            val goal = (fields["g"] as JsonPrimitive).intOrNull ?: return null
            val step = (fields["s"] as JsonPrimitive).intOrNull ?: return null
            val startedOn = LocalDate.parse((fields["o"] as JsonPrimitive).contentOrNull ?: return null)
            if (goal < 1 || goal >= minutes || step < 1) return null
            Taper(goal, step, startedOn)
        }.getOrNull()
    }

    /** The one-word-per-target format: "off", "blocked", "5h", "30d". */
    private fun decodeLegacy(raw: String): Limits? = when (raw) {
        "off" -> Limits.OFF
        "blocked" -> Limits.BLOCKED
        else -> {
            val period = when (raw.last()) {
                'h' -> Period.HOUR
                'd' -> Period.DAY
                else -> null
            }
            val minutes = raw.dropLast(1).toIntOrNull()
            if (period == null || minutes == null || minutes <= 0) {
                null
            } else {
                Limits(allowances = listOf(Allowance(minutes, period)))
            }
        }
    }

    private fun daysOf(mask: Int): Days {
        val cleaned = mask and Days.ALL_MASK
        return Days(if (cleaned == 0) Days.ALL_MASK else cleaned)
    }
}
