package pcm

import model.EffectType
import model.Instrument
import model.PanningPosition
import model.Row
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * ChannelAudioGenerator - manages the state of an individual channel. Maintains information about the current note,
 * instrument, and effects. Processes effects relevant to the channel and applies them to the active note state.
 *
 * Has a resampler instance, which manages retrieval of instrument audio data.
 */
class ChannelAudioGenerator(
    private val panningPosition: PanningPosition,
    var beatsPerMinute: Int = 125,
    var ticksPerRow: Int = 6
) {
    private val resampler = Resampler()
    private val activeNote = ActiveNote()
    private var effectState: EffectState = EffectState()
    private lateinit var activeInstrument: Instrument
    private var currentVolume: Byte = 0

    companion object {
        private const val SAMPLING_RATE = 44100.0
        private val SINE_TABLE = arrayListOf(0, 24, 49, 74, 97, 120, 141, 161, 180, 197, 212, 224, 235, 244, 250, 253, 255, 253, 250, 244, 235, 224, 212, 197, 180, 161, 141, 120, 97, 74, 49, 24, 0, -24, -49, -74, -97, -120, -141, -161, -180, -197, -212, -224, -235, -244, -250, -253, -255, -253, -250, -244, -235, -224, -212, -197, -180, -161, -141, -120, -97, -74, -49, -24)
    }

    /**
     * Retrieves the next sample from the resampler, applies volume and panning adjustment, and returns it.
     *
     * Vibrato effects are applied in here as well, since those need to be handled per-sample rather than per-tick.
     */
    fun getNextSample(): Pair<Byte, Byte> {
        if (!activeNote.isInstrumentCurrentlyPlaying) {
            return Pair(0, 0)
        }

        if (effectState.effectType == EffectType.VIBRATO) {
            val periodAdjustment = getVibratoPeriodAdjustment(effectState.vibrato)
            if (activeNote.actualPeriod != activeNote.specifiedPeriod + periodAdjustment) {
                activeNote.actualPeriod = activeNote.specifiedPeriod + periodAdjustment
                resampler.recalculateStep(activeNote.actualPeriod, SAMPLING_RATE)
            }
        }

        val actualSample = resampler.getInterpolatedSample()
        val volumeAdjustedSample = applyVolume(actualSample, currentVolume)

        if (effectState.vibrato != null) {
            effectState.vibrato!!.samplesElapsed = getUpdatedVibratoSamplesElapsed(effectState.vibrato)
        }

        return getStereoSample(volumeAdjustedSample)
    }

    /**
     * Handles new row data during playback - when a new row is provided, it updates the instrument, period, and effect
     */
    fun setRowData(row: Row, instruments: List<Instrument>) {
        updateInstrument(row, instruments)
        updatePeriod(row)
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
            resampler.audioDataReference = (effectState.xValue * 4096 + effectState.yValue * 256).toDouble()
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
                resampler.recalculateStep(activeNote.actualPeriod, SAMPLING_RATE)
            }
            EffectType.PITCH_SLIDE_DOWN -> {
                val periodAdjustment = effectState.xValue * 16 + effectState.yValue
                activeNote.actualPeriod = (activeNote.actualPeriod + periodAdjustment).coerceAtMost(856)
                activeNote.specifiedPeriod = activeNote.actualPeriod
                resampler.recalculateStep(activeNote.actualPeriod, SAMPLING_RATE)
            }
            else -> return
        }
    }

    private fun updateInstrument(row: Row, instruments: List<Instrument>) {
        if (row.instrumentNumber == 0) {
            return
        }

        if (row.instrumentNumber != activeNote.instrumentNumber) {
            activeNote.instrumentNumber = row.instrumentNumber
            activeInstrument = instruments[activeNote.instrumentNumber - 1]
            resampler.instrument = instruments[activeNote.instrumentNumber - 1]

            // In rare circumstances, an instrument number might be provided without an accompanying note - if this is
            // a different instrument than the one currently playing, stop playing it.
            if (row.period == 0) {
                activeNote.isInstrumentCurrentlyPlaying = false
            }

            // If the instrument is changing, we definitely want to reset the audio data reference, unless effect is 3xx
            if (EffectType.SLIDE_TO_NOTE != row.effect) {
                resampler.audioDataReference = 2.0
            }
        }

        // Specifying instrument number always updates the volume, even if no period is specified
        currentVolume = activeInstrument.volume
    }

    private fun updatePeriod(row: Row) {
        if (row.period == 0) {
            return
        }

        // if the new row has a note indicated, we need to change the active period to the new active period and reset the instrument sampling position
        activeNote.specifiedPeriod = row.period

        //a slide to note effect will not reset the position of the audio data reference or cause us to immediately change the period
        if (!listOf(EffectType.SLIDE_TO_NOTE, EffectType.SLIDE_TO_NOTE_WITH_VOLUME_SLIDE).contains(row.effect)) {
            activeNote.actualPeriod = row.period
            resampler.audioDataReference = 2.0
        }

        activeNote.isInstrumentCurrentlyPlaying = true
        resampler.recalculateStep(activeNote.actualPeriod, SAMPLING_RATE)
    }

    /**
     * Accepts the sample and responds with a volume-adjusted sample. Maximum volume is 64 - at 64, it will simply respond
     * with the sample at the original value that it was already at. For anything below 64, it determines the volume ratio
     * and multiplies the sample by that. A volume value of zero will result in a sample value of zero.
     */
    private fun applyVolume(actualSample: Byte, volume: Byte): Byte {
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

        resampler.recalculateStep(activeNote.actualPeriod, SAMPLING_RATE)
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