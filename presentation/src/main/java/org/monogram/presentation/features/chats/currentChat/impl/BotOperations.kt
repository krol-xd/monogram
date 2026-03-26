package org.monogram.presentation.features.chats.currentChat.impl

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.update
import org.monogram.domain.models.*
import org.monogram.domain.repository.ChatMembersFilter
import org.monogram.presentation.features.chats.currentChat.DefaultChatComponent

internal fun DefaultChatComponent.handleMentionQueryChange(
    query: String?,
    allMembers: List<UserModel>,
    onMembersUpdated: (List<UserModel>) -> Unit
): Job? {
    if (query == null) {
        _state.update { it.copy(mentionSuggestions = emptyList()) }
        return null
    }

    return scope.launch {
        delay(150)
        val currentState = _state.value
        val suggestions = if (query.isEmpty()) {
            if (allMembers.isEmpty()) {
                val canLoadMembers = !currentState.isChannel || currentState.isAdmin
                if (canLoadMembers && (currentState.isGroup || currentState.isChannel)) {
                    try {
                        val members = userRepository.getChatMembers(chatId, 0, 200, ChatMembersFilter.Recent)
                            .map { it.user }
                        onMembersUpdated(members)
                        members
                    } catch (e: Exception) {
                        emptyList()
                    }
                } else {
                    emptyList()
                }
            } else {
                allMembers
            }
        } else {
            val lowerQuery = query.lowercase()
            val filtered = allMembers.filter {
                it.firstName.lowercase().contains(lowerQuery) ||
                        it.lastName?.lowercase()?.contains(lowerQuery) == true ||
                        it.username?.lowercase()?.contains(lowerQuery) == true
            }
            if (filtered.isEmpty() && query.length > 2) {
                try {
                    val searchResults = userRepository.searchContacts(query)
                    searchResults
                } catch (e: Exception) {
                    emptyList()
                }
            } else {
                filtered
            }
        }
        _state.update { it.copy(mentionSuggestions = suggestions) }
    }
}

