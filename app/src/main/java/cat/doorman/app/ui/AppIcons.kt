package cat.doorman.app.ui

import android.content.Context
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Loads app icons for the picker.
 *
 * Off the main thread and cached, because "show every app" is a hundred rows
 * and they all compose at once: decoding a hundred adaptive icons where the
 * frame is drawn would drop the scroll on its face.
 *
 * The cache is held for the life of the screen rather than per row, so
 * scrolling back up does not decode everything a second time.
 */
class AppIcons(private val context: Context) {

    private val cache = ConcurrentHashMap<String, ImageBitmap>()

    suspend fun load(packageName: String): ImageBitmap? {
        cache[packageName]?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val drawable = context.packageManager.getApplicationIcon(packageName)
                // A fixed pixel size, not the drawable's own: adaptive icons
                // report their bounds as zero until they are given some, and an
                // icon drawn at zero by zero is an invisible row.
                drawable.toBitmap(SIZE_PX, SIZE_PX).asImageBitmap()
            }.getOrNull()?.also { cache[packageName] = it }
        }
    }

    private companion object {
        /**
         * Comfortably above the 40dp the row draws them at, so they stay crisp
         * on a dense screen without holding full-resolution bitmaps for every
         * app on the phone.
         */
        const val SIZE_PX = 144
    }
}
