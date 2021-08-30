package pcm

import model.ProTrackerModule
import model.Row
import java.nio.ByteBuffer
import kotlin.math.floor

class AudioGenerator(private val module: ProTrackerModule) {
    //these will eventually need to be vars since they can be modified by effects, but they can be vals for now
    private val ticksPerRow = 6
    private val beatsPerMinute = 125
    private val orderList = module.orderList.subList(0, module.numberOfSongPositions.toInt())

    private val songPositionState: SongPositionState = SongPositionState(0, 0, 0, 0, module.orderList[0])
    private val activeNoteState: ActiveNoteState = ActiveNoteState(false, module.patterns[songPositionState.currentPatternNumber].channels[0].rows[0], 0, 0)
    private val resamplingState = ResamplingState(0.0, 0, 2, 0, 0.0)

    companion object {
        private const val SAMPLING_RATE = 44100.0
        private const val PAL_CLOCK_RATE = 7093789.2
    }

    init {
        if (activeNoteState.activeRow.period != 0) {
            activeNoteState.isInstrumentCurrentlyPlaying = true
            activeNoteState.activePeriod = activeNoteState.activeRow.period
            activeNoteState.activeInstrumentNumber = activeNoteState.activeRow.instrumentNumber - 1

            resamplingState.samplesPerSecond = PAL_CLOCK_RATE / (activeNoteState.activePeriod * 2)
            resamplingState.iterationsUntilNextSample = getIterationsUntilNextSample(SAMPLING_RATE, resamplingState.samplesPerSecond, resamplingState.sampleProgressCounter)
        }
    }

    fun songStillActive(): Boolean {
        return songPositionState.currentOrderListPosition < orderList.size
    }

    /**
     * Retrieves the next sample in the song
     */
    fun generateNextSample(): Byte {
        val sample = getNextSample(activeNoteState.activePeriod, module.instruments[activeNoteState.activeInstrumentNumber].audioData!!)
        updateSongPosition()
        return sample
    }

    /**
     * Retrieves the next sample with the given period (note) and audio data
     */
    private fun getNextSample(period: Int, audioData: ByteArray): Byte {
        if (!activeNoteState.isInstrumentCurrentlyPlaying) {
            return 0
        }

        //we need the current sample and the next sample to properly interpolate
        val currentSample = audioData[resamplingState.currentSamplePositionOfInstrument]
        val nextSample = if(resamplingState.currentSamplePositionOfInstrument + 1 == audioData.size) 0 else audioData[resamplingState.currentSamplePositionOfInstrument + 1]

        val rise = (nextSample - currentSample).toDouble()
        val slope = rise / resamplingState.iterationsUntilNextSample

        val actualSample = ((slope * resamplingState.samplesSinceSampleChanged).toInt() + currentSample).toByte()
        updateResamplingPosition(audioData.size, period)

        return actualSample
    }

    private fun getIterationsUntilNextSample(samplingRate: Double, samplesPerSecond: Double, counter: Double): Int {
        val iterationsPerSample = floor(SAMPLING_RATE / samplesPerSecond).toInt()
        val maximumCounterValueForExtraIteration = (samplingRate - (samplesPerSecond * iterationsPerSample))

        return iterationsPerSample + additionalIteration(counter, maximumCounterValueForExtraIteration)
    }

    private fun additionalIteration(counter: Double, maximumCounterValueForExtraIteration: Double): Int =
        if (counter < maximumCounterValueForExtraIteration) 1 else 0

    private fun getSamplesPerTick(): Double {
        val beatsPerSecond = beatsPerMinute.toDouble() / 60.0
        val samplesPerBeat = SAMPLING_RATE / beatsPerSecond
        val samplesPerRow = samplesPerBeat / 4
        return samplesPerRow / ticksPerRow
    }

    private fun updateSongPosition() {
        //Update our progress in the song
        songPositionState.currentSamplePosition++
        if (songPositionState.currentSamplePosition >= getSamplesPerTick()) {
            songPositionState.currentSamplePosition = 0
            songPositionState.currentTickPosition++
        }

        if (songPositionState.currentTickPosition >= ticksPerRow) {
            songPositionState.currentTickPosition = 0
            songPositionState.currentRowPosition++

            //if we are at a new row, we need to identify if a new note command has been given
            if (songPositionState.currentRowPosition < 64) {
                updateRow()
            }
        }

        //Select the next pattern in the order list, if possible
        if (songPositionState.currentRowPosition >= 64) {
            songPositionState.currentOrderListPosition++
            songPositionState.currentRowPosition = 0

            if (songPositionState.currentOrderListPosition < orderList.size) {
                updateRow()
            }
        }
    }

    private fun updateResamplingPosition(sizeOfAudioData: Int, period: Int) {
        resamplingState.sampleProgressCounter += resamplingState.samplesPerSecond
        resamplingState.samplesSinceSampleChanged++
        if (resamplingState.sampleProgressCounter > SAMPLING_RATE) {
            resamplingState.sampleProgressCounter -= SAMPLING_RATE
            resamplingState.currentSamplePositionOfInstrument++
            if (activeNoteState.isInstrumentCurrentlyPlaying && resamplingState.currentSamplePositionOfInstrument >= sizeOfAudioData) {
                activeNoteState.isInstrumentCurrentlyPlaying = false
                activeNoteState.activePeriod = 0
            }

            //recalculate all resampling data here
            resamplingState.samplesPerSecond = PAL_CLOCK_RATE / (period * 2)
            resamplingState.iterationsUntilNextSample = getIterationsUntilNextSample(SAMPLING_RATE, resamplingState.samplesPerSecond, resamplingState.sampleProgressCounter)
            resamplingState.samplesSinceSampleChanged = 0
        }
    }

    private fun updateRow() {
        songPositionState.currentPatternNumber = module.orderList[songPositionState.currentOrderListPosition]
        activeNoteState.activeRow = module.patterns[songPositionState.currentPatternNumber].channels[0].rows[songPositionState.currentRowPosition]
        if (activeNoteState.activeRow.period != 0) {
            activeNoteState.isInstrumentCurrentlyPlaying = true
            activeNoteState.activePeriod = activeNoteState.activeRow.period
            resamplingState.currentSamplePositionOfInstrument = 2
            activeNoteState.activeInstrumentNumber = activeNoteState.activeRow.instrumentNumber - 1
        }
    }

    data class SongPositionState(
        var currentOrderListPosition: Int,
        var currentRowPosition: Int,
        var currentTickPosition: Int,
        var currentSamplePosition: Int,
        var currentPatternNumber: Int
    )

    data class ActiveNoteState(
        var isInstrumentCurrentlyPlaying: Boolean,
        var activeRow: Row,
        var activePeriod: Int, //The period (aka note) can be modified by effects, so it should be tracked separately from the active row
        var activeInstrumentNumber: Int
    )

    data class ResamplingState(
        var samplesPerSecond: Double,
        var iterationsUntilNextSample: Int,
        var currentSamplePositionOfInstrument: Int,
        var samplesSinceSampleChanged: Int,
        var sampleProgressCounter: Double
    )

    fun resample(audioData: ByteArray, period: Int): ByteArray {
        val samplesPerSecond = PAL_CLOCK_RATE / (period * 2)
        val returnBuffer = ByteBuffer.allocate(60000)

        var counter = 0.0

        //the first two bytes of the sample data are used only for looping data, so we should start at the third byte (index=2)
        var audioDataIndex = 2

        var iterationsSinceSampleChanged = 0
        while (audioDataIndex < audioData.size) {
            val totalIterationsUntilNextSample = getIterationsUntilNextSample(SAMPLING_RATE, samplesPerSecond, counter)

            val currentSample = audioData[audioDataIndex]
            val nextSample = if (audioDataIndex + 1 == audioData.size) 0 else audioData[audioDataIndex + 1]
            val rise = nextSample - currentSample
            val slope = rise.toDouble() / totalIterationsUntilNextSample.toDouble()

            val actualSample = ((slope * iterationsSinceSampleChanged).toInt() + currentSample).toByte()
            returnBuffer.put(actualSample)

            counter += samplesPerSecond
            iterationsSinceSampleChanged++
            if (counter > SAMPLING_RATE) {
                counter -= SAMPLING_RATE
                audioDataIndex++
                iterationsSinceSampleChanged = 0
            }
        }

        return returnBuffer.array()
    }
}