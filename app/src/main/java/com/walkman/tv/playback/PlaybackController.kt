package com.walkman.tv.playback

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.walkman.tv.data.store.PlaybackSnapshot
import com.walkman.tv.data.model.Quality
import com.walkman.tv.data.model.Track
import com.walkman.tv.playback.external.AudioMime
import com.walkman.tv.playback.external.ExternalPlaybackTarget
import com.walkman.tv.source.ResolveOrigin
import com.walkman.tv.source.ResolvedTrack
import com.walkman.tv.source.SourceManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

enum class RepeatMode { OFF, ALL, ONE }

data class PlaybackState(
    val queue: List<Track> = emptyList(),
    val index: Int = -1,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val resolving: Boolean = false,
    val warning: String? = null,
    val originLabel: String? = null,
    val quality: Quality? = null,
    val error: String? = null,
    val repeatMode: RepeatMode = RepeatMode.ALL,
    val shuffle: Boolean = false,
    val isMv: Boolean = false,
) {
    val currentTrack: Track? get() = queue.getOrNull(index)
}

/**
 * Single source of truth for playback (iOS `PlaybackEngine`). Owns one ExoPlayer; resolves each
 * track's URL lazily via [SourceManager] on switch (URLs expire), records history, loads lyrics,
 * and reuses the same player for MV video.
 */
