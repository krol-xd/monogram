package org.monogram.data.repository

import android.content.Context
import org.monogram.core.DispatcherProvider
import org.monogram.core.ScopeProvider
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import org.monogram.data.datasource.remote.StickerRemoteSource
import org.monogram.data.gateway.UpdateDispatcher
import org.monogram.data.infra.EmojiLoader
import org.monogram.data.infra.FileDownloadQueue
import org.monogram.data.infra.FileUpdateHandler
import org.monogram.domain.models.GifModel
import org.monogram.domain.models.RecentEmojiModel
import org.monogram.domain.models.StickerSetModel
import org.monogram.domain.models.StickerType
import org.monogram.domain.repository.CacheProvider
import org.monogram.domain.repository.StickerRepository
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

class StickerRepositoryImpl(
    private val remote: StickerRemoteSource,
    private val fileQueue: FileDownloadQueue,
    private val fileUpdateHandler: FileUpdateHandler,
    private val updates: UpdateDispatcher,
    private val cacheProvider: CacheProvider,
    private val dispatchers: DispatcherProvider,
    private val context: Context,
    scopeProvider: ScopeProvider
) : StickerRepository {

    private val scope = scopeProvider.appScope

    private val _installedStickerSets = MutableStateFlow<List<StickerSetModel>>(emptyList())
    override val installedStickerSets = _installedStickerSets.asStateFlow()

    private val _customEmojiStickerSets = MutableStateFlow<List<StickerSetModel>>(emptyList())
    override val customEmojiStickerSets = _customEmojiStickerSets.asStateFlow()

    private val _archivedStickerSets = MutableStateFlow<List<StickerSetModel>>(emptyList())
    override val archivedStickerSets = _archivedStickerSets.asStateFlow()

    private val _archivedEmojiSets = MutableStateFlow<List<StickerSetModel>>(emptyList())
    override val archivedEmojiSets = _archivedEmojiSets.asStateFlow()

    private val regularMutex = Mutex()
    private val customEmojiMutex = Mutex()
    private val archivedMutex = Mutex()
    private val archivedEmojiMutex = Mutex()

    @Volatile
    private var lastRegularLoadTime = 0L
    @Volatile
    private var lastCustomEmojiLoadTime = 0L

    override val recentEmojis: Flow<List<RecentEmojiModel>> = cacheProvider.recentEmojis

    private val tgsCache = mutableMapOf<String, String>()
    private val filePathsCache = ConcurrentHashMap<Long, String>()
    private var cachedEmojis: List<String>? = null
    private var fallbackEmojisCache: List<String>? = null

    init {
        scope.launch {
            updates.installedStickerSets.collect { update ->
                when (update.stickerType) {
                    is TdApi.StickerTypeRegular -> loadInstalledStickerSets(force = true)
                    is TdApi.StickerTypeCustomEmoji -> loadCustomEmojiStickerSets(force = true)
                }
            }
        }
    }

    override suspend fun loadInstalledStickerSets() = loadInstalledStickerSets(force = false)

    private suspend fun loadInstalledStickerSets(force: Boolean) = regularMutex.withLock {
        val now = System.currentTimeMillis()
        if (!force && _installedStickerSets.value.isNotEmpty()) return@withLock
        if (force && _installedStickerSets.value.isNotEmpty() && now - lastRegularLoadTime < 1000) return@withLock

        val sets = remote.getInstalledStickerSets(StickerType.REGULAR)
        if (force && _installedStickerSets.value.map { it.id } == sets.map { it.id }) {
            lastRegularLoadTime = System.currentTimeMillis()
            return@withLock
        }

        _installedStickerSets.value = sets
        lastRegularLoadTime = System.currentTimeMillis()
    }

    override suspend fun loadCustomEmojiStickerSets() = loadCustomEmojiStickerSets(force = false)

    private suspend fun loadCustomEmojiStickerSets(force: Boolean) = customEmojiMutex.withLock {
        val now = System.currentTimeMillis()
        if (!force && _customEmojiStickerSets.value.isNotEmpty()) return@withLock
        if (force && _customEmojiStickerSets.value.isNotEmpty() && now - lastCustomEmojiLoadTime < 1000) return@withLock

        val sets = remote.getInstalledStickerSets(StickerType.CUSTOM_EMOJI)
        if (force && _customEmojiStickerSets.value.map { it.id } == sets.map { it.id }) {
            lastCustomEmojiLoadTime = System.currentTimeMillis()
            return@withLock
        }

        _customEmojiStickerSets.value = sets
        lastCustomEmojiLoadTime = System.currentTimeMillis()
    }

    override suspend fun loadArchivedStickerSets() = archivedMutex.withLock {
        _archivedStickerSets.value = remote.getArchivedStickerSets(StickerType.REGULAR)
    }

    override suspend fun loadArchivedEmojiSets() = archivedEmojiMutex.withLock {
        _archivedEmojiSets.value = remote.getArchivedStickerSets(StickerType.CUSTOM_EMOJI)
    }

    override suspend fun getStickerSet(setId: Long) = remote.getStickerSet(setId)

    override suspend fun getStickerSetByName(name: String) = remote.getStickerSetByName(name)

    override suspend fun toggleStickerSetInstalled(setId: Long, isInstalled: Boolean) {
        remote.toggleStickerSetInstalled(setId, isInstalled)
        invalidateStickerSetCaches()
    }

    override suspend fun toggleStickerSetArchived(setId: Long, isArchived: Boolean) {
        remote.toggleStickerSetArchived(setId, isArchived)
        invalidateStickerSetCaches()
        scope.launch {
            loadArchivedStickerSets()
            loadArchivedEmojiSets()
        }
    }

    override suspend fun reorderStickerSets(
        stickerType: StickerRepository.TdLibStickerType,
        stickerSetIds: List<Long>
    ) {
        val type = when (stickerType) {
            StickerRepository.TdLibStickerType.REGULAR -> StickerType.REGULAR
            StickerRepository.TdLibStickerType.CUSTOM_EMOJI -> StickerType.CUSTOM_EMOJI
            StickerRepository.TdLibStickerType.MASK -> StickerType.MASK
        }
        remote.reorderStickerSets(type, stickerSetIds)
    }

    override suspend fun getDefaultEmojis(): List<String> {
        cachedEmojis?.let { return it }

        val fetched = remote.getEmojiCategories().toMutableSet()
        if (fetched.size < 100) fetched.addAll(getFallbackEmojis())

        return fetched.toList().also { cachedEmojis = it }
    }

    override suspend fun searchEmojis(query: String) = remote.searchEmojis(query)

    override suspend fun searchCustomEmojis(query: String) = remote.searchCustomEmojis(query)

    override suspend fun getMessageAvailableReactions(chatId: Long, messageId: Long) =
        remote.getMessageAvailableReactions(chatId, messageId)

    override suspend fun getRecentStickers() = remote.getRecentStickers()

    override suspend fun clearRecentStickers() = remote.clearRecentStickers()

    override suspend fun searchStickers(query: String) = remote.searchStickers(query)

    override suspend fun searchStickerSets(query: String) = remote.searchStickerSets(query)

    override suspend fun addRecentEmoji(recentEmoji: RecentEmojiModel) {
        cacheProvider.addRecentEmoji(recentEmoji)
    }

    override suspend fun clearRecentEmojis() = cacheProvider.clearRecentEmojis()

    override suspend fun getSavedGifs(): List<GifModel> {
        val cached = cacheProvider.savedGifs.value
        if (cached.isNotEmpty()) return cached

        val remoteGifs = remote.getSavedGifs()
        cacheProvider.setSavedGifs(remoteGifs)
        return remoteGifs
    }

    override suspend fun addSavedGif(path: String) {
        remote.addSavedGif(path)
        val remoteGifs = remote.getSavedGifs()
        cacheProvider.setSavedGifs(remoteGifs)
    }

    override suspend fun searchGifs(query: String) = remote.searchGifs(query)

    override fun getStickerFile(fileId: Long): Flow<String?> = channelFlow {
        filePathsCache[fileId]?.let { send(it); return@channelFlow }

        val job = launch {
            fileUpdateHandler.downloadCompleted
                .filter { it.first == fileId }
                .collect { (_, path) ->
                    filePathsCache[fileId] = path
                    send(path)
                }
        }

        val cachedPath = fileUpdateHandler.downloadCompleted
            .replayCache
            .firstOrNull { it.first == fileId }
            ?.second

        if (cachedPath != null) {
            filePathsCache[fileId] = cachedPath
            send(cachedPath)
            job.cancel()
            return@channelFlow
        }

        fileQueue.enqueue(fileId.toInt(), 32, FileDownloadQueue.DownloadType.STICKER)
        awaitClose { job.cancel() }
    }

    override fun getGifFile(gif: GifModel): Flow<String?> = flow {
        if (gif.fileId == 0L) {
            emit(null); return@flow
        }
        getStickerFile(gif.fileId).collect { emit(it) }
    }

    override suspend fun getTgsJson(path: String): String? = withContext(dispatchers.io) {
        tgsCache[path]?.let { return@withContext it }
        runCatching {
            val file = File(path)
            if (!file.exists() || file.length() == 0L) return@withContext null
            GZIPInputStream(FileInputStream(file))
                .bufferedReader()
                .use { it.readText() }
                .also { tgsCache[path] = it }
        }.getOrNull()
    }

    override fun clearCache() {
        tgsCache.clear()
        filePathsCache.clear()
        cachedEmojis = null
        fallbackEmojisCache = null
        invalidateStickerSetCaches()
    }

    private fun invalidateStickerSetCaches() {
        _installedStickerSets.value = emptyList()
        _customEmojiStickerSets.value = emptyList()
        lastRegularLoadTime = 0
        lastCustomEmojiLoadTime = 0
    }

    private suspend fun getFallbackEmojis(): List<String> = withContext(dispatchers.default) {
        fallbackEmojisCache?.let { return@withContext it }
        EmojiLoader.getSupportedEmojis(context).also { fallbackEmojisCache = it }
    }
}