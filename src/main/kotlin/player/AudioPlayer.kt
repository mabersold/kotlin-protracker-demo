package player

import model.Constants.SAMPLING_RATE
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem

class AudioPlayer {
    private val audioFormat: AudioFormat = AudioFormat(SAMPLING_RATE.toFloat(), 8, 2, true, true)
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