package com.blackout.focus.data.reel

enum class ReelPlatform(
    val id: String,
    val displayName: String,
    val shortCode: String,
    val packageName: String
) {
    INSTAGRAM(
        id = "INSTAGRAM",
        displayName = "INSTAGRAM REELS",
        shortCode = "IG",
        packageName = "com.instagram.android"
    ),
    YOUTUBE(
        id = "YOUTUBE",
        displayName = "YOUTUBE SHORTS",
        shortCode = "YT",
        packageName = "com.google.android.youtube"
    );

    companion object {
        fun fromPackageName(pkg: String?): ReelPlatform? {
            return entries.firstOrNull { it.packageName == pkg }
        }

        fun fromId(id: String?): ReelPlatform {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: INSTAGRAM
        }
    }
}
