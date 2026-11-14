package cat.doorman.app.limits

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Turns the reel books -- screen id to where it stands -- into stored text and
 * back. Kotlinx rather than org.json for the same reason as [LimitsCodec]: it
 * has to be checkable on a plain JVM.
 *
 * Unreadable text gives an empty book, which is the generous direction: the
 * only thing lost is that a stretch already begun starts over.
 */
object ReelLedgerCodec {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    @Serializable
    private data class Stored(
        @SerialName("s") val start: Long,
        @SerialName("c") val count: Int,
        @SerialName("o") val over: Boolean = false,
    )

    private val serializer = MapSerializer(String.serializer(), Stored.serializer())

    fun encode(ledger: Map<String, ReelWindow>): String = json.encodeToString(
        serializer,
        ledger.mapValues { (_, w) -> Stored(w.startMillis, w.count, w.over) },
    )

    fun decode(raw: String?): Map<String, ReelWindow> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            json.decodeFromString(serializer, raw).mapValues { (_, s) ->
                ReelWindow(s.start, s.count.coerceAtLeast(0), s.over)
            }
        }.getOrDefault(emptyMap())
    }
}
