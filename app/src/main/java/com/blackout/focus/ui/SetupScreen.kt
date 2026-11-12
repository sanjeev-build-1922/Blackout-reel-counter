package com.blackout.focus.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import android.app.TimePickerDialog
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import com.blackout.focus.data.model.BlackoutSchedule
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blackout.focus.data.model.AppInfo
import com.blackout.focus.data.model.TimerPreset
import com.blackout.focus.ui.components.DotMatrixNumber
import com.blackout.focus.ui.components.DotMatrixProgressBar
import com.blackout.focus.ui.theme.MutedGray
import com.blackout.focus.ui.theme.PureBlack
import com.blackout.focus.ui.theme.PureWhite
import com.blackout.focus.ui.theme.StatusGreen
import com.blackout.focus.ui.theme.StatusRed
import com.blackout.focus.ui.theme.SubtleBorder
import com.blackout.focus.ui.theme.SurfaceDark
import com.blackout.focus.ui.theme.SurfaceElevated
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun SetupScreen(
    viewModel: MainViewModel,
    onRequestOverlayPermission: () -> Unit,
    onRequestAccessibilityPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit
) {
    val permissionState by viewModel.permissionState.collectAsState()
    val selectedPreset by viewModel.selectedPreset.collectAsState()
    val customMinutesInput by viewModel.customMinutesInput.collectAsState()
    val installedApps by viewModel.installedApps.collectAsState()
    val allowedPackages by viewModel.allowedPackages.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val reelUiState by viewModel.reelUiState.collectAsState()
    val schedules by viewModel.schedules.collectAsState()
    val isRootAvailable by viewModel.isRootAvailable.collectAsState()
    val rootModeEnabled by viewModel.rootModeEnabled.collectAsState()
    val suspendAppsOnBlackout by viewModel.suspendAppsOnBlackout.collectAsState()
    val kioskLockTaskEnabled by viewModel.kioskLockTaskEnabled.collectAsState()
    val bootScriptInstalled by viewModel.bootScriptInstalled.collectAsState()
    val rootStatusMessage by viewModel.rootStatusMessage.collectAsState()
    var showAddScheduleScreen by remember { mutableStateOf(false) }

    var showAllowedAppsSelector by remember { mutableStateOf(false) }
    var activeTab by remember { mutableIntStateOf(0) }

    if (showAddScheduleScreen) {
        AddScheduleScreen(
            onSave = { label, sh, sm, eh, em, days ->
                viewModel.addSchedule(label, sh, sm, eh, em, days)
                showAddScheduleScreen = false
            },
            onCancel = { showAddScheduleScreen = false }
        )
        return
    }

    if (showAllowedAppsSelector) {
        AllowedAppsSelectorScreen(
            apps = installedApps,
            searchQuery = searchQuery,
            onSearchQueryChange = viewModel::updateSearchQuery,
            onToggleApp = viewModel::toggleAllowedApp,
            onClose = {
                viewModel.updateSearchQuery("")
                showAllowedAppsSelector = false
            }
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PureBlack)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.Top
    ) {
        // TOP HEADER
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Blackout",
                    color = PureWhite,
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 26.sp
                )
                Text(
                    text = "Focus blocker & reel counter",
                    color = MutedGray,
                    fontFamily = FontFamily.SansSerif,
                    fontSize = 13.sp
                )
            }

            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(SurfaceDark)
                    .clickable { activeTab = 1 }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(if (reelUiState.isOverDailyLimit) StatusRed else PureWhite)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "${reelUiState.todayCount} reels",
                    color = PureWhite,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // SEGMENTED PILL SWITCHER
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(SurfaceDark)
                .padding(4.dp)
        ) {
            SegmentedTabButton(
                title = "Blackout",
                isSelected = activeTab == 0,
                onClick = { activeTab = 0 },
                modifier = Modifier.weight(1f)
            )
            SegmentedTabButton(
                title = "Reel Counter",
                isSelected = activeTab == 1,
                onClick = { activeTab = 1 },
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(22.dp))

        if (activeTab == 0) {
            // =========================================================================
            // TAB 0: BLACKOUT SETUP
            // =========================================================================

            // 1. PERMISSIONS CARD
            SectionLabel("Permissions")
            Spacer(modifier = Modifier.height(8.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(SurfaceDark)
            ) {
                PermissionRow(
                    title = "Display over apps",
                    subtitle = "Draws the blackout screen",
                    isGranted = permissionState.hasOverlayPermission,
                    onClick = onRequestOverlayPermission
                )
                HorizontalDivider(color = SubtleBorder, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 16.dp))
                PermissionRow(
                    title = "Accessibility service",
                    subtitle = "Blocks navigation & counts reels",
                    isGranted = permissionState.hasAccessibilityPermission,
                    onClick = onRequestAccessibilityPermission
                )
                HorizontalDivider(color = SubtleBorder, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 16.dp))
                PermissionRow(
                    title = "Notification access",
                    subtitle = "Shows allowed app names only",
                    isGranted = permissionState.hasNotificationPermission,
                    onClick = onRequestNotificationPermission
                )
            }

            Spacer(modifier = Modifier.height(22.dp))

            // 2. ALLOWED APPS CARD
            SectionLabel("Allowed Notifications")
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(SurfaceDark)
                    .clickable { showAllowedAppsSelector = true }
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Allowed apps",
                        color = PureWhite,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Hold app name 4s during blackout to open",
                        color = MutedGray,
                        fontSize = 12.sp
                    )
                }
                Text(
                    text = "${allowedPackages.size} >",
                    color = PureWhite,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp
                )
            }

            Spacer(modifier = Modifier.height(22.dp))

            // 3. TIMER DURATION
            SectionLabel("Session Duration")
            Spacer(modifier = Modifier.height(8.dp))

            val presets = TimerPreset.values().toList()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                presets.forEach { preset ->
                    val isSelected = preset == selectedPreset
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) PureWhite else SurfaceDark)
                            .clickable { viewModel.selectTimerPreset(preset) }
                            .padding(vertical = 13.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = preset.label,
                            color = if (isSelected) PureBlack else PureWhite,
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            if (selectedPreset == TimerPreset.CUSTOM) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(SurfaceDark)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Custom minutes",
                        color = PureWhite,
                        fontSize = 14.sp
                    )
                    BasicTextField(
                        value = customMinutesInput,
                        onValueChange = viewModel::updateCustomMinutes,
                        singleLine = true,
                        textStyle = TextStyle(
                            color = PureWhite,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.End
                        ),
                        cursorBrush = SolidColor(PureWhite),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .width(90.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(SurfaceElevated)
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            // 4. AUTOMATIC SCHEDULES SECTION (Multiple Schedules + Day-wise / Every Day)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionLabel("Schedules")
                Text(
                    text = "+ Add",
                    color = PureWhite,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(SurfaceDark)
                        .clickable { showAddScheduleScreen = true }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))

            if (schedules.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(SurfaceDark)
                        .clickable { showAddScheduleScreen = true }
                        .padding(horizontal = 16.dp, vertical = 16.dp)
                ) {
                    Text(
                        text = "No schedules yet. Tap + Add to auto-start Blackout.",
                        color = MutedGray,
                        fontSize = 13.sp
                    )
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    schedules.forEach { schedule ->
                        ScheduleItemCard(
                            schedule = schedule,
                            onToggle = { enabled -> viewModel.toggleSchedule(schedule.id, enabled) },
                            onDelete = { viewModel.deleteSchedule(schedule.id) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            // 5. ROOT & CUSTOM ROM PEAK ENGINE (All 5 Capabilities)
            SectionLabel("Root & Custom ROM Engine")
            Spacer(modifier = Modifier.height(8.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(SurfaceDark)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Root Peak Enforcer",
                            color = PureWhite,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = rootStatusMessage,
                            color = if (isRootAvailable) StatusGreen else MutedGray,
                            fontSize = 12.sp
                        )
                    }
                    Switch(
                        checked = rootModeEnabled,
                        onCheckedChange = viewModel::toggleRootMode,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = PureBlack,
                            checkedTrackColor = PureWhite,
                            uncheckedThumbColor = MutedGray,
                            uncheckedTrackColor = SurfaceElevated
                        )
                    )
                }

                HorizontalDivider(color = SubtleBorder, thickness = 0.5.dp)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "OS App Suspend (pm suspend)",
                            color = PureWhite,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "Freezes distracting apps at system level during Blackout",
                            color = MutedGray,
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = suspendAppsOnBlackout,
                        onCheckedChange = viewModel::toggleSuspendAppsOnBlackout,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = PureBlack,
                            checkedTrackColor = PureWhite,
                            uncheckedThumbColor = MutedGray,
                            uncheckedTrackColor = SurfaceElevated
                        )
                    )
                }

                HorizontalDivider(color = SubtleBorder, thickness = 0.5.dp)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Device Owner Kiosk LockTask",
                            color = PureWhite,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "Hardware-locks Control Centre, Recents & Power Menu",
                            color = MutedGray,
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = kioskLockTaskEnabled,
                        onCheckedChange = viewModel::toggleKioskLockTask,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = PureBlack,
                            checkedTrackColor = PureWhite,
                            uncheckedThumbColor = MutedGray,
                            uncheckedTrackColor = SurfaceElevated
                        )
                    )
                }

                HorizontalDivider(color = SubtleBorder, thickness = 0.5.dp)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (bootScriptInstalled) "Magisk/KSU service.d script: Installed" else "Magisk/KSU service.d script: Ready",
                        color = MutedGray,
                        fontSize = 11.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(SurfaceElevated)
                            .clickable { viewModel.triggerFullRootBoost() }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = "Apply All 5 via Root",
                            color = PureWhite,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            if (!statusMessage.isNullOrBlank()) {
                Text(
                    text = statusMessage.orEmpty(),
                    color = StatusRed,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(SurfaceDark)
                        .padding(14.dp)
                )
                Spacer(modifier = Modifier.height(14.dp))
            }

            // START BUTTON
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(PureWhite)
                    .clickable { viewModel.startBlackoutSession() }
                    .padding(vertical = 17.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Start Blackout",
                    color = PureBlack,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        } else {
            // =========================================================================
            // TAB 1: REEL COUNTER DASHBOARD (No Reset Button — Tamper-Proof Daily Count)
            // =========================================================================
            ReelCounterDashboardSection(
                reelUiState = reelUiState,
                onToggleHud = viewModel::setHudEnabled,
                onToggleHaptics = viewModel::setHapticsEnabled,
                onAdjustDailyLimit = viewModel::adjustDailyLimit,
                onSelectWeekOffset = viewModel::selectWeekOffset
            )
        }
    }
}

