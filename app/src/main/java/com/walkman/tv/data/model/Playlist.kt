package com.walkman.tv.data.model

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * A list of tracks. Built-in lists use fixed ids ([LOVE], [HISTORY], [DEFAULT]); user lists get
 * a UUID. The former RN "试听列表" default list is replaced by the auto-recorded [HISTORY] list.
 *
 * [source] / [sourceListId] are the official LX `UserListInfo` metadata fields. Walkman has no
 * UI for them, but they are preserved so a user list imported from a songlist on another LX
 * client round-trips through sync instead of losing its origin.
 */
@Serializable
data class Playlist(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val tracks: List<Track> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis(),
    val source: String? = null,
    val sourceListId: String? = null,
) {
    companion object {
        const val LOVE = "love"
        const val HISTORY = "history"

        /** Official LX `defaultList` id. Stored but never shown as a user playlist (see LibraryStore). */
        const val DEFAULT = "default"
        const val LOVE_NAME = "我喜欢的"
        const val HISTORY_NAME = "播放历史"
        const val DEFAULT_NAME = "试听列表"
    }
}