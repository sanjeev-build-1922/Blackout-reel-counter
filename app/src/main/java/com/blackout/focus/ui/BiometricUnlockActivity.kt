package com.blackout.focus.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.blackout.focus.BlackoutApplication
import com.blackout.focus.data.repository.BlackoutRepository
import com.blackout.focus.service.BlackoutForegroundService
import com.blackout.focus.ui.theme.BlackoutTheme
import com.blackout.focus.ui.theme.PureBlack

/**
 * Solid-black FragmentActivity launched when the user completes a 4-second hold on an
 * Allowed App's name on the Blackout overlay.
 *
 * Hosts the native Android BiometricPrompt (Fingerprint / Biometric / Device PIN).
 * - On authentication success: temporarily unlocks the target package, launches it via Intent,
 *   and lets BlackoutAccessibilityService monitor the active window for strict re-blocking on exit.
 * - On failure or cancellation: immediately restores the full-screen Blackout overlay.
 */
class BiometricUnlockActivity : FragmentActivity() {

    private lateinit var repository: BlackoutRepository
    private var targetPackageName: String = ""
    private var targetAppName: String = ""
    private var unlockSucceeded: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)

        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)

        repository = BlackoutApplication.getInstance().repository
        targetPackageName = intent?.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
        targetAppName = intent?.getStringExtra(EXTRA_APP_NAME).orEmpty()

        if (targetPackageName.isBlank() || !repository.isSessionActive.value) {
            restoreOverlayAndFinish()
            return
        }

        // Render pure solid black background so the screen remains completely black behind BiometricPrompt
        setContent {
            BlackoutTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PureBlack)
                )
            }
        }

        showNativeBiometricPrompt()
    }

    private fun showNativeBiometricPrompt() {
        val executor = ContextCompat.getMainExecutor(this)

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                unlockSucceeded = true
                launchAllowedApplication(targetPackageName)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                if (!unlockSucceeded) {
                    restoreOverlayAndFinish()
                }
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                // BiometricPrompt stays open for retry; if user cancels, onAuthenticationError fires
            }
        }

        val biometricPrompt = BiometricPrompt(this, executor, callback)
        val promptInfo = buildPromptInfo(targetAppName.ifBlank { targetPackageName })

        try {
            biometricPrompt.authenticate(promptInfo)
        } catch (_: Throwable) {
            restoreOverlayAndFinish()
        }
    }

    private fun buildPromptInfo(displayAppName: String): BiometricPrompt.PromptInfo {
        val biometricManager = BiometricManager.from(this)
        val builder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(displayAppName)
            .setSubtitle("Authenticate to temporarily open $displayAppName")
            .setConfirmationRequired(false)

        val strongOrCredential = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val weakOrCredential = BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (biometricManager.canAuthenticate(weakOrCredential) == BiometricManager.BIOMETRIC_SUCCESS) {
                builder.setAllowedAuthenticators(weakOrCredential)
            } else {
                builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                builder.setNegativeButtonText("Cancel")
            }
        } else {
            @Suppress("DEPRECATION")
            if (biometricManager.canAuthenticate(strongOrCredential) == BiometricManager.BIOMETRIC_SUCCESS) {
                builder.setDeviceCredentialAllowed(true)
            } else {
                builder.setNegativeButtonText("Cancel")
            }
        }

        return builder.build()
    }

    private fun launchAllowedApplication(packageName: String) {
        // Ensure package is unsuspended via root before querying/launching its intent
        BlackoutApplication.getInstance().rootShellController
            .setPackageTemporarilyUnsuspendedAsync(packageName, unsuspended = true)
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }

        if (launchIntent != null) {
            repository.grantTemporaryAppUnlock(packageName)
            try {
                startActivity(launchIntent)
                finish()
                @Suppress("DEPRECATION")
                overridePendingTransition(0, 0)
            } catch (_: Throwable) {
                repository.revokeTemporaryAppUnlock()
                restoreOverlayAndFinish()
            }
        } else {
            restoreOverlayAndFinish()
        }
    }

    private fun restoreOverlayAndFinish() {
        repository.setBiometricPromptInProgress(false)
        BlackoutForegroundService.syncOverlayNow(this)
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    override fun onStop() {
        super.onStop()
        if (!unlockSucceeded && !isChangingConfigurations) {
            repository.setBiometricPromptInProgress(false)
            BlackoutForegroundService.syncOverlayNow(this)
        }
    }

    companion object {
        private const val EXTRA_PACKAGE_NAME = "extra_package_name"
        private const val EXTRA_APP_NAME = "extra_app_name"

        fun launch(context: Context, packageName: String, appName: String) {
            val intent = Intent(context.applicationContext, BiometricUnlockActivity::class.java).apply {
                putExtra(EXTRA_PACKAGE_NAME, packageName)
                putExtra(EXTRA_APP_NAME, appName)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                        or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        or Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
            }
            context.applicationContext.startActivity(intent)
        }
    }
}
