package model

data class ProTrackerModule(
    val title: String,
    val orderList: ArrayList<Int>,
    val patterns: ArrayList<Pattern>,
    val samples: ArrayList<Sample>,
    val numberOfSongPositions: Byte,
    val noiseTrackerRestartPosition: Byte,
    val identifier: String
)
