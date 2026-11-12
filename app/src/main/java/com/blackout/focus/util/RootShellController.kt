package com.blackout.focus.util

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.blackout.focus.receiver.BlackoutDeviceAdminReceiver
import com.blackout.focus.service.BlackoutAccessibilityService
import com.blackout.focus.service.BlackoutNotificationListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Root & Custom ROM Peak Enforcer (Magisk / KernelSU / APatch):
 * 1. Auto-Permissions & Doze Whitelist (`pm grant`, `appops`, `settings put secure`, `deviceidle whitelist`).
 * 2. OS-Level Package Suspend (`pm suspend` / `pm unsuspend`) during active Blackout sessions.
 * 3. Root Device Owner Promotion (`dpm set-device-owner`) + Kiosk Lock Task Mode (`LOCK_TASK_FEATURE_NONE`).
 * 4. System-Level Status Bar / Control Centre Collapse & Disable via root shell (`cmd statusbar`).
 * 5. Magisk / KernelSU Boot Enforcer Script (`/data/adb/service.d/blackout_enforcer.sh`) so Safe Mode or
 *    reboot cannot bypass an active Blackout session.
 */
class RootShellController private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val storageContext: Context = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        appContext.createDeviceProtectedStorageContext()
    } else {
        appContext
    }

    private val prefs: SharedPreferences =
        storageContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _isRootAvailable = MutableStateFlow(false)
    val isRootAvailable: StateFlow<Boolean> = _isRootAvailable.asStateFlow()

    private val _rootModeEnabled = MutableStateFlow(prefs.getBoolean(KEY_ROOT_MODE_ENABLED, true))
    val rootModeEnabled: StateFlow<Boolean> = _rootModeEnabled.asStateFlow()

    private val _suspendAppsOnBlackout = MutableStateFlow(prefs.getBoolean(KEY_SUSPEND_APPS, true))
    val suspendAppsOnBlackout: StateFlow<Boolean> = _suspendAppsOnBlackout.asStateFlow()

    private val _kioskLockTaskEnabled = MutableStateFlow(prefs.getBoolean(KEY_KIOSK_LOCK_TASK, true))
    val kioskLockTaskEnabled: StateFlow<Boolean> = _kioskLockTaskEnabled.asStateFlow()

    private val _bootServiceScriptInstalled = MutableStateFlow(prefs.getBoolean(KEY_BOOT_SCRIPT_INSTALLED, false))
    val bootServiceScriptInstalled: StateFlow<Boolean> = _bootServiceScriptInstalled.asStateFlow()

    private val _rootStatusMessage = MutableStateFlow("Checking root access...")
    val rootStatusMessage: StateFlow<String> = _rootStatusMessage.asStateFlow()

    /**
     * Executes a list of shell commands inside a single `su` process with a strict timeout.
     */
    suspend fun executeSuCommands(commands: List<String>, timeoutSeconds: Long = 8L): Boolean {
        if (commands.isEmpty()) return true
        return withContext(Dispatchers.IO) {
            var process: Process? = null
            try {
                process = ProcessBuilder("su").redirectErrorStream(true).start()
                DataOutputStream(process.outputStream).use { os ->
                    for (cmd in commands) {
                        os.writeBytes("$cmd\n")
                    }
                    os.writeBytes("exit\n")
                    os.flush()
                }
                val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    false
                } else {
                    process.exitValue() == 0
                }
            } catch (_: Throwable) {
                try {
                    process?.destroyForcibly()
                } catch (_: Throwable) {
                }
                false
            }
        }
    }

    /**
     * Checks if Magisk / KernelSU / APatch root access is granted.
     */
    fun checkAndInitializeRootAsync(onComplete: (() -> Unit)? = null) {
        scope.launch {
            val hasRoot = checkRootAccessInternal()
            _isRootAvailable.value = hasRoot
            if (hasRoot) {
                _rootStatusMessage.value = "Root active (Magisk / KernelSU)"
                if (_rootModeEnabled.value) {
                    autoGrantPermissionsAndBatteryImmunityInternal()
                }
            } else {
                _rootStatusMessage.value = "Root not detected (Standard mode active)"
            }
            onComplete?.let {
                withContext(Dispatchers.Main) { it.invoke() }
            }
        }
    }

    private suspend fun checkRootAccessInternal(): Boolean = withContext(Dispatchers.IO) {
        var process: Process? = null
        try {
            process = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
            val finished = process.waitFor(4L, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return@withContext false
            }
            val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            output.contains("uid=0")
        } catch (_: Throwable) {
            try {
                process?.destroyForcibly()
            } catch (_: Throwable) {
            }
            false
        }
    }

    /**
     * CAPABILITY 1 & 3 & 5:
     * Grants Overlay, Accessibility, Notification Listener, Exact Alarm, Doze Whitelist,
     * promotes Blackout to Device Admin / Device Owner for Kiosk Lock Task, and installs
     * the `/data/adb/service.d/blackout_enforcer.sh` boot script.
     */
    fun applyFullRootBoostAsync(onDone: (() -> Unit)? = null) {
        scope.launch {
            val hasRoot = checkRootAccessInternal()
            _isRootAvailable.value = hasRoot
            if (!hasRoot) {
                _rootStatusMessage.value = "Root permission denied in Magisk / KernelSU"
                onDone?.let { withContext(Dispatchers.Main) { it() } }
                return@launch
            }
            autoGrantPermissionsAndBatteryImmunityInternal()
            installMagiskBootEnforcerScriptInternal()
            _rootStatusMessage.value = "All 5 Root Capabilities Active"
            onDone?.let { withContext(Dispatchers.Main) { it() } }
        }
    }

    private suspend fun autoGrantPermissionsAndBatteryImmunityInternal() {
        val pkg = appContext.packageName
        val accService = "$pkg/${BlackoutAccessibilityService::class.java.name}"
        val notifService = "$pkg/${BlackoutNotificationListenerService::class.java.name}"
        val adminReceiver = "$pkg/${BlackoutDeviceAdminReceiver::class.java.name}"

        val commands = mutableListOf(
            // 1. Grant AppOps & Runtime Permissions silently
            "appops set $pkg SYSTEM_ALERT_WINDOW allow",
            "appops set $pkg SCHEDULE_EXACT_ALARM allow",
            "appops set $pkg RUN_IN_BACKGROUND allow",
            "appops set $pkg RUN_ANY_IN_BACKGROUND allow",
            "pm grant $pkg android.permission.CAMERA",
            "pm grant $pkg android.permission.POST_NOTIFICATIONS",
            // 2. Whitelist from Doze & Custom ROM battery killers
            "dumpsys deviceidle whitelist +$pkg",
            // 3. Enable Accessibility Service & Notification Listener automatically
            " curr_acc=\$(settings get secure enabled_accessibility_services); if [ \"\$curr_acc\" = \"null\" ] || [ -z \"\$curr_acc\" ]; then settings put secure enabled_accessibility_services \"\$accService\"; elif ! echo \"\$curr_acc\" | grep -q \"\$accService\"; then settings put secure enabled_accessibility_services \"\$curr_acc:\$accService\"; fi ",
            "settings put secure accessibility_enabled 1",
            " curr_notif=\$(settings get secure enabled_notification_listeners); if [ \"\$curr_notif\" = \"null\" ] || [ -z \"\$curr_notif\" ]; then settings put secure enabled_notification_listeners \"\$notifService\"; elif ! echo \"\$curr_notif\" | grep -q \"\$notifService\"; then settings put secure enabled_notification_listeners \"\$curr_notif:\$notifService\"; fi ",
            // 4. Enable Device Admin & attempt Device Owner promotion for Kiosk Lock Task Mode
            "dpm set-active-admin $adminReceiver",
            "dpm set-device-owner $adminReceiver"
        )

        executeSuCommands(commands, timeoutSeconds = 10L)
        configureLockTaskPackagesIfDeviceOwner()
    }

    fun configureLockTaskPackagesIfDeviceOwner() {
        try {
            val dpm = appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return
            val admin = BlackoutDeviceAdminReceiver.getComponentName(appContext)
            if (dpm.isDeviceOwnerApp(appContext.packageName)) {
                dpm.setLockTaskPackages(admin, arrayOf(appContext.packageName))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    // Disable Status Bar, Notification Shade, Home, Recents, and Global Actions (Power Menu) in LockTask
                    dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
                }
            }
        } catch (_: Throwable) {
        }
    }

    fun isLockTaskPermitted(): Boolean {
        return try {
            val dpm = appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            dpm?.isLockTaskPermitted(appContext.packageName) == true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * CAPABILITY 5:
     * Installs a boot enforcer script into `/data/adb/service.d/blackout_enforcer.sh`
     * (supported natively by both Magisk and KernelSU) so that on every boot, Accessibility
     * and the Blackout Foreground Service are automatically enforced.
     */
    private suspend fun installMagiskBootEnforcerScriptInternal() {
        val pkg = appContext.packageName
        val accService = "$pkg/${BlackoutAccessibilityService::class.java.name}"
        val scriptPath = "/data/adb/service.d/blackout_enforcer.sh"

        val scriptCommands = listOf(
            "mkdir -p /data/adb/service.d",
            "cat << 'EOF' > $scriptPath\n" +
                "#!/system/bin/sh\n" +
                "until [ \"\$(getprop sys.boot_completed)\" = \"1\" ]; do sleep 2; done\n" +
                "appops set \$pkg SYSTEM_ALERT_WINDOW allow\n" +
                "dumpsys deviceidle whitelist +\$pkg\n" +
                "curr_acc=\$(settings get secure enabled_accessibility_services)\n" +
                "if [ \"\$curr_acc\" = \"null\" ] || [ -z \"\$curr_acc\" ]; then\n" +
                "  settings put secure enabled_accessibility_services \"\$accService\"\n" +
                "elif ! echo \"\$curr_acc\" | grep -q \"\$accService\"; then\n" +
                "  settings put secure enabled_accessibility_services \"\$curr_acc:\$accService\"\n" +
                "fi\n" +
                "settings put secure accessibility_enabled 1\n" +
                "am start-foreground-service -n \$pkg/\$pkg.service.BlackoutForegroundService -a com.blackout.focus.action.START_OR_RESUME\n" +
                "EOF",
            "chmod 755 $scriptPath"
        )

        val ok = executeSuCommands(scriptCommands, timeoutSeconds = 8L)
        if (ok) {
            prefs.edit().putBoolean(KEY_BOOT_SCRIPT_INSTALLED, true).apply()
            _bootServiceScriptInstalled.value = true
        }
    }

    /**
     * CAPABILITY 2 & 4:
     * Called when a Blackout session starts:
     * - Suspends distracting packages at the OS level using `pm suspend <pkg>`
     * - Collapses and locks the status bar / Control Centre via root shell.
     */
    fun enforceBlackoutStartViaRootAsync(temporarilyUnlockedPkg: String? = null) {
        if (!_rootModeEnabled.value) return
        scope.launch {
            if (!_isRootAvailable.value && !checkRootAccessInternal()) return@launch
            _isRootAvailable.value = true

            val cmds = mutableListOf(
                "cmd statusbar collapse",
                "settings put global policy_control immersive.full=*"
            )

            if (_suspendAppsOnBlackout.value) {
                val targets = resolveSuspendablePackages(excludePackage = temporarilyUnlockedPkg)
                if (targets.isNotEmpty()) {
                    saveSuspendedPackages(targets)
                    for (pkg in targets) {
                        cmds.add("pm suspend $pkg")
                    }
                }
            }
            executeSuCommands(cmds, timeoutSeconds = 12L)
        }
    }

    /**
     * Temporarily unsuspends a single Allowed App when the user passes the 4-second hold + BiometricPrompt,
     * then re-suspends it as soon as they exit that app!
     */
    fun setPackageTemporarilyUnsuspendedAsync(packageName: String, unsuspended: Boolean) {
        if (!_rootModeEnabled.value || !_suspendAppsOnBlackout.value) return
        scope.launch {
            if (!_isRootAvailable.value) return@launch
            val cmd = if (unsuspended) "pm unsuspend $packageName" else "pm suspend $packageName"
            executeSuCommands(listOf(cmd), timeoutSeconds = 5L)
        }
    }

    /**
     * Called when the Blackout timer finishes:
     * - Unsuspends all packages that were suspended by Blackout (`pm unsuspend <pkg>`)
     * - Restores normal status bar / immersive policy control.
     */
    fun restoreAllOnBlackoutCompleteAsync() {
        scope.launch {
            val previouslySuspended = getSavedSuspendedPackages()
            val allCandidateTargets = resolveSuspendablePackages(excludePackage = null)
            val toUnsuspend = (previouslySuspended + allCandidateTargets).distinct()

            val cmds = mutableListOf(
                "settings put global policy_control null"
            )
            for (pkg in toUnsuspend) {
                cmds.add("pm unsuspend $pkg")
            }
            executeSuCommands(cmds, timeoutSeconds = 12L)
            clearSavedSuspendedPackages()
        }
    }

    /**
     * Collapses the Control Centre / Notification Shade at root level (`cmd statusbar collapse`).
     */
    fun collapseStatusBarViaRootAsync() {
        if (!_rootModeEnabled.value || !_isRootAvailable.value) return
        scope.launch {
            executeSuCommands(listOf("cmd statusbar collapse"), timeoutSeconds = 3L)
        }
    }

    private fun resolveSuspendablePackages(excludePackage: String?): Set<String> {
        val result = mutableSetOf<String>()
        // Always include Instagram & YouTube if installed
        result.add("com.instagram.android")
        result.add("com.google.android.youtube")

        try {
            val pm = appContext.packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = pm.queryIntentActivities(mainIntent, PackageManager.MATCH_ALL)
            for (info in resolveInfos) {
                val ai = info.activityInfo?.applicationInfo ?: continue
                val pkg = ai.packageName ?: continue
                if (pkg == appContext.packageName) continue
                if (pkg == excludePackage) continue
                if (AlarmWhitelist.isAlarmOrClockPackage(appContext, pkg)) continue
                if (AlarmWhitelist.isLauncherPackage(appContext, pkg)) continue
                if (AlarmWhitelist.isTransientInputOrSystemAuthPackage(appContext, pkg)) continue
                if (pkg.startsWith("com.android.dialer") || pkg.startsWith("com.google.android.dialer")) continue

                val isUserInstalled = (ai.flags and ApplicationInfo.FLAG_SYSTEM) == 0
                if (isUserInstalled || pkg == "com.instagram.android" || pkg == "com.google.android.youtube" ||
                    pkg == "com.android.chrome"
                ) {
                    result.add(pkg)
                }
            }
        } catch (_: Throwable) {
        }

        if (excludePackage != null) {
            result.remove(excludePackage)
        }
        return result
    }

    private fun saveSuspendedPackages(pkgs: Set<String>) {
        val merged = (getSavedSuspendedPackages() + pkgs).toSet()
        prefs.edit().putStringSet(KEY_SUSPENDED_PACKAGES_SET, merged).commit()
    }

    private fun getSavedSuspendedPackages(): Set<String> {
        return prefs.getStringSet(KEY_SUSPENDED_PACKAGES_SET, emptySet())?.toSet() ?: emptySet()
    }

    private fun clearSavedSuspendedPackages() {
        prefs.edit().remove(KEY_SUSPENDED_PACKAGES_SET).commit()
    }

    fun setRootModeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ROOT_MODE_ENABLED, enabled).apply()
        _rootModeEnabled.value = enabled
        if (enabled) {
            applyFullRootBoostAsync()
        } else {
            restoreAllOnBlackoutCompleteAsync()
        }
    }

    fun setSuspendAppsOnBlackout(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SUSPEND_APPS, enabled).apply()
        _suspendAppsOnBlackout.value = enabled
        if (!enabled) {
            restoreAllOnBlackoutCompleteAsync()
        }
    }

    fun setKioskLockTaskEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_KIOSK_LOCK_TASK, enabled).apply()
        _kioskLockTaskEnabled.value = enabled
    }

    companion object {
        private const val PREFS_NAME = "blackout_root_prefs"
        private const val KEY_ROOT_MODE_ENABLED = "root_mode_enabled"
        private const val KEY_SUSPEND_APPS = "suspend_apps_on_blackout"
        private const val KEY_KIOSK_LOCK_TASK = "kiosk_lock_task_enabled"
        private const val KEY_BOOT_SCRIPT_INSTALLED = "boot_script_installed"
        private const val KEY_SUSPENDED_PACKAGES_SET = "suspended_packages_set"

        @Volatile
        private var INSTANCE: RootShellController? = null

        fun getInstance(context: Context): RootShellController {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: RootShellController(context).also { INSTANCE = it }
            }
        }
    }
}
