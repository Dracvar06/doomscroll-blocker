package cat.doorman.app

import cat.doorman.app.gray.GraySetting
import cat.doorman.app.limits.Allowance
import cat.doorman.app.limits.DayRecord
import cat.doorman.app.limits.HardTimes
import cat.doorman.app.limits.Limits
import cat.doorman.app.limits.Period
import cat.doorman.app.limits.ReelQuota
import cat.doorman.app.limits.Taper
import cat.doorman.app.report.UsageAccess
import cat.doorman.app.rules.RuleSet
import cat.doorman.app.ui.AppChoice
import cat.doorman.app.ui.Pace
import cat.doorman.app.ui.PartChoice
import cat.doorman.app.ui.PartLine
import cat.doorman.app.ui.Plan
import cat.doorman.app.ui.PlanExtras
import cat.doorman.app.ui.PlanLine
import cat.doorman.app.ui.PlanMath
import cat.doorman.app.ui.Reduction
import cat.doorman.app.ui.STORE_URL
import cat.doorman.app.ui.Suggestion
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * What the plan wizard works out, apart from how it looks. A plan that rounds
 * oddly, or writes a limit to something the person did not choose, changes
 * somebody's limits in a way they never agreed to.
 */
class PlanMathTest {

    private val rules: RuleSet = Json { ignoreUnknownKeys = true }
        .decodeFromString(File("src/main/assets/rules.json").readText())

    private val instagram = "com.instagram.android"
    private val tiktok = "com.zhiliaoapp.musically"
    private val youtube = "com.google.android.youtube"
    private val today = LocalDate.of(2026, 11, 14)

    private fun choice(
        pkg: String = instagram,
        usual: Int = 100,
        reduction: Reduction = Reduction.HALF,
        parts: Map<String, PartChoice> = emptyMap(),
    ) = AppChoice(pkg, usual, reduction, parts = parts)

    private fun screensOf(pkg: String) = rules.apps.getValue(pkg).screens.map { it.id }.toSet()

    private val igMessages = setOf("ig_dm_inbox", "ig_dm_thread")
    private val igParts = setOf("ig_stories", "ig_reels", "ig_reel_viewer", "ig_feed", "ig_explore")

    // Targets

    @Test
    fun `chips go from the smallest change to the largest and end with other`() {
        assertEquals(
            listOf(Reduction.A_BIT, Reduction.HALF, Reduction.THIRTY, Reduction.CUSTOM),
            PlanMath.targetChips(100, Reduction.HALF),
        )
        // 25 and 15 are under 33, and 30 is the smallest change of the three.
        assertEquals(
            listOf(Reduction.THIRTY, Reduction.A_BIT, Reduction.HALF, Reduction.CUSTOM),
            PlanMath.targetChips(33, Reduction.HALF),
        )
    }

    @Test
    fun `chips are in the same order for every app, by minutes from highest to lowest`() {
        for (usual in 10..240) {
            for (selected in Reduction.entries) {
                val chips = PlanMath.targetChips(usual, selected)
                assertEquals(Reduction.CUSTOM, chips.last())
                val minutes = chips.dropLast(1).map { PlanMath.suggestTarget(usual, it) }
                assertEquals("usual=$usual", minutes.sortedDescending(), minutes)
            }
        }
        // 33 and 95 minutes both read from the largest target down, whichever chip gets there.
        assertEquals(
            listOf(30, 25, 15),
            PlanMath.targetChips(33, Reduction.HALF).dropLast(1).map { PlanMath.suggestTarget(33, it) },
        )
        assertEquals(
            listOf(70, 50, 30),
            PlanMath.targetChips(95, Reduction.HALF).dropLast(1).map { PlanMath.suggestTarget(95, it) },
        )
    }

    @Test
    fun `apps with a limit of any kind, or grey, start picked`() {
        val installed = setOf(instagram, youtube, tiktok)
        val limits = mapOf(
            instagram to Limits(allowances = listOf(Allowance(30, Period.DAY))),
            "yt_shorts" to Limits.BLOCKED,
            "tt_feed" to Limits.OFF,
        )
        val held = PlanMath.heldApps(rules, limits, mapOf(tiktok to GraySetting()), installed)
        assertEquals(setOf(instagram, youtube, tiktok), held)
        assertEquals(
            setOf(instagram),
            PlanMath.heldApps(rules, limits, emptyMap(), setOf(instagram, tiktok)),
        )
        assertTrue(PlanMath.heldApps(rules, mapOf("tt_feed" to Limits.OFF), emptyMap(), installed).isEmpty())
    }

