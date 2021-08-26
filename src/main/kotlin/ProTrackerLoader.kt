import model.Channel
import model.Pattern
import model.ProTrackerModule
import model.Row
import model.Sample
import java.nio.ByteBuffer
import kotlin.experimental.and

class ProTrackerLoader {
    companion object {
        private const val TITLE_LENGTH = 20
        private const val SAMPLE_NAME_LENGTH = 22
        private const val NUMBER_OF_SAMPLES = 31
        private const val ORDER_LIST_MAX_LENGTH = 128
    }

    private lateinit var loadingBuffer: ByteBuffer

    fun loadModule(fileName: String): ProTrackerModule {
        prepareBuffer(fileName)

        val songTitle = getString(TITLE_LENGTH)
        val samples = arrayListOf<Sample>()

        repeat(NUMBER_OF_SAMPLES) {
            samples.add(getSample())
        }

        val numberOfSongPositions = loadingBuffer.get()
        val noiseTrackerRestartPosition = loadingBuffer.get()

        val orderList = arrayListOf<Int>()

        repeat(ORDER_LIST_MAX_LENGTH) {
            orderList.add(loadingBuffer.get().toInt())
        }

        val identifier = getString(4)

        val patterns = arrayListOf<Pattern>()

        for (patternNumber in 0..orderList.maxOrNull()!!) {
            val channels = arrayListOf(Channel(arrayListOf()), Channel(arrayListOf()), Channel(arrayListOf()), Channel(arrayListOf()))

            patterns.add(Pattern(patternNumber, channels))

            for (rowNumber in 0..63) {
                for (channelNumber in 0..3) {
                    val channelLineArray = ByteArray(4)
                    loadingBuffer.get(channelLineArray)
                    channels[channelNumber].rows.add(getRow(channelLineArray))
                }
            }
        }

        for (sample in samples) {
            if (sample.length > 0) {
                sample.sampleData = ByteArray(sample.length * 2)
                loadingBuffer.get(sample.sampleData)
            }
        }

        return ProTrackerModule(songTitle, patterns, samples, numberOfSongPositions, noiseTrackerRestartPosition, identifier)
    }

    private fun getString(length: Int): String {
        val builder = StringBuilder()

        repeat(length) {
            builder.append(Char(loadingBuffer.get().toInt()))
        }

        return builder.toString().trimEnd(Char.MIN_VALUE)
    }

    private fun getSample(): Sample {
        val sampleName = getString(SAMPLE_NAME_LENGTH)
        val sampleLength = loadingBuffer.short
        val fineTune = signedNibble(loadingBuffer.get())
        val volume = loadingBuffer.get()
        val repeatOffsetStart = loadingBuffer.short
        val repeatLength = loadingBuffer.short

        return Sample(sampleName, sampleLength, fineTune, volume, repeatOffsetStart, repeatLength)
    }

    private fun prepareBuffer(fileName: String) {
        val fileBytes = javaClass.classLoader.getResourceAsStream(fileName)!!.readBytes()
        loadingBuffer = ByteBuffer.allocate(fileBytes.size)
        loadingBuffer.put(fileBytes)
        loadingBuffer.rewind()
    }

    private fun getRow(bufferData: ByteArray): Row {
        val sampleNumber = bufferData[0].toInt().and(240) + bufferData[2].toInt().and(240).shr(4)
        val period = bufferData[0].toInt().and(15).shl(8).or(bufferData[1].toInt().and(255))
        val effect = bufferData[2].toInt().and(15).shl(8).or(bufferData[3].toInt().and(255))

        val effectNumber = effect.and(3840).shr(8)
        val xValue = effect.and(240).shr(4)
        val yValue = effect.and(15)

        return Row(sampleNumber, period, effectNumber, xValue, yValue)
    }

    private fun signedNibble(data: Byte): Byte {
        //get rid of the upper 4 bits
        val nibble = data.and(15)

        //if first bit is 1, it's a negative number
        if (nibble.and(8) == 8.toByte() ) {
            return (nibble - 16).toByte()
        } else {
            return nibble
        }
    }
}