package com.fsck.k9.backend.imap

import com.fsck.k9.mail.power.PowerManager
import com.fsck.k9.mail.store.imap.IdleRefreshManager
import com.fsck.k9.mail.store.imap.IdleResult
import com.fsck.k9.mail.store.imap.ImapFolderIdler
import com.fsck.k9.mail.store.imap.ImapStore
import kotlin.concurrent.thread
import timber.log.Timber

/**
 * Listens for changes to an IMAP folder in a dedicated thread.
 */
class ImapFolderPusher(
    private val imapStore: ImapStore,
    private val powerManager: PowerManager,
    private val idleRefreshManager: IdleRefreshManager,
    private val callback: ImapPusherCallback,
    private val accountName: String,
    private val folderServerId: String,
    private val idleRefreshTimeoutMs: Long
) {
    @Volatile
    private var folderIdler: ImapFolderIdler? = null

    @Volatile
    private var stopPushing = false

    fun start() {
        Timber.v("Starting ImapFolderPusher for %s / %s", accountName, folderServerId)

        thread(name = "ImapFolderPusher-$accountName-$folderServerId") {
            Timber.v("Starting ImapFolderPusher thread for %s / %s", accountName, folderServerId)

            runPushLoop()

            Timber.v("Exiting ImapFolderPusher thread for %s / %s", accountName, folderServerId)
        }
    }

    fun stop() {
        Timber.v("Stopping ImapFolderPusher for %s / %s", accountName, folderServerId)

        stopPushing = true
        folderIdler?.stop()
    }

    private fun runPushLoop() {
        val wakeLock = powerManager.newWakeLock("ImapFolderPusher-$accountName-$folderServerId")
        wakeLock.acquire()

        performInitialSync()

        val folderIdler = ImapFolderIdler.create(
            idleRefreshManager,
            wakeLock,
            imapStore,
            folderServerId,
            idleRefreshTimeoutMs
        ).also {
            folderIdler = it
        }

        try {
            while (!stopPushing) {
                val idleResult = folderIdler.idle()

                if (idleResult == IdleResult.SYNC) {
                    callback.onPushEvent(folderServerId)
                }
            }
        } catch (e: Exception) {
            Timber.v(e, "Exception in ImapFolderPusher")

            this.folderIdler = null
            callback.onPushError(folderServerId, e)
        }

        wakeLock.release()
    }

    private fun performInitialSync() {
        callback.onPushEvent(folderServerId)
    }
}
