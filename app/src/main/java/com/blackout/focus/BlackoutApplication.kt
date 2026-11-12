package com.blackout.focus

import android.app.Application
import com.blackout.focus.data.reel.ReelRepository
import com.blackout.focus.data.repository.BlackoutRepository
import com.blackout.focus.data.repository.ScheduleRepository
import com.blackout.focus.service.ScheduleAlarmScheduler
import com.blackout.focus.service.BlackoutForegroundService
import com.blackout.focus.util.FlashlightController
import com.blackout.focus.util.PermissionHelper
import com.blackout.focus.util.RootShellController

class BlackoutApplication : Application() {

    lateinit var repository: BlackoutRepository
        private set

    lateinit var scheduleRepository: ScheduleRepository
        private set

    lateinit var reelRepository: ReelRepository
        private set

    lateinit var flashlightController: FlashlightController
        private set

    lateinit var rootShellController: RootShellController
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        repository = BlackoutRepository.getInstance(this)
        scheduleRepository = ScheduleRepository.getInstance(this)
        reelRepository = ReelRepository.getInstance(this)
        flashlightController = FlashlightController.getInstance(this)
        rootShellController = RootShellController.getInstance(this)
        rootShellController.checkAndInitializeRootAsync()

        if (repository.reconcileSessionStateOnStartup()) {
            if (PermissionHelper.hasOverlayPermission(this)) {
                BlackoutForegroundService.startService(this)
            }
        }
        ScheduleAlarmScheduler.evaluateAndScheduleNext(this)
    }

    companion object {
        @Volatile
        private var instance: BlackoutApplication? = null

        fun getInstance(): BlackoutApplication {
            return instance ?: throw IllegalStateException("BlackoutApplication not initialized")
        }
    }
}
