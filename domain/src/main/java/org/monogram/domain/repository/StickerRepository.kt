package org.monogram.domain.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.monogram.domain.models.GifModel
import org.monogram.domain.models.RecentEmojiModel
import org.monogram.domain.models.StickerModel
import org.monogram.domain.models.StickerSetModel

interface StickerRepository {
    val installedStickerSets: StateFlow<List<StickerSetModel>>
    val customEmojiStickerSets: StateFlow<List<StickerSetModel>>
    val archivedStickerSets: StateFlow<List<StickerSetModel>>
    val archivedEmojiSets: StateFlow<List<StickerSetModel>>
    val recentEmojis: Flow<List<RecentEmojiModel>>

    suspend fun loadInstalledStickerSets()
    suspend fun loadCustomEmojiStickerSets()
    suspend fun loadArchivedStickerSets()
    suspend fun loadArchivedEmojiSets()
    suspend fun getDefaultEmojis(): List<String>
    suspend fun getRecentStickers(): List<StickerModel>
    suspend fun addRecentEmoji(recentEmoji: RecentEmojiModel)
    suspend fun clearRecentStickers()
    suspend fun clearRecentEmojis()

    fun getStickerFile(fileId: Long): Flow<String?>
    fun getGifFile(gif: GifModel): Flow<String?>
    suspend fun searchGifs(query: String): List<GifModel>
    suspend fun getSavedGifs(): List<GifModel>
    suspend fun addSavedGif(path: String)
    suspend fun getTgsJson(path: String): String?
    fun clearCache()
    suspend fun getMessageAvailableReactions(chatId: Long, messageId: Long): List<String>

    suspend fun getStickerSet(setId: Long): StickerSetModel?
    suspend fun getStickerSetByName(name: String): StickerSetModel?
    suspend fun verifyStickerSet(setId: Long)
    suspend fun toggleStickerSetInstalled(setId: Long, isInstalled: Boolean)
    suspend fun toggleStickerSetArchived(setId: Long, isArchived: Boolean)
    suspend fun reorderStickerSets(stickerType: TdLibStickerType, stickerSetIds: List<Long>)

    suspend fun searchEmojis(query: String): List<String>
    suspend fun searchCustomEmojis(query: String): List<StickerModel>
    suspend fun searchStickers(query: String): List<StickerModel>
    suspend fun searchStickerSets(query: String): List<StickerSetModel>

    enum class TdLibStickerType {
        REGULAR, CUSTOM_EMOJI, MASK
    }
}
