package org.monogram.data.db.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chats")
data class ChatEntity(
    @PrimaryKey val id: Long,
    val title: String,
    val unreadCount: Int,
    val avatarPath: String?,
    val lastMessageText: String,
    val lastMessageTime: String,
    val order: Long,
    val isPinned: Boolean,
    val createdAt: Long = System.currentTimeMillis()
)