class PlaybackController(
    context: Context,
    private val sources: SourceManager,
    private val lyricsFetcher: LyricsFetcher,
    private val audioSpecProbe: AudioSpecProbe? = null,
) {
    /**
     * Real-time audio level (0..1), smoothed by the audio thread inside [AudioLevelProcessor].
     * Drives the reactive waveform in the player + recommend NowPlayingPanel. Stays at 0 when
     * the player is paused or when ExoPlayer chooses audio offload (DSP path bypasses the
     * processor chain — the consuming Waveform then falls back to its synthetic animation).
     */
    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    /** Last quality the cascade settled on for the current track — used by [onPlayerError]
     *  to decide which tier to step down from on format-reject errors. */
    private var lastAttemptedQuality: Quality? = null

    /** Number of consecutive network-error retries on the current track. Reset to 0 on a fresh
     *  playAt() (qualityCap == null), on the format-cascade path, or once we've given up and
     *  skipped to next. Must NOT be reset when a retry's resolve succeeds: a URL that resolves
     *  fine but whose bytes always fail (e.g. HTTP 4xx) would otherwise retry forever. */
    private var networkRetryCount: Int = 0

    /** Number of consecutive *tracks* that have failed back-to-back (resolve failed or an
     *  unrecoverable player error). Auto-skip to next on each failure; stop only once this hits
     *  [MAX_CONSECUTIVE_FAILURES]. Reset to 0 when any track actually reaches READY, and on a
     *  fresh user-initiated queue. Distinct from [networkRetryCount], which retries one track. */
    private var consecutiveFailures: Int = 0

    /**
     * Optional local-first URL resolver. When set, [playAt] checks it before going to
     * [SourceManager]. Wired by [com.walkman.tv.di.AppContainer] to point at
     * DownloadStore.localFile + LocalMusicStore.fileUri so already-downloaded songs and
     * SAF-imported tracks skip the online resolve cascade entirely.
     *
     * Returns a uri string (`file://...`, `content://...`) or null if the track isn't local.
     */
    var localUrlResolver: ((Track) -> String?)? = null

    /**
     * Measured audio spec for the currently-playing URL (FLAC/MP3 codec params from the
     * file header). Null while probing or when the format isn't recognised. Drives the
     * badge ceiling-clamp in spec §6 and the small "FLAC 24bit/192kHz" caption next to it.
     */
    private val _audioSpec = MutableStateFlow<AudioSpec?>(null)
    val audioSpec: StateFlow<AudioSpec?> = _audioSpec.asStateFlow()

    /** Url currently being probed — used to discard late results when the user has switched
     *  tracks before the probe returned. */
    @Volatile private var probingUrl: String? = null
    private var probeJob: Job? = null

    private val audioLevelProcessor = AudioLevelProcessor { level ->
        _audioLevel.value = level
    }

    val player: ExoPlayer = buildPlayer(context.applicationContext, audioLevelProcessor)

    private val appContext = context.applicationContext

    /** Opt-in structured trace for the auto-next chain; silent on non-debuggable builds. */
    private val trace = PlaybackTrace(
        runCatching {
            (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        }.getOrDefault(false),
    )

    private companion object {
        /** ExoPlayer error codes that indicate the URL was fetched fine but the bytes can't be
         *  decoded — i.e. a format-level rejection. Spec §3 calls for one-tier cascade here. */
        val FORMAT_REJECT_CODES = setOf(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        )

        /** ExoPlayer error codes that point at transient network issues — connection drops,
         *  flaky CDN, expired URL on a paused track, etc. Worth retrying a few times before
         *  giving up on the track. */
        val NETWORK_RETRY_CODES = setOf(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
        )
        const val MAX_NETWORK_RETRIES = 3

        /** Stop auto-skipping once this many tracks fail back-to-back (a whole playlist of dead
         *  links shouldn't spin forever). */
        const val MAX_CONSECUTIVE_FAILURES = 3

        /** Hard ceiling for one URL resolve (all scripts, the quality cascade and the cross-source
         *  fallback included). A single script call already times out at 15s, but a hanging script
         *  could otherwise be retried for every quality tier - leaving the UI in `resolving` for
         *  minutes. 30s leaves room for one retry at the next tier before we give up and advance. */
        const val RESOLVE_TIMEOUT_MS = 30_000L

        /**
         * Build the ExoPlayer with settings tuned for music playback on Android TV:
         *
         * - Larger LoadControl buffers (60s max / 30s min target) so a Hi-Res FLAC at
         *   ~10 Mbps doesn't constantly underrun on slower connections; the start
         *   threshold (3s) keeps perceived play latency low.
         * - DefaultAudioSink with audio offload enabled — on Android 10+ the OS will
         *   route bit-perfect PCM/FLAC to dedicated DSP/HDMI passthrough when the
         *   downstream device can decode it, preserving 24-bit / 96-192 kHz tracks
         *   without resampling.
         * - DefaultRenderersFactory in PREFER_EXTENSION mode so the bundled FLAC /
         *   OPUS / VORBIS extension renderers (when present) win over the slower
         *   MediaCodec path.
         */
        fun buildPlayer(
            appContext: android.content.Context,
            levelProcessor: AudioLevelProcessor,
        ): ExoPlayer {
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    /* minBufferMs = */ 30_000,
                    /* maxBufferMs = */ 60_000,
                    /* bufferForPlaybackMs = */ 3_000,
                    /* bufferForPlaybackAfterRebufferMs = */ 5_000,
                )
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()

            // Prefer bundled FLAC/OPUS/VORBIS extension renderers over MediaCodec; pipe audio at
            // float precision so 24-bit sources don't get prematurely truncated to 16-bit PCM.
            // (Audio offload is opted in via TrackSelectionParameters below — the deprecated
            //  DefaultRenderersFactory.setEnableAudioOffload was removed in Media3 1.4.)
            //
            // Anonymous subclass overrides buildAudioSink so we can append our
            // [AudioLevelProcessor] to the DefaultAudioSink's processor chain. The processor
            // forwards bytes unchanged, so audio quality is preserved. When the OS chooses
            // audio offload (DSP / HDMI passthrough / A2DP offload), this chain is bypassed
            // entirely and the processor stops receiving samples — the consumer naturally
            // falls back to a synthetic waveform.
            val renderersFactory = object : DefaultRenderersFactory(appContext) {
                override fun buildAudioSink(
                    context: android.content.Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean,
                ): AudioSink? {
                    return DefaultAudioSink.Builder(context)
                        .setEnableFloatOutput(enableFloatOutput)
                        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                        .setAudioProcessorChain(
                            DefaultAudioSink.DefaultAudioProcessorChain(levelProcessor),
                        )
                        .build()
                }
            }
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                .setEnableAudioFloatOutput(true)
                .setEnableAudioTrackPlaybackParams(true)

            val audioAttrs = AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build()

            // Use the 1-arg constructor + setRenderersFactory(): the 2-arg Builder(Context,
            // RenderersFactory) overload is ambiguous with Builder(Context, MediaSource.Factory).
            val player = ExoPlayer.Builder(appContext)
                .setRenderersFactory(renderersFactory)
                .setLoadControl(loadControl)
                .setAudioAttributes(audioAttrs, /* handleAudioFocus = */ true)
                .setHandleAudioBecomingNoisy(true)
                .build()

            // Ask the OS for audio offload when the downstream sink (DSP / HDMI passthrough /
            // Bluetooth A2DP offload) can decode it directly — preserves bit-perfect PCM/FLAC.
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setAudioOffloadPreferences(
                    TrackSelectionParameters.AudioOffloadPreferences.Builder()
                        .setAudioOffloadMode(
                            TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED,
                        )
                        .build(),
                )
                .build()
            return player
        }
    }
    private var serviceStarted = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _lyrics = MutableStateFlow<List<LyricLine>>(emptyList())
    val lyrics: StateFlow<List<LyricLine>> = _lyrics.asStateFlow()

    /** Set by the app to record played tracks into history. */
    var onTrackStarted: ((Track) -> Unit)? = null
    /** Preferred play quality, fed from settings. */
    var preferredQuality: Quality = Quality.K320

    private var resolveJob: Job? = null

    /** Single-flight guard: only the newest transition may touch [player] or [_state]. */
    private val transitions = PlaybackTransitionGuard()

    /** Transition id whose MediaItem is currently loaded in [player] (0 = none). */
    private var mediaItemGen: Long = 0L

    /** Transition id whose STATE_ENDED was already consumed - one ended episode advances once. */
    private var endedHandledGen: Long = 0L

    /**
     * URL / quality of the track currently loaded in [player], kept only so the external-player
     * path can hand the *same* resolved URL to another app without re-resolving (spec 12). Cleared
     * on [stop]; [lastResolvedTrackId] guards against handing over a URL from a previous track
     * while a new one is still resolving.
     */
    @Volatile private var lastResolvedUrl: String? = null
    @Volatile private var lastResolvedTrackId: String? = null
    @Volatile private var lastResolvedQuality: Quality? = null

    /**
     * Snapshot of the most recently resolved track (url + mime), deliberately NOT cleared by
     * [stop]: the Settings page needs a *real* URL to enumerate external players with, and it is
     * often opened after playback stopped. The URL may have expired by then, which is fine — only
     * its scheme/shape is used for matching.
     */
    @Volatile private var lastExternalSnapshot: ExternalPlaybackTarget? = null

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.value = _state.value.copy(isPlaying = isPlaying)
                trace.step(
                    mediaItemGen,
                    if (isPlaying) "PLAYING" else "PAUSED",
                    "playWhenReady" to player.playWhenReady,
                    "playerState" to player.playbackState,
                )
                // Pause: drop the audio level immediately so the waveform settles instead
                // of holding whatever amplitude the last decoded chunk reported.
                if (!isPlaying) _audioLevel.value = 0f
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                trace.step(
                    mediaItemGen, "PLAYER_STATE",
                    "state" to playbackState, "playWhenReady" to player.playWhenReady,
                )
                if (playbackState == Player.STATE_ENDED) {
                    if (_state.value.isMv) exitMv() else handleEnded()
                } else {
                    // Leaving ENDED (repeat-one restart, new item, stop) re-arms the once-guard.
                    endedHandledGen = 0L
                }
                if (playbackState == Player.STATE_READY) {
                    // A track actually started — clear the consecutive-failure streak.
                    consecutiveFailures = 0
                    _state.value = _state.value.copy(durationMs = player.duration.coerceAtLeast(0))
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val gen = mediaItemGen
                trace.step(
                    gen, "ERROR",
                    "code" to error.errorCodeName, "errorCode" to error.errorCode,
                )
                if (!transitions.isCurrent(gen)) {
                    // Error from a media item a newer transition already replaced - never let the
                    // stale failure stop or advance the track the user is on now.
                    trace.step(gen, "STALE_DROP", "stage" to "onPlayerError")
                    return
                }
                // Format/codec rejections are the "encrypted .mgg" / "actually MP3 not FLAC" case
                // from spec §3 — re-cascade from one tier below the current attempt. Network /
                // unauthorized errors get surfaced as-is.
                val isFormatReject = error.errorCode in FORMAT_REJECT_CODES
                val current = lastAttemptedQuality
                val track = _state.value.currentTrack
                if (isFormatReject && track != null && current != null && current != Quality.K128) {
                    val full = Quality.orderedHighToLow
                    val idx = full.indexOf(current)
                    val below = if (idx >= 0 && idx + 1 < full.size) full[idx + 1] else Quality.K128
                    android.util.Log.w(
                        "PlaybackController",
                        "format reject @ ${current.key} (${error.errorCodeName}); re-cascading from ${below.key}",
                    )
                    _state.value = _state.value.copy(
                        warning = "${current.displayName} 格式不支持，降级到 ${below.displayName}",
                    )
                    networkRetryCount = 0 // format error path — separate from the network counter
                    playAt(_state.value.index, qualityCap = below, reason = "retry-format")
                    return
                }
                // Transient network — exponential backoff retry up to MAX_NETWORK_RETRIES.
                // After that, advance to next so playback doesn't dead-stop the queue.
                if (error.errorCode in NETWORK_RETRY_CODES && track != null) {
                    if (networkRetryCount < MAX_NETWORK_RETRIES) {
                        networkRetryCount++
                        val delayMs = 500L shl (networkRetryCount - 1) // 500ms / 1s / 2s
                        android.util.Log.w(
                            "PlaybackController",
                            "network error (${error.errorCodeName}); retry ${networkRetryCount}/$MAX_NETWORK_RETRIES in ${delayMs}ms",
                        )
                        _state.value = _state.value.copy(
                            warning = "网络不稳定，正在重试 ($networkRetryCount/$MAX_NETWORK_RETRIES)…",
                            resolving = true,
                            error = null,
                        )
                        val trackIdAtRetry = track.id
                        val genAtRetry = gen
                        scope.launch {
                            kotlinx.coroutines.delay(delayMs)
                            // Guard: a newer transition (user skip / auto-next) always wins.
                            if (transitions.isCurrent(genAtRetry) && _state.value.currentTrack?.id == trackIdAtRetry) {
                                playAt(_state.value.index, qualityCap = lastAttemptedQuality, reason = "retry-network")
                            }
                        }
                        return
                    }
                    // Retries exhausted — this track has failed; auto-skip (counts toward the
                    // consecutive-failure limit).
                    android.util.Log.w(
                        "PlaybackController",
                        "network retries exhausted on ${track.name}; skipping to next",
                    )
                    failAndAdvance(gen, "网络不稳定")
                    return
                }
                // Any other error — this track failed; auto-skip (or stop after too many in a row).
                failAndAdvance(gen, error.errorCodeName)
            }
        })
        startPositionTicker()
    }

    // MARK: - queue control

    fun setQueue(tracks: List<Track>, startIndex: Int = 0, autoPlay: Boolean = true) {
        if (tracks.isEmpty()) return
        consecutiveFailures = 0 // fresh user-initiated queue — start the failure streak over
        _state.value = _state.value.copy(queue = tracks, index = startIndex.coerceIn(0, tracks.size - 1))
        if (autoPlay) playAt(_state.value.index, reason = "set-queue")
    }

    /** Capture the parts of state we persist across launches. */
    fun snapshot(): PlaybackSnapshot {
        val s = _state.value
        return PlaybackSnapshot(
            queue = s.queue,
            currentIndex = s.index,
            wasPlaying = s.isPlaying,
            repeatModeOrdinal = s.repeatMode.ordinal,
            shuffle = s.shuffle,
        )
    }

    /** Restore queue + index from a snapshot. Resumes playback if the previous session was
     *  playing; URLs are resolved fresh (they expire). Called once from AppContainer.bootstrap. */
    fun restoreSnapshot(s: PlaybackSnapshot) {
        val mode = RepeatMode.values().getOrNull(s.repeatModeOrdinal) ?: RepeatMode.ALL
        _state.value = _state.value.copy(repeatMode = mode, shuffle = s.shuffle)
        if (s.queue.isEmpty() || s.currentIndex !in s.queue.indices) return
        // Stage the queue without auto-play first, so the UI shows the track immediately.
        _state.value = _state.value.copy(queue = s.queue, index = s.currentIndex, isPlaying = false)
        if (s.wasPlaying) playAt(s.currentIndex, reason = "restore")
    }

    fun playTrack(track: Track) = setQueue(listOf(track), 0, true)

    fun playAt(index: Int, qualityCap: Quality? = null, reason: String = "select") {
        val track = _state.value.queue.getOrNull(index) ?: return
        // Fresh attempt (no cap) clears the last-attempted memory + the network retry counter;
        // cascade retries (cap != null) keep them so [onPlayerError] can keep stepping down or
        // continue counting retries within the same logical attempt.
        if (qualityCap == null) {
            lastAttemptedQuality = null
            networkRetryCount = 0
        }
        val effectivePreferred = qualityCap ?: preferredQuality
        // Single-flight: this request supersedes every resolve / retry / failure-advance still in
        // flight. Those steps carry their own, now stale, id and are dropped when they land.
        val gen = transitions.begin()
        trace.step(
            gen, "TRANSITION_BEGIN",
            "reason" to reason, "index" to index, "track" to track.id,
            "source" to track.source.key, "quality" to effectivePreferred.key,
            "cap" to qualityCap?.key, "playWhenReady" to player.playWhenReady,
            "playerState" to player.playbackState,
        )
        _state.value = _state.value.copy(index = index, resolving = true, error = null, warning = null, isMv = false)
        // Switch lyrics immediately with the metadata — lyrics depend only on the track, not the
        // resolved play URL, so don't make them wait for (or hinge on) a successful audio resolve.
        // Guarded to fresh attempts so a quality-cascade retry of the same track doesn't refetch.
        if (qualityCap == null) loadLyrics(track)
        resolveJob?.cancel()
        resolveJob = scope.launch {
            trace.step(gen, "RESOLVE_START", "track" to track.id, "quality" to effectivePreferred.key)
            // Local fast-path: downloaded tracks + SAF-imported tracks skip the online cascade.
            // localUrlResolver is set up at AppContainer init time.
            val localUrl = runCatching { localUrlResolver?.invoke(track) }.getOrNull()
            val resolved: Result<ResolvedTrack> = if (localUrl != null) {
                trace.step(gen, "RESOLVE_OK", "origin" to "local")
                Result.success(
                    ResolvedTrack(
                        url = localUrl,
                        origin = ResolveOrigin.LocalFile,
                        quality = track.qualities.firstOrNull() ?: effectivePreferred,
                        warning = null,
                    ),
                )
            } else {
                try {
                    val r = withTimeout(RESOLVE_TIMEOUT_MS) { sources.resolveMusicURL(track, effectivePreferred) }
                    trace.step(gen, "RESOLVE_OK", "origin" to r.origin.label, "quality" to r.quality.key)
                    Result.success(r)
                } catch (e: TimeoutCancellationException) {
                    trace.step(gen, "RESOLVE_TIMEOUT", "afterMs" to RESOLVE_TIMEOUT_MS)
                    Result.failure(SourceManager.SourceException("解析播放地址超时"))
                } catch (e: CancellationException) {
                    // Superseded (or shutting down). Cancellation must unwind: it is NOT a
                    // playback failure and must never reach the auto-skip path.
                    trace.step(gen, "RESOLVE_CANCELLED")
                    throw e
                } catch (t: Throwable) {
                    trace.step(gen, "RESOLVE_FAIL", "cause" to (t::class.simpleName ?: "error"), "msg" to t.message)
                    Result.failure(t)
                }
            }
            // A newer transition owns the player now - drop this result instead of applying it.
            if (!transitions.isCurrent(gen)) {
                trace.step(gen, "STALE_DROP", "stage" to "after-resolve")
                return@launch
            }
            try {
                playResolvedOrFail(track, resolved, gen)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                android.util.Log.e("PlaybackController", "playAt crashed", t)
                failAndAdvance(gen, t.message ?: t::class.simpleName ?: "播放出错")
            }
        }
    }

    private fun playResolvedOrFail(track: Track, resolved: Result<ResolvedTrack>, gen: Long) {
        if (!transitions.isCurrent(gen)) {
            trace.step(gen, "STALE_DROP", "stage" to "before-apply")
            return
        }
        resolved.onSuccess { r ->
                // Remember the exact URL we just handed ExoPlayer so the external-player button can
                // pass the same (already-working, already-authenticated) URL on without resolving
                // again. URLs expire, but the user is playing this one right now.
                lastResolvedUrl = r.url
                lastResolvedTrackId = track.id
                lastResolvedQuality = r.quality
                lastExternalSnapshot = ExternalPlaybackTarget(
                    track = track,
                    url = r.url,
                    mimeType = AudioMime.resolve(r.url, null),
                    quality = r.quality,
                    codec = null,
                    transitionId = gen,
                )
                val item = MediaItem.Builder()
                    .setUri(r.url)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(track.name)
                            .setArtist(track.singer)
                            .setAlbumTitle(track.albumName)
                            .apply { track.picURL?.let { setArtworkUri(android.net.Uri.parse(it)) } }
                            .build(),
                    )
                    .build()
                // Remember the tier so onPlayerError can re-cascade from one tier below if
                // ExoPlayer rejects the file format.
                lastAttemptedQuality = r.quality
                player.setMediaItem(item)
                player.prepare()
                mediaItemGen = gen
                trace.step(gen, "SET_MEDIA_ITEM", "quality" to r.quality.key, "origin" to r.origin.label)
                // Fire the file-header probe so the badge can later clamp the claimed tier
                // down if the bytes turn out to be lower-grade than the script reported.
                // Spec §5.4: clear current spec first, run async, validate URL is still
                // current before publishing.
                _audioSpec.value = null
                probingUrl = r.url
                probeJob?.cancel()
                if (audioSpecProbe != null) {
                    probeJob = scope.launch {
                        val targetUrl = r.url
                        val spec = audioSpecProbe.probe(targetUrl)
                        if (spec != null && probingUrl == targetUrl) {
                            _audioSpec.value = spec
                        }
                    }
                }
                // NOTE: MediaSessionService startup is currently disabled — Android 12+ kills
                // the process via ForegroundServiceDidNotStartInTimeException if the service
                // doesn't post a media notification within 5s, which races with our URL-resolve
                // path. Re-enable once a proper MediaNotification.Provider is in place.
                // ensureService()
                player.playWhenReady = true
                trace.step(
                    gen, "PLAY_INTENT",
                    "playWhenReady" to player.playWhenReady, "playerState" to player.playbackState,
                )
                _state.value = _state.value.copy(
                    resolving = false,
                    warning = r.warning,
                    originLabel = r.origin.label,
                    quality = r.quality,
                    error = null,
                )
                onTrackStarted?.invoke(track)
                // (lyrics already loaded in playAt when the track switched)
            }.onFailure { e ->
                failAndAdvance(gen, e.message ?: "无法播放")
            }
    }

    /**
     * A track has definitively failed (couldn't resolve a URL, or an unrecoverable player error).
     * Auto-skip to the next track so the queue keeps going; only stop once
     * [MAX_CONSECUTIVE_FAILURES] tracks fail back-to-back. The streak resets when any track
     * reaches READY (see the player listener). Guarded so a user skipping mid-delay wins.
     */
    private fun failAndAdvance(gen: Long, reason: String) {
        // A newer transition already owns the player - this failure is stale (typically a resolve
        // we cancelled on purpose). Never let it stop or advance the track the user is on now.
        if (!transitions.isCurrent(gen)) {
            trace.step(gen, "STALE_DROP", "stage" to "failAndAdvance", "reason" to reason)
            return
        }
        // Stop the stale audio: playAt keeps the previous track playing while it resolves the
        // next one (for gapless switching). If the target failed, that old track would otherwise
        // keep playing — halt it now so a failed switch actually goes silent.
        runCatching { player.stop() }
        trace.step(gen, "FAIL_ADVANCE", "reason" to reason, "consecutive" to (consecutiveFailures + 1))
        networkRetryCount = 0
        consecutiveFailures++
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            consecutiveFailures = 0
            _state.value = _state.value.copy(
                error = "连续 $MAX_CONSECUTIVE_FAILURES 首播放失败，已停止（$reason）",
                resolving = false,
                warning = null,
            )
            return
        }
        _state.value = _state.value.copy(
            warning = "播放失败，自动播放下一首（$reason）",
            resolving = false,
            error = null,
        )
        val failedId = _state.value.currentTrack?.id
        scope.launch {
            delay(400)
            // Advance only while this transition is still current and the user hasn't picked
            // another song during the delay.
            if (transitions.isCurrent(gen) && _state.value.currentTrack?.id == failedId) {
                advance("auto-next-fail")
            }
        }
    }

    /**
     * Toggle audio offload (DSP direct path). Some vendor audio HALs (小米/鸿蒙/坚果 etc.)
     * advertise offload support but crash natively when fed FLAC/Hi-Res through it, so this
     * is exposed as a settings switch. Takes effect on the next format change / track switch.
     */
    fun setAudioOffloadEnabled(enabled: Boolean) {
        val mode = if (enabled) {
            TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
        } else {
            TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
        }
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setAudioOffloadPreferences(
                TrackSelectionParameters.AudioOffloadPreferences.Builder()
                    .setAudioOffloadMode(mode)
                    .build(),
            )
            .build()
    }

    fun togglePlay() {
        if (player.isPlaying) {
            player.pause()
            return
        }
        // Cold-start path: restoreSnapshot() stages the queue + index in [_state] but only
        // calls playAt() when the previous session was actively playing (wasPlaying=true).
        // If the user left paused, ExoPlayer is empty on relaunch — `player.play()` is a
        // no-op. Lazy-resolve the current track now so the play button actually starts the
        // song that the UI is already showing.
        val s = _state.value
        if (player.mediaItemCount == 0 && s.queue.isNotEmpty() && s.index in s.queue.indices) {
            playAt(s.index, reason = "toggle-play")
        } else {
            player.play()
        }
    }

    /** User-initiated "next" (UI button / remote). Natural end and failure recovery use [advance]. */
    fun next() = advance("user-next")

    /**
     * The one place that decides where playback goes next (repeat / shuffle / queue end) and
     * starts exactly one transition. Shared by the next button, STATE_ENDED and failure recovery
     * so those paths can never race each other into two transitions.
     */
    private fun advance(reason: String) {
        val s = _state.value
        if (s.queue.isEmpty()) return
        val nextIdx = PlaybackQueue.nextIndex(s.queue.size, s.index, s.repeatMode, s.shuffle)
        if (nextIdx == null) {
            trace.step(
                transitions.current, "QUEUE_END",
                "reason" to reason, "index" to s.index, "size" to s.queue.size,
            )
            player.pause()
            return
        }
        trace.step(transitions.current, "NEXT_SELECTED", "reason" to reason, "from" to s.index, "to" to nextIdx)
        playAt(nextIdx, reason = reason)
    }

    fun prev() {
        val s = _state.value
        if (s.queue.isEmpty()) return
        if (player.currentPosition > 3000) { player.seekTo(0); return }
        val prevIdx = PlaybackQueue.prevIndex(s.queue.size, s.index, s.shuffle) ?: return
        playAt(prevIdx, reason = "user-prev")
    }

    fun seekTo(ms: Long) = player.seekTo(ms.coerceAtLeast(0))

    fun setRepeatMode(mode: RepeatMode) { _state.value = _state.value.copy(repeatMode = mode) }

    fun toggleShuffle() { _state.value = _state.value.copy(shuffle = !_state.value.shuffle) }

    fun stop() {
        transitions.invalidate()
        resolveJob?.cancel()
        mediaItemGen = 0L
        endedHandledGen = 0L
        lastResolvedUrl = null
        lastResolvedTrackId = null
        lastResolvedQuality = null
        player.stop()
        player.clearMediaItems()
        _state.value = PlaybackState()
        _lyrics.value = emptyList()
    }

    // MARK: - MV

    /** Play an MV video url on the same player (UI attaches a PlayerView for output). */
    fun playMvUrl(url: String) {
        resolveJob?.cancel()
        val gen = transitions.begin()
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        mediaItemGen = gen
        player.playWhenReady = true
        trace.step(gen, "MV_PLAY", "playerState" to player.playbackState)
        _state.value = _state.value.copy(isMv = true, resolving = false, error = null)
    }

    /** Exit MV and resume the current audio track. */
    fun exitMv() {
        if (!_state.value.isMv) return
        _state.value = _state.value.copy(isMv = false)
        playAt(_state.value.index, reason = "exit-mv")
    }

    // MARK: - external player

    /**
     * The URL + best-effort MIME for the track the external player should play, or null when the
     * current track has not been resolved yet (or we only have a stale URL from a previous track).
     *
     * This never mutates playback state and never touches [player]'s queue — the external path is
     * additive (spec 12 / 22).
     */
    fun currentExternalTarget(): ExternalPlaybackTarget? {
        val s = _state.value
        val track = s.currentTrack ?: return null
        val spec = _audioSpec.value
        val quality = s.quality ?: lastResolvedQuality
        if (lastResolvedTrackId == track.id && !lastResolvedUrl.isNullOrBlank()) {
            val url = lastResolvedUrl!!
            return ExternalPlaybackTarget(
                track = track,
                url = url,
                mimeType = AudioMime.resolve(url, spec?.codec),
                quality = quality,
                codec = spec?.codec,
                transitionId = transitions.current,
            )
        }
        // Downloaded / SAF-imported tracks skip the resolve cascade, so there is no remembered
        // resolve URL — fall back to the local resolver's URI for this track.
        val local = runCatching { localUrlResolver?.invoke(track) }.getOrNull() ?: return null
        return ExternalPlaybackTarget(
            track = track,
            url = local,
            mimeType = AudioMime.resolve(local, spec?.codec),
            quality = quality,
            codec = spec?.codec,
            transitionId = transitions.current,
        )
    }

    /**
     * The most recent real (url, mime) pair, for the Settings "更换默认播放器" entry. Falls back to
     * the last resolved snapshot once playback has stopped, and is null when nothing has ever been
     * resolved — the settings page then asks the user to play a song first instead of inventing a
     * placeholder URL.
     */
    fun lastExternalTarget(): ExternalPlaybackTarget? =
        currentExternalTarget() ?: lastExternalSnapshot?.let {
            it.copy(mimeType = AudioMime.resolve(it.url, _audioSpec.value?.codec))
        }

    /**
     * Pause Walkman's own output before another app takes over the audio path, so the two never
     * play on top of each other (HDMI/eARC would otherwise mix them). The queue, index and history
     * are untouched — pressing play later resumes the same track.
     *
     * Returns whether Walkman was actually playing, so the caller can restore exactly that state
     * via [resumeAfterExternal] if the hand-off is dismissed or fails.
     */
    fun pauseForExternal(): Boolean {
        val wasPlaying = runCatching { player.isPlaying }.getOrDefault(false)
        runCatching { if (player.isPlaying) player.pause() }
        return wasPlaying
    }

    /** Undo [pauseForExternal] after a dismissed/failed hand-off. */
    fun resumeAfterExternal() {
        runCatching { player.play() }
    }

    // MARK: - internals

    private fun handleEnded() {
        val gen = mediaItemGen
        if (!transitions.isCurrent(gen)) {
            trace.step(gen, "ENDED_IGNORED", "why" to "stale-item")
            return
        }
        if (endedHandledGen == gen) {
            trace.step(gen, "ENDED_IGNORED", "why" to "duplicate")
            return
        }
        endedHandledGen = gen
        if (_state.value.resolving) {
            // A transition is already in flight (usually the user just skipped): let it win.
            trace.step(gen, "ENDED_IGNORED", "why" to "transition-in-flight")
            return
        }
        if (PlaybackQueue.endedAction(_state.value.repeatMode) == EndedAction.REPEAT_CURRENT) {
            trace.step(gen, "ENDED", "action" to "repeat-one")
            // Re-arm immediately: repeat-one restarts the same MediaItem, whose id never changes.
            // (The advance path keeps the guard, where a duplicate ENDED would skip two tracks.)
            endedHandledGen = 0L
            player.seekTo(0)
            player.play()
            return
        }
        trace.step(
            gen, "ENDED",
            "action" to "auto-next", "repeat" to _state.value.repeatMode, "index" to _state.value.index,
        )
        advance("auto-next")
    }

    private fun loadLyrics(track: Track) {
        _lyrics.value = emptyList()
        scope.launch {
            val lines = runCatching { lyricsFetcher.fetch(track) }.getOrDefault(emptyList())
            if (_state.value.currentTrack?.id == track.id) _lyrics.value = lines
        }
    }

    /** Start the MediaSessionService once, so background audio + remote controls work. */
    private fun ensureService() {
        if (serviceStarted) return
        serviceStarted = true
        runCatching {
            ContextCompat.startForegroundService(appContext, Intent(appContext, PlaybackService::class.java))
        }
    }

    private fun startPositionTicker() {
        scope.launch {
            while (true) {
                if (player.isPlaying || _state.value.isMv) {
                    _state.value = _state.value.copy(
                        positionMs = player.currentPosition.coerceAtLeast(0),
                        durationMs = player.duration.coerceAtLeast(0),
                    )
                }
                delay(1000)
            }
        }
    }

    fun release() {
        resolveJob?.cancel()
        player.release()
    }

    /** Current ExoPlayer audio session id — what an `Equalizer` etc must bind to. */
    val audioSessionId: Int get() = player.audioSessionId
}
