package com.blackout.focus.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.blackout.focus.BlackoutApplication
import com.blackout.focus.service.BlackoutForegroundService
import com.blackout.focus.ui.theme.BlackoutTheme
import com.blackout.focus.util.PermissionHelper

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val runtimePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.refreshPermissions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestRuntimePermissionsIfNeeded()

        setContent {
            BlackoutTheme {
                SetupScreen(
                    viewModel = viewModel,
                    onRequestOverlayPermission = {
                        startActivity(PermissionHelper.createOverlayPermissionIntent(this))
                    },
                    onRequestAccessibilityPermission = {
                        startActivity(PermissionHelper.createAccessibilitySettingsIntent())
                    },
                    onRequestNotificationPermission = {
                        startActivity(PermissionHelper.createNotificationListenerSettingsIntent())
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshPermissions()

        // Auto-resume blackout overlay if an active session exists and targetEndTimeMillis is in the future
        val repo = BlackoutApplication.getInstance().repository
        if (repo.reconcileSessionStateOnStartup() && PermissionHelper.hasOverlayPermission(this)) {
            BlackoutForegroundService.startService(this)
        }
    }

    private fun requestRuntimePermissionsIfNeeded() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.CAMERA)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (needed.isNotEmpty()) {
            runtimePermissionLauncher.launch(needed.toTypedArray())
        }
    }
}
