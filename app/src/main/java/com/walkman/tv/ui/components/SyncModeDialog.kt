package com.walkman.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import com.walkman.tv.data.sync.LxSyncProtocol
import com.walkman.tv.ui.theme.AppColors

/**
 * TV-friendly replacement for the official "select sync mode" modal.
 *
 * The server only asks for a mode when *both* the server and this device already hold list data
 * and no usable snapshot exists - i.e. the very first sync. The server maps the answer through
 * its own `TRANS_MODE` table, so the strings handed to [onChoose] are the official
 * `LX.Sync.List.SyncMode` values (`overwrite*_full` is appended when 完全覆盖 is ticked), never
 * anything invented here. No raw mode name is ever shown to the user.
 */
@Composable
fun SyncModeDialog(
    onChoose: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var fullOverwrite by remember { mutableStateOf(false) }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .width(460.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(AppColors.BgPanel)
                .padding(horizontal = 22.dp, vertical = 20.dp),
        ) {
            Text("选择同步方式", color = AppColors.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "服务器和本机都有数据，且没有可用的同步快照。请选择如何处理，操作前不会删除任何数据。",
                color = AppColors.TextMuted,
                fontSize = 12.sp,
            )

            Spacer(Modifier.height(14.dp))
            Text("合并（保留双方）", color = AppColors.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            ModeButton(
                text = "合并 · 保留本机顺序",
                focusRequester = firstFocus,
                onClick = { onChoose(LxSyncProtocol.SYNC_MODE_MERGE_LOCAL_REMOTE) },
            )
            Spacer(Modifier.height(6.dp))
            ModeButton(text = "合并 · 保留服务器顺序", onClick = { onChoose(LxSyncProtocol.SYNC_MODE_MERGE_REMOTE_LOCAL) })

            Spacer(Modifier.height(14.dp))
            Text("覆盖（只用一方）", color = AppColors.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            ModeButton(
                text = "用本机覆盖服务器",
                onClick = { onChoose(withFull(LxSyncProtocol.SYNC_MODE_OVERWRITE_LOCAL_REMOTE, fullOverwrite)) },
            )
            Spacer(Modifier.height(6.dp))
            ModeButton(
                text = "用服务器覆盖本机",
                onClick = { onChoose(withFull(LxSyncProtocol.SYNC_MODE_OVERWRITE_REMOTE_LOCAL, fullOverwrite)) },
            )
            Spacer(Modifier.height(6.dp))
            TvPill(
                onClick = { fullOverwrite = !fullOverwrite },
                selected = fullOverwrite,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(
                    if (fullOverwrite) "☑ 完全覆盖：删除对端多余的歌单" else "☐ 完全覆盖：删除对端多余的歌单",
                    fontSize = 12.sp,
                )
            }

            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, androidx.compose.ui.Alignment.End),
            ) {
                TvPill(
                    onClick = onCancel,
                    accent = AppColors.Danger,
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp),
                ) {
                    Text("取消", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun ModeButton(text: String, focusRequester: FocusRequester? = null, onClick: () -> Unit) {
    TvPill(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        focusRequester = focusRequester,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 11.dp),
    ) {
        Text(text, fontSize = 13.sp)
    }
}

private fun withFull(mode: String, full: Boolean): String = if (full) "${mode}_full" else mode

/**
 * Chinese label for a stored or currently selected sync mode. Null means the user has never
 * chosen; the raw protocol name is never shown (see [SyncModeDialog]).
 */
fun syncModeLabel(mode: String?): String = when (mode) {
    null -> "未设置（下次同步时选择）"
    LxSyncProtocol.SYNC_MODE_MERGE_LOCAL_REMOTE -> "合并本地和服务器（保留本机顺序）"
    LxSyncProtocol.SYNC_MODE_MERGE_REMOTE_LOCAL -> "合并本地和服务器（保留服务器顺序）"
    LxSyncProtocol.SYNC_MODE_OVERWRITE_LOCAL_REMOTE -> "使用本地覆盖服务器"
    LxSyncProtocol.SYNC_MODE_OVERWRITE_REMOTE_LOCAL -> "使用服务器覆盖本地"
    LxSyncProtocol.SYNC_MODE_OVERWRITE_LOCAL_REMOTE_FULL -> "完全使用本地覆盖服务器"
    LxSyncProtocol.SYNC_MODE_OVERWRITE_REMOTE_LOCAL_FULL -> "完全使用服务器覆盖本地"
    else -> "未知"
}