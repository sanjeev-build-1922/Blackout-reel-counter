package com.blackout.focus.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.blackout.focus.data.repository.BlackoutRepository
import com.blackout.focus.data.repository.ScheduleRepository
import com.blackout.focus.receiver.ScheduleTriggerReceiver
import com.blackout.focus.util.PermissionHelper

/**
 * Schedules the next AlarmManager trigger for upcoming Blackout schedules and activates
 * any schedule whose window is currently active.
 */
object ScheduleAlarmScheduler {

    private const val REQUEST_CODE_NEXT_SCHEDULE = 4001

    fun evaluateAndScheduleNext(context: Context) {
        val appContext = context.applicationContext
        val scheduleRepo = ScheduleRepository.getInstance(appContext)
        val blackoutRepo = BlackoutRepository.getInstance(appContext)
        val now = System.currentTimeMillis()

        // 1. If a schedule is active right now and no longer manual session is running, activate it!
        val activeScheduleEnd = scheduleRepo.getActiveScheduleEndMillis(now)
        if (activeScheduleEnd != null && activeScheduleEnd > now) {
            val currentEnd = blackoutRepo.targetEndTimeMillis.value
            if (!blackoutRepo.isSessionActive.value || activeScheduleEnd > currentEnd) {
                blackoutRepo.startBlackoutSessionWithTargetEnd(activeScheduleEnd)
                if (PermissionHelper.hasOverlayPermission(appContext)) {
                    BlackoutForegroundService.startService(appContext)
                }
            }
        }

        // 2. Schedule AlarmManager for the next upcoming start time
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(appContext, ScheduleTriggerReceiver::class.java).apply {
            action = ScheduleTriggerReceiver.ACTION_TRIGGER_SCHEDULE
        }
        val pendingIntent = PendingIntent.getBroadcast(
            appContext,
            REQUEST_CODE_NEXT_SCHEDULE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val nextStart = scheduleRepo.getNextUpcomingStartMillis(now)
        if (nextStart == null) {
            alarmManager.cancel(pendingIntent)
            return
        }

        try {
            val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                alarmManager.canScheduleExactAlarms()
            } else {
                true
            }
            if (canExact) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextStart, pendingIntent)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextStart, pendingIntent)
            }
        } catch (_: SecurityException) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextStart, pendingIntent)
        } catch (_: Throwable) {
        }
    }
}
