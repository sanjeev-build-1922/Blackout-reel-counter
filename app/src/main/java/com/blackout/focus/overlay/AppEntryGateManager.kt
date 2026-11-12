package com.blackout.focus.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blackout.focus.data.reel.ReelPlatform
import com.blackout.focus.ui.theme.BlackoutTheme
import com.blackout.focus.ui.theme.MutedGray
import com.blackout.focus.ui.theme.PureBlack
import com.blackout.focus.ui.theme.PureWhite
import com.blackout.focus.ui.theme.SurfaceDark
import com.blackout.focus.ui.theme.SurfaceElevated
import kotlinx.coroutines.delay

/**
 * Displays a large 10-second mindful entry card whenever the user opens Instagram or YouTube.
 * - Counts down from 10s to 0s; if the user does not tap either button, it auto-dismisses after 10s
 *   so the app opens normally.
 * - Contains 2 buttons: "Back" (exits to Home) and "Set Blocker" (opens Blackout setup screen).
 */
class AppEntryGateManager(
    private val context: Context,
    private val onBackClicked: () -> Unit,
    private val onSetBlockerClicked: () -> Unit,
    private val onAutoAllowed: (ReelPlatform) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var rootView: ComposeView? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null
    private var isShowing = false
    private var activePlatform: ReelPlatform? = null

    fun isGateShowing(): Boolean = isShowing

    fun show(platform: ReelPlatform, todayReelCount: Int) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { showInternal(platform, todayReelCount) }
        } else {
            showInternal(platform, todayReelCount)
        }
    }

    fun dismiss() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { dismissInternal() }
        } else {
            dismissInternal()
        }
    }

    private fun showInternal(platform: ReelPlatform, todayReelCount: Int) {
        if (isShowing && activePlatform == platform) return
        if (isShowing) {
            dismissInternal()
        }

        val owner = OverlayLifecycleOwner()
        owner.onCreate()

        val appDisplayName = when (platform) {
            ReelPlatform.INSTAGRAM -> "Instagram"
            ReelPlatform.YOUTUBE -> "YouTube"
        }

        val composeView = ComposeView(context).apply {
            setContent {
                BlackoutTheme {
                    AppEntryGateCardScreen(
                        appName = appDisplayName,
                        todayReelCount = todayReelCount,
                        onCountdownFinished = {
                            dismissInternal()
                            onAutoAllowed(platform)
                        },
                        onBack = {
                            dismissInternal()
                            onBackClicked()
                        },
                        onSetBlocker = {
                            dismissInternal()
                            onSetBlockerClicked()
                        }
                    )
                }
            }
        }
        owner.attachToView(composeView)

        val overlayType = if (Settings.canDrawOverlays(context)) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                or WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        try {
            windowManager.addView(composeView, params)
            owner.onStart()
            owner.onResume()
            rootView = composeView
            lifecycleOwner = owner
            activePlatform = platform
            isShowing = true
        } catch (_: Throwable) {
            owner.onDestroy()
            rootView = null
            lifecycleOwner = null
            activePlatform = null
            isShowing = false
        }
    }

    private fun dismissInternal() {
        val view = rootView
        val owner = lifecycleOwner
        rootView = null
        lifecycleOwner = null
        activePlatform = null
        isShowing = false

        if (owner != null) {
            try {
                owner.onPause()
                owner.onStop()
                owner.onDestroy()
            } catch (_: Throwable) {
            }
        }
        if (view != null) {
            try {
                windowManager.removeViewImmediate(view)
            } catch (_: Throwable) {
                try {
                    windowManager.removeView(view)
                } catch (_: Throwable) {
                }
            }
        }
    }
}

@Composable
private fun AppEntryGateCardScreen(
    appName: String,
    todayReelCount: Int,
    onCountdownFinished: () -> Unit,
    onBack: () -> Unit,
    onSetBlocker: () -> Unit
) {
    var secondsLeft by remember { mutableIntStateOf(10) }

    LaunchedEffect(Unit) {
        while (secondsLeft > 0) {
            delay(1000L)
            secondsLeft -= 1
        }
        onCountdownFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PureBlack.copy(alpha = 0.88f))
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(SurfaceDark)
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "PAUSE",
                color = MutedGray,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                letterSpacing = 2.sp
            )

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "Open $appName?",
                color = PureWhite,
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = """You've watched $todayReelCount reels today.
Opening automatically in""",
                color = MutedGray,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(22.dp))

            // 10-Second Countdown Circle/Box
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(PureBlack)
                    .padding(horizontal = 32.dp, vertical = 18.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "${secondsLeft}s",
                    color = PureWhite,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 42.sp
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            // 2 Buttons: 1st Back, 2nd Set Blocker
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(SurfaceElevated)
                        .clickable(onClick = onBack)
                        .padding(vertical = 15.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Back",
                        color = PureWhite,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(PureWhite)
                        .clickable(onClick = onSetBlocker)
                        .padding(vertical = 15.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Set Blocker",
                        color = PureBlack,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
            }
        }
    }
}