    @Test
    fun `a fixed chip that is not under what is used is not offered`() {
        assertFalse(Reduction.THIRTY in PlanMath.targetChips(20, Reduction.HALF))
        assertFalse(Reduction.THIRTY in PlanMath.targetChips(30, Reduction.HALF))
        assertEquals(listOf(Reduction.HALF, Reduction.CUSTOM), PlanMath.targetChips(10, Reduction.HALF))
    }


    @Test
    fun `targets are rounded to five minutes and never under five`() {
        assertEquals(45, PlanMath.roundToFive(47.0))
        assertEquals(50, PlanMath.roundToFive(47.5))
        assertEquals(5, PlanMath.roundToFive(2.0))
        assertEquals(5, PlanMath.roundToFive(0.0))
    }

    @Test
    fun `a bit less is three quarters and half is half`() {
        assertEquals(75, PlanMath.suggestTarget(100, Reduction.A_BIT))
        assertEquals(50, PlanMath.suggestTarget(100, Reduction.HALF))
        // 47.5 rounds up to a number somebody would say.
        assertEquals(50, PlanMath.suggestTarget(95, Reduction.HALF))
        assertEquals(10, PlanMath.suggestTarget(16, Reduction.HALF))
        assertEquals(5, PlanMath.suggestTarget(4, Reduction.HALF))
    }

    @Test
    fun `thirty minutes is never more than is used now`() {
        assertEquals(30, PlanMath.suggestTarget(100, Reduction.THIRTY))
        assertEquals(20, PlanMath.suggestTarget(20, Reduction.THIRTY))
    }

    @Test
    fun `a typed target is taken as it is, within the range`() {
        assertEquals(35, PlanMath.suggestTarget(100, Reduction.CUSTOM, 35))
        assertEquals(PlanMath.MAX_TARGET, PlanMath.suggestTarget(100, Reduction.CUSTOM, 999))
        assertEquals(PlanMath.MIN_TARGET, PlanMath.suggestTarget(100, Reduction.CUSTOM, 0))
    }

    @Test
    fun `android's figure is rounded up so a short use is not nothing`() {
        assertEquals(PlanMath.MIN_TARGET, PlanMath.usualMinutes(0L))
        assertEquals(5, PlanMath.usualMinutes(2 * 60_000L))
        assertEquals(101, PlanMath.usualMinutes(100 * 60_000L + 1))
    }

    // Start level and pace

    @Test
    fun `the start level is three quarters of use`() {
        assertEquals(75, PlanMath.startLevel(100, 50))
        assertEquals(45, PlanMath.startLevel(60, 10))
        assertEquals(30, PlanMath.startLevel(40, 20))
    }

    @Test
    fun `the start level is never under the target`() {
        assertEquals(90, PlanMath.startLevel(100, 90))
    }

    @Test
    fun `the start level is never over what is used`() {
        assertEquals(100, PlanMath.startLevel(100, 100))
        assertEquals(10, PlanMath.startLevel(10, 5))
        assertEquals(20, PlanMath.startLevel(20, 40))
    }

    @Test
    fun `each pace shows its own step and the earliest date it gets there`() {
        val steps = PlanMath.paceSteps(75, 45, HardTimes.EMPTY, today)
        assertEquals(listOf(Pace.GENTLE, Pace.STEADY, Pace.QUICK), steps.map { it.pace })
        assertEquals(listOf(4, 8, 15), steps.map { it.stepMinutes })
        // From Saturday 14 November the first Monday is the 16th; a goal 30
        // minutes down at 15 a week is two Mondays, so the 30th.
        assertEquals(LocalDate.of(2026, 11, 30), steps[2].reachesOn)
        assertEquals(LocalDate.of(2026, 12, 14), steps[1].reachesOn)
        assertEquals(LocalDate.of(2027, 1, 11), steps[0].reachesOn)
    }

