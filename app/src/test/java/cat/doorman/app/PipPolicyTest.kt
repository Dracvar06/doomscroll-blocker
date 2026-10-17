package cat.doorman.app

import cat.doorman.app.limits.Allowances
import cat.doorman.app.service.PipPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * An app swiped away into its floating window is still the app.
 */
class PipPolicyTest {

    private val block = Allowances.Outcome.Block("com.google.android.youtube", Allowances.Reason.ALLOWANCE_SPENT)
    private val clock = Allowances.Outcome.OnTheClock(listOf("com.google.android.youtube|DAY"), 120_000)

    @Test
    fun `a whole-app block covers the floating window`() {
        assertEquals(PipPolicy.Action.Cover, PipPolicy.decide(false, block))
    }

    @Test
    fun `a schedule counts as a block`() {
        val schedule = Allowances.Outcome.Block("com.google.android.youtube", Allowances.Reason.SCHEDULE)
        assertEquals(PipPolicy.Action.Cover, PipPolicy.decide(false, schedule))
    }

    /** The reported case: a blocked Short swiped away keeps playing in the corner. */
    @Test
    fun `an app left while blocked stays covered`() {
        assertEquals(PipPolicy.Action.Cover, PipPolicy.decide(true, null))
    }

    @Test
    fun `left while blocked beats time still on the clock`() {
        assertEquals(PipPolicy.Action.Cover, PipPolicy.decide(true, clock))
    }

    @Test
    fun `time left means the floating window is timed, not covered`() {
        assertEquals(
            PipPolicy.Action.Charge(listOf("com.google.android.youtube|DAY")),
            PipPolicy.decide(false, clock),
        )
    }

    @Test
    fun `nothing held means nothing to do`() {
        assertEquals(PipPolicy.Action.None, PipPolicy.decide(false, null))
        assertEquals(PipPolicy.Action.None, PipPolicy.decide(false, Allowances.Outcome.Allow))
    }
}
