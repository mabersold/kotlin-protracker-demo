package pcm

import model.Instrument
import model.PanningPosition
import model.Row
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * ChannelAudioGenerator - does the bulk of the resampling work for a Protracker module
 *
 * Contains audio PCM data, information about the current note being played, and resampling state information
 */
class ChannelAudioGenerator(
    row: Row,
    private val panningPosition: PanningPosition
) {
    private val activeNote = ActiveNote(row)
    private val resamplingState = ResamplingState()
    private lateinit var activeInstrument: Instrument
    private var currentVolume: Byte = 0

    companion object {
        private const val SAMPLING_RATE = 44100.0
        private const val PAL_CLOCK_RATE = 7093789.2
    }

    /**
     * retrieves next sample from the channel
     *
     * Calculates the actual sample based on the current resampling state, then updates the resampling state
     * The function to calculate the sample is simply by muliplying the slope by the number of interations we have passed in the current run,
     * and then adding it to the current sample in the resampling state
     *
     * By doing this, we get a smooth line in between samples from the original audio data instead of blocky steps
     */
    fun getNextSample(): Pair<Byte, Byte> {
        if (!activeNote.isInstrumentCurrentlyPlaying) {
            return Pair(0, 0)
        }

        val actualSample = ((resamplingState.slope * resamplingState.samplesSinceSampleChanged).toInt() + resamplingState.currentSample).toByte()
        val volumeAdjustedSample = adjustForVolume(actualSample, currentVolume)

        updateResamplingState()

        return getStereoSample(volumeAdjustedSample)
    }

    /**
     * updates the active row of the current object
     *
     * receives a row and a list of instruments
     *
     * If we are to play a new note, update the active note data and resampling state. A new note is indicated by if the
     * provided row has a period of anything other than zero.
     *
     * Note that not every row with a period will have an instrument number specified, so only update the instrument number
     * if it is present
     */
    fun updateActiveRow(row: Row, instruments: List<Instrument>) {
        activeNote.activeRow = row
        if (row.period != 0) {
            // if the new row has a note indicated, we need to change the active period to the new active period and reset the instrument sampling position
            activeNote.isInstrumentCurrentlyPlaying = true
            activeNote.activePeriod = row.period
            resamplingState.currentSamplePositionOfInstrument = 2

            resamplingState.samplesSinceSampleChanged = 0
            resamplingState.sampleProgressCounter = 0.0
            resamplingState.samplesPerSecond = getSamplesPerSecond(activeNote.activePeriod)

            // if the new row has an instrument indicated, we need to change the active instrument number
            if (row.instrumentNumber != 0 && row.instrumentNumber != activeNote.activeInstrumentNumber) {
                activeNote.activeInstrumentNumber = row.instrumentNumber
                activeInstrument = instruments[activeNote.activeInstrumentNumber - 1]
            }

            currentVolume = if (row.effectNumber == 12) {
                (row.effectXValue * 16 + row.effectYValue).coerceAtMost(64).toByte()
            } else {
                activeInstrument.volume
            }

            updateCurrentAndNextSamples()
        }

        // A change volume effect can take place without a new note effect - in this case, we apply the
        if (row.effectNumber == 12) {
            currentVolume = (row.effectXValue * 16 + row.effectYValue).coerceAtMost(64).toByte()
        }
    }

    /**
     * Some effect commands take place for every tick. This function invokes those effects.
     */
    fun applyPerTickEffects() {
        if (activeNote.activeRow.effectNumber == 10) {
            currentVolume = if (activeNote.activeRow.effectXValue > 0) {
                (activeNote.activeRow.effectXValue + currentVolume).coerceAtMost(64).toByte()
            } else {
                (currentVolume - activeNote.activeRow.effectYValue).coerceAtLeast(0).toByte()
            }
        }
    }

    /**
     * Accepts the sample and responds with a volume-adjusted sample. Maximum volume is 64 - at 64, it will simply respond
     * with the sample at the original value that it was already at. For anything below 64, it determines the volume ratio
     * and multiplies the sample by that. A volume value of zero will result in a sample value of zero.
     */
    private fun adjustForVolume(actualSample: Byte, volume: Byte): Byte {
        if (volume == 64.toByte()) {
            return actualSample
        }

        return (actualSample * (volume / 64.0)).roundToInt().toByte()
    }

    /**
     * Accepts a sample byte and responds with a pair of bytes adjusted for stereo panning
     *
     * Protracker panning is very simple: it's either left channel or right channel. All we need to do is respond with the
     * sample in the pair's first field for left panning, or second field for right panning.
     */
    private fun getStereoSample(sample: Byte): Pair<Byte, Byte> =
        if (PanningPosition.LEFT == panningPosition) Pair(sample, 0) else Pair(0, sample)

    /**
     * For resampling purposes, determine how many iterations of the current sample will take place between now and the
     * next sample.
     *
     * The number of iterations will be used to calculate the resampled values that take place between the current and
     * next sample
     */
    private fun getIterationsUntilNextSample(samplesPerSecond: Double, counter: Double): Int {
        val iterationsPerSample = floor(SAMPLING_RATE / samplesPerSecond).toInt()
        val maximumCounterValueForExtraIteration = (SAMPLING_RATE - (samplesPerSecond * iterationsPerSample))

        return iterationsPerSample + additionalIteration(counter, maximumCounterValueForExtraIteration)
    }

    /**
     * Determines if there is an additional iteration to the expected iterations - essentially accounting for remainders
     */
    private fun additionalIteration(counter: Double, maximumCounterValueForExtraIteration: Double): Int =
        if (counter < maximumCounterValueForExtraIteration) 1 else 0

    /**
     * Updates the resampling state - to be called after every time we generate a sample
     */
    private fun updateResamplingState() {
        resamplingState.sampleProgressCounter += resamplingState.samplesPerSecond
        resamplingState.samplesSinceSampleChanged++
        if (resamplingState.sampleProgressCounter > SAMPLING_RATE) {
            resamplingState.sampleProgressCounter -= SAMPLING_RATE
            resamplingState.currentSamplePositionOfInstrument++

            // If we have exceeded the length of the audio data for an unlooped instrument, we want to stop playing it
            if (!isInstrumentLooped(activeInstrument) && activeNote.isInstrumentCurrentlyPlaying && resamplingState.currentSamplePositionOfInstrument >= activeInstrument.audioData?.size!!) {
                activeNote.isInstrumentCurrentlyPlaying = false
                activeNote.activePeriod = 0
            }

            updateCurrentAndNextSamples()

            resamplingState.samplesPerSecond = getSamplesPerSecond(activeNote.activePeriod)
            resamplingState.iterationsUntilNextSample = getIterationsUntilNextSample(resamplingState.samplesPerSecond, resamplingState.sampleProgressCounter)
            resamplingState.samplesSinceSampleChanged = 0
        }
    }

    /**
     * Store the next two samples we will compare for resampling purposes - to be called only when we advance through
     * the audio data during the resampling process
     */
    private fun updateCurrentAndNextSamples() {
        if (!activeNote.isInstrumentCurrentlyPlaying) {
            return
        }

        // If the instrument is looped and we have exceeded the length of the audio file, set the new sample position to the loop offset
        if (isInstrumentLooped(activeInstrument) && resamplingState.currentSamplePositionOfInstrument >= activeInstrument.audioData?.size!!) {
            resamplingState.currentSamplePositionOfInstrument = activeInstrument.repeatOffsetStart * 2
        }

        resamplingState.currentSample = activeInstrument.audioData?.get(resamplingState.currentSamplePositionOfInstrument) ?: 0
        resamplingState.nextSample = getNextSample(activeInstrument, resamplingState.currentSamplePositionOfInstrument)

        resamplingState.slope = calculateSlope(resamplingState.currentSample, resamplingState.nextSample, resamplingState.iterationsUntilNextSample)
    }

    /**
     * Determine the next sample we will use for interpolation. Normally, this will just be 1 position higher than the
     * current sample. If we reach the end of the audio data, we will either return a 0 (for an unlooped instrument) or
     * whatever is at the repeat offset (for a looped instrument)
     */
    private fun getNextSample(instrument: Instrument, currentSamplePosition: Int): Byte {
        if (currentSamplePosition + 1 >= instrument.audioData?.size!!) {
            if (!isInstrumentLooped(instrument)) {
                return 0
            }

            //if the instrument is looped, return whatever value is at the repeat offset start
            return instrument.audioData?.get(instrument.repeatOffsetStart * 2)!!
        }

        return instrument.audioData?.get(currentSamplePosition + 1) ?: 0
    }

    private fun calculateSlope(firstSample: Byte, secondSample: Byte, run: Int): Double =
        (secondSample - firstSample).toDouble() / run

    private fun getSamplesPerSecond(activePeriod: Int) =
        PAL_CLOCK_RATE / (activePeriod * 2)

    private fun isInstrumentLooped(instrument: Instrument) =
        instrument.repeatLength > 1

    data class ResamplingState(
        var samplesPerSecond: Double = 0.0,
        var iterationsUntilNextSample: Int = 0,
        var currentSamplePositionOfInstrument: Int = 2,
        var samplesSinceSampleChanged: Int = 0,
        var sampleProgressCounter: Double = 0.0,
        var currentSample: Byte = 0,
        var nextSample: Byte = 0,
        var slope: Double = 0.0
    )

    data class ActiveNote(
        var activeRow: Row,
        var isInstrumentCurrentlyPlaying: Boolean = false,
        var activePeriod: Int = 0, //The period (aka note) can be modified by effects, so it should be tracked separately from the active row
        var activeInstrumentNumber: Int = 0
    )
}