package pcm

import kotlin.math.PI
import kotlin.math.min

class LowPassFilter(cutoffFrequency: Float, samplingRate: Float) {
    private val RC = 1.0f / (2.0f * PI * (min(cutoffFrequency, samplingRate / 2f))).toFloat()
    private val dt = 1.0f / samplingRate
    private val alpha = dt / (RC + dt)
    private var filteredValue = 0f

    fun filter(input: Float): Float {
        filteredValue = alpha * input + (1 - alpha) * filteredValue
        return filteredValue
    }

    fun reset() {
        filteredValue = 0f
    }
}