package com.blackout.focus.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.blackout.focus.data.model.BlackoutSchedule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists multiple recurring Blackout schedules in Device-Protected Storage SharedPreferences
 * using org.json so schedules survive app kills and device reboots.
 */
class ScheduleRepository private constructor(context: Context) {

    private val storageContext: Context = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        context.applicationContext.createDeviceProtectedStorageContext()
    } else {
        context.applicationContext
    }

    private val prefs: SharedPreferences =
        storageContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _schedules = MutableStateFlow(loadSchedulesFromPrefs())
    val schedules: StateFlow<List<BlackoutSchedule>> = _schedules.asStateFlow()

    private fun loadSchedulesFromPrefs(): List<BlackoutSchedule> {
        val raw = prefs.getString(KEY_SCHEDULES_JSON, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            val list = mutableListOf<BlackoutSchedule>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val daysArr = obj.getJSONArray("daysOfWeek")
                val days = mutableSetOf<Int>()
                for (d in 0 until daysArr.length()) {
                    days.add(daysArr.getInt(d))
                }
                list.add(
                    BlackoutSchedule(
                        id = obj.getString("id"),
                        label = obj.optString("label", "Focus Schedule"),
                        startHour = obj.getInt("startHour"),
                        startMinute = obj.getInt("startMinute"),
                        endHour = obj.getInt("endHour"),
                        endMinute = obj.getInt("endMinute"),
                        daysOfWeek = days,
                        isEnabled = obj.optBoolean("isEnabled", true)
                    )
                )
            }
            list
        } catch (_: Throwable) {
            emptyList()
        }
    }

    @Synchronized
    private fun saveSchedulesToPrefs(list: List<BlackoutSchedule>) {
        val array = JSONArray()
        for (item in list) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("label", item.label)
                put("startHour", item.startHour)
                put("startMinute", item.startMinute)
                put("endHour", item.endHour)
                put("endMinute", item.endMinute)
                put("isEnabled", item.isEnabled)
                put("daysOfWeek", JSONArray(item.daysOfWeek.sorted()))
            }
            array.put(obj)
        }
        prefs.edit().putString(KEY_SCHEDULES_JSON, array.toString()).commit()
        _schedules.value = list
    }

    @Synchronized
    fun addSchedule(schedule: BlackoutSchedule) {
        val updated = _schedules.value + schedule
        saveSchedulesToPrefs(updated)
    }

    @Synchronized
    fun toggleSchedule(id: String, enabled: Boolean) {
        val updated = _schedules.value.map {
            if (it.id == id) it.copy(isEnabled = enabled) else it
        }
        saveSchedulesToPrefs(updated)
    }

    @Synchronized
    fun deleteSchedule(id: String) {
        val updated = _schedules.value.filterNot { it.id == id }
        saveSchedulesToPrefs(updated)
    }

    /**
     * Checks all enabled schedules. If [nowMillis] is inside any schedule's active window,
     * returns the target end timestamp (max end time if multiple overlap).
     */
    fun getActiveScheduleEndMillis(nowMillis: Long = System.currentTimeMillis()): Long? {
        val activeEnds = _schedules.value.mapNotNull { it.getActiveWindowEndMillis(nowMillis) }
        return activeEnds.maxOrNull()
    }

    /**
     * Finds the earliest next upcoming schedule start timestamp across all enabled schedules.
     */
    fun getNextUpcomingStartMillis(nowMillis: Long = System.currentTimeMillis()): Long? {
        return _schedules.value.mapNotNull { it.getNextStartMillis(nowMillis) }.minOrNull()
    }

    companion object {
        private const val PREFS_NAME = "blackout_schedules_prefs"
        private const val KEY_SCHEDULES_JSON = "schedules_json"

        @Volatile
        private var INSTANCE: ScheduleRepository? = null

        fun getInstance(context: Context): ScheduleRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ScheduleRepository(context).also { INSTANCE = it }
            }
        }
    }
}
