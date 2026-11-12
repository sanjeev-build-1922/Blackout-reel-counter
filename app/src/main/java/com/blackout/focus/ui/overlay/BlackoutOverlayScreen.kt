package com.blackout.focus.ui.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blackout.focus.data.model.AllowedNotificationItem
import com.blackout.focus.data.reel.DailyBreakdown
import com.blackout.focus.ui.theme.MutedGray
import com.blackout.focus.ui.theme.PureBlack
import com.blackout.focus.ui.theme.PureWhite
import com.blackout.focus.ui.theme.SurfaceDark
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val FOUR_SECOND_HOLD_MS = 4000L

@Composable
fun BlackoutOverlayScreen(
    allowedNotifications: List<AllowedNotificationItem>,
    todayReelBreakdown: DailyBreakdown,
    isFlashlightOn: Boolean,
    onToggleFlashlight: () -> Unit,
    onFourSecondHoldCompleted: (packageName: String, appName: String) -> Unit
) {
    var currentTimeText by remember { mutableStateOf(formatCurrentTime()) }

    LaunchedEffect(Unit) {
        while (true) {
            currentTimeText = formatCurrentTime()
            delay(1000L)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PureBlack)
            .pointerInput(Unit) {
                awaitEachGesture {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        event.changes.forEach { it.consume() }
                    }
                }
            }
            .padding(horizontal = 24.dp, vertical = 52.dp)
    ) {
        // TOP CENTER: Clean Digital Clock + Today's Reel Count & Time Spent
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = currentTimeText,
                color = PureWhite,
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Light,
                fontSize = 38.sp,
                letterSpacing = 1.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = String.format(
                    Locale.US,
                    "%d Reels Today  •  IG %d  •  YT %d",
                    todayReelBreakdown.total,
                    todayReelBreakdown.instagramCount,
                    todayReelBreakdown.youtubeCount
                ),
                color = PureWhite,
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Time: ${todayReelBreakdown.formatTotalTime()}  •  Avg: ${todayReelBreakdown.formatAvgPerReel()}/reel",
                color = MutedGray,
                fontFamily = FontFamily.SansSerif,
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
        }

        // CENTER: Allowed App Notification Names ONLY
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            allowedNotifications.forEach { item ->
                AllowedAppNotificationRow(
                    appName = item.appName,
                    packageName = item.packageName,
                    onFourSecondHold = {
                        onFourSecondHoldCompleted(item.packageName, item.appName)
                    }
                )
                Spacer(modifier = Modifier.height(14.dp))
            }
        }

        // BOTTOM CENTER: Subtle Pill Flashlight Toggle
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .clip(RoundedCornerShape(50))
                .background(if (isFlashlightOn) PureWhite else SurfaceDark)
                .clickable(onClick = onToggleFlashlight)
                .padding(horizontal = 28.dp, vertical = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (isFlashlightOn) "Flashlight On" else "Flashlight Off",
                color = if (isFlashlightOn) PureBlack else PureWhite,
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp
            )
        }
    }
}

@Composable
private fun AllowedAppNotificationRow(
    appName: String,
    packageName: String,
    onFourSecondHold: () -> Unit
) {
    Box(
        modifier = Modifier
            .pointerInput(packageName) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    val upOrCancel = withTimeoutOrNull(FOUR_SECOND_HOLD_MS) {
                        waitForUpOrCancellation()
                    }
                    if (upOrCancel == null) {
                        onFourSecondHold()
                    }
                }
            }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = appName,
            color = PureWhite,
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Normal,
            fontSize = 20.sp,
            textAlign = TextAlign.Center
        )
    }
}

private fun formatCurrentTime(): String {
    val formatter = SimpleDateFormat("hh:mm a", Locale.US)
    return formatter.format(Date(System.currentTimeMillis()))
}
