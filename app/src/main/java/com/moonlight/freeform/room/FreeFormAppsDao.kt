package com.moonlight.freeform.room

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * @author sunshine
 * @date 2021/1/31
 */
@Dao
interface FreeFormAppsDao {

    @Query("INSERT INTO FreeFormAppsEntity(packageName, userId) VALUES(:packageName, :userId)")
    fun insert(packageName: String, userId: Int)

    @Query("DELETE FROM FreeFormAppsEntity WHERE packageName = :packageName and userId = :userId")
    fun delete(packageName: String, userId: Int)

    @Query("SELECT * FROM FreeFormAppsEntity")
    fun getAll() : LiveData<List<FreeFormAppsEntity>?>

    @Query("SELECT * FROM FreeFormAppsEntity")
    fun getAllByFlow() : Flow<List<FreeFormAppsEntity>?>

    @Query("SELECT packageName FROM FreeFormAppsEntity")
    fun getAllName() : LiveData<List<String>?>

    @Query("SELECT packageName FROM FreeFormAppsEntity")
    fun getAllWithoutLiveData() : List<String>?

    // Backup: read full entities (with the sortNum primary key and userId); getAllWithoutLiveData only returns packageName, which is not enough.
    @Query("SELECT * FROM FreeFormAppsEntity ORDER BY sortNum ASC")
    fun getAllEntitiesSync(): List<FreeFormAppsEntity>

    // Restore: keep the original sortNum ordering key. Using the insertApps statement that omits sortNum
    // would take an auto-incremented new id, scrambling the app order when restoring across devices.
    @Query("INSERT OR REPLACE INTO FreeFormAppsEntity(sortNum, packageName, userId) VALUES(:sortNum, :packageName, :userId)")
    fun insertWithSortNum(sortNum: Int, packageName: String, userId: Int)

    @Query("SELECT COUNT(*) FROM FreeFormAppsEntity")
    fun getCount(): Int

    @Query("DELETE FROM FreeFormAppsEntity")
    fun deleteAll()

    @Delete
    fun deleteList(freeFormAppsEntityList: List<FreeFormAppsEntity>)

    @Update
    fun update(entity: FreeFormAppsEntity)
}