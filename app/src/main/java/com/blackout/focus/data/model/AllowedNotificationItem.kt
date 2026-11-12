package com.blackout.focus.data.model

/**
 * Holds ONLY the package name and application display name for an allowed notification.
 * Notification title, message body, and sender details are intentionally never stored or displayed.
 */
data class AllowedNotificationItem(
    val packageName: String,
    val appName: String,
    val timestampMillis: Long
)
