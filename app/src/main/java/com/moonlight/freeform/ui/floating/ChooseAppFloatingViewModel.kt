package com.moonlight.freeform.ui.floating

import android.content.Context
import com.moonlight.freeform.room.DatabaseRepository
import com.moonlight.freeform.room.FreeFormAppsEntity
import kotlinx.coroutines.flow.Flow

/**
 * @author sunshine
 * @date 2022/1/6
 */
class ChooseAppFloatingViewModel(context: Context) {
    private val repository = DatabaseRepository(context)

    fun getAllFreeFormApps(): Flow<List<FreeFormAppsEntity>?> {
        return repository.getAllFreeFormAppsByFlow()
    }

    fun deleteNotInstall(notInstallList: List<FreeFormAppsEntity>) {
        repository.deleteMore(notInstallList)
    }
}