    @Test
    fun `from tomorrow is just the target`() {
        val allowance = PlanMath.allowanceFor(choice(), null, today)
        assertEquals(Allowance(50, Period.DAY), allowance)
    }

    @Test
    fun `step by step starts at the start level and ends at the target`() {
        val allowance = PlanMath.allowanceFor(choice(), Pace.STEADY, today)
        assertEquals(Allowance(75, Period.DAY, taper = Taper(50, 8, today)), allowance)
    }

    @Test
    fun `a target at the start level has nothing to lower`() {
        val allowance = PlanMath.allowanceFor(choice(usual = 100, reduction = Reduction.A_BIT), Pace.STEADY, today)
        assertEquals(Allowance(75, Period.DAY), allowance)
    }

    // toLimits

    @Test
    fun `the app time is one limit over the whole app except its messages`() {
        val limits = PlanMath.toLimits(Plan(listOf(choice())), rules, today)
        val app = limits.getValue(instagram)
        assertEquals(listOf(Allowance(50, Period.DAY)), app.allowances)
        assertEquals(igMessages, app.exceptOn)
        assertNull(app.onlyOn)
    }

    @Test
    fun `an app with no messages and nothing free is the whole app`() {
        val limits = PlanMath.toLimits(Plan(listOf(choice(youtube))), rules, today)
        assertNull(limits.getValue(youtube).exceptOn)
        assertNull(limits.getValue(youtube).onlyOn)
    }

    @Test
    fun `a part that counts has no limit of its own`() {
        val limits = PlanMath.toLimits(Plan(listOf(choice())), rules, today)
        igParts.forEach { assertEquals(it, Limits.OFF, limits[it]) }
    }

    @Test
    fun `a part that is blocked is held outright and still counted in no time`() {
        val limits = PlanMath.toLimits(
            Plan(listOf(choice(parts = mapOf("ig_reels" to PartChoice.Blocked)))), rules, today,
        )
        assertEquals(Limits.BLOCKED, limits["ig_reels"])
        assertEquals(igMessages, limits.getValue(instagram).exceptOn)
    }

    @Test
    fun `a part that is free joins the messages in what the time leaves alone`() {
        val limits = PlanMath.toLimits(
            Plan(listOf(choice(parts = mapOf("ig_stories" to PartChoice.Free)))), rules, today,
        )
        assertEquals(Limits.OFF, limits["ig_stories"])
        assertEquals(igMessages + "ig_stories", limits.getValue(instagram).exceptOn)
        // Without messages a free part is the only exception.
        val yt = PlanMath.toLimits(
            Plan(listOf(choice(youtube, parts = mapOf("yt_watch" to PartChoice.Free)))), rules, today,
        )
        assertEquals(setOf("yt_watch"), yt.getValue(youtube).exceptOn)
    }

    @Test
    fun `a count of videos goes on the screen that counts them and its time still counts`() {
        val quota = ReelQuota(5, 30)
        val limits = PlanMath.toLimits(
            Plan(listOf(choice(parts = mapOf("ig_reels" to PartChoice.Videos(quota))))), rules, today,
        )
        assertEquals(Limits(reels = quota), limits["ig_reels"])
        assertFalse("ig_reels" in limits.getValue(instagram).exceptOn.orEmpty())
    }

    @Test
    fun `videos on a screen that does not count them is only its time`() {
        val limits = PlanMath.toLimits(
            Plan(listOf(choice(parts = mapOf("ig_feed" to PartChoice.Videos(ReelQuota(5, 30)))))), rules, today,
        )
        assertEquals(Limits.OFF, limits["ig_feed"])
    }

    @Test
    fun `a screen that lets one video through and the messages are never written`() {
        val limits = PlanMath.toLimits(Plan(listOf(choice())), rules, today)
        assertFalse("ig_single_reel" in limits)
        igMessages.forEach { assertFalse(it in limits) }
        assertEquals(igParts + instagram, limits.keys)
    }

    @Test
    fun `the plan only writes the apps it covers`() {
        val limits = PlanMath.toLimits(Plan(listOf(choice())), rules, today)
        assertTrue(limits.keys.none { it in screensOf(youtube) || it == youtube })
    }

