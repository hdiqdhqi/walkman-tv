package com.walkman.tv.data.sync

import com.walkman.tv.data.model.Playlist
import com.walkman.tv.data.model.Quality
import com.walkman.tv.data.model.SourceID
import com.walkman.tv.data.model.Track
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.File

/** Shared fixtures for the offline LX sync tests. */

fun track(
    source: SourceID = SourceID.KW,
    songmid: String = "mid-1",
    name: String = "歌名",
    singer: String = "歌手",
    albumName: String? = null,
    albumId: String? = null,
    duration: Int? = 235,
    picURL: String? = null,
    qualities: List<Quality> = emptyList(),
    extras: Map<String, String> = emptyMap(),
    syncMeta: Map<String, JsonElement> = emptyMap(),
): Track = Track(
    id = if (source == SourceID.LOCAL) "local_hash-$songmid" else Track.makeID(source, songmid),
    name = name,
    singer = singer,
    albumName = albumName,
    albumId = albumId,
    source = source,
    songmid = songmid,
    duration = duration,
    picURL = picURL,
    qualities = qualities,
    extras = extras,
    syncMeta = syncMeta,
)

fun musicInfo(
    source: String = "kw",
    songmid: String = "mid-1",
    name: String = "歌名",
    singer: String = "歌手",
    meta: Map<String, JsonElement> = emptyMap(),
): MusicInfo = MusicInfo(
    id = "${source}_$songmid",
    name = name,
    singer = singer,
    source = source,
    interval = "03:55",
    meta = JsonObject(meta + ("songId" to kotlinx.serialization.json.JsonPrimitive(songmid))),
)

fun userList(
    id: String = "list-1",
    name: String = "歌单",
    tracks: List<MusicInfo> = emptyList(),
    source: String? = null,
    sourceListId: String? = null,
    locationUpdateTime: Long? = null,
): LxUserList = LxUserList(id, name, source, sourceListId, locationUpdateTime, tracks)

fun listData(
    defaultList: List<MusicInfo> = emptyList(),
    loveList: List<MusicInfo> = emptyList(),
    userList: List<LxUserList> = emptyList(),
): LxListData = LxListData(defaultList, loveList, userList)

fun lovePlaylist(vararg tracks: Track): Playlist = Playlist(Playlist.LOVE, Playlist.LOVE_NAME, tracks.toList())

fun tempDir(): File = File.createTempFile("walkman-sync-test", "").let { file ->
    file.delete()
    file.mkdirs()
    file
}