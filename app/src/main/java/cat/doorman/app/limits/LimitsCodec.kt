package cat.doorman.app.limits

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
    )

    @Serializable
    private data class StoredWindow(
        @SerialName("f") val from: Int,
        @SerialName("t") val to: Int,
        @SerialName("d") val days: Int = Days.ALL_MASK,
    )

    @Serializable
    private data class Stored(
        val blocked: Boolean = false,
        val allow: List<StoredAllowance> = emptyList(),
        val windows: List<StoredWindow> = emptyList(),
    )

    fun encode(limits: Limits): String = json.encodeToString(
        Stored(
            blocked = limits.blocked,
            allow = limits.allowances.map {
                StoredAllowance(it.minutes, it.period.name, it.days.mask)
            },
            windows = limits.windows.map {
                StoredWindow(it.fromMinute, it.toMinute, it.days.mask)
            },
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
            Limits(
                blocked = stored.blocked,
                allowances = stored.allow.mapNotNull { entry ->
                    val period = runCatching { Period.valueOf(entry.period) }.getOrNull()
                        ?: return@mapNotNull null
                    if (entry.minutes <= 0) return@mapNotNull null
                    Allowance(entry.minutes, period, daysOf(entry.days))
                },
                windows = stored.windows.mapNotNull { entry ->
                    if (entry.from !in 0 until MINUTES_PER_DAY ||
                        entry.to !in 0 until MINUTES_PER_DAY
                    ) {
                        return@mapNotNull null
                    }
                    Window(entry.from, entry.to, daysOf(entry.days))
                },
            )
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
