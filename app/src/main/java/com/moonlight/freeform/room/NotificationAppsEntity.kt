package com.moonlight.freeform.room

import androidx.room.Entity
import androidx.room.PrimaryKey


/**
 * @author sunshine
 * @date 2021/1/31
 * Database entity for apps that open in a freeform window
 */
@Entity
class NotificationAppsEntity(
//    @PrimaryKey(autoGenerate = true)
//    val id: Int,
    @PrimaryKey
    val packageName: String,
    //Added 2021-06-04: user identifier
    val userId: Int = 0
)

