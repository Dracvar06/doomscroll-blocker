package cat.doorman.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cat.doorman.app.limits.Limits
import cat.doorman.app.limits.MINUTES_PER_DAY
import cat.doorman.app.limits.blockedMinutesOfWeek
import java.time.DayOfWeek
import java.time.format.TextStyle

/**
 * The week at a glance: seven rows of twenty-four hours, with the held stretches
 * filled in.
 *
 * This exists because time rules are the part of Doorman that words are worst
 * at. "Blocked 21:00 to 09:00 on weekdays" has to be read twice and still
 * leaves people unsure whether Friday night counts and what happens at
 * midnight. The same rule drawn as a strip answers both without a sentence: the
 * block visibly runs off the right-hand edge of Friday and continues at the
 * left of Saturday.
 *
 * It is also the honest check on the rules underneath. What is drawn is exactly
 * the array the blocking decision reads, so if the picture looks wrong, it is
 * wrong.
 */
@Composable
fun WeekTimeline(limits: Limits, modifier: Modifier = Modifier) {
    val week = limits.blockedMinutesOfWeek()
    // Read through the configuration so day names follow Doorman's own
    // language setting, which can differ from the phone's.
    val locale = LocalConfiguration.current.locales[0]
    val held = MaterialTheme.colorScheme.primary
    val free = MaterialTheme.colorScheme.surfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text("", modifier = Modifier.width(34.dp), style = MaterialTheme.typography.labelSmall)
            listOf(0, 6, 12, 18, 24).forEachIndexed { index, hour ->
                Text(
                    text = if (hour == 24) "" else "$hour",
                    style = MaterialTheme.typography.labelSmall,
                    color = onSurface,
                    modifier = Modifier.weight(if (index == 4) 0.01f else 1f),
                )
            }
        }
        DayOfWeek.entries.forEach { day ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = day.getDisplayName(TextStyle.SHORT, locale).take(3),
                    style = MaterialTheme.typography.labelSmall,
                    color = onSurface,
                    modifier = Modifier.width(34.dp),
                )
                Canvas(
                    modifier = Modifier
                        .weight(1f)
                        .height(14.dp)
                        .padding(end = 2.dp),
                ) {
                    drawRect(color = free, size = size)
                    val start = (day.value - 1) * MINUTES_PER_DAY
                    val perMinute = size.width / MINUTES_PER_DAY
                    // Runs of held minutes rather than a rectangle per minute:
                    // 1440 draw calls a row, seven rows, on every recomposition
                    // is a stutter nobody needs to see.
                    var runStart = -1
                    for (minute in 0..MINUTES_PER_DAY) {
                        val on = minute < MINUTES_PER_DAY && week[start + minute]
                        if (on && runStart < 0) runStart = minute
                        if (!on && runStart >= 0) {
                            drawRect(
                                color = held,
                                topLeft = Offset(runStart * perMinute, 0f),
                                size = Size((minute - runStart) * perMinute, size.height),
                            )
                            runStart = -1
                        }
                    }
                }
            }
        }
    }
}

/** Kept out of the drawing code so the colour choice is visible in one place. */
internal val TimelineEmpty = Color.Transparent
