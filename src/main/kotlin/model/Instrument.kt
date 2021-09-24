package model

data class Instrument(
    val name: String,
    val length: Short,
    val fineTune: Byte,
    val volume: Byte,
    val repeatOffsetStart: Short,
    val repeatLength: Short,
    var floatAudioData: ArrayList<Float> = arrayListOf()
)