    @Test
    fun `no two parts of an app are offered under the same name`() {
        // The wizard names a part by its brief label, which every language file must tell apart.
        val names = File("src/main/res/values/strings.xml").readText()
        rules.apps.forEach { (pkg, app) ->
            val labels = PlanMath.partScreens(app).map { screen ->
                val key = screen.labelKey
                Regex("""<string name="${key}_brief"[^>]*>(.*?)</string>""").find(names)?.groupValues?.get(1)
                    ?: Regex("""<string name="$key"[^>]*>(.*?)</string>""").find(names)?.groupValues?.get(1)
            }
            assertEquals(pkg, labels.size, labels.toSet().size)
        }
    }

    @Test
    fun `messages are never a part`() {
        rules.apps.values.forEach { app ->
            assertTrue(PlanMath.partScreens(app).none { it.messages })
        }
        assertEquals(igMessages, PlanMath.messageScreens(rules.apps.getValue(instagram)).toSet())
    }

    @Test
    fun `step by step writes a taper`() {
        val limits = PlanMath.toLimits(Plan(listOf(choice()), pace = Pace.STEADY), rules, today)
        assertEquals(
            listOf(Allowance(75, Period.DAY, taper = Taper(50, 8, today))),
            limits.getValue(instagram).allowances,
        )
    }

    @Test
    fun `an app that is only videos still gets its time and a limit on the screen`() {
        val quota = ReelQuota(3, 15)
        val limits = PlanMath.toLimits(
            Plan(listOf(choice(tiktok, parts = mapOf("tt_feed" to PartChoice.Videos(quota))))), rules, today,
        )
        assertEquals(Limits(reels = quota), limits["tt_feed"])
        assertEquals(listOf(Allowance(50, Period.DAY)), limits.getValue(tiktok).allowances)
        assertNull(limits.getValue(tiktok).exceptOn)
    }

    // Where the parts start from

    @Test
    fun `parts start as counting when nothing is set`() {
        assertTrue(PlanMath.currentParts(rules.apps.getValue(instagram), instagram, emptyMap()).isEmpty())
    }

    @Test
    fun `parts start from the limits in force`() {
        val quota = ReelQuota(5, 30)
        val existing = mapOf(
            "ig_reels" to Limits(reels = quota),
            "ig_explore" to Limits.BLOCKED,
            instagram to Limits(allowances = listOf(Allowance(30, Period.DAY)), exceptOn = setOf("ig_stories")),
        )
        assertEquals(
            mapOf(
                "ig_stories" to PartChoice.Free,
                "ig_reels" to PartChoice.Videos(quota),
                "ig_explore" to PartChoice.Blocked,
            ),
            PlanMath.currentParts(rules.apps.getValue(instagram), instagram, existing),
        )
    }

    @Test
    fun `an old plan's screens left out of the budget start as free`() {
        val old = Limits(allowances = listOf(Allowance(30, Period.DAY)), onlyOn = setOf("ig_reels", "ig_feed"))
        val parts = PlanMath.currentParts(rules.apps.getValue(instagram), instagram, mapOf(instagram to old))
        assertEquals(setOf("ig_stories", "ig_reel_viewer", "ig_explore"), parts.keys)
        assertTrue(parts.values.all { it == PartChoice.Free })
    }

    @Test
    fun `a block wins over what the app limit says about the part`() {
        val existing = mapOf(
            "ig_stories" to Limits.BLOCKED,
            instagram to Limits(allowances = listOf(Allowance(30, Period.DAY)), exceptOn = setOf("ig_stories")),
        )
        assertEquals(
            PartChoice.Blocked,
            PlanMath.currentParts(rules.apps.getValue(instagram), instagram, existing)["ig_stories"],
        )
    }

    @Test
    fun `an old plan's limits are replaced by the new model`() {
        val old = Limits(allowances = listOf(Allowance(30, Period.DAY)), onlyOn = setOf("ig_reels"))
        val parts = PlanMath.currentParts(rules.apps.getValue(instagram), instagram, mapOf(instagram to old))
        val limits = PlanMath.toLimits(Plan(listOf(choice(parts = parts))), rules, today)
        assertNull(limits.getValue(instagram).onlyOn)
        assertEquals(igMessages + (igParts - "ig_reels"), limits.getValue(instagram).exceptOn)
    }

