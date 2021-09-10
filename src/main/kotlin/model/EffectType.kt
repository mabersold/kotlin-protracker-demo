package model

enum class EffectType {
    PITCH_SLIDE_UP,
    PITCH_SLIDE_DOWN,
    SLIDE_TO_NOTE,
    SLIDE_TO_NOTE_WITH_VOLUME_SLIDE,
    VOLUME_SLIDE,
    INSTRUMENT_OFFSET,
    SET_VOLUME,
    PATTERN_BREAK,
    FINE_VOLUME_SLIDE_UP,
    FINE_VOLUME_SLIDE_DOWN,
    UNKNOWN_EFFECT
}