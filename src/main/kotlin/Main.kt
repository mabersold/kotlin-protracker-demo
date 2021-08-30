import pcm.AudioGenerator
import player.AudioPlayer
import java.nio.ByteBuffer

fun main(args: Array<String>) {
    val loader = ProTrackerLoader()
    val module = loader.loadModule("test.mod")
    val audioGenerator = AudioGenerator(module)
    val audioPlayer = AudioPlayer()

    val buffer = ByteBuffer.allocate(1000)

    audioPlayer.prepareAudioLine()

    while (audioGenerator.songStillActive()) {
        buffer.put(audioGenerator.generateNextSample())
        if (!buffer.hasRemaining()) {
            audioPlayer.playAudio(buffer.array())
            buffer.clear()
        }
    }

    audioPlayer.destroyAudioLine()
}