    @Test
    fun `the quick path blocks the endless screens of the chosen apps and nothing else`() {
        val limits = PlanMath.toLimits(Plan(listOf(choice()), quick = true), rules, today)
        assertEquals(
            mapOf(
                "ig_single_reel" to Limits.BLOCKED,
                "ig_reels" to Limits.BLOCKED,
                "ig_explore" to Limits.BLOCKED,
            ),
            limits,
        )
    }

    @Test
    fun `the quick path keeps a limit already set on a screen it holds`() {
        val reels = Limits(reels = ReelQuota(10, 60))
        val limits = PlanMath.toLimits(
            Plan(listOf(choice()), quick = true), rules, today, existing = mapOf("ig_reels" to reels),
        )
        assertEquals(reels, limits["ig_reels"])
    }

    @Test
    fun `an app that is not in the plan is not touched`() {
        val limits = PlanMath.toLimits(Plan(listOf(choice(tiktok)), quick = true), rules, today)
        assertEquals(setOf("tt_feed", "tt_shared_video"), limits.keys)
    }

    @Test
    fun `an unknown package writes nothing`() {
        val plan = Plan(listOf(AppChoice("com.example.nothing", 60)))
        assertTrue(PlanMath.toLimits(plan, rules, today).isEmpty())
        assertTrue(PlanMath.summary(plan, rules, HardTimes.EMPTY, today).isEmpty())
    }

    // Grey, the application and what changes

    @Test
    fun `evening grey goes on each app in the plan, only when asked`() {
        val plan = Plan(listOf(choice(), choice(tiktok)))
        val on = PlanMath.grayFor(plan, PlanExtras(eveningGrey = true))
        assertEquals(setOf(instagram, tiktok), on.keys)
        val grey: GraySetting = on.getValue(instagram)
        assertEquals(21 * 60, grey.windows.single().fromMinute)
        assertEquals(7 * 60, grey.windows.single().toMinute)
        assertTrue(PlanMath.grayFor(plan, PlanExtras()).isEmpty())
    }

    @Test
    fun `the application carries the wait and the report as asked`() {
        val application = PlanMath.application(
            Plan(listOf(choice())), PlanExtras(delaySeconds = 30, weeklyReport = false), rules, today, emptyMap(),
        )
        assertEquals(30, application.delaySeconds)
        assertEquals(false, application.weeklyReport)
        assertTrue(application.gray.isEmpty())
    }

    @Test
    fun `only what differs is a change, and a block opened is a loosening`() {
        val wanted = mapOf("ig_reels" to Limits.OFF, "ig_feed" to Limits.OFF, instagram to Limits(
            allowances = listOf(Allowance(50, Period.DAY)), exceptOn = igMessages,
        ))
        val current = mapOf("ig_reels" to Limits.BLOCKED, "ig_feed" to Limits.OFF)
        val changes = PlanMath.limitChanges(wanted, current, HardTimes.EMPTY, today)
        assertEquals(setOf("ig_reels", instagram), changes.map { it.id }.toSet())
        assertTrue(changes.first { it.id == "ig_reels" }.loosens)
        assertFalse(changes.first { it.id == instagram }.loosens)
    }

    @Test
    fun `grey that is already there is not a change and less of it is a loosening`() {
        val grey = PlanMath.EVENING_GREY
        assertTrue(PlanMath.grayChanges(mapOf("a" to grey), mapOf("a" to grey)).isEmpty())
        assertFalse(PlanMath.grayChanges(mapOf("a" to grey), emptyMap()).single().loosens)
        // From all day to hours is less grey.
        assertTrue(PlanMath.grayChanges(mapOf("a" to grey), mapOf("a" to GraySetting())).single().loosens)
    }

    // Summary and share

    @Test
    fun `a budget line says where it starts, where it goes and when`() {
        val line = PlanMath.summary(Plan(listOf(choice()), pace = Pace.STEADY), rules, HardTimes.EMPTY, today)
            .single() as PlanLine.Budget
        assertEquals(75, line.fromMinutes)
        assertEquals(50, line.toMinutes)
        assertTrue(line.lowers)
        assertNotNull(line.reachesOn)
    }

