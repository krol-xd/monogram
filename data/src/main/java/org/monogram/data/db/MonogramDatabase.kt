package org.monogram.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import org.monogram.data.db.dao.ChatDao
import org.monogram.data.db.dao.MessageDao
import org.monogram.data.db.dao.UserDao
import org.monogram.data.db.model.ChatEntity
import org.monogram.data.db.model.MessageEntity
import org.monogram.data.db.model.UserEntity

@Database(entities = [ChatEntity::class, MessageEntity::class, UserEntity::class], version = 3, exportSchema = false)
abstract class MonogramDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun messageDao(): MessageDao
    abstract fun userDao(): UserDao
}