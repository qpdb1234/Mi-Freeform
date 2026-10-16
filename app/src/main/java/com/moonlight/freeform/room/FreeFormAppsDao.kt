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

    // 备份用：取完整实体（含 sortNum 主键和 userId），getAllWithoutLiveData 只给 packageName 不够。
    @Query("SELECT * FROM FreeFormAppsEntity ORDER BY sortNum ASC")
    fun getAllEntitiesSync(): List<FreeFormAppsEntity>

    // 恢复用：保留原始 sortNum 排序主键，用 insertApps 那条不带 sortNum 的语句
    // 会拿到自增的新号，跨设备恢复时应用顺序就乱了。
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