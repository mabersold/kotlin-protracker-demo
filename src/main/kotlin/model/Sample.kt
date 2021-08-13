package model

data class Sample(
    val name: String,
    val length: Short,
    val fineTune: Byte,
    val volume: Byte,
    val repeatOffsetStart: Short,
    val repeatLength: Short,
    var sampleData: ByteArray? = null
)