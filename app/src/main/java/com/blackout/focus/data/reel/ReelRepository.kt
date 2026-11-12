package com.blackout.focus.data.reel

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.blackout.focus.widget.ReelGlanceWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max

data class DailyBreakdown(
    val instagramCount: Int = 0,
    val youtubeCount: Int = 0,
    val instagramSeconds: Long = 0L,
    val youtubeSeconds: Long = 0L
) {
    val total: Int
        get() = instagramCount + youtubeCount

    val totalSeconds: Long
        get() = instagramSeconds + youtubeSeconds

    val instagramRatio: Float
        get() = if (total > 0) instagramCount.toFloat() / total.toFloat() else 0f

    val youtubeRatio: Float
        get() = if (total > 0) youtubeCount.toFloat() / total.toFloat() else 0f

    /**
     * Overall average seconds spent per reel/short today.
     */
    val avgSecondsPerReel: Float
        get() = if (total > 0 && totalSeconds > 0L) {
            totalSeconds.toFloat() / total.toFloat()
        } else {
            0f
        }

    val instagramAvgSecondsPerReel: Float
        get() = if (instagramCount > 0 && instagramSeconds > 0L) {
            instagramSeconds.toFloat() / instagramCount.toFloat()
        } else {
            0f
        }

    val youtubeAvgSecondsPerReel: Float
        get() = if (youtubeCount > 0 && youtubeSeconds > 0L) {
            youtubeSeconds.toFloat() / youtubeCount.toFloat()
        } else {
            0f
        }

    fun formatTotalTime(): String = formatDuration(totalSeconds)
    fun formatInstagramTime(): String = formatDuration(instagramSeconds)
    fun formatYoutubeTime(): String = formatDuration(youtubeSeconds)

    fun formatAvgPerReel(): String {
        val avg = avgSecondsPerReel
        if (avg <= 0f) return "0s"
        return if (avg < 60f) {
            String.format(Locale.US, "%.1fs", avg)
        } else {
            val mins = (avg / 60f).toInt()
            val secs = (avg % 60f).toInt()
            "${mins}m ${secs}s"
        }
    }

    companion object {
        fun formatDuration(seconds: Long): String {
            if (seconds <= 0L) return "0m 0s"
            val hours = seconds / 3600L
            val mins = (seconds % 3600L) / 60L
            val secs = seconds % 60L
            return when {
                hours > 0L -> "${hours}h ${mins}m"
                mins > 0L -> "${mins}m ${secs}s"
                else -> "${secs}s"
            }
        }
    }
}

