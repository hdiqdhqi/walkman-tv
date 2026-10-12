package com.walkman.tv.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.SurroundSound
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.walkman.tv.ui.components.FolderBrowser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.walkman.tv.data.model.Quality
import com.walkman.tv.data.sync.LocalDeviceAddress
import com.walkman.tv.data.logging.RunLog
import com.walkman.tv.data.sync.SyncState
import com.walkman.tv.di.getLanIp
import com.walkman.tv.ui.appContainer
import com.walkman.tv.ui.components.QrDialog
import com.walkman.tv.ui.components.TvFocusGatedField
import com.walkman.tv.ui.components.TvFocusable
import com.walkman.tv.ui.components.TvKeypad
import com.walkman.tv.ui.components.TvPill
import com.walkman.tv.ui.components.syncModeLabel
import com.walkman.tv.ui.theme.AppColors
import com.walkman.tv.playback.update.UpdateState
import kotlinx.coroutines.launch

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val settings by appContainer.settingsStore.settings.collectAsState()
    val scripts by appContainer.scriptStore.scripts.collectAsState()
    var url by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var showQr by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<com.walkman.tv.data.model.UserScript?>(null) }

    // System file picker (SAF) for .js scripts.
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                status = "正在读取文件…"
                val raw = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use {
                            it.readBytes().toString(Charsets.UTF_8)
                        }
                    }.getOrNull()
                }
                if (raw.isNullOrEmpty()) {
                    status = "读取文件失败"
                } else {
                    status = "正在导入文件…"
                    val r = appContainer.scriptStore.import(raw)
                    status = r.fold({ "已导入：${it.name}" }, { "导入失败：${it.message}" })
                }
            }
        }
    }

    // In-app File browser for the download folder (TVs often lack a SAF picker). Writing needs
    // WRITE_EXTERNAL_STORAGE on API ≤ 29, or All-files access on 30+.
    var showDownloadBrowser by remember { mutableStateOf(false) }
    var dlPermHint by remember { mutableStateOf(false) }
    val dlPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) showDownloadBrowser = true else dlPermHint = true }

    fun openDownloadPicker() {
        if (hasStorageWriteAccess(context)) {
            showDownloadBrowser = true
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            dlPermHint = true
            runCatching {
                context.startActivity(
                    android.content.Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure {
                runCatching {
                    context.startActivity(
                        android.content.Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
        } else {
            dlPermLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    // Receive script payloads from the phone-to-TV QR flow.
    LaunchedEffect(Unit) {
        appContainer.events.qrScriptUrl.collect { u ->
            showQr = false
            importFromUrl(scope, u) { status = it }
        }
    }
    LaunchedEffect(Unit) {
        appContainer.events.qrScriptText.collect { raw ->
            showQr = false
            status = "正在导入上传的脚本…"
            val r = appContainer.scriptStore.import(raw)
            status = r.fold({ "已导入：${it.name}" }, { "导入失败：${it.message}" })
        }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(top = 8.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Section("播放音质") {
            // 8 tiers including extended (hires/atmos/master) — wrap so they fit on any TV width.
            // Each pill carries a small icon to make the tier intent scannable in one glance.
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Quality.orderedHighToLow.reversed().forEach { q ->
                    TvPill(
                        onClick = { scope.launch { appContainer.settingsStore.update { it.copy(preferredQuality = q) } } },
                        selected = settings.preferredQuality == q,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.tv.material3.Icon(
                                imageVector = qualityIcon(q),
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(q.displayName, fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        Section("发现页音源") {
            // Toggle each source in/out. The discover (home) page subtitle and section
            // contents track this list in real time — see HomeStore.loadIfNeeded.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.walkman.tv.data.model.SourceID.homePageOrder.forEach { src ->
                    val active = src in settings.homeSources
                    TvPill(
                        onClick = {
                            scope.launch {
                                appContainer.settingsStore.update { s ->
                                    val next = if (active) s.homeSources - src else s.homeSources + src
                                    // Spec §8: never let the set collapse to empty — keep at least
                                    // one source so the discover page stays usable.
                                    s.copy(homeSources = if (next.isEmpty()) s.homeSources else next)
                                }
                            }
                        },
                        selected = active,
                    ) {
                        Text(src.displayName, fontSize = 13.sp)
                    }
                }
            }
        }

        Section("内置直连兜底") {
            ToggleRow("脚本失败时尝试内置直连（kw/wy）", settings.fallbackEnabled) {
                scope.launch { appContainer.settingsStore.update { it.copy(fallbackEnabled = !it.fallbackEnabled) } }
            }
        }

        Section("歌单自动更新") {
            // Playlists imported from a platform share link (网易云 / QQ音乐 / 酷狗 / 酷我) remember
            // their origin and can be re-fetched: on demand from the playlist page, and on this
            // schedule in the background — LX Music's per-list isAutoUpdate. 咪咕 has no songlist
            // API, so its lists are never queued.
            ToggleRow("自动更新导入的歌单", settings.songlistAutoUpdateEnabled) {
                scope.launch {
                    appContainer.settingsStore.update { it.copy(songlistAutoUpdateEnabled = !it.songlistAutoUpdateEnabled) }
                }
            }
            if (settings.songlistAutoUpdateEnabled) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    // The pills configure the *background* cadence; while the app is on screen the
                    // foreground poller re-checks every SonglistAutoUpdateConfig.FOREGROUND_CHECK_INTERVAL_MS.
                    Text("后台更新间隔", color = AppColors.TextMuted, fontSize = 12.sp)
                    listOf(3, 6, 12, 24).forEach { hours ->
                        TvPill(
                            onClick = {
                                scope.launch { appContainer.settingsStore.update { it.copy(songlistAutoUpdateIntervalHours = hours) } }
                            },
                            selected = settings.songlistAutoUpdateIntervalHours == hours,
                        ) {
                            Text("$hours 小时", fontSize = 13.sp)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "应用在前台时每 " +
                        "${com.walkman.tv.data.songlist.SonglistAutoUpdateConfig.FOREGROUND_CHECK_INTERVAL_MS / 60_000} " +
                        "分钟检查一次在线歌单，只有检测到变化才会更新（不会重复写入，也不会因此触发同步推送）。",
                    color = AppColors.TextMuted,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            var bulkStatus by remember { mutableStateOf<String?>(null) }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                TvPill(
                    onClick = {
                        bulkStatus = "正在更新…"
                        scope.launch {
                            val r = appContainer.songlistAutoUpdater.updateAllNow()
                            bulkStatus = when {
                                r.attempted == 0 -> "没有可更新的歌单"
                                r.applied == 0 && r.failed > 0 -> "更新失败（${r.failed} 个）"
                                r.failed == 0 && r.skipped == 0 -> "已更新 ${r.applied} 个歌单"
                                else -> "已更新 ${r.applied} 个，跳过 ${r.skipped} 个，失败 ${r.failed} 个"
                            }
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text("立即更新全部", fontSize = 13.sp)
                }
                bulkStatus?.let {
                    Text(it, color = AppColors.TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            // Result of the most recent refresh — including the silent background sweeps, so an
            // automatic update is never invisible from the UI.
            val lastRefresh by appContainer.songlistAutoUpdater.message.collectAsState()
            lastRefresh?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    "最近一次：$it",
                    color = AppColors.TextMuted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // When the background job is expected to do something. Android may run it later — the
            // text says so instead of promising an exact minute (spec §7.2).
            Spacer(Modifier.height(2.dp))
            Text(
                "下次预计更新：${songlistDueText(appContainer.songlistAutoUpdater.nextDueAt())}" +
                    "（自动更新关闭或没有符合条件的歌单时不执行）",
                color = AppColors.TextMuted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Section("音频硬件直通") {
            ToggleRow("Hi-Res 硬件直通（播放无损闪退时请关闭）", settings.audioOffloadEnabled) {
                scope.launch { appContainer.settingsStore.update { it.copy(audioOffloadEnabled = !it.audioOffloadEnabled) } }
            }
        }

        Section("跳转播放") {
            // Not a global playback mode: Walkman stays the default. This only decides which app
            // the player-screen "跳转播放" button hands the current song to. There is deliberately
            // no package enumeration here (that would need <queries>): the pick goes through
            // Android's own chooser and we remember the chosen component.
            var handoffNotice by remember { mutableStateOf<String?>(null) }
            var pickerPlayers by remember {
                mutableStateOf<List<com.walkman.tv.playback.external.ExternalPlayerChoice>>(emptyList())
            }
            var showPlayerPicker by remember { mutableStateOf(false) }
            val savedRef = com.walkman.tv.playback.external.ExternalPlayerRef.parse(
                settings.defaultHandoffPlayer,
                settings.defaultHandoffPlayerLabel,
            )
            fun notifyHandoff(message: String) {
                // Always give visible feedback: a remote-driven button that silently does nothing
                // is indistinguishable from a broken focus chain.
                handoffNotice = message
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
            // Enumerate the TV's real players with the most recent *real* play URL. Deliberately
            // no placeholder URL: if nothing has been resolved yet we say so instead.
            fun openPlayerPicker() {
                val target = appContainer.playbackController.lastExternalTarget()
                if (target == null) {
                    notifyHandoff("请先播放一首歌曲后再选择默认播放器")
                    return
                }
                val players = appContainer.externalPlayerLauncher.findPlayers(target.url, target.mimeType)
                if (players.isEmpty()) {
                    notifyHandoff("未找到可用的外部播放器")
                    return
                }
                pickerPlayers = players
                showPlayerPicker = true
            }
            TvFocusable(
                onClick = { openPlayerPicker() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "默认跳转播放器",
                        color = AppColors.TextPrimary,
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        savedRef?.displayName ?: "未设置",
                        color = AppColors.AccentGreen,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TvPill(onClick = { openPlayerPicker() }) {
                    Text("更换默认播放器", fontSize = 13.sp)
                }
                TvPill(
                    onClick = {
                        if (settings.defaultHandoffPlayer == null) {
                            notifyHandoff("当前没有设置默认播放器")
                        } else {
                            scope.launch {
                                appContainer.settingsStore.update {
                                    it.copy(defaultHandoffPlayer = null, defaultHandoffPlayerLabel = null)
                                }
                                com.walkman.tv.playback.external.ExternalPlayerLog.emitStandalone(
                                    com.walkman.tv.playback.external.ExternalPlayerLog.EVENT_DEFAULT_CLEARED,
                                )
                                notifyHandoff("已清除默认播放器")
                            }
                        }
                    },
                ) {
                    Text("清除默认播放器", fontSize = 13.sp)
                }
            }
            handoffNotice?.let {
                Text(it, color = AppColors.AccentGreen, fontSize = 12.sp)
            }
            Text(
                "开启后详情页会显示「跳转播放」按钮，可将当前歌曲的播放地址交给电视上的其他播放器打开。" +
                    "该方式会把媒体直链暴露给外部应用。",
                color = AppColors.TextMuted,
                fontSize = 12.sp,
            )

            if (showPlayerPicker) {
                com.walkman.tv.ui.components.ExternalPlayerPickerDialog(
                    players = pickerPlayers,
                    onPick = { ref ->
                        showPlayerPicker = false
                        scope.launch {
                            appContainer.settingsStore.update {
                                it.copy(defaultHandoffPlayer = ref.componentString, defaultHandoffPlayerLabel = ref.label)
                            }
                            com.walkman.tv.playback.external.ExternalPlayerLog.emitSelected(
                                0L,
                                ref.packageName,
                                ref.label,
                            )
                            notifyHandoff("已设置默认播放器：" + ref.displayName)
                        }
                    },
                    onDismiss = { showPlayerPicker = false },
                )
            }
        }

        Section("歌词翻译") {
            ToggleRow("显示歌词翻译", settings.showLyricTranslation) {
                scope.launch { appContainer.settingsStore.update { it.copy(showLyricTranslation = !it.showLyricTranslation) } }
            }
        }

        Section("歌词大小") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.walkman.tv.data.store.LyricSize.entries.forEach { size ->
                    TvPill(
                        onClick = { scope.launch { appContainer.settingsStore.update { it.copy(lyricSize = size) } } },
                        selected = settings.lyricSize == size,
                    ) {
                        Text(size.label, fontSize = 13.sp)
                    }
                }
            }
        }

        Section("黑胶颜色") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.walkman.tv.data.store.VinylColor.entries.forEach { color ->
                    TvPill(
                        onClick = { scope.launch { appContainer.settingsStore.update { it.copy(vinylColor = color) } } },
                        selected = settings.vinylColor == color,
                    ) {
                        Text(color.label, fontSize = 13.sp)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "播放页黑胶唱片的配色，立即生效。默认翠绿（与官方一致），经典黑即本应用此前的黑色唱片外观。",
                color = AppColors.TextMuted,
                fontSize = 11.sp,
            )
        }

        Section("播放界面") {
            ToggleRow("播放控件自动隐藏", settings.autoHidePlayerControls) {
                scope.launch { appContainer.settingsStore.update { it.copy(autoHidePlayerControls = !it.autoHidePlayerControls) } }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "开启后，播放页 5 秒无操作自动隐藏进度条和播放按钮，按方向键或确认键重新显示。",
                color = AppColors.TextMuted,
                fontSize = 11.sp,
            )
        }

        Section("下载目录") {
            val roots = remember { appContainer.downloadStore.availableRoots() }
            val treeUriStr = settings.customDownloadTreeUri
            val usingTree = treeUriStr != null
            // A folder picked via the in-app browser is a customDownloadDir that isn't one of the
            // app-scoped volume roots.
            val usingCustomFolder = settings.customDownloadDir != null &&
                roots.none { it.dir.absolutePath == settings.customDownloadDir }
            // Current volume = configured path, or the first volume (default) when unset. Only
            // highlighted when a SAF folder is NOT in use.
            val currentPath = settings.customDownloadDir
                ?: roots.firstOrNull()?.dir?.absolutePath
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                roots.forEachIndexed { index, opt ->
                    val path = opt.dir.absolutePath
                    TvPill(
                        onClick = {
                            scope.launch {
                                appContainer.settingsStore.update {
                                    // Picking a volume clears any SAF folder. Store null for the
                                    // default (first) volume so it keeps following the system default.
                                    it.copy(
                                        customDownloadDir = if (index == 0) null else path,
                                        customDownloadTreeUri = null,
                                    )
                                }
                            }
                        },
                        selected = !usingTree && path == currentPath,
                    ) {
                        Text(opt.label, fontSize = 13.sp)
                    }
                }
                // Pick any folder via the in-app browser (incl. USB/SD) — no SAF needed.
                TvPill(
                    onClick = { openDownloadPicker() },
                    selected = usingTree || usingCustomFolder,
                ) {
                    Text("选择其他文件夹…", fontSize = 13.sp)
                }
            }
            if (dlPermHint) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "需要存储写入权限：请在系统设置里为「随便听」开启文件访问（或允许存储权限），返回后再点选择。",
                    color = AppColors.Warning,
                    fontSize = 11.sp,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    usingTree -> "已选文件夹：" + (
                        treeUriStr?.let { runCatching { appContainer.downloadStore.treeDisplayName(Uri.parse(it)) }.getOrNull() }
                            ?: "自定义文件夹"
                        )
                    usingCustomFolder -> "已选文件夹：" + (settings.customDownloadDir ?: "")
                    else -> currentPath ?: "（应用默认音乐目录）"
                },
                color = AppColors.TextMuted,
                fontSize = 11.sp,
            )
            Text(
                if (usingTree || usingCustomFolder) {
                    "下载会保存到你选的文件夹（可被文件管理器/其它应用浏览）。切换目录只影响之后的下载。"
                } else {
                    "存储卷目录在应用专属空间（卸载会清除、不易在文件管理器看到）；想存到可见位置请点「选择其他文件夹」。切换只影响之后的下载，已下载的仍可正常播放。"
                },
                color = AppColors.TextMuted,
                fontSize = 11.sp,
            )
        }

        Section("下载并发数") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (1..6).forEach { n ->
                    TvPill(
                        onClick = { scope.launch { appContainer.settingsStore.update { it.copy(maxConcurrentDownloads = n) } } },
                        selected = settings.maxConcurrentDownloads == n,
                    ) {
                        Text("$n", fontSize = 13.sp)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "同时下载的最大歌曲数（批量下载时排队，越大越快但越占带宽）。默认 3。",
                color = AppColors.TextMuted,
                fontSize = 11.sp,
            )
        }

        Section("批量下载") {
            ToggleRow("已下载的也按新音质重下", settings.redownloadOnQualityChange) {
                scope.launch { appContainer.settingsStore.update { it.copy(redownloadOnQualityChange = !it.redownloadOnQualityChange) } }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "开启后，对歌单「下载全部」时，已下载但音质与所选不同的歌会按新音质重新下载（如 128k 升级到 FLAC）；关闭则一律跳过已下载。默认开启。",
                color = AppColors.TextMuted,
                fontSize = 11.sp,
            )
        }

        Section("自定义音源") {
            Text(
                "粘贴 URL 自动远程拉取；直接粘贴脚本则按 JS 代码导入；或上传 .js 文件；扫码可在手机/电脑操作。",
                color = AppColors.TextMuted,
                fontSize = 12.sp,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                // D-pad focus only draws the ring; the IME comes up on an explicit OK press.
                TvFocusGatedField(
                    modifier = Modifier.weight(1f),
                    imeAction = ImeAction.Done,
                    onImeDone = { importInput(scope, url) { status = it } },
                ) { slot ->
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        singleLine = true,
                        modifier = slot.modifier.fillMaxWidth(),
                        placeholder = { Text("URL（http://...）或直接粘贴 JS 代码", color = AppColors.TextMuted) },
                        keyboardOptions = slot.keyboardOptions,
                        keyboardActions = slot.keyboardActions,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = AppColors.TextPrimary,
                            unfocusedTextColor = AppColors.TextPrimary,
                            focusedBorderColor = AppColors.AccentGreen,
                            unfocusedBorderColor = AppColors.Card,
                            cursorColor = AppColors.AccentGreen,
                        ),
                    )
                }
                Spacer(Modifier.width(8.dp))
                TvPill(onClick = { importInput(scope, url) { status = it } }, selected = true) { Text("导入", fontSize = 14.sp) }
                Spacer(Modifier.width(8.dp))
                TvPill(
                    onClick = {
                        runCatching {
                            filePicker.launch(arrayOf("application/javascript", "text/javascript", "application/x-javascript", "text/plain", "*/*"))
                        }.onFailure { status = "未找到可用的文件选择器" }
                    },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.UploadFile, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("上传文件", fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.width(8.dp))
                TvPill(
                    onClick = { showQr = true },
                    shape = CircleShape,
                    contentPadding = PaddingValues(10.dp),
                ) {
                    Icon(Icons.Filled.QrCode2, contentDescription = "扫码导入", modifier = Modifier.size(22.dp))
                }
            }
            status?.let { Text(it, color = AppColors.TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)) }

            Spacer(Modifier.padding(top = 6.dp))
            if (scripts.isEmpty()) {
                Text("尚未导入任何脚本", color = AppColors.TextMuted, fontSize = 13.sp)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    scripts.forEach { s ->
                        // Two side-by-side focusables (toggle area + delete pill). The delete
                        // path used to live on the UI's rememberCoroutineScope, which gets
                        // cancelled when the user navigates away mid-click; route through
                        // appContainer.appScope so the unload + filter + persist sequence
                        // can't be interrupted between steps.
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TvFocusable(
                                onClick = { scope.launch { appContainer.scriptStore.setEnabled(s.id, !s.enabled) } },
                                modifier = Modifier.weight(1f),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(s.name, color = AppColors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                        Text("v${s.version}  ${s.author}", color = AppColors.TextMuted, fontSize = 12.sp)
                                    }
                                    Text(
                                        if (s.enabled) "已启用" else "已停用",
                                        color = if (s.enabled) AppColors.AccentGreen else AppColors.TextMuted,
                                        fontSize = 12.sp,
                                    )
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            TvPill(
                                onClick = { pendingDelete = s },
                                accent = AppColors.Danger,
                                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                            ) { Text("删除", fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            }
        }

        SyncSection()

        DiagnosticsSection()

        UpdateSection()
    }

    pendingDelete?.let { target ->
        DeleteScriptConfirmDialog(
            scriptName = target.name,
            onCancel = { pendingDelete = null },
            onConfirm = {
                // Use the appScope (process-lived) so the unload + filter + persist sequence
                // can't be cancelled by leaving Settings mid-delete.
                pendingDelete = null
                appContainer.appScope.launch {
                    appContainer.scriptStore.remove(target.id)
                    status = "已删除：${target.name}"
                }
            },
        )
    }

    if (showQr) {
        val ip = remember { getLanIp() }
        val port = appContainer.localServer?.boundPort
        if (ip != null && port != null) {
            QrDialog(
                url = "http://$ip:$port/script",
                title = "扫码导入音源",
                subtitle = "手机扫码、电脑打开地址都可以，可粘贴脚本 URL 或上传 .js 文件",
                onDismiss = { showQr = false },
            )
        } else {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showQr = false },
                title = { Text("无法启动扫码") },
                text = { Text("请确认电视已连接 Wi-Fi 且本地服务已启动后再试。") },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = { showQr = false }) {
                        Text("好", color = AppColors.AccentGreen)
                    }
                },
                containerColor = AppColors.BgPanel,
                titleContentColor = AppColors.TextPrimary,
                textContentColor = AppColors.TextSecondary,
            )
        }
    }

    if (showDownloadBrowser) {
        FolderBrowser(
            onPick = { dir ->
                dlPermHint = false
                showDownloadBrowser = false
                scope.launch {
                    appContainer.settingsStore.update {
                        it.copy(customDownloadDir = dir.absolutePath, customDownloadTreeUri = null)
                    }
                }
            },
            onCancel = { showDownloadBrowser = false },
        )
    }
}

/** Whether we can write to arbitrary folders with java.io.File right now. */
private fun hasStorageWriteAccess(ctx: android.content.Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(
            ctx,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

/**
 * Auto-detects the input: if it looks like an http(s) URL, fetches the URL and imports the
 * body; otherwise treats the input as raw JS code and imports it directly. Used by both the
 * "导入" button and the IME "完成" action.
 */
private fun importInput(scope: kotlinx.coroutines.CoroutineScope, input: String, onStatus: (String) -> Unit) {
    if (input.isBlank()) return
    val trimmed = input.trim()
    val isUrl = trimmed.startsWith("http://", ignoreCase = true) ||
        trimmed.startsWith("https://", ignoreCase = true)
    scope.launch {
        onStatus(if (isUrl) "正在下载脚本…" else "正在导入脚本…")
        val result = runCatching {
            val raw = if (isUrl) appContainer.fetchText(trimmed) else trimmed
            appContainer.scriptStore.import(raw).getOrThrow()
        }
        onStatus(result.fold({ "已导入：${it.name}" }, { "导入失败：${it.message}" }))
    }
}

// Kept for backwards-compat callers that already exist; just delegates.
private fun importFromUrl(scope: kotlinx.coroutines.CoroutineScope, url: String, onStatus: (String) -> Unit) {
    if (url.isBlank()) return
    scope.launch {
        onStatus("正在导入…")
        val result = runCatching {
            val raw = appContainer.fetchText(url.trim())
            appContainer.scriptStore.import(raw).getOrThrow()
        }
        onStatus(result.fold({ "已导入：${it.name}" }, { "导入失败：${it.message}" }))
    }
}

/**
 * 检查更新: auto-checks GitHub once on open, then drives the whole download → install flow
 * inline. State lives in [com.walkman.tv.playback.update.UpdateManager] so leaving and
 * re-entering Settings resumes wherever it was (e.g. keeps a "有新版本" result).
 */
@Composable
private fun UpdateSection() {
    val ctx = LocalContext.current
    val mgr = appContainer.updateManager
    val state by mgr.state.collectAsState()
    var permHint by remember { mutableStateOf(false) }

    // Check + download run on the process-lived appScope (not a composable scope) so leaving
    // Settings mid-flight doesn't cancel them — the StateFlow keeps progressing and the UI
    // re-attaches to wherever it got to when the user comes back.
    // Auto-check once — only from a clean Idle so re-entering Settings doesn't re-hit GitHub.
    LaunchedEffect(Unit) {
        if (mgr.state.value is UpdateState.Idle) appContainer.appScope.launch { mgr.check() }
    }
    // Note: auto-launching the installer on download-complete is handled globally in RootScreen.

    Section("检查更新") {
        Text("当前版本 v${mgr.currentVersion}", color = AppColors.TextSecondary, fontSize = 13.sp)

        // Status line — plain text, no focusable, so it can freely swap per state.
        when (val s = state) {
            is UpdateState.Checking ->
                Text("正在检查更新…", color = AppColors.TextMuted, fontSize = 13.sp)
            is UpdateState.UpToDate ->
                Text("已是最新版本", color = AppColors.AccentGreen, fontSize = 13.sp)
            is UpdateState.Failed ->
                Text("检查失败：${s.message}", color = AppColors.Danger, fontSize = 12.sp)
            is UpdateState.Available -> {
                Text("发现新版本 v${s.release.versionName}", color = AppColors.AccentGreen, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                if (s.release.notes.isNotBlank()) {
                    Text(s.release.notes, color = AppColors.TextMuted, fontSize = 12.sp, maxLines = 6, overflow = TextOverflow.Ellipsis)
                }
            }
            is UpdateState.Downloaded -> {
                Text("下载完成", color = AppColors.AccentGreen, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                if (permHint) {
                    Text(
                        "请在系统弹出的设置里允许「随便听」安装未知应用，返回后再点「立即安装」。",
                        color = AppColors.Warning, fontSize = 12.sp,
                    )
                }
            }
            else -> {} // Idle / Downloading — the button label carries the state
        }

        // ONE stable button: its label + action follow the state, but the composable node never
        // leaves the tree — so remote focus stays put across 检查→检查中→有更新 transitions
        // instead of drifting to some other control on the page.
        val actionLabel = when (val s = state) {
            is UpdateState.Checking -> "检查中…"
            is UpdateState.UpToDate -> "重新检查"
            is UpdateState.Failed -> "重试"
            is UpdateState.Available -> "下载并安装"
            is UpdateState.Downloading -> "正在下载 ${(s.progress * 100).toInt()}%…"
            is UpdateState.Downloaded -> "立即安装"
            is UpdateState.Idle -> "检查更新"
        }
        TvPill(
            onClick = {
                when (val s = state) {
                    is UpdateState.Idle, is UpdateState.UpToDate, is UpdateState.Failed ->
                        appContainer.appScope.launch { mgr.check() }
                    is UpdateState.Available ->
                        appContainer.appScope.launch { mgr.download(s.release) }
                    is UpdateState.Downloaded ->
                        permHint = !mgr.install(ctx, s.file)
                    else -> {} // Checking / Downloading — busy, no-op
                }
            },
            selected = true,
        ) { Text(actionLabel, fontSize = 14.sp) }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = AppColors.AccentGreen, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
private fun ToggleRow(label: String, on: Boolean, onToggle: () -> Unit) {
    TvFocusable(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = AppColors.TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(if (on) "开" else "关", color = if (on) AppColors.AccentGreen else AppColors.TextMuted, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** Per-tier Material icon — small visual cue alongside the displayName in the picker.
 *  Matches iOS's per-tier symbol convention (spec §4) using Material's closest analogues. */
private fun qualityIcon(q: Quality): androidx.compose.ui.graphics.vector.ImageVector = when (q) {
    Quality.K128 -> Icons.Filled.MusicNote
    Quality.K320 -> Icons.Filled.GraphicEq
    Quality.FLAC -> Icons.Filled.HighQuality
    Quality.FLAC24, Quality.HIRES -> Icons.Filled.AutoAwesome
    Quality.ATMOS, Quality.ATMOS_PLUS -> Icons.Filled.SurroundSound
    Quality.MASTER -> Icons.Filled.Diamond
}

@Composable
private fun DeleteScriptConfirmDialog(
    scriptName: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onCancel,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .width(360.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(AppColors.BgPanel)
                .padding(horizontal = 24.dp, vertical = 22.dp),
        ) {
            Text("删除自定义音源", color = AppColors.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(
                "确定要删除「$scriptName」吗？删除后需要重新导入才能使用。",
                color = AppColors.TextSecondary,
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(22.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            ) {
                TvPill(
                    onClick = onCancel,
                    focusRequester = cancelFocus,
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp),
                ) {
                    Text("取消", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                TvPill(
                    onClick = onConfirm,
                    selected = true,
                    accent = AppColors.Danger,
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp),
                ) {
                    Text("确定", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** Which sync text field the on-screen remote keypad is currently typing into. */
private enum class SyncField { Url, Code }

/** How often the "当前设备地址" row re-resolves the active LAN IPv4. */
private const val SYNC_DEVICE_ADDRESS_REFRESH_MS = 10_000L

/**
 * LX Music Sync Server configuration + status.
 *
 * TV-first: the remote has no usable IME for long secrets, so both fields get an optional
 * [TvKeypad]. Nothing here touches the WebSocket / AES / RSA / library - every action goes
 * through [com.walkman.tv.data.sync.SyncManager].
 *
 * There is deliberately NO user-name field: the official protocol has no such concept (users
 * are configured on the server via `LX_USER_*`), and the connection code is the only secret.
 */
@Composable
private fun SyncSection() {
    val scope = rememberCoroutineScope()
    val settings by appContainer.settingsStore.settings.collectAsState()
    val state by appContainer.syncManager.state.collectAsState()
    val message by appContainer.syncManager.message.collectAsState()
    val serverInfo by appContainer.syncManager.serverInfo.collectAsState()
    val context = LocalContext.current

    // "当前设备地址" is re-resolved periodically: Wi-Fi/Ethernet can switch while Settings is
    // open (and the TV may resume from standby with a different address). The interface /
    // ConnectivityManager lookups run on IO, never on the main thread.
    var deviceAddress by remember { mutableStateOf("未知") }
    LaunchedEffect(Unit) {
        while (true) {
            val ip = withContext(Dispatchers.IO) { LocalDeviceAddress.currentLanIpv4(context) }
            deviceAddress = ip ?: "未知"
            delay(SYNC_DEVICE_ADDRESS_REFRESH_MS)
        }
    }

    // Drafts are committed explicitly (保存 / any action button) so every keystroke does not
    // rewrite settings.json.
    var urlDraft by remember { mutableStateOf(settings.syncServerUrl) }
    var codeDraft by remember { mutableStateOf(settings.syncConnectionCode) }
    var showCode by remember { mutableStateOf(false) }
    var keypad by remember { mutableStateOf<SyncField?>(null) }

    LaunchedEffect(settings.syncServerUrl, settings.syncConnectionCode) {
        urlDraft = settings.syncServerUrl
        codeDraft = settings.syncConnectionCode
    }

    suspend fun persistDrafts() = appContainer.settingsStore.update {
        it.copy(syncServerUrl = urlDraft.trim(), syncConnectionCode = codeDraft.trim())
    }

    // Long-running work runs on appScope so leaving Settings mid-flight neither cancels it nor
    // loses the result. The settings write lands first, so the request uses exactly what the
    // user sees on screen.
    fun persistThen(action: suspend () -> Unit) {
        appContainer.appScope.launch {
            persistDrafts()
            action()
        }
    }

    Section("LX Music 数据同步") {
        // Official-client style header: this device's LAN address + the live sync state.
        SyncInfoRow("当前设备地址：", deviceAddress)
        SyncInfoRow("状态：", state.displayText, syncStateColor(state))

        // Remembered answer to the server's `list_sync_get_sync_mode` question. Changeable at any
        // time; the new value applies from the next sync on and never triggers a sync by itself.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SyncInfoRow("同步方式：", syncModeLabel(settings.syncMode))
            TvPill(
                onClick = { appContainer.syncManager.requestSyncModeChange() },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) { Text("修改同步方式", fontSize = 12.sp) }
        }

        Text(
            "与 LX Music 手机版/桌面版共用同一个 LX Music Sync Server，用于同步「我喜欢的」和自建歌单。",
            color = AppColors.TextMuted,
            fontSize = 12.sp,
        )

        ToggleRow("开启同步", settings.syncEnabled) {
            scope.launch { appContainer.settingsStore.update { it.copy(syncEnabled = !it.syncEnabled) } }
        }

        Spacer(Modifier.height(4.dp))
        Text("服务地址", color = AppColors.TextSecondary, fontSize = 13.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TvFocusGatedField(
                modifier = Modifier.weight(1f),
                imeAction = ImeAction.Done,
                onImeDone = { persistThen {} },
            ) { slot ->
                OutlinedTextField(
                    value = urlDraft,
                    onValueChange = { urlDraft = it },
                    singleLine = true,
                    modifier = slot.modifier.fillMaxWidth(),
                    placeholder = { Text("https://example.com/sync", color = AppColors.TextMuted) },
                    keyboardOptions = slot.keyboardOptions,
                    keyboardActions = slot.keyboardActions,
                    colors = syncFieldColors(),
                )
            }
            Spacer(Modifier.width(8.dp))
            TvPill(
                onClick = { keypad = if (keypad == SyncField.Url) null else SyncField.Url },
                selected = keypad == SyncField.Url,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            ) { Text("键盘", fontSize = 13.sp) }
        }

        Spacer(Modifier.height(8.dp))
        Text("连接码", color = AppColors.TextSecondary, fontSize = 13.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TvFocusGatedField(
                modifier = Modifier.weight(1f),
                imeAction = ImeAction.Done,
                onImeDone = { persistThen {} },
            ) { slot ->
                OutlinedTextField(
                    value = codeDraft,
                    onValueChange = { codeDraft = it },
                    singleLine = true,
                    modifier = slot.modifier.fillMaxWidth(),
                    placeholder = { Text("LX Music Sync Server 的连接码", color = AppColors.TextMuted) },
                    visualTransformation = if (showCode) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = slot.keyboardOptions,
                    keyboardActions = slot.keyboardActions,
                    colors = syncFieldColors(),
                )
            }
            Spacer(Modifier.width(8.dp))
            TvPill(
                onClick = { showCode = !showCode },
                selected = showCode,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            ) { Text(if (showCode) "隐藏" else "显示", fontSize = 13.sp) }
            Spacer(Modifier.width(8.dp))
            TvPill(
                onClick = { keypad = if (keypad == SyncField.Code) null else SyncField.Code },
                selected = keypad == SyncField.Code,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            ) { Text("键盘", fontSize = 13.sp) }
        }

        keypad?.let { target ->
            Spacer(Modifier.height(8.dp))
            TvKeypad(
                onAppend = { c ->
                    when (target) {
                        SyncField.Url -> urlDraft += c
                        SyncField.Code -> codeDraft += c
                    }
                },
                onBackspace = {
                    when (target) {
                        SyncField.Url -> urlDraft = urlDraft.dropLast(1)
                        SyncField.Code -> codeDraft = codeDraft.dropLast(1)
                    }
                },
                onClear = {
                    when (target) {
                        SyncField.Url -> urlDraft = ""
                        SyncField.Code -> codeDraft = ""
                    }
                },
                onConfirm = {
                    keypad = null
                    persistThen {}
                },
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TvPill(
                onClick = { persistThen {} },
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
            ) { Text("保存", fontSize = 13.sp) }
            TvPill(
                onClick = { if (!state.busy) persistThen { appContainer.syncManager.testConnection() } },
                selected = true,
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
            ) { Text("测试连接", fontSize = 13.sp, fontWeight = FontWeight.Bold) }
            TvPill(
                onClick = { if (!state.busy) persistThen { appContainer.syncManager.syncNow() } },
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
            ) { Text("立即同步", fontSize = 13.sp) }
        }

        Spacer(Modifier.height(8.dp))
        ToggleRow("自动同步", settings.syncAutoSync) {
            scope.launch { appContainer.settingsStore.update { it.copy(syncAutoSync = !it.syncAutoSync) } }
        }

        serverInfo?.let { Text(it, color = AppColors.TextMuted, fontSize = 12.sp) }
        Text(
            "上次同步：${formatSyncTime(settings.syncLastSyncTime)}",
            color = AppColors.TextMuted,
            fontSize = 12.sp,
        )
        message?.let { Text(it, color = AppColors.TextSecondary, fontSize = 12.sp) }

        Spacer(Modifier.height(4.dp))
        Text(
            "说明：收藏和自建歌单会与 LX Music 同步。播放历史仅保存在本机，不参与同步。",
            color = AppColors.TextMuted,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun syncFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = AppColors.TextPrimary,
    unfocusedTextColor = AppColors.TextPrimary,
    focusedBorderColor = AppColors.AccentGreen,
    unfocusedBorderColor = AppColors.Card,
    cursorColor = AppColors.AccentGreen,
)

private fun syncStateColor(state: SyncState): Color = when (state) {
    SyncState.Connected, SyncState.Success -> AppColors.AccentGreen
    SyncState.AuthFailed, SyncState.BlockedIp, is SyncState.Error -> AppColors.Danger
    SyncState.VersionIncompatible, SyncState.ServiceUnavailable, SyncState.NeedSyncMode -> AppColors.Warning
    else -> AppColors.TextSecondary
}

private fun formatSyncTime(millis: Long?): String {
    if (millis == null || millis <= 0L) return "尚未同步"
    val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
    return fmt.format(java.util.Date(millis))
}

/** Read-only `label value` row (official-client style) used by the sync header. */
@Composable
private fun SyncInfoRow(label: String, value: String, valueColor: Color = AppColors.TextPrimary) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = AppColors.TextSecondary, fontSize = 13.sp)
        Text(value, color = valueColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun DiagnosticsSection() {
    var status by remember { mutableStateOf<RunLog.Status?>(null) }
    var showQr by remember { mutableStateOf(false) }
    var qrUrl by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf<String?>(null) }
    var pendingClear by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Live status (size / 状态 / 最近错误); RunLog.status() touches the filesystem, so keep it off
    // the main thread and refresh in the background.
    LaunchedEffect(Unit) {
        while (true) {
            status = withContext(Dispatchers.IO) { appContainer.runLog.status() }
            delay(2000)
        }
    }
    // The download server is temporary: leaving 设置 stops it and invalidates the token (spec §27).
    DisposableEffect(Unit) {
        onDispose { appContainer.stopLogServer() }
    }

    Section("故障诊断") {
        Text("运行日志：自动记录", color = AppColors.TextPrimary, fontSize = 14.sp)
        Text("保存：最近 3 天 · 上限 10 MB", color = AppColors.TextSecondary, fontSize = 13.sp)
        Text(
            "当前大小：" + formatLogSize(status?.totalBytes ?: 0L) + " / 10 MB",
            color = AppColors.TextSecondary,
            fontSize = 13.sp,
        )
        SyncInfoRow("状态：", status?.statusText ?: "正在记录")
        SyncInfoRow(
            "最近错误：",
            status?.lastProblem ?: "无",
            if (status?.lastProblem == null) AppColors.TextMuted else AppColors.Warning,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TvPill(
                onClick = {
                    scope.launch {
                        val server = withContext(Dispatchers.IO) { appContainer.startLogServer() }
                        if (server == null) {
                            notice = "未检测到局域网地址，请确保电视和手机连接到同一网络。"
                        } else {
                            qrUrl = server.pageUrl()
                            showQr = true
                            notice = null
                        }
                    }
                },
                selected = true,
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
            ) { Text("扫码下载日志", fontSize = 13.sp, fontWeight = FontWeight.Bold) }
            TvPill(
                onClick = { pendingClear = true },
                accent = AppColors.Danger,
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
            ) { Text("清除日志", fontSize = 13.sp) }
        }

        Text(
            "日志仅保存在本机；扫码后手机浏览器可下载 ZIP（logs/ + device.txt），链接 5 分钟后失效。",
            color = AppColors.TextMuted,
            fontSize = 11.sp,
        )
        notice?.let { Text(it, color = AppColors.Warning, fontSize = 12.sp) }
    }

    if (showQr) {
        QrDialog(
            url = qrUrl,
            title = "扫码下载日志",
            subtitle = "手机扫码打开页面，可下载全部日志或最近 30 分钟",
            onDismiss = {
                showQr = false
                appContainer.stopLogServer()
            },
        )
    }

    if (pendingClear) {
        ClearLogConfirmDialog(
            onCancel = { pendingClear = false },
            onConfirm = {
                pendingClear = false
                appContainer.runLog.clear()
            },
        )
    }
}

@Composable
private fun ClearLogConfirmDialog(onCancel: () -> Unit, onConfirm: () -> Unit) {
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onCancel,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .width(360.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(AppColors.BgPanel)
                .padding(horizontal = 24.dp, vertical = 22.dp),
        ) {
            Text("清除运行日志", color = AppColors.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(
                "确定清除运行日志？清除后会从现在重新开始记录。",
                color = AppColors.TextSecondary,
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(22.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            ) {
                TvPill(
                    onClick = onCancel,
                    focusRequester = cancelFocus,
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp),
                ) { Text("取消", fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
                TvPill(
                    onClick = onConfirm,
                    selected = true,
                    accent = AppColors.Danger,
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp),
                ) { Text("确定", fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

private fun formatLogSize(bytes: Long): String = when {
    bytes < 1024 -> bytes.toString() + " B"
    bytes < 1024 * 1024 -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0)
    else -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}

/** Coarse "不到 1 分钟 / x 分钟后 / x 小时后 / x 天后" label for the next songlist sweep. */
private fun songlistDueText(millis: Long?): String {
    if (millis == null || millis <= 0L) return "未计划"
    val diff = (millis - System.currentTimeMillis()).coerceAtLeast(0L)
    return when {
        diff < 60_000L -> "不到 1 分钟"
        diff < 3_600_000L -> "${diff / 60_000L} 分钟后"
        diff < 86_400_000L -> "${diff / 3_600_000L} 小时后"
        else -> "${diff / 86_400_000L} 天后"
    }
}
