package cat.doorman.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cat.doorman.app.R
import cat.doorman.app.limits.MINUTES_PER_DAY
import cat.doorman.app.limits.RingGeometry
import cat.doorman.app.limits.Window
import kotlin.math.cos
import kotlin.math.sin

/**
 * A day as a ring, with the blocked stretch drawn as an arc between two
 * draggable handles.
 *
 * The shape is the argument. A day has no end and no beginning -- midnight is a
 * point you pass, not an edge you fall off -- and a blocked stretch from nine at
 * night to nine in the morning is simply an arc across the top. Every awkward
 * question the model had to answer in prose ("what does it mean for a window to
 * end before it starts?") is answered here by looking at it.
 *
 * It also replaces two taps into a system dialog with one gesture, which is what
 * was actually asked for.
 */
@Composable
fun TimeRing(
    window: Window,
    onChange: (Window) -> Unit,
    onPickExactly: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val held = MaterialTheme.colorScheme.primary
    // Not surfaceVariant: on a dark card it is nearly the card's own colour,
    // and an invisible track leaves a floating arc with no clock behind it --
    // you cannot see how much of the day is *not* held.
    val track = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.22f)
    val handleFill = MaterialTheme.colorScheme.onPrimaryContainer
    val current by rememberUpdatedState(window)

    // Chosen when a drag begins and kept for the whole gesture, so a handle
    // dragged past the other one does not hand over mid-swipe.
    var draggingStart by remember { mutableStateOf(true) }

    val strokePx = with(LocalDensity.current) { 26.dp.toPx() }
    val handlePx = with(LocalDensity.current) { 11.dp.toPx() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .pointerInput(Unit) {
                    // Claimed from the touch down rather than through
                    // detectDragGestures. The ring lives inside a scrolling
                    // column, and a mostly-vertical drag was being handed to
                    // the scroller: the page moved and the handle did not.
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val startHandle = handleAt(
                            current.fromMinute.toFloat() / MINUTES_PER_DAY,
                            size.width,
                            size.height,
                        )
                        val endHandle = handleAt(
                            current.toMinute.toFloat() / MINUTES_PER_DAY,
                            size.width,
                            size.height,
                        )
                        val grabbedStart = nearHandle(down.position, startHandle)
                        val grabbedEnd = nearHandle(down.position, endHandle)
                        if (!grabbedStart && !grabbedEnd) return@awaitEachGesture
                        down.consume()
                        // Kept for the whole gesture, so dragging one handle
                        // past the other does not hand over mid-swipe.
                        draggingStart = if (grabbedStart && grabbedEnd) {
                            RingGeometry.nearerHandleIsStart(
                                minuteFor(down.position, size.width, size.height),
                                current.fromMinute,
                                current.toMinute,
                            )
                        } else {
                            grabbedStart
                        }
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            change.consume()
                            val minute = minuteFor(change.position, size.width, size.height)
                            onChange(
                                if (draggingStart) {
                                    current.copy(fromMinute = minute)
                                } else {
                                    current.copy(toMinute = minute)
                                },
                            )
                        }
                    }
                },
        ) {
            val ringSize = Size(size.width - strokePx, size.height - strokePx)
            val topLeft = Offset(strokePx / 2, strokePx / 2)

            drawArc(
                color = track,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = ringSize,
                style = Stroke(width = strokePx),
            )

            // Always drawn from the start, sweeping forwards by the window's own
            // length. Drawing "from the smaller number to the larger" would show
            // the inverse arc for every window that crosses midnight -- exactly
            // the case this control exists to make obvious.
            //
            // -90 puts zero degrees at the top; Canvas measures from three
            // o'clock.
            drawArc(
                color = held,
                startAngle = RingGeometry.angleOf(current.fromMinute) - 90f,
                sweepAngle = (current.lengthMinutes.toFloat() / MINUTES_PER_DAY) * 360f,
                useCenter = false,
                topLeft = topLeft,
                size = ringSize,
                style = Stroke(width = strokePx),
            )

            listOf(current.fromMinute, current.toMinute).forEach { minute ->
                val radians = Math.toRadians(
                    (RingGeometry.angleOf(minute) - 90f).toDouble(),
                )
                val radius = (size.minDimension - strokePx) / 2
                val centre = Offset(
                    size.width / 2 + (cos(radians) * radius).toFloat(),
                    size.height / 2 + (sin(radians) * radius).toFloat(),
                )
                drawCircle(color = handleFill, radius = handlePx, center = centre)
                drawCircle(color = held, radius = handlePx * 0.45f, center = centre)
            }

        }

        // Hour labels inside the ring. A clock face with no numbers is a gauge,
        // and a gauge does not tell you whether the arc you are looking at
        // covers the evening or the morning.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .padding(38.dp),
        ) {
            // Midnight and noon only. Six and eighteen sit at the same height
            // as the times in the middle, and on a ring this size they collided
            // with them -- two labels that orient the face are worth more than
            // four that fight the thing they are labelling.
            listOf(
                "0" to Alignment.TopCenter,
                "12" to Alignment.BottomCenter,
            ).forEach { (label, where) ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(where),
                )
            }
        }

        // Tapping the times still opens the numeric picker. The dial is the
        // pleasant way in, not the only way in.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.pointerInput(Unit) {
                detectTapGestures { offset ->
                    onPickExactly(offset.x < size.width / 2)
                }
            },
        ) {
            Text(
                text = "${formatMinute(window.fromMinute)} → ${formatMinute(window.toMinute)}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = durationLabel(window.lengthMinutes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A rotary dial for a number of minutes, which is what a kitchen timer is.
 *
 * One turn is an hour, because that is how the thing on a kitchen counter
 * works. Longer budgets are reachable from the chips beside it rather than by
 * winding the dial round more than once, which is fiddly to do and impossible
 * to read back.
 */
@Composable
fun MinutesDial(minutes: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    val label = androidx.compose.ui.res.pluralStringResource(R.plurals.minutes, minutes, minutes)
    RotaryDial(
        value = minutes,
        maxValue = 60,
        stepValue = 5,
        minValue = 5,
        centreText = "$minutes",
        description = label,
        centreTextFor = { "$it" },
        modifier = modifier,
    ) { onChange(it) }
}

/**
 * The wait before a loosening change takes effect, as a dial.
 *
 * Capped at five minutes. Past that the wait stops being a pause for thought
 * and becomes a punishment, and an app that punishes people gets uninstalled by
 * the very people it was meant to help. Zero is a real position on this dial:
 * "no wait" is a legitimate choice and it is where a fresh install starts.
 */
@Composable
fun DelayDial(seconds: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    RotaryDial(
        value = seconds,
        maxValue = MAX_DELAY_SECONDS,
        stepValue = DELAY_STEP_SECONDS,
        minValue = 0,
        centreText = delayLabel(seconds),
        description = delayLabel(seconds),
        centreTextFor = { delayLabel(it) },
        modifier = modifier,
    ) { onChange(it) }
}

@Composable
private fun delayLabel(seconds: Int): String = when {
    seconds == 0 -> stringResource(R.string.change_delay_instant)
    seconds < 60 -> stringResource(R.string.change_delay_seconds, seconds)
    seconds % 60 == 0 -> stringResource(R.string.change_delay_minutes, seconds / 60)
    // Minutes and seconds. Reusing the duration string here read 150 seconds
    // as "2 h 30 min", which is a wait nobody set and this dial cannot reach.
    else -> stringResource(R.string.change_delay_minutes_seconds, seconds / 60, seconds % 60)
}

/** One turn, from nothing to [maxValue]. */
@Composable
private fun RotaryDial(
    value: Int,
    maxValue: Int,
    stepValue: Int,
    minValue: Int,
    centreText: String,
    description: String,
    centreTextFor: @Composable (Int) -> String,
    modifier: Modifier = Modifier,
    onChange: (Int) -> Unit,
) {
    val held = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.22f)
    val handleFill = MaterialTheme.colorScheme.onPrimaryContainer
    val strokePx = with(LocalDensity.current) { 22.dp.toPx() }
    val handlePx = with(LocalDensity.current) { 10.dp.toPx() }
    val current by rememberUpdatedState(value)

    // The dial follows the finger locally and reports once, when the finger
    // lifts. Reporting every step looked fine for a time budget and was wrong
    // for the change delay: shortening that wait is itself a change that has to
    // wait, so a continuous drag started a fresh countdown on every step while
    // the dial sat still, refusing to move.
    var dragging by remember { mutableStateOf<Int?>(null) }
    val shown = dragging ?: value

    Box(
        modifier = modifier
            .size(160.dp)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier
                .size(160.dp)
                .pointerInput(maxValue, stepValue, minValue) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val handle = handleAt(
                            current.coerceIn(0, maxValue) / maxValue.toFloat(),
                            size.width,
                            size.height,
                        )
                        if (!nearHandle(down.position, handle)) return@awaitEachGesture
                        down.consume()
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            change.consume()
                            val degrees = RingGeometry.degreesFromTop(
                                change.position.x - size.width / 2,
                                change.position.y - size.height / 2,
                            )
                            val raw = degrees / 360f * maxValue
                            val candidate = (Math.round(raw / stepValue) * stepValue)
                                .coerceIn(minValue, maxValue)
                            dragging = RingGeometry.withoutCrossingSeam(
                                candidate = candidate,
                                previous = dragging ?: current,
                                minValue = minValue,
                                maxValue = maxValue,
                            )
                        }
                        dragging?.let { if (it != current) onChange(it) }
                        dragging = null
                    }
                },
        ) {
            val ringSize = Size(size.width - strokePx, size.height - strokePx)
            val topLeft = Offset(strokePx / 2, strokePx / 2)
            drawArc(
                color = track,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = ringSize,
                style = Stroke(width = strokePx),
            )
            val fraction = shown.coerceIn(0, maxValue) / maxValue.toFloat()
            if (fraction > 0f) {
                drawArc(
                    color = held,
                    startAngle = -90f,
                    sweepAngle = fraction * 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = ringSize,
                    style = Stroke(width = strokePx),
                )
            }
            val radians = Math.toRadians((fraction * 360f - 90f).toDouble())
            val radius = (size.minDimension - strokePx) / 2
            val centre = Offset(
                size.width / 2 + (cos(radians) * radius).toFloat(),
                size.height / 2 + (sin(radians) * radius).toFloat(),
            )
            drawCircle(color = handleFill, radius = handlePx, center = centre)
            drawCircle(color = held, radius = handlePx * 0.45f, center = centre)
        }
        Text(
            text = if (dragging != null) centreTextFor(shown) else centreText,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Five minutes. Beyond this a wait is a punishment rather than a pause. */
const val MAX_DELAY_SECONDS = 300

/** Fine enough to land on ten and thirty seconds, coarse enough to be draggable. */
const val DELAY_STEP_SECONDS = 10

/** "12 h", "45 min", "1 h 30 min". */
@Composable
fun durationLabel(totalMinutes: Int): String {
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours == 0 -> stringResource(R.string.duration_minutes, minutes)
        minutes == 0 -> stringResource(R.string.duration_hours, hours)
        else -> stringResource(R.string.duration_hours_minutes, hours, minutes)
    }
}

