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
class MediaPauser(context: Context) {

    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var request: AudioFocusRequest? = null
    private var lastAt = 0L

    /** True if something was playing and focus was taken from it. */
    fun pauseIfPlaying(): Boolean {
        if (!audio.isMusicActive) return false
        // Audio takes a moment to stop after focus is taken, and the callers
        // look again on every window change. Once is enough; if something
        // starts again later, the next look deals with it.
        val now = SystemClock.uptimeMillis()
        if (now - lastAt < RETRY_AFTER_MS) return false
        lastAt = now
        release()
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setOnAudioFocusChangeListener { }
            .build()
        val granted = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (granted) request = focus
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
