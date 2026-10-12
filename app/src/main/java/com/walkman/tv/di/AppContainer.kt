package com.walkman.tv.di

import android.content.Context
import com.walkman.tv.data.store.LibraryStore
import com.walkman.tv.data.store.PlaybackSnapshotStore
import com.walkman.tv.data.store.ScriptStore
import com.walkman.tv.data.store.SearchHistoryStore
import com.walkman.tv.data.store.SettingsStore
import com.walkman.tv.playback.EqualizerManager
import com.walkman.tv.playback.LyricsFetcher
import com.walkman.tv.playback.PlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import com.walkman.tv.source.OtherSourceFinder
import com.walkman.tv.source.SourceManager
import com.walkman.tv.source.builtin.BuiltInLyricResolver
import com.walkman.tv.source.builtin.BuiltInResolver
import com.walkman.tv.source.catalog.Boards
import com.walkman.tv.source.catalog.CatalogHttp
import com.walkman.tv.source.catalog.Catalogs
import com.walkman.tv.source.catalog.MvResolver
import com.walkman.tv.source.catalog.Songlists
import com.walkman.tv.source.js.ScriptHttpClient
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Hand-rolled dependency container (no Hilt). Holds app-wide singletons; created once in [App].
 */
class AppContainer(val appContext: Context) {

    /** Process-wide event bus for hardware-key events (e.g. KEYCODE_MENU). */
    val events: AppEvents = AppEvents()

    /** In-app HTTP server backing the phone-to-TV QR flow. null when start failed. */
    @Volatile var localServer: LocalServer? = null
        private set

    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val preload: String by lazy {
        appContext.assets.open("script/user-api-preload.js").use { it.readBytes().toString(Charsets.UTF_8) }
    }

    val catalogHttp by lazy { CatalogHttp(httpClient) }

    val catalogs by lazy { Catalogs(catalogHttp) }
    val hotSearch by lazy { com.walkman.tv.source.catalog.HotSearch(catalogHttp) }
    val boards by lazy { Boards(catalogHttp) }
    val songlists by lazy { Songlists(catalogHttp) }
    /** Backs the recommend (discover) right column — heroes / recommendations / boards. */
    val homeStore by lazy { com.walkman.tv.ui.recommend.HomeStore(songlists, boards) }
    val mvResolver by lazy { MvResolver(catalogHttp) }

    private val scriptHttp by lazy { ScriptHttpClient(httpClient) }
    private val builtInResolver by lazy { BuiltInResolver(httpClient) }
    private val builtInLyricResolver by lazy { BuiltInLyricResolver(catalogHttp) }
    private val otherSourceFinder by lazy { OtherSourceFinder(catalogs) }

    val sourceManager: SourceManager by lazy {
        SourceManager(preload, scriptHttp, otherSourceFinder, builtInResolver)
    }

    private val lyricsFetcher by lazy {
        LyricsFetcher(sourceManager, builtInLyricResolver, otherSourceFinder)
    }

    /** Created eagerly on the main thread in [App] (ExoPlayer needs a consistent looper). */
    lateinit var playbackController: PlaybackController
        private set

    val equalizerManager: EqualizerManager by lazy {
        EqualizerManager { playbackController.audioSessionId }
    }

    /** In-app updater — checks GitHub Releases, downloads the ABI-matched APK, launches install. */
    val updateManager: com.walkman.tv.playback.update.UpdateManager by lazy {
        com.walkman.tv.playback.update.UpdateManager(appContext, httpClient)
    }

    /** Queries/launches external ACTION_VIEW players for the player-screen hand-off (spec 12). */
    val externalPlayerLauncher: com.walkman.tv.playback.external.ExternalPlayerLauncher by lazy {
        com.walkman.tv.playback.external.ExternalPlayerLauncher(appContext)
    }

