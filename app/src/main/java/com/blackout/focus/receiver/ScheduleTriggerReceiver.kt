package com.blackout.focus.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.blackout.focus.service.ScheduleAlarmScheduler

class ScheduleTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        ScheduleAlarmScheduler.evaluateAndScheduleNext(context)
    }

    companion object {
        const val ACTION_TRIGGER_SCHEDULE = "com.blackout.focus.action.TRIGGER_SCHEDULE"
    }
}
