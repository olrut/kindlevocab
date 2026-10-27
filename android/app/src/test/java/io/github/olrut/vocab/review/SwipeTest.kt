package io.github.olrut.vocab.review

import org.junit.Assert.assertEquals
import org.junit.Test

class SwipeTest {
    private val w = 1000f
    private val h = 2000f
    private val fling = 1500f

    private fun decide(dx: Float = 0f, dy: Float = 0f, vx: Float = 0f, vy: Float = 0f) = decideSwipe(dx, dy, vx, vy, w, h, fling)

    @Test
    fun `horizontal drag past a quarter of the width goes to the next word, either way`() {
        assertEquals(Swipe.Next, decide(dx = 251f))
        assertEquals(Swipe.Next, decide(dx = -251f))
        assertEquals(Swipe.None, decide(dx = 249f))
    }

    @Test
    fun `a short horizontal fling also counts, but only in the drag direction`() {
        assertEquals(Swipe.Next, decide(dx = 60f, vx = 1600f))
        assertEquals(Swipe.Next, decide(dx = -60f, vx = -1600f))
        assertEquals(Swipe.None, decide(dx = 60f, vx = -1600f))
        assertEquals(Swipe.None, decide(dx = 60f, vx = 1400f))
    }

    @Test
    fun `downward drag past a quarter of the height marks learned`() {
        assertEquals(Swipe.Learned, decide(dy = 501f))
        assertEquals(Swipe.None, decide(dy = 499f))
        assertEquals(Swipe.Learned, decide(dy = 80f, vy = 1600f))
    }

    @Test
    fun `upward swipes do nothing`() {
        assertEquals(Swipe.None, decide(dy = -900f, vy = -3000f))
    }

    @Test
    fun `the dominant axis decides a diagonal drag`() {
        assertEquals(Swipe.Next, decide(dx = 600f, dy = 550f))
        assertEquals(Swipe.Learned, decide(dx = 300f, dy = 700f))
    }

    @Test
    fun `a tap-sized movement is nothing`() {
        assertEquals(Swipe.None, decide(dx = 3f, dy = 2f, vx = 100f, vy = 50f))
    }
}
