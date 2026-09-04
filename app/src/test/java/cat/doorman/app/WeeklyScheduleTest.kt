package cat.doorman.app

import cat.doorman.app.report.WeeklySchedule
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class WeeklyScheduleTest {

    @Test
    fun `a Monday before nine fires that same morning`() {
        assertEquals(
            LocalDateTime.parse("2026-09-07T09:00"),
            WeeklySchedule.nextFireAt(LocalDateTime.parse("2026-09-07T08:59")),
        )
    }

    @Test
    fun `nine on the dot has already fired, so it waits a week`() {
        assertEquals(
            LocalDateTime.parse("2026-09-14T09:00"),
            WeeklySchedule.nextFireAt(LocalDateTime.parse("2026-09-07T09:00")),
        )
    }

    @Test
    fun `midweek waits for the Monday coming`() {
        assertEquals(
            LocalDateTime.parse("2026-09-07T09:00"),
            WeeklySchedule.nextFireAt(LocalDateTime.parse("2026-09-04T23:30")),
        )
    }

    @Test
    fun `Sunday morning is the longest a report is ever held`() {
        assertEquals(
            LocalDateTime.parse("2026-09-07T09:00"),
            WeeklySchedule.nextFireAt(LocalDateTime.parse("2026-09-06T09:00")),
        )
    }
}
