import pcm.AudioGenerator
import player.AudioPlayer
import java.nio.ByteBuffer

fun main(args: Array<String>) {
    val loader = ProTrackerLoader()
    val module = loader.loadModule("space_debris.mod")
    val audioGenerator = AudioGenerator(module)
    val audioPlayer = AudioPlayer()

    val buffer = ByteBuffer.allocate(2000)

    audioPlayer.prepareAudioLine()

    while (audioGenerator.songStillActive()) {
        val nextSamples = audioGenerator.generateNextSample()
        buffer.put(nextSamples.first)
        buffer.put(nextSamples.second)
        if (!buffer.hasRemaining()) {
            audioPlayer.playAudio(buffer.array())
            buffer.clear()
        }
    }

    audioPlayer.destroyAudioLine()
}