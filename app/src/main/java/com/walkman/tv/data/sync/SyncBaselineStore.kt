package com.walkman.tv.data.sync

import android.content.Context
import com.walkman.tv.data.store.JsonStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Persistence for the LX Sync [SyncBaseline].
 *
 * Deliberately a sibling of `library.json` (like `importedSonglists.json`) instead of a field
 * inside it: the baseline must survive exactly as long as the *agreement* does, is rewritten once
 * per successful sync, and must never be visible to the LX protocol payloads.
 *
 * A missing/corrupt file (or one written by a different server or account) simply means "no
 * baseline yet" - the next sync then behaves like the very first one and re-establishes it, which
 * is the safe direction: worst case we merge less cleverly, never that we delete something.
 */
class SyncBaselineStore internal constructor(filesDir: File) {
    constructor(context: Context) : this(context.filesDir)

    private val store = JsonStore(
        File(filesDir, "lxSyncBaseline.json"),
        SyncBaseline.serializer(),
        SyncBaseline(),
    )

    @Volatile
    private var cached: SyncBaseline? = null

    suspend fun loadAll() {
        cached = withContext(Dispatchers.IO) { store.load() }
    }

    /** The raw stored baseline (null when still unloaded and the file has nothing usable). */
    fun current(): SyncBaseline? = cached?.takeIf { it.serverId.isNotBlank() }

    /**
     * The baseline to merge against for [serverId], or null when there is none for this account
     * (first sync ever, another server, or a fresh install). A null baseline makes the caller adopt
     * the server's payload verbatim - the same behaviour this app had before - so an account switch
     * can never cross-merge two different libraries.
     */
    fun forServer(serverId: String?): SyncBaseline? {
        val stored = current() ?: return null
        if (serverId.isNullOrBlank()) return null
        return stored.takeIf { it.serverId == serverId }
    }

    suspend fun save(baseline: SyncBaseline) {
        cached = baseline
        withContext(Dispatchers.IO) { store.save(baseline) }
    }

    /** Forgets the agreement, e.g. when the user disables sync and clears the server. */
    suspend fun clear() = save(SyncBaseline())
}
