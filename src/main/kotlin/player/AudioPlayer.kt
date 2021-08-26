package player

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem

class AudioPlayer {
    private val audioFormat: AudioFormat = AudioFormat(44100.0F, 8, 1, true, true)
    private val audioLine = AudioSystem.getSourceDataLine(audioFormat)

    init {
        audioLine.open()
    }

    fun playAudio(audioData: ByteArray) {
        audioLine.start()

        audioLine.write(audioData, 0, audioData.size)

        audioLine.drain()
    }
}