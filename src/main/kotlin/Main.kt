import pcm.AudioGenerator
import player.AudioPlayer

fun main(args: Array<String>) {
    val loader = ProTrackerLoader()
    val module = loader.loadModule("test.mod")
    val audioGenerator = AudioGenerator(module)
    val audioPlayer = AudioPlayer()

    val resampledAudioData = audioGenerator.resample(module.samples[0].sampleData!!, module.patterns[0].channels[0].rows[0].period)

//    val resampledData = audioGenerator.downSample(module.samples[17].sampleData!!)
//    val resampledAgainData = audioGenerator.downSample(resampledData)

    //play the sample
//    audioPlayer.playAudio(module.samples[17].sampleData!!)

    //play the sample again, but resample down
//    audioPlayer.playAudio(resampledData)
//    audioPlayer.playAudio(resampledAgainData)

    audioPlayer.playAudio(resampledAudioData)
    println("Module title: ${module.title}")

    audioPlayer.playAudio(audioGenerator.resample(module.samples[0].sampleData!!, 404))

    //sample 1 plays at f-5 in SchismTracker
    //the period value for that is 320 - the documentation describes this as an F-2
    //effect is 15, value 06 - this is just a tempo change value

    //sample rate conversion: https://github.com/wuchubuzai/OpenIMAJ/blob/a2f295e3889f99e9e7e5789e830bbc29fed4a503/audio/audio-processing/src/main/java/org/openimaj/audio/conversion/SampleRateConverter.java
}