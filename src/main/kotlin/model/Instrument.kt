package model

data class Instrument(
    val name: String,
    val length: Short,
    val fineTune: Int,
    val volume: Int,
    val repeatOffsetStart: Short,
    val repeatLength: Short,
    val audioData: ArrayList<Float> = arrayListOf()
)