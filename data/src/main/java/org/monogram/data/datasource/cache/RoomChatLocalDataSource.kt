package org.monogram.data.datasource.cache

import kotlinx.coroutines.flow.Flow
import org.monogram.data.db.dao.ChatDao
import org.monogram.data.db.dao.MessageDao
import org.monogram.data.db.model.ChatEntity
import org.monogram.data.db.model.MessageEntity

class RoomChatLocalDataSource(
    private val chatDao: ChatDao,
    private val messageDao: MessageDao
) : ChatLocalDataSource {
    override fun getAllChats(): Flow<List<ChatEntity>> = chatDao.getAllChats()

    override suspend fun insertChat(chat: ChatEntity) = chatDao.insertChat(chat)

    override suspend fun insertChats(chats: List<ChatEntity>) = chatDao.insertChats(chats)

    override suspend fun deleteChat(chatId: Long) = chatDao.deleteChat(chatId)

    override suspend fun clearAllChats() = chatDao.clearAll()

    override fun getMessagesForChat(chatId: Long): Flow<List<MessageEntity>> = messageDao.getMessagesForChat(chatId)

    override suspend fun getMessagesOlder(chatId: Long, fromMessageId: Long, limit: Int) = messageDao.getMessagesOlder(chatId, fromMessageId, limit)

    override suspend fun getMessagesNewer(chatId: Long, fromMessageId: Long, limit: Int) = messageDao.getMessagesNewer(chatId, fromMessageId, limit)

    override suspend fun insertMessage(message: MessageEntity) = messageDao.insertMessage(message)

    override suspend fun insertMessages(messages: List<MessageEntity>) = messageDao.insertMessages(messages)

    override suspend fun deleteMessage(messageId: Long) = messageDao.deleteMessage(messageId)

    override suspend fun clearMessagesForChat(chatId: Long) = messageDao.clearMessagesForChat(chatId)

    override suspend fun deleteExpired(timestamp: Long) {
        chatDao.deleteExpired(timestamp)
        messageDao.deleteExpired(timestamp)
    }
}