package com.blackout.focus.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import com.blackout.focus.BlackoutApplication
import com.blackout.focus.data.reel.ReelRepository
import com.blackout.focus.data.repository.BlackoutRepository
import com.blackout.focus.ui.overlay.BlackoutOverlayScreen
import com.blackout.focus.ui.theme.BlackoutTheme
import com.blackout.focus.util.FlashlightController
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class BlackoutLockScreenActivity : ComponentActivity() {

    private lateinit var repository: BlackoutRepository
    private lateinit var reelRepository: ReelRepository
    private lateinit var flashlightController: FlashlightController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)

        configureLockScreenFlags()

        val app = BlackoutApplication.getInstance()
        repository = app.repository
        reelRepository = app.reelRepository
        flashlightController = app.flashlightController

        if (!repository.shouldDisplayOverlayNow()) {
            finishWithoutAnimation()
            return
        }

        lifecycleScope.launch {
            combine(
                repository.isSessionActive,
                repository.temporarilyUnlockedPackage,
                repository.isBiometricPromptInProgress,
                repository.isAlarmExemptionActive
            ) { isActive, unlockedPkg, biometricInProgress, alarmActive ->
                isActive && unlockedPkg == null && !biometricInProgress && !alarmActive
            }.collect { shouldRemainVisible ->
                if (!shouldRemainVisible) {
                    finishWithoutAnimation()
                }
            }
        }

        setContent {
            BlackoutTheme {
                val allowedNotifications by repository.activeAllowedNotifications.collectAsState()
                val isFlashlightOn by flashlightController.isFlashlightOn.collectAsState()
                val todayReelBreakdown by reelRepository.todayBreakdownState.collectAsState()

                BlackoutOverlayScreen(
                    allowedNotifications = allowedNotifications,
                    todayReelBreakdown = todayReelBreakdown,
                    isFlashlightOn = isFlashlightOn,
                    onToggleFlashlight = {
                        flashlightController.toggleFlashlight()
                    },
                    onFourSecondHoldCompleted = { packageName, appName ->
                        repository.setBiometricPromptInProgress(true)
                        BiometricUnlockActivity.launch(this@BlackoutLockScreenActivity, packageName, appName)
                        finishWithoutAnimation()
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBarsImmersive()
        val rootCtrl = BlackoutApplication.getInstance().rootShellController
        if (rootCtrl.rootModeEnabled.value && rootCtrl.kioskLockTaskEnabled.value && rootCtrl.isLockTaskPermitted()) {
            try {
                startLockTask()
            } catch (_: Throwable) {
            }
        }
        if (!repository.shouldDisplayOverlayNow()) {
            finishWithoutAnimation()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBarsImmersive()
            collapseStatusBarPanel(this)
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // Strictly block Back button during active Blackout
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_APP_SWITCH,
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_ASSIST,
            KeyEvent.KEYCODE_VOICE_ASSIST,
            KeyEvent.KEYCODE_SEARCH,
            KeyEvent.KEYCODE_SETTINGS -> true
            else -> super.onKeyDown(keyCode, event)
        }
    }

    @Suppress("DEPRECATION")
    private fun configureLockScreenFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
        }
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                or WindowManager.LayoutParams.FLAG_FULLSCREEN
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBarsImmersive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
    }

    private fun finishWithoutAnimation() {
        try {
            stopLockTask()
        } catch (_: Throwable) {
        }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        fun launchIfBlocking(context: Context) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm?.isInteractive == false) return
            val repo = BlackoutRepository.getInstance(context)
            if (!repo.shouldDisplayOverlayNow()) return
            try {
                val intent = Intent(context.applicationContext, BlackoutLockScreenActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK
                            or Intent.FLAG_ACTIVITY_SINGLE_TOP
                            or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            or Intent.FLAG_ACTIVITY_NO_ANIMATION
                    )
                }
                context.applicationContext.startActivity(intent)
            } catch (_: Throwable) {
            }
        }

        @Suppress("PrivateApi", "DEPRECATION")
        fun collapseStatusBarPanel(context: Context) {
            try {
                context.sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
            } catch (_: Throwable) {
            }
            try {
                val statusBarService = context.getSystemService("statusbar") ?: return
                val statusBarManager = Class.forName("android.app.StatusBarManager")
                val collapse = statusBarManager.getMethod("collapsePanels")
                collapse.isAccessible = true
                collapse.invoke(statusBarService)
            } catch (_: Throwable) {
            }
        }
    }
}
