package pcm

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
    private var nextRowPosition = 0
    private var nextRowPattern = this.currentlyPlayingPatternNumber

    companion object {
        private const val SAMPLING_RATE = 44100.0
    }

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
     * Retrieves the next sample in the song, mixing the results from each channel audio generator
     */
    fun generateNextSample(): Pair<Byte, Byte> {
        recalculateSongPosition()

        if (!songStillActive()) {
            return Pair(0, 0)
        }

        applyNewRowData()
        applyPerTickEffects()

        var leftSample = 0
        var rightSample = 0

        this.channelAudioGenerators.forEachIndexed { i, generator ->
            if (currentChannelIsPlaying(i)) {
                val nextSample = generator.getNextSample()
                leftSample += nextSample.first
                rightSample += nextSample.second
            }
        }

        this.samplePosition++

        return Pair(
            leftSample.coerceAtMost(Byte.MAX_VALUE.toInt()).coerceAtLeast(Byte.MIN_VALUE.toInt()).toByte(),
            rightSample.coerceAtMost(Byte.MAX_VALUE.toInt()).coerceAtLeast(Byte.MIN_VALUE.toInt()).toByte()
        )
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
     * This function is how we keep track of our position in the song. The samplePosition counter is increased every
     * time we generate a new sample. In this function, if we have exceeded samples per tick, increase the tick number.
     * If we have exceeded the number of ticks per row, advance to the next row and calculate what the new next row
     * will be.
     */
    private fun recalculateSongPosition() {
        // Update tick position, if needed
        if (this.samplePosition > this.samplesPerTick) {
            this.samplePosition = 0
            this.tickPosition++
        }

        // Update row position, order list position, and current pattern number, if needed
        if (this.tickPosition >= this.ticksPerRow) {
            this.tickPosition = 0
            this.rowPosition = this.nextRowPosition
            this.currentlyPlayingPatternNumber = this.nextRowPattern
            determineNextRow()
        }
    }

    /**
     * This function determines what our next row will be. If we are at the last row of the song, set to -1. If we are
     * at the last row of the pattern but not the last row of the song, the next row will either be the first row of the
     * next pattern in the order list, or whatever the pattern break specifies (if the effect is present in the row). In
     * all other cases, the next row position is just the current row position + 1.
     */
    private fun determineNextRow() {
        if (!songStillActive()) {
            return
        }

        val isLastRowOfPattern = this.rowPosition == 63 || rowHasGlobalEffect(EffectType.PATTERN_BREAK, this.module, this.currentlyPlayingPatternNumber, this.rowPosition)
        val isLastRowOfSong = isLastRowOfPattern && (this.orderListPosition + 1 >= this.orderList.size)

        if (isLastRowOfSong) {
            this.nextRowPosition = -1
            this.nextRowPattern = -1
        } else if (isLastRowOfPattern) {
            val patternBreakRow = getRowWithGlobalEffect(this.rowPosition, EffectType.PATTERN_BREAK, this.currentlyPlayingPatternNumber, this.module)

            this.nextRowPosition = (((patternBreakRow?.effectXValue ?: (0 * 10)) + (patternBreakRow?.effectYValue ?: 0))).coerceAtMost(63)
            this.nextRowPattern = this.orderList[this.orderListPosition + 1]
            this.orderListPosition++
        } else {
            this.nextRowPosition = this.rowPosition + 1
        }
    }

    private fun applyPerTickEffects() {
        if (!isStartOfNewTick()) {
            return
        }

        this.channelAudioGenerators.forEachIndexed { i, generator ->
            if (currentChannelIsPlaying(i)) {
                generator.applyPerTickEffects()
            }
        }
    }

    private fun getSamplesPerTick(): Double {
        val beatsPerSecond = this.beatsPerMinute.toDouble() / 60.0
        val samplesPerBeat = SAMPLING_RATE / beatsPerSecond
        val samplesPerRow = samplesPerBeat / 4
        return samplesPerRow / this.ticksPerRow
    }

    private fun isStartOfNewTick(): Boolean =
        songStillActive() && this.samplePosition == 0 && this.tickPosition != 0

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

    private fun rowHasGlobalEffect(effect: EffectType, module: ProTrackerModule, patternNumber: Int, rowNumber: Int): Boolean =
        module.patterns[patternNumber].channels.any { channel ->
            effect == channel.rows[rowNumber].effect
        }

    private fun getRowWithGlobalEffect(rowNumber: Int, effect: EffectType, patternNumber: Int, module: ProTrackerModule): Row? =
        module.patterns[patternNumber].channels.findLast { channel ->
            effect == channel.rows[rowNumber].effect
        }?.rows?.get(rowNumber)

    private fun currentChannelIsPlaying(channelNumber: Int): Boolean =
        this.soloChannels.isEmpty() || this.soloChannels.contains(channelNumber)
}