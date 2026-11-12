package com.blackout.focus.data.model

enum class TimerPreset(val label: String, val durationMinutes: Long) {
    MINUTES_15("15m", 15L),
    MINUTES_30("30m", 30L),
    HOURS_1("1h", 60L),
    HOURS_2("2h", 120L),
    CUSTOM("Custom", -1L)
}
