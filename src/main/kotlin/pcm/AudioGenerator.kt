package pcm

import model.ProTrackerModule
import model.Row
import java.nio.ByteBuffer
import kotlin.math.floor

class AudioGenerator(private val module: ProTrackerModule) {
    private var ticksPerRow = 6
    private var beatsPerMinute = 125
    private var currentTickPosition = 0
    private var currentRowPosition = 0
    private var currentSamplePosition = 0

    private var activeSampleNumber = 0

    //Current state information
    private var isInstrumentCurrentlyPlaying = false
    private lateinit var activeRow: Row
    //todo: There is no active period if instrument is not playing
    //The period (aka note) can be modified by effects, so it should be tracked separately from the active row
    private var activePeriod = 0

    //Starting at 2, because the first two bytes are loop data
    //todo: Reset currentSamplePositionOfInstrument when a new note command is issued
    private var currentSamplePositionOfInstrument = 2
    private var samplesSinceSampleChanged = 0
    private var sampleProgressCounter = 0.0

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
        }
    }

    fun songStillActive(): Boolean {
        //the song is still active if:
        //  --we have not passed the final sample of the final tick of the final pattern in the order list

        //todo: This should be based on the order list, not the row position
        return currentRowPosition < 64
    }

    fun generateNextSample(): Byte {
        //get the sample data
        val sample = getNextSample(activePeriod, module.samples[0].sampleData!!)

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
                    //Yes, there is a new note command
                    isInstrumentCurrentlyPlaying = true
                    activePeriod = activeRow.period
                    currentSamplePositionOfInstrument = 2
                }
            }
        }

        return sample
    }

    private fun getNextSample(period: Int, audioData: ByteArray): Byte {
        if (!isInstrumentCurrentlyPlaying) {
            return 0
        }

        val samplesPerSecond = PAL_CLOCK_RATE / (period * 2)
        //todo: I'm pretty sure this does not need to be calculated every single iteration
        val totalIterationsUntilNextSample = getIterationsUntilNextSample(SAMPLING_RATE, samplesPerSecond, sampleProgressCounter).toDouble()

        //get the current sample from the audio data
        val currentSample = audioData[currentSamplePositionOfInstrument]
        //get the next sample from the audio data
        val nextSample = if(currentSamplePositionOfInstrument + 1 == audioData.size) 0 else audioData[currentSamplePositionOfInstrument + 1]

        val rise = (nextSample - currentSample).toDouble()
        val slope = rise / totalIterationsUntilNextSample

        val actualSample = ((slope * samplesSinceSampleChanged).toInt() + currentSample).toByte()

        //Adjust all the values if needed
        sampleProgressCounter += samplesPerSecond
        samplesSinceSampleChanged++
        if (sampleProgressCounter > SAMPLING_RATE) {
            sampleProgressCounter -= SAMPLING_RATE
            currentSamplePositionOfInstrument++
            if (isInstrumentCurrentlyPlaying && currentSamplePositionOfInstrument > audioData.size) {
                isInstrumentCurrentlyPlaying = false
            }

            samplesSinceSampleChanged = 0
        }

        return actualSample
    }

    //todo: calculate how much I need to allocate
    fun resample(audioData: ByteArray, period: Int): ByteArray {
        val samplesPerSecond = PAL_CLOCK_RATE / (period * 2)
        val returnBuffer = ByteBuffer.allocate(60000)

        var counter = 0.0

        //the first two bytes of the sample data are used only for looping data, so we should start at the third byte (index=2)
        var audioDataIndex = 2

        var iterationsSinceSampleChanged = 0
//        var incrementerIShouldDelete = 0
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
//            println(incrementerIShouldDelete++)
        }

        return returnBuffer.array()
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
}