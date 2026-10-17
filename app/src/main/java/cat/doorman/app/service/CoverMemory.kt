package cat.doorman.app.service

import cat.doorman.app.model.ScreenSnapshot
import cat.doorman.app.rules.ScreenEvaluator.Verdict

/**
 * Remembers which blocked screen a comments sheet is open over.
 *
 * Opening the comments on a reel hides the whole reel from the accessibility
 * tree -- the sheet leaves a handful of its own views and nothing else -- so
 * the screen underneath looked like nothing at all, the block was dropped, and
 * the reel went on playing above the sheet with its sound. Doing it quickly
 * enough, before the block had landed, kept the reel watchable indefinitely.
 *
 * What a sheet cannot do is be where it is without something under it, and what
 * was under it is what was last seen: if that was a blocked screen, the sheet is
 * that screen for every purpose, limits included, and the block stays.
 *
 * It is deliberately narrow, because blocking the wrong screen is the failure
 * that matters. The sheet has to be one the rules know by a marker id, and the
 * last screen seen has to have been a blocked one. Any other screen Doorman
 * cannot name -- the inbox, a profile, a share sheet -- is left alone, and so is
 * a comments sheet over something that was not blocked.
 *
 * Pure, so the awkward orderings have tests instead of a phone.
 */
object CoverMemory {

    /** The blocked screen last seen in [packageName], which a sheet may be covering. */
    data class Held(val packageName: String, val verdict: Verdict)

    /** What to remember from here on, and the verdict to act on now. */
    data class Step(val held: Held?, val verdict: Verdict)

    fun step(
        held: Held?,
        packageName: String,
        verdict: Verdict,
        recognisedScreenId: String?,
        snapshot: ScreenSnapshot?,
        coverViewIds: List<String>,
    ): Step {
        // A blocked screen: remember it, and act on it as it is.
        if (verdict.blocked && verdict.screenId != null) {
            return Step(Held(packageName, verdict), verdict)
        }
        // Any other screen the rules know by name is positively somewhere else.
        if (recognisedScreenId != null) return Step(null, verdict)
        // Unnamed. Only a known sheet over a blocked screen in this app is that
        // screen still; anything else ends the memory.
        val covered = held != null &&
            held.packageName == packageName &&
            snapshot != null &&
            coverViewIds.any { id -> snapshot.nodes.any { it.viewIdMatches(id) } }
        return if (covered) Step(held, held!!.verdict) else Step(null, verdict)
    }
}
