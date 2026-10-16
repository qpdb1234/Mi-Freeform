package com.moonlight.freeform.room

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * @author sunshine
 * @date 2021/1/31
 */
@Dao
interface NotificationAppsDao {

    @Query("INSERT INTO NotificationAppsEntity(packageName, userId) VALUES(:packageName, :userId)")
    fun insert(packageName: String, userId: Int)

    @Query("DELETE FROM NotificationAppsEntity WHERE packageName = :packageName and userId = :userId")
    fun delete(packageName: String, userId: Int)

    @Query("SELECT * FROM NotificationAppsEntity")
    fun getAll() : LiveData<List<NotificationAppsEntity>?>

    @Query("SELECT * FROM NotificationAppsEntity")
    fun getAllByFlow() : Flow<List<NotificationAppsEntity>?>

    // 备份用：直接取快照，不订阅 LiveData。
    @Query("SELECT * FROM NotificationAppsEntity")
    fun getAllEntitiesSync(): List<NotificationAppsEntity>

    // 恢复用：恢复时先 deleteAll 再逐条插回来。
    @Query("INSERT OR REPLACE INTO NotificationAppsEntity(packageName, userId) VALUES(:packageName, :userId)")
    fun insertWithUserId(packageName: String, userId: Int)

    @Query("DELETE FROM NotificationAppsEntity")
    fun deleteAll()
}