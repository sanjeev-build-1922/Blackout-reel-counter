package com.blackout.focus.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.AlarmClock
import android.view.inputmethod.InputMethodManager

/**
 * Maintains:
 * 1. Whitelist of system clock/alarm packages (for alarm exemption).
 * 2. Strict Blacklist of Settings / PackageInstaller / System Manager packages that can NEVER be
 *    unlocked or added to Allowed Apps (prevents bypassing via Control Centre -> Settings -> Force Stop).
 * 3. Detection helpers for Control Centre / Notification Shade / SystemUI panels.
 */
object AlarmWhitelist {

    private val KNOWN_CLOCK_AND_ALARM_PACKAGES = setOf(
        "com.google.android.deskclock",       // Google Clock / Pixel
        "com.android.deskclock",              // AOSP Clock
        "com.sec.android.app.clockpackage",   // Samsung Clock
        "com.oneplus.deskclock",              // OnePlus Clock
        "com.coloros.alarmclock",             // OPPO / ColorOS Clock
        "com.oplus.alarmclock",               // OPLUS / Realme Clock
        "com.miui.clock",                     // Xiaomi / MIUI Clock
        "com.asus.deskclock",                 // ASUS Clock
        "com.huawei.deskclock",               // Huawei Clock
        "com.hihonor.deskclock",              // Honor Clock
        "com.htc.android.worldclock",         // HTC Clock
        "com.sonyericsson.organizer",         // Sony Clock
        "com.sonymobile.organizer",           // Sony Xperia Clock
        "com.lge.clock",                      // LG Clock
        "com.motorola.timeweatherwidget",     // Motorola Clock Widget
        "com.motorola.deskclock",             // Motorola DeskClock
        "com.lenovo.deskclock",               // Lenovo Clock
        "com.nubia.deskclock.preset",         // Nubia Clock
        "com.vivo.alarmclock",                // Vivo Clock
        "com.bbk.alarmclock"                  // BBK / Vivo Clock
    )

    /**
     * Packages that must NEVER be accessible during an active Blackout session — even if the user
     * tries to launch them from Control Centre, Quick Settings, Assistant, or an Allowed App.
     */
    private val STRICTLY_FORBIDDEN_BYPASS_PACKAGES = setOf(
        "com.android.settings",
        "com.samsung.accessibility",
        "com.samsung.android.setting.multisound",
        "com.samsung.android.lool",
        "com.miui.securitycenter",
        "com.miui.powerkeeper",
        "com.coloros.safecenter",
        "com.oplus.safecenter",
        "com.coloros.securitypermission",
        "com.vivo.permissionmanager",
        "com.iqoo.secure",
        "com.huawei.systemmanager",
        "com.oneplus.security",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.google.android.apps.wellbeing",
        "com.samsung.android.forest"
    )

    private val SYSTEM_BIOMETRIC_PACKAGES = setOf(
        "com.android.biometric",
        "com.samsung.android.biometrics.app.setting"
    )

    /**
     * Returns true if the package is Settings, Package Installer, or an OEM Security Manager
     * that could be used to force-stop or disable Blackout.
     */
    fun isForbiddenBypassPackage(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        if (STRICTLY_FORBIDDEN_BYPASS_PACKAGES.contains(packageName)) return true
        val lower = packageName.lowercase()
        return lower == "com.android.settings" ||
            lower.endsWith(".settings") ||
            lower.contains("packageinstaller") ||
            lower.contains("securitycenter") ||
            lower.contains("safecenter") ||
            lower.contains("permissioncontroller") ||
            lower.contains("permissionmanager")
    }

    /**
     * Checks whether the given package is a known or dynamically resolved system alarm/clock app.
     */
    fun isAlarmOrClockPackage(context: Context, packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        if (isForbiddenBypassPackage(packageName)) return false
        if (KNOWN_CLOCK_AND_ALARM_PACKAGES.contains(packageName)) return true

        val lower = packageName.lowercase()
        if (lower.contains("deskclock") || lower.contains("alarmclock") || lower.endsWith(".clockpackage")) {
            return true
        }

        return try {
            val pm = context.packageManager
            val alarmIntent = Intent(AlarmClock.ACTION_SHOW_ALARMS).setPackage(packageName)
            val resolved = pm.queryIntentActivities(alarmIntent, PackageManager.MATCH_DEFAULT_ONLY)
            resolved.isNotEmpty()
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Checks if the package is an enabled soft keyboard (IME) or system biometric dialog
     * so typing inside a temporarily unlocked app does not falsely trigger app-exit re-blocking.
     * Note: Settings and PermissionController are explicitly excluded so users cannot escape via keyboard settings!
     */
    fun isTransientInputOrSystemAuthPackage(context: Context, packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        if (isForbiddenBypassPackage(packageName)) return false
        if (SYSTEM_BIOMETRIC_PACKAGES.contains(packageName)) return true

        return try {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            val imePackages = imm?.enabledInputMethodList?.map { it.packageName }?.toSet() ?: emptySet()
            imePackages.contains(packageName)
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Checks if the package is a Home Launcher application.
     */
    fun isLauncherPackage(context: Context, packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        return try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolveInfos = context.packageManager.queryIntentActivities(
                intent,
                PackageManager.MATCH_DEFAULT_ONLY
            )
            resolveInfos.any { it.activityInfo?.packageName == packageName }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Detects if an AccessibilityEvent className or window title belongs to Control Centre,
     * Quick Settings, Notification Shade, Status Bar, Power Menu, or Recents in SystemUI.
     */
    fun isSystemUiShadeOrControlCenter(packageName: String?, className: String?, windowTitle: String?): Boolean {
        if (packageName == "com.android.systemui" || packageName == "com.miui.systemui.plugin" ||
            packageName == "miui.systemui.plugin"
        ) {
            return true
        }
        val cls = className?.lowercase().orEmpty()
        val title = windowTitle?.lowercase().orEmpty()
        return cls.contains("statusbar") ||
            cls.contains("quicksettings") ||
            cls.contains("notificationshade") ||
            cls.contains("controlcenter") ||
            cls.contains("qs") ||
            cls.contains("globalactions") ||
            cls.contains("recents") ||
            title.contains("notification") ||
            title.contains("quick settings") ||
            title.contains("control center") ||
            title.contains("control centre")
    }
}
