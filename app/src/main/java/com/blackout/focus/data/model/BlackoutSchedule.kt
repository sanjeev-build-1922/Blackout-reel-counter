package com.blackout.focus.data.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * Represents a recurring Blackout schedule.
 * - [daysOfWeek]: Set of ISO DayOfWeek values (1 = Monday .. 7 = Sunday).
 *   When all 7 days are selected, it runs every day ("Every day").
 * - Supports both same-day windows (e.g. 09:00 - 17:00) and overnight windows (e.g. 22:00 - 06:00).
 */
data class BlackoutSchedule(
    val id: String = UUID.randomUUID().toString(),
    val label: String = "Focus Schedule",
    val startHour: Int,
    val startMinute: Int,
    val endHour: Int,
    val endMinute: Int,
    val daysOfWeek: Set<Int> = (1..7).toSet(),
    val isEnabled: Boolean = true
) {
    val isEveryDay: Boolean
        get() = daysOfWeek.size == 7

    fun formattedTimeRange(): String {
        val fmt = DateTimeFormatter.ofPattern("hh:mm a", Locale.US)
        val start = LocalTime.of(startHour, startMinute).format(fmt)
        val end = LocalTime.of(endHour, endMinute).format(fmt)
        return "$start - $end"
    }

    fun formattedDays(): String {
        if (daysOfWeek.size == 7) return "Every day"
        if (daysOfWeek == setOf(1, 2, 3, 4, 5)) return "Weekdays"
        if (daysOfWeek == setOf(6, 7)) return "Weekends"
        val names = listOf(
            1 to "Mon", 2 to "Tue", 3 to "Wed", 4 to "Thu",
            5 to "Fri", 6 to "Sat", 7 to "Sun"
        )
        return names.filter { daysOfWeek.contains(it.first) }.joinToString(", ") { it.second }
    }

    /**
     * If [nowMillis] falls inside an active window of this schedule, returns the exact epoch millis
     * when this window ends. Returns null otherwise.
     */
    fun getActiveWindowEndMillis(nowMillis: Long = System.currentTimeMillis()): Long? {
        if (!isEnabled || daysOfWeek.isEmpty()) return null

        val zone = ZoneId.systemDefault()
        val nowDt = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDateTime()
        val today = nowDt.toLocalDate()
        val startTime = LocalTime.of(startHour, startMinute)
        val endTime = LocalTime.of(endHour, endMinute)

        if (startTime == endTime) return null

        val isOvernight = endTime.isBefore(startTime)

        if (!isOvernight) {
            // Same-day window (e.g. 09:00 -> 17:00)
            if (daysOfWeek.contains(today.dayOfWeek.value)) {
                val startDt = LocalDateTime.of(today, startTime)
                val endDt = LocalDateTime.of(today, endTime)
                if (!nowDt.isBefore(startDt) && nowDt.isBefore(endDt)) {
                    return endDt.atZone(zone).toInstant().toEpochMilli()
                }
            }
        } else {
            // Overnight window (e.g. 22:00 -> 06:00 next day)
            // Case A: Started today at startTime and ends tomorrow at endTime
            if (daysOfWeek.contains(today.dayOfWeek.value)) {
                val startDt = LocalDateTime.of(today, startTime)
                val endDt = LocalDateTime.of(today.plusDays(1), endTime)
                if (!nowDt.isBefore(startDt) && nowDt.isBefore(endDt)) {
                    return endDt.atZone(zone).toInstant().toEpochMilli()
                }
            }
            // Case B: Started yesterday at startTime and ends today at endTime
            val yesterday = today.minusDays(1)
            if (daysOfWeek.contains(yesterday.dayOfWeek.value)) {
                val startDt = LocalDateTime.of(yesterday, startTime)
                val endDt = LocalDateTime.of(today, endTime)
                if (!nowDt.isBefore(startDt) && nowDt.isBefore(endDt)) {
                    return endDt.atZone(zone).toInstant().toEpochMilli()
                }
            }
        }

        return null
    }

    /**
     * Computes the next upcoming start timestamp (in epoch millis) strictly after [nowMillis].
     */
    fun getNextStartMillis(nowMillis: Long = System.currentTimeMillis()): Long? {
        if (!isEnabled || daysOfWeek.isEmpty()) return null
        val zone = ZoneId.systemDefault()
        val nowDt = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDateTime()
        val today = nowDt.toLocalDate()
        val startTime = LocalTime.of(startHour, startMinute)

        for (dayOffset in 0L..7L) {
            val candidateDate: LocalDate = today.plusDays(dayOffset)
            if (daysOfWeek.contains(candidateDate.dayOfWeek.value)) {
                val candidateDt = LocalDateTime.of(candidateDate, startTime)
                if (candidateDt.isAfter(nowDt)) {
                    return candidateDt.atZone(zone).toInstant().toEpochMilli()
                }
            }
        }
        return null
    }
}