    @Test
    fun `a budget line carries what the app takes now beside where the limit starts`() {
        val plan = Plan(listOf(choice(youtube, usual = 95)), pace = Pace.STEADY)
        val line = PlanMath.summary(plan, rules, HardTimes.EMPTY, today).single() as PlanLine.Budget
        assertEquals(95, line.usualMinutes)
        assertEquals(70, line.fromMinutes)
        assertEquals(50, line.toMinutes)
    }

    @Test
    fun `a flat budget line has no date`() {
        val line = PlanMath.summary(Plan(listOf(choice())), rules, HardTimes.EMPTY, today).single() as PlanLine.Budget
        assertFalse(line.lowers)
        assertNull(line.reachesOn)
        assertEquals(50, line.toMinutes)
    }

    @Test
    fun `the line says whether messages are free and lists only the parts that are not counted`() {
        val quota = ReelQuota(5, 30)
        val parts = mapOf(
            "ig_stories" to PartChoice.Free,
            "ig_reels" to PartChoice.Videos(quota),
            "ig_feed" to PartChoice.Counts,
        )
        val line = PlanMath.summary(Plan(listOf(choice(parts = parts))), rules, HardTimes.EMPTY, today)
            .single() as PlanLine.Budget
        assertTrue(line.messagesFree)
        // In the rules' own order, whatever order they were picked in.
        assertEquals(
            listOf(PartLine("ig_stories", PartChoice.Free), PartLine("ig_reels", PartChoice.Videos(quota))),
            line.parts,
        )
        val plain = PlanMath.summary(Plan(listOf(choice(youtube))), rules, HardTimes.EMPTY, today)
            .single() as PlanLine.Budget
        assertFalse(plain.messagesFree)
        assertTrue(plain.parts.isEmpty())
    }

    @Test
    fun `the quick path says what it blocks`() {
        val line = PlanMath.summary(Plan(listOf(choice()), quick = true), rules, HardTimes.EMPTY, today).single()
        assertEquals(PlanLine.Blocked(instagram), line)
    }

    @Test
    fun `the share text is first person lines and a closing ask`() {
        val text = PlanMath.shareText("Intro", listOf("A: 1", "B: 2"), "Outro", storeUrl = "")
        assertEquals("Intro\n- A: 1\n- B: 2\nOutro", text)
    }

    @Test
    fun `the share text carries the link only when there is one`() {
        assertEquals("Intro\n- A\nOutro\nhttps://example.org/doorman",
            PlanMath.shareText("Intro", listOf("A"), "Outro", storeUrl = "https://example.org/doorman"))
        assertFalse(PlanMath.shareText("Intro", listOf("A"), "Outro", storeUrl = "  ").endsWith("\n"))
        // What ships today: no store page, so no link.
        assertEquals("", STORE_URL)
        assertEquals("Intro\n- A\nOutro", PlanMath.shareText("Intro", listOf("A"), "Outro"))
    }

    // Looking back

    private fun day(date: LocalDate, ig: Long = 0L, loosenings: Int = 0) = DayRecord(
        date = date.toString(),
        loosenings = loosenings,
        screens = mapOf("ig_reels" to ig),
    )

    private val shared = Limits(allowances = listOf(Allowance(30, Period.DAY)), exceptOn = igMessages)

    @Test
    fun `no rows in the last fortnight is no look back`() {
        val old = listOf(day(today.minusDays(20), ig = 60_000L))
        assertNull(PlanMath.lookBack(old, listOf(instagram), rules, mapOf(instagram to shared), HardTimes.EMPTY, today))
    }

    @Test
    fun `it averages the days on record and counts the days the limit ran out`() {
        val journal = listOf(
            day(today.minusDays(1), ig = 30 * 60_000L),
            day(today.minusDays(2), ig = 29 * 60_000L + 30_000L),
            day(today.minusDays(3), ig = 10 * 60_000L),
            day(today.minusDays(30), ig = 999 * 60_000L),
        )
        val back = PlanMath.lookBack(journal, listOf(instagram), rules, mapOf(instagram to shared), HardTimes.EMPTY, today)!!
        assertEquals(3, back.days)
        val app = back.apps.single()
        assertEquals(23, app.averageMinutes)
        assertEquals(30, app.limitMinutes)
        assertEquals(2, app.daysRanOut)
    }

