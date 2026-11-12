# Blackout (`com.blackout.focus`)

A strict, minimalist, timer-based distraction-blocking Android application built with **Kotlin**, **Jetpack Compose**, and **MVVM** architecture (Min SDK 26, Target SDK 34).

## Core Architecture & Engineering Highlights

1. **Setup Screen (`ui/SetupScreen.kt` & `ui/MainViewModel.kt`)**
   - Pure black (`#000000`) and white (`#FFFFFF`) monospace UI with zero animations or gradients.
   - Real-time status indicators and deep-link toggles for required system permissions:
     - `SYSTEM_ALERT_WINDOW` (`Settings.ACTION_MANAGE_OVERLAY_PERMISSION`)
     - `BIND_ACCESSIBILITY_SERVICE` (`Settings.ACTION_ACCESSIBILITY_SETTINGS`)
     - `BIND_NOTIFICATION_LISTENER_SERVICE` (`Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS`)
   - **Allowed Apps Selector**: Queries installed launcher applications and persists the user's allowed package list in `SharedPreferences`.
   - **Timer Presets**: `15m`, `30m`, `1h`, `2h`, and `Custom` (in minutes).

2. **Full-Screen Blackout Overlay (`overlay/BlackoutOverlayManager.kt` & `ui/overlay/BlackoutOverlayScreen.kt`)**
   - Drawn over all windows via `WindowManager` using `WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY` and a custom `OverlayLifecycleOwner` (`LifecycleOwner`, `ViewModelStoreOwner`, `SavedStateRegistryOwner`).
   - **Top Center**: Real-time wall-clock digital clock (`hh:mm a`, e.g., `02:15 PM`).
   - **Center**: Displays **only** the application's name in plain white text when a notification arrives from an Allowed App (never displays message body or sender).
   - **Bottom Center**: Hardware camera flashlight toggle (`FLASHLIGHT: ON` / `FLASHLIGHT: OFF`) managed by `FlashlightController` with full `CameraAccessException` protection.

3. **4-Second Hold & Native Biometric Unlock (`ui/BiometricUnlockActivity.kt` & `service/BlackoutAccessibilityService.kt`)**
   - Holding an allowed app's name on the overlay continuously for **4,000 ms** launches `BiometricUnlockActivity` (a solid-black `FragmentActivity`) and triggers Android's native `BiometricPrompt` (Fingerprint / Biometric / PIN).
   - Upon authentication success, the black overlay hides and launches the target application via `Intent`.
   - **Strict Re-Blocking**: `BlackoutAccessibilityService` monitors `TYPE_WINDOW_STATE_CHANGED` and `TYPE_WINDOWS_CHANGED`. As soon as the user leaves the allowed app (presses Home, Recents, or switches apps), the temporary unlock is revoked and the black overlay instantaneously reappears.

4. **System Alarm Exemption (`util/AlarmWhitelist.kt`)**
   - Whitelists Google Clock (`com.google.android.deskclock`), AOSP Clock, Samsung, OnePlus, Xiaomi, OPPO, Vivo, ASUS, Huawei, Motorola, and Sony clock/alarm packages, as well as `Notification.CATEGORY_ALARM`.
   - Temporarily hides the overlay while an alarm is ringing so the user can dismiss or snooze it, then immediately resumes the blackout overlay.

5. **Wall-Clock Timer & Reboot Persistence (`data/repository/BlackoutRepository.kt` & `receiver/BootCompletedReceiver.kt`)**
   - Computes `targetEndTimeMillis = System.currentTimeMillis() + durationMillis` and persists it in Device-Protected Storage `SharedPreferences`.
   - Automatically resumes the `BlackoutForegroundService` and overlay if the app is killed by the OS or if the device reboots while `targetEndTimeMillis > System.currentTimeMillis()`.
