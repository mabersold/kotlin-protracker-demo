package loader

import model.Channel
import model.EffectType
import model.Instrument
import model.Pattern
import model.ProTrackerModule
import model.Row
import model.PanningPosition
import java.io.File
import java.nio.ByteBuffer
import kotlin.experimental.and

class ProTrackerLoader {
    companion object {
        private const val TITLE_LENGTH = 20
        private const val INSTRUMENT_NAME_LENGTH = 22
        private const val NUMBER_OF_INSTRUMENTS = 31
        private const val ORDER_LIST_MAX_LENGTH = 128
        private const val SPACE_DEBRIS = "space_debris.mod"
        private const val PROTRACKER_IDENTIFIER = "M.K."
    }

    /**
     * Loads the module from the given file. If no file is specified, loads, space debris (included in the repo).
     *
     * For reference on the file format, see mod_format_descriptor.txt
     */
    fun loadModule(fileName: String? = null): ProTrackerModule {
        val loadingBuffer = prepareBuffer(fileName)

        val songTitle = getString(TITLE_LENGTH, loadingBuffer)
        val instruments = arrayListOf<Instrument>()

        repeat(NUMBER_OF_INSTRUMENTS) {
            instruments.add(getInstrument(loadingBuffer))
        }

        val numberOfSongPositions = loadingBuffer.get()
        val noiseTrackerRestartPosition = loadingBuffer.get()

        val orderList = arrayListOf<Int>()

        repeat(ORDER_LIST_MAX_LENGTH) {
            orderList.add(loadingBuffer.get().toInt())
        }

        val identifier = getString(4, loadingBuffer)

        if (PROTRACKER_IDENTIFIER != identifier) {
            throw RuntimeException("Could not validate file as a Protracker module. Expected identifier $PROTRACKER_IDENTIFIER but was $identifier. Terminating.")
        }

        val patterns = arrayListOf<Pattern>()

        for (patternNumber in 0..orderList.maxOrNull()!!) {
            // Panning positions are hard-coded LRRL in 4-channel ProTracker format
            val channels = arrayListOf(
                Channel(arrayListOf(), PanningPosition.LEFT),
                Channel(arrayListOf(), PanningPosition.RIGHT),
                Channel(arrayListOf(), PanningPosition.RIGHT),
                Channel(arrayListOf(), PanningPosition.LEFT)
            )

            patterns.add(Pattern(patternNumber, channels))

            for (rowNumber in 0..63) {
                for (channelNumber in 0..3) {
                    val channelLineArray = ByteArray(4)
                    loadingBuffer.get(channelLineArray)
                    channels[channelNumber].rows.add(getRow(channelLineArray))
                }
            }
        }

        for (instrument in instruments) {
            if (instrument.length > 0) {
                val byteAudioData = ByteArray(instrument.length * 2)
                loadingBuffer.get(byteAudioData)
                byteAudioData.forEach { byte ->
                    instrument.audioData.add(byte / Byte.MAX_VALUE.toFloat())
                }
            }
        }

        return ProTrackerModule(songTitle, orderList, patterns, instruments, numberOfSongPositions, noiseTrackerRestartPosition, identifier)
    }

    private fun getString(length: Int, buffer: ByteBuffer): String {
        val builder = StringBuilder()

        repeat(length) {
            builder.append(Char(buffer.get().toInt()))
        }

        return builder.toString().trimEnd(Char.MIN_VALUE)
    }

    private fun getInstrument(buffer: ByteBuffer): Instrument {
        val instrumentName = getString(INSTRUMENT_NAME_LENGTH, buffer)
        val instrumentLength = buffer.short
        val fineTune = signedNibble(buffer.get())
        val volume = buffer.get().toInt()
        val repeatOffsetStart = buffer.short
        val repeatLength = buffer.short

        return Instrument(instrumentName, instrumentLength, fineTune, volume, repeatOffsetStart, repeatLength)
    }

    private fun prepareBuffer(fileName: String?): ByteBuffer {
        val fileBytes = if (fileName == null) {
            javaClass.classLoader.getResourceAsStream(SPACE_DEBRIS)!!.readBytes()
        } else {
            File(fileName).readBytes()
        }

        return ByteBuffer.allocate(fileBytes.size).put(fileBytes).rewind()
    }

    private fun getRow(bufferData: ByteArray): Row {
        val instrumentNumber = bufferData[0].toInt().and(240) + bufferData[2].toInt().and(240).shr(4)
        val pitch = bufferData[0].toInt().and(15).shl(8).or(bufferData[1].toInt().and(255)).toFloat()
        val effect = bufferData[2].toInt().and(15).shl(8).or(bufferData[3].toInt().and(255))

        val effectNumber = effect.and(3840).shr(8)
        val xValue = effect.and(240).shr(4)
        val yValue = effect.and(15)
        val effectType = when(effectNumber) {
            0 -> {
                if (xValue == 0 && yValue == 0) {
                    EffectType.NONE
                } else {
                    EffectType.ARPEGGIO
                }
            }
            1 -> EffectType.PITCH_SLIDE_UP
            2 -> EffectType.PITCH_SLIDE_DOWN
            3 -> EffectType.SLIDE_TO_NOTE
            4 -> EffectType.VIBRATO
            5 -> EffectType.SLIDE_TO_NOTE_WITH_VOLUME_SLIDE
            6 -> EffectType.VIBRATO_WITH_VOLUME_SLIDE
            9 -> EffectType.INSTRUMENT_OFFSET
            10 -> EffectType.VOLUME_SLIDE
            11 -> EffectType.POSITION_JUMP
            12 -> EffectType.SET_VOLUME
            13 -> EffectType.PATTERN_BREAK
            14 -> {
                when(xValue) {
                    5 -> EffectType.SET_FINE_TUNE
                    10 -> EffectType.FINE_VOLUME_SLIDE_UP
                    11 -> EffectType.FINE_VOLUME_SLIDE_DOWN
                    else -> EffectType.UNKNOWN_EFFECT
                }
            }
            15 -> EffectType.CHANGE_SPEED
            else -> EffectType.UNKNOWN_EFFECT
        }
        return Row(instrumentNumber, pitch, effectType, xValue, yValue)
    }

    private fun signedNibble(data: Byte): Int {
        //get rid of the upper 4 bits
        val nibble = data.and(15)

        //if first bit is 1, it's a negative number
        return if (nibble.and(8) == 8.toByte() ) {
            (nibble - 16)
        } else {
            nibble.toInt()
        }
    }
}