class ReelRepository private constructor(
    private val reelDao: ReelDao,
    private val appContext: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val storageContext: Context = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        appContext.createDeviceProtectedStorageContext()
    } else {
        appContext
    }

    private val prefs: SharedPreferences =
        storageContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _currentDateKey = MutableStateFlow(todayDateKey())

    private val _hudEnabled = MutableStateFlow(prefs.getBoolean(KEY_HUD_ENABLED, true))
    val hudEnabled: StateFlow<Boolean> = _hudEnabled.asStateFlow()

    private val _hapticsEnabled = MutableStateFlow(prefs.getBoolean(KEY_HAPTICS_ENABLED, true))
    val hapticsEnabled: StateFlow<Boolean> = _hapticsEnabled.asStateFlow()

    private val _dailyLimit = MutableStateFlow(prefs.getInt(KEY_DAILY_LIMIT, 100))
    val dailyLimit: StateFlow<Int> = _dailyLimit.asStateFlow()

    private val _activeForegroundPlatform = MutableStateFlow<ReelPlatform?>(null)
    val activeForegroundPlatform: StateFlow<ReelPlatform?> = _activeForegroundPlatform.asStateFlow()

    // Instant SharedPreferences-backed counters & usage seconds for the current calendar day
    private val _cachedIgCount = MutableStateFlow(0)
    private val _cachedYtCount = MutableStateFlow(0)
    private val _todayIgSeconds = MutableStateFlow(0L)
    private val _todayYtSeconds = MutableStateFlow(0L)

    init {
        reconcileDailyPrefsWithDate()
    }

    @Synchronized
    private fun reconcileDailyPrefsWithDate() {
        val today = todayDateKey()
        val savedDate = prefs.getString(KEY_SAVED_DATE, null)
        if (savedDate == today) {
            _cachedIgCount.value = prefs.getInt(KEY_TODAY_IG_COUNT, 0)
            _cachedYtCount.value = prefs.getInt(KEY_TODAY_YT_COUNT, 0)
            _todayIgSeconds.value = prefs.getLong(KEY_TODAY_IG_SECONDS, 0L)
            _todayYtSeconds.value = prefs.getLong(KEY_TODAY_YT_SECONDS, 0L)
        } else {
            // New day started: reset daily preferences for the new date
            prefs.edit()
                .putString(KEY_SAVED_DATE, today)
                .putInt(KEY_TODAY_IG_COUNT, 0)
                .putInt(KEY_TODAY_YT_COUNT, 0)
                .putLong(KEY_TODAY_IG_SECONDS, 0L)
                .putLong(KEY_TODAY_YT_SECONDS, 0L)
                .apply()
            _cachedIgCount.value = 0
            _cachedYtCount.value = 0
            _todayIgSeconds.value = 0L
            _todayYtSeconds.value = 0L
        }
        _currentDateKey.value = today
    }

    /**
     * Combines Room DB counts with instant SharedPreferences cache (taking max so counts are never lost
     * across app restarts and are available on frame 0) plus real-time foreground usage duration.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val todayBreakdownState: StateFlow<DailyBreakdown> = combine(
        _currentDateKey.flatMapLatest { dateKey ->
            reelDao.observeDailyBreakdown(dateKey)
        },
        _cachedIgCount,
        _cachedYtCount,
        _todayIgSeconds,
        _todayYtSeconds
    ) { tuples, cachedIg, cachedYt, igSecs, ytSecs ->
        var roomIg = 0
        var roomYt = 0
        for (tuple in tuples) {
            when (ReelPlatform.fromId(tuple.platform)) {
                ReelPlatform.INSTAGRAM -> roomIg += tuple.total
                ReelPlatform.YOUTUBE -> roomYt += tuple.total
            }
        }
        val finalIg = max(roomIg, cachedIg)
        val finalYt = max(roomYt, cachedYt)

        // Keep cached prefs synced if Room had higher historical count for today
        if (finalIg != cachedIg || finalYt != cachedYt) {
            prefs.edit()
                .putInt(KEY_TODAY_IG_COUNT, finalIg)
                .putInt(KEY_TODAY_YT_COUNT, finalYt)
                .apply()
        }

        DailyBreakdown(
            instagramCount = finalIg,
            youtubeCount = finalYt,
            instagramSeconds = igSecs,
            youtubeSeconds = ytSecs
        )
    }.stateIn(
        scope,
        SharingStarted.Eagerly,
        DailyBreakdown(
            instagramCount = _cachedIgCount.value,
            youtubeCount = _cachedYtCount.value,
            instagramSeconds = _todayIgSeconds.value,
            youtubeSeconds = _todayYtSeconds.value
        )
    )

    fun refreshDateKeyIfNeeded() {
        val nowKey = todayDateKey()
        if (_currentDateKey.value != nowKey || prefs.getString(KEY_SAVED_DATE, null) != nowKey) {
            reconcileDailyPrefsWithDate()
        }
    }

    fun todayDateKey(): String {
        return LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
    }

    suspend fun getTodayCountSnapshot(): Int {
        refreshDateKeyIfNeeded()
        val roomTotal = reelDao.getDailyTotalSnapshot(todayDateKey())
        val cachedTotal = _cachedIgCount.value + _cachedYtCount.value
        return max(roomTotal, cachedTotal)
    }

    fun observeAllTimeTotal(): Flow<Int> {
        return reelDao.observeAllTimeTotal().map { roomAllTime ->
            max(roomAllTime, todayBreakdownState.value.total)
        }
    }

    fun observeDailyTotalsInRange(startDateKey: String, endDateKey: String): Flow<List<DailyCountTuple>> {
        return reelDao.observeDailyTotalsInRange(startDateKey, endDateKey)
    }

    /**
     * Records a Reel or Short swipe:
     * 1. Immediately increments the SharedPreferences-backed StateFlow so the Floating HUD
     *    updates instantaneously on the same frame and continues seamlessly from the last count.
     * 2. Persists the event in Room Database for weekly charts and all-time history.
     */
    @Synchronized
    fun recordReelSwipeAsync(platform: ReelPlatform, count: Int = 1) {
        refreshDateKeyIfNeeded()
        val today = todayDateKey()

        when (platform) {
            ReelPlatform.INSTAGRAM -> {
                val next = _cachedIgCount.value + count
                _cachedIgCount.value = next
                prefs.edit()
                    .putString(KEY_SAVED_DATE, today)
                    .putInt(KEY_TODAY_IG_COUNT, next)
                    .apply()
            }
            ReelPlatform.YOUTUBE -> {
                val next = _cachedYtCount.value + count
                _cachedYtCount.value = next
                prefs.edit()
                    .putString(KEY_SAVED_DATE, today)
                    .putInt(KEY_TODAY_YT_COUNT, next)
                    .apply()
            }
        }

        scope.launch {
            val entity = ReelEntity(
                timestamp = System.currentTimeMillis(),
                platform = platform.id,
                count = count,
                dateKey = today
            )
            reelDao.insertReel(entity)
            ReelGlanceWidget.updateAllWidgets(appContext)
        }
    }

    /**
     * Adds elapsed foreground time (in seconds) spent inside Instagram or YouTube today.
     */
    @Synchronized
    fun addPlatformUsageSeconds(platform: ReelPlatform, deltaSeconds: Long) {
        if (deltaSeconds <= 0L) return
        refreshDateKeyIfNeeded()
        val today = todayDateKey()

        when (platform) {
            ReelPlatform.INSTAGRAM -> {
                val next = _todayIgSeconds.value + deltaSeconds
                _todayIgSeconds.value = next
                prefs.edit()
                    .putString(KEY_SAVED_DATE, today)
                    .putLong(KEY_TODAY_IG_SECONDS, next)
                    .apply()
            }
            ReelPlatform.YOUTUBE -> {
                val next = _todayYtSeconds.value + deltaSeconds
                _todayYtSeconds.value = next
                prefs.edit()
                    .putString(KEY_SAVED_DATE, today)
                    .putLong(KEY_TODAY_YT_SECONDS, next)
                    .apply()
            }
        }
    }

    fun setHudEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_HUD_ENABLED, enabled).apply()
        _hudEnabled.value = enabled
    }

    fun setHapticsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_HAPTICS_ENABLED, enabled).apply()
        _hapticsEnabled.value = enabled
    }

    fun setDailyLimit(limit: Int) {
        val coerced = limit.coerceIn(10, 1000)
        prefs.edit().putInt(KEY_DAILY_LIMIT, coerced).apply()
        _dailyLimit.value = coerced
    }

    fun setActiveForegroundPlatform(platform: ReelPlatform?) {
        _activeForegroundPlatform.value = platform
    }

    fun saveHudPosition(x: Int, y: Int) {
        prefs.edit()
            .putInt(KEY_HUD_X, x)
            .putInt(KEY_HUD_Y, y)
            .putBoolean(KEY_HUD_HAS_CUSTOM_POS, true)
            .apply()
    }

    fun getHudPosition(): Pair<Int, Int>? {
        if (!prefs.getBoolean(KEY_HUD_HAS_CUSTOM_POS, false)) return null
        return prefs.getInt(KEY_HUD_X, 24) to prefs.getInt(KEY_HUD_Y, 140)
    }

    companion object {
        private const val PREFS_NAME = "blackout_reel_counter_prefs"
        private const val KEY_HUD_ENABLED = "hud_enabled"
        private const val KEY_HAPTICS_ENABLED = "haptics_enabled"
        private const val KEY_DAILY_LIMIT = "daily_limit"
        private const val KEY_HUD_X = "hud_x"
        private const val KEY_HUD_Y = "hud_y"
        private const val KEY_HUD_HAS_CUSTOM_POS = "hud_has_custom_pos"

        private const val KEY_SAVED_DATE = "saved_date_key"
        private const val KEY_TODAY_IG_COUNT = "today_ig_count"
        private const val KEY_TODAY_YT_COUNT = "today_yt_count"
        private const val KEY_TODAY_IG_SECONDS = "today_ig_seconds"
        private const val KEY_TODAY_YT_SECONDS = "today_yt_seconds"

        @Volatile
        private var INSTANCE: ReelRepository? = null

        fun getInstance(context: Context): ReelRepository {
            return INSTANCE ?: synchronized(this) {
                val db = ReelDatabase.getInstance(context)
                INSTANCE ?: ReelRepository(db.reelDao(), context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }
}