/**
 * Where a handle sits, as a point on the ring.
 */
private fun handleAt(fractionOfTurn: Float, width: Int, height: Int): Offset {
    val radians = Math.toRadians((fractionOfTurn * 360f - 90f).toDouble())
    val radius = minOf(width, height) / 2f * 0.86f
    return Offset(
        width / 2f + (cos(radians) * radius).toFloat(),
        height / 2f + (sin(radians) * radius).toFloat(),
    )
}

/**
 * Whether a touch landed close enough to a handle to have meant it.
 *
 * The dial is grabbed by its handle, the way a knob is, rather than by anywhere
 * on the ring. Claiming the whole ring seemed right until a scroll down the
 * middle of the settings screen passed straight through it and turned the dial
 * on the way past -- a control in a long page cannot take every drag that
 * crosses it.
 *
 * The target is far wider than the handle is drawn, because a fingertip is.
 */
private fun nearHandle(touch: Offset, handle: Offset): Boolean {
    val dx = touch.x - handle.x
    val dy = touch.y - handle.y
    return kotlin.math.sqrt(dx * dx + dy * dy) <= GRAB_RADIUS_PX
}

private const val GRAB_RADIUS_PX = 110f

/** Where a touch on the ring lands, as a minute of the day. */
private fun minuteFor(touch: Offset, width: Int, height: Int): Int =
    RingGeometry.minuteAt(
        RingGeometry.degreesFromTop(touch.x - width / 2f, touch.y - height / 2f),
    )

/** Kept so the palette choices for the ring sit in one place. */
internal val RingUnset = Color.Transparent
