package cat.doorman.app.rules

/**
 * Whether the user reached the app in front of them by coming from somewhere
 * else -- following a link out of a conversation, say -- or opened it
 * themselves.
 *
 * This is what tells a video a friend sent apart from a feed on TikTok, where
 * the two are the same screen. Kept pure and separate from the service so the
 * awkward cases have tests instead of a phone.
 */
object Arrival {

    /**
     * True when [previous] is a genuinely different app that the user was in
     * just before [current].
     *
     * Three things are deliberately not "another app":
     *
     *  - the launcher, because opening TikTok from your home screen is exactly
     *    the deliberate visit this is meant to exclude;
     *  - Doorman itself, or coming back from taking a pass would count as a
     *    link and grant a second free video on top of it;
     *  - the app itself, which is not an arrival at all.
     *
     * Nothing known means false. An unknown history should not hand out a pass.
     */
    fun isFromAnotherApp(
        previous: String?,
        current: String?,
        launcherPackages: Set<String>,
        self: String,
    ): Boolean {
        if (previous == null || current == null) return false
        if (previous == current) return false
        if (previous == self) return false
        if (previous in launcherPackages) return false
        return true
    }
}
