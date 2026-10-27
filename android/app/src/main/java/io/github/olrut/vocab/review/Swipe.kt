package io.github.olrut.vocab.review

import kotlin.math.abs

enum class Swipe { Next, Learned, None }

/**
 * What a finished drag means (docs/SPEC.md § 6). The dominant axis decides. Sideways, either way:
 * next word. Down: learned. Up: nothing. A drag counts once it passes a quarter of the screen,
 * or earlier when it ends in a fling (faster than [flingVelocity], in the drag's direction).
 * Distances in px, velocities in px/s.
 */
fun decideSwipe(dx: Float, dy: Float, vx: Float, vy: Float, width: Float, height: Float, flingVelocity: Float): Swipe {
    fun passes(d: Float, v: Float, size: Float) = abs(d) > size / 4 || (abs(v) > flingVelocity && v * d > 0)
    return if (abs(dx) >= abs(dy)) {
        if (passes(dx, vx, width)) Swipe.Next else Swipe.None
    } else {
        if (dy > 0 && passes(dy, vy, height)) Swipe.Learned else Swipe.None
    }
}
