package org.monogram.presentation.features.chats.currentChat.chatContent

import org.monogram.domain.models.MessageModel
import java.text.SimpleDateFormat
import java.util.*

sealed class GroupedMessageItem {
    data class Single(val message: MessageModel) : GroupedMessageItem()
    data class Album(val albumId: Long, val messages: List<MessageModel>) : GroupedMessageItem()
}

fun groupMessagesByAlbum(messages: List<MessageModel>): List<GroupedMessageItem> {
    val result = mutableListOf<GroupedMessageItem>()
    var currentAlbumId: Long? = null
    val currentAlbumMessages = mutableListOf<MessageModel>()

    for (msg in messages) {
        if (msg.mediaAlbumId != 0L) {
            if (currentAlbumId == msg.mediaAlbumId) {
                currentAlbumMessages.add(msg)
            } else {
                if (currentAlbumMessages.isNotEmpty()) {
                    result.add(GroupedMessageItem.Album(currentAlbumId!!, currentAlbumMessages.reversed()))
                    currentAlbumMessages.clear()
                }
                currentAlbumId = msg.mediaAlbumId
                currentAlbumMessages.add(msg)
            }
        } else {
            if (currentAlbumMessages.isNotEmpty()) {
                result.add(GroupedMessageItem.Album(currentAlbumId!!, currentAlbumMessages.reversed()))
                currentAlbumMessages.clear()
                currentAlbumId = null
            }
            result.add(GroupedMessageItem.Single(msg))
        }
    }
    if (currentAlbumMessages.isNotEmpty()) {
        result.add(GroupedMessageItem.Album(currentAlbumId!!, currentAlbumMessages.reversed()))
    }
    return result
}

fun shouldShowDate(current: MessageModel, older: MessageModel?): Boolean {
    val currentTimestamp = System.currentTimeMillis()
    val msgTimestamp = current.date.toLong() * 1000
    val fmt = SimpleDateFormat("yyyyDDD", Locale.US)

    if (fmt.format(Date(currentTimestamp)) == fmt.format(Date(msgTimestamp))) {
        return false
    }

    if (older == null) return true
    return !fmt.format(Date(msgTimestamp)).equals(fmt.format(Date(older.date.toLong() * 1000)))
}