internal fun DefaultChatComponent.handleInlineQueryChange(botUsername: String, query: String) {
    val normalizedUsername = botUsername.trim().removePrefix("@").lowercase()
    if (normalizedUsername.isBlank()) {
        clearInlineBotState()
        return
    }

    val normalizedQuery = query
    if (normalizedQuery.isBlank()) {
        clearInlineBotState()
        return
    }

    val state = _state.value
    if (
        state.currentInlineBotUsername == normalizedUsername &&
        state.currentInlineQuery == normalizedQuery &&
        (state.inlineBotResults != null || state.isInlineBotLoading)
    ) {
        return
    }

    inlineBotJob?.cancel()
    inlineBotJob = scope.launch {
        delay(300)
        val currentState = _state.value
        val cachedBotId = if (currentState.currentInlineBotUsername == normalizedUsername) {
            currentState.currentInlineBotId
        } else {
            null
        }

        _state.update {
            it.copy(
                inlineBotResults = if (it.currentInlineBotUsername == normalizedUsername) it.inlineBotResults else null,
                currentInlineBotId = cachedBotId,
                currentInlineBotUsername = normalizedUsername,
                currentInlineQuery = normalizedQuery,
                isInlineBotLoading = true
            )
        }

        try {
            val botId = cachedBotId ?: userRepository.searchPublicChat(normalizedUsername)
                ?.id

            if (!isActive) return@launch

            if (botId == null) {
                clearInlineBotState()
                return@launch
            }

            val results = repositoryMessage.getInlineBotResults(botId, chatId, normalizedQuery)
            if (!isActive) return@launch

            _state.update { liveState ->
                if (liveState.currentInlineBotUsername != normalizedUsername || liveState.currentInlineQuery != normalizedQuery) {
                    liveState
                } else {
                    liveState.copy(
                        inlineBotResults = results,
                        currentInlineBotId = botId
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("DefaultChatComponent", "Failed to fetch inline bot results", e)
            clearInlineBotState()
        } finally {
            if (isActive) {
                _state.update { liveState ->
                    if (liveState.currentInlineBotUsername == normalizedUsername && liveState.currentInlineQuery == normalizedQuery) {
                        liveState.copy(isInlineBotLoading = false)
                    } else {
                        liveState
                    }
                }
            }
        }
    }
}

internal fun DefaultChatComponent.handleLoadMoreInlineResults(offset: String) {
    if (offset.isBlank() || _state.value.isInlineBotLoading) return

    val botId = _state.value.currentInlineBotId ?: return
    val query = _state.value.currentInlineQuery ?: return

    inlineBotJob?.cancel()
    inlineBotJob = scope.launch {
        _state.update { it.copy(isInlineBotLoading = true) }
        try {
            val results = repositoryMessage.getInlineBotResults(botId, chatId, query, offset)
            if (!isActive) return@launch

            if (results != null) {
                _state.update { currentState ->
                    val currentResults = currentState.inlineBotResults
                    if (
                        currentResults != null &&
                        currentState.currentInlineBotId == botId &&
                        currentState.currentInlineQuery == query
                    ) {
                        val mergedResults = (currentResults.results + results.results)
                            .distinctBy { "${it.type}:${it.id}" }

                        currentState.copy(
                            inlineBotResults = currentResults.copy(
                                nextOffset = results.nextOffset,
                                results = mergedResults,
                                switchPmText = results.switchPmText ?: currentResults.switchPmText,
                                switchPmParameter = results.switchPmParameter ?: currentResults.switchPmParameter
                            )
                        )
                    } else {
                        currentState.copy(inlineBotResults = results)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("DefaultChatComponent", "Failed to load more inline bot results", e)
        } finally {
            if (isActive) {
                _state.update { it.copy(isInlineBotLoading = false) }
            }
        }
    }
}

internal fun DefaultChatComponent.handleSendInlineResult(resultId: String) {
    val results = _state.value.inlineBotResults ?: return
    scope.launch {
        try {
            repositoryMessage.sendInlineBotResult(
                chatId = chatId,
                queryId = results.queryId,
                resultId = resultId,
                replyToMsgId = _state.value.replyMessage?.id,
                threadId = _state.value.currentTopicId
            )
        } catch (e: Exception) {
            Log.e("DefaultChatComponent", "Failed to send inline bot result", e)
            return@launch
        }

        _state.update {
            it.copy(
                inlineBotResults = null,
                currentInlineBotId = null,
                currentInlineBotUsername = null,
                currentInlineQuery = null,
                replyMessage = null
            )
        }
    }
}

private fun DefaultChatComponent.clearInlineBotState() {
    val currentState = _state.value
    if (
        currentState.inlineBotResults == null &&
        currentState.currentInlineBotId == null &&
        currentState.currentInlineBotUsername == null &&
        currentState.currentInlineQuery == null &&
        !currentState.isInlineBotLoading
    ) {
        return
    }

    _state.update {
        it.copy(
            inlineBotResults = null,
            currentInlineBotId = null,
            currentInlineBotUsername = null,
            currentInlineQuery = null,
            isInlineBotLoading = false
        )
    }
}

internal fun DefaultChatComponent.handleReplyMarkupButtonClick(
    messageId: Long,
    button: InlineKeyboardButtonModel,
    botUserId: Long
) {
    scope.launch {
        when (val type = button.type) {
            is InlineKeyboardButtonType.Callback -> {
                repositoryMessage.onCallbackQuery(chatId, messageId, type.data)
            }

            is InlineKeyboardButtonType.Url -> {
                onLinkClick(type.url)
            }

            is InlineKeyboardButtonType.WebApp -> {
                onOpenMiniApp(type.url, button.text, botUserId)
            }

            is InlineKeyboardButtonType.Buy -> {
                repositoryMessage.onCallbackQueryBuy(chatId, messageId)
            }

            is InlineKeyboardButtonType.User -> {
                toProfile(type.userId)
            }

            else -> {
                Log.e("DefaultChatComponent", "Unknown inline keyboard button type: $type")
            }
        }
    }
}

internal fun DefaultChatComponent.handleKeyboardButtonClick(
    messageId: Long,
    button: KeyboardButtonModel,
    botUserId: Long
) {
    scope.launch {
        when (val type = button.type) {
            is KeyboardButtonType.Text -> {
                onSendMessage(button.text)
            }

            is KeyboardButtonType.WebApp -> {
                onOpenMiniApp(type.url, button.text, botUserId)
            }

            else -> {
                Log.e("DefaultChatComponent", "Unknown keyboard button type: $type")
            }
        }
    }
}

internal fun DefaultChatComponent.handleBotCommandClick(command: String) {
    onSendMessage(command)
}
