package model

data class Row(
    val instrumentNumber: Int,
    val period: Int,
    val effect: EffectType,
    val effectXValue: Int,
    val effectYValue: Int
)
