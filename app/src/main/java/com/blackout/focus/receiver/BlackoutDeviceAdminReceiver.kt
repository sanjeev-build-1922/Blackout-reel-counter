package com.blackout.focus.receiver

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * DeviceAdminReceiver enabling Device Admin & Root-promoted Device Owner (LockTask Kiosk) mode.
 */
class BlackoutDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return "Disabling Blackout Device Admin weakens anti-bypass protection."
    }

    companion object {
        fun getComponentName(context: Context): ComponentName {
            return ComponentName(context.applicationContext, BlackoutDeviceAdminReceiver::class.java)
        }
    }
}
