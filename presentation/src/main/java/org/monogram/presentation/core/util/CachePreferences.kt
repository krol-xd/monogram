package org.monogram.presentation.core.util

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import org.monogram.domain.models.AttachMenuBotModel
import org.monogram.domain.models.FolderModel
import org.monogram.domain.models.GifModel
import org.monogram.domain.models.RecentEmojiModel
import org.monogram.domain.repository.CacheProvider

class CachePreferences(private val context: Context) : CacheProvider {
    private val prefs: SharedPreferences = context.getSharedPreferences("monogram_cache", Context.MODE_PRIVATE)

    private val _recentEmojis = MutableStateFlow(getRecentEmojisFromPrefs())
    override val recentEmojis: StateFlow<List<RecentEmojiModel>> = _recentEmojis

    private val _searchHistory = MutableStateFlow(getSearchHistoryFromPrefs())
    override val searchHistory: StateFlow<List<Long>> = _searchHistory

    private val _chatFolders = MutableStateFlow(getChatFoldersFromPrefs())
    override val chatFolders: StateFlow<List<FolderModel>> = _chatFolders

    private val _attachBots = MutableStateFlow(getAttachBotsFromPrefs())
    override val attachBots: StateFlow<List<AttachMenuBotModel>> = _attachBots

    private val _cachedSimCountryIso = MutableStateFlow(prefs.getString(KEY_CACHED_SIM_COUNTRY_ISO, null))
    override val cachedSimCountryIso: StateFlow<String?> = _cachedSimCountryIso

    private val _savedGifs = MutableStateFlow(getSavedGifsFromPrefs())
    override val savedGifs: StateFlow<List<GifModel>> = _savedGifs

    override fun addRecentEmoji(recentEmoji: RecentEmojiModel) {
        val current = _recentEmojis.value.toMutableList()
        current.removeIf { it.emoji == recentEmoji.emoji && it.sticker?.id == recentEmoji.sticker?.id }
        current.add(0, recentEmoji)
        if (current.size > 50) {
            current.removeAt(current.lastIndex)
        }
        _recentEmojis.value = current
        prefs.edit().putString(KEY_RECENT_EMOJIS, Json.encodeToString(current)).apply()
    }

    override fun clearRecentEmojis() {
        _recentEmojis.value = emptyList()
        prefs.edit().remove(KEY_RECENT_EMOJIS).apply()
    }

    override fun addSearchChatId(chatId: Long) {
        val current = _searchHistory.value.toMutableList()
        current.remove(chatId)
        current.add(0, chatId)
        if (current.size > 40) {
            current.removeAt(current.lastIndex)
        }
        _searchHistory.value = current
        prefs.edit().putString(KEY_SEARCH_HISTORY, Json.encodeToString(current)).apply()
    }

    override fun removeSearchChatId(chatId: Long) {
        val current = _searchHistory.value.toMutableList()
        if (current.remove(chatId)) {
            _searchHistory.value = current
            prefs.edit().putString(KEY_SEARCH_HISTORY, Json.encodeToString(current)).apply()
        }
    }

    override fun clearSearchHistory() {
        _searchHistory.value = emptyList()
        prefs.edit().remove(KEY_SEARCH_HISTORY).apply()
    }

    override fun setChatFolders(folders: List<FolderModel>) {
        prefs.edit().putString(KEY_CHAT_FOLDERS, Json.encodeToString(folders)).apply()
        _chatFolders.value = folders
    }

    override fun setAttachBots(bots: List<AttachMenuBotModel>) {
        prefs.edit().putString(KEY_ATTACH_BOTS, Json.encodeToString(bots)).apply()
        _attachBots.value = bots
    }

    override fun setCachedSimCountryIso(iso: String?) {
        if (iso != null) {
            prefs.edit().putString(KEY_CACHED_SIM_COUNTRY_ISO, iso).apply()
        } else {
            prefs.edit().remove(KEY_CACHED_SIM_COUNTRY_ISO).apply()
        }
        _cachedSimCountryIso.value = iso
    }

    override fun saveChatScrollPosition(chatId: Long, messageId: Long) {
        prefs.edit().putLong("chat_scroll_$chatId", messageId).apply()
    }

    override fun getChatScrollPosition(chatId: Long): Long {
        return prefs.getLong("chat_scroll_$chatId", 0L)
    }

    override fun setSavedGifs(gifs: List<GifModel>) {
        prefs.edit().putString(KEY_SAVED_GIFS, Json.encodeToString(gifs)).apply()
        _savedGifs.value = gifs
    }

    private fun getRecentEmojisFromPrefs(): List<RecentEmojiModel> {
        val json = prefs.getString(KEY_RECENT_EMOJIS, null) ?: return emptyList()
        return try {
            Json.decodeFromString(json)
        } catch (e: Exception) {
            try {
                Json.decodeFromString<List<String>>(json).map { RecentEmojiModel(it) }
            } catch (e2: Exception) {
                emptyList()
            }
        }
    }

    private fun getSearchHistoryFromPrefs(): List<Long> {
        return try {
            val json = prefs.getString(KEY_SEARCH_HISTORY, null) ?: return emptyList()
            Json.decodeFromString<List<Long>>(json)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun getChatFoldersFromPrefs(): List<FolderModel> {
        val json = prefs.getString(KEY_CHAT_FOLDERS, null) ?: return emptyList()
        return try {
            Json.decodeFromString(json)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun getAttachBotsFromPrefs(): List<AttachMenuBotModel> {
        val json = prefs.getString(KEY_ATTACH_BOTS, null) ?: return emptyList()
        return try {
            Json.decodeFromString(json)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun getSavedGifsFromPrefs(): List<GifModel> {
        val json = prefs.getString(KEY_SAVED_GIFS, null) ?: return emptyList()
        return try {
            Json.decodeFromString(json)
        } catch (e: Exception) {
            emptyList()
        }
    }

    companion object {
        private const val KEY_RECENT_EMOJIS = "recent_emojis"
        private const val KEY_SEARCH_HISTORY = "search_history"
        private const val KEY_CHAT_FOLDERS = "chat_folders"
        private const val KEY_ATTACH_BOTS = "attach_bots"
        private const val KEY_CACHED_SIM_COUNTRY_ISO = "cached_sim_country_iso"
        private const val KEY_SAVED_GIFS = "saved_gifs"
    }
}
