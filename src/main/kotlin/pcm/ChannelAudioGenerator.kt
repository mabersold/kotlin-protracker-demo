package pcm

import model.Constants.INSTRUMENT_STARTING_REFERENCE
import model.Constants.SAMPLING_RATE
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

    // Instrument state variables
    private var currentInstrumentNumber: Int = 0
    private lateinit var activeInstrument: Instrument

    // Note state variables
    private var actualPeriod: Int = 0
    private var specifiedPeriod: Int = 0
    private var isInstrumentPlaying: Boolean = false
    private var fineTune: Int = 0

    // Effect state variables
    private var currentVolume: Int = 0
    private var currentEffect: EffectType = EffectType.UNKNOWN_EFFECT
    private var effectXValue: Int = 0
    private var effectYValue: Int = 0

    // State variables for specific effect types
    private var slideToNotePeriodShift: Int = 0
    private var vibratoCyclesPerRow: Float = 0.0F
    private var vibratoDepth: Int = 0
    private var vibratoSamplesPerCyclePosition: Float = 0.0F
    private var vibratoSamplesPerCycle: Float = 0.0F
    private var vibratoSamplesElapsed: Int = 0

    companion object {
        private val SINE_TABLE = arrayListOf(0, 24, 49, 74, 97, 120, 141, 161, 180, 197, 212, 224, 235, 244, 250, 253, 255, 253, 250, 244, 235, 224, 212, 197, 180, 161, 141, 120, 97, 74, 49, 24, 0, -24, -49, -74, -97, -120, -141, -161, -180, -197, -212, -224, -235, -244, -250, -253, -255, -253, -250, -244, -235, -224, -212, -197, -180, -161, -141, -120, -97, -74, -49, -24)
    }

    /**
     * Retrieves the next sample from the resampler, applies volume and panning adjustment, and returns it.
     *
     * Vibrato effects are applied in here as well, since those need to be handled per-sample rather than per-tick.
     */
    fun getNextSample(): Pair<Float, Float> {
        if (!this.isInstrumentPlaying) {
            return Pair(0.0F, 0.0F)
        }

        val vibratoPeriodAdjustment = getVibratoPeriodAdjustment()
        if (vibratoPeriodAdjustment != 0 && this.actualPeriod != this.specifiedPeriod + vibratoPeriodAdjustment) {
            this.actualPeriod = this.specifiedPeriod + vibratoPeriodAdjustment
            this.resampler.recalculateStep(this.actualPeriod, SAMPLING_RATE, this.fineTune)
        }

        val actualSample = this.resampler.getInterpolatedSample()

        val volumeAdjustedSample = applyVolume(actualSample, this.currentVolume)

        if (this.currentEffect == EffectType.VIBRATO) {
            this.vibratoSamplesElapsed = getUpdatedVibratoSamplesElapsed()
        }

        return getStereoSample(volumeAdjustedSample)
    }

    /**
     * Handles new row data during playback - when a new row is provided, it updates the instrument, period, and effect
     */
    fun setRowData(row: Row, instruments: List<Instrument>) {
        updateInstrument(row, instruments)
        updatePeriod(row)
        updateEffect(row.effect, row.effectXValue, row.effectYValue)
    }

    /**
     * Applies effects that take place only once at the start of a row, before any pcm data has been generated for that row
     */
    fun applyStartOfRowEffects() {
        this.currentVolume = when(this.currentEffect) {
            EffectType.FINE_VOLUME_SLIDE_UP ->
                (this.currentVolume + this.effectYValue).coerceAtMost(64)
            EffectType.FINE_VOLUME_SLIDE_DOWN ->
                (this.currentVolume - this.effectYValue).coerceAtLeast(0)
            EffectType.SET_VOLUME ->
                (this.effectXValue * 16 + this.effectYValue).coerceAtMost(64)
            else -> this.currentVolume
        }

        if (EffectType.INSTRUMENT_OFFSET == this.currentEffect) {
            this.resampler.audioDataReference = (this.effectXValue * 4096 + this.effectYValue * 256).toFloat()
        }
    }

    /**
     * Applies effects that should be applied once per tick
     */
    fun applyPerTickEffects() {
        when(this.currentEffect) {
            EffectType.SLIDE_TO_NOTE ->
                applySlideToNoteAdjustment()
            EffectType.SLIDE_TO_NOTE_WITH_VOLUME_SLIDE -> {
                applySlideToNoteAdjustment()
                this.currentVolume = applyVolumeSlideAdjustment(this.effectXValue, this.effectYValue, this.currentVolume)
            }
            EffectType.VOLUME_SLIDE ->
                this.currentVolume = applyVolumeSlideAdjustment(this.effectXValue, this.effectYValue, this.currentVolume)
            EffectType.PITCH_SLIDE_UP -> {
                val periodAdjustment = this.effectXValue * 16 + this.effectYValue
                this.actualPeriod = (this.actualPeriod - periodAdjustment).coerceAtLeast(113)
                this.specifiedPeriod = this.actualPeriod
                resampler.recalculateStep(this.actualPeriod, SAMPLING_RATE, this.fineTune)
            }
            EffectType.PITCH_SLIDE_DOWN -> {
                val periodAdjustment = this.effectXValue * 16 + this.effectYValue
                this.actualPeriod = (this.actualPeriod + periodAdjustment).coerceAtMost(856)
                this.specifiedPeriod = this.actualPeriod
                this.resampler.recalculateStep(this.actualPeriod, SAMPLING_RATE, this.fineTune)
            }
            else -> return
        }
    }

    private fun updateInstrument(row: Row, instruments: List<Instrument>) {
        if (row.instrumentNumber == 0) {
            return
        }

        if (row.instrumentNumber != this.currentInstrumentNumber) {
            this.currentInstrumentNumber = row.instrumentNumber
            this.activeInstrument = instruments[this.currentInstrumentNumber - 1]
            this.resampler.instrument = instruments[this.currentInstrumentNumber - 1]

            // In rare circumstances, an instrument number might be provided without an accompanying note - if this is
            // a different instrument than the one currently playing, stop playing it.
            if (row.period == 0) {
                this.isInstrumentPlaying = false
            }

            // If the instrument is changing, we definitely want to reset the audio data reference, unless effect is 3xx
            if (EffectType.SLIDE_TO_NOTE != row.effect) {
                this.resampler.audioDataReference = INSTRUMENT_STARTING_REFERENCE
            }
        }

        // Specifying instrument number always updates the volume, even if no period is specified
        this.currentVolume = this.activeInstrument.volume
    }

    private fun updatePeriod(row: Row) {
        if (row.period == 0) {
            return
        }

        // if the new row has a note indicated, we need to change the active period to the new active period and reset the instrument sampling position
        this.specifiedPeriod = row.period

        //a slide to note effect will not reset the position of the audio data reference or cause us to immediately change the period
        if (!listOf(EffectType.SLIDE_TO_NOTE, EffectType.SLIDE_TO_NOTE_WITH_VOLUME_SLIDE).contains(row.effect)) {
            this.actualPeriod = row.period
            resampler.audioDataReference = INSTRUMENT_STARTING_REFERENCE
        }

        this.fineTune = getFineTuneValue(this.activeInstrument, row)
        this.isInstrumentPlaying = true
        this.resampler.recalculateStep(this.actualPeriod, SAMPLING_RATE, this.fineTune)
    }

    /**
     * Accepts the sample and responds with a volume-adjusted sample. Maximum volume is 64 - at 64, it will simply respond
     * with the sample at the original value that it was already at. For anything below 64, it determines the volume ratio
     * and multiplies the sample by that. A volume value of zero will result in a sample value of zero.
     */
    private fun applyVolume(actualSample: Float, volume: Int): Float {
        if (volume == 64) {
            return actualSample
        }

        return (actualSample * (volume / 64.0F))
    }

    /**
     * Accepts a sample byte and responds with a pair of bytes adjusted for stereo panning
     *
     * Protracker panning is very simple: it's either left channel or right channel. All we need to do is respond with the
     * sample in the pair's first field for left panning, or second field for right panning.
     */
    private fun getStereoSample(sample: Float): Pair<Float, Float> =
        if (PanningPosition.LEFT == this.panningPosition) Pair(sample, 0.0F) else Pair(0.0F, sample)

    private fun applyVolumeSlideAdjustment(xValue: Int, yValue: Int, volume: Int) =
        if (xValue > 0) {
            (xValue + volume).coerceAtMost(64)
        } else {
            (volume - yValue).coerceAtLeast(0)
        }

    private fun applySlideToNoteAdjustment() {
        if (this.actualPeriod == this.specifiedPeriod) return

        val periodShift = this.slideToNotePeriodShift
        if (this.actualPeriod > this.specifiedPeriod) {
            this.actualPeriod = (this.actualPeriod - periodShift).coerceAtLeast(this.specifiedPeriod)
        } else if (this.actualPeriod < this.specifiedPeriod) {
            this.actualPeriod = (this.actualPeriod + periodShift).coerceAtMost(this.specifiedPeriod)
        }

        resampler.recalculateStep(this.actualPeriod, SAMPLING_RATE, this.fineTune)
    }

    private fun updateEffect(effectType: EffectType, xValue: Int, yValue: Int) {
        when (effectType) {
            EffectType.SLIDE_TO_NOTE -> {
                this.slideToNotePeriodShift = getSlideToNotePeriodShift(xValue, yValue)
            }
            EffectType.VIBRATO -> {
                val continueActiveVibrato = this.currentEffect == effectType
                updateVibratoState(xValue, yValue, continueActiveVibrato)
            }
            else -> {}
        }

        this.currentEffect = effectType
        this.effectXValue = xValue
        this.effectYValue = yValue
    }

    private fun updateVibratoState(xValue: Int, yValue: Int, continueActiveVibrato: Boolean) {
        this.vibratoSamplesElapsed = if (continueActiveVibrato) this.vibratoSamplesElapsed else 0

        this.vibratoCyclesPerRow = if (xValue == 0) this.vibratoCyclesPerRow else xValue * this.ticksPerRow / 64.0F
        this.vibratoDepth = if (yValue == 0) this.vibratoDepth else yValue

        val samplesPerRow = (SAMPLING_RATE / (beatsPerMinute / 60.0F)) / 4

        this.vibratoSamplesPerCycle = samplesPerRow * this.vibratoCyclesPerRow.pow(-1)
        this.vibratoSamplesPerCyclePosition = this.vibratoSamplesPerCycle / 64
    }

    private fun getSlideToNotePeriodShift(xValue: Int, yValue: Int): Int =
        if (xValue == 0 && yValue == 0) this.slideToNotePeriodShift else xValue * 16 + yValue

    private fun getVibratoPeriodAdjustment(): Int {
        if (this.currentEffect != EffectType.VIBRATO) {
            return 0
        }

        val vibratoCyclePosition = this.vibratoSamplesElapsed / this.vibratoSamplesPerCyclePosition
        val sineTableValue = SINE_TABLE[floor(vibratoCyclePosition).toInt()]
        return sineTableValue * this.vibratoDepth / 128
    }

    private fun getUpdatedVibratoSamplesElapsed(): Int {
        return if (this.vibratoSamplesElapsed + 1 >= this.vibratoSamplesPerCycle)
            0
        else
            this.vibratoSamplesElapsed + 1
    }

    // If a row has a fine tune value effect, return the value from that effect, otherwise return the instrument's default fine tune value
    private fun getFineTuneValue(instrument: Instrument, row: Row): Int {
        if (EffectType.SET_FINE_TUNE == row.effect) {
            return if (row.effectYValue >= 8) {
                row.effectYValue - 16
            } else {
                row.effectYValue
            }
        }

        return instrument.fineTune
    }
}