@Composable
private fun SegmentedTabButton(
    title: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(11.dp))
            .background(if (isSelected) SurfaceElevated else PureBlack.copy(alpha = 0f))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            color = if (isSelected) PureWhite else MutedGray,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            fontSize = 13.sp
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = MutedGray,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 4.dp)
    )
}

@Composable
private fun PermissionRow(
    title: String,
    subtitle: String,
    isGranted: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = PureWhite,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = subtitle,
                color = MutedGray,
                fontSize = 12.sp
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(if (isGranted) StatusGreen.copy(alpha = 0.16f) else StatusRed.copy(alpha = 0.16f))
                .padding(horizontal = 10.dp, vertical = 5.dp)
        ) {
            Text(
                text = if (isGranted) "Active" else "Enable",
                color = if (isGranted) StatusGreen else StatusRed,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun ReelCounterDashboardSection(
    reelUiState: ReelUiState,
    onToggleHud: (Boolean) -> Unit,
    onToggleHaptics: (Boolean) -> Unit,
    onAdjustDailyLimit: (Int) -> Unit,
    onSelectWeekOffset: (Int) -> Unit
) {
    val breakdown = reelUiState.breakdown

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. HERO COUNTER CARD
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(SurfaceDark)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Today's Reels",
                    color = PureWhite,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                )
                Text(
                    text = "${reelUiState.todayCount} / ${reelUiState.dailyLimit}",
                    color = if (reelUiState.isOverDailyLimit) StatusRed else MutedGray,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(PureBlack)
                    .padding(vertical = 22.dp),
                contentAlignment = Alignment.Center
            ) {
                DotMatrixNumber(
                    value = reelUiState.todayCount,
                    minDigits = 3,
                    dotSize = 7.dp,
                    dotSpacing = 3.5.dp,
                    digitSpacing = 18.dp,
                    activeColor = if (reelUiState.isOverDailyLimit) StatusRed else PureWhite
                )
            }

            DotMatrixProgressBar(
                progress = reelUiState.limitProgress,
                totalColumns = 28,
                activeColor = if (reelUiState.isOverDailyLimit) StatusRed else PureWhite,
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatChip(label = "Total time", value = breakdown.formatTotalTime())
                StatChip(label = "Avg / reel", value = breakdown.formatAvgPerReel())
                StatChip(label = "All-time", value = "${reelUiState.allTimeTotal}")
            }
        }

        // 2. INSTAGRAM & YOUTUBE TIME + COUNT BREAKDOWN
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            PlatformTelemetryCard(
                title = "Instagram",
                reelCount = breakdown.instagramCount,
                timeSpent = breakdown.formatInstagramTime(),
                avgPerReel = if (breakdown.instagramCount > 0 && breakdown.instagramSeconds > 0L) {
                    String.format(Locale.US, "%.1fs / reel", breakdown.instagramAvgSecondsPerReel)
                } else {
                    "0s / reel"
                },
                modifier = Modifier.weight(1f)
            )

            PlatformTelemetryCard(
                title = "YouTube",
                reelCount = breakdown.youtubeCount,
                timeSpent = breakdown.formatYoutubeTime(),
                avgPerReel = if (breakdown.youtubeCount > 0 && breakdown.youtubeSeconds > 0L) {
                    String.format(Locale.US, "%.1fs / reel", breakdown.youtubeAvgSecondsPerReel)
                } else {
                    "0s / reel"
                },
                modifier = Modifier.weight(1f)
            )
        }

        // 3. WEEKLY ACTIVITY CHART
        if (reelUiState.weekPages.isNotEmpty()) {
            WeeklyChartSection(
                weekPages = reelUiState.weekPages,
                onSelectWeekOffset = onSelectWeekOffset
            )
        }

        // 4. COUNTER CONTROLS (No Reset Button)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(SurfaceDark)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Floating counter", color = PureWhite, fontSize = 15.sp)
                    Text(text = "Shows live count inside Instagram & YouTube", color = MutedGray, fontSize = 12.sp)
                }
                Switch(
                    checked = reelUiState.hudEnabled,
                    onCheckedChange = onToggleHud,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = PureBlack,
                        checkedTrackColor = PureWhite,
                        uncheckedThumbColor = MutedGray,
                        uncheckedTrackColor = SurfaceElevated
                    )
                )
            }

            HorizontalDivider(color = SubtleBorder, thickness = 0.5.dp)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Haptic tick", color = PureWhite, fontSize = 15.sp)
                    Text(text = "Subtle vibration per counted reel", color = MutedGray, fontSize = 12.sp)
                }
                Switch(
                    checked = reelUiState.hapticsEnabled,
                    onCheckedChange = onToggleHaptics,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = PureBlack,
                        checkedTrackColor = PureWhite,
                        uncheckedThumbColor = MutedGray,
                        uncheckedTrackColor = SurfaceElevated
                    )
                )
            }

            HorizontalDivider(color = SubtleBorder, thickness = 0.5.dp)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Daily limit", color = PureWhite, fontSize = 15.sp)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(SurfaceElevated)
                            .clickable { onAdjustDailyLimit(-10) }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(text = "-10", color = PureWhite, fontSize = 13.sp)
                    }
                    Text(
                        text = "${reelUiState.dailyLimit}",
                        color = PureWhite,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(SurfaceElevated)
                            .clickable { onAdjustDailyLimit(10) }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(text = "+10", color = PureWhite, fontSize = 13.sp)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun PlatformTelemetryCard(
    title: String,
    reelCount: Int,
    timeSpent: String,
    avgPerReel: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceDark)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = title,
            color = MutedGray,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "$reelCount reels",
            color = PureWhite,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp
        )
        HorizontalDivider(color = SubtleBorder, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = "Time", color = MutedGray, fontSize = 11.sp)
            Text(
                text = timeSpent,
                color = PureWhite,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = "Avg", color = MutedGray, fontSize = 11.sp)
            Text(
                text = avgPerReel,
                color = PureWhite,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun StatChip(label: String, value: String) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(text = label, color = MutedGray, fontSize = 11.sp)
        Text(
            text = value,
            color = PureWhite,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WeeklyChartSection(
    weekPages: List<WeekHistoryPage>,
    onSelectWeekOffset: (Int) -> Unit
) {
    val pageCount = weekPages.size
    val initialIdx = (pageCount - 1).coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = initialIdx, pageCount = { pageCount })
    val scope = rememberCoroutineScope()

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { idx ->
            weekPages.getOrNull(idx)?.let { onSelectWeekOffset(it.weekOffset) }
        }
    }

    val currentPage = weekPages.getOrNull(pagerState.currentPage) ?: weekPages.last()
    val canGoBack = pagerState.currentPage > 0
    val canGoForward = pagerState.currentPage < pageCount - 1

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(SurfaceDark)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = currentPage.label,
                    color = PureWhite,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                )
                Text(
                    text = "${currentPage.rangeDescription}  •  ${currentPage.totalCount} total  •  ${currentPage.dailyAverage}/day",
                    color = MutedGray,
                    fontSize = 12.sp
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(SurfaceElevated)
                        .clickable(enabled = canGoBack) {
                            scope.launch { pagerState.scrollToPage(pagerState.currentPage - 1) }
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(text = "<", color = if (canGoBack) PureWhite else MutedGray, fontSize = 13.sp)
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(SurfaceElevated)
                        .clickable(enabled = canGoForward) {
                            scope.launch { pagerState.scrollToPage(pagerState.currentPage + 1) }
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(text = ">", color = if (canGoForward) PureWhite else MutedGray, fontSize = 13.sp)
                }
            }
        }

        val maxDay = (weekPages.flatMap { it.days }.maxOfOrNull { it.count } ?: 50).coerceAtLeast(30)

        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth()) { pageIdx ->
            val page = weekPages[pageIdx]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(132.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                page.days.forEach { day ->
                    val fraction = (day.count.toFloat() / maxDay.toFloat()).coerceIn(0.06f, 1f)
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom
                    ) {
                        if (day.count > 0) {
                            Text(
                                text = day.count.toString(),
                                color = if (day.isToday) PureWhite else MutedGray,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp
                            )
                        } else {
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .width(16.dp)
                                .height(88.dp * fraction)
                                .clip(RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp))
                                .background(
                                    when {
                                        day.isToday -> PureWhite
                                        day.count > 0 -> MutedGray
                                        else -> SurfaceElevated
                                    }
                                )
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = day.dayShort,
                            color = if (day.isToday) PureWhite else MutedGray,
                            fontWeight = if (day.isToday) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AllowedAppsSelectorScreen(
    apps: List<AppInfo>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onToggleApp: (String, Boolean) -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PureBlack)
            .padding(20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Allowed Apps",
                color = PureWhite,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(PureWhite)
                    .clickable(onClick = onClose)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "Done",
                    color = PureBlack,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(SurfaceDark)
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            if (searchQuery.isEmpty()) {
                Text(
                    text = "Search installed apps...",
                    color = MutedGray,
                    fontSize = 14.sp
                )
            }
            BasicTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                singleLine = true,
                textStyle = TextStyle(color = PureWhite, fontSize = 14.sp),
                cursorBrush = SolidColor(PureWhite),
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(items = apps, key = { it.packageName }) { app ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (app.isAllowed) SurfaceElevated else SurfaceDark)
                        .clickable { onToggleApp(app.packageName, !app.isAllowed) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = app.appName,
                            color = PureWhite,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = app.packageName,
                            color = MutedGray,
                            fontSize = 11.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(if (app.isAllowed) PureWhite else PureBlack)
                            .border(1.5.dp, if (app.isAllowed) PureWhite else MutedGray, CircleShape)
                    )
                }
            }
        }
    }
}


