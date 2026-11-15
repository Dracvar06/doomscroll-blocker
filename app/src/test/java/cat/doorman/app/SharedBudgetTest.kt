package cat.doorman.app

import cat.doorman.app.limits.Allowance
import cat.doorman.app.limits.Allowances
import cat.doorman.app.limits.Limits
import cat.doorman.app.limits.LimitsCodec
import cat.doorman.app.limits.Period
import cat.doorman.app.limits.isLoosening
import cat.doorman.app.service.ClockPlan
import cat.doorman.app.service.NavigationTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * An app's limit scoped to some of its screens. The one rule behind all of it
 * is that it fails open: off the chosen screens, or with no screen known, the
 * app is left alone.
 */
class SharedBudgetTest {

    private val app = "com.instagram.android"
    private val noon = LocalDateTime.of(2026, 9, 7, 12, 0)
    private val minutes = Allowance(30, Period.DAY)
    private val shared = Limits(allowances = listOf(minutes), onlyOn = setOf("ig_feed", "ig_reels"))

    private fun spentMinutes(m: Long) = mapOf(
        "$app|DAY" to Allowances.Spent(Allowances.periodKey(Period.DAY, noon), m * 60_000L),
    )

    /** The app target as the service builds it for [screen]. */
    private fun targetsOn(screen: String?, limits: Limits = shared) =
        listOf(Allowances.Target(app, limits, spentMinutes(1))).filter { limits.appliesOn(screen) }

    @Test
    fun `applies on a listed screen`() {
        assertTrue(shared.appliesOn("ig_feed"))
        assertTrue(shared.appliesOn("ig_reels"))
    }

    @Test
    fun `does not apply on messages, an unlisted screen or an unknown one`() {
        assertFalse(shared.appliesOn("ig_dm_thread"))
        assertFalse(shared.appliesOn("ig_profile"))
        assertFalse(shared.appliesOn(null))
    }

    @Test
    fun `a limit with no scope applies everywhere, even where no screen is known`() {
        val everywhere = Limits(allowances = listOf(minutes))
        assertTrue(everywhere.appliesOn("ig_dm_thread"))
        assertTrue(everywhere.appliesOn(null))
    }

    @Test
    fun `an empty scope applies nowhere`() {
        assertFalse(shared.copy(onlyOn = emptySet()).appliesOn("ig_feed"))
    }

    @Test
    fun `a scoped limit is not off`() {
        assertFalse(shared.isOff)
    }

    @Test
    fun `a spent budget blocks a listed screen and lets messages through`() {
        val spent = mapOf(
            "$app|DAY" to Allowances.Spent(Allowances.periodKey(Period.DAY, noon), 30 * 60_000L),
        )
        val feed = listOf(Allowances.Target(app, shared, spent))
        assertEquals(Allowances.Outcome.Block(app, Allowances.Reason.ALLOWANCE_SPENT), Allowances.decide(feed, noon))
        assertEquals(Allowances.Outcome.Allow, Allowances.decide(targetsOn("ig_dm_thread"), noon))
    }

    @Test
    fun `two listed screens charge the one app key`() {
        val onFeed = Allowances.decide(targetsOn("ig_feed"), noon) as Allowances.Outcome.OnTheClock
        val onReels = Allowances.decide(targetsOn("ig_reels"), noon) as Allowances.Outcome.OnTheClock
        assertEquals(listOf("$app|DAY"), onFeed.charge)
        assertEquals(onFeed.charge, onReels.charge)
    }

    // ---- a single video opened from a listed screen ------------------------

    private val reelsLimits = mapOf<String, Limits>()
    private val fromFeed = NavigationTracker.Entry.From("ig_feed")

    @Test
    fun `a video opened from a listed screen is the shared budget carrying on`() {
        val origin = ClockPlan.originOf(fromFeed, emptySet(), reelsLimits, spentMinutes(1), app, shared)
        assertEquals(app, origin?.id)

        val plan = ClockPlan.onSingleVideo(origin, emptyList(), noon)
        assertEquals(ClockPlan.SingleVideo.Counted(listOf("$app|DAY")), plan)
    }

    @Test
    fun `a video opened from a listed screen is closed once the budget is spent`() {
        val origin = ClockPlan.originOf(fromFeed, emptySet(), reelsLimits, spentMinutes(30), app, shared)

        val plan = ClockPlan.onSingleVideo(origin, emptyList(), noon)
        assertEquals(
            ClockPlan.SingleVideo.Closed(Allowances.Outcome.Block(app, Allowances.Reason.ALLOWANCE_SPENT)),
            plan,
        )
    }

    @Test
    fun `a video opened from messages, or from nowhere known, has no shared origin`() {
        val dm = NavigationTracker.Entry.From("ig_dm_thread")
        assertNull(ClockPlan.originOf(dm, emptySet(), reelsLimits, spentMinutes(30), app, shared))
        assertNull(ClockPlan.originOf(NavigationTracker.Entry.External, emptySet(), reelsLimits, spentMinutes(30), app, shared))
        assertNull(ClockPlan.originOf(NavigationTracker.Entry.InApp, emptySet(), reelsLimits, spentMinutes(30), app, shared))
    }

