package com.blackout.focus.util

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Safely manages the hardware camera flashlight (torch) with full exception handling
 * so it never crashes if the camera is busy, disconnected, or lacks a flash unit.
 */
class FlashlightController private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val cameraManager: CameraManager? =
        appContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager

    private val _isFlashlightOn = MutableStateFlow(false)
    val isFlashlightOn: StateFlow<Boolean> = _isFlashlightOn.asStateFlow()

    private val _isFlashlightAvailable = MutableStateFlow(false)
    val isFlashlightAvailable: StateFlow<Boolean> = _isFlashlightAvailable.asStateFlow()

    private var torchCameraId: String? = null

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == torchCameraId) {
                _isFlashlightOn.value = enabled
            }
        }

        override fun onTorchModeUnavailable(cameraId: String) {
            if (cameraId == torchCameraId) {
                _isFlashlightOn.value = false
            }
        }
    }

    init {
        resolveTorchCameraId()
        try {
            cameraManager?.registerTorchCallback(torchCallback, Handler(Looper.getMainLooper()))
        } catch (_: Throwable) {
            // Ignore registration failure on unsupported devices
        }
    }

    private fun resolveTorchCameraId(): String? {
        val manager = cameraManager ?: return null
        return try {
            var fallbackId: String? = null
            for (id in manager.cameraIdList) {
                val characteristics = manager.getCameraCharacteristics(id)
                val hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                if (hasFlash) {
                    val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                    if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                        torchCameraId = id
                        _isFlashlightAvailable.value = true
                        return id
                    }
                    if (fallbackId == null) {
                        fallbackId = id
                    }
                }
            }
            torchCameraId = fallbackId
            _isFlashlightAvailable.value = fallbackId != null
            fallbackId
        } catch (_: Throwable) {
            _isFlashlightAvailable.value = false
            null
        }
    }

    /**
     * Toggles the flashlight state safely. Returns true if the command succeeded,
     * or false if the camera is busy / unavailable.
     */
    fun toggleFlashlight(): Boolean {
        return setFlashlight(!_isFlashlightOn.value)
    }

    /**
     * Explicitly sets the flashlight state with comprehensive try-catch protection
     * against CameraAccessException (e.g., CAMERA_IN_USE / MAX_CAMERAS_IN_USE).
     */
    fun setFlashlight(enable: Boolean): Boolean {
        val manager = cameraManager ?: return false
        val cameraId = torchCameraId ?: resolveTorchCameraId() ?: return false

        return try {
            manager.setTorchMode(cameraId, enable)
            _isFlashlightOn.value = enable
            true
        } catch (_: CameraAccessException) {
            // Camera is busy or in use by another application
            _isFlashlightOn.value = false
            false
        } catch (_: IllegalArgumentException) {
            _isFlashlightOn.value = false
            false
        } catch (_: SecurityException) {
            _isFlashlightOn.value = false
            false
        } catch (_: Throwable) {
            _isFlashlightOn.value = false
            false
        }
    }

    /**
     * Safely turns off the flashlight if it is currently on.
     */
    fun turnOffFlashlight() {
        if (_isFlashlightOn.value) {
            setFlashlight(false)
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: FlashlightController? = null

        fun getInstance(context: Context): FlashlightController {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: FlashlightController(context).also { INSTANCE = it }
            }
        }
    }
}
