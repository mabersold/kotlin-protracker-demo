package pcm

import model.EffectType
import model.ProTrackerModule

/**
 * AudioGenerator - manages audio generation for the entire module
 *
 * Contains a list of channel audio generators, one for each channel
 *
 * Maintains the current position of the module and retrieves and mixes channel audio
 */
class AudioGenerator(private val module: ProTrackerModule, replacementOrderList: List<Int> = listOf(), private val soloChannels: List<Int> = listOf()) {
    //these will eventually need to be vars since they can be modified by effects, but they can be vals for now
    private val ticksPerRow = 6
    private val beatsPerMinute = 125
    private val orderList = replacementOrderList.ifEmpty { module.orderList.subList(0, module.numberOfSongPositions.toInt()) }
    private val samplesPerTick = getSamplesPerTick()

    private val channelAudioGenerators: ArrayList<ChannelAudioGenerator> = ArrayList()

    private val songPositionState: SongPositionState = SongPositionState(0, 0, 0, 0, orderList[0])

    companion object {
        private const val SAMPLING_RATE = 44100.0
    }

    init {
        //Generate channel audio generators for each channel in the module
        module.patterns[songPositionState.currentPatternNumber].channels.forEachIndexed { i, channel ->
            channelAudioGenerators.add(ChannelAudioGenerator(channel.panningPosition))
        }
    }

    fun songStillActive(): Boolean =
        songPositionState.currentOrderListPosition < orderList.size

    /**
     * Retrieves the next sample in the song, mixing the results from each channel audio generator
     */
    fun generateNextSample(): Pair<Byte, Byte> {
        updateRowData()

        var leftSample = 0
        var rightSample = 0

        channelAudioGenerators.forEachIndexed { i, generator ->
            if (soloChannels.isEmpty() || soloChannels.contains(i + 1)) {
                val nextSample = generator.getNextSample()
                leftSample += nextSample.first
                rightSample += nextSample.second
            }
        }

        updateCounters()
        applyPerTickEffects()

        return Pair(
            leftSample.coerceAtMost(Byte.MAX_VALUE.toInt()).coerceAtLeast(Byte.MIN_VALUE.toInt()).toByte(),
            rightSample.coerceAtMost(Byte.MAX_VALUE.toInt()).coerceAtLeast(Byte.MIN_VALUE.toInt()).toByte()
        )
    }

    /**
     * If we are at the start of a new row, update the channel audio generators with new row data
     */
    private fun updateRowData() {
        if (isStartOfNewRow(songPositionState)) {
            if (songPositionState.patternBreakActive) {
                applyPatternBreak(songPositionState)
            }

            updateRow(songPositionState.currentRowPosition)

            channelAudioGenerators.forEach { generator ->
                generator.applyStartOfRowEffects()
            }
        }
    }

    private fun updateCounters() {
        songPositionState.currentSamplePosition++

        //if samplePosition > samples per tick, go to the next tick
        if (songPositionState.currentSamplePosition > samplesPerTick) {
            songPositionState.currentSamplePosition = 0
            songPositionState.currentTickPosition++
        }

        //update row
        if (songPositionState.currentTickPosition >= ticksPerRow) {
            songPositionState.currentTickPosition = 0
            songPositionState.currentRowPosition++
        }

        //Update pattern
        if (songPositionState.currentRowPosition >= 64) {
            songPositionState.currentOrderListPosition++
            songPositionState.currentRowPosition = 0
        }
    }

    private fun applyPerTickEffects() {
        if (isStartOfNewTick(songPositionState)) {
            channelAudioGenerators.forEach { generator ->
                generator.applyPerTickEffects()
            }
        }
    }

    private fun getSamplesPerTick(): Double {
        val beatsPerSecond = beatsPerMinute.toDouble() / 60.0
        val samplesPerBeat = SAMPLING_RATE / beatsPerSecond
        val samplesPerRow = samplesPerBeat / 4
        return samplesPerRow / ticksPerRow
    }

    private fun isStartOfNewTick(songPositionState: SongPositionState): Boolean =
        songStillActive() && songPositionState.currentSamplePosition == 0 && songPositionState.currentTickPosition != 0

    private fun isStartOfNewRow(songPositionState: SongPositionState): Boolean =
        songStillActive() && songPositionState.currentTickPosition == 0 && songPositionState.currentSamplePosition == 0

    /**
     * Updates the channel audio generators with new row information - note, effects, and audio data
     */
    private fun updateRow(rowNumber: Int) {
        songPositionState.currentPatternNumber = orderList[songPositionState.currentOrderListPosition]

        //If the current row has a pattern break, set pattern break data
        if (rowHasPatternBreak(module, songPositionState.currentPatternNumber, rowNumber)) {
            val patternBreakRow = module.patterns[songPositionState.currentPatternNumber].channels.findLast { channel ->
                EffectType.PATTERN_BREAK == channel.rows[rowNumber].effect
            }?.rows?.get(rowNumber)

            songPositionState.patternBreakActive = true
            songPositionState.patternBreakStartingRow = patternBreakRow?.effectXValue!! * 10 + patternBreakRow.effectYValue
        }

        //update the active row in the channel audio generators
        channelAudioGenerators.forEachIndexed { i, generator ->
            val row = module.patterns[songPositionState.currentPatternNumber].channels[i].rows[rowNumber]
            generator.updateActiveRow(row, module.instruments)
        }
    }

    private fun applyPatternBreak(songPositionState: SongPositionState) {
        songPositionState.currentRowPosition = songPositionState.patternBreakStartingRow
        songPositionState.currentOrderListPosition++

        songPositionState.patternBreakActive = false
        songPositionState.patternBreakStartingRow = 0
    }

    private fun rowHasPatternBreak(module: ProTrackerModule, patternNumber: Int, rowNumber: Int): Boolean =
        module.patterns[patternNumber].channels.any { channel ->
            EffectType.PATTERN_BREAK == channel.rows[rowNumber].effect
        }

    data class SongPositionState(
        var currentOrderListPosition: Int,
        var currentRowPosition: Int,
        var currentTickPosition: Int,
        var currentSamplePosition: Int,
        var currentPatternNumber: Int,
        var patternBreakActive: Boolean = false,
        var patternBreakStartingRow: Int = 0
    )
}