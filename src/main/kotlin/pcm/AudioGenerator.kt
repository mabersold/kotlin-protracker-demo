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
    private val orderList = replacementOrderList.ifEmpty { module.orderList.subList(0, module.numberOfSongPositions.toInt()) }
    private val samplesPerTick = getSamplesPerTick()
    private val globalEffects = hashMapOf<EffectType, Int>()

    private val channelAudioGenerators: ArrayList<ChannelAudioGenerator> = ArrayList()

    private val songPositionState: SongPositionState = SongPositionState(0, 0, 0, 0, orderList[0])

    companion object {
        private const val SAMPLING_RATE = 44100.0
    }

    init {
        //Generate channel audio generators for each channel in the module
        module.patterns[songPositionState.currentPatternNumber].channels.forEach { channel ->
            val channelAudioGenerator = ChannelAudioGenerator(channel.panningPosition)
            channelAudioGenerator.beatsPerMinute = this.beatsPerMinute
            channelAudioGenerator.ticksPerRow = this.ticksPerRow
            channelAudioGenerators.add(channelAudioGenerator)
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
            if (currentChannelIsPlaying(i)) {
                val nextSample = generator.getNextSample()
                leftSample += nextSample.first
                rightSample += nextSample.second
            }
        }

        updateSongPosition()
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
        if (!isStartOfNewRow(songPositionState)) {
            return
        }

        if (globalEffects[EffectType.PATTERN_BREAK] != null) {
            // apply the pattern break
            songPositionState.currentRowPosition = globalEffects[EffectType.PATTERN_BREAK]!!
            songPositionState.currentOrderListPosition++

            // Remove the pattern break from the global effects
            globalEffects.remove(EffectType.PATTERN_BREAK)
        }

        updateRow(songPositionState.currentRowPosition)

        channelAudioGenerators.forEachIndexed { i, generator ->
            if (currentChannelIsPlaying(i)) {
                generator.applyStartOfRowEffects()
            }
        }
    }

    private fun updateSongPosition() {
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
        if (!isStartOfNewTick(songPositionState)) {
            return
        }

        channelAudioGenerators.forEachIndexed { i, generator ->
            if (currentChannelIsPlaying(i)) {
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
        if (rowHasGlobalEffect(EffectType.PATTERN_BREAK, module, songPositionState.currentPatternNumber, rowNumber)) {
            val patternBreakRow = getRowWithGlobalEffect(rowNumber, EffectType.PATTERN_BREAK, songPositionState.currentPatternNumber, module)
            globalEffects[patternBreakRow?.effect!!] = (patternBreakRow.effectXValue * 10 + patternBreakRow.effectYValue)
        }

        if (rowHasGlobalEffect(EffectType.CHANGE_SPEED, module, songPositionState.currentPatternNumber, rowNumber)) {
            applySpeedChange(rowNumber)
        }

        //update the active row in the channel audio generators
        channelAudioGenerators.forEachIndexed { i, generator ->
            if (currentChannelIsPlaying(i))  {
                val row = module.patterns[songPositionState.currentPatternNumber].channels[i].rows[rowNumber]
                generator.setNextRow(row, module.instruments)
            }
        }
    }

    private fun applySpeedChange(rowNumber: Int) {
        val speedChangeRow = getRowWithGlobalEffect(rowNumber, EffectType.CHANGE_SPEED, songPositionState.currentPatternNumber, module)

        val speedChange = speedChangeRow?.effectXValue!! * 16 + speedChangeRow.effectYValue
        if (speedChange < 32) {
            this.ticksPerRow = speedChange
            channelAudioGenerators.forEach { generator ->
                generator.ticksPerRow = speedChange
            }
        } else {
            this.beatsPerMinute = speedChange
            channelAudioGenerators.forEach { generator ->
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
        soloChannels.isEmpty() || soloChannels.contains(channelNumber)

    data class SongPositionState(
        var currentOrderListPosition: Int,
        var currentRowPosition: Int,
        var currentTickPosition: Int,
        var currentSamplePosition: Int,
        var currentPatternNumber: Int,
    )
}