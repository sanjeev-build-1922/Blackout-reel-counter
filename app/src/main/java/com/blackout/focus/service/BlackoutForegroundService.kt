package com.blackout.focus.service

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.blackout.focus.BlackoutApplication
import com.blackout.focus.data.repository.BlackoutRepository
import com.blackout.focus.overlay.BlackoutOverlayManager
import com.blackout.focus.ui.BlackoutLockScreenActivity
import com.blackout.focus.ui.MainActivity
import com.blackout.focus.util.FlashlightController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground Service keeping the Blackout timer alive in the background and controlling
 * the WindowManager overlay lifecycle + Lock Screen activity until System.currentTimeMillis() >= targetEndTimeMillis.
 */
class BlackoutForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var repository: BlackoutRepository
    private lateinit var flashlightController: FlashlightController
    private lateinit var overlayManager: BlackoutOverlayManager

    private var timerWatchdogJob: Job? = null
    private var stateObserverJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val screenLockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (!repository.isSessionActive.value) return
            if (repository.checkAndExpireSessionIfNeeded()) {
                handleTimerCompletion()
                return
            }
            when (action) {
                Intent.ACTION_SCREEN_OFF -> {
                    repository.revokeTemporaryAppUnlock()
                    overlayManager.syncOverlayVisibility()
                    BlackoutAccessibilityService.syncAccessibilityOverlay()
                }
                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_USER_PRESENT -> {
                    overlayManager.syncOverlayVisibility()
                    overlayManager.bringOverlayToFront()
                    BlackoutAccessibilityService.syncAccessibilityOverlay()
                    BlackoutLockScreenActivity.launchIfBlocking(this@BlackoutForegroundService)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val app = BlackoutApplication.getInstance()
        repository = app.repository
        flashlightController = app.flashlightController
        overlayManager = BlackoutOverlayManager(
            context = applicationContext,
            repository = repository,
            reelRepository = app.reelRepository,
            flashlightController = flashlightController,
            useAccessibilityOverlayType = false,
            onCollapseShadeRequested = {
                BlackoutLockScreenActivity.collapseStatusBarPanel(applicationContext)
            }
        )
        createNotificationChannel()
        registerScreenReceiver()
    }

    private fun registerScreenReceiver() {
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenLockReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(screenLockReceiver, filter)
            }
        } catch (_: Throwable) {
        }
    }

    private fun acquirePartialWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Blackout:FocusTimerWatchdogWakeLock"
            )?.apply {
                setReferenceCounted(false)
                acquire(6 * 60 * 60 * 1000L) // Up to 6 hours safety bound
            }
        } catch (_: Throwable) {
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()

        when (intent?.action) {
            ACTION_SYNC_OVERLAY -> {
                if (repository.checkAndExpireSessionIfNeeded()) {
                    handleTimerCompletion()
                } else {
                    overlayManager.syncOverlayVisibility()
                    BlackoutAccessibilityService.syncAccessibilityOverlay()
                }
            }
            ACTION_STOP_IF_EXPIRED -> {
                if (repository.checkAndExpireSessionIfNeeded()) {
                    handleTimerCompletion()
                }
            }
            else -> {
                if (!repository.reconcileSessionStateOnStartup()) {
                    handleTimerCompletion()
                    return START_NOT_STICKY
                }
                startStateObserversIfNeeded()
                startTimerWatchdogIfNeeded()
                BlackoutApplication.getInstance().rootShellController.enforceBlackoutStartViaRootAsync(repository.temporarilyUnlockedPackage.value)
                overlayManager.syncOverlayVisibility()
                BlackoutAccessibilityService.syncAccessibilityOverlay()
                BlackoutLockScreenActivity.launchIfBlocking(this)
            }
        }

        return START_STICKY
    }

    private fun startStateObserversIfNeeded() {
        if (stateObserverJob?.isActive == true) return
        stateObserverJob = serviceScope.launch {
            combine(
                repository.isSessionActive,
                repository.temporarilyUnlockedPackage,
                repository.isBiometricPromptInProgress,
                repository.isAlarmExemptionActive
            ) { isActive, unlockedPkg, biometricInProgress, alarmActive ->
                isActive && unlockedPkg == null && !biometricInProgress && !alarmActive
            }.collect { shouldShow ->
                overlayManager.syncOverlayVisibility()
                BlackoutAccessibilityService.syncAccessibilityOverlay()
                if (shouldShow) {
                    BlackoutLockScreenActivity.launchIfBlocking(this@BlackoutForegroundService)
                }
            }
        }
    }

    private fun startTimerWatchdogIfNeeded() {
        if (timerWatchdogJob?.isActive == true) return
        timerWatchdogJob = serviceScope.launch {
            while (isActive) {
                val expired = repository.checkAndExpireSessionIfNeeded()
                if (expired) {
                    handleTimerCompletion()
                    break
                }
                overlayManager.syncOverlayVisibility()
                BlackoutAccessibilityService.syncAccessibilityOverlay()

                // If device is on the lock screen (Keyguard locked), ensure BlackoutLockScreenActivity is active
                val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                val isScreenOn = pm?.isInteractive != false
                if (isScreenOn) {
                    val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                    if (km?.isKeyguardLocked == true && repository.shouldDisplayOverlayNow()) {
                        BlackoutLockScreenActivity.launchIfBlocking(this@BlackoutForegroundService)
                    }
                    delay(250L)
                } else {
                    // Screen is OFF: poll slowly so battery is preserved while still catching timer end
                    delay(1000L)
                }
            }
        }
    }

    /**
     * Executed automatically once System.currentTimeMillis() >= targetEndTimeMillis:
     * 1. Calls WindowManager.removeView() on both Application and Accessibility overlays
     * 2. Turns off the flashlight
     * 3. Marks session completed in persistence
     * 4. Returns to the normal Setup Screen (MainActivity)
     * 5. Stops the Foreground Service
     */
    private fun handleTimerCompletion() {
        timerWatchdogJob?.cancel()
        stateObserverJob?.cancel()

        // 1. Remove WindowManager overlay views
        overlayManager.removeOverlay()
        BlackoutAccessibilityService.removeAccessibilityOverlay()

        // 2. Turn off flashlight safely
        flashlightController.turnOffFlashlight()

        // 3. Clear persisted session state and unsuspend all root-suspended apps
        repository.completeBlackoutSession()
        BlackoutApplication.getInstance().rootShellController.restoreAllOnBlackoutCompleteAsync()

        // 4. Release wake lock
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Throwable) {
        }

        // 5. Return to the normal Setup Screen
        try {
            val setupIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                        or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        or Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            startActivity(setupIntent)
        } catch (_: Throwable) {
        }

        // 6. Stop Foreground Service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun startAsForeground() {
        val notification = buildForegroundNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildForegroundNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("Blackout Active")
            .setContentText("Distraction blocking session in progress")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Blackout Active Session",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the Blackout focus timer active"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(screenLockReceiver)
        } catch (_: Throwable) {
        }
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Throwable) {
        }
        overlayManager.removeOverlay()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "blackout_focus_service_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START_OR_RESUME = "com.blackout.focus.action.START_OR_RESUME"
        const val ACTION_SYNC_OVERLAY = "com.blackout.focus.action.SYNC_OVERLAY"
        const val ACTION_STOP_IF_EXPIRED = "com.blackout.focus.action.STOP_IF_EXPIRED"

        fun startService(context: Context) {
            val intent = Intent(context.applicationContext, BlackoutForegroundService::class.java).apply {
                action = ACTION_START_OR_RESUME
            }
            ContextCompat.startForegroundService(context.applicationContext, intent)
        }

        fun syncOverlayNow(context: Context) {
            val repo = BlackoutRepository.getInstance(context)
            if (!repo.isSessionActive.value) return
            BlackoutAccessibilityService.syncAccessibilityOverlay()
            val intent = Intent(context.applicationContext, BlackoutForegroundService::class.java).apply {
                action = ACTION_SYNC_OVERLAY
            }
            try {
                ContextCompat.startForegroundService(context.applicationContext, intent)
            } catch (_: Throwable) {
            }
        }
    }
}
