package com.walkman.tv.data.sync

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

/** Official `LX.AddMusicLocationType`: where `list_music_add` / `list_music_move` insert. */
enum class AddMusicLocation {
    TOP,
    BOTTOM;

    companion object {
        fun fromKey(key: String?): AddMusicLocation = if (key == "top") TOP else BOTTOM
    }
}

/**
 * One official `LX.Sync.List.ActionList` entry, parsed. Every action the server can push has an
 * explicit variant here - SyncManager/SyncClient never string-match on action names themselves.
 */
sealed interface LxListAction {
    /** Replace all three lists (`list_data_overwrite`). */
    data class DataOverwrite(val data: LxListData) : LxListAction

    data class CreateLists(val position: Int, val listInfos: List<LxUserListInfo>) : LxListAction

    data class RemoveLists(val ids: List<String>) : LxListAction

    data class UpdateLists(val lists: List<LxUserListInfo>) : LxListAction

    data class UpdateListPosition(val position: Int, val ids: List<String>) : LxListAction

    data class AddMusic(
        val listId: String,
        val musicInfos: List<MusicInfo>,
        val location: AddMusicLocation,
    ) : LxListAction

    data class MoveMusic(
        val fromId: String,
        val toId: String,
        val musicInfos: List<MusicInfo>,
        val location: AddMusicLocation,
    ) : LxListAction

    data class RemoveMusic(val listId: String, val ids: List<String>) : LxListAction

    data class UpdateMusic(val entries: List<Entry>) : LxListAction {
        /** Official `ListActionMusicUpdate` entry: which list, and the replacement `MusicInfo`. */
        data class Entry(val listId: String, val musicInfo: MusicInfo)
    }

    data class UpdateMusicPosition(
        val listId: String,
        val position: Int,
        val ids: List<String>,
    ) : LxListAction

    data class OverwriteMusic(val listId: String, val musicInfos: List<MusicInfo>) : LxListAction

    data class ClearMusic(val listIds: List<String>) : LxListAction

    companion object {
        /**
         * Parses a server action object `{action, data}`. Throws [LxSyncRpcException] for an
         * unknown action or a malformed payload - exactly like the official handlers, which let
         * the error travel back to the server (which then closes the socket with code 4100).
         */
        fun parse(element: JsonElement?): LxListAction {
            val obj = element as? JsonObject ?: throw LxSyncRpcException("list sync action: not an object")
            val name = (obj["action"] as? JsonPrimitive)?.content
                ?: throw LxSyncRpcException("list sync action: missing name")
            val data = obj["data"]
            return try {
                parsePayload(name, data)
            } catch (e: LxSyncRpcException) {
                throw e
            } catch (e: SerializationException) {
                // The official handlers let a bad payload travel back to the server; keep the
                // exception type uniform so callers only ever have to handle LxSyncRpcException.
                throw LxSyncRpcException("$name: invalid payload")
            }
        }

        private fun parsePayload(name: String, data: JsonElement?): LxListAction =
            when (name) {
                "list_data_overwrite" -> DataOverwrite(decode(data, LxListData.serializer(), name))
                "list_create" -> {
                    val dto = decode(data, ActionCreateDto.serializer(), name)
                    CreateLists(dto.position, dto.listInfos)
                }
                "list_remove" -> RemoveLists(decodeStringList(data, name))
                "list_update" -> UpdateLists(decodeList(data, LxUserListInfo.serializer(), name))
                "list_update_position" -> {
                    val dto = decode(data, ActionPositionDto.serializer(), name)
                    UpdateListPosition(dto.position, dto.ids)
                }
                "list_music_add" -> {
                    val dto = decode(data, ActionMusicAddDto.serializer(), name)
                    AddMusic(dto.id, dto.musicInfos, AddMusicLocation.fromKey(dto.addMusicLocationType))
                }
                "list_music_move" -> {
                    val dto = decode(data, ActionMusicMoveDto.serializer(), name)
                    MoveMusic(dto.fromId, dto.toId, dto.musicInfos, AddMusicLocation.fromKey(dto.addMusicLocationType))
                }
                "list_music_remove" -> {
                    val dto = decode(data, ActionMusicRemoveDto.serializer(), name)
                    RemoveMusic(dto.listId, dto.ids)
                }
                "list_music_update" -> {
                    val entries = decodeList(data, MusicUpdateDto.serializer(), name).map { dto ->
                        val info = dto.musicInfo
                            ?: throw LxSyncRpcException("$name: entry without musicInfo")
                        UpdateMusic.Entry(dto.id, info)
                    }
                    UpdateMusic(entries)
                }
                "list_music_update_position" -> {
                    val dto = decode(data, ActionMusicPositionDto.serializer(), name)
                    UpdateMusicPosition(dto.listId, dto.position, dto.ids)
                }
                "list_music_overwrite" -> {
                    val dto = decode(data, ActionMusicOverwriteDto.serializer(), name)
                    OverwriteMusic(dto.listId, dto.musicInfos)
                }
                "list_music_clear" -> ClearMusic(decodeStringList(data, name))
                else -> throw LxSyncRpcException("unknown list sync action: $name")
            }

        private fun <T> decode(
            element: JsonElement?,
            serializer: kotlinx.serialization.KSerializer<T>,
            name: String,
        ): T {
            val payload = element ?: throw LxSyncRpcException("$name: missing data")
            return LxListJson.json.decodeFromJsonElement(serializer, payload)
        }

        private fun <T> decodeList(
            element: JsonElement?,
            serializer: kotlinx.serialization.KSerializer<T>,
            name: String,
        ): List<T> {
            val payload = element as? JsonArray ?: throw LxSyncRpcException("$name: data is not an array")
            return payload.map { LxListJson.json.decodeFromJsonElement(serializer, it) }
        }

        private fun decodeStringList(element: JsonElement?, name: String): List<String> {
            val payload = element as? JsonArray ?: throw LxSyncRpcException("$name: data is not an array")
            return payload.map {
                (it as? JsonPrimitive)?.content ?: throw LxSyncRpcException("$name: id is not a string")
            }
        }
    }
}

@Serializable
private data class ActionCreateDto(
    val position: Int = 0,
    val listInfos: List<LxUserListInfo> = emptyList(),
)

@Serializable
private data class ActionPositionDto(val position: Int = 0, val ids: List<String> = emptyList())

@Serializable
private data class ActionMusicAddDto(
    val id: String = "",
    val musicInfos: List<MusicInfo> = emptyList(),
    val addMusicLocationType: String? = null,
)

@Serializable
private data class ActionMusicMoveDto(
    val fromId: String = "",
    val toId: String = "",
    val musicInfos: List<MusicInfo> = emptyList(),
    val addMusicLocationType: String? = null,
)

@Serializable
private data class ActionMusicRemoveDto(val listId: String = "", val ids: List<String> = emptyList())

@Serializable
private data class MusicUpdateDto(val id: String = "", val musicInfo: MusicInfo? = null)

@Serializable
private data class ActionMusicPositionDto(
    val listId: String = "",
    val position: Int = 0,
    val ids: List<String> = emptyList(),
)

@Serializable
private data class ActionMusicOverwriteDto(
    val listId: String = "",
    val musicInfos: List<MusicInfo> = emptyList(),
)