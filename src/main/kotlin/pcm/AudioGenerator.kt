package pcm

import model.ProTrackerModule
import java.nio.ByteBuffer
import kotlin.math.floor

class AudioGenerator(private val module: ProTrackerModule) {
   private var ticksPerDivision = 6
   private var beatsPerMinute = 125

    companion object {
        private const val SAMPLING_RATE = 44100.0
        private const val PAL_CLOCK_RATE = 7093789.2
    }

    fun generateNextSamples() {

    }

    //todo: calculate how much I need to allocate
    fun resample(audioData: ByteArray, period: Int): ByteArray {
        val samplesPerSecond = PAL_CLOCK_RATE / (period * 2)
        val returnBuffer = ByteBuffer.allocate(60000)

        var counter = 0.0

        //the first two bytes of the sample data are used only for looping data, so we should start at the third byte (index=2)
        var audioDataIndex = 2

        var iterationsSinceSampleChanged = 0
//        var incrementerIShouldDelete = 0
        while (audioDataIndex < audioData.size) {
            val totalIterationsUntilNextSample = getIterationsUntilNextSample(SAMPLING_RATE, samplesPerSecond, counter)

            val currentSample = audioData[audioDataIndex]
            val nextSample = if (audioDataIndex + 1 == audioData.size) 0 else audioData[audioDataIndex + 1]
            val rise = nextSample - currentSample
            val slope = rise.toDouble() / totalIterationsUntilNextSample.toDouble()

            val actualSample = ((slope * iterationsSinceSampleChanged).toInt() + currentSample).toByte()
            returnBuffer.put(actualSample)

            counter += samplesPerSecond
            iterationsSinceSampleChanged++
            if (counter > SAMPLING_RATE) {
                counter -= SAMPLING_RATE
                audioDataIndex++
                iterationsSinceSampleChanged = 0
            }
//            println(incrementerIShouldDelete++)
        }

        return returnBuffer.array()
    }

    private fun getIterationsUntilNextSample(samplingRate: Double, samplesPerSecond: Double, counter: Double): Int {
        val iterationsPerSample = floor(SAMPLING_RATE / samplesPerSecond).toInt()
        val maximumCounterValueForExtraIteration = (samplingRate - (samplesPerSecond * iterationsPerSample))

        return iterationsPerSample + additionalIteration(counter, maximumCounterValueForExtraIteration)
    }

    private fun additionalIteration(counter: Double, maximumCounterValueForExtraIteration: Double): Int =
        if (counter < maximumCounterValueForExtraIteration) 1 else 0
}