    @Test
    fun `running out most days and taking hard days suggests keeping or easing`() {
        val journal = (1..4).map { day(today.minusDays(it.toLong()), ig = 30 * 60_000L, loosenings = 1) }
        val hard = HardTimes(hardDays = setOf(today.minusDays(2)))
        val back = PlanMath.lookBack(journal, listOf(instagram), rules, mapOf(instagram to shared), hard, today)!!
        assertEquals(Suggestion.KEEP_OR_EASE, back.apps.single().suggestion)
        assertEquals(1, back.hardDays)
        assertEquals(4, back.loosenings)
    }

    @Test
    fun `well under the limit suggests a lower target`() {
        val journal = (1..5).map { day(today.minusDays(it.toLong()), ig = 10 * 60_000L) }
        val back = PlanMath.lookBack(journal, listOf(instagram), rules, mapOf(instagram to shared), HardTimes.EMPTY, today)!!
        assertEquals(Suggestion.LOWER, back.apps.single().suggestion)
    }

    @Test
    fun `an app with no limit has an average and no suggestion`() {
        val journal = listOf(day(today.minusDays(1), ig = 20 * 60_000L))
        val app = PlanMath.lookBack(journal, listOf(instagram), rules, emptyMap(), HardTimes.EMPTY, today)!!.apps.single()
        assertEquals(20, app.averageMinutes)
        assertNull(app.limitMinutes)
        assertEquals(Suggestion.NONE, app.suggestion)
    }

    // The nudge

    @Test
    fun `three hard days in a fortnight make the nudge due`() {
        val hard = HardTimes(hardDays = setOf(today, today.minusDays(5), today.minusDays(13)))
        assertTrue(PlanMath.nudgeDue(emptyList(), hard, today, null))
        // The oldest of them drops out on the fifteenth day.
        assertFalse(PlanMath.nudgeDue(emptyList(), hard, today.plusDays(1), null))
    }

    @Test
    fun `three weakened limits in a fortnight make the nudge due`() {
        val journal = listOf(
            day(today.minusDays(1), loosenings = 2),
            day(today.minusDays(9), loosenings = 1),
            day(today.minusDays(40), loosenings = 5),
        )
        assertTrue(PlanMath.nudgeDue(journal, HardTimes.EMPTY, today, null))
        assertFalse(PlanMath.nudgeDue(journal.dropLast(2), HardTimes.EMPTY, today, null))
    }

    @Test
    fun `a nudge waved away stays quiet for a fortnight`() {
        val hard = HardTimes(hardDays = setOf(today, today.minusDays(1), today.minusDays(2)))
        assertFalse(PlanMath.nudgeDue(emptyList(), hard, today, today.minusDays(13)))
        assertTrue(PlanMath.nudgeDue(emptyList(), hard, today, today.minusDays(14)))
    }

    // Usage figures

    private fun bucket(pkg: String, day: LocalDate, minutes: Long) =
        UsageAccess.Bucket(pkg, day, minutes * 60_000L)

    @Test
    fun `usage is averaged over the days Android has any figures for`() {
        val buckets = listOf(
            bucket("a", today.minusDays(1), 60),
            bucket("a", today.minusDays(2), 30),
            // A day "a" was not opened is a day of zero for it, not a day left out.
            bucket("b", today.minusDays(3), 10),
        )
        assertEquals(3, UsageAccess.daysCovered(buckets))
        val averages = UsageAccess.dailyAverages(buckets, setOf("a", "b", "c"))!!
        assertEquals(30 * 60_000L, averages["a"])
        assertEquals(10 * 60_000L / 3, averages["b"])
        assertEquals(0L, averages["c"])
    }

    @Test
    fun `no buckets is no figures rather than zero`() {
        assertNull(UsageAccess.dailyAverages(emptyList(), setOf("a")))
        assertEquals(0, UsageAccess.daysCovered(emptyList()))
    }
}
