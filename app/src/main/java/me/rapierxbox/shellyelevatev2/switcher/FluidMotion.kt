package me.rapierxbox.shellyelevatev2.switcher

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sign

// motion helpers after apples designing fluid interfaces talk (wwdc 2018)
// springs are described by how long they take to respond and how much they bounce
// a released gesture is projected forward with scroll view deceleration to pick its target
// and dragging past an edge follows the scroll view rubber band curve
object FluidMotion {

    // uiscrollview normal deceleration per millisecond. the thrown distance converges to v * 0.499s
    const val DECELERATION_NORMAL = 0.998f

    // spring stiffness for a unit mass that settles in roughly the given response time in seconds
    fun stiffness(responseSeconds: Float): Float {
        val omega = 2f * PI.toFloat() / responseSeconds
        return omega * omega
    }

    // where a throw at this velocity in px per second would come to rest
    fun project(velocity: Float, decelerationRate: Float = DECELERATION_NORMAL): Float =
        velocity / 1000f * decelerationRate / (1f - decelerationRate)

    // resistance past an edge. small pulls follow the finger almost one to one and big ones level off
    fun rubberBand(offset: Float, dimension: Float, coefficient: Float = 0.55f): Float {
        if (dimension <= 0f) return 0f
        val magnitude = (1f - 1f / (abs(offset) * coefficient / dimension + 1f)) * dimension
        return magnitude * sign(offset)
    }
}