@Composable
private fun ScheduleItemCard(
    schedule: BlackoutSchedule,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceDark)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = schedule.label,
                    color = PureWhite,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = schedule.formattedTimeRange(),
                    color = PureWhite,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = schedule.formattedDays(),
                    color = MutedGray,
                    fontSize = 12.sp
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = schedule.isEnabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = PureBlack,
                        checkedTrackColor = PureWhite,
                        uncheckedThumbColor = MutedGray,
                        uncheckedTrackColor = SurfaceElevated
                    )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Del",
                    color = StatusRed,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(StatusRed.copy(alpha = 0.14f))
                        .clickable(onClick = onDelete)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun AddScheduleScreen(
    onSave: (String, Int, Int, Int, Int, Set<Int>) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    var label by remember { mutableStateOf("Night Focus") }
    var startHour by remember { mutableIntStateOf(22) }
    var startMinute by remember { mutableIntStateOf(0) }
    var endHour by remember { mutableIntStateOf(6) }
    var endMinute by remember { mutableIntStateOf(0) }
    var selectedDays by remember { mutableStateOf((1..7).toSet()) }

    val timeFmt = remember { DateTimeFormatter.ofPattern("hh:mm a", Locale.US) }
    val dayItems = listOf(
        1 to "M", 2 to "T", 3 to "W", 4 to "T", 5 to "F", 6 to "S", 7 to "S"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PureBlack)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.Top
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "New Schedule",
                color = PureWhite,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp
            )
            Text(
                text = "Cancel",
                color = MutedGray,
                fontSize = 14.sp,
                modifier = Modifier
                    .clickable(onClick = onCancel)
                    .padding(8.dp)
            )
        }

        Spacer(modifier = Modifier.height(22.dp))

        SectionLabel("Label")
        Spacer(modifier = Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(SurfaceDark)
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            BasicTextField(
                value = label,
                onValueChange = { label = it.take(30) },
                singleLine = true,
                textStyle = TextStyle(color = PureWhite, fontSize = 15.sp),
                cursorBrush = SolidColor(PureWhite),
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        SectionLabel("Time Window")
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(SurfaceDark)
                    .clickable {
                        TimePickerDialog(context, { _, h, m ->
                            startHour = h
                            startMinute = m
                        }, startHour, startMinute, false).show()
                    }
                    .padding(16.dp)
            ) {
                Text(text = "Start", color = MutedGray, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = LocalTime.of(startHour, startMinute).format(timeFmt),
                    color = PureWhite,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(SurfaceDark)
                    .clickable {
                        TimePickerDialog(context, { _, h, m ->
                            endHour = h
                            endMinute = m
                        }, endHour, endMinute, false).show()
                    }
                    .padding(16.dp)
            ) {
                Text(text = "End", color = MutedGray, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = LocalTime.of(endHour, endMinute).format(timeFmt),
                    color = PureWhite,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SectionLabel("Repeat Days")
            Text(
                text = if (selectedDays.size == 7) "All Days Selected" else "Select All Days",
                color = PureWhite,
                fontSize = 12.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(SurfaceDark)
                    .clickable { selectedDays = (1..7).toSet() }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            dayItems.forEach { (dayVal, letter) ->
                val active = selectedDays.contains(dayVal)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (active) PureWhite else SurfaceDark)
                        .clickable {
                            selectedDays = if (active) {
                                if (selectedDays.size > 1) selectedDays - dayVal else selectedDays
                            } else {
                                selectedDays + dayVal
                            }
                        }
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = letter,
                        color = if (active) PureBlack else PureWhite,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(PureWhite)
                .clickable {
                    onSave(label, startHour, startMinute, endHour, endMinute, selectedDays)
                }
                .padding(vertical = 17.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Save Schedule",
                color = PureBlack,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        }
    }
}
