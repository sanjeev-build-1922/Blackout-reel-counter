package com.blackout.focus.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.blackout.focus.data.model.AllowedNotificationItem
import com.blackout.focus.util.AlarmWhitelist
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Single source of truth for Blackout session persistence, allowed apps,
 * active allowed app notifications, temporary biometric unlock state, and alarm exemptions.
 *
 * Uses Device-Protected Storage context on Android N+ so BootCompletedReceiver and
 * BlackoutAccessibilityService can read the persisted targetEndTimeMillis even during Direct Boot
 * (LOCKED_BOOT_COMPLETED) before the user unlocks the device.
 */
class BlackoutRepository private constructor(context: Context) {

    private val storageContext: Context = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        context.applicationContext.createDeviceProtectedStorageContext()
    } else {
        context.applicationContext
    }

    private val prefs: SharedPreferences =
        storageContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _targetEndTimeMillis = MutableStateFlow(prefs.getLong(KEY_TARGET_END_TIME_MILLIS, 0L))
    val targetEndTimeMillis: StateFlow<Long> = _targetEndTimeMillis.asStateFlow()

    private val _isSessionActive = MutableStateFlow(prefs.getBoolean(KEY_IS_SESSION_ACTIVE, false))
    val isSessionActive: StateFlow<Boolean> = _isSessionActive.asStateFlow()

    private val _allowedPackages = MutableStateFlow<Set<String>>(
        (prefs.getStringSet(KEY_ALLOWED_PACKAGES, emptySet())?.toSet() ?: emptySet())
            .filterNot { AlarmWhitelist.isForbiddenBypassPackage(it) }
            .toSet()
    )
    val allowedPackages: StateFlow<Set<String>> = _allowedPackages.asStateFlow()

    private val _activeAllowedNotifications = MutableStateFlow<List<AllowedNotificationItem>>(emptyList())
    val activeAllowedNotifications: StateFlow<List<AllowedNotificationItem>> =
        _activeAllowedNotifications.asStateFlow()

    private val _temporarilyUnlockedPackage = MutableStateFlow<String?>(null)
    val temporarilyUnlockedPackage: StateFlow<String?> = _temporarilyUnlockedPackage.asStateFlow()

    private val _isBiometricPromptInProgress = MutableStateFlow(false)
    val isBiometricPromptInProgress: StateFlow<Boolean> = _isBiometricPromptInProgress.asStateFlow()

    private val _isAlarmWindowActive = MutableStateFlow(false)
    private val _isAlarmNotificationActive = MutableStateFlow(false)

    private val _isAlarmExemptionActive = MutableStateFlow(false)
    val isAlarmExemptionActive: StateFlow<Boolean> = _isAlarmExemptionActive.asStateFlow()

    /**
     * Reconciles persisted state on app/service startup or boot.
     * Returns true if a valid unexpired session is active and must be enforced.
     */
    @Synchronized
    fun reconcileSessionStateOnStartup(): Boolean {
        val savedActive = prefs.getBoolean(KEY_IS_SESSION_ACTIVE, false)
        val savedEndTime = prefs.getLong(KEY_TARGET_END_TIME_MILLIS, 0L)
        val now = System.currentTimeMillis()

        return if (savedActive && savedEndTime > now) {
            _targetEndTimeMillis.value = savedEndTime
            _isSessionActive.value = true
            true
        } else {
            if (savedActive || savedEndTime != 0L) {
                completeBlackoutSession()
            }
            false
        }
    }

    /**
     * Starts a new Blackout session using exact wall-clock target time calculation:
     * targetEndTimeMillis = System.currentTimeMillis() + durationMillis
     */
    @Synchronized
    fun startBlackoutSession(durationMillis: Long): Long {
        return startBlackoutSessionWithTargetEnd(System.currentTimeMillis() + durationMillis)
    }

    @Synchronized
    fun startBlackoutSessionWithTargetEnd(targetEndMillis: Long): Long {
        val targetEnd = targetEndMillis
        prefs.edit()
            .putLong(KEY_TARGET_END_TIME_MILLIS, targetEnd)
            .putBoolean(KEY_IS_SESSION_ACTIVE, true)
            .commit()

        _temporarilyUnlockedPackage.value = null
        _isBiometricPromptInProgress.value = false
        _isAlarmWindowActive.value = false
        _isAlarmNotificationActive.value = false
        _isAlarmExemptionActive.value = false
        _activeAllowedNotifications.value = emptyList()

        _targetEndTimeMillis.value = targetEnd
        _isSessionActive.value = true
        return targetEnd
    }

    /**
     * Checks if the target end time has been reached.
     * Returns true if the session has expired and was completed.
     */
    @Synchronized
    fun checkAndExpireSessionIfNeeded(): Boolean {
        if (!_isSessionActive.value) return true
        val target = _targetEndTimeMillis.value
        if (target <= 0L || System.currentTimeMillis() >= target) {
            completeBlackoutSession()
            return true
        }
        return false
    }

    /**
     * Completes and clears the active Blackout session.
     */
    @Synchronized
    fun completeBlackoutSession() {
        prefs.edit()
            .putLong(KEY_TARGET_END_TIME_MILLIS, 0L)
            .putBoolean(KEY_IS_SESSION_ACTIVE, false)
            .commit()

        _isSessionActive.value = false
        _targetEndTimeMillis.value = 0L
        _temporarilyUnlockedPackage.value = null
        _isBiometricPromptInProgress.value = false
        _isAlarmWindowActive.value = false
        _isAlarmNotificationActive.value = false
        _isAlarmExemptionActive.value = false
        _activeAllowedNotifications.value = emptyList()
    }

    /**
     * Updates whether an installed package is in the Allowed Apps set.
     * Forbidden bypass packages (Settings, PackageInstaller, Security Managers) are strictly rejected.
     */
    @Synchronized
    fun setPackageAllowed(packageName: String, isAllowed: Boolean) {
        if (AlarmWhitelist.isForbiddenBypassPackage(packageName)) return
        val updated = _allowedPackages.value.toMutableSet()
        if (isAllowed) {
            updated.add(packageName)
        } else {
            updated.remove(packageName)
            _activeAllowedNotifications.update { list ->
                list.filterNot { it.packageName == packageName }
            }
        }
        prefs.edit()
            .putStringSet(KEY_ALLOWED_PACKAGES, updated)
            .apply()
        _allowedPackages.value = updated
    }

    /**
     * Records that an allowed app has posted a notification.
     * Stores ONLY packageName and appName (no message or sender).
     */
    fun onAllowedAppNotificationPosted(packageName: String, appName: String) {
        if (!_isSessionActive.value) return
        if (AlarmWhitelist.isForbiddenBypassPackage(packageName)) return
        if (!_allowedPackages.value.contains(packageName)) return

        val now = System.currentTimeMillis()
        _activeAllowedNotifications.update { current ->
            val filtered = current.filterNot { it.packageName == packageName }
            (listOf(AllowedNotificationItem(packageName, appName, now)) + filtered)
        }
    }

    /**
     * Removes an allowed app from the displayed notification list when its notification is cleared
     * or when the user unlocks and opens that app.
     */
    fun onAllowedAppNotificationRemoved(packageName: String) {
        _activeAllowedNotifications.update { current ->
            current.filterNot { it.packageName == packageName }
        }
    }

    /**
     * Marks whether the native BiometricPrompt host activity is currently visible.
     */
    fun setBiometricPromptInProgress(inProgress: Boolean) {
        _isBiometricPromptInProgress.value = inProgress
    }

    /**
     * Grants temporary unlock for a specific allowed package after a 4-second hold + biometric success.
     */
    fun grantTemporaryAppUnlock(packageName: String) {
        if (!_isSessionActive.value) return
        if (AlarmWhitelist.isForbiddenBypassPackage(packageName)) return
        _isBiometricPromptInProgress.value = false
        _temporarilyUnlockedPackage.value = packageName
        onAllowedAppNotificationRemoved(packageName)
    }

    /**
     * Immediately revokes the temporary app unlock when the user exits the allowed app
     * (presses Home, Recents, Back to launcher, locks the screen, or switches to any other app).
     */
    fun revokeTemporaryAppUnlock() {
        if (_temporarilyUnlockedPackage.value != null) {
            _temporarilyUnlockedPackage.value = null
        }
    }

    fun setAlarmWindowActive(active: Boolean) {
        _isAlarmWindowActive.value = active
        recomputeAlarmExemption()
    }

    fun setAlarmNotificationActive(active: Boolean) {
        _isAlarmNotificationActive.value = active
        recomputeAlarmExemption()
    }

    private fun recomputeAlarmExemption() {
        _isAlarmExemptionActive.value = _isAlarmWindowActive.value || _isAlarmNotificationActive.value
    }

    /**
     * Returns true if the full-screen solid black overlay should currently be drawn on screen.
     */
    fun shouldDisplayOverlayNow(): Boolean {
        val now = System.currentTimeMillis()
        val active = _isSessionActive.value && (_targetEndTimeMillis.value > now)
        if (!active) return false
        if (_isAlarmExemptionActive.value) return false
        if (_isBiometricPromptInProgress.value) return false
        if (_temporarilyUnlockedPackage.value != null) return false
        return true
    }

    companion object {
        private const val PREFS_NAME = "blackout_focus_prefs"
        private const val KEY_TARGET_END_TIME_MILLIS = "target_end_time_millis"
        private const val KEY_IS_SESSION_ACTIVE = "is_session_active"
        private const val KEY_ALLOWED_PACKAGES = "allowed_packages"

        @Volatile
        private var INSTANCE: BlackoutRepository? = null

        fun getInstance(context: Context): BlackoutRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BlackoutRepository(context).also { INSTANCE = it }
            }
        }
    }
}
