package cat.doorman.app.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cat.doorman.app.R
import cat.doorman.app.limits.DialScale
import cat.doorman.app.limits.MINUTES_PER_DAY
import cat.doorman.app.limits.Period
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
    var dragging by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    // Everything about the handle answers to the finger: it swells, its halo
    // brightens, and it ticks at every five-minute step. A dial you cannot feel
    // is a picture of a dial.
    val press by animateFloatAsState(if (dragging) 1f else 0f, label = "grip")

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
                        dragging = true
                        var lastMinute = if (draggingStart) {
                            current.fromMinute
                        } else {
                            current.toMinute
                        }
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            change.consume()
                            val minute = minuteFor(change.position, size.width, size.height)
                            if (minute != lastMinute) {
                                lastMinute = minute
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                            onChange(
                                if (draggingStart) {
                                    current.copy(fromMinute = minute)
                                } else {
                                    current.copy(toMinute = minute)
                                },
                            )
                        }
                        dragging = false
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
                drawHandle(centre, handlePx, held, handleFill, press)
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
                text = dialLabel(window.lengthMinutes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A rotary dial for a time budget, which is what a kitchen timer is.
 *
 * One turn covers the whole of what the chosen period can mean: an hour, six
 * hours, or forty-two hours. The numbers around it are not evenly spread --
 * see [DialScale] -- so that two minutes an hour and thirty hours a week are
 * both reachable with the same thumb and neither needs surgical precision.
 */
@Composable
fun MinutesDial(
    minutes: Int,
    period: Period,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var typing by remember { mutableStateOf(false) }
    RotaryDial(
        value = minutes,
        positions = remember(period) { DialScale.positions(period) },
        centreTextFor = { dialLabel(it) },
        description = durationLabel(minutes),
        colourAt = { budgetColour(it.toFloat() / DialScale.maxMinutes(period)) },
        onType = { typing = true },
        modifier = modifier,
        onChange = onChange,
    )
    if (typing) {
        DurationEntryDialog(
            majorLabel = stringResource(R.string.dial_field_hours),
            minorLabel = stringResource(R.string.dial_field_minutes),
            major = minutes / 60,
            minor = minutes % 60,
            onDismiss = { typing = false },
            onConfirm = { hours, mins ->
                // Clamped, not rejected. Someone typing 90 minutes an hour has
                // said something impossible, and the nearest thing they can
                // have is the whole hour.
                onChange(DialScale.clampTo(hours * 60 + mins, period))
                typing = false
            },
        )
    }
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
    var typing by remember { mutableStateOf(false) }
    RotaryDial(
        value = seconds,
        positions = remember { (0..MAX_DELAY_SECONDS step DELAY_STEP_SECONDS).toList() },
        centreTextFor = { delayLabel(it) },
        description = delayLabel(seconds),
        colourAt = { waitColour(it) },
        onType = { typing = true },
        modifier = modifier,
        onChange = onChange,
    )
    if (typing) {
        DurationEntryDialog(
            majorLabel = stringResource(R.string.dial_field_minutes),
            minorLabel = stringResource(R.string.dial_field_seconds),
            major = seconds / 60,
            minor = seconds % 60,
            onDismiss = { typing = false },
            onConfirm = { mins, secs ->
                onChange((mins * 60 + secs).coerceIn(0, MAX_DELAY_SECONDS))
                typing = false
            },
        )
    }
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

/**
 * One turn, from the first of [positions] to the last.
 *
 * The dial knows nothing about what it is measuring; it turns through a list of
 * values spread evenly around the circle. That is what lets the same control be
 * a kitchen timer for minutes and a countdown for seconds, and what lets a
 * budget dial accelerate without the drawing code knowing that it does.
 */
@Composable
private fun RotaryDial(
    value: Int,
    positions: List<Int>,
    centreTextFor: @Composable (Int) -> String,
    description: String,
    colourAt: (Int) -> Color,
    onType: () -> Unit,
    modifier: Modifier = Modifier,
    onChange: (Int) -> Unit,
) {
    val track = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.22f)
    val handleFill = MaterialTheme.colorScheme.onPrimaryContainer
    val strokePx = with(LocalDensity.current) { 22.dp.toPx() }
    val handlePx = with(LocalDensity.current) { 10.dp.toPx() }
    val current by rememberUpdatedState(value)
    val rungs by rememberUpdatedState(positions)
    val haptics = LocalHapticFeedback.current

    // The dial follows the finger locally and reports once, when the finger
    // lifts. Reporting every step looked fine for a time budget and was wrong
    // for the change delay: shortening that wait is itself a change that has to
    // wait, so a continuous drag started a fresh countdown on every step while
    // the dial sat still, refusing to move.
    var draggingTurn by remember { mutableStateOf<Float?>(null) }
    val turnOfValue = turnOf(positions, value)
    // Snapped, so the handle clicks from one duration to the next under the
    // finger rather than sliding between them, the way a dial with detents does.
    val target = snapTurn(positions, draggingTurn ?: turnOfValue)
    // A value that arrives from anywhere but the finger -- typed in, clamped by
    // a change of period, restored from storage -- travels there. Under the
    // finger the dial goes exactly where the finger is, because a control that
    // lags behind a thumb feels broken rather than smooth.
    val settled by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        label = "dial",
    )
    val press by animateFloatAsState(if (draggingTurn != null) 1f else 0f, label = "grip")
    val shownTurn = if (draggingTurn == null) settled else target
    // While a finger is on it, the dial says what the rung under the finger
    // says. At rest it says the value itself, which is not always a rung: a
    // typed 5 h 04 has to read back as 5 h 04, not as the nearest thing the
    // dial could have been turned to.
    val shown = if (draggingTurn == null) value else valueAtTurn(positions, shownTurn)
    val colourStops = remember(positions, colourAt) { colourStopsFor(positions, colourAt) }
    val typeLabel = stringResource(R.string.dial_type_action)

    Box(
        modifier = modifier
            .size(160.dp)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier
                .size(160.dp)
                .pointerInput(positions) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val from = turnOf(rungs, current)
                        val handle = handleAt(from, size.width, size.height)
                        if (!nearHandle(down.position, handle)) return@awaitEachGesture
                        down.consume()
                        var lastRung = Math.round(from * (rungs.size - 1))
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            change.consume()
                            val degrees = RingGeometry.degreesFromTop(
                                change.position.x - size.width / 2,
                                change.position.y - size.height / 2,
                            )
                            val turn = RingGeometry.withoutCrossingSeam(
                                candidateTurn = degrees / 360f,
                                previousTurn = draggingTurn ?: from,
                            )
                            draggingTurn = turn
                            // One tick per rung, the way a kitchen timer clicks.
                            // It is also the only way to tell, without looking,
                            // that the numbers are moving at all.
                            val rung = Math.round(turn * (rungs.size - 1))
                            if (rung != lastRung) {
                                lastRung = rung
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        }
                        draggingTurn?.let {
                            val landed = valueAtTurn(rungs, it)
                            if (landed != current) onChange(landed)
                        }
                        draggingTurn = null
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
            if (shownTurn > 0f) {
                // Turned a quarter so the gradient's own zero lands at the top
                // of the dial, where the arc starts. A sweep gradient begins at
                // three o'clock; rotating the canvas is cheaper than rewriting
                // every colour stop to account for the offset.
                rotate(degrees = -90f) {
                    drawArc(
                        brush = Brush.sweepGradient(
                            colorStops = colourStops,
                            center = center,
                        ),
                        startAngle = 0f,
                        sweepAngle = shownTurn * 360f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = ringSize,
                        style = Stroke(width = strokePx),
                    )
                }
            }
            val radians = Math.toRadians((shownTurn * 360f - 90f).toDouble())
            val radius = (size.minDimension - strokePx) / 2
            val centre = Offset(
                size.width / 2 + (cos(radians) * radius).toFloat(),
                size.height / 2 + (sin(radians) * radius).toFloat(),
            )
            drawHandle(centre, handlePx, colourAt(shown), handleFill, press)
        }
        // The number is a button. Tapping it opens a keyboard, for anyone who
        // knows exactly what they want and would rather say it than hunt for it
        // around a circle.
        Text(
            text = centreTextFor(shown),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(RoundedCornerShape(percent = 50))
                .clickable(onClickLabel = typeLabel, onClick = onType)
                // A faint pill, so the number reads as something you can press.
                // Without it the keyboard is a feature nobody finds: plain text
                // in the middle of a ring looks like a readout.
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                .padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}

/**
 * The colours a dial can wear, and what they mean.
 *
 * Green is "this is fine" and red is "look at this". Which end of a dial earns
 * which is not the same question twice:
 *
 * - On a **budget**, more is worse. Forty-two hours a week is a lot of
 *   scrolling and the ring should not pretend otherwise.
 * - On the **change delay**, less is worse. No wait at all means an impulse can
 *   undo a decision before the impulse passes, which is the one thing this app
 *   exists to prevent -- so "immediate" is the red end, and half a minute of
 *   pause is already enough to be green.
 *
 * Fixed colours rather than the theme's, because the theme is taken from the
 * user's wallpaper: green to red has to mean green to red on every phone, not
 * whatever two colours the wallpaper happens to offer. They are mid-toned so
 * that neither end disappears against a light background or a dark one.
 *
 * The ring the day is drawn on keeps the theme colour and no gradient. There
 * the arc is the *block*, and a longer one is someone taking better care of
 * themselves; turning that red would be the app frowning at the thing it exists
 * to encourage.
 */
private val DIAL_LOW = Color(0xFF63BE72)
private val DIAL_MID = Color(0xFFE0B341)
private val DIAL_HIGH = Color(0xFFDF6350)

/** Green while the budget is small, red as it takes over the period. */
private fun budgetColour(shareOfTheMost: Float): Color = between(shareOfTheMost)

/**
 * Red at no wait at all, green by half a minute.
 *
 * The scale is short on purpose. The pause only has to outlast the reach for
 * the phone, and a dial that stayed amber until four minutes would be telling
 * people their perfectly good thirty seconds was not good enough.
 */
private fun waitColour(seconds: Int): Color =
    between(1f - (seconds.toFloat() / SETTLED_WAIT_SECONDS))

private const val SETTLED_WAIT_SECONDS = 30f

/** 0 is green, 1 is red, and the amber in the middle keeps the pair from muddying. */
private fun between(t: Float): Color {
    val f = t.coerceIn(0f, 1f)
    return if (f < 0.5f) {
        lerp(DIAL_LOW, DIAL_MID, f * 2f)
    } else {
        lerp(DIAL_MID, DIAL_HIGH, (f - 0.5f) * 2f)
    }
}

/**
 * The gradient's colour at each point around the dial.
 *
 * The colour follows the *duration* the dial is showing there, not the angle.
 * Those are not the same thing once the rungs stop being evenly spaced: twenty
 * minutes a day sits a third of the way round the dial, and colouring it by
 * angle painted it amber -- an alarm over a budget that is nothing of the sort.
 * Sampled at enough points for the curve to read as smooth.
 */
private fun colourStopsFor(
    positions: List<Int>,
    colourAt: (Int) -> Color,
): Array<Pair<Float, Color>> = Array(STOPS) { i ->
    val turn = i / (STOPS - 1f)
    turn to colourAt(valueAtTurn(positions, turn))
}

private const val STOPS = 16

/**
 * A handle that answers to the finger: a soft halo the size of the area that
 * actually grabs it, swelling while it is held.
 *
 * The halo is not decoration. Grabbing was narrowed to the handle so that
 * scrolling the page past a dial would stop turning it, and that fix is only
 * fair if the handle looks like the part you are meant to take hold of.
 */
private fun DrawScope.drawHandle(
    centre: Offset,
    handlePx: Float,
    colour: Color,
    fill: Color,
    press: Float,
) {
    drawCircle(
        color = colour.copy(alpha = 0.14f + 0.14f * press),
        radius = handlePx * (1.9f + 0.5f * press),
        center = centre,
    )
    val grown = handlePx * (1f + 0.18f * press)
    drawCircle(color = fill, radius = grown, center = centre)
    drawCircle(color = colour, radius = grown * 0.45f, center = centre)
}

/** How far round the dial a value sits, as a fraction of a turn. */
private fun turnOf(positions: List<Int>, value: Int): Float =
    DialScale.nearestIndex(positions, value).toFloat() / (positions.size - 1)

/** The same fraction, pulled onto the nearest rung. */
private fun snapTurn(positions: List<Int>, turn: Float): Float =
    Math.round(turn * (positions.size - 1)).toFloat() / (positions.size - 1)

private fun valueAtTurn(positions: List<Int>, turn: Float): Int =
    positions[Math.round(turn * (positions.size - 1)).coerceIn(0, positions.size - 1)]

/** Five minutes. Beyond this a wait is a punishment rather than a pause. */
const val MAX_DELAY_SECONDS = 300

/** Fine enough to land on ten and thirty seconds, coarse enough to be draggable. */
const val DELAY_STEP_SECONDS = 10

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
