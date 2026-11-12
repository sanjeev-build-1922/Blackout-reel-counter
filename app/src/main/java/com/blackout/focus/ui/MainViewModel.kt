package com.blackout.focus.ui

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.blackout.focus.BlackoutApplication
import com.blackout.focus.data.model.AppInfo
import com.blackout.focus.data.model.BlackoutSchedule
import com.blackout.focus.data.model.TimerPreset
import com.blackout.focus.service.ScheduleAlarmScheduler
import com.blackout.focus.data.reel.DailyBreakdown
import com.blackout.focus.data.reel.DailyCountTuple
import com.blackout.focus.service.BlackoutForegroundService
import com.blackout.focus.util.AlarmWhitelist
import com.blackout.focus.util.PermissionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

data class PermissionUiState(
    val hasOverlayPermission: Boolean = false,
    val hasAccessibilityPermission: Boolean = false,
    val hasNotificationPermission: Boolean = false
) {
    val allRequiredGranted: Boolean
        get() = hasOverlayPermission && hasAccessibilityPermission && hasNotificationPermission
}

data class DayBarItem(
    val date: LocalDate,
    val dateKey: String,
    val dayShort: String,
    val count: Int,
    val isToday: Boolean
)

data class WeekHistoryPage(
    val weekOffset: Int,
    val label: String,
    val rangeDescription: String,
    val days: List<DayBarItem>,
    val totalCount: Int
) {
    val dailyAverage: Int
        get() {
            val countedDays = days.count { it.date <= LocalDate.now() }
            return if (countedDays > 0) totalCount / countedDays else 0
        }
}

