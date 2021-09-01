package player

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem

class AudioPlayer {
    private val audioFormat: AudioFormat = AudioFormat(44100.0F, 8, 2, true, true)
    private val audioLine = AudioSystem.getSourceDataLine(audioFormat)

    init {
        audioLine.open()
    }

    fun prepareAudioLine() {
        audioLine.start()
    }

    fun destroyAudioLine() {
        audioLine.drain()
        audioLine.close()
    }

    fun playAudio(audioData: ByteArray) {
        audioLine.write(audioData, 0, audioData.size)
    }
}