    val scriptStore: ScriptStore by lazy { ScriptStore(appContext, sourceManager) }
    val libraryStore: LibraryStore by lazy { LibraryStore(appContext.filesDir) }
    val settingsStore: SettingsStore by lazy { SettingsStore(appContext) }
    val playbackSnapshotStore: PlaybackSnapshotStore by lazy { PlaybackSnapshotStore(appContext) }
    val searchHistoryStore: SearchHistoryStore by lazy { SearchHistoryStore(appContext) }

    /** Wall-clock process start, reported in device.txt (spec §17). */
    private val appStartedAt: Long = System.currentTimeMillis()

    /**
     * Always-on, bounded running log (spec §1). Started in [bootstrap]; logging is on by default so
     * an intermittent problem can still be analysed after it happened - no opt-in switch.
     */
    val runLog: com.walkman.tv.data.logging.RunLog by lazy {
        com.walkman.tv.data.logging.RunLog(
            dir = java.io.File(appContext.filesDir, "logs"),
            freeBytes = { runCatching { appContext.filesDir.usableSpace }.getOrNull() },
        )
    }

    /** Temporary LAN log-download server; non-null only while a QR code is on screen. */
    @Volatile private var logServer: com.walkman.tv.data.logging.LogDownloadServer? = null

    /** LX Music Sync Server: credentials, transport and the UI-facing state machine. */
    val syncAuthStore: com.walkman.tv.data.sync.SyncAuthStore by lazy {
        com.walkman.tv.data.sync.SyncAuthStore(appContext)
    }
    val syncClient: com.walkman.tv.data.sync.SyncClient by lazy {
        com.walkman.tv.data.sync.SyncClient(httpClient, syncAuthStore, appScope)
    }
    /**
     * The last state this device and the sync server agreed on. Lives beside `library.json` so the
     * merge baseline survives a restart (and is never part of the LX payload itself).
     */
    val syncBaselineStore: com.walkman.tv.data.sync.SyncBaselineStore by lazy {
        com.walkman.tv.data.sync.SyncBaselineStore(appContext)
    }
    val syncManager: com.walkman.tv.data.sync.SyncManager by lazy {
        com.walkman.tv.data.sync.SyncManager(
            settingsStore,
            syncClient,
            libraryStore,
            appScope,
            syncBaselineStore,
        )
    }
    /** Imported (网易云 / QQ音乐 / 酷狗 / 酷我) playlists: origin records + background refresh. */
    val importedSonglistStore: com.walkman.tv.data.songlist.ImportedSonglistStore by lazy {
        com.walkman.tv.data.songlist.ImportedSonglistStore(appContext)
    }

    /**
     * Reads a playlist from its platform. One instance is both the fetcher (import / update) and
     * the capability lookup (`supports`), so the UI and the updater can never disagree about which
     * platforms are supported. Short links (163cn.tv / c6.y.qq.com / t1.kugou.com) are followed
     * through the catalog HTTP client before the platform API runs.
     */
    val onlinePlaylistRepository: com.walkman.tv.data.online.OnlinePlaylistRepository by lazy {
        com.walkman.tv.data.online.OnlinePlaylistRepository(
            serviceFor = { songlists.serviceFor(it) },
            resolveFinalUrl = { url -> catalogHttp.resolveFinalUrl(url) },
        )
    }

    /**
     * Manual + scheduled mirror updates of imported playlists. Deliberately separate from
     * [syncManager]: LX Sync moves *data between devices*, this re-reads a list from *its platform*.
     * The only contact point is LibraryStore - a refresh writes through `replaceListTracks`, whose
     * `changes` flow then carries it to the other devices exactly like a hand edit would.
     */
    val songlistAutoUpdater: com.walkman.tv.data.songlist.SonglistAutoUpdater by lazy {
        com.walkman.tv.data.songlist.SonglistAutoUpdater(
            settingsStore = settingsStore,
            libraryStore = libraryStore,
            originStore = importedSonglistStore,
            fetcher = onlinePlaylistRepository,
            support = onlinePlaylistRepository,
        )
    }

