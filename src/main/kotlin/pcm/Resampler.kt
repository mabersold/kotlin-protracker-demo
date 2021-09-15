package pcm

import model.Instrument
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Used to calculate a sample from the given instrument data. Contains the following fields:
 *
 * audioDataReference: Position in the instrument audio data for the next sample.
 *
 * step: Value to add to audioDataReference after retrieving the next sample. If this value decreases, so will the pitch
 * of the instrument. If it increases, the pitch will increase.
 *
 * instrument: The instrument that is being resampled. Contains the audio data ByteArray and loop information.
 */
class Resampler {
    companion object {
        private const val PAL_CLOCK_RATE = 7093789.2
    }

    var audioDataReference: Double = 2.0
    var instrument: Instrument? = null
    private var step: Double = 0.0

    /**
     * Retrieves a sample and performs linear interpolation. This follows the following steps:
     *
     * 1. Get the current sample and the subsequent sample from the audio data.
     * 2. Determine the rise between the two
     * 3. Determine the run between the two samples: How many samples we need to interpolate before we will switch to
     *    the next pair of samples
     * 4. Calculate the slope - needs to be a double so we can multiply properly
     * 5. Multiply the slope by our current position in the run and add to the sample, which is returned as a byte
     */
    fun getInterpolatedSample(): Byte {
        if (audioDataReference >= (instrument?.audioData?.size ?: 0)) {
            return 0
        }

        // 1: Get sample and subsequent sample from the audio data
        val flooredReference = floor(audioDataReference).toInt()
        val sample = instrument?.audioData?.get(flooredReference) ?: 0
        val subsequentSample = getSubsequentSample(instrument, flooredReference)

        // 2: Determine the rise
        val rise = subsequentSample - sample

        // 3: Determine the run.
        val stepsSinceFirstStep = floor((audioDataReference - flooredReference) / step).toInt()
        val remainingSteps = floor((flooredReference + 1 - audioDataReference) / step).toInt()
        val run = remainingSteps + stepsSinceFirstStep + 1

        // 4: Calculate the slope
        val slope = rise.toDouble() / run.toDouble()

        // 4.5: Update the audio data reference before we return the sample
        audioDataReference = getNextAudioDataReference(audioDataReference, step, instrument)

        // 5: Multiply the slop by our current position in the run and add to the sample, and return
        return (sample + (slope * stepsSinceFirstStep)).roundToInt().toByte()
    }

    /**
     * Recalculates the step: Since the step is the key for determining the pitch, whenever the pitch changes, we will
     * need to recalculate the step.
     */
    fun recalculateStep(period: Int, samplingRate: Double) {
        step = PAL_CLOCK_RATE / (period * 2) / samplingRate
    }

    /**
     * Returns the next sample in the original audio data. If we are at the end of the audio data array, either return 0
     * for an unlooped instrument, or return whatever is at the repeat offset for a looped instrument.
     */
    private fun getSubsequentSample(instrument: Instrument?, currentSamplePosition: Int): Byte {
        if (currentSamplePosition + 1 >= (instrument?.audioData?.size ?: 0)) {
            if (!isInstrumentLooped(instrument)) {
                return 0
            }

            //if the instrument is looped, return whatever value is at the repeat offset start
            return instrument?.audioData?.get(instrument.repeatOffsetStart * 2) ?: 0
        }

        return instrument?.audioData?.get(currentSamplePosition + 1) ?: 0
    }

    private fun isInstrumentLooped(instrument: Instrument?) =
        (instrument?.repeatLength ?: 0) > 1

    /**
     * Update the audio data reference by adding the step to it. If our new reference exceeds the length of the audio data,
     * and the instrument is looped, set our new reference to its position at the repeat offset (but keep the remainder)
     *
     * For unlooped instruments, there is no need to calculate this, since the resampler will just return 0 when it has
     * exceeded the length of the audio data
     */
    private fun getNextAudioDataReference(reference: Double, step: Double, instrument: Instrument?): Double {
        var newReference = reference + step
        if (isInstrumentLooped(instrument) &&  floor(newReference).toInt() >= (instrument?.audioData?.size ?: 0)) {
            val referenceRemainder = newReference - floor(newReference)
            newReference = ((instrument?.repeatOffsetStart ?: 0) * 2).toDouble() + referenceRemainder
        }

        return newReference
    }
}