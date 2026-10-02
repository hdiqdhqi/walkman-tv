package com.walkman.tv.data.sync

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Official `LX.List.UserListInfo` (a user playlist *without* its songs) - the shape used by
 * `list_create` / `list_update`. Field order matches the official `buildUserListInfoFull`.
 */
@Serializable
data class LxUserListInfo(
    val id: String = "",
    val name: String = "",
    val source: String? = null,
    val sourceListId: String? = null,
    val locationUpdateTime: Long? = null,
)

/** Official `LX.List.UserListInfoFull` = [LxUserListInfo] + `list`. */
@Serializable
data class LxUserList(
    val id: String = "",
    val name: String = "",
    val source: String? = null,
    val sourceListId: String? = null,
    val locationUpdateTime: Long? = null,
    val list: List<MusicInfo> = emptyList(),
)

/**
 * Official `LX.Sync.List.ListData`: exactly the three lists the protocol syncs. History,
 * downloads, lyrics, local files metadata and settings are NOT part of this type.
 */
@Serializable
data class LxListData(
    val defaultList: List<MusicInfo> = emptyList(),
    val loveList: List<MusicInfo> = emptyList(),
    val userList: List<LxUserList> = emptyList(),
)

/**
 * JSON codec for the list-sync payloads. One shared instance so every encode/decode (including
 * the `list_sync_get_md5` input) uses identical formatting.
 *
 *  - `explicitNulls = false`: the official client's `JSON.stringify` omits `undefined` fields.
 *  - `coerceInputValues = true`: the official `interval` is `string | null`, so a `null` must
 *    land on our non-nullable default instead of throwing.
 *  - compact output (kotlinx emits no whitespace) so the MD5 input has the same shape as the
 *    server's `JSON.stringify` input.
 */
object LxListJson {
    @OptIn(ExperimentalSerializationApi::class)
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
    }

    fun encode(data: LxListData): String = json.encodeToString(LxListData.serializer(), data)

    fun decode(text: String): LxListData = json.decodeFromString(LxListData.serializer(), text)

    /** What `list_sync_get_md5` must answer: MD5 of the protocol serialization of our lists. */
    fun md5(data: LxListData): String = LxSyncProtocol.md5Hex(encode(data))
}