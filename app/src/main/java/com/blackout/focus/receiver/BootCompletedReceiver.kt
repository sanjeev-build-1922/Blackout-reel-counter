package com.blackout.focus.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.blackout.focus.data.repository.BlackoutRepository
import com.blackout.focus.service.BlackoutForegroundService
import com.blackout.focus.service.ScheduleAlarmScheduler
import com.blackout.focus.util.PermissionHelper

/**
 * Automatically resumes the Blackout Foreground Service and full-screen overlay
 * after a device reboot or app update if the persisted targetEndTimeMillis is still in the future.
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            val repository = BlackoutRepository.getInstance(context)
            if (repository.reconcileSessionStateOnStartup()) {
                if (PermissionHelper.hasOverlayPermission(context)) {
                    BlackoutForegroundService.startService(context)
                }
            }
            ScheduleAlarmScheduler.evaluateAndScheduleNext(context)
        }
    }
}