    @Test
    fun `an unscoped app limit is never an origin`() {
        val everywhere = Limits(allowances = listOf(minutes))
        assertNull(ClockPlan.originOf(fromFeed, emptySet(), reelsLimits, spentMinutes(30), app, everywhere))
    }

    // ---- storing it --------------------------------------------------------

    @Test
    fun `the scope survives a round trip`() {
        assertEquals(shared, LimitsCodec.decode(LimitsCodec.encode(shared)))
        val none = shared.copy(onlyOn = emptySet())
        assertEquals(none, LimitsCodec.decode(LimitsCodec.encode(none)))
    }

    @Test
    fun `stored limits from before the scope existed decode with none`() {
        val old = LimitsCodec.decode("""{"allow":[{"m":30,"p":"DAY"}]}""")
        assertNull(old?.onlyOn)
        assertEquals(30, old?.allowances?.single()?.minutes)
    }

    @Test
    fun `a damaged scope decodes as applying nowhere and keeps the rest`() {
        val damaged = LimitsCodec.decode("""{"allow":[{"m":30,"p":"DAY"}],"onlyOn":"oops"}""")
        assertEquals(emptySet<String>(), damaged?.onlyOn)
        assertEquals(30, damaged?.allowances?.single()?.minutes)

        val badItem = LimitsCodec.decode("""{"allow":[{"m":30,"p":"DAY"}],"onlyOn":["ig_feed",5]}""")
        assertEquals(emptySet<String>(), badItem?.onlyOn)
    }

    // ---- loosening ---------------------------------------------------------

    @Test
    fun `narrowing the scope is loosening`() {
        val everywhere = Limits(allowances = listOf(minutes))
        assertTrue(isLoosening(everywhere, shared))
        assertTrue(isLoosening(shared, shared.copy(onlyOn = setOf("ig_feed"))))
        assertTrue(isLoosening(shared, shared.copy(onlyOn = setOf("ig_feed", "ig_stories"))))
    }

    @Test
    fun `widening the scope is tightening`() {
        val everywhere = Limits(allowances = listOf(minutes))
        assertFalse(isLoosening(shared, everywhere))
        assertFalse(isLoosening(shared, shared.copy(onlyOn = shared.onlyOn!! + "ig_stories")))
        assertFalse(isLoosening(shared, shared))
    }

    @Test
    fun `scoping a limit that was off is not loosening`() {
        assertFalse(isLoosening(Limits.OFF, shared))
    }

    // ---- the plan's shape: app limit scoped, screens' own limits off ------

    private val plan = Limits(allowances = listOf(Allowance(45, Period.DAY)), onlyOn = setOf("ig_feed", "ig_reels"))
    private val screens = mapOf(
        "ig_feed" to Limits.OFF, "ig_reels" to Limits.OFF, "ig_stories" to Limits.OFF, "ig_dm_thread" to Limits.OFF,
    )

    /** What the service decides on [screen]: the app target if it applies there, plus a screen's own limit if it has one. */
    private fun decideOn(screen: String?, spentMinutes: Long): Allowances.Outcome {
        val spent = mapOf(
            "$app|DAY" to Allowances.Spent(Allowances.periodKey(Period.DAY, noon), spentMinutes * 60_000L),
        )
        val targets = buildList {
            if (!plan.isOff && plan.appliesOn(screen)) add(Allowances.Target(app, plan, spent))
            val own = screens[screen]
            if (own != null && !own.isOff) add(Allowances.Target(screen!!, own, spent))
        }
        return Allowances.decide(targets, noon)
    }

    @Test
    fun `screens only the shared budget names are still recognised`() {
        val active = cat.doorman.app.data.ScreenPreferences.activeScreenIds(screens, mapOf(app to plan))
        assertEquals(setOf("ig_feed", "ig_reels"), active)
    }

    @Test
    fun `feed and reels are allowed and charged to the app while time remains`() {
        listOf("ig_feed", "ig_reels").forEach { screen ->
            val outcome = decideOn(screen, 10) as Allowances.Outcome.OnTheClock
            assertEquals(listOf("$app|DAY"), outcome.charge)
        }
    }

    @Test
    fun `feed and reels are both blocked once the budget is spent`() {
        listOf("ig_feed", "ig_reels").forEach { screen ->
            assertEquals(
                Allowances.Outcome.Block(app, Allowances.Reason.ALLOWANCE_SPENT),
                decideOn(screen, 45),
            )
        }
    }

    @Test
    fun `messages, stories and unknown screens are never touched`() {
        listOf("ig_dm_thread", "ig_stories", null).forEach { screen ->
            assertEquals(Allowances.Outcome.Allow, decideOn(screen, 45))
        }
    }
}
