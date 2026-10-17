package cat.doorman.app.service

import cat.doorman.app.model.ScreenSnapshot
import cat.doorman.app.rules.ScreenEvaluator.Outcome
import cat.doorman.app.rules.ScreenEvaluator.Verdict

/**
 * Remembers which blocked screen -- or spent single item -- a comments sheet is
 * open over.
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

    /**
     * The screen last seen in [packageName] that a sheet may be covering, and
     * when it was last confirmed there.
     */
    data class Held(val packageName: String, val verdict: Verdict, val seenAt: Long = 0L)

    /** What to remember from here on, and the verdict to act on now. */
    data class Step(val held: Held?, val verdict: Verdict)

    /**
     * How long a frame that is neither a named screen nor a known sheet may
     * pass without ending the memory. A sheet sliding up is first a handful of
     * views with none of its own ids yet -- 20 nodes, logged on his phone
     * 80 ms before the comments -- and that one frame was enough to forget the
     * reel underneath, so the finished sheet was "nothing" again.
     */
    const val TRANSITION_GRACE_MS = 1_500L

    fun step(
        held: Held?,
        packageName: String,
        verdict: Verdict,
        recognisedScreenId: String?,
        snapshot: ScreenSnapshot?,
        coverViewIds: List<String>,
        nowMillis: Long = 0L,
    ): Step {
        // A blocked screen, or a single item allowed once: remember it, and act
        // on it as it is. The single item belongs here too. Once its one
        // allowance is spent it is blocked in all but name, and comments
        // opened over it were the free way past that block. Whether it is
        // spent is the budget's question, which the caller asks again with
        // the verdict handed back below.
        if (verdict.screenId != null &&
            (verdict.blocked || verdict.outcome == Outcome.ALLOW_ONCE)
        ) {
            return Step(Held(packageName, verdict, nowMillis), verdict)
        }
        // Any other screen the rules know by name is positively somewhere else.
        if (recognisedScreenId != null) return Step(null, verdict)
        val sameApp = held != null && held.packageName == packageName
        // Unnamed. Only a known sheet over a remembered screen in this app is
        // that screen still.
        val marked = snapshot != null &&
            coverViewIds.any { id -> snapshot.nodes.any { it.viewIdMatches(id) } }
        if (sameApp && marked) return Step(held!!.copy(seenAt = nowMillis), held.verdict)
        // A frame in between: kept in mind for a moment, but acted on as what it
        // is. Blocking it would put a block over whatever unnamed screen comes
        // next -- the inbox, a profile -- which is the failure that matters.
        if (sameApp && coverViewIds.isNotEmpty() && nowMillis - held!!.seenAt <= TRANSITION_GRACE_MS) {
            return Step(held, verdict)
        }
        return Step(null, verdict)
    }
}
