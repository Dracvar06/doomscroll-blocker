package cat.doorman.app.gray

import cat.doorman.app.limits.Days
import cat.doorman.app.limits.MINUTES_PER_DAY
import cat.doorman.app.limits.Window
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.time.LocalDateTime

/**
 * Turns the screen grey while somebody is on a screen they chose, because a
 * feed is a lot less compelling without its colour.
 *
 * A target is a screen id or an app's main package, the same namespace the
 * limits use. A setting present for a target means grey is on there; [windows]
 * narrows it to those hours, and none means all day.
 *
 * Kept apart from [cat.doorman.app.limits.Limits] on purpose. A limit decides
 * whether a screen is open, and grey decides what an open one looks like: you
 * can want a feed open for ten minutes an hour *and* grey the whole time, and
 * folding one into the other would make each of them harder to read.
 */
data class GraySetting(
    val windows: List<Window> = emptyList(),
) {
    /** Whether grey applies at [now]: all day when no hours are set, else inside them. */
    fun activeAt(now: LocalDateTime): Boolean =
        windows.isEmpty() || windows.any { it.contains(now.dayOfWeek, now.hour * 60 + now.minute) }
}

/**
 * What the screen in front should look like.
 *
 * Pure, like the rest of the rules: the whole decision, including "grey on the
 * reels someone sent but not in the conversation they came from", can be
 * replayed without a phone.
 */
object GrayPolicy {

    /**
     * True when the screen should be grey: the screen itself has grey on and
     * its hours hold, or its whole app does.
     *
     * [screenId] is the screen the rules recognise, held or not -- null for
     * anything they do not name. That is what keeps a conversation in colour
     * inside an app whose feed is grey: an unnamed screen is never grey on its
     * own account, only through a setting for the whole app.
     */
    fun shouldBeGray(
        settings: Map<String, GraySetting>,
        appKey: String?,
        screenId: String?,
        now: LocalDateTime,
    ): Boolean {
        if (settings.isEmpty()) return false
        if (screenId != null && settings[screenId]?.activeAt(now) == true) return true
        if (appKey != null && settings[appKey]?.activeAt(now) == true) return true
        return false
    }

    /**
     * True when [to] greys less than [from] did, which is what the change delay
     * waits on: switching a screen off, or cutting its hours down.
     */
    fun isLoosening(from: GraySetting?, to: GraySetting?): Boolean {
        if (from == null) return false
        if (to == null) return true
        if (from.windows.isEmpty()) return to.windows.isNotEmpty()
        if (to.windows.isEmpty()) return false
        return coveredMinutes(from) .let { before ->
            val after = coveredMinutes(to)
            before.indices.any { before[it] && !after[it] }
        }
    }

    /** Each minute of the week, marked with whether this setting greys it. */
    private fun coveredMinutes(setting: GraySetting): BooleanArray {
        val week = BooleanArray(7 * MINUTES_PER_DAY)
        if (setting.windows.isEmpty()) {
            week.fill(true)
            return week
        }
        java.time.DayOfWeek.entries.forEach { day ->
            val start = (day.value - 1) * MINUTES_PER_DAY
            for (minute in 0 until MINUTES_PER_DAY) {
                if (setting.windows.any { it.contains(day, minute) }) week[start + minute] = true
            }
        }
        return week
    }
}

/**
 * Turns grey settings into stored text and back. Kotlinx for the same reason as
 * the limits codec: it has to be checkable on a plain JVM. Unreadable text gives
 * no settings, so a damaged store means grey off, never grey stuck on.
 */
object GrayCodec {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    @Serializable
    private data class StoredWindow(
        @SerialName("f") val from: Int,
        @SerialName("t") val to: Int,
        @SerialName("d") val days: Int = Days.ALL_MASK,
    )

    @Serializable
    private data class Stored(val w: List<StoredWindow> = emptyList())

    private val serializer = MapSerializer(String.serializer(), Stored.serializer())

    fun encode(settings: Map<String, GraySetting>): String = json.encodeToString(
        serializer,
        settings.mapValues { (_, s) ->
            Stored(s.windows.map { StoredWindow(it.fromMinute, it.toMinute, it.days.mask) })
        },
    )

    fun decode(raw: String?): Map<String, GraySetting> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            json.decodeFromString(serializer, raw).mapValues { (_, stored) ->
                GraySetting(
                    stored.w.mapNotNull { entry ->
                        if (entry.from !in 0 until MINUTES_PER_DAY ||
                            entry.to !in 0 until MINUTES_PER_DAY
                        ) {
                            return@mapNotNull null
                        }
                        val mask = entry.days and Days.ALL_MASK
                        Window(entry.from, entry.to, Days(if (mask == 0) Days.ALL_MASK else mask))
                    },
                )
            }
        }.getOrDefault(emptyMap())
    }
}
