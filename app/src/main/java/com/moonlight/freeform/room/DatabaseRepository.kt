package com.moonlight.freeform.room

import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import com.moonlight.freeform.room.MyDatabase.Companion.getDatabase
import kotlinx.coroutines.flow.Flow
import java.lang.Exception

/**
 * @author sunshine
 * @date 2021/1/31
 */
class DatabaseRepository(context: Context) {

    private val freeFormAppsDao: FreeFormAppsDao
    private val notificationAppsDao: NotificationAppsDao

    fun insertFreeForm(packageName: String, userId: Int) {
        try {
            freeFormAppsDao.insert(packageName, userId)
        }catch (e: Exception) {
            // An insert failure means the entry silently disappears after the user picks an app in the sidebar; we must not stay silent.
            Log.w(TAG, "insert_freeform_failed pkg=$packageName user=$userId", e)
        }
    }

    fun deleteFreeForm(packageName: String, userId: Int) {
        freeFormAppsDao.delete(packageName, userId)
    }

    fun getAllFreeFormName(): LiveData<List<String>?> {
        return freeFormAppsDao.getAllName()
    }

    fun getAllFreeForm() : LiveData<List<FreeFormAppsEntity>?> {
        return freeFormAppsDao.getAll()
    }

    fun getAllFreeFormAppsByFlow(): Flow<List<FreeFormAppsEntity>?> {
        return freeFormAppsDao.getAllByFlow()
    }

    fun getCount(): Int {
        return freeFormAppsDao.getCount()
    }

    fun update(entity: FreeFormAppsEntity) {
        freeFormAppsDao.update(entity)
    }

    fun getAllFreeFormWithoutLiveData() : List<String>? {
        return freeFormAppsDao.getAllWithoutLiveData()
    }

    // Used by backup/restore; see the comment on the same-named DAO method.
    fun getAllFreeFormEntitiesSync(): List<FreeFormAppsEntity> {
        return freeFormAppsDao.getAllEntitiesSync()
    }

    fun insertFreeFormWithSortNum(sortNum: Int, packageName: String, userId: Int) {
        try {
            freeFormAppsDao.insertWithSortNum(sortNum, packageName, userId)
        } catch (e: Exception) {
            Log.w(TAG, "insert_freeform_with_sort_failed sort=$sortNum pkg=$packageName user=$userId", e)
        }
    }

    fun getAllNotificationEntitiesSync(): List<NotificationAppsEntity> {
        return notificationAppsDao.getAllEntitiesSync()
    }

    fun insertNotificationWithUserId(packageName: String, userId: Int) {
        try {
            notificationAppsDao.insertWithUserId(packageName, userId)
        } catch (e: Exception) {
            Log.w(TAG, "insert_notification_with_user_failed pkg=$packageName user=$userId", e)
        }
    }

    fun deleteAllFreeForm() {
        freeFormAppsDao.deleteAll()
    }

    fun deleteMore(freeFormAppsEntityList: List<FreeFormAppsEntity>) {
        freeFormAppsDao.deleteList(freeFormAppsEntityList)
    }

    fun insertNotification(packageName: String, userId: Int) {
        try {
            notificationAppsDao.insert(packageName, userId)
        }catch (e: Exception) {
            Log.w(TAG, "insert_notification_failed pkg=$packageName user=$userId", e)
        }

    }

    fun deleteNotification(packageName: String, userId: Int) {
        notificationAppsDao.delete(packageName, userId)
    }

    fun getAllNotification(): LiveData<List<NotificationAppsEntity>?> {
        return notificationAppsDao.getAll()
    }

    fun getAllNotificationByFlow(): Flow<List<NotificationAppsEntity>?> {
        return notificationAppsDao.getAllByFlow()
    }

    fun deleteAllNotification() {
        notificationAppsDao.deleteAll()
    }

    init {
        val database = getDatabase(context)
        freeFormAppsDao = database.freeFormAppsDao
        notificationAppsDao = database.notificationAppsDao
    }

    companion object {
        private const val TAG = "DatabaseRepository"
    }
}