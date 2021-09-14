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
    private var effectState: EffectState = EffectState()
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

        if (effectState.effectType == EffectType.VIBRATO) {
            val periodAdjustment = getVibratoPeriodAdjustment(effectState.vibrato)
            if (activeNote.actualPeriod != activeNote.specifiedPeriod + periodAdjustment) {
                activeNote.actualPeriod = activeNote.specifiedPeriod + periodAdjustment
                resamplingState.audioDataStep = getSamplesPerSecond(activeNote.actualPeriod) / SAMPLING_RATE
            }
        }

        val actualSample = getInterpolatedSample(resamplingState.audioDataReference, resamplingState.audioDataStep)
        val volumeAdjustedSample = adjustForVolume(actualSample, currentVolume)

        updateResamplingState()

        if (effectState.vibrato != null) {
            effectState.vibrato!!.samplesElapsed = getUpdatedVibratoSamplesElapsed(effectState.vibrato)
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

        effectState = getEffectState(row.effect, row.effectXValue, row.effectYValue, effectState)
    }

    /**
     * Applies effects that take place only once at the start of a row, before any pcm data has been generated for that row
     */
    fun applyStartOfRowEffects() {
        currentVolume = when(effectState.effectType) {
            EffectType.FINE_VOLUME_SLIDE_UP ->
                (currentVolume + effectState.yValue).coerceAtMost(64).toByte()
            EffectType.FINE_VOLUME_SLIDE_DOWN ->
                (currentVolume - effectState.yValue).coerceAtLeast(0).toByte()
            EffectType.SET_VOLUME ->
                (effectState.xValue * 16 + effectState.yValue).coerceAtMost(64).toByte()
            else -> currentVolume
        }

        if (EffectType.INSTRUMENT_OFFSET == effectState.effectType) {
            resamplingState.audioDataReference = (effectState.xValue * 4096 + effectState.yValue * 256).toDouble()
        }
    }

    /**
     * Applies effects that should be applied once per tick
     */
    fun applyPerTickEffects() {
        when(effectState.effectType) {
            EffectType.SLIDE_TO_NOTE ->
                applySlideToNoteAdjustment(effectState.slideToNote)
            EffectType.SLIDE_TO_NOTE_WITH_VOLUME_SLIDE -> {
                applySlideToNoteAdjustment(effectState.slideToNote)
                currentVolume = applyVolumeSlideAdjustment(effectState.xValue, effectState.yValue, currentVolume)
            }
            EffectType.VOLUME_SLIDE ->
                currentVolume = applyVolumeSlideAdjustment(effectState.xValue, effectState.yValue, currentVolume)
            EffectType.PITCH_SLIDE_UP -> {
                val periodAdjustment = effectState.xValue * 16 + effectState.yValue
                activeNote.actualPeriod = (activeNote.actualPeriod - periodAdjustment).coerceAtLeast(113)
                activeNote.specifiedPeriod = activeNote.actualPeriod
                resamplingState.audioDataStep = getSamplesPerSecond(activeNote.actualPeriod) / SAMPLING_RATE
            }
            EffectType.PITCH_SLIDE_DOWN -> {
                val periodAdjustment = effectState.xValue * 16 + effectState.yValue
                activeNote.actualPeriod = (activeNote.actualPeriod + periodAdjustment).coerceAtMost(856)
                activeNote.specifiedPeriod = activeNote.actualPeriod
                resamplingState.audioDataStep = getSamplesPerSecond(activeNote.actualPeriod) / SAMPLING_RATE
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

    private fun applySlideToNoteAdjustment(slideToNote: SlideToNoteState?) {
        if (activeNote.actualPeriod == activeNote.specifiedPeriod) return

        val periodShift = slideToNote?.periodShift ?: 0
        if (activeNote.actualPeriod > activeNote.specifiedPeriod) {
            activeNote.actualPeriod = (activeNote.actualPeriod - periodShift).coerceAtLeast(activeNote.specifiedPeriod)
        } else if (activeNote.actualPeriod < activeNote.specifiedPeriod) {
            activeNote.actualPeriod = (activeNote.actualPeriod + periodShift).coerceAtMost(activeNote.specifiedPeriod)
        }

        resamplingState.audioDataStep = getSamplesPerSecond(activeNote.actualPeriod) / SAMPLING_RATE
    }

    private fun getEffectState(effectType: EffectType, xValue: Int, yValue: Int, previousEffectState: EffectState): EffectState {
        val effectState = EffectState()
        effectState.vibrato = previousEffectState.vibrato
        effectState.slideToNote = previousEffectState.slideToNote
        effectState.effectType = effectType
        effectState.xValue = xValue
        effectState.yValue = yValue

        when (effectType) {
            EffectType.SLIDE_TO_NOTE -> {
                effectState.slideToNote = getSlideToNoteEffectState(xValue, yValue, previousEffectState.slideToNote)
            }
            EffectType.VIBRATO -> {
                effectState.vibrato = getVibratoEffectState(xValue, yValue, previousEffectState.vibrato)
            }
            else -> {}
        }

        return effectState
    }

    private fun getVibratoEffectState(xValue: Int, yValue: Int, previousVibratoState: VibratoState?): VibratoState {
        val samplesElapsed = previousVibratoState?.samplesElapsed ?: 0

        val cyclesPerRow = if (xValue == 0) previousVibratoState?.cyclesPerRow!! else xValue * ticksPerRow / 64.0
        val depth = if (yValue == 0) previousVibratoState?.depth!! else yValue

        val samplesPerRow = (SAMPLING_RATE / (beatsPerMinute / 60.0)) / 4

        val samplesPerCycle = samplesPerRow * cyclesPerRow.pow(-1)
        val samplesPerCyclePosition = samplesPerCycle / 64

        return VibratoState(cyclesPerRow, depth, samplesPerCyclePosition, samplesPerCycle, samplesElapsed)
    }

    private fun getSlideToNoteEffectState(xValue: Int, yValue: Int, previousSlideToNoteState: SlideToNoteState?): SlideToNoteState {
        val periodShift = if (xValue == 0 && yValue == 0 && previousSlideToNoteState != null) {
            previousSlideToNoteState.periodShift
        } else {
            xValue * 16 + yValue
        }

        return SlideToNoteState(periodShift)
    }

    private fun getVibratoPeriodAdjustment(vibratoState: VibratoState?): Int {
        if (vibratoState == null) {
            return 0
        }

        val vibratoCyclePosition = vibratoState.samplesElapsed / vibratoState.samplesPerCyclePosition
        val sineTableValue = SINE_TABLE[floor(vibratoCyclePosition).toInt()]
        return sineTableValue * vibratoState.depth / 128
    }

    private fun getUpdatedVibratoSamplesElapsed(previousVibratoState: VibratoState?): Int {
        if (previousVibratoState == null) {
            return 0
        }

        return if (previousVibratoState.samplesElapsed + 1 >= previousVibratoState.samplesPerCycle)
            0
        else
            previousVibratoState.samplesElapsed + 1
    }

    data class ResamplingState(
        var audioDataReference: Double = 2.0,
        var audioDataStep: Double = 0.0
    )

    data class ActiveNote(
        var isInstrumentCurrentlyPlaying: Boolean = false,
        var actualPeriod: Int = 0,
        var specifiedPeriod: Int = 0,
        var instrumentNumber: Int = 0
    )

    data class EffectState(
        var effectType: EffectType = EffectType.UNKNOWN_EFFECT,
        var xValue: Int = 0,
        var yValue: Int = 0,
        var vibrato: VibratoState? = null,
        var slideToNote: SlideToNoteState? = null
    )

    data class SlideToNoteState(
        var periodShift: Int = 0
    )

    data class VibratoState(
        var cyclesPerRow: Double = 0.0,
        var depth: Int = 0,
        var samplesPerCyclePosition: Double = 0.0,
        var samplesPerCycle: Double = 0.0,
        var samplesElapsed: Int = 0
    )
}