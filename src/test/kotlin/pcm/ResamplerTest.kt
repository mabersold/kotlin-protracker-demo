package pcm

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import loader.ProTrackerLoader
import player.AudioPlayer

class ResamplerTest {
    @Test
    fun `resamples to higher frequency without anti-aliasing`() {
        val resampler = Resampler()
        val audioData = listOf(0.2f, 0.5f, 0.8f, 0.1f, 0.4f, 0.7f, 0.0f, -0.3f)
        val currentRate = 8000f
        val targetRate = 16000f
        val expectedOutput = listOf(
            0.2f, 0.8f, 0.4f, 0.0f
        )
        val output = resampler.resample(audioData, currentRate, targetRate, false)
        assertEquals(expectedOutput, output)
    }

    @Test
    fun `resamples to higher frequency with anti-aliasing`() {
        val resampler = Resampler()
        val audioData = listOf(0.2f, 0.5f, 0.8f, 0.1f, 0.4f, 0.7f, 0.0f, -0.3f)
        val currentRate = 8000f
        val targetRate = 16000f
        val expectedOutput = listOf(
            0.15170941f, 0.6434683f, 0.45878616f, 0.11077529f
        )
        val output = resampler.resample(audioData, currentRate, targetRate, true)
        assertEquals(expectedOutput, output)
    }

    @Test
    fun `resamples to lower frequency without anti-aliasing`() {
        val resampler = Resampler()
        val audioData = listOf(0.2f, 0.5f, 0.8f, 0.1f, 0.4f, 0.7f, 0.0f, -0.3f)
        val currentRate = 16000f
        val targetRate = 8000f
        val expectedOutput = listOf(0.2f, 0.35f, 0.5f, 0.65f, 0.8f, 0.45000002f, 0.1f, 0.25f, 0.4f, 0.55f, 0.7f, 0.35f, 0.0f, -0.15f, -0.3f, -0.3f)
        val output = resampler.resample(audioData, currentRate, targetRate, false)
        assertEquals(expectedOutput, output)
    }

    @Test
    fun `resamples to lower frequency with anti-aliasing`() {
        val resampler = Resampler()
        val audioData = listOf(0.2f, 0.5f, 0.8f, 0.1f, 0.4f, 0.7f, 0.0f, -0.3f)
        val currentRate = 16000f
        val targetRate = 8000f
        val expectedOutput = listOf(0.15170941f, 0.30212215f, 0.4522218f, 0.6022458f, 0.7522517f, 0.5229796f, 0.20212969f, 0.23844157f, 0.36099124f, 0.5043633f, 0.65276295f, 0.42310303f, 0.10215949f, -0.08911534f, -0.24908128f, -0.28770554f)
        val output = resampler.resample(audioData, currentRate, targetRate)
        assertEquals(expectedOutput, output)
    }

//    @Test
    fun `resample an instrument and play it`() {
        val loader = ProTrackerLoader()
        val resampler = Resampler()

        val spaceDebris = loader.loadModule()

        val audioData = spaceDebris.instruments[0].audioData
        val resampledAudioDataWithAntiAliasing = resampler.resample(audioData, 44100f, 8287f)
        val resampledAudioDataWithoutAntiAliasing = resampler.resample(audioData, 44100f, 8287f, false)
        val reResampledAudioData = resampler.resample(resampledAudioDataWithAntiAliasing, 8287f, 16500f)

        val audioPlayer = AudioPlayer()
        audioPlayer.prepareAudioLine()

        val inStereo = resampledAudioDataWithAntiAliasing.map { sample ->
            val sampleAsWholeNumber = ((sample * 32767).toInt().coerceAtMost(32767).coerceAtLeast(-32768).toShort()) / 2
            Pair(sampleAsWholeNumber.toShort(), sampleAsWholeNumber.toShort())
        }

        val sampleBuffer = ByteBuffer.allocate(400000)

        inStereo.forEach {
            sampleBuffer.putShort(it.first)
            sampleBuffer.putShort(it.second)
        }

        val inStereoNoAntiAliasing = resampledAudioDataWithoutAntiAliasing.map { sample ->
            val sampleAsWholeNumber = ((sample * 32767).toInt().coerceAtMost(32767).coerceAtLeast(-32768).toShort()) / 2
            Pair(sampleAsWholeNumber.toShort(), sampleAsWholeNumber.toShort())
        }

        inStereoNoAntiAliasing.forEach {
            sampleBuffer.putShort(it.first)
            sampleBuffer.putShort(it.second)
        }

        audioPlayer.playAudio(sampleBuffer.array())

        audioPlayer.destroyAudioLine()

        println("debug point")
    }
}