package org.monogram.presentation.chatsScreen.currentChat

import android.util.Log
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.arkivanov.essenty.lifecycle.doOnResume
import com.arkivanov.essenty.lifecycle.doOnStart
import com.arkivanov.essenty.lifecycle.doOnStop
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import org.monogram.core.DispatcherProvider
import org.monogram.domain.managers.DistrManager
import org.monogram.domain.models.*
import org.monogram.domain.repository.*
import org.monogram.presentation.chatsScreen.currentChat.components.VideoPlayerPool
import org.monogram.presentation.chatsScreen.currentChat.impl.*
import org.monogram.presentation.root.AppComponentContext
import org.monogram.presentation.settingsScreens.storage.CacheController
import org.monogram.presentation.util.AppPreferences
import org.monogram.presentation.util.IDownloadUtils
import org.monogram.presentation.util.componentScope
import java.io.File

class DefaultChatComponent(
    context: AppComponentContext,
    val chatId: Long,
    private val toProfiles: (Long) -> Unit,
    private val onBack: () -> Unit,
    private val onProfileClick: () -> Unit,
    private val onForward: (Long, List<Long>) -> Unit,
    private val onLink: (String) -> Unit,
    private val initialMessageId: Long? = null
) : ChatComponent, AppComponentContext by context {

    internal val settingsRepository: SettingsRepository = container.repositories.settingsRepository
    override val downloadUtils: IDownloadUtils = container.utils.downloadUtils()
    internal val userRepository: UserRepository = container.repositories.userRepository
    override val stickerRepository: StickerRepository = container.repositories.stickerRepository
    internal val privacyRepository: PrivacyRepository = container.repositories.privacyRepository
    internal val botPreferences: BotPreferencesProvider = container.preferences.botPreferencesProvider
    internal val toastMessageDisplayer: MessageDisplayer = container.utils.messageDisplayer()
    internal val chatsListRepository: ChatsListRepository = container.repositories.chatsListRepository
    override val repositoryMessage: MessageRepository = container.repositories.messageRepository
    override val appPreferences: AppPreferences = container.preferences.appPreferences
    internal val cacheProvider: CacheProvider = container.cacheProvider
    override val videoPlayerPool: VideoPlayerPool = container.utils.videoPlayerPool
    internal val cacheController: CacheController = container.utils.cacheController
    internal val distrManager: DistrManager = container.utils.distrManager()
    internal val dispatcherProvider: DispatcherProvider = container.utils.dispatcherProvider

    val scope = componentScope
    val messageMutex = Mutex()
    var messageLoadingJob: Job? = null
    var loadMoreJob: Job? = null
    var loadNewerJob: Job? = null
    var inlineBotJob: Job? = null
    private var autoLoadJob: Job? = null
    private var mentionJob: Job? = null

    internal val _state = MutableStateFlow(
        ChatComponent.State(
            chatId = chatId,
            fontSize = appPreferences.fontSize.value,
            bubbleRadius = appPreferences.bubbleRadius.value,
            wallpaper = appPreferences.wallpaper.value,
            isWallpaperBlurred = appPreferences.isWallpaperBlurred.value,
            wallpaperBlurIntensity = appPreferences.wallpaperBlurIntensity.value,
            isWallpaperMoving = appPreferences.isWallpaperMoving.value,
            wallpaperDimming = appPreferences.wallpaperDimming.value,
            isWallpaperGrayscale = appPreferences.isWallpaperGrayscale.value,
            isPlayerGesturesEnabled = appPreferences.isPlayerGesturesEnabled.value,
            isPlayerDoubleTapSeekEnabled = appPreferences.isPlayerDoubleTapSeekEnabled.value,
            playerSeekDuration = appPreferences.playerSeekDuration.value,
            isPlayerZoomEnabled = appPreferences.isPlayerZoomEnabled.value,
            autoDownloadMobile = appPreferences.autoDownloadMobile.value,
            autoDownloadWifi = appPreferences.autoDownloadWifi.value,
            autoDownloadRoaming = appPreferences.autoDownloadRoaming.value,
            autoDownloadFiles = appPreferences.autoDownloadFiles.value,
            autoplayGifs = appPreferences.autoplayGifs.value,
            autoplayVideos = appPreferences.autoplayVideos.value,
            isWhitelistedInAdBlock = appPreferences.adBlockWhitelistedChannels.value.contains(chatId),
            scrollToMessageId = initialMessageId,
            highlightedMessageId = initialMessageId,
            lastScrollPosition = cacheProvider.getChatScrollPosition(chatId),
            isInstalledFromGooglePlay = distrManager.isInstalledFromGooglePlay()
        )
    )
    override val state: StateFlow<ChatComponent.State> = _state.asStateFlow()

    private var availableWallpapers: List<WallpaperModel> = emptyList()
    private var allMembers: List<UserModel> = emptyList()

    init {
        setupLifecycle()
        setupCollectors()
        initialLoad()
    }

    private fun setupLifecycle() {
        lifecycle.doOnStart {
            startAutoLoad()
        }

        lifecycle.doOnStop {
            autoLoadJob?.cancel()
        }

        lifecycle.doOnResume {
            loadChatInfo()
            handleResume(initialMessageId)
        }

        scope.launch {
            try {
                awaitCancellation()
            } finally {
                repositoryMessage.closeChat(chatId)
            }
        }
    }

    private fun setupCollectors() {
        setupMessageCollectors()
        setupPinnedMessageCollector()
        observeUserUpdates()
        observeCurrentUser()
        observeFileDownloads()

        appPreferences.adBlockWhitelistedChannels
            .onEach { channels ->
                _state.update { it.copy(isWhitelistedInAdBlock = channels.contains(chatId)) }
            }
            .launchIn(scope)

        loadWallpapers { wallpapers ->
            availableWallpapers = wallpapers
            observePreferences(availableWallpapers)
        }
    }

    private fun initialLoad() {
        scope.launch {
            repositoryMessage.openChat(chatId)
            withContext(Dispatchers.Main) {
                loadChatInfo()
                loadDraft()
                loadPinnedMessage()
                loadMembers()
            }
        }
    }

    private fun startAutoLoad() {
        autoLoadJob?.cancel()
        autoLoadJob = scope.launch {
            while (isActive) {
                val currentState = _state.value
                if (initialMessageId == null && currentState.messages.size <= 1 && !currentState.isLoading && !currentState.isLoadingOlder) {
                    Log.d("DefaultChatComponent", "Auto-loading messages...")
                    loadMessages()
                }
                delay(5000)
            }
        }
    }

    private fun handleResume(initialMessageId: Long?) {
        val currentState = _state.value
        if (!currentState.viewAsTopics) {
            if (initialMessageId != null) {
                scrollToMessage(initialMessageId)
            } else if (currentState.messages.isEmpty()) {
                loadMessages()
            }
        } else if (currentState.messages.size <= 1 && currentState.currentTopicId == null) {
            loadMessages()
        }
    }

    private fun loadMembers() {
        scope.launch {
            val currentState = _state.value
            if (currentState.isGroup || currentState.isChannel) {
                try {
                    allMembers = userRepository.getChatMembers(chatId, 0, 200, ChatMembersFilter.Recent)
                        .map { it.user }
                } catch (e: Exception) {
                    Log.e("DefaultChatComponent", "Failed to load members", e)
                }
            }
        }
    }

    private fun observeCurrentUser() {
        userRepository.currentUserFlow
            .onEach { user ->
                _state.update { it.copy(currentUser = user) }
            }
            .launchIn(scope)
    }

    private fun observeFileDownloads() {
        repositoryMessage.messageDownloadCompletedFlow
            .onEach { (fileId, path) ->
                if (path.isNotEmpty()) {
                    updateMessagesWithFile(fileId.toInt(), path)
                    updateInlineResultsWithFile(fileId.toInt(), path)
                }
            }
            .launchIn(scope)
    }

    private fun updateInlineResultsWithFile(fileId: Int, newPath: String) {
        _state.update { currentState ->
            val currentResults = currentState.inlineBotResults ?: return@update currentState
            val updatedResults = currentResults.results.map { result ->
                if (result.thumbFileId == fileId) result.copy(thumbUrl = newPath) else result
            }
            currentState.copy(inlineBotResults = currentResults.copy(results = updatedResults))
        }
    }

    private fun updateMessagesWithFile(fileId: Int, newPath: String) {
        _state.update { currentState ->
            val updatedMessages = currentState.messages.map { msg ->
                updateMessagePathIfNeeded(msg, fileId, newPath)
            }
            currentState.copy(messages = updatedMessages)
        }
    }

    private fun updateMessagePathIfNeeded(msg: MessageModel, targetFileId: Int, newPath: String): MessageModel {
        return when (val content = msg.content) {
            is MessageContent.Photo -> if (content.fileId == targetFileId) msg.copy(content = content.copy(path = newPath, isDownloading = false, downloadProgress = 1f)) else msg
            is MessageContent.Video -> if (content.fileId == targetFileId) msg.copy(content = content.copy(path = newPath, isDownloading = false, downloadProgress = 1f)) else msg
            is MessageContent.Document -> if (content.fileId == targetFileId) msg.copy(content = content.copy(path = newPath, isDownloading = false, downloadProgress = 1f)) else msg
            is MessageContent.Audio -> if (content.fileId == targetFileId) msg.copy(content = content.copy(path = newPath, isDownloading = false, downloadProgress = 1f)) else msg
            is MessageContent.Sticker -> if (content.fileId == targetFileId) msg.copy(content = content.copy(path = newPath, isDownloading = false, downloadProgress = 1f)) else msg
            is MessageContent.Voice -> if (content.fileId == targetFileId) msg.copy(content = content.copy(path = newPath, isDownloading = false, downloadProgress = 1f)) else msg
            is MessageContent.VideoNote -> if (content.fileId == targetFileId) msg.copy(content = content.copy(path = newPath, isDownloading = false, downloadProgress = 1f)) else msg
            is MessageContent.Gif -> if (content.fileId == targetFileId) msg.copy(content = content.copy(path = newPath, isDownloading = false, downloadProgress = 1f)) else msg
            else -> msg
        }
    }

    override fun onSendMessage(text: String, entities: List<MessageEntity>) = handleSendMessage(text, entities)
    override fun onSendSticker(stickerPath: String) = handleSendSticker(stickerPath)
    override fun onSendPhoto(photoPath: String, caption: String) = handleSendPhoto(photoPath, caption)
    override fun onSendVideo(videoPath: String, caption: String) = handleSendVideo(videoPath, caption)
    override fun onSendGif(gif: GifModel) = handleSendGif(gif)
    override fun onSendGifFile(path: String, caption: String) = handleSendGifFile(path, caption)
    override fun onSendAlbum(paths: List<String>, caption: String) = handleSendAlbum(paths, caption)
    override fun onSendVoice(path: String, duration: Int, waveform: ByteArray) = handleSendVoice(path, duration, waveform)
    override fun onVideoRecorded(file: File) = handleVideoRecorded(file)

    override fun loadMore() = loadMoreMessages()
    override fun loadNewer() = loadNewerMessages()

    override fun onBackClicked() {
        if (_state.value.currentTopicId != null) {
            cancelAllLoadingJobs()
            _state.update { it.copy(
                currentTopicId = null,
                rootMessage = null,
                messages = emptyList(),
                isLoading = false
            ) }
            loadMessages(force = true)
            loadPinnedMessage()
            loadDraft()
        } else {
            onBack()
        }
    }

    override fun onProfileClicked() = onProfileClick()
    override fun onMessageClicked(id: Long) = toProfile(id)
    override fun onMessageVisible(messageId: Long) {
        handleMessageVisible(messageId)
        scope.launch {
            _state.value.messages.find { it.id == messageId }?.senderId?.let { senderId ->
                if (senderId > 0) userRepository.getUserFullInfo(senderId)
            }
        }
    }

    override fun onReplyMessage(message: MessageModel) {
        _state.update { it.copy(replyMessage = message, editingMessage = null) }
    }

    override fun onCancelReply() {
        _state.update { it.copy(replyMessage = null) }
    }

    override fun onCancelEdit() {
        _state.update { it.copy(editingMessage = null) }
    }

    override fun onForwardMessage(message: MessageModel) {
        onForward(chatId, listOf(message.id))
    }

    override fun onForwardSelectedMessages() {
        val ids = _state.value.selectedMessageIds.toList().sorted()
        if (ids.isNotEmpty()) {
            onForward(chatId, ids)
            onClearSelection()
        }
    }

    override fun onDeleteMessage(message: MessageModel, revoke: Boolean) = handleDeleteMessage(message, revoke)
    override fun onDeleteSelectedMessages(revoke: Boolean) = handleDeleteSelectedMessages(revoke)

    override fun onEditMessage(message: MessageModel) {
        _state.update { it.copy(
            editingMessage = message,
            replyMessage = null,
            editRequestTime = System.currentTimeMillis()
        ) }
    }

    override fun onSaveEditedMessage(text: String, entities: List<MessageEntity>) = handleSaveEditedMessage(text, entities)
    override fun onDraftChange(text: String) = handleDraftChange(text)

    override fun onPinMessage(message: MessageModel) {
        scope.launch { repositoryMessage.pinMessage(chatId, message.id) }
    }

    override fun onUnpinMessage(message: MessageModel) {
        scope.launch { repositoryMessage.unpinMessage(chatId, message.id) }
    }

    override fun onPinnedMessageClick(message: MessageModel?) = handlePinnedMessageClick(message)

    override fun onShowAllPinnedMessages() {
        scope.launch {
            val threadId = _state.value.currentTopicId
            val pinnedMessages = repositoryMessage.getAllPinnedMessages(chatId, threadId)
            _state.update { it.copy(
                allPinnedMessages = pinnedMessages,
                showPinnedMessagesList = true
            ) }
        }
    }

    override fun onDismissPinnedMessages() {
        _state.update { it.copy(showPinnedMessagesList = false) }
    }

    override fun onScrollToMessageConsumed() {
        _state.update { it.copy(scrollToMessageId = null) }
    }

    override fun onScrollToBottom() = scrollToBottomInternal()

    override fun onDownloadFile(fileId: Int) {
        repositoryMessage.downloadFile(fileId)
    }

    override fun onDownloadHighRes(messageId: Long) {
        scope.launch {
            val currentState = _state.value
            val message = currentState.messages.find { it.id == messageId } ?: return@launch
            val highResId = repositoryMessage.getHighResFileId(chatId, messageId) ?: return@launch
            if (highResId == 0) return@launch

            val existingInfo = repositoryMessage.getFileInfo(highResId)
            if (existingInfo?.local?.isDownloadingCompleted == true && !existingInfo.local.path.isNullOrEmpty()) {
                updateViewerPath(message, existingInfo.local.path)
                return@launch
            }

            repositoryMessage.downloadFile(highResId, priority = 32)

            val job = launch {
                repositoryMessage.messageDownloadCompletedFlow
                    .filter { (id, _) -> id == highResId.toLong() }
                    .collect { (_, path) ->
                        if (path.isNotEmpty()) {
                            updateViewerPath(message, path)
                            this.cancel()
                        }
                    }
            }
            delay(60_000)
            job.cancel()
        }
    }

    private suspend fun updateViewerPath(message: MessageModel, newPath: String) {
        withContext(dispatcherProvider.main) {
            _state.update { currentState ->
                val currentImages = currentState.fullScreenImages ?: return@update currentState
                val oldPath = (message.content as? MessageContent.Photo)?.path ?: return@update currentState
                val index = currentImages.indexOf(oldPath)
                if (index != -1) {
                    val newImages = currentImages.toMutableList()
                    newImages[index] = newPath
                    currentState.copy(fullScreenImages = newImages)
                } else currentState
            }
        }
    }

    override fun onCancelDownloadFile(fileId: Int) {
        scope.launch { repositoryMessage.cancelDownloadFile(fileId) }
    }

    override fun updateScrollPosition(messageId: Long) {
        val currentState = _state.value
        if (currentState.currentTopicId != null) return
        val toSave = if (currentState.isAtBottom) 0L else messageId
        cacheProvider.saveChatScrollPosition(chatId, toSave)
        if (toSave != 0L) {
            _state.update { it.copy(lastScrollPosition = toSave) }
        }
    }

    override fun onBottomReached(isAtBottom: Boolean) {
        _state.update { it.copy(isAtBottom = isAtBottom) }
    }

    override fun onHighlightConsumed() {
        _state.update { it.copy(highlightedMessageId = null) }
    }

    private var lastTypingTime = 0L
    override fun onTyping() {
        val now = System.currentTimeMillis()
        if (now - lastTypingTime > 4000) {
            lastTypingTime = now
            val threadId = _state.value.currentTopicId
            scope.launch {
                repositoryMessage.sendChatAction(chatId, MessageRepository.ChatAction.Typing, threadId)
            }
        }
    }

    override fun onSendReaction(messageId: Long, reaction: String) = handleSendReaction(messageId, reaction)

    override suspend fun getMessageReadDate(chatId: Long, messageId: Long): Int {
        return repositoryMessage.getMessageReadDate(chatId, messageId)
    }

    override fun toProfile(id: Long) = toProfiles(id)
    override fun onToggleMessageSelection(messageId: Long) = handleToggleMessageSelection(messageId)
    override fun onClearSelection() = handleClearSelection()
    override fun onClearMessages() {
        _state.update { it.copy(messages = emptyList()) }
    }

    override fun onCopySelectedMessages(clipboardManager: ClipboardManager) = handleCopySelectedMessages(clipboardManager)
    override fun onStickerClick(setId: Long) = handleStickerClick(setId)
    override fun onDismissStickerSet() {
        _state.update { it.copy(selectedStickerSet = null) }
    }

    override fun onAddToGifs(path: String) = handleAddToGifs(path)

    override fun onPollOptionClick(messageId: Long, optionId: Int) = handlePollOptionClick(messageId, optionId)
    override fun onRetractVote(messageId: Long) = handleRetractVote(messageId)
    override fun onShowVoters(messageId: Long, optionId: Int) = handleShowVoters(messageId, optionId)
    override fun onDismissVoters() {
        _state.update { it.copy(showPollVoters = false, pollVoters = emptyList(), isPollVotersLoading = false) }
    }
    override fun onTopicClick(topicId: Int) {
        cancelAllLoadingJobs()
        _state.update { it.copy(currentTopicId = topicId.toLong(), messages = emptyList()) }
        loadMessages()
        loadPinnedMessage()
        loadDraft()
    }

    override fun onOpenInstantView(url: String) {
        _state.update { it.copy(instantViewUrl = url) }
    }

    override fun onDismissInstantView() {
        _state.update { it.copy(instantViewUrl = null) }
    }

    override fun onOpenYouTube(url: String) {
        _state.update { it.copy(youtubeUrl = url) }
    }

    override fun onDismissYouTube() {
        _state.update { it.copy(youtubeUrl = null) }
    }

    override fun onOpenMiniApp(url: String, name: String, botUserId: Long) = handleOpenMiniApp(url, name, botUserId)
    override fun onDismissMiniApp() {
        _state.update { it.copy(miniAppUrl = null, miniAppName = null, miniAppBotUserId = 0L) }
    }
    override fun onAcceptMiniAppTOS() = handleAcceptMiniAppTOS()
    override fun onDismissMiniAppTOS() = handleDismissMiniAppTOS()

    override fun onOpenWebView(url: String) {
        _state.update { it.copy(webViewUrl = url) }
    }

    override fun onDismissWebView() {
        _state.update { it.copy(webViewUrl = null) }
    }

    override fun onOpenImages(images: List<String>, captions: List<String?>, startIndex: Int, messageId: Long?) {
        _state.update { it.copy(
            fullScreenImages = images,
            fullScreenCaptions = captions,
            fullScreenStartIndex = startIndex
        ) }
        if (messageId != null) onDownloadHighRes(messageId)
    }

    override fun onDismissImages() {
        _state.update { it.copy(fullScreenImages = null) }
    }

    override fun onOpenVideo(path: String?, messageId: Long?, caption: String?) {
        _state.update { it.copy(
            fullScreenVideoPath = path,
            fullScreenVideoMessageId = messageId,
            fullScreenVideoCaption = caption
        ) }
    }

    override fun onDismissVideo() {
        _state.update { it.copy(fullScreenVideoPath = null, fullScreenVideoMessageId = null) }
    }

    override fun onAddToAdBlockWhitelist() {
        val current = appPreferences.adBlockWhitelistedChannels.value.toMutableSet()
        if (current.add(chatId)) appPreferences.setAdBlockWhitelistedChannels(current)
    }

    override fun onRemoveFromAdBlockWhitelist() {
        val current = appPreferences.adBlockWhitelistedChannels.value.toMutableSet()
        if (current.remove(chatId)) appPreferences.setAdBlockWhitelistedChannels(current)
    }

    override fun onToggleMute() {
        val shouldMute = !_state.value.isMuted
        chatsListRepository.toggleMuteChats(setOf(chatId), shouldMute)
        _state.update { it.copy(isMuted = shouldMute) }
    }

    override fun onSearchToggle() {
        _state.update { it.copy(isSearchActive = !it.isSearchActive, searchQuery = "") }
    }

    override fun onSearchQueryChange(query: String) {
        _state.update { it.copy(searchQuery = query) }
    }

    override fun onClearHistory() = chatsListRepository.clearChatHistory(chatId, true)
    override fun onDeleteChat() {
        chatsListRepository.deleteChats(setOf(chatId))
        onBack()
    }

    override fun onReport() {
        _state.update { it.copy(showReportDialog = true) }
    }

    override fun onReportMessage(message: MessageModel) {
        _state.update { it.copy(selectedMessageIds = setOf(message.id), showReportDialog = true) }
    }

    override fun onReportReasonSelected(reason: String) {
        val selectedIds = _state.value.selectedMessageIds.toList()
        chatsListRepository.reportChat(chatId, reason, selectedIds)
        _state.update { it.copy(showReportDialog = false) }
        if (selectedIds.isNotEmpty()) onClearSelection()
        toastMessageDisplayer.show("Report sent")
    }

    override fun onDismissReportDialog() {
        _state.update { it.copy(showReportDialog = false) }
    }

    override fun onCopyLink(clipboardManager: ClipboardManager) {
        scope.launch {
            val link = chatsListRepository.getChatLink(chatId)
            if (link != null) clipboardManager.setText(AnnotatedString(link))
        }
    }

    override fun scrollToMessage(messageId: Long) = scrollToMessageInternal(messageId)
    override fun onBotCommandClick(command: String) = onSendMessage("/$command")
    override fun onShowBotCommands() {
        _state.update { it.copy(showBotCommands = true) }
    }
    override fun onDismissBotCommands() {
        _state.update { it.copy(showBotCommands = false) }
    }

    override fun onCommentsClick(messageId: Long) {
        cancelAllLoadingJobs()
        scope.launch {
            var rootMessage = _state.value.messages.find { it.id == messageId }
            if (rootMessage == null) {
                val around = repositoryMessage.getMessagesAround(chatId, messageId, 1)
                rootMessage = around.find { it.id == messageId }
            }
            _state.update { it.copy(currentTopicId = messageId, rootMessage = rootMessage, messages = emptyList()) }
            loadMessages(force = true)
            loadPinnedMessage()
            loadDraft()
        }
    }

    override fun onReplyMarkupButtonClick(messageId: Long, button: InlineKeyboardButtonModel, botUserId: Long) {
        when (val type = button.type) {
            is InlineKeyboardButtonType.Callback -> scope.launch { repositoryMessage.onCallbackQuery(chatId, messageId, type.data) }
            is InlineKeyboardButtonType.SwitchInline -> onSendMessage("@${_state.value.messages.find { it.id == messageId }?.senderName} ${type.query}")
            is InlineKeyboardButtonType.User -> toProfile(type.userId)
            is InlineKeyboardButtonType.WebApp -> onOpenMiniApp(type.url, button.text, botUserId)
            is InlineKeyboardButtonType.LoginUrl -> onLink(type.url)
            is InlineKeyboardButtonType.Buy -> {
                if (type.slug != null) onOpenInvoice(slug = type.slug) else onOpenInvoice(messageId = messageId)
            }
            is InlineKeyboardButtonType.Url -> onLink(type.url)
            else -> Log.d("DefaultChatComponent", "Unsupported button type: $type")
        }
    }

    override fun onLinkClick(url: String) {
        if (url.startsWith("http") && !url.contains("t.me")) onOpenWebView(url) else onLink(url)
    }

    override fun onOpenInvoice(slug: String?, messageId: Long?) = handleOpenInvoice(slug, messageId)
    override fun onDismissInvoice(status: String) = handleDismissInvoice(status)

    override fun onMentionQueryChange(query: String?) {
        mentionJob?.cancel()
        mentionJob = handleMentionQueryChange(query, allMembers) { allMembers = it }
    }

    override fun onJoinChat() {
        scope.launch {
            repositoryMessage.joinChat(chatId)
            _state.update { it.copy(isMember = true) }
        }
    }

    override fun onBlockUser(userId: Long) {
        scope.launch {
            privacyRepository.blockUser(userId)
            toastMessageDisplayer.show("user blocked")
        }
    }

    override fun onUnblockUser(userId: Long) {
        scope.launch {
            privacyRepository.unblockUser(userId)
            toastMessageDisplayer.show("user unblocked")
        }
    }

    override fun onRestrictUser(userId: Long, permissions: ChatPermissionsModel) {
        _state.update { it.copy(restrictUserId = userId) }
    }

    override fun onDismissRestrictDialog() {
        _state.update { it.copy(restrictUserId = null) }
    }

    override fun onConfirmRestrict(permissions: ChatPermissionsModel, untilDate: Int) {
        val userId = _state.value.restrictUserId ?: return
        scope.launch {
            repositoryMessage.restrictChatMember(chatId, userId, permissions, untilDate)
            _state.update { it.copy(restrictUserId = null) }
            toastMessageDisplayer.show("User restricted")
        }
    }

    override fun onInlineQueryChange(botUsername: String, query: String) = handleInlineQueryChange(botUsername, query)
    override fun onLoadMoreInlineResults(offset: String) = handleLoadMoreInlineResults(offset)
    override fun onSendInlineResult(resultId: String) = handleSendInlineResult(resultId)

    override fun onClosePoll(messageId: Long) = handleClosePoll(messageId)
}