data class ReelUiState(
    val todayCount: Int = 0,
    val breakdown: DailyBreakdown = DailyBreakdown(),
    val allTimeTotal: Int = 0,
    val weekPages: List<WeekHistoryPage> = emptyList(),
    val selectedWeekOffset: Int = 0,
    val hudEnabled: Boolean = true,
    val hapticsEnabled: Boolean = true,
    val dailyLimit: Int = 100
) {
    val limitProgress: Float
        get() = if (dailyLimit > 0) (todayCount.toFloat() / dailyLimit.toFloat()).coerceIn(0f, 1f) else 0f

    val isOverDailyLimit: Boolean
        get() = todayCount >= dailyLimit
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val app = application as BlackoutApplication
    private val repository = app.repository
    private val reelRepository = app.reelRepository
    private val scheduleRepository = app.scheduleRepository
    private val rootController = app.rootShellController

    private val _permissionState = MutableStateFlow(readCurrentPermissions())
    val permissionState: StateFlow<PermissionUiState> = _permissionState.asStateFlow()

    private val _selectedPreset = MutableStateFlow(TimerPreset.MINUTES_30)
    val selectedPreset: StateFlow<TimerPreset> = _selectedPreset.asStateFlow()

    private val _customMinutesInput = MutableStateFlow("45")
    val customMinutesInput: StateFlow<String> = _customMinutesInput.asStateFlow()

    private val _installedPackagesRaw = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    val allowedPackages: StateFlow<Set<String>> = repository.allowedPackages
    val isSessionActive: StateFlow<Boolean> = repository.isSessionActive
    val schedules: StateFlow<List<BlackoutSchedule>> = scheduleRepository.schedules
    val isRootAvailable: StateFlow<Boolean> = rootController.isRootAvailable
    val rootModeEnabled: StateFlow<Boolean> = rootController.rootModeEnabled
    val suspendAppsOnBlackout: StateFlow<Boolean> = rootController.suspendAppsOnBlackout
    val kioskLockTaskEnabled: StateFlow<Boolean> = rootController.kioskLockTaskEnabled
    val bootScriptInstalled: StateFlow<Boolean> = rootController.bootServiceScriptInstalled
    val rootStatusMessage: StateFlow<String> = rootController.rootStatusMessage

    private val selectedWeekOffset = MutableStateFlow(0)

    private val today = LocalDate.now()
    private val past8WeeksStart = today
        .minusWeeks(7)
        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    private val startKey = past8WeeksStart.format(DateTimeFormatter.ISO_LOCAL_DATE)
    private val endKey = today
        .with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
        .format(DateTimeFormatter.ISO_LOCAL_DATE)

    val reelUiState: StateFlow<ReelUiState> = combine(
        reelRepository.todayBreakdownState,
        reelRepository.observeAllTimeTotal(),
        reelRepository.observeDailyTotalsInRange(startKey, endKey),
        combine(
            reelRepository.hudEnabled,
            reelRepository.hapticsEnabled,
            reelRepository.dailyLimit,
            selectedWeekOffset
        ) { hud, haptics, limit, offset ->
            ReelSettingsTuple(hud, haptics, limit, offset)
        }
    ) { breakdown, allTime, rangeTotals, settings ->
        ReelUiState(
            todayCount = breakdown.total,
            breakdown = breakdown,
            allTimeTotal = allTime,
            weekPages = buildWeeklyPages(rangeTotals, breakdown.total),
            selectedWeekOffset = settings.offset,
            hudEnabled = settings.hud,
            hapticsEnabled = settings.haptics,
            dailyLimit = settings.limit
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), ReelUiState())

    private data class ReelSettingsTuple(
        val hud: Boolean,
        val haptics: Boolean,
        val limit: Int,
        val offset: Int
    )

    val installedApps: StateFlow<List<AppInfo>> = combine(
        _installedPackagesRaw,
        repository.allowedPackages,
        _searchQuery
    ) { rawApps, allowedSet, query ->
        val trimmed = query.trim().lowercase()
        rawApps
            .filter { (pkg, name) ->
                trimmed.isEmpty() ||
                    name.lowercase().contains(trimmed) ||
                    pkg.lowercase().contains(trimmed)
            }
            .map { (pkg, name) ->
                AppInfo(
                    packageName = pkg,
                    appName = name,
                    isAllowed = allowedSet.contains(pkg)
                )
            }
            .sortedWith(compareByDescending<AppInfo> { it.isAllowed }.thenBy { it.appName.lowercase() })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    init {
        refreshPermissions()
        loadInstalledApplications()
    }

    fun refreshPermissions() {
        _permissionState.value = readCurrentPermissions()
        reelRepository.refreshDateKeyIfNeeded()
    }

    private fun readCurrentPermissions(): PermissionUiState {
        return PermissionUiState(
            hasOverlayPermission = PermissionHelper.hasOverlayPermission(appContext),
            hasAccessibilityPermission = PermissionHelper.isAccessibilityServiceEnabled(appContext),
            hasNotificationPermission = PermissionHelper.isNotificationListenerEnabled(appContext)
        )
    }

    private fun loadInstalledApplications() {
        viewModelScope.launch {
            val apps = withContext(Dispatchers.IO) {
                val pm = appContext.packageManager
                val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
                val resolveInfos = pm.queryIntentActivities(mainIntent, PackageManager.MATCH_ALL)
                resolveInfos
                    .mapNotNull { info ->
                        val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                        if (pkg == appContext.packageName) return@mapNotNull null
                        if (AlarmWhitelist.isForbiddenBypassPackage(pkg)) return@mapNotNull null
                        if (AlarmWhitelist.isLauncherPackage(appContext, pkg)) return@mapNotNull null
                        val label = info.loadLabel(pm)?.toString()?.takeIf { it.isNotBlank() } ?: pkg
                        pkg to label
                    }
                    .distinctBy { it.first }
                    .sortedBy { it.second.lowercase() }
            }
            _installedPackagesRaw.value = apps
        }
    }

    private fun buildWeeklyPages(rawTotals: List<DailyCountTuple>, liveTodayTotal: Int): List<WeekHistoryPage> {
        val countMap = rawTotals.associate { it.dateKey to it.total }.toMutableMap()
        val now = LocalDate.now()
        val isoFmt = DateTimeFormatter.ISO_LOCAL_DATE
        val todayKey = now.format(isoFmt)
        countMap[todayKey] = maxOf(countMap[todayKey] ?: 0, liveTodayTotal)

        val currentMonday = now.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val pages = mutableListOf<WeekHistoryPage>()
        val rangeFmt = DateTimeFormatter.ofPattern("MMM d", Locale.US)

        for (offset in 7 downTo 0) {
            val weekMonday = currentMonday.minusWeeks(offset.toLong())
            val weekSunday = weekMonday.plusDays(6)

            val daysList = (0..6).map { dayIndex ->
                val date = weekMonday.plusDays(dayIndex.toLong())
                val dKey = date.format(isoFmt)
                val c = countMap[dKey] ?: 0
                val dayLetter = when (date.dayOfWeek) {
                    DayOfWeek.MONDAY -> "M"
                    DayOfWeek.TUESDAY -> "T"
                    DayOfWeek.WEDNESDAY -> "W"
                    DayOfWeek.THURSDAY -> "T"
                    DayOfWeek.FRIDAY -> "F"
                    DayOfWeek.SATURDAY -> "S"
                    DayOfWeek.SUNDAY -> "S"
                }
                DayBarItem(
                    date = date,
                    dateKey = dKey,
                    dayShort = dayLetter,
                    count = c,
                    isToday = date.isEqual(now)
                )
            }

            val weekSum = daysList.sumOf { it.count }
            val label = when (offset) {
                0 -> "This Week"
                1 -> "Last Week"
                else -> "$offset Weeks Ago"
            }
            val rangeDesc = "${weekMonday.format(rangeFmt)} - ${weekSunday.format(rangeFmt)}"

            pages.add(
                WeekHistoryPage(
                    weekOffset = offset,
                    label = label,
                    rangeDescription = rangeDesc,
                    days = daysList,
                    totalCount = weekSum
                )
            )
        }
        return pages
    }

    fun selectTimerPreset(preset: TimerPreset) {
        _selectedPreset.value = preset
        _statusMessage.value = null
    }

    fun updateCustomMinutes(input: String) {
        val digitsOnly = input.filter { it.isDigit() }.take(4)
        _customMinutesInput.value = digitsOnly
        _statusMessage.value = null
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun toggleAllowedApp(packageName: String, isAllowed: Boolean) {
        if (AlarmWhitelist.isForbiddenBypassPackage(packageName)) return
        repository.setPackageAllowed(packageName, isAllowed)
    }

    fun setHudEnabled(enabled: Boolean) {
        reelRepository.setHudEnabled(enabled)
    }

    fun setHapticsEnabled(enabled: Boolean) {
        reelRepository.setHapticsEnabled(enabled)
    }

    fun adjustDailyLimit(delta: Int) {
        val next = (reelRepository.dailyLimit.value + delta).coerceIn(20, 500)
        reelRepository.setDailyLimit(next)
    }

    fun addSchedule(
        label: String,
        startHour: Int,
        startMinute: Int,
        endHour: Int,
        endMinute: Int,
        daysOfWeek: Set<Int>
    ) {
        if (daysOfWeek.isEmpty()) return
        val cleanLabel = label.trim().ifBlank { "Focus Schedule" }
        scheduleRepository.addSchedule(
            BlackoutSchedule(
                label = cleanLabel,
                startHour = startHour,
                startMinute = startMinute,
                endHour = endHour,
                endMinute = endMinute,
                daysOfWeek = daysOfWeek,
                isEnabled = true
            )
        )
        ScheduleAlarmScheduler.evaluateAndScheduleNext(appContext)
    }

    fun toggleSchedule(id: String, enabled: Boolean) {
        scheduleRepository.toggleSchedule(id, enabled)
        ScheduleAlarmScheduler.evaluateAndScheduleNext(appContext)
    }

    fun deleteSchedule(id: String) {
        scheduleRepository.deleteSchedule(id)
        ScheduleAlarmScheduler.evaluateAndScheduleNext(appContext)
    }

    fun toggleRootMode(enabled: Boolean) {
        rootController.setRootModeEnabled(enabled)
        refreshPermissions()
    }

    fun toggleSuspendAppsOnBlackout(enabled: Boolean) {
        rootController.setSuspendAppsOnBlackout(enabled)
    }

    fun toggleKioskLockTask(enabled: Boolean) {
        rootController.setKioskLockTaskEnabled(enabled)
    }

    fun triggerFullRootBoost() {
        rootController.applyFullRootBoostAsync {
            refreshPermissions()
        }
    }

    fun selectWeekOffset(offset: Int) {
        selectedWeekOffset.value = offset
    }

    fun startBlackoutSession() {
        refreshPermissions()
        val perms = _permissionState.value
        if (!perms.hasOverlayPermission) {
            _statusMessage.value = "Enable 'Display over apps' permission first."
            return
        }
        if (!perms.hasAccessibilityPermission) {
            _statusMessage.value = "Enable 'Accessibility service' permission first."
            return
        }
        if (!perms.hasNotificationPermission) {
            _statusMessage.value = "Enable 'Notification access' permission first."
            return
        }

        val durationMinutes = when (val preset = _selectedPreset.value) {
            TimerPreset.CUSTOM -> _customMinutesInput.value.toLongOrNull() ?: 0L
            else -> preset.durationMinutes
        }

        if (durationMinutes <= 0L) {
            _statusMessage.value = "Enter a valid duration (at least 1 minute)."
            return
        }

        val durationMillis = durationMinutes * 60_000L
        repository.startBlackoutSession(durationMillis)
        BlackoutForegroundService.startService(appContext)
        _statusMessage.value = null
    }
}
