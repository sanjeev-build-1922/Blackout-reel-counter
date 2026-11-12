package com.blackout.focus.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import com.blackout.focus.data.reel.ReelRepository
import com.blackout.focus.data.repository.BlackoutRepository
import com.blackout.focus.ui.BiometricUnlockActivity
import com.blackout.focus.ui.overlay.BlackoutOverlayScreen
import com.blackout.focus.ui.theme.BlackoutTheme
import com.blackout.focus.util.FlashlightController
import com.blackout.focus.util.PermissionHelper

private class InterceptingOverlayFrameLayout(
    context: Context,
    private val onBlockedInteraction: () -> Unit
) : FrameLayout(context) {

    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        if (event != null) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_HOME,
                KeyEvent.KEYCODE_APP_SWITCH,
                KeyEvent.KEYCODE_MENU,
                KeyEvent.KEYCODE_ASSIST,
                KeyEvent.KEYCODE_VOICE_ASSIST,
                KeyEvent.KEYCODE_SEARCH,
                KeyEvent.KEYCODE_SETTINGS,
                KeyEvent.KEYCODE_NOTIFICATION -> {
                    onBlockedInteraction()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean {
        if (ev != null && ev.actionMasked == MotionEvent.ACTION_DOWN && ev.rawY < 96f) {
            onBlockedInteraction()
        }
        return super.onInterceptTouchEvent(ev)
    }
}

class BlackoutOverlayManager(
    private val context: Context,
    private val repository: BlackoutRepository,
    private val reelRepository: ReelRepository,
    private val flashlightController: FlashlightController,
    private val useAccessibilityOverlayType: Boolean = false,
    private val onCollapseShadeRequested: (() -> Unit)? = null
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var rootOverlayContainer: FrameLayout? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null
    private var isAdded: Boolean = false

    fun syncOverlayVisibility() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { syncOverlayVisibilityInternal() }
        } else {
            syncOverlayVisibilityInternal()
        }
    }

    fun bringOverlayToFront() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { bringOverlayToFrontInternal() }
        } else {
            bringOverlayToFrontInternal()
        }
    }

    private fun bringOverlayToFrontInternal() {
        if (!repository.shouldDisplayOverlayNow()) return
        val container = rootOverlayContainer
        if (isAdded && container != null) {
            try {
                windowManager.updateViewLayout(container, createOverlayLayoutParams())
            } catch (_: Throwable) {
                removeOverlayInternal()
                showOverlayInternal()
            }
        } else {
            showOverlayInternal()
        }
    }

    private fun syncOverlayVisibilityInternal() {
        if (!useAccessibilityOverlayType && !PermissionHelper.hasOverlayPermission(context)) {
            removeOverlayInternal()
            return
        }

        if (repository.shouldDisplayOverlayNow()) {
            showOverlayInternal()
        } else {
            removeOverlayInternal()
        }
    }

    fun removeOverlay() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { removeOverlayInternal() }
        } else {
            removeOverlayInternal()
        }
    }

    private fun showOverlayInternal() {
        if (isAdded && rootOverlayContainer != null) {
            return
        }

        val owner = OverlayLifecycleOwner()
        owner.onCreate()

        val container = InterceptingOverlayFrameLayout(
            context = context,
            onBlockedInteraction = {
                onCollapseShadeRequested?.invoke()
            }
        ).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
            @Suppress("DEPRECATION")
            systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                )
        }

        val composeView = ComposeView(context).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
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
                            triggerBiometricUnlockForAllowedApp(packageName, appName)
                        }
                    )
                }
            }
        }

        container.addView(
            composeView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        owner.attachToView(container)

        val layoutParams = createOverlayLayoutParams()

        try {
            windowManager.addView(container, layoutParams)
            container.requestFocus()
            owner.onStart()
            owner.onResume()
            rootOverlayContainer = container
            lifecycleOwner = owner
            isAdded = true
        } catch (_: Throwable) {
            owner.onDestroy()
            rootOverlayContainer = null
            lifecycleOwner = null
            isAdded = false
        }
    }

    private fun removeOverlayInternal() {
        val view = rootOverlayContainer
        val owner = lifecycleOwner

        rootOverlayContainer = null
        lifecycleOwner = null
        isAdded = false

        if (owner != null) {
            try {
                owner.onPause()
                owner.onStop()
                owner.onDestroy()
            } catch (_: Throwable) {
            }
        }

        if (view != null) {
            try {
                windowManager.removeViewImmediate(view)
            } catch (_: Throwable) {
                try {
                    windowManager.removeView(view)
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun triggerBiometricUnlockForAllowedApp(packageName: String, appName: String) {
        repository.setBiometricPromptInProgress(true)
        removeOverlayInternal()
        BiometricUnlockActivity.launch(context, packageName, appName)
    }

    @Suppress("DEPRECATION")
    private fun createOverlayLayoutParams(): WindowManager.LayoutParams {
        val windowType = if (useAccessibilityOverlayType) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }

        // Note: No FLAG_KEEP_SCREEN_ON so the display times out and turns off naturally to save battery!
        val flags = (
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                or WindowManager.LayoutParams.FLAG_FULLSCREEN
                or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                or WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
                or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            )

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            windowType,
            flags,
            PixelFormat.OPAQUE
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }
}
