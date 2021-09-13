package pcm

import model.EffectType
import model.Instrument
import model.PanningPosition
import model.Row
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * ChannelAudioGenerator - does the bulk of the resampling work for a Protracker module
 *
 * Contains audio PCM data, information about the current note being played, and resampling state information
 */
class ChannelAudioGenerator(
    private val panningPosition: PanningPosition
) {
    private val activeNote = ActiveNote()
    private val resamplingState = ResamplingState()
    private var vibratoState: VibratoEffectState = VibratoEffectState()
    private lateinit var activeInstrument: Instrument
    private var currentVolume: Byte = 0
    var beatsPerMinute: Int = 125
    var ticksPerRow: Int = 6

    companion object {
        private const val SAMPLING_RATE = 44100.0
        private const val PAL_CLOCK_RATE = 7093789.2
        private val SINE_TABLE = arrayListOf(0, 24, 49, 74, 97, 120, 141, 161, 180, 197, 212, 224, 235, 244, 250, 253, 255, 253, 250, 244, 235, 224, 212, 197, 180, 161, 141, 120, 97, 74, 49, 24, 0, -24, -49, -74, -97, -120, -141, -161, -180, -197, -212, -224, -235, -244, -250, -253, -255, -253, -250, -244, -235, -224, -212, -197, -180, -161, -141, -120, -97, -74, -49, -24)
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

        if (vibratoState.isActive) {
            val cyclePosition = vibratoState.samplesElapsed / vibratoState.samplesPerCyclePosition
            val sineTableValue = SINE_TABLE[floor(cyclePosition).roundToInt()]
            val depthAdjustment = sineTableValue * vibratoState.depth / 128
            if (activeNote.actualPeriod != activeNote.specifiedPeriod + depthAdjustment) {
                activeNote.actualPeriod = activeNote.specifiedPeriod + depthAdjustment
                resamplingState.audioDataStep = getSamplesPerSecond(activeNote.actualPeriod) / SAMPLING_RATE
            }
        }

        val actualSample = getInterpolatedSample(resamplingState.audioDataReference, resamplingState.audioDataStep)
        val volumeAdjustedSample = adjustForVolume(actualSample, currentVolume)

        updateResamplingState()

        if (vibratoState.isActive) {
            vibratoState.samplesElapsed++
            if (vibratoState.samplesElapsed >= vibratoState.samplesPerCycle) {
                vibratoState.samplesElapsed = 0
            }
        }

        return getStereoSample(volumeAdjustedSample)
    }

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
    private fun getInterpolatedSample(reference: Double, step: Double): Byte {
        // 1: Get sample and subsequent sample from the audio data
        val flooredReference = floor(reference).toInt()
        val sample = activeInstrument.audioData?.get(flooredReference)!!
        val subsequentSample = getSubsequentSample(activeInstrument, flooredReference)

        // 2: Determine the rise
        val rise = subsequentSample - sample

        // 3: Determine the run.
        val stepsSinceFirstStep = floor((reference - flooredReference) / step).toInt()
        val remainingSteps = floor((flooredReference + 1 - reference) / step).toInt()
        val run = remainingSteps + stepsSinceFirstStep + 1

        // 4: Calculate the slope
        val slope = rise.toDouble() / run.toDouble()

        // 5: Multiply the slop by our current position in the run and add to the sample, and return
        return (sample + (slope * stepsSinceFirstStep)).roundToInt().toByte()
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
        if (row.instrumentNumber != 0) {
            if (row.instrumentNumber != activeNote.instrumentNumber) {
                activeNote.instrumentNumber = row.instrumentNumber
                activeInstrument = instruments[activeNote.instrumentNumber - 1]

                // In rare circumstances, an instrument number might be provided without an accompanying note - if this is
                // a different instrument than the one currently playing, stop playing it.
                if (row.period == 0) {
                    activeNote.isInstrumentCurrentlyPlaying = false
                }

                // If the instrument is changing, we definitely want to reset the audio data reference, unless effect is 3xx
                if (EffectType.SLIDE_TO_NOTE != row.effect) {
                    resamplingState.audioDataReference = 2.0
                }
            }

            // Change the volume when an instrument is specified, regardless of whether a period is specified
            // This will be overwritten if a change volume effect is specified - do this regardless of whether the instrument
            // is changing or not
            currentVolume = activeInstrument.volume
        }

        if (row.period != 0) {
            // if the new row has a note indicated, we need to change the active period to the new active period and reset the instrument sampling position
            activeNote.specifiedPeriod = row.period

            if (EffectType.SLIDE_TO_NOTE != row.effect) {
                activeNote.actualPeriod = row.period
            }

            activeNote.isInstrumentCurrentlyPlaying = true
            resamplingState.audioDataStep = getSamplesPerSecond(activeNote.actualPeriod) / SAMPLING_RATE

            //reset the audio data reference unless effect is 3xx
            if (EffectType.SLIDE_TO_NOTE != row.effect) {
                resamplingState.audioDataReference = 2.0
            }
        }

        if (EffectType.SLIDE_TO_NOTE == row.effect && (row.effectXValue != 0 || row.effectYValue != 0)) {
            activeNote.slideToNoteSpeed = row.effectXValue * 16 + row.effectYValue
        } else {
            activeNote.effectXValue = row.effectXValue
            activeNote.effectYValue = row.effectYValue
        }

        vibratoState = if (EffectType.VIBRATO == row.effect) {
            getVibratoEffectState(row.effectXValue, row.effectYValue, vibratoState)
        } else {
            VibratoEffectState(isActive = false)
        }

        if (row.effect != activeNote.effect) {
            activeNote.effect = row.effect
        }

        // A change volume effect can take place without a new note effect
        if (EffectType.SET_VOLUME == row.effect) {
            currentVolume = (row.effectXValue * 16 + row.effectYValue).coerceAtMost(64).toByte()
        }

        // Apply instrument offset
        if (EffectType.INSTRUMENT_OFFSET == row.effect) {
            resamplingState.audioDataReference = (activeNote.effectXValue * 4096 + activeNote.effectYValue * 256).toDouble()
        }
    }

    /**
     * Applies effects that take place only once at the start of a row, before any pcm data has been generated for that row
     */
    fun applyStartOfRowEffects() {
        currentVolume = when(activeNote.effect) {
            EffectType.FINE_VOLUME_SLIDE_UP ->
                (currentVolume + activeNote.effectYValue).coerceAtMost(64).toByte()
            EffectType.FINE_VOLUME_SLIDE_DOWN ->
                (currentVolume - activeNote.effectYValue).coerceAtLeast(0).toByte()
            else -> currentVolume
        }
    }

    /**
     * Some effect commands take place for every tick. This function invokes those effects.
     */
    fun applyPerTickEffects() {
        when (activeNote.effect) {
            EffectType.VOLUME_SLIDE ->
                currentVolume = applyVolumeSlideAdjustment(activeNote.effectXValue, activeNote.effectYValue, currentVolume)
            EffectType.SLIDE_TO_NOTE ->
                applySlideToNoteAdjustment()
            EffectType.SLIDE_TO_NOTE_WITH_VOLUME_SLIDE -> {
                currentVolume = applyVolumeSlideAdjustment(activeNote.effectXValue, activeNote.effectYValue, currentVolume)
                applySlideToNoteAdjustment()
            }
            else -> return
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
     * Updates the resampling state - to be called after every time we generate a sample
     */
    private fun updateResamplingState() {
        resamplingState.audioDataReference += resamplingState.audioDataStep

        //If we have exceeded the length of the audio data for an unlooped instrument, we want to stop playing it
        if (!isInstrumentLooped(activeInstrument) && activeNote.isInstrumentCurrentlyPlaying && resamplingState.audioDataReference.roundToInt() >= activeInstrument.audioData?.size!!) {
            activeNote.isInstrumentCurrentlyPlaying = false
            activeNote.actualPeriod = 0
        } else if (isInstrumentLooped(activeInstrument) && activeNote.isInstrumentCurrentlyPlaying && resamplingState.audioDataReference.roundToInt() >= activeInstrument.audioData?.size!!) {
            val referenceRemainder = resamplingState.audioDataReference - floor(resamplingState.audioDataReference)
            resamplingState.audioDataReference = (activeInstrument.repeatOffsetStart * 2).toDouble() + referenceRemainder
        }
    }

    /**
     * Determine the next sample we will use for interpolation. Normally, this will just be 1 position higher than the
     * current sample. If we reach the end of the audio data, we will either return a 0 (for an unlooped instrument) or
     * whatever is at the repeat offset (for a looped instrument)
     */
    private fun getSubsequentSample(instrument: Instrument, currentSamplePosition: Int): Byte {
        if (currentSamplePosition + 1 >= instrument.audioData?.size!!) {
            if (!isInstrumentLooped(instrument)) {
                return 0
            }

            //if the instrument is looped, return whatever value is at the repeat offset start
            return instrument.audioData?.get(instrument.repeatOffsetStart * 2)!!
        }

        return instrument.audioData?.get(currentSamplePosition + 1) ?: 0
    }

    private fun getSamplesPerSecond(activePeriod: Int) =
        PAL_CLOCK_RATE / (activePeriod * 2)

    private fun isInstrumentLooped(instrument: Instrument) =
        instrument.repeatLength > 1

    private fun applyVolumeSlideAdjustment(xValue: Int, yValue: Int, volume: Byte) =
        if (xValue > 0) {
            (xValue + volume).coerceAtMost(64).toByte()
        } else {
            (volume - yValue).coerceAtLeast(0).toByte()
        }

    private fun applySlideToNoteAdjustment() {
        if (activeNote.actualPeriod > activeNote.specifiedPeriod) {
            activeNote.actualPeriod = (activeNote.actualPeriod - activeNote.slideToNoteSpeed).coerceAtLeast(activeNote.specifiedPeriod)
            resamplingState.audioDataStep = getSamplesPerSecond(activeNote.actualPeriod) / SAMPLING_RATE
        } else if (activeNote.actualPeriod < activeNote.specifiedPeriod) {
            activeNote.actualPeriod = (activeNote.actualPeriod + activeNote.slideToNoteSpeed).coerceAtMost(activeNote.specifiedPeriod)
            resamplingState.audioDataStep = getSamplesPerSecond(activeNote.actualPeriod) / SAMPLING_RATE
        }
    }

    private fun getVibratoEffectState(xValue: Int, yValue: Int, previousVibratoState: VibratoEffectState): VibratoEffectState {
        //if the current vibrato effect state is already active, don't reset the samples elapsed counter
        val samplesElapsed = if (previousVibratoState.isActive) previousVibratoState.samplesElapsed else 0

        val cyclesPerRow = if (xValue == 0) previousVibratoState.cyclesPerRow else xValue * ticksPerRow / 64.0
        val depth = if (yValue == 0) previousVibratoState.depth else yValue

        val samplesPerRow = (SAMPLING_RATE / (beatsPerMinute / 60.0)) / 4

        val samplesPerCycle = samplesPerRow * cyclesPerRow.pow(-1)
        val samplesPerCyclePosition = samplesPerCycle / 64

        return VibratoEffectState(cyclesPerRow, depth, samplesPerCyclePosition, samplesPerCycle, samplesElapsed, true)
    }

    data class ResamplingState(
        var audioDataReference: Double = 2.0,
        var audioDataStep: Double = 0.0
    )

    data class ActiveNote(
        var isInstrumentCurrentlyPlaying: Boolean = false,
        var actualPeriod: Int = 0,
        var specifiedPeriod: Int = 0,
        var instrumentNumber: Int = 0,
        var effectNumber: Int = 0,
        var effect: EffectType = EffectType.UNKNOWN_EFFECT,
        var effectXValue: Int = 0,
        var effectYValue: Int = 0,
        var slideToNoteSpeed: Int = 0
    )

    data class VibratoEffectState(
        var cyclesPerRow: Double = 0.0,
        var depth: Int = 0,
        var samplesPerCyclePosition: Double = 0.0,
        var samplesPerCycle: Double = 0.0,
        var samplesElapsed: Int = 0,
        var isActive: Boolean = false
    )
}