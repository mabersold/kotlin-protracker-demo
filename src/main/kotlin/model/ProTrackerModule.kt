package model

data class ProTrackerModule(
    val title: String,
    val orderList: ArrayList<Int>,
    val patterns: ArrayList<Pattern>,
    val instruments: ArrayList<Instrument>,
    val numberOfSongPositions: Byte,
    val noiseTrackerRestartPosition: Byte,
    val identifier: String
)
