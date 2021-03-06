package pcm

import model.Constants.SAMPLING_RATE
import model.EffectType
import model.ProTrackerModule
import model.Row

/**
 * AudioGenerator - manages audio generation for the entire module
 *
 * Contains a list of channel audio generators, one for each channel
 *
 * Maintains the current position of the module and retrieves and mixes channel audio
 */
class AudioGenerator(private val module: ProTrackerModule, replacementOrderList: List<Int> = listOf(), private val soloChannels: List<Int> = listOf()) {
    private var ticksPerRow = 6
    private var beatsPerMinute = 125
    private val orderList = replacementOrderList.ifEmpty { this.module.orderList.subList(0, this.module.numberOfSongPositions.toInt()) }
    private val samplesPerTick = getSamplesPerTick()

    private val channelAudioGenerators: ArrayList<ChannelAudioGenerator> = ArrayList()

    private var orderListPosition = 0
    private var samplePosition = 0
    private var tickPosition = 0
    private var rowPosition = 0
    private var currentlyPlayingPatternNumber = this.orderList[this.orderListPosition]
    private var nextRowNumber = 0
    private var nextRowPatternNumber = this.currentlyPlayingPatternNumber
    private var nextRowOrderListPosition = 0

    init {
        //Generate channel audio generators for each channel in the module
        this.module.patterns[this.currentlyPlayingPatternNumber].channels.forEach { channel ->
            this.channelAudioGenerators.add(ChannelAudioGenerator(channel.panningPosition, this.beatsPerMinute, this.ticksPerRow))
        }

        determineNextRow()
    }

    fun songStillActive(): Boolean =
        this.rowPosition != -1

    /**
     * Retrieves the next set of samples in the song, mixing the results from each channel audio generator
     */
    fun generateNextSamples(numberOfSamples: Int = 0): List<Pair<Short, Short>> {
        if (!songStillActive()) {
            return listOf(Pair(0, 0))
        }

        // if numberOfSamples is zero, generate the number of samples remaining for the current tick
        val samplesToGenerate = if (numberOfSamples == 0) (this.samplesPerTick - this.samplePosition).toInt() else numberOfSamples
        val samplesToReturn = arrayListOf<Pair<Short, Short>>()

        for (i in 0..samplesToGenerate) {
            recalculateSongPosition()
            applyNewRowData()
            applyPerTickEffects()

            var leftSample = 0.0F
            var rightSample = 0.0F

            this.channelAudioGenerators.forEachIndexed { j, generator ->
                if (currentChannelIsPlaying(j)) {
                    val nextSample = generator.getNextSample()
                    leftSample += nextSample.first
                    rightSample += nextSample.second
                }
            }

            samplesToReturn.add(
                Pair(
                    convertTo16Bit(leftSample),
                    convertTo16Bit(rightSample)
                )
            )

            this.samplePosition++
        }

        return samplesToReturn
    }

    /**
     * If we are at the start of a new row, update the channel audio generators with new row data, and apply start of
     * row effects, if present
     */
    private fun applyNewRowData() {
        if (!isStartOfNewRow()) {
            return
        }

        sendRowDataToChannels(this.rowPosition)

        this.channelAudioGenerators.forEachIndexed { i, generator ->
            if (currentChannelIsPlaying(i)) {
                generator.applyStartOfRowEffects()
            }
        }
    }

    /**
     * Keep track of our current song position. Called once per sample generated. The samplePosition counter is increased every
     * time we generate a new sample. In this function, if we have exceeded samples per tick, increase the tick number.
     * If we have exceeded the number of ticks per row, advance to the next row and calculate what the new next row
     * will be.
     */
    private fun recalculateSongPosition() {
        // Update tick position, if needed
        if (this.samplePosition >= this.samplesPerTick) {
            this.samplePosition = 0
            this.tickPosition++
        }

        // Update row position, order list position, and current pattern number, if needed
        if (this.tickPosition >= this.ticksPerRow) {
            this.tickPosition = 0
            this.rowPosition = this.nextRowNumber
            this.currentlyPlayingPatternNumber = this.nextRowPatternNumber
            this.orderListPosition = this.nextRowOrderListPosition
            determineNextRow()
        }
    }

    /**
     * Calculate the next row of the song. If we are at the last row of the song, set to -1. If we are at the last row of
     * the pattern but not the last row of the song, the next row will either be the first row of the next pattern in the
     * order list, or whatever the pattern break specifies (if the effect is present in the row). In all other cases, the
     * next row position is just the current row position + 1.
     */
    private fun determineNextRow() {
        if (!songStillActive()) {
            return
        }

        val isLastRowOfPattern = this.rowPosition == 63 || rowHasGlobalEffect(EffectType.PATTERN_BREAK, this.module, this.currentlyPlayingPatternNumber, this.rowPosition) || rowHasGlobalEffect(EffectType.POSITION_JUMP, this.module, this.currentlyPlayingPatternNumber, this.rowPosition)
        val isLastRowOfSong = isLastRowOfPattern && (this.orderListPosition + 1 >= this.orderList.size)

        if (isLastRowOfSong) {
            this.nextRowNumber = -1
            this.nextRowPatternNumber = -1
        } else if (isLastRowOfPattern) {
            val patternBreakRow = getRowWithGlobalEffect(this.rowPosition, EffectType.PATTERN_BREAK, this.currentlyPlayingPatternNumber, this.module)
            val positionJumpRow = getRowWithGlobalEffect(this.rowPosition, EffectType.POSITION_JUMP, this.currentlyPlayingPatternNumber, this.module)

            this.nextRowNumber = (((patternBreakRow?.effectXValue ?: 0) * 10) + (patternBreakRow?.effectYValue ?: 0)).coerceAtMost(63)
            this.nextRowOrderListPosition = getNextOrderListPosition(positionJumpRow, this.orderListPosition)
            this.nextRowPatternNumber = this.orderList[this.nextRowOrderListPosition]
        } else {
            this.nextRowNumber = this.rowPosition + 1
        }
    }

