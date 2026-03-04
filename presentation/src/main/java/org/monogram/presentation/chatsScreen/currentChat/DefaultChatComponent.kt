package org.monogram.presentation.chatsScreen.currentChat

import android.util.Log
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.arkivanov.essenty.lifecycle.doOnResume
import com.arkivanov.essenty.lifecycle.doOnStart
import com.arkivanov.essenty.lifecycle.doOnStop
import org.monogram.core.DispatcherProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import org.monogram.domain.managers.DistrManager
import org.monogram.domain.models.*
import org.monogram.domain.repository.*
import org.monogram.presentation.chatsScreen.currentChat.components.VideoPlayerPool
import org.monogram.presentation.chatsScreen.currentChat.impl.*
import org.monogram.presentation.root.AppComponentContext
import org.monogram.presentation.settingsScreens.storage.CacheController
import org.monogram.presentation.util.AppPreferences
import org.monogram.presentation.util.IDownloadUtils
import java.io.File

class DefaultChatComponent(
    context: AppComponentContext,
    val chatId: Long,
    val settingsRepository: SettingsRepository = context.container.repositories.settingsRepository,
    override val downloadUtils: IDownloadUtils = context.container.utils.downloadUtils(),
    val userRepository: UserRepository = context.container.repositories.userRepository,
    override val stickerRepository: StickerRepository = context.container.repositories.stickerRepository,
    val privacyRepository: PrivacyRepository = context.container.repositories.privacyRepository,
    val botPreferences: BotPreferencesProvider = context.container.preferences.botPreferencesProvider,
    val toastMessageDisplayer: MessageDisplayer = context.container.utils.messageDisplayer(),
    val chatsListRepository: ChatsListRepository = context.container.repositories.chatsListRepository,
    override val repositoryMessage: MessageRepository = context.container.repositories.messageRepository,
    override val appPreferences: AppPreferences = context.container.preferences.appPreferences,
    val cacheProvider: CacheProvider = context.container.cacheProvider,
    override val videoPlayerPool: VideoPlayerPool = context.container.utils.videoPlayerPool,
    val cacheController: CacheController = context.container.utils.cacheController,
    val distrManager: DistrManager = context.container.utils.distrManager(),
    val dispatcherProvider: DispatcherProvider = context.container.utils.dispatcherProvider,
    val toProfiles: (Long) -> Unit,
    private val onBack: () -> Unit,
    private val onProfileClick: () -> Unit,
    private val onForward: (Long, List<Long>) -> Unit,
    private val onLink: (String) -> Unit,
    initialMessageId: Long? = null
) : ChatComponent, AppComponentContext by context {
    val scope = CoroutineScope(dispatcherProvider.mainImmediate + SupervisorJob())
    val messageMutex = Mutex()
    var messageLoadingJob: Job? = null
    var loadMoreJob: Job? = null
    var loadNewerJob: Job? = null
    var inlineBotJob: Job? = null
    private var autoLoadJob: Job? = null

    val _state = MutableStateFlow(
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
    override val state: StateFlow<ChatComponent.State> = _state

    private var availableWallpapers: List<WallpaperModel> = emptyList()
    private var allMembers: List<UserModel> = emptyList()

    init {
        scope.launch {
            repositoryMessage.openChat(chatId)
        }
        observeMediaProgress()
        lifecycle.doOnStart {
            autoLoadJob?.cancel()
            autoLoadJob = scope.launch {
                while (isActive) {
                    val state = _state.value
                    if (initialMessageId == null && state.messages.size <= 1 && !state.isLoading && !state.isLoadingOlder) {
                        Log.d("DefaultChatComponent", "Auto-loading messages...")
                        loadMessages()
                    }
                    delay(2000)
                }
            }
        }

        lifecycle.doOnStop {
            autoLoadJob?.cancel()
        }

        lifecycle.doOnResume {
            loadChatInfo()
            if (!_state.value.viewAsTopics) {
                if (initialMessageId != null) {
                    scrollToMessage(initialMessageId)
                } else if (_state.value.messages.isEmpty()) {
                    loadMessages()
                }
            } else if (_state.value.messages.size <= 1 && _state.value.currentTopicId == null) {
                Log.d("DefaultChatComponent", "Resuming chat when no messages")
                loadMessages()
            }
        }

        lifecycle.doOnDestroy {
            scope.launch {
                repositoryMessage.closeChat(chatId)
            }
            scope.cancel()
        }

        loadChatInfo()
        setupMessageCollectors()
        setupPinnedMessageCollector()
        loadDraft()
        loadPinnedMessage()
        loadWallpapers { wallpapers ->
            availableWallpapers = wallpapers
            observePreferences(availableWallpapers)
        }
        observeUserUpdates()
        observeCurrentUser()
        loadMembers()

        observeFileDownloads()

        appPreferences.adBlockWhitelistedChannels
            .onEach { channels ->
                _state.value = _state.value.copy(isWhitelistedInAdBlock = channels.contains(chatId))
            }
            .launchIn(scope)
    }

    private fun loadMembers() {
        scope.launch {
            if (_state.value.isGroup || _state.value.isChannel) {
                try {
                    allMembers =
                        userRepository.getChatMembers(chatId, 0, 200, ChatMembersFilter.Recent)
                            .map { it.user }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun observeCurrentUser() {
        scope.launch {
            userRepository.currentUserFlow.collectLatest { user ->
                _state.value = _state.value.copy(currentUser = user)
            }
        }
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
        val currentResults = _state.value.inlineBotResults ?: return
        var hasChanges = false

        val updatedResults = currentResults.results.map { result ->
            if (result.thumbFileId == fileId) {
                hasChanges = true
                result.copy(thumbUrl = newPath)
            } else {
                result
            }
        }

        if (hasChanges) {
            _state.value = _state.value.copy(
                inlineBotResults = currentResults.copy(results = updatedResults)
            )
        }
    }

    private fun updateMessagesWithFile(fileId: Int, newPath: String) {
        val currentMessages = _state.value.messages
        var hasChanges = false

        val updatedMessages = currentMessages.map { msg ->
            val updatedMsg = updateMessagePathIfNeeded(msg, fileId, newPath)
            if (updatedMsg !== msg) {
                hasChanges = true
            }
            updatedMsg
        }

        if (hasChanges) {
            _state.value = _state.value.copy(messages = updatedMessages)
        }
    }

    private fun updateMessagePathIfNeeded(
        msg: MessageModel,
        targetFileId: Int,
        newPath: String
    ): MessageModel {
        return when (val content = msg.content) {
            is MessageContent.Photo -> {
                if (content.fileId == targetFileId) {
                    msg.copy(
                        content = content.copy(
                            path = newPath,
                            isDownloading = false,
                            downloadProgress = 1f
                        )
                    )
                } else msg
            }

            is MessageContent.Video -> {
                if (content.fileId == targetFileId) {
                    msg.copy(
                        content = content.copy(
                            path = newPath,
                            isDownloading = false,
                            downloadProgress = 1f
                        )
                    )
                } else msg
            }

            is MessageContent.Document -> {
                if (content.fileId == targetFileId) {
                    msg.copy(
                        content = content.copy(
                            path = newPath,
                            isDownloading = false,
                            downloadProgress = 1f
                        )
                    )
                } else msg
            }

            is MessageContent.Audio -> {
                if (content.fileId == targetFileId) {
                    msg.copy(
                        content = content.copy(
                            path = newPath,
                            isDownloading = false,
                            downloadProgress = 1f
                        )
                    )
                } else msg
            }

            is MessageContent.Sticker -> {
                if (content.fileId == targetFileId) {
                    msg.copy(
                        content = content.copy(
                            path = newPath,
                            isDownloading = false,
                            downloadProgress = 1f
                        )
                    )
                } else msg
            }

            is MessageContent.Voice -> {
                if (content.fileId == targetFileId) {
                    msg.copy(
                        content = content.copy(
                            path = newPath,
                            isDownloading = false,
                            downloadProgress = 1f
                        )
                    )
                } else msg
            }

            is MessageContent.VideoNote -> {
                if (content.fileId == targetFileId) {
                    msg.copy(
                        content = content.copy(
                            path = newPath,
                            isDownloading = false,
                            downloadProgress = 1f
                        )
                    )
                } else msg
            }

            is MessageContent.Gif -> {
                if (content.fileId == targetFileId) {
                    msg.copy(
                        content = content.copy(
                            path = newPath,
                            isDownloading = false,
                            downloadProgress = 1f
                        )
                    )
                } else msg
            }

            else -> msg
        }
    }

    override fun onSendMessage(text: String, entities: List<MessageEntity>) =
        handleSendMessage(text, entities)

    override fun onSendSticker(stickerPath: String) = handleSendSticker(stickerPath)

    override fun onSendPhoto(photoPath: String, caption: String) =
        handleSendPhoto(photoPath, caption)

    override fun onSendVideo(videoPath: String, caption: String) =
        handleSendVideo(videoPath, caption)

    override fun onSendGif(gif: GifModel) = handleSendGif(gif)

    override fun onSendGifFile(path: String, caption: String) = handleSendGifFile(path, caption)

    override fun onSendAlbum(paths: List<String>, caption: String) = handleSendAlbum(paths, caption)

    override fun onSendVoice(path: String, duration: Int, waveform: ByteArray) =
        handleSendVoice(path, duration, waveform)

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

    override fun onProfileClicked() {
        onProfileClick()
    }

    override fun onMessageClicked(id: Long) {
        toProfile(id)
    }

    override fun onMessageVisible(messageId: Long) = handleMessageVisible(messageId)

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

    override fun onDeleteMessage(message: MessageModel, revoke: Boolean) =
        handleDeleteMessage(message, revoke)

    override fun onDeleteSelectedMessages(revoke: Boolean) = handleDeleteSelectedMessages(revoke)

    override fun onEditMessage(message: MessageModel) {
        _state.update { it.copy(
            editingMessage = message,
            replyMessage = null,
            editRequestTime = System.currentTimeMillis()
        ) }
    }

    override fun onSaveEditedMessage(text: String, entities: List<MessageEntity>) =
        handleSaveEditedMessage(text, entities)

    override fun onDraftChange(text: String) = handleDraftChange(text)

    override fun onPinMessage(message: MessageModel) {
        scope.launch {
            repositoryMessage.pinMessage(chatId, message.id)
        }
    }

    override fun onUnpinMessage(message: MessageModel) {
        scope.launch {
            repositoryMessage.unpinMessage(chatId, message.id)
        }
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
            val message = _state.value.messages.find { it.id == messageId } ?: return@launch


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
            val currentImages = _state.value.fullScreenImages ?: return@withContext

            val oldPath = (message.content as? MessageContent.Photo)?.path
            if (oldPath != null) {
                val index = currentImages.indexOf(oldPath)
                if (index != -1) {
                    val newImages = currentImages.toMutableList()
                    newImages[index] = newPath
                    _state.update { it.copy(fullScreenImages = newImages) }
                }
            }
        }
    }

    override fun onCancelDownloadFile(fileId: Int) {
        scope.launch {
            repositoryMessage.cancelDownloadFile(fileId)
        }
    }

    override fun updateScrollPosition(messageId: Long) {
        if (_state.value.currentTopicId != null) return
        val toSave = if (_state.value.isAtBottom) 0L else messageId
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

    override fun onSendReaction(messageId: Long, reaction: String) =
        handleSendReaction(messageId, reaction)

    override suspend fun getMessageReadDate(chatId: Long, messageId: Long): Int {
        return repositoryMessage.getMessageReadDate(chatId, messageId)
    }

    override fun toProfile(id: Long) {
        Log.d("DefaultChatComponent", id.toString())
        toProfiles(id)
    }

    override fun onToggleMessageSelection(messageId: Long) = handleToggleMessageSelection(messageId)

    override fun onClearSelection() = handleClearSelection()

    override fun onClearMessages() {
        _state.update { it.copy(messages = emptyList()) }
    }

    override fun onCopySelectedMessages(clipboardManager: ClipboardManager) =
        handleCopySelectedMessages(clipboardManager)

    override fun onStickerClick(setId: Long) = handleStickerClick(setId)

    override fun onDismissStickerSet() {
        _state.update { it.copy(selectedStickerSet = null) }
    }

    override fun onAddToGifs(path: String) = handleAddToGifs(path)

    override fun onPollOptionClick(messageId: Long, optionId: Int) {
        scope.launch {
            repositoryMessage.setPollAnswer(chatId, messageId, listOf(optionId))
        }
    }

    override fun onRetractVote(messageId: Long) {
        scope.launch {
            repositoryMessage.setPollAnswer(chatId, messageId, emptyList())
        }
    }

    override fun onShowVoters(messageId: Long, optionId: Int) {
        scope.launch {
            _state.update { it.copy(
                showPollVoters = true,
                pollVoters = emptyList(),
                isPollVotersLoading = true
            ) }
            val voters = repositoryMessage.getPollVoters(chatId, messageId, optionId, 0, 50)
            _state.update { it.copy(
                pollVoters = voters,
                isPollVotersLoading = false
            ) }
        }
    }

    override fun onDismissVoters() {
        _state.update { it.copy(
            showPollVoters = false,
            pollVoters = emptyList(),
            isPollVotersLoading = false
        ) }
    }

    override fun onClosePoll(messageId: Long) {
        scope.launch {
            repositoryMessage.stopPoll(chatId, messageId)
        }
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

    override fun onOpenMiniApp(url: String, name: String, botUserId: Long) {
        if (botUserId != 0L && !botPreferences.getWebappPermission(botUserId, "tos_accepted")) {
            _state.update { it.copy(
                showMiniAppTOS = true,
                miniAppTOSBotUserId = botUserId,
                miniAppTOSUrl = url,
                miniAppTOSName = name
            ) }
        } else {
            _state.update { it.copy(
                miniAppUrl = url,
                miniAppName = name,
                miniAppBotUserId = botUserId
            ) }
        }
    }

    override fun onDismissMiniApp() {
        _state.update { it.copy(miniAppUrl = null, miniAppName = null, miniAppBotUserId = 0L) }
    }

    override fun onAcceptMiniAppTOS() {
        val botUserId = _state.value.miniAppTOSBotUserId
        val url = _state.value.miniAppTOSUrl
        val name = _state.value.miniAppTOSName
        if (botUserId != 0L && url != null && name != null) {
            botPreferences.setWebappPermission(botUserId, "tos_accepted", true)
            _state.value = _state.value.copy(
                showMiniAppTOS = false,
                miniAppTOSBotUserId = 0L,
                miniAppTOSUrl = null,
                miniAppTOSName = null,
                miniAppUrl = url,
                miniAppName = name,
                miniAppBotUserId = botUserId
            )
        }
    }

    override fun onDismissMiniAppTOS() {
        _state.update { it.copy(
            showMiniAppTOS = false,
            miniAppTOSBotUserId = 0L,
            miniAppTOSUrl = null,
            miniAppTOSName = null
        ) }
    }

    override fun onOpenWebView(url: String) {
        _state.update { it.copy(webViewUrl = url) }
    }

    override fun onDismissWebView() {
        _state.update { it.copy(webViewUrl = null) }
    }

    override fun onOpenImages(
        images: List<String>,
        captions: List<String?>,
        startIndex: Int,
        messageId: Long?
    ) {
        _state.update { it.copy(
            fullScreenImages = images,
            fullScreenCaptions = captions,
            fullScreenStartIndex = startIndex
        ) }

        if (messageId != null) {
            onDownloadHighRes(messageId)
        }
    }

    override fun onDismissImages() {
        _state.value = _state.value.copy(fullScreenImages = null)
    }

    override fun onOpenVideo(path: String?, messageId: Long?, caption: String?) {
        _state.value = _state.value.copy(
            fullScreenVideoPath = path,
            fullScreenVideoMessageId = messageId,
            fullScreenVideoCaption = caption
        )
    }

    override fun onDismissVideo() {
        _state.value =
            _state.value.copy(fullScreenVideoPath = null, fullScreenVideoMessageId = null)
    }

    override fun onAddToAdBlockWhitelist() {
        val current = appPreferences.adBlockWhitelistedChannels.value.toMutableSet()
        if (current.add(chatId)) {
            appPreferences.setAdBlockWhitelistedChannels(current)
        }
    }

    override fun onRemoveFromAdBlockWhitelist() {
        val current = appPreferences.adBlockWhitelistedChannels.value.toMutableSet()
        if (current.remove(chatId)) {
            appPreferences.setAdBlockWhitelistedChannels(current)
        }
    }

    override fun onToggleMute() {
        val shouldMute = !_state.value.isMuted
        chatsListRepository.toggleMuteChats(setOf(chatId), shouldMute)
        _state.value = _state.value.copy(isMuted = shouldMute)
    }

    override fun onSearchToggle() {
        _state.value = _state.value.copy(
            isSearchActive = !_state.value.isSearchActive,
            searchQuery = ""
        )
    }

    override fun onSearchQueryChange(query: String) {
        _state.value = _state.value.copy(searchQuery = query)

    }

    override fun onClearHistory() {
        chatsListRepository.clearChatHistory(chatId, true)
    }

    override fun onDeleteChat() {
        chatsListRepository.deleteChats(setOf(chatId))
        onBack()
    }

    override fun onReport() {
        _state.value = _state.value.copy(showReportDialog = true)
    }

    override fun onReportMessage(message: MessageModel) {
        _state.value =
            _state.value.copy(selectedMessageIds = setOf(message.id), showReportDialog = true)
    }

    override fun onReportReasonSelected(reason: String) {
        val selectedIds = _state.value.selectedMessageIds.toList()
        chatsListRepository.reportChat(chatId, reason, selectedIds)
        _state.value = _state.value.copy(showReportDialog = false)
        if (selectedIds.isNotEmpty()) {
            onClearSelection()
        }
        toastMessageDisplayer.show("Report sent")
    }

    override fun onDismissReportDialog() {
        _state.value = _state.value.copy(showReportDialog = false)
    }

    override fun onCopyLink(clipboardManager: ClipboardManager) {
        scope.launch {
            val link = chatsListRepository.getChatLink(chatId)
            if (link != null) {
                clipboardManager.setText(AnnotatedString(link))
            }
        }
    }

    override fun scrollToMessage(messageId: Long) = scrollToMessageInternal(messageId)

    override fun onBotCommandClick(command: String) {
        onSendMessage("/$command")
    }

    override fun onShowBotCommands() {
        _state.value = _state.value.copy(showBotCommands = true)
    }

    override fun onDismissBotCommands() {
        _state.value = _state.value.copy(showBotCommands = false)
    }

    override fun onCommentsClick(messageId: Long) {
        cancelAllLoadingJobs()
        scope.launch {
            var rootMessage = _state.value.messages.find { it.id == messageId }
            if (rootMessage == null) {
                val around = repositoryMessage.getMessagesAround(chatId, messageId, 1)
                rootMessage = around.find { it.id == messageId }
            }

            _state.value = _state.value.copy(
                currentTopicId = messageId,
                rootMessage = rootMessage,
                messages = emptyList()
            )
            loadMessages(force = true)
            loadPinnedMessage()
            loadDraft()
        }
    }

    override fun onReplyMarkupButtonClick(
        messageId: Long,
        button: InlineKeyboardButtonModel,
        botUserId: Long
    ) {
        Log.d(
            "DefaultChatComponent",
            "onReplyMarkupButtonClick: messageId=$messageId, buttonText=${button.text}, type=${button.type}"
        )
        when (val type = button.type) {
            is InlineKeyboardButtonType.Callback -> {
                scope.launch {
                    repositoryMessage.onCallbackQuery(chatId, messageId, type.data)
                }
            }

            is InlineKeyboardButtonType.SwitchInline -> {
                onSendMessage("@${_state.value.messages.find { it.id == messageId }?.senderName} ${type.query}")
            }

            is InlineKeyboardButtonType.User -> {
                toProfile(type.userId)
            }

            is InlineKeyboardButtonType.WebApp -> {
                onOpenMiniApp(type.url, button.text, botUserId)
            }

            is InlineKeyboardButtonType.LoginUrl -> {
                onLink(type.url)
            }

            is InlineKeyboardButtonType.Buy -> {
                val slug = type.slug
                Log.d("DefaultChatComponent", "Buy button clicked: slug=$slug")
                if (slug != null) {
                    onOpenInvoice(slug = slug)
                } else {
                    onOpenInvoice(messageId = messageId)
                }
            }

            is InlineKeyboardButtonType.Url -> {
                onLink(type.url)
            }

            is InlineKeyboardButtonType.Unsupported -> {
                Log.d("DefaultChatComponent", "Unsupported button type: $type")
            }
        }
    }

    override fun onLinkClick(url: String) {
        if (url.startsWith("http") && !url.contains("t.me")) {
            onOpenWebView(url)
        } else {
            onLink(url)
        }
    }

    override fun onOpenInvoice(slug: String?, messageId: Long?) {
        Log.d("DefaultChatComponent", "onOpenInvoice: slug=$slug, messageId=$messageId")
        _state.value = _state.value.copy(invoiceSlug = slug, invoiceMessageId = messageId)
    }

    override fun onDismissInvoice(status: String) {
        Log.d("DefaultChatComponent", "onDismissInvoice: status=$status")
        _state.value = _state.value.copy(invoiceSlug = null, invoiceMessageId = null)
    }

    private var mentionJob: Job? = null
    override fun onMentionQueryChange(query: String?) {
        mentionJob?.cancel()
        if (query == null) {
            _state.value = _state.value.copy(mentionSuggestions = emptyList())
            return
        }

        mentionJob = scope.launch {
            delay(150)
            val suggestions = if (query.isEmpty()) {
                if (allMembers.isEmpty()) {
                    allMembers =
                        userRepository.getChatMembers(chatId, 0, 200, ChatMembersFilter.Recent)
                            .map { it.user }
                }
                allMembers
            } else {
                val lowerQuery = query.lowercase()
                val filtered = allMembers.filter {
                    it.firstName.lowercase().contains(lowerQuery) ||
                            it.lastName?.lowercase()?.contains(lowerQuery) == true ||
                            it.username?.lowercase()?.contains(lowerQuery) == true
                }
                if (filtered.isEmpty() && query.length > 2) {
                    userRepository.searchContacts(query)
                } else {
                    filtered
                }
            }
            _state.value = _state.value.copy(mentionSuggestions = suggestions)
        }
    }

    override fun onJoinChat() {
        scope.launch {
            repositoryMessage.joinChat(chatId)
            _state.value = _state.value.copy(isMember = true)
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
        _state.value = _state.value.copy(restrictUserId = userId)
    }

    override fun onDismissRestrictDialog() {
        _state.value = _state.value.copy(restrictUserId = null)
    }

    override fun onConfirmRestrict(permissions: ChatPermissionsModel, untilDate: Int) {
        val userId = _state.value.restrictUserId ?: return
        scope.launch {
            repositoryMessage.restrictChatMember(chatId, userId, permissions, untilDate)
            _state.value = _state.value.copy(restrictUserId = null)
            toastMessageDisplayer.show("User restricted")
        }
    }

    override fun onInlineQueryChange(botUsername: String, query: String) {
        inlineBotJob?.cancel()
        inlineBotJob = scope.launch {
            delay(400)
            _state.value = _state.value.copy(isInlineBotLoading = true)
            try {
                val bot = userRepository.searchPublicChat(botUsername)
                if (bot != null) {
                    val results = repositoryMessage.getInlineBotResults(bot.id, chatId, query)
                    _state.value = _state.value.copy(
                        inlineBotResults = results,
                        currentInlineBotId = bot.id,
                        currentInlineQuery = query
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _state.value = _state.value.copy(isInlineBotLoading = false)
            }
        }
    }

    override fun onLoadMoreInlineResults(offset: String) {
        val botId = _state.value.currentInlineBotId ?: return
        val query = _state.value.currentInlineQuery ?: return

        inlineBotJob?.cancel()
        inlineBotJob = scope.launch {
            _state.value = _state.value.copy(isInlineBotLoading = true)
            try {
                val results = repositoryMessage.getInlineBotResults(botId, chatId, query, offset)
                val currentResults = _state.value.inlineBotResults
                if (currentResults != null) {
                    val mergedResults = results?.let { currentResults.results + it.results } ?: currentResults.results
                    if (results != null) {
                        _state.value = _state.value.copy(
                            inlineBotResults = results.copy(results = mergedResults)
                        )
                    }
                } else {
                    _state.value = _state.value.copy(inlineBotResults = results)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _state.value = _state.value.copy(isInlineBotLoading = false)
            }
        }
    }

    override fun onSendInlineResult(resultId: String) {
        val results = _state.value.inlineBotResults ?: return
        scope.launch {
            repositoryMessage.sendInlineBotResult(
                chatId = chatId,
                queryId = results.queryId,
                resultId = resultId,
                replyToMsgId = _state.value.replyMessage?.id,
                threadId = _state.value.currentTopicId
            )
        }
        _state.update { it.copy(
            inlineBotResults = null,
            currentInlineBotId = null,
            currentInlineQuery = null,
            replyMessage = null
        ) }
    }

    private var mediaProgressJob: Job? = null

    private fun observeMediaProgress() {
        mediaProgressJob = scope.launch {
            while (isActive) {
                var hasChanges = false
                val currentMessages = _state.value.messages


                suspend fun checkProgress(
                    fileId: Int,
                    isDownloading: Boolean,
                    downloadProgress: Float,
                    currentPath: String?
                ): Triple<Boolean, Float, String?>? {
                    if (isDownloading && fileId != 0) {

                        val fileInfo = repositoryMessage.getFileInfo(fileId)
                        if (fileInfo != null) {
                            val progress =
                                if (fileInfo.size > 0) fileInfo.local.downloadedSize.toFloat() / fileInfo.size else 0f
                            val isCompleted = fileInfo.local.isDownloadingCompleted
                            val newPath = if (isCompleted) fileInfo.local.path else currentPath

                            if (progress != downloadProgress || isCompleted != !isDownloading || newPath != currentPath) {
                                return Triple(
                                    !isCompleted,
                                    progress,
                                    newPath.takeIf { it?.isNotEmpty() == true } ?: currentPath)
                            }
                        }
                    }
                    return null
                }

                val updatedMessages = currentMessages.map { msg ->
                    var updatedMsg = msg
                    when (val content = msg.content) {
                        is MessageContent.Photo -> {
                            checkProgress(
                                content.fileId,
                                content.isDownloading,
                                content.downloadProgress,
                                content.path
                            )?.let { (isDown, prog, path) ->
                                updatedMsg = msg.copy(
                                    content = content.copy(
                                        isDownloading = isDown,
                                        downloadProgress = prog,
                                        path = path
                                    )
                                )
                                hasChanges = true
                            }
                        }

                        is MessageContent.Video -> {
                            checkProgress(
                                content.fileId,
                                content.isDownloading,
                                content.downloadProgress,
                                content.path
                            )?.let { (isDown, prog, path) ->
                                updatedMsg = msg.copy(
                                    content = content.copy(
                                        isDownloading = isDown,
                                        downloadProgress = prog,
                                        path = path
                                    )
                                )
                                hasChanges = true
                            }
                        }

                        is MessageContent.Document -> {
                            checkProgress(
                                content.fileId,
                                content.isDownloading,
                                content.downloadProgress,
                                content.path
                            )?.let { (isDown, prog, path) ->
                                updatedMsg = msg.copy(
                                    content = content.copy(
                                        isDownloading = isDown,
                                        downloadProgress = prog,
                                        path = path
                                    )
                                )
                                hasChanges = true
                            }
                        }

                        is MessageContent.Audio -> {
                            checkProgress(
                                content.fileId,
                                content.isDownloading,
                                content.downloadProgress,
                                content.path
                            )?.let { (isDown, prog, path) ->
                                updatedMsg = msg.copy(
                                    content = content.copy(
                                        isDownloading = isDown,
                                        downloadProgress = prog,
                                        path = path
                                    )
                                )
                                hasChanges = true
                            }
                        }

                        is MessageContent.Voice -> {
                            checkProgress(
                                content.fileId,
                                content.isDownloading,
                                content.downloadProgress,
                                content.path
                            )?.let { (isDown, prog, path) ->
                                updatedMsg = msg.copy(
                                    content = content.copy(
                                        isDownloading = isDown,
                                        downloadProgress = prog,
                                        path = path
                                    )
                                )
                                hasChanges = true
                            }
                        }

                        is MessageContent.Gif -> {
                            checkProgress(
                                content.fileId,
                                content.isDownloading,
                                content.downloadProgress,
                                content.path
                            )?.let { (isDown, prog, path) ->
                                updatedMsg = msg.copy(
                                    content = content.copy(
                                        isDownloading = isDown,
                                        downloadProgress = prog,
                                        path = path
                                    )
                                )
                                hasChanges = true
                            }
                        }

                        else -> {}
                    }
                    updatedMsg
                }

                if (hasChanges) {
                    _state.value = _state.value.copy(messages = updatedMessages)
                }

                delay(300)
            }
        }
    }
}