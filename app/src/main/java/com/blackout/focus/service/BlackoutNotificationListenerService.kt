package com.blackout.focus.service

import android.app.Notification
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.blackout.focus.BlackoutApplication
import com.blackout.focus.data.repository.BlackoutRepository
import com.blackout.focus.util.AlarmWhitelist

/**
 * Listens for incoming notifications during a Blackout session:
 * 1. Exempts active system alarms (Notification.CATEGORY_ALARM or whitelisted clock full-screen intents).
 * 2. If a notification arrives from a user-selected "Allowed App", resolves ONLY the App's Name
 *    (never the sender or message content) and publishes it to BlackoutRepository.
 */
class BlackoutNotificationListenerService : NotificationListenerService() {

    private val repository: BlackoutRepository
        get() = BlackoutApplication.getInstance().repository

    override fun onListenerConnected() {
        super.onListenerConnected()
        refreshActiveNotificationsState()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        // 1. Check if this notification is a ringing alarm or timer
        if (isAlarmNotification(sbn)) {
            repository.setAlarmNotificationActive(true)
            BlackoutForegroundService.syncOverlayNow(this)
            return
        }

        // 2. Only track allowed app notifications during an active blackout session
        if (!repository.isSessionActive.value) return
        if (repository.checkAndExpireSessionIfNeeded()) return

        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return

        // Ignore group summary placeholders so we don't duplicate entries unnecessarily
        val notification = sbn.notification ?: return
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) {
            // Still allow if no other notification from this package is present, handled by deduplication
        }

        if (repository.allowedPackages.value.contains(pkg)) {
            val appName = resolveApplicationName(pkg)
            repository.onAllowedAppNotificationPosted(pkg, appName)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return

        if (isAlarmNotification(sbn) || AlarmWhitelist.isAlarmOrClockPackage(this, pkg)) {
            recheckActiveAlarmNotifications()
        }

        // Check if the allowed app still has any remaining active notifications
        val stillHasNotificationsFromPkg = try {
            activeNotifications?.any { it.packageName == pkg && it.key != sbn.key } == true
        } catch (_: Throwable) {
            false
        }

        if (!stillHasNotificationsFromPkg) {
            repository.onAllowedAppNotificationRemoved(pkg)
        }
    }

    private fun refreshActiveNotificationsState() {
        try {
            val currentList = activeNotifications ?: return
            var alarmActive = false
            for (sbn in currentList) {
                if (isAlarmNotification(sbn)) {
                    alarmActive = true
                } else if (repository.isSessionActive.value &&
                    repository.allowedPackages.value.contains(sbn.packageName)
                ) {
                    val appName = resolveApplicationName(sbn.packageName)
                    repository.onAllowedAppNotificationPosted(sbn.packageName, appName)
                }
            }
            repository.setAlarmNotificationActive(alarmActive)
        } catch (_: Throwable) {
            // Ignore security or binder exceptions if listener is disconnecting
        }
    }

    private fun recheckActiveAlarmNotifications() {
        try {
            val hasActiveAlarm = activeNotifications?.any { isAlarmNotification(it) } == true
            repository.setAlarmNotificationActive(hasActiveAlarm)
            BlackoutForegroundService.syncOverlayNow(this)
        } catch (_: Throwable) {
            repository.setAlarmNotificationActive(false)
        }
    }

    private fun isAlarmNotification(sbn: StatusBarNotification): Boolean {
        val notification = sbn.notification ?: return false
        if (notification.category == Notification.CATEGORY_ALARM) {
            return true
        }
        if (AlarmWhitelist.isAlarmOrClockPackage(this, sbn.packageName)) {
            val isOngoing = (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0
            val hasFullScreen = notification.fullScreenIntent != null
            if (isOngoing || hasFullScreen) {
                return true
            }
        }
        return false
    }

    private fun resolveApplicationName(packageName: String): String {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        }
    }
}
