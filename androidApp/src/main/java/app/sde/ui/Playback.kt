package app.sde.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos

/**
 * The animation clock: [revealed] runs from 0 to [steps] grid steps over [secondsPerRun] seconds,
 * frame by frame. Scrubbing sets it directly and pauses.
 */
@Stable
class Playback(steps: Int) {
    var steps by mutableStateOf(steps)
    var revealed by mutableFloatStateOf(0f)
    var playing by mutableStateOf(true)
    var secondsPerRun by mutableFloatStateOf(8f)
    var loop by mutableStateOf(true)

    val finished: Boolean get() = revealed >= steps

    fun restart() {
        revealed = 0f
        playing = true
    }

    fun scrubTo(fraction: Float) {
        playing = false
        revealed = fraction.coerceIn(0f, 1f) * steps
    }
}

@Composable
fun rememberPlayback(steps: Int): Playback = remember { Playback(steps) }.also { it.steps = steps }

/**
 * Advances [playback] while it plays. When a run finishes it calls [onRunFinished] if looping
 * (e.g. to draw fresh paths), else it stops.
 */
@Composable
fun PlaybackClock(playback: Playback, onRunFinished: () -> Unit) {
    LaunchedEffect(playback.playing) {
        if (!playback.playing) return@LaunchedEffect
        var previous = 0L
        while (true) {
            withFrameNanos { now ->
                if (previous != 0L) {
                    val seconds = (now - previous) / 1e9f
                    playback.revealed = (playback.revealed + seconds / playback.secondsPerRun * playback.steps)
                        .coerceAtMost(playback.steps.toFloat())
                }
                previous = now
            }
            if (playback.finished) {
                if (playback.loop) {
                    onRunFinished()
                    playback.revealed = 0f
                    previous = 0L
                } else {
                    playback.playing = false
                    return@LaunchedEffect
                }
            }
        }
    }
}
