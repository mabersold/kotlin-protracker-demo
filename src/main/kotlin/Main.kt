import loader.ProTrackerLoader
import pcm.AudioGenerator
import player.AudioPlayer
import java.nio.ByteBuffer

fun main(args: Array<String>) {
    val loader = ProTrackerLoader()
    val module = loader.loadModule(getFileNameFromArgs(args))

    val audioGenerator = AudioGenerator(module)

    val audioPlayer = AudioPlayer()
    audioPlayer.prepareAudioLine()

    val sampleBuffer = ByteBuffer.allocate(2000)

    while (audioGenerator.songStillActive()) {
        val nextSamples = audioGenerator.generateNextSamples()

        nextSamples.forEach {
            if (!sampleBuffer.hasRemaining()) {
                audioPlayer.playAudio(sampleBuffer.array())
                sampleBuffer.clear()
            }

            sampleBuffer.putShort(it.first)
            sampleBuffer.putShort(it.second)
        }
    }

    audioPlayer.destroyAudioLine()
}

private fun getFileNameFromArgs(args: Array<String>): String? =
    if (args.isNotEmpty()) args[0] else null