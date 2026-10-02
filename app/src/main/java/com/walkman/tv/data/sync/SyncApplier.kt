package com.walkman.tv.data.sync

import com.walkman.tv.data.model.Playlist
import com.walkman.tv.data.model.Track
import com.walkman.tv.data.store.LibraryStore

/**
 * Applies one server list action to Walkman's library.
 *
 * Every mutation goes through [LibraryStore.mutateSyncLists], so
 *  - the local JSON file is written once per action,
 *  - the read-modify-write cannot interleave with a user edit,
 *  - and no code outside this class touches the raw stored model.
 *
 * Semantics are ports of the official `handleRemoteListAction` / `listEvent` handlers
 * (`lx-music-desktop/src/main/modules/sync/listEvent.ts`, `lx-music-sync-server/src/modules/list/event.ts`).
 * Duplicate-tolerant on purpose: a repeated message must not duplicate songs or playlists.
 */
class SyncApplier(
    private val libraryStore: LibraryStore,
    private val repository: SyncRepository,
) {
    suspend fun apply(action: LxListAction) {
        if (action is LxListAction.DataOverwrite) {
            libraryStore.overwriteSyncLists(repository.toSyncLists(action.data))
            return
        }
        libraryStore.mutateSyncLists { current -> mutate(Working(current), action).toSyncLists() }
    }

    private fun mutate(w: Working, action: LxListAction): Working {
        when (action) {
            is LxListAction.DataOverwrite -> Unit // handled above

            is LxListAction.CreateLists -> w.createLists(action.position, action.listInfos)
            is LxListAction.RemoveLists -> w.removeLists(action.ids)
            is LxListAction.UpdateLists -> w.updateLists(action.lists)
            is LxListAction.UpdateListPosition -> w.updateListPosition(action.position, action.ids)

            is LxListAction.AddMusic -> w.addMusic(action.listId, action.musicInfos, action.location)
            is LxListAction.MoveMusic -> w.moveMusic(action.fromId, action.toId, action.musicInfos, action.location)
            is LxListAction.RemoveMusic -> w.removeMusic(action.listId, action.ids)
            is LxListAction.UpdateMusic -> w.updateMusic(action.entries)
            is LxListAction.UpdateMusicPosition -> w.updateMusicPosition(action.listId, action.position, action.ids)
            is LxListAction.OverwriteMusic -> w.overwriteMusic(action.listId, action.musicInfos)
            is LxListAction.ClearMusic -> w.clearMusic(action.listIds)
        }
        return w
    }

    /** Mutable view of the three protocol lists with `MusicInfo` (protocol ids) inside. */
    private class Working(lists: LibraryStore.SyncLists) {
        class UserList(
            var id: String,
            var name: String,
            var source: String?,
            var sourceListId: String?,
            var locationUpdateTime: Long?,
            val list: MutableList<MusicInfo>,
        )

        val defaultList: MutableList<MusicInfo> = infos(lists.defaultList)
        val loveList: MutableList<MusicInfo> = infos(lists.love)
        val userLists: MutableList<UserList> = lists.userLists.map { playlist ->
            UserList(
                id = playlist.id,
                name = playlist.name,
                source = playlist.source,
                sourceListId = playlist.sourceListId,
                locationUpdateTime = playlist.updatedAt,
                list = infos(playlist.tracks),
            )
        }.toMutableList()

        fun tracks(listId: String): MutableList<MusicInfo>? = when (listId) {
            Playlist.DEFAULT -> defaultList
            Playlist.LOVE -> loveList
            else -> userLists.firstOrNull { it.id == listId }?.list
        }

        fun createLists(position: Int, listInfos: List<LxUserListInfo>) {
            for (info in listInfos) {
                if (info.id.isEmpty() || userLists.any { it.id == info.id }) continue
                val entry = UserList(info.id, info.name, info.source, info.sourceListId, info.locationUpdateTime, mutableListOf())
                if (position < 0 || position >= userLists.size) userLists += entry
                else userLists.add(position, entry)
            }
        }

        fun removeLists(ids: List<String>) {
            val removing = ids.toHashSet()
            userLists.removeAll { it.id in removing }
        }

        fun updateLists(listInfos: List<LxUserListInfo>) {
            for (info in listInfos) {
                val target = userLists.firstOrNull { it.id == info.id } ?: continue
                target.name = info.name
                target.source = info.source
                target.sourceListId = info.sourceListId
                info.locationUpdateTime?.let { target.locationUpdateTime = it }
            }
        }

        fun updateListPosition(position: Int, ids: List<String>) {
            val now = System.currentTimeMillis()
            val byId = userLists.associateBy { it.id }.toMutableMap()
            val moving = ArrayList<UserList>(ids.size)
            for (id in ids) {
                val item = byId.remove(id) ?: continue
                item.locationUpdateTime = now
                moving += item
            }
            val rest = userLists.filter { byId.containsKey(it.id) }
            val out = ArrayList<UserList>(rest.size + moving.size)
            out += rest
            out.addAll(position.coerceIn(0, out.size), moving)
            userLists.clear()
            userLists += out
        }

        fun addMusic(listId: String, musicInfos: List<MusicInfo>, location: AddMusicLocation) {
            val target = tracks(listId) ?: return
            val present = target.mapTo(HashSet()) { it.id }
            val fresh = ArrayList<MusicInfo>(musicInfos.size)
            for (info in musicInfos) {
                if (present.add(info.id)) fresh += info
            }
            if (fresh.isEmpty()) return
            when (location) {
                AddMusicLocation.TOP -> target.addAll(0, fresh)
                AddMusicLocation.BOTTOM -> target += fresh
            }
        }

        fun moveMusic(fromId: String, toId: String, musicInfos: List<MusicInfo>, location: AddMusicLocation) {
            removeMusic(fromId, musicInfos.map { it.id })
            addMusic(toId, musicInfos, location)
        }

        fun removeMusic(listId: String, ids: List<String>) {
            val target = tracks(listId) ?: return
            val removing = ids.toHashSet()
            target.removeAll { it.id in removing }
        }

        fun updateMusic(entries: List<LxListAction.UpdateMusic.Entry>) {
            for (entry in entries) {
                val target = tracks(entry.listId) ?: continue
                val index = target.indexOfFirst { it.id == entry.musicInfo.id }
                if (index < 0) continue
                // Official `listMusicUpdateInfo` replaces name/singer/source/interval/meta only.
                target[index] = entry.musicInfo
            }
        }

        fun updateMusicPosition(listId: String, position: Int, ids: List<String>) {
            val target = tracks(listId) ?: return
            val byId = target.associateBy { it.id }
            val moving = ArrayList<MusicInfo>(ids.size)
            val movingIds = HashSet<String>(ids.size)
            for (id in ids) {
                val info = byId[id] ?: continue
                if (movingIds.add(id)) moving += info
            }
            if (moving.isEmpty()) return
            val rest = target.filter { it.id !in movingIds }
            val out = ArrayList<MusicInfo>(rest.size + moving.size)
            out += rest
            out.addAll(position.coerceIn(0, out.size), moving)
            target.clear()
            target += out
        }

        fun overwriteMusic(listId: String, musicInfos: List<MusicInfo>) {
            val target = tracks(listId) ?: return
            target.clear()
            target += musicInfos
        }

        fun clearMusic(listIds: List<String>) {
            for (id in listIds) tracks(id)?.clear()
        }

        fun toSyncLists(): LibraryStore.SyncLists {
            val now = System.currentTimeMillis()
            return LibraryStore.SyncLists(
                defaultList = defaultList.mapNotNull(LxMusicMapper::toTrack),
                love = loveList.mapNotNull(LxMusicMapper::toTrack),
                userLists = userLists.map { list ->
                    Playlist(
                        id = list.id,
                        name = list.name,
                        tracks = list.list.mapNotNull(LxMusicMapper::toTrack),
                        updatedAt = list.locationUpdateTime ?: 0L,
                        source = list.source,
                        sourceListId = list.sourceListId,
                    )
                },
            )
        }

        private companion object {
            fun infos(tracks: List<Track>): MutableList<MusicInfo> =
                tracks.mapNotNull(LxMusicMapper::toMusicInfo).toMutableList()
        }
    }
}