    /**
     * Apply per tick effects. Most of the work is done by the channel audio generators.
     */
    private fun applyPerTickEffects() {
        if (!isStartOfNewTick()) {
            return
        }

        this.channelAudioGenerators.forEachIndexed { i, generator ->
            if (currentChannelIsPlaying(i)) {
                generator.applyPerTickEffects(this.tickPosition)
            }
        }
    }

    /**
     * Calculate how many samples should be generated per tick
     */
    private fun getSamplesPerTick(): Double {
        val beatsPerSecond = this.beatsPerMinute.toDouble() / 60.0
        val samplesPerBeat = SAMPLING_RATE / beatsPerSecond
        val samplesPerRow = samplesPerBeat / 4
        return samplesPerRow / this.ticksPerRow
    }

    /**
     * Determines whether the current position is the start of a new tick
     */
    private fun isStartOfNewTick(): Boolean =
        songStillActive() && this.samplePosition == 0

    /**
     * Determines whether the current position is the start of a new row.
     */
    private fun isStartOfNewRow(): Boolean =
        songStillActive() && this.tickPosition == 0 && this.samplePosition == 0

    /**
     * Updates the channel audio generators with new row information - note, effects, and audio data
     */
    private fun sendRowDataToChannels(rowNumber: Int) {
        // Channel audio generators need to be aware of speed changes for vibrato calculating purposes
        if (rowHasGlobalEffect(EffectType.CHANGE_SPEED, this.module, this.currentlyPlayingPatternNumber, rowNumber)) {
            applySpeedChange(rowNumber)
        }

        this.channelAudioGenerators.forEachIndexed { i, generator ->
            if (currentChannelIsPlaying(i))  {
                val row = this.module.patterns[this.currentlyPlayingPatternNumber].channels[i].rows[rowNumber]
                generator.setRowData(row, this.module.instruments)
            }
        }
    }

    /**
     * When a speed change effect is specified, change the speed of the song and calculate new ticksPerRow and beatsPerMinute
     * Channel audio generators must be aware of the speed change since that is used in vibrato effects
     */
    private fun applySpeedChange(rowNumber: Int) {
        val speedChangeRow = getRowWithGlobalEffect(rowNumber, EffectType.CHANGE_SPEED, this.currentlyPlayingPatternNumber, this.module) ?: return

        val speedChange = speedChangeRow.effectXValue * 16 + speedChangeRow.effectYValue
        if (speedChange < 32) {
            this.ticksPerRow = speedChange
            this.channelAudioGenerators.forEach { generator ->
                generator.ticksPerRow = speedChange
            }
        } else {
            this.beatsPerMinute = speedChange
            this.channelAudioGenerators.forEach { generator ->
                generator.beatsPerMinute = speedChange
            }
        }
    }

    /**
     * Returns true if the row has the specified global effect
     */
    private fun rowHasGlobalEffect(effect: EffectType, module: ProTrackerModule, patternNumber: Int, rowNumber: Int): Boolean =
        module.patterns[patternNumber].channels.any { channel ->
            effect == channel.rows[rowNumber].effect
        }

    /**
     * Returns a row at the specified song position that has a specified effect. If more than one row has the specified
     * effect, it will return the last one in the list. If no row has the effect, it will return null.
     */
    private fun getRowWithGlobalEffect(rowNumber: Int, effect: EffectType, patternNumber: Int, module: ProTrackerModule): Row? =
        module.patterns[patternNumber].channels.findLast { channel ->
            effect == channel.rows[rowNumber].effect
        }?.rows?.get(rowNumber)

    /**
     * Determines whether a specified channel number should be playing. If no channels are soloed, returns true.
     */
    private fun currentChannelIsPlaying(channelNumber: Int): Boolean =
        this.soloChannels.isEmpty() || this.soloChannels.contains(channelNumber)

    /**
     * Converts the incoming sample, as a float, into a 16-bit short. This is the final step of our audio generation -
     * the calling function receives a collection of 16-bit values for the PCM audio data, which is then sent to the
     * output device.
     */
    private fun convertTo16Bit(sample: Float): Short =
        (sample * 32767).toInt().coerceAtMost(32767).coerceAtLeast(-32768).toShort()

    /**
     * Determines the next position in the order list. If there is a non-null row with a position jump effect, the next
     * order list position is determined by the position jump effect in that row. Otherwise, it's just one higher than
     * the current order list position
     */
    private fun getNextOrderListPosition(rowWithPositionJump: Row?, currentOrderListPosition: Int): Int =
        if (rowWithPositionJump != null)
            (rowWithPositionJump.effectXValue * 16 + rowWithPositionJump.effectYValue).coerceAtMost(127).coerceAtLeast(0)
        else
            currentOrderListPosition + 1
}