package org.monogram.data.infra

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import org.monogram.core.ScopeProvider
import org.monogram.data.db.dao.SponsorDao
import org.monogram.data.db.model.SponsorEntity
import org.monogram.data.gateway.TelegramGateway
import org.monogram.data.mapper.updateSponsorIds
import org.monogram.domain.repository.AuthRepository
import org.monogram.domain.repository.AuthStep
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "SponsorSync"
private const val SPONSOR_CHANNEL_ID = -1003640797855L
private const val HISTORY_LIMIT = 50
private const val AUTH_CHECK_INTERVAL_MS = 5L * 60L * 1000L
private const val ONE_DAY_MS = 24L * 60L * 60L * 1000L

class SponsorSyncManager(
    private val scopeProvider: ScopeProvider,
    private val gateway: TelegramGateway,
    private val sponsorDao: SponsorDao,
    private val authRepository: AuthRepository
) {
    private val started = AtomicBoolean(false)
    private val syncInProgress = AtomicBoolean(false)

    init {
        start()
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return

        scopeProvider.appScope.launch(Dispatchers.IO) {
            loadFromDatabase()
            syncOnce(force = true)

            while (true) {
                if (authRepository.authState.value !is AuthStep.Ready) {
                    delay(AUTH_CHECK_INTERVAL_MS)
                    continue
                }

                if (sponsorDao.getAllIds().isEmpty()) {
                    Log.d(TAG, "No sponsors in DB, syncing immediately")
                    syncOnce(force = true)
                    continue
                }

                delay(ONE_DAY_MS)
                syncOnce(force = false)
            }
        }
    }

    fun forceSync() {
        scopeProvider.appScope.launch(Dispatchers.IO) {
            syncOnce(force = true)
        }
    }

    private suspend fun loadFromDatabase() {
        val cachedIds = sponsorDao.getAllIds().toSet()
        updateSponsorIds(cachedIds)
        Log.d(TAG, "Loaded ${cachedIds.size} sponsor ids from DB")
    }

    private suspend fun syncOnce(force: Boolean) {
        if (!syncInProgress.compareAndSet(false, true)) return

        try {
            if (authRepository.authState.value !is AuthStep.Ready) {
                Log.d(TAG, "Skipping sponsor sync: user is not authorized")
                return
            }

            val latestUpdatedAt = sponsorDao.getLatestUpdatedAt() ?: 0L
            val age = System.currentTimeMillis() - latestUpdatedAt
            if (!force && latestUpdatedAt > 0L && age < ONE_DAY_MS) {
                Log.d(TAG, "Skipping sync: last sync ${age}ms ago")
                return
            }

            Log.d(TAG, "Sponsor sync started (force=$force)")
            val messages = gateway.execute(
                TdApi.GetChatHistory(SPONSOR_CHANNEL_ID, 0, 0, HISTORY_LIMIT, false)
            ) as? TdApi.Messages ?: run {
                Log.e(TAG, "Sponsor sync failed: GetChatHistory returned null")
                return
            }

            val parsedIds = parseSponsorIds(messages.messages)
            val oldIds = sponsorDao.getAllIds().toSet()

            val now = System.currentTimeMillis()
            val actualIds = when {
                parsedIds.isEmpty() && oldIds.isNotEmpty() -> {
                    Log.w(TAG, "Parsed empty sponsor list, keeping existing ${oldIds.size} ids")
                    oldIds
                }
                parsedIds.isEmpty() -> {
                    sponsorDao.clearAll()
                    emptySet()
                }
                else -> {
                    sponsorDao.insertAll(parsedIds.map { userId ->
                        SponsorEntity(
                            userId = userId,
                            sourceChannelId = SPONSOR_CHANNEL_ID,
                            updatedAt = now
                        )
                    })
                    sponsorDao.deleteNotIn(parsedIds.toList())
                    parsedIds
                }
            }

            updateSponsorIds(actualIds)

            val added = actualIds - oldIds
            val removed = oldIds - actualIds
            Log.d(
                TAG,
                "Sponsor sync finished: messages=${messages.totalCount}, ids=${actualIds.size}, added=${added.size}, removed=${removed.size}"
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Sponsor sync failed", t)
        } finally {
            syncInProgress.set(false)
        }
    }

    private fun parseSponsorIds(messages: Array<TdApi.Message>): Set<Long> {
        val idRegex = Regex("\\b\\d{5,20}\\b")
        return messages.asSequence()
            .mapNotNull { message -> extractText(message.content) }
            .flatMap { text -> idRegex.findAll(text).map { it.value } }
            .mapNotNull { it.toLongOrNull() }
            .toSet()
    }

    private fun extractText(content: TdApi.MessageContent): String? {
        return when (content) {
            is TdApi.MessageText -> content.text.text
            is TdApi.MessagePhoto -> content.caption.text
            is TdApi.MessageVideo -> content.caption.text
            is TdApi.MessageDocument -> content.caption.text
            is TdApi.MessageAudio -> content.caption.text
            is TdApi.MessageAnimation -> content.caption.text
            is TdApi.MessageVoiceNote -> content.caption.text
            else -> null
        }
    }
}
