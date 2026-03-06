package org.monogram.presentation.features.profile.logs.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageModel
import org.monogram.presentation.features.chats.currentChat.components.chats.MessageText
import org.monogram.presentation.features.chats.currentChat.components.chats.buildAnnotatedMessageTextWithEmoji
import org.monogram.presentation.features.chats.currentChat.components.chats.rememberMessageInlineContent
import org.monogram.presentation.features.profile.logs.ProfileLogsComponent
import java.io.File

@Composable
fun MessagePreview(
    message: MessageModel,
    oldMessage: MessageModel? = null,
    component: ProfileLogsComponent
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val content = message.content

            val mediaPath = when (content) {
                is MessageContent.Photo -> content.path
                is MessageContent.Gif -> content.path
                is MessageContent.Video -> content.path
                is MessageContent.Sticker -> content.path
                else -> null
            }

            if (mediaPath != null) {
                val file = File(mediaPath)
                if (file.exists()) {
                    AsyncImage(
                        model = file,
                        contentDescription = null,
                        modifier = Modifier
                            .size(if (content is MessageContent.Gif) 80.dp else 40.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable {
                                when (content) {
                                    is MessageContent.Photo -> component.onPhotoClick(mediaPath, content.caption)
                                    is MessageContent.Gif -> component.onVideoClick(
                                        mediaPath,
                                        content.caption,
                                        content.fileId,
                                        true
                                    )

                                    is MessageContent.Video -> component.onVideoClick(
                                        mediaPath,
                                        content.caption,
                                        content.fileId,
                                        content.supportsStreaming
                                    )

                                    else -> {}
                                }
                            },
                        contentScale = ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                if (oldMessage != null && oldMessage.content is MessageContent.Text && content is MessageContent.Text) {
                    Text(
                        text = calculateDiff((oldMessage.content as MessageContent.Text).text, content.text),
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    when (content) {
                        is MessageContent.Text -> {
                            val annotatedText = buildAnnotatedMessageTextWithEmoji(
                                text = content.text,
                                entities = content.entities
                            )
                            val inlineContent = rememberMessageInlineContent(
                                entities = content.entities,
                                fontSize = 12f
                            )
                            MessageText(
                                text = annotatedText,
                                inlineContent = inlineContent,
                                style = MaterialTheme.typography.bodySmall,
                                entities = content.entities
                            )
                        }

                        is MessageContent.Photo -> Text(
                            content.caption.ifEmpty { "Photo" },
                            style = MaterialTheme.typography.bodySmall
                        )

                        is MessageContent.Video -> Text(
                            content.caption.ifEmpty { "Video" },
                            style = MaterialTheme.typography.bodySmall
                        )

                        is MessageContent.Document -> Text(
                            content.caption.ifEmpty { content.fileName },
                            style = MaterialTheme.typography.bodySmall
                        )

                        is MessageContent.Audio -> Text(
                            content.caption.ifEmpty { content.title.ifEmpty { content.fileName } },
                            style = MaterialTheme.typography.bodySmall
                        )

                        is MessageContent.Sticker -> Text(
                            "Sticker ${content.emoji}",
                            style = MaterialTheme.typography.bodySmall
                        )

                        is MessageContent.Voice -> Text("Voice message", style = MaterialTheme.typography.bodySmall)
                        is MessageContent.VideoNote -> Text("Video message", style = MaterialTheme.typography.bodySmall)
                        is MessageContent.Gif -> Text(
                            content.caption.ifEmpty { "GIF" },
                            style = MaterialTheme.typography.bodySmall
                        )

                        is MessageContent.Contact -> Text(
                            "Contact: ${content.firstName} ${content.lastName}".trim(),
                            style = MaterialTheme.typography.bodySmall
                        )

                        is MessageContent.Poll -> Text(
                            "Poll: ${content.question}",
                            style = MaterialTheme.typography.bodySmall
                        )

                        is MessageContent.Location -> Text(
                            "Location",
                            style = MaterialTheme.typography.bodySmall
                        )

                        is MessageContent.Venue -> Text(
                            "Venue: ${content.title}",
                            style = MaterialTheme.typography.bodySmall
                        )

                        is MessageContent.Service -> Text(
                            text = content.text,
                            style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        MessageContent.Unsupported -> Text(
                            "Unsupported message",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}
