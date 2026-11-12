package com.blackout.focus.data.reel

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ReelDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReel(entity: ReelEntity): Long

    @Query("SELECT COALESCE(SUM(count), 0) FROM reel_logs WHERE date_key = :dateKey")
    fun observeDailyTotal(dateKey: String): Flow<Int>

    @Query("SELECT COALESCE(SUM(count), 0) FROM reel_logs WHERE date_key = :dateKey")
    suspend fun getDailyTotalSnapshot(dateKey: String): Int

    @Query(
        """
        SELECT platform, COALESCE(SUM(count), 0) AS total
        FROM reel_logs
        WHERE date_key = :dateKey
        GROUP BY platform
        """
    )
    fun observeDailyBreakdown(dateKey: String): Flow<List<PlatformCountTuple>>

    @Query("SELECT COALESCE(SUM(count), 0) FROM reel_logs")
    fun observeAllTimeTotal(): Flow<Int>

    @Query("SELECT * FROM reel_logs ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecentLogs(limit: Int = 30): Flow<List<ReelEntity>>

    @Query(
        """
        SELECT date_key, COALESCE(SUM(count), 0) AS total
        FROM reel_logs
        GROUP BY date_key
        ORDER BY date_key DESC
        LIMIT :days
        """
    )
    fun observeRecentDailyTotals(days: Int = 7): Flow<List<DailyCountTuple>>

    @Query(
        """
        SELECT date_key, COALESCE(SUM(count), 0) AS total
        FROM reel_logs
        WHERE date_key >= :startDateKey AND date_key <= :endDateKey
        GROUP BY date_key
        """
    )
    fun observeDailyTotalsInRange(startDateKey: String, endDateKey: String): Flow<List<DailyCountTuple>>

    @Query("DELETE FROM reel_logs WHERE date_key = :dateKey")
    suspend fun clearDay(dateKey: String)

    @Query("DELETE FROM reel_logs")
    suspend fun clearAll()
}
