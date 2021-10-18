package pcm

import model.Constants.INSTRUMENT_STARTING_REFERENCE
import model.Constants.SAMPLING_RATE
import model.EffectType
import model.Instrument
import model.PanningPosition
import model.Row
import kotlin.math.floor
import kotlin.math.pow

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
    private var actualPitch: Float = 0.0F
    private var specifiedPitch: Float = 0.0F
    private var isInstrumentPlaying: Boolean = false
    private var fineTune: Int = 0

    // Effect state variables
    private var currentVolume: Int = 0
    private var currentEffect: EffectType = EffectType.UNKNOWN_EFFECT
    private var effectXValue: Int = 0
    private var effectYValue: Int = 0

    // State variables for specific effect types
    private var slideToNotePitchShift: Int = 0
    private var vibratoCyclesPerRow: Float = 0.0F
    private var vibratoDepth: Int = 0
    private var vibratoSamplesPerCyclePosition: Float = 0.0F
    private var vibratoSamplesPerCycle: Float = 0.0F
    private var vibratoSamplesElapsed: Int = 0

    companion object {
        // This is used to calculate a vibrato in a sine waveform. While I could do the sine math myself, this is
        // how ProTracker implemented it - and therefore how I will implement it.
        private val SINE_TABLE = arrayListOf(0, 24, 49, 74, 97, 120, 141, 161, 180, 197, 212, 224, 235, 244, 250, 253, 255, 253, 250, 244, 235, 224, 212, 197, 180, 161, 141, 120, 97, 74, 49, 24, 0, -24, -49, -74, -97, -120, -141, -161, -180, -197, -212, -224, -235, -244, -250, -253, -255, -253, -250, -244, -235, -224, -212, -197, -180, -161, -141, -120, -97, -74, -49, -24)
        private const val TRIAMU_RATIO = 1.007246412F
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

        val vibratoPitchAdjustment = getVibratoPitchAdjustment()
        if (vibratoPitchAdjustment != 0 && this.actualPitch != this.specifiedPitch + vibratoPitchAdjustment) {
            this.resampler.recalculateStep(this.actualPitch + vibratoPitchAdjustment, SAMPLING_RATE)
        }

        val actualSample = this.resampler.getInterpolatedSample()

        val volumeAdjustedSample = getSampleWithVolumeApplied(actualSample, this.currentVolume)

        if (listOf(EffectType.VIBRATO, EffectType.VIBRATO_WITH_VOLUME_SLIDE).contains(this.currentEffect)) {
            this.vibratoSamplesElapsed = getUpdatedVibratoSamplesElapsed()
        }

        return getStereoSample(volumeAdjustedSample)
    }

    /**
     * Handles new row data during playback - when a new row is provided, it updates the instrument, pitch, and effect
     */
    fun setRowData(row: Row, instruments: List<Instrument>) {
        updateInstrument(row, instruments)
        updatePitch(row)
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
    fun applyPerTickEffects(tickPosition: Int) {
        if (tickPosition == 0) {
            return
        }

        applyVolumeAdjustmentEffect()
        applyPitchAdjustmentEffect(tickPosition)
    }

    private fun applyVolumeAdjustmentEffect() {
        this.currentVolume = when(this.currentEffect) {
            EffectType.SLIDE_TO_NOTE_WITH_VOLUME_SLIDE -> {
                applyVolumeSlide(this.effectXValue, this.effectYValue, this.currentVolume)
            }
            EffectType.VOLUME_SLIDE, EffectType.VIBRATO_WITH_VOLUME_SLIDE -> applyVolumeSlide(this.effectXValue, this.effectYValue, this.currentVolume)
            else -> this.currentVolume
        }
    }

    private fun applyPitchAdjustmentEffect(tickPosition: Int) {
        when(this.currentEffect) {
            EffectType.SLIDE_TO_NOTE, EffectType.SLIDE_TO_NOTE_WITH_VOLUME_SLIDE -> applySlideToNoteAdjustment()
            EffectType.PITCH_SLIDE_UP, EffectType.PITCH_SLIDE_DOWN -> {
                this.actualPitch = getPitchSlideAdjustment(this.effectXValue, this.effectYValue, this.currentEffect, this.actualPitch)
                this.specifiedPitch = this.actualPitch
                this.resampler.recalculateStep(this.actualPitch, SAMPLING_RATE)
            }
            EffectType.ARPEGGIO -> {
                //get the new pitch based on our tick position
                val numberOfSemitones = when (tickPosition % 3) {
                    1 -> this.effectXValue
                    2 -> this.effectYValue
                    else -> 0
                }
                // calculate the pitch adjustment, factoring in number of semitones - each semitone is the equivalent of 8 finetunes
                this.actualPitch = getFineTuneAdjustedPitch(this.specifiedPitch, 8 * numberOfSemitones)
                this.resampler.recalculateStep(this.actualPitch, SAMPLING_RATE)
            }
            else -> return
        }
    }

    /**
     * Updates the instrument for a new row. If no new instrument is specified, retain current instrument data.
     *
     * If a new instrument number is specified, replace the current instrument with the new instrument.
     *
     * In either case, if any instrument number is specified, set the current volume to the active instrument's volume.
     */
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
            if (row.pitch == 0.0F) {
                this.isInstrumentPlaying = false
            }

            // If the instrument is changing, we definitely want to reset the audio data reference, unless effect is 3xx
            if (EffectType.SLIDE_TO_NOTE != row.effect) {
                this.resampler.audioDataReference = INSTRUMENT_STARTING_REFERENCE
            }
        }

        // Specifying instrument number always updates the volume, even if no pitch is specified
        this.currentVolume = this.activeInstrument.volume
    }

    /**
     * Changes the pitch to the pitch of the given row. Immediately change the pitch to the
     * given row's pitch, unless a slide to note effect is specified.
     */
    private fun updatePitch(row: Row) {
        if (row.pitch == 0.0F) {
            return
        }

        this.fineTune = getFineTuneValue(this.activeInstrument, row)
        val fineTuneAdjustedPitch = getFineTuneAdjustedPitch(row.pitch, this.fineTune)

        this.specifiedPitch = fineTuneAdjustedPitch

        //a slide to note effect will not reset the position of the audio data reference or cause us to immediately change the pitch
        if (!listOf(EffectType.SLIDE_TO_NOTE, EffectType.SLIDE_TO_NOTE_WITH_VOLUME_SLIDE).contains(row.effect)) {
            this.actualPitch = fineTuneAdjustedPitch
            resampler.audioDataReference = INSTRUMENT_STARTING_REFERENCE
        }

        this.isInstrumentPlaying = true
        this.resampler.recalculateStep(this.actualPitch, SAMPLING_RATE)
    }

    /**
     * Accepts the sample and responds with a volume-adjusted sample. Maximum volume is 64 - at 64, it will simply respond
     * with the sample at the original value that it was already at. For anything below 64, it determines the volume ratio
     * and multiplies the sample by that. A volume value of zero will result in a sample value of zero. Note that the
     * actual range of samples values is -1.0F to 1.0F, so the actual volume multiplier will be within that range
     */
    private fun getSampleWithVolumeApplied(actualSample: Float, volume: Int): Float {
        if (volume == 64) {
            return actualSample
        }

        return (actualSample * (volume / 64.0F))
    }

    /**
     * Accepts a sample float and responds with a pair of floats adjusted for stereo panning
     *
     * Protracker panning is very simple: it's either left channel or right channel. All we need to do is respond with the
     * sample in the pair's first field for left panning, or second field for right panning.
     */
    private fun getStereoSample(sample: Float): Pair<Float, Float> =
        if (PanningPosition.LEFT == this.panningPosition) Pair(sample, 0.0F) else Pair(0.0F, sample)

    /**
     * Apply volume slide effect. Volume increase/decrease are the x/y values of the effect. They cannot both be non-
     * zero, but this function assumes that has already been taken care of.
     */
    private fun applyVolumeSlide(volumeIncrease: Int, volumeDecrease: Int, volume: Int) =
        if (volumeIncrease > 0) {
            (volumeIncrease + volume).coerceAtMost(64)
        } else {
            (volume - volumeDecrease).coerceAtLeast(0)
        }

    /**
     * Apply a slide to note effect. If the actual pitch is at the specified pitch, do nothing (we have already reached
     * the desired pitch). Otherwise, either add or subtract the pitch shift (depending on where the actual pitch is
     * relative to the specified pitch), making sure we do not exceed the specified pitch.
     */
    private fun applySlideToNoteAdjustment() {
        if (this.actualPitch == this.specifiedPitch) return

        if (this.actualPitch > this.specifiedPitch) {
            this.actualPitch = (this.actualPitch - this.slideToNotePitchShift).coerceAtLeast(this.specifiedPitch)
        } else if (this.actualPitch < this.specifiedPitch) {
            this.actualPitch = (this.actualPitch + this.slideToNotePitchShift).coerceAtMost(this.specifiedPitch)
        }

        resampler.recalculateStep(this.actualPitch, SAMPLING_RATE)
    }

    /**
     * When a new effect is given, update the effect. In some cases, we want to set some state variables because we may
     * need to "remember" the effect parameters later.
     */
    private fun updateEffect(effectType: EffectType, xValue: Int, yValue: Int) {
        when (effectType) {
            EffectType.SLIDE_TO_NOTE -> {
                this.slideToNotePitchShift = getSlideToNotePitchShift(xValue, yValue)
            }
            EffectType.VIBRATO, EffectType.VIBRATO_WITH_VOLUME_SLIDE -> {
                val continueActiveVibrato = listOf(EffectType.VIBRATO, EffectType.VIBRATO_WITH_VOLUME_SLIDE).contains(effectType)
                updateVibratoState(xValue, yValue, continueActiveVibrato, effectType)
            }
            else -> {
                // When there is no effect, make sure to recalculate to the correct actual pitch so that a vibrato
                // effect does not leave the pitch in a bad state
                this.resampler.recalculateStep(this.actualPitch, SAMPLING_RATE)
            }
        }

        this.currentEffect = effectType
        this.effectXValue = xValue
        this.effectYValue = yValue
    }

    /**
     * Vibrato is tracked separately from other effects because it is "special" - it is not neatly applied at the start
     * of a tick or the start of a row like other effects. Instead, we keep track of the vibrato state continuously as
     * we generate samples. So, when a vibrato effect is given, set the variables we need, and remember the past values
     * if we are meant to continue with a currently active vibrato
     */
    private fun updateVibratoState(xValue: Int, yValue: Int, continueActiveVibrato: Boolean, effectType: EffectType) {
        this.vibratoSamplesElapsed = if (continueActiveVibrato) this.vibratoSamplesElapsed else 0

        // Only set these values for a vibrato effect, vibrato+volume slide parameters are used for volume slide only
        if (effectType == EffectType.VIBRATO) {
            this.vibratoCyclesPerRow = if (xValue == 0) this.vibratoCyclesPerRow else xValue * this.ticksPerRow / 64.0F
            this.vibratoDepth = if (yValue == 0) this.vibratoDepth else yValue
        }

        val samplesPerRow = (SAMPLING_RATE / (beatsPerMinute / 60.0F)) / 4

        this.vibratoSamplesPerCycle = samplesPerRow * this.vibratoCyclesPerRow.pow(-1)
        this.vibratoSamplesPerCyclePosition = this.vibratoSamplesPerCycle / 64
    }

    // When a slide to note effect is given, return the new value, or the old value when the params are 0 and 0
    private fun getSlideToNotePitchShift(xValue: Int, yValue: Int): Int =
        if (xValue == 0 && yValue == 0) this.slideToNotePitchShift else xValue * 16 + yValue

    // If there is an active vibrato effect, calculate by how much the active note's pitch needs to be adjusted
    private fun getVibratoPitchAdjustment(): Int {
        if (!listOf(EffectType.VIBRATO, EffectType.VIBRATO_WITH_VOLUME_SLIDE).contains(this.currentEffect)) {
            return 0
        }

        val vibratoCyclePosition = this.vibratoSamplesElapsed / this.vibratoSamplesPerCyclePosition
        val sineTableValue = SINE_TABLE[floor(vibratoCyclePosition).toInt()]
        return sineTableValue * this.vibratoDepth / 128
    }

    // Recalculate how many samples have elapsed in the vibrato effect. If we have exceeded the number of samples per
    // vibrato cycle, return to zero.
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

    /**
     * Each fine tune value represents 1/8th of a semitone - simply divide the pitch by the triamu ratio raised to
     * the power of the number of finetunes (positive or negative).
     */
    private fun getFineTuneAdjustedPitch(pitch: Float, fineTune: Int): Float =
        if (fineTune == 0) pitch else pitch / TRIAMU_RATIO.pow(fineTune)

    private fun getPitchSlideAdjustment(xValue: Int, yValue: Int, effectType: EffectType, actualPitch: Float): Float {
        val pitchAdjustment = xValue * 16 + yValue
        return when (effectType) {
            EffectType.PITCH_SLIDE_UP -> (actualPitch - pitchAdjustment).coerceAtLeast(113.0F)
            EffectType.PITCH_SLIDE_DOWN -> (actualPitch + pitchAdjustment).coerceAtMost(856.0F)
            else -> actualPitch
        }
    }
}