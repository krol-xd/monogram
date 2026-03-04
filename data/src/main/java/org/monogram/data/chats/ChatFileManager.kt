package org.monogram.data.chats

import org.monogram.core.DispatcherProvider
import org.monogram.core.ScopeProvider
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import org.monogram.data.gateway.TelegramGateway
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap


class ChatFileManager(
    private val gateway: TelegramGateway,
    private val dispatchers: DispatcherProvider,
    scopeProvider: ScopeProvider,
    private val onUpdate: () -> Unit
) {
    private val scope = scopeProvider.appScope

    private val downloadingFiles: MutableSet<Int> = Collections.newSetFromMap(ConcurrentHashMap())
    private val loadingEmojis: MutableSet<Long> = Collections.newSetFromMap(ConcurrentHashMap())
    private val filePaths = ConcurrentHashMap<Int, String>()
    private val emojiPathsCache = ConcurrentHashMap<Long, String>()
    private val fileIdToEmojiId = ConcurrentHashMap<Int, Long>()
    private val chatPhotoIds = ConcurrentHashMap<Int, Long>()

    fun getFilePath(fileId: Int): String? = filePaths[fileId]
    fun getEmojiPath(emojiId: Long): String? = emojiPathsCache[emojiId]
    fun getChatIdByPhotoId(fileId: Int): Long? = chatPhotoIds[fileId]

    fun registerChatPhoto(fileId: Int, chatId: Long) {
        chatPhotoIds[fileId] = chatId
    }

    fun handleFileUpdate(file: TdApi.File): Boolean {
        if (file.local.isDownloadingCompleted) {
            filePaths[file.id] = file.local.path
            return handleFileUpdated(file.id, file.local.path)
        }
        return false
    }

    private fun handleFileUpdated(fileId: Int, path: String): Boolean {
        if (path.isEmpty()) return false
        var updated = false
        fileIdToEmojiId[fileId]?.let { emojiId ->
            emojiPathsCache[emojiId] = path
            updated = true
        }
        if (chatPhotoIds.containsKey(fileId)) updated = true
        return updated
    }

    fun downloadFile(fileId: Int, priority: Int, offset: Long = 0, limit: Long = 0, synchronous: Boolean = true) {
        if (fileId == 0) return
        if (downloadingFiles.add(fileId)) {
            scope.launch(dispatchers.io) {
                runCatching {
                    gateway.execute(TdApi.DownloadFile(fileId, priority, offset, limit, synchronous))
                }
                downloadingFiles.remove(fileId)
            }
        }
    }

    fun loadEmoji(emojiId: Long) {
        if (emojiId == 0L || emojiPathsCache.containsKey(emojiId)) return
        if (loadingEmojis.add(emojiId)) {
            scope.launch(dispatchers.io) {
                runCatching {
                    val result = gateway.execute(TdApi.GetCustomEmojiStickers(longArrayOf(emojiId)))
                    val sticker = result.stickers.firstOrNull() ?: return@launch
                    val file = sticker.sticker
                    val path = file.local.path.ifEmpty { filePaths[file.id] ?: "" }
                    fileIdToEmojiId[file.id] = emojiId
                    if (path.isNotEmpty()) {
                        emojiPathsCache[emojiId] = path
                        onUpdate()
                    } else {
                        downloadFile(file.id, 32)
                    }
                }
                loadingEmojis.remove(emojiId)
            }
        }
    }
}