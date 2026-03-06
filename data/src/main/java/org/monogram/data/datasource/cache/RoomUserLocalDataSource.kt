package org.monogram.data.datasource.cache

import org.drinkless.tdlib.TdApi
import org.monogram.data.db.dao.UserDao
import org.monogram.data.db.model.UserEntity
import java.util.concurrent.ConcurrentHashMap

class RoomUserLocalDataSource(
    private val userDao: UserDao
) : UserLocalDataSource {
    private val fullInfos = ConcurrentHashMap<Long, TdApi.UserFullInfo>()
    private val users = ConcurrentHashMap<Long, TdApi.User>()

    override suspend fun getUser(userId: Long): TdApi.User? = users[userId]

    override suspend fun putUser(user: TdApi.User) {
        users[user.id] = user
    }

    override suspend fun getUserFullInfo(userId: Long): TdApi.UserFullInfo? = fullInfos[userId]

    override suspend fun putUserFullInfo(userId: Long, info: TdApi.UserFullInfo) {
        fullInfos[userId] = info
    }

    override suspend fun getAllUsers(): Collection<TdApi.User> = users.values

    override suspend fun clearAll() {
        fullInfos.clear()
        users.clear()
    }

    suspend fun saveUser(user: UserEntity) = userDao.insertUser(user)
    suspend fun loadUser(userId: Long) = userDao.getUser(userId)
    suspend fun deleteUser(userId: Long) = userDao.deleteUser(userId)
    suspend fun clearDatabase() = userDao.clearAll()
}