    /**
     * The one foreground poller for those playlists: while the app is on screen it asks
     * [com.walkman.tv.data.songlist.SonglistAutoUpdater.runDueCheck] every
     * [com.walkman.tv.data.songlist.SonglistAutoUpdateConfig.FOREGROUND_CHECK_INTERVAL_MS] — the
     * startup check, a resume after the interval and the timer all share this single loop, so no
     * Activity, page or rebuild can start a second one. Fed by [observeAppLifecycle] and switched by
     * the 歌单自动更新 setting in [bootstrap].
     */
    val songlistForegroundChecker: com.walkman.tv.data.songlist.SonglistForegroundChecker by lazy {
        com.walkman.tv.data.songlist.SonglistForegroundChecker(
            updater = songlistAutoUpdater,
            scope = appScope,
            awaitReady = { awaitBootstrap() },
            isOnline = { hasUsableNetwork() },
        )
    }

    /**
     * Whether the device currently has a usable network. Used only to *skip* a foreground tick (it is
     * retried on the next one), so an offline TV does not queue a doomed request per playlist every
     * three minutes — the same spirit as the WorkManager job's `NetworkType.CONNECTED` constraint.
     * The foreground checks are not the network's owner either way: 手动更新 always tries.
     */
    private fun hasUsableNetwork(): Boolean = runCatching {
        val manager = appContext.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            as? android.net.ConnectivityManager ?: return@runCatching true
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return@runCatching false
        capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(true)

    val coverCache: com.walkman.tv.data.store.CoverCache by lazy {
        com.walkman.tv.data.store.CoverCache(appContext)
    }
    val downloadStore: com.walkman.tv.data.store.DownloadStore by lazy {
        com.walkman.tv.data.store.DownloadStore(appContext)
    }
    val localFolderStore: com.walkman.tv.data.store.LocalFolderStore by lazy {
        com.walkman.tv.data.store.LocalFolderStore(appContext)
    }
    val downloadCoordinator: com.walkman.tv.playback.download.DownloadCoordinator by lazy {
        com.walkman.tv.playback.download.DownloadCoordinator(
            store = downloadStore,
            sources = sourceManager,
            coverCache = coverCache,
            http = httpClient,
            lyricsFetcher = lyricsFetcher,
            catalogHttp = catalogHttp,
        )
    }
    val localMusicStore: com.walkman.tv.playback.local.LocalMusicStore by lazy {
        com.walkman.tv.playback.local.LocalMusicStore(
            context = appContext,
            localFolderStore = localFolderStore,
            coverCache = coverCache,
        )
    }

    /** Process-lived scope used for operations that mustn't be cancelled by a UI navigation
     *  (e.g. settings → script delete). Public so screens can opt into it explicitly. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Completes once [bootstrap]'s persisted data is loaded. The WorkManager sweep awaits this so
     *  it can never run against an empty library - which would look like "every list was deleted"
     *  and make `runDueSweep` prune all origin records. */
    private val bootstrapDone = kotlinx.coroutines.CompletableDeferred<Unit>()

    /** Suspend until [bootstrap] has loaded the persisted stores. Never throws. */
    suspend fun awaitBootstrap() { bootstrapDone.await() }

    /**
     * Start reporting this process's foreground transitions to [songlistForegroundChecker].
     *
     * Called once from [com.walkman.tv.App.onCreate]; the callbacks fire on the main thread, so the
     * started-Activity counter needs no synchronisation. One counter for the whole process is what
     * guarantees exactly one loop: a rotation, a page switch or a second Activity never looks like
     * "the app was reopened".
     */
    fun observeAppLifecycle(application: android.app.Application) {
        application.registerActivityLifecycleCallbacks(ProcessLifecycle())
    }

    /** Foreground = at least one started Activity. See [observeAppLifecycle]. */
    private inner class ProcessLifecycle : android.app.Application.ActivityLifecycleCallbacks {
        private var started = 0

        override fun onActivityStarted(activity: android.app.Activity) {
            if (++started == 1) songlistForegroundChecker.onForeground()
        }

        override fun onActivityStopped(activity: android.app.Activity) {
            started = (started - 1).coerceAtLeast(0)
            if (started == 0) songlistForegroundChecker.onBackground()
        }

        override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) = Unit
        override fun onActivityResumed(activity: android.app.Activity) = Unit
        override fun onActivityPaused(activity: android.app.Activity) = Unit
        override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) = Unit
        override fun onActivityDestroyed(activity: android.app.Activity) = Unit
    }

    fun initPlayback() {
        if (!::playbackController.isInitialized) {
            playbackController = PlaybackController(
                appContext,
                sourceManager,
                lyricsFetcher,
                com.walkman.tv.playback.AudioSpecProbe(httpClient),
            )
            playbackController.onTrackStarted = { track ->
                appScope.launch { libraryStore.recordHistory(track) }
            }
            // Local-first URL resolver — downloads + SAF imports skip the online cascade.
            // Doubles as the play-time file check: if a COMPLETED download's file is gone we
            // flag it missing (badge disappears, 已下载 tab shows 文件缺失) and fall through to
            // the online cascade; if it's there we clear any stale missing flag.
            playbackController.localUrlResolver = { track ->
                // Resolves both File downloads and SAF-exported downloads to a playable Uri.
                val playable = downloadStore.localPlayableUri(track.id)
                when {
                    playable != null -> {
                        downloadStore.markPresent(track.id)
                        playable
                    }
                    else -> {
                        if (downloadStore.isDownloaded(track.id)) downloadStore.markMissing(track.id)
                        localMusicStore.fileUri(track)?.toString()
                    }
                }
            }
        }
    }

    // Hot-search JSON cache for the phone page (/api/hotsearch). Fetched at most once per 5 min;
    // called from NanoHTTPD's worker thread, so blocking here is fine.
    @Volatile private var hotSearchCache: String = "[]"
    @Volatile private var hotSearchCachedAt: Long = 0L

    /** Blocking hot-search JSON: [{"source":"kw","name":"酷我","words":[...]}, ...]. */
    private fun hotSearchJsonBlocking(): String {
        val now = System.currentTimeMillis()
        if (now - hotSearchCachedAt < 5 * 60_000L && hotSearchCache != "[]") return hotSearchCache
        val json = runCatching {
            kotlinx.coroutines.runBlocking {
                val cols = hotSearch.fetchAll()
                buildString {
                    append('[')
                    cols.forEachIndexed { i, col ->
                        if (i > 0) append(',')
                        append("{\"source\":\"").append(col.source.key)
                        append("\",\"name\":\"").append(col.source.displayName)
                        append("\",\"words\":[")
                        col.words.forEachIndexed { j, w ->
                            if (j > 0) append(',')
                            append('"').append(jsonEscape(w)).append('"')
                        }
                        append("]}")
                    }
                    append(']')
                }
            }
        }.getOrDefault("[]")
        if (json != "[]") { hotSearchCache = json; hotSearchCachedAt = now }
        return json
    }

    private fun jsonEscape(s: String): String = buildString {
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(c)
        }
    }

    /** Fetch a remote text resource (used to import a custom-source script from a URL). */
    suspend fun fetchText(url: String): String = kotlinx.coroutines.withContext(Dispatchers.IO) {
        okhttp3.Request.Builder().url(url).build().let { req ->
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                resp.body?.string() ?: throw IllegalStateException("空响应")
            }
        }
    }

    /** Load persisted data and wire settings → playback. Called once at startup. */
    fun bootstrap() {
        // Bring up the LAN HTTP server (best-effort; QR features just won't work if it fails).
        if (localServer == null) {
            localServer = LocalServer.start(events, hotSearchJson = { hotSearchJsonBlocking() })
        }
        // Running log first, so APP_START is the first line of a fresh log (spec §21). It is a
        // pure observer: no business logic depends on it.
        runLog.start()
        com.walkman.tv.playback.PlaybackTrace.sink = { transitionId, step, fields ->
            runLog.event(
                com.walkman.tv.data.logging.LogLevel.INFO,
                "Playback",
                step,
                transitionId,
                fields.toMap(),
            )
        }
        // External-player events (spec 21) go through their own sink so their event names
        // (EXTERNAL_PLAYER_*) stay distinct from the play-chain trace steps.
        com.walkman.tv.playback.external.ExternalPlayerLog.sink = { transitionId, event, fields ->
            runLog.event(
                com.walkman.tv.data.logging.LogLevel.INFO,
                "ExternalPlayer",
                event,
                transitionId,
                fields.toMap(),
            )
        }
        // Online-playlist (网易云 / QQ / 酷狗 / 酷我) events. The event name and the field set are
        // decided in OnlinePlaylistLog, which sanitises a platform id before it gets here, so the
        // log can never contain a full signed URL or a credential (spec §9).
        com.walkman.tv.data.online.OnlinePlaylistLog.sink = { event, fields ->
            runLog.event(
                com.walkman.tv.data.logging.LogLevel.INFO,
                com.walkman.tv.data.online.OnlinePlaylistLog.COMPONENT,
                event,
                fields = fields.toMap(),
            )
        }
        // The list-sync data path (baseline / three-way merge / incremental push). Same contract as
        // the online-playlist sink: the fields are counts, names of stages and reasons only - never a
        // payload, a connection code or a signed URL (spec §9).
        com.walkman.tv.data.sync.SyncLog.sink = { event, fields ->
            runLog.event(
                com.walkman.tv.data.logging.LogLevel.INFO,
                com.walkman.tv.data.sync.SyncLog.COMPONENT,
                event,
                fields = fields.toMap(),
            )
        }
        runLog.event(
            com.walkman.tv.data.logging.LogLevel.INFO,
            "App",
            "APP_START",
            fields = mapOf(
                "version" to appVersionName(),
                "sdk" to android.os.Build.VERSION.SDK_INT,
                "model" to android.os.Build.MODEL,
            ),
        )
        appScope.launch {
            settingsStore.loadAll()
            libraryStore.loadAll()
            importedSonglistStore.loadAll()
            // The merge baseline decides "first sync or daily sync", so it must be in memory before
            // the first auto-connect below.
            syncBaselineStore.loadAll()
            // Restore server credentials before any auto-connect so a returning user never has
            // to re-type the connection code (the stored clientId/key re-authenticate silently).
            syncAuthStore.loadAll()
            syncManager.start()
            // Everything the background songlist sweep reads is now loaded - let a WorkManager job
            // that started this process proceed (see SonglistUpdateWorker).
            bootstrapDone.complete(Unit)
            // Keep the WorkManager schedule in step with 歌单自动更新: enabled ⇒ one unique periodic
            // job at the configured interval, disabled ⇒ cancelled. Independent of LX Sync.
            settingsStore.settings
                .map { it.songlistAutoUpdateEnabled }
                .distinctUntilChanged()
                .onEach { enabled ->
                    runCatching {
                        if (enabled) {
                            com.walkman.tv.data.songlist.SonglistUpdateWork.ensureScheduled(appContext)
                        } else {
                            com.walkman.tv.data.songlist.SonglistUpdateWork.cancel(appContext)
                        }
                    }
                    // The foreground loop lives or dies with the same switch (spec §4.8); the manual
                    // 更新歌单 / 立即更新全部 entry points deliberately keep working either way.
                    songlistForegroundChecker.setEnabled(enabled)
                }
                .launchIn(appScope)
            // Observe - never drive - the sync state machine: each transition becomes a SYNC_* line
            // (spec §11). Uses the existing StateFlow, so the LX protocol is untouched.
            syncManager.state
                .onEach { st ->
                    runLog.event(
                        com.walkman.tv.data.logging.SyncEventMapping.level(st),
                        "Sync",
                        com.walkman.tv.data.logging.SyncEventMapping.event(st),
                        fields = com.walkman.tv.data.logging.SyncEventMapping.fields(st),
                    )
                }
                .launchIn(appScope)
            settingsStore.settings.onEach { s ->
                playbackController.preferredQuality = s.preferredQuality
                playbackController.setAudioOffloadEnabled(s.audioOffloadEnabled)
                sourceManager.fallbackEnabled = s.fallbackEnabled
                downloadStore.configuredRoot = s.customDownloadDir?.let { java.io.File(it) }
                downloadStore.downloadTreeUri = s.customDownloadTreeUri?.let { runCatching { android.net.Uri.parse(it) }.getOrNull() }
                downloadCoordinator.setMaxConcurrent(s.maxConcurrentDownloads)
            }.launchIn(appScope)
            scriptStore.loadAll()
            searchHistoryStore.load()
            coverCache.loadAll()
            downloadStore.loadAll()
            localFolderStore.loadAll()

            // Restore last session's queue + index + playing state. Run after scripts load so
            // any custom-source needed to resolve URLs is ready.
            val snapshot = playbackSnapshotStore.load()
            playbackController.restoreSnapshot(snapshot)

            // Auto-save the snapshot whenever queue/index/isPlaying changes. distinctUntilChanged
            // already coalesces the high-frequency position ticks since we only project the three
            // fields that matter here.
            playbackController.state
                .map { Triple(it.queue.map { t -> t.id }, it.index, it.isPlaying) }
                .distinctUntilChanged()
                .onEach { playbackSnapshotStore.save(playbackController.snapshot()) }
                .launchIn(appScope)
        }
    }

    /**
     * Start (or reuse) the temporary log download server. Returns null when there is no LAN IPv4
     * (spec §26) or the socket cannot be bound. Idempotent: at most one instance (spec §31).
     */
    fun startLogServer(): com.walkman.tv.data.logging.LogDownloadServer? {
        logServer?.let { return it }
        val ip = com.walkman.tv.data.sync.LocalDeviceAddress.currentLanIpv4(appContext) ?: return null
        val server = com.walkman.tv.data.logging.LogDownloadServer.start(
            host = ip,
            zip = { sinceMs -> buildLogZip(sinceMs) },
            onStopped = { logServer = null },
        ) ?: return null
        logServer = server
        return server
    }

    /** Stop the server and invalidate its token; safe to call when nothing is running. */
    fun stopLogServer() {
        val server = logServer ?: return
        logServer = null
        runCatching { server.stopNow() }
    }

    /** Read the stored logs and pack them with device.txt (spec §16/§17). */
    private fun buildLogZip(sinceMs: Long?): ByteArray {
        val now = System.currentTimeMillis()
        val slices = runLog.readAll(sinceMs)
        val metrics = appContext.resources.displayMetrics
        val facts = com.walkman.tv.data.logging.DeviceFacts(
            appVersion = appVersionName(),
            buildType = if ((appContext.applicationInfo.flags and
                    android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) "debug" else "release",
            deviceModel = android.os.Build.MODEL ?: "?",
            androidVersion = android.os.Build.VERSION.RELEASE ?: "?",
            sdkInt = android.os.Build.VERSION.SDK_INT,
            abis = android.os.Build.SUPPORTED_ABIS?.toList() ?: emptyList(),
            screenResolution = metrics.widthPixels.toString() + "x" + metrics.heightPixels,
            lanIpv4 = com.walkman.tv.data.sync.LocalDeviceAddress.currentLanIpv4(appContext),
            appStartMs = appStartedAt,
        )
        return com.walkman.tv.data.logging.LogExport.buildZip(slices, facts, now)
    }

    private fun appVersionName(): String = runCatching {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
    }.getOrNull() ?: "?"

    /** Synchronous final save — called right before the process is killed on user-confirmed exit. */
    fun saveSnapshotNow() {
        if (::playbackController.isInitialized) {
            runCatching { playbackSnapshotStore.saveBlocking(playbackController.snapshot()) }
        }
    }
}
