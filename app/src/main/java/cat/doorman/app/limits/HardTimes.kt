package cat.doorman.app.limits

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate

/**
 * The relief the user has taken, kept apart from the limits it eases.
 *
 * Dates, not flags: a hard day is gone at midnight because it names a day, and
 * nothing has to remember to clear it. [hardWeeks] holds the first day of each
 * hard week.
 */
data class HardTimes(
    val hardDays: Set<LocalDate> = emptySet(),
    val hardWeeks: List<LocalDate> = emptyList(),
) {
    companion object {
        val EMPTY = HardTimes()
    }
}

/**
 * Turns [HardTimes] into stored text and back. Unreadable text gives none
 * taken, which is the generous direction: relief that is lost leaves limits at
 * the level they were on, and the one thing it costs is a tap to take it again.
 */
object HardTimesCodec {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    @Serializable
    private data class Stored(
        @SerialName("d") val days: List<String> = emptyList(),
        @SerialName("w") val weeks: List<String> = emptyList(),
    )

    fun encode(hard: HardTimes): String = json.encodeToString(
        Stored(
            days = hard.hardDays.sorted().map { it.toString() },
            weeks = hard.hardWeeks.sorted().map { it.toString() },
        ),
    )

    fun decode(raw: String?): HardTimes {
        if (raw.isNullOrBlank()) return HardTimes.EMPTY
        return runCatching {
            val stored = json.decodeFromString<Stored>(raw)
            HardTimes(
                hardDays = stored.days.mapNotNull(::dateOf).toSet(),
                hardWeeks = stored.weeks.mapNotNull(::dateOf),
            )
        }.getOrDefault(HardTimes.EMPTY)
    }

    private fun dateOf(text: String): LocalDate? = runCatching { LocalDate.parse(text) }.getOrNull()
}
