package cat.doorman.app.block

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log

/**
 * Stops what is playing when a block lands.
 *
 * The block covers the picture and nothing else, so a video that was playing
 * when the block appeared kept playing behind it -- and a video in its floating
 * window kept going in plain sight.
 *
 * Audio focus rather than a media "pause" key. The key goes to the active
 * media session, and YouTube's floating player reports none that answers it
 * (state NONE, its audio still running), so the key did nothing. Asking for
 * focus for good is the thing every player is built to obey: an app that is
 * told it has lost focus permanently stops. It also reaches only what is
 * actually playing, rather than whichever session answered last.
 *
 * It is a blunt instrument: music playing in another app stops too. That is
 * the price, and it was chosen.
 */
class MediaPauser(
    context: Context,
    /**
     * Something took focus back. Instagram does, the moment the volume key
     * unmutes a reel it had muted on losing focus; being told is what lets the
     * reel be silenced again in a fraction of a second rather than on the next
     * look.
     */
    private val onLost: () -> Unit = {},
) {

    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var request: AudioFocusRequest? = null
    private var lastAt = 0L
    private var lost = false

    /** Focus is Doorman's and nobody has taken it since: asking again changes nothing. */
    val holdsFocus: Boolean get() = request != null && !lost

    /**
     * True if something was playing and focus was taken from it. [quick] is
     * for answering [onLost]: the app has just shown it is playing again, so
     * there is no waiting between attempts (the caller caps how often).
     */
    fun pauseIfPlaying(quick: Boolean = false): Boolean {
        if (!audio.isMusicActive) return false
        // Audio takes a moment to stop after focus is taken, and the callers
        // look again on every window change. Once is enough; if something
        // starts again later, the next look deals with it.
        val now = SystemClock.uptimeMillis()
        if (!quick && now - lastAt < RETRY_AFTER_MS) return false
        lastAt = now
        release()
        // The listener is built before the request it belongs to exists; a
        // loss reported to a request already given up is nobody's business.
        val focusRef = arrayOfNulls<AudioFocusRequest>(1)
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setOnAudioFocusChangeListener { change ->
                if (change < 0 && request === focusRef[0]) {
                    lost = true
                    onLost()
                }
            }
            .build()
        focusRef[0] = focus
        val granted = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (granted) {
            request = focus
            lost = false
        }
        Log.i("Doorman", "media pause: focus granted=$granted")
        return granted
    }

    private companion object {
        const val RETRY_AFTER_MS = 2_000L
    }

    /** Gives focus back. Whatever lost it for good does not start again by itself. */
    fun release() {
        request?.let { runCatching { audio.abandonAudioFocusRequest(it) } }
        request = null
    }
}
