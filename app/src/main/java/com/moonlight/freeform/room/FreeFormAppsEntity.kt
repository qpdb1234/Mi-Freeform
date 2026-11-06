package com.moonlight.freeform.room

import androidx.room.Entity
import androidx.room.PrimaryKey


/**
 * @author sunshine
 * @date 2021/1/31
 * Database entity for apps that open in a freeform window
 */
@Entity
class FreeFormAppsEntity(
    //Used for ordering
    @PrimaryKey(autoGenerate = true)
    val sortNum: Int,
    var packageName: String,
    //Added 2021-06-04: user identifier
    var userId: Int = 0
) {
    override fun toString(): String {
        return "FreeFormAppsEntity(sortNum=$sortNum, packageName='$packageName')"
    }
}

