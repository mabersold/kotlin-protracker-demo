package pcm

import model.ProTrackerModule
import model.Row
import java.nio.ByteBuffer
import kotlin.math.floor

class AudioGenerator(private val module: ProTrackerModule) {
    //these will eventually need to be vars since they can be modified by effects, but they can be vals for now
    private val ticksPerRow = 6
    private val beatsPerMinute = 125

    //Song progress state data
    private var currentTickPosition = 0
    private var currentRowPosition = 0
    private var currentSamplePosition = 0

    //Active note state data
    private var isInstrumentCurrentlyPlaying = false
    private var activeRow: Row
    //The period (aka note) can be modified by effects, so it should be tracked separately from the active row
    private var activePeriod = 0
    //This starts at 2 because the first two bytes of audio data are looping info
    private var currentSamplePositionOfInstrument = 2
    private var samplesSinceSampleChanged = 0
    private var sampleProgressCounter = 0.0
    private var activeSampleNumber = 0

    //Resampling state data
    private var iterationsUntilNextSample = 0
    private var samplesPerSecond = 0.0

    companion object {
        private const val SAMPLING_RATE = 44100.0
        private const val PAL_CLOCK_RATE = 7093789.2
    }

    init {
        //get the first note - set isInstrumentCurrentlyPlaying and activePeriod
        activeRow = module.patterns[0].channels[0].rows[0]
        if (activeRow.period != 0) {
            isInstrumentCurrentlyPlaying = true
            activePeriod = activeRow.period

            samplesPerSecond = PAL_CLOCK_RATE / (activePeriod * 2)
            iterationsUntilNextSample = getIterationsUntilNextSample(SAMPLING_RATE, samplesPerSecond, sampleProgressCounter)
        }
    }

    fun songStillActive(): Boolean {
        //the song is still active if:
        //  --we have not passed the final sample of the final tick of the final pattern in the order list

        //todo: This should be based on the order list, not the row position
        return currentRowPosition < 64
    }

    /**
     * Retrieves the next sample in the song
     */
    fun generateNextSample(): Byte {
        val sample = getNextSample(activePeriod, module.samples[0].sampleData!!)
        updateSongPosition()
        return sample
    }

    /**
     * Retrieves the next sample with the given period (note) and audio data
     */
    private fun getNextSample(period: Int, audioData: ByteArray): Byte {
        if (!isInstrumentCurrentlyPlaying) {
            return 0
        }

        //we need the current sample and the next sample to properly interpolate
        val currentSample = audioData[currentSamplePositionOfInstrument]
        val nextSample = if(currentSamplePositionOfInstrument + 1 == audioData.size) 0 else audioData[currentSamplePositionOfInstrument + 1]

        val rise = (nextSample - currentSample).toDouble()
        val slope = rise / iterationsUntilNextSample

        val actualSample = ((slope * samplesSinceSampleChanged).toInt() + currentSample).toByte()
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
        currentSamplePosition++
        if (currentSamplePosition >= getSamplesPerTick()) {
            currentSamplePosition = 0
            currentTickPosition++
        }

        if (currentTickPosition >= ticksPerRow) {
            currentTickPosition = 0
            currentRowPosition++

            //if we are at a new row, we need to identify if a new note command has been given
            if (currentRowPosition < 64) {
                activeRow = module.patterns[0].channels[0].rows[currentRowPosition]
                if (activeRow.period != 0) {
                    isInstrumentCurrentlyPlaying = true
                    activePeriod = activeRow.period
                    currentSamplePositionOfInstrument = 2
                }
            }
        }
    }

    private fun updateResamplingPosition(sizeOfAudioData: Int, period: Int) {
        sampleProgressCounter += samplesPerSecond
        samplesSinceSampleChanged++
        if (sampleProgressCounter > SAMPLING_RATE) {
            sampleProgressCounter -= SAMPLING_RATE
            currentSamplePositionOfInstrument++
            if (isInstrumentCurrentlyPlaying && currentSamplePositionOfInstrument >= sizeOfAudioData) {
                isInstrumentCurrentlyPlaying = false
                activePeriod = 0
            }

            //recalculate all resampling data here
            samplesPerSecond = PAL_CLOCK_RATE / (period * 2)
            iterationsUntilNextSample = getIterationsUntilNextSample(SAMPLING_RATE, samplesPerSecond, sampleProgressCounter)
            samplesSinceSampleChanged = 0
        }
    }

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