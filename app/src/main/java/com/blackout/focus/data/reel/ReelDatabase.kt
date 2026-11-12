package com.blackout.focus.data.reel

import android.content.Context
import android.os.Build
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ReelEntity::class],
    version = 1,
    exportSchema = false
)
abstract class ReelDatabase : RoomDatabase() {

    abstract fun reelDao(): ReelDao

    companion object {
        private const val DATABASE_NAME = "blackout_reel_counter.db"

        @Volatile
        private var INSTANCE: ReelDatabase? = null

        fun getInstance(context: Context): ReelDatabase {
            return INSTANCE ?: synchronized(this) {
                val storageContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    context.applicationContext.createDeviceProtectedStorageContext()
                } else {
                    context.applicationContext
                }
                INSTANCE ?: Room.databaseBuilder(
                    storageContext,
                    ReelDatabase::class.java,
                    DATABASE_NAME
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
