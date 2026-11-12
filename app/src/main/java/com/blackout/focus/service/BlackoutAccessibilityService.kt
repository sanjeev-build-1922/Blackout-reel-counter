package com.blackout.focus.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.blackout.focus.BlackoutApplication
import com.blackout.focus.data.reel.ReelPlatform
import com.blackout.focus.data.reel.ReelRepository
import com.blackout.focus.data.repository.BlackoutRepository
import com.blackout.focus.overlay.BlackoutOverlayManager
import com.blackout.focus.overlay.AppEntryGateManager
import com.blackout.focus.overlay.FloatingReelHudManager
import com.blackout.focus.ui.MainActivity
import com.blackout.focus.ui.BlackoutLockScreenActivity
import com.blackout.focus.util.AlarmWhitelist
import com.blackout.focus.util.FlashlightController
import com.blackout.focus.util.PermissionHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

class BlackoutAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var repository: BlackoutRepository
    private lateinit var reelRepository: ReelRepository
    private lateinit var flashlightController: FlashlightController

    private var accessibilityOverlayManager: BlackoutOverlayManager? = null
    private var floatingReelHudManager: FloatingReelHudManager? = null
    private var appEntryGateManager: AppEntryGateManager? = null
    private var approvedEntryPlatform: ReelPlatform? = null

    private var stateObserverJob: Job? = null
    private var watchdogJob: Job? = null
    private var usageTimerJob: Job? = null

    // ReelCounter swipe tracking state
    private var lastRecordedSwipeElapsedMs: Long = 0L
    private var lastInstagramPageIndex: Int = -1
    private var lastYoutubePageIndex: Int = -1
    private var lastKnownForegroundAppPackage: String? = null

    private val screenAndLockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return

            when (action) {
                Intent.ACTION_SCREEN_OFF -> {
                    clearActiveReelPlatformAndHideHud()

                    if (!repository.isSessionActive.value) return
                    if (repository.checkAndExpireSessionIfNeeded()) return

                    repository.revokeTemporaryAppUnlock()
                    dismissSystemShadeAndDialogs()
                    accessibilityOverlayManager?.syncOverlayVisibility()
                }
                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_USER_PRESENT -> {
                    if (!repository.isSessionActive.value) {
                        verifyTopWindowPlatform()
                        return
                    }
                    if (repository.checkAndExpireSessionIfNeeded()) return

                    dismissSystemShadeAndDialogs()
                    accessibilityOverlayManager?.syncOverlayVisibility()
                    accessibilityOverlayManager?.bringOverlayToFront()
                    if (repository.shouldDisplayOverlayNow()) {
                        clearActiveReelPlatformAndHideHud()
                        BlackoutLockScreenActivity.launchIfBlocking(this@BlackoutAccessibilityService)
                    } else {
                        verifyTopWindowPlatform()
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        val app = BlackoutApplication.getInstance()
        repository = app.repository
        reelRepository = app.reelRepository
        flashlightController = app.flashlightController
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        try {
            val info = serviceInfo ?: AccessibilityServiceInfo()
            info.flags = info.flags or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            serviceInfo = info
        } catch (_: Exception) {
        }

        accessibilityOverlayManager = BlackoutOverlayManager(
            context = this,
            repository = repository,
            reelRepository = reelRepository,
            flashlightController = flashlightController,
            useAccessibilityOverlayType = true,
            onCollapseShadeRequested = {
                dismissSystemShadeAndDialogs()
            }
        )

        floatingReelHudManager = FloatingReelHudManager(
            context = this,
            reelRepository = reelRepository
        )

        appEntryGateManager = AppEntryGateManager(
            context = this,
            onBackClicked = {
                approvedEntryPlatform = null
                clearActiveReelPlatformAndHideHud()
                try {
                    performGlobalAction(GLOBAL_ACTION_HOME)
                } catch (_: Throwable) {
                }
            },
            onSetBlockerClicked = {
                approvedEntryPlatform = null
                clearActiveReelPlatformAndHideHud()
                try {
                    performGlobalAction(GLOBAL_ACTION_HOME)
                } catch (_: Throwable) {
                }
                val setupIntent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                try {
                    startActivity(setupIntent)
                } catch (_: Throwable) {
                }
            },
            onAutoAllowed = { platform ->
                approvedEntryPlatform = platform
                if (reelRepository.hudEnabled.value) {
                    floatingReelHudManager?.show(
                        platform,
                        reelRepository.todayBreakdownState.value.total
                    )
                }
            }
        )

        // Live sync of today's reel count to the Floating HUD pill
        serviceScope.launch {
            reelRepository.todayBreakdownState.collect { breakdown ->
                floatingReelHudManager?.updateCount(breakdown.total)
            }
        }

        // Hide HUD immediately if user disables the Floating Counter toggle in settings
        serviceScope.launch {
            reelRepository.hudEnabled.collect { enabled ->
                if (!enabled) {
                    floatingReelHudManager?.hide()
                } else {
                    val activePlatform = reelRepository.activeForegroundPlatform.value
                    if (activePlatform != null && !repository.shouldDisplayOverlayNow()) {
                        floatingReelHudManager?.show(
                            activePlatform,
                            reelRepository.todayBreakdownState.value.total
                        )
                    }
                }
            }
        }

        registerScreenLockReceiver()
        startStateObserver()
        startUnifiedWatchdog()
        startPlatformUsageTimer()

        if (repository.reconcileSessionStateOnStartup() && PermissionHelper.hasOverlayPermission(this)) {
            BlackoutForegroundService.startService(this)
            accessibilityOverlayManager?.syncOverlayVisibility()
        } else {
            verifyTopWindowPlatform()
        }
    }

    private fun registerScreenLockReceiver() {
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenAndLockReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(screenAndLockReceiver, filter)
            }
        } catch (_: Throwable) {
        }
    }

    private fun startStateObserver() {
        stateObserverJob?.cancel()
        stateObserverJob = serviceScope.launch {
            combine(
                repository.isSessionActive,
                repository.temporarilyUnlockedPackage,
                repository.isBiometricPromptInProgress,
                repository.isAlarmExemptionActive
            ) { isActive, unlockedPkg, biometricInProgress, alarmActive ->
                isActive && unlockedPkg == null && !biometricInProgress && !alarmActive
            }.collect { shouldShowBlackout ->
                accessibilityOverlayManager?.syncOverlayVisibility()
                if (shouldShowBlackout) {
                    clearActiveReelPlatformAndHideHud()
                    dismissSystemShadeAndDialogs()
                    accessibilityOverlayManager?.bringOverlayToFront()
                } else {
                    verifyTopWindowPlatform()
                }
            }
        }
    }

    /**
     * Tracks actual seconds spent inside Instagram and YouTube today while the screen is interactive.
     */
    private fun startPlatformUsageTimer() {
        usageTimerJob?.cancel()
        usageTimerJob = serviceScope.launch {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            while (isActive) {
                delay(1000L)
                val isScreenOn = pm?.isInteractive != false
                val activePlatform = reelRepository.activeForegroundPlatform.value
                if (isScreenOn && activePlatform != null && !repository.shouldDisplayOverlayNow()) {
                    reelRepository.addPlatformUsageSeconds(activePlatform, 1L)
                }
            }
        }
    }

    private fun startUnifiedWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = serviceScope.launch {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            while (isActive) {
                val isScreenOn = pm?.isInteractive != false
                if (!isScreenOn) {
                    clearActiveReelPlatformAndHideHud()
                    delay(1000L)
                    continue
                }

                if (repository.isSessionActive.value && !repository.checkAndExpireSessionIfNeeded()) {
                    accessibilityOverlayManager?.syncOverlayVisibility()
                    inspectInteractiveWindowsAndEnforceLock()
                    if (repository.shouldDisplayOverlayNow()) {
                        clearActiveReelPlatformAndHideHud()
                    } else {
                        verifyTopWindowPlatform()
                    }
                    delay(200L)
                } else {
                    ScheduleAlarmScheduler.evaluateAndScheduleNext(this@BlackoutAccessibilityService)
                    accessibilityOverlayManager?.removeOverlay()
                    verifyTopWindowPlatform()
                    delay(650L)
                }
            }
        }
    }

    private fun inspectInteractiveWindowsAndEnforceLock() {
        try {
            val windowList = windows ?: return
            val unlockedPkg = repository.temporarilyUnlockedPackage.value

            val hasSplitOrPip = windowList.any {
                it.type == AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER || it.isInPictureInPictureMode
            }
            if (hasSplitOrPip && !repository.isAlarmExemptionActive.value) {
                repository.revokeTemporaryAppUnlock()
                ejectFromForbiddenAppAndRelock()
                return
            }

            for (window in windowList) {
                val rootNode = try { window.root } catch (_: Throwable) { null } ?: continue
                val pkg = rootNode.packageName?.toString()
                @Suppress("DEPRECATION")
                rootNode.recycle()
                if (pkg.isNullOrBlank() || pkg == packageName) continue

                val title = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    window.title?.toString()
                } else {
                    null
                }

                if (AlarmWhitelist.isForbiddenBypassPackage(pkg)) {
                    repository.revokeTemporaryAppUnlock()
                    ejectFromForbiddenAppAndRelock()
                    return
                }

                if (repository.shouldDisplayOverlayNow()) {
                    if (window.type == AccessibilityWindowInfo.TYPE_SYSTEM &&
                        AlarmWhitelist.isSystemUiShadeOrControlCenter(pkg, null, title)
                    ) {
                        dismissSystemShadeAndDialogs()
                        accessibilityOverlayManager?.bringOverlayToFront()
                    }
                } else if (unlockedPkg != null) {
                    if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                        pkg != unlockedPkg &&
                        !AlarmWhitelist.isTransientInputOrSystemAuthPackage(this, pkg) &&
                        !AlarmWhitelist.isAlarmOrClockPackage(this, pkg)
                    ) {
                        repository.revokeTemporaryAppUnlock()
                        ejectFromForbiddenAppAndRelock()
                        return
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val eventPackage = event.packageName?.toString().orEmpty()
        val className = event.className?.toString().orEmpty()

        // -------------------------------------------------------------------------
        // PART 1: REEL COUNTER SWIPE DETECTION & FLOATING HUD LIFECYCLE
        // -------------------------------------------------------------------------
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // Ignore our own overlay package or transient system UI toasts/bars when updating foreground app hint
                if (eventPackage.isNotEmpty() && eventPackage != packageName) {
                    if (eventPackage == "com.android.systemui") {
                        val lowerCls = className.lowercase()
                        if (lowerCls.contains("recents") ||
                            lowerCls.contains("overview") ||
                            lowerCls.contains("quickstep")
                        ) {
                            lastKnownForegroundAppPackage = eventPackage
                        }
                    } else if (!AlarmWhitelist.isTransientInputOrSystemAuthPackage(this, eventPackage)) {
                        lastKnownForegroundAppPackage = eventPackage
                    }
                }
                verifyTopWindowPlatform(hintPackage = lastKnownForegroundAppPackage, hintClassName = className)
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                verifyTopWindowPlatform(hintPackage = lastKnownForegroundAppPackage)
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                val platform = ReelPlatform.fromPackageName(eventPackage)
                if (platform != null && !repository.shouldDisplayOverlayNow()) {
                    // Ensure HUD is visible immediately when scrolling inside IG/YT
                    ensurePlatformActiveOnInteraction(platform)
                    handleViewScrolled(event, platform)
                }
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val platform = ReelPlatform.fromPackageName(eventPackage)
                if (platform != null && !repository.shouldDisplayOverlayNow()) {
                    ensurePlatformActiveOnInteraction(platform)
                    handleWindowContentChanged(event, platform)
                }
            }
            else -> Unit
        }

        // -------------------------------------------------------------------------
        // PART 2: BLACKOUT SESSION ENFORCEMENT
        // -------------------------------------------------------------------------
        if (!repository.isSessionActive.value) return
        if (repository.checkAndExpireSessionIfNeeded()) {
            accessibilityOverlayManager?.removeOverlay()
            return
        }

        if (eventPackage.isBlank() || eventPackage == packageName) {
            return
        }

        if (AlarmWhitelist.isForbiddenBypassPackage(eventPackage)) {
            val prevUnlocked = repository.temporarilyUnlockedPackage.value
            repository.revokeTemporaryAppUnlock()
            if (prevUnlocked != null) {
                BlackoutApplication.getInstance().rootShellController.setPackageTemporarilyUnsuspendedAsync(prevUnlocked, unsuspended = false)
            }
            ejectFromForbiddenAppAndRelock()
            return
        }

        if (AlarmWhitelist.isSystemUiShadeOrControlCenter(eventPackage, className, null)) {
            if (repository.shouldDisplayOverlayNow()) {
                dismissSystemShadeAndDialogs()
                accessibilityOverlayManager?.bringOverlayToFront()
                val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                if (km?.isKeyguardLocked == true) {
                    BlackoutLockScreenActivity.launchIfBlocking(this)
                }
                return
            } else if (repository.temporarilyUnlockedPackage.value != null) {
                val lowerCls = className.lowercase()
                if (lowerCls.contains("quicksettings") ||
                    lowerCls.contains("controlcenter") ||
                    lowerCls.contains("recents") ||
                    lowerCls.contains("overview")
                ) {
                    repository.revokeTemporaryAppUnlock()
                    ejectFromForbiddenAppAndRelock()
                    return
                }
            }
        }

        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            return
        }

        // System Alarm Exemption Check
        val isAlarmPkg = AlarmWhitelist.isAlarmOrClockPackage(this, eventPackage)
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val isAlarmRingingMode = audioManager?.mode == AudioManager.MODE_RINGTONE

        if (isAlarmPkg) {
            repository.setAlarmWindowActive(true)
            accessibilityOverlayManager?.syncOverlayVisibility()
            BlackoutForegroundService.syncOverlayNow(this)
            return
        } else if (repository.isAlarmExemptionActive.value && !isAlarmRingingMode) {
            repository.setAlarmWindowActive(false)
            accessibilityOverlayManager?.syncOverlayVisibility()
            BlackoutForegroundService.syncOverlayNow(this)
        }

        // Strict Re-Blocking for Temporarily Unlocked Allowed App
        val unlockedPkg = repository.temporarilyUnlockedPackage.value
        if (unlockedPkg != null) {
            if (eventPackage == unlockedPkg) {
                return
            }

            if (AlarmWhitelist.isTransientInputOrSystemAuthPackage(this, eventPackage)) {
                return
            }

            val prevUnlocked = repository.temporarilyUnlockedPackage.value
            repository.revokeTemporaryAppUnlock()
            if (prevUnlocked != null) {
                BlackoutApplication.getInstance().rootShellController.setPackageTemporarilyUnsuspendedAsync(prevUnlocked, unsuspended = false)
            }
            ejectFromForbiddenAppAndRelock()
            return
        }

        if (repository.shouldDisplayOverlayNow()) {
            dismissSystemShadeAndDialogs()
            accessibilityOverlayManager?.syncOverlayVisibility()
            accessibilityOverlayManager?.bringOverlayToFront()
            BlackoutForegroundService.syncOverlayNow(this)
            BlackoutLockScreenActivity.launchIfBlocking(this)
        }
    }

    private fun ensurePlatformActiveOnInteraction(platform: ReelPlatform) {
        lastKnownForegroundAppPackage = platform.packageName
        if (reelRepository.activeForegroundPlatform.value != platform) {
            reelRepository.setActiveForegroundPlatform(platform)
        }
        if (appEntryGateManager?.isGateShowing() == true) return
        val isTempUnlocked = repository.temporarilyUnlockedPackage.value == platform.packageName
        if (!isTempUnlocked && approvedEntryPlatform != platform) {
            floatingReelHudManager?.hide()
            appEntryGateManager?.show(platform, reelRepository.todayBreakdownState.value.total)
            return
        }
        if (reelRepository.hudEnabled.value) {
            floatingReelHudManager?.show(platform, reelRepository.todayBreakdownState.value.total)
        }
    }

    /**
     * Clean, rock-solid top window verification based on the proven ReelCounter architecture:
     * - Checks interactive TYPE_APPLICATION windows for Instagram or YouTube.
     * - Detects if Launcher or Recents/Overview is focused on top.
     * - Never blinks on transient empty window reads or status bar ticks.
     */
    private fun verifyTopWindowPlatform(
        hintPackage: String? = null,
        hintClassName: String? = null
    ) {
        if (repository.shouldDisplayOverlayNow()) {
            clearActiveReelPlatformAndHideHud()
            return
        }

        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (km?.isKeyguardLocked == true) {
            clearActiveReelPlatformAndHideHud()
            return
        }

        val lowerHintCls = hintClassName?.lowercase().orEmpty()
        if (lowerHintCls.contains("recents") ||
            lowerHintCls.contains("overview") ||
            lowerHintCls.contains("quickstep")
        ) {
            clearActiveReelPlatformAndHideHud()
            return
        }

        var detectedPlatform: ReelPlatform? = null
        var foundAnyApplicationWindow = false
        var recentsOrLauncherFocused = false

        val interactiveWindows = try {
            windows
        } catch (_: Exception) {
            null
        }

        if (!interactiveWindows.isNullOrEmpty()) {
            for (window in interactiveWindows) {
                if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                    (window.isFocused || window.isActive)
                ) {
                    val root = try { window.root } catch (_: Exception) { null }
                    val pkg = root?.packageName?.toString()
                    @Suppress("DEPRECATION")
                    root?.recycle()

                    if (pkg.isNullOrBlank() || pkg == packageName) continue
                    if (AlarmWhitelist.isTransientInputOrSystemAuthPackage(this, pkg)) continue

                    foundAnyApplicationWindow = true

                    if (AlarmWhitelist.isLauncherPackage(this, pkg)) {
                        // User is on Home Launcher or Gesture Recents/Overview screen
                        recentsOrLauncherFocused = true
                        break
                    }

                    val matched = ReelPlatform.fromPackageName(pkg)
                    if (matched != null) {
                        detectedPlatform = matched
                        lastKnownForegroundAppPackage = pkg
                        break
                    } else if (window.isFocused) {
                        // A different non-IG/YT app is focused
                        detectedPlatform = null
                        lastKnownForegroundAppPackage = pkg
                        break
                    }
                }
            }
        }

        // Fallback to last known foreground package ONLY if windows list was temporarily unavailable
        if (!foundAnyApplicationWindow && !recentsOrLauncherFocused) {
            val fallbackPkg = hintPackage ?: lastKnownForegroundAppPackage
            if (!fallbackPkg.isNullOrEmpty()) {
                detectedPlatform = ReelPlatform.fromPackageName(fallbackPkg)
            }
        }

        if (recentsOrLauncherFocused) {
            detectedPlatform = null
        }

        val previousPlatform = reelRepository.activeForegroundPlatform.value
        if (detectedPlatform != null) {
            if (previousPlatform != detectedPlatform) {
                reelRepository.setActiveForegroundPlatform(detectedPlatform)
            }
            // Show 10-second Entry Card once per entry into IG/YT (unless Blackout session is temporarily unlocking it)
            val isTempUnlocked = repository.temporarilyUnlockedPackage.value == detectedPlatform.packageName
            if (!isTempUnlocked && approvedEntryPlatform != detectedPlatform) {
                floatingReelHudManager?.hide()
                appEntryGateManager?.show(
                    detectedPlatform,
                    reelRepository.todayBreakdownState.value.total
                )
            } else if (appEntryGateManager?.isGateShowing() != true && reelRepository.hudEnabled.value) {
                floatingReelHudManager?.show(
                    detectedPlatform,
                    reelRepository.todayBreakdownState.value.total
                )
            }
        } else {
            clearActiveReelPlatformAndHideHud()
        }
    }

    private fun clearActiveReelPlatformAndHideHud() {
        // Reset page indices and entry-gate approval when leaving IG/YT
        lastInstagramPageIndex = -1
        lastYoutubePageIndex = -1
        approvedEntryPlatform = null
        appEntryGateManager?.dismiss()
        if (reelRepository.activeForegroundPlatform.value != null) {
            reelRepository.setActiveForegroundPlatform(null)
        }
        floatingReelHudManager?.hide()
    }

    // -------------------------------------------------------------------------
    // REEL & SHORTS SWIPE DETECTION LOGIC
    // -------------------------------------------------------------------------
    private fun handleViewScrolled(event: AccessibilityEvent, platform: ReelPlatform) {
        val nowElapsed = SystemClock.elapsedRealtime()
        if (nowElapsed - lastRecordedSwipeElapsedMs < DEBOUNCE_COOLDOWN_MS) {
            return
        }

        if (isVerticalPagerOrReelScroll(event, platform)) {
            commitReelSwipe(platform, nowElapsed)
        }
    }

    private fun handleWindowContentChanged(event: AccessibilityEvent, platform: ReelPlatform) {
        val nowElapsed = SystemClock.elapsedRealtime()
        if (nowElapsed - lastRecordedSwipeElapsedMs < DEBOUNCE_COOLDOWN_MS) {
            return
        }

        val sourceNode = event.source ?: return
        try {
            val viewId = sourceNode.viewIdResourceName?.lowercase().orEmpty()
            val className = event.className?.toString()?.lowercase().orEmpty()

            val isReelContainerTransition = when (platform) {
                ReelPlatform.INSTAGRAM -> {
                    (viewId.contains("clips_viewer_view_pager") ||
                        viewId.contains("reel_viewer") ||
                        viewId.contains("clips_video_container")) &&
                        (event.contentChangeTypes and AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE != 0)
                }
                ReelPlatform.YOUTUBE -> {
                    (viewId.contains("reel_recycler") ||
                        viewId.contains("reel_player_page_container") ||
                        viewId.contains("shorts_page_container")) &&
                        (event.contentChangeTypes and AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE != 0)
                }
            }

            if (isReelContainerTransition && (className.contains("viewpager") || className.contains("recyclerview"))) {
                commitReelSwipe(platform, nowElapsed)
            }
        } finally {
            @Suppress("DEPRECATION")
            sourceNode.recycle()
        }
    }

    private fun isVerticalPagerOrReelScroll(
        event: AccessibilityEvent,
        platform: ReelPlatform
    ): Boolean {
        val className = event.className?.toString()?.lowercase().orEmpty()
        val deltaY = event.scrollDeltaY
        val deltaX = event.scrollDeltaX

        if (deltaX != 0 && abs(deltaX) > abs(deltaY)) {
            return false
        }

        val sourceNode = event.source
        var viewId = ""
        var isShortFormContext = false

        if (sourceNode != null) {
            try {
                viewId = sourceNode.viewIdResourceName?.lowercase().orEmpty()
                isShortFormContext = matchesShortFormViewId(viewId, platform) ||
                    hasShortFormAncestorOrChild(sourceNode, platform)
            } finally {
                @Suppress("DEPRECATION")
                sourceNode.recycle()
            }
        }

        val fromIndex = event.fromIndex
        val toIndex = event.toIndex
        val hasPageAdvance = fromIndex >= 0 && toIndex >= 0 && fromIndex != toIndex
        val hasSignificantVerticalDelta = abs(deltaY) >= MIN_VERTICAL_SCROLL_DELTA_PX

        val isPagerOrRecycler = className.contains("viewpager") ||
            className.contains("recyclerview") ||
            className.contains("scrollable")

        if (!isPagerOrRecycler && !isShortFormContext) {
            return false
        }

        if (hasPageAdvance) {
            when (platform) {
                ReelPlatform.INSTAGRAM -> {
                    if (toIndex == lastInstagramPageIndex && !hasSignificantVerticalDelta) {
                        return false
                    }
                    lastInstagramPageIndex = toIndex
                }
                ReelPlatform.YOUTUBE -> {
                    if (toIndex == lastYoutubePageIndex && !hasSignificantVerticalDelta) {
                        return false
                    }
                    lastYoutubePageIndex = toIndex
                }
            }
        }

        return when {
            isShortFormContext && (hasPageAdvance || hasSignificantVerticalDelta) -> true
            className.contains("viewpager") && (hasPageAdvance || hasSignificantVerticalDelta) -> true
            isPagerOrRecycler && hasPageAdvance && hasSignificantVerticalDelta -> true
            else -> false
        }
    }

    private fun matchesShortFormViewId(viewId: String, platform: ReelPlatform): Boolean {
        if (viewId.isEmpty()) return false
        return when (platform) {
            ReelPlatform.INSTAGRAM -> {
                viewId.contains("clips") ||
                    viewId.contains("reel") ||
                    viewId.contains("media_container")
            }
            ReelPlatform.YOUTUBE -> {
                viewId.contains("reel") ||
                    viewId.contains("shorts") ||
                    viewId.contains("slim_video")
            }
        }
    }

    private fun hasShortFormAncestorOrChild(
        node: AccessibilityNodeInfo,
        platform: ReelPlatform
    ): Boolean {
        var currentParent: AccessibilityNodeInfo? = node.parent
        var depth = 0
        while (currentParent != null && depth < 3) {
            val parentId = currentParent.viewIdResourceName?.lowercase().orEmpty()
            if (matchesShortFormViewId(parentId, platform)) {
                @Suppress("DEPRECATION")
                currentParent.recycle()
                return true
            }
            val nextParent = currentParent.parent
            @Suppress("DEPRECATION")
            currentParent.recycle()
            currentParent = nextParent
            depth++
        }
        return false
    }

    private fun commitReelSwipe(platform: ReelPlatform, elapsedMs: Long) {
        lastRecordedSwipeElapsedMs = elapsedMs
        reelRepository.recordReelSwipeAsync(platform = platform, count = 1)

        if (reelRepository.hapticsEnabled.value) {
            triggerClickHaptic()
        }
    }

    private fun triggerClickHaptic() {
        try {
            val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                manager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            if (vibrator == null || !vibrator.hasVibrator()) return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else {
                vibrator.vibrate(VibrationEffect.createOneShot(18L, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (_: Exception) {
        }
    }

    private fun ejectFromForbiddenAppAndRelock() {
        clearActiveReelPlatformAndHideHud()
        dismissSystemShadeAndDialogs()
        try {
            performGlobalAction(GLOBAL_ACTION_BACK)
            performGlobalAction(GLOBAL_ACTION_HOME)
        } catch (_: Throwable) {
        }
        accessibilityOverlayManager?.syncOverlayVisibility()
        accessibilityOverlayManager?.bringOverlayToFront()
        BlackoutForegroundService.syncOverlayNow(this)
        BlackoutLockScreenActivity.launchIfBlocking(this)
    }

    fun dismissSystemShadeAndDialogs() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
            } catch (_: Throwable) {
            }
        }
        BlackoutLockScreenActivity.collapseStatusBarPanel(this)
        BlackoutApplication.getInstance().rootShellController.collapseStatusBarViaRootAsync()
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return super.onKeyEvent(event)

        if (event.keyCode == KeyEvent.KEYCODE_HOME ||
            event.keyCode == KeyEvent.KEYCODE_APP_SWITCH
        ) {
            lastKnownForegroundAppPackage = null
            clearActiveReelPlatformAndHideHud()
        }

        if (!repository.isSessionActive.value) return super.onKeyEvent(event)
        if (repository.checkAndExpireSessionIfNeeded()) return super.onKeyEvent(event)

        if (!repository.shouldDisplayOverlayNow()) {
            if (event.keyCode == KeyEvent.KEYCODE_APP_SWITCH ||
                event.keyCode == KeyEvent.KEYCODE_SETTINGS
            ) {
                repository.revokeTemporaryAppUnlock()
                ejectFromForbiddenAppAndRelock()
                return true
            }
            return super.onKeyEvent(event)
        }

        return when (event.keyCode) {
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_APP_SWITCH,
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_ASSIST,
            KeyEvent.KEYCODE_VOICE_ASSIST,
            KeyEvent.KEYCODE_SEARCH,
            KeyEvent.KEYCODE_SETTINGS,
            KeyEvent.KEYCODE_NOTIFICATION,
            KeyEvent.KEYCODE_ALL_APPS -> {
                dismissSystemShadeAndDialogs()
                true
            }
            else -> super.onKeyEvent(event)
        }
    }

    override fun onInterrupt() {
        clearActiveReelPlatformAndHideHud()
        accessibilityOverlayManager?.removeOverlay()
    }

    override fun onDestroy() {
        instance = null
        clearActiveReelPlatformAndHideHud()
        try {
            unregisterReceiver(screenAndLockReceiver)
        } catch (_: Throwable) {
        }
        accessibilityOverlayManager?.removeOverlay()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val DEBOUNCE_COOLDOWN_MS = 1000L
        private const val MIN_VERTICAL_SCROLL_DELTA_PX = 160

        @Volatile
        private var instance: BlackoutAccessibilityService? = null

        fun syncAccessibilityOverlay() {
            instance?.accessibilityOverlayManager?.syncOverlayVisibility()
            instance?.accessibilityOverlayManager?.bringOverlayToFront()
        }

        fun removeAccessibilityOverlay() {
            instance?.accessibilityOverlayManager?.removeOverlay()
        }
    }
}
