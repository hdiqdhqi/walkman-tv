package com.walkman.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import com.walkman.tv.data.model.Track
import com.walkman.tv.ui.theme.AppColors

/**
 * Long-press action sheet for a downloaded song. One destructive action for now (删除下载); kept
 * as a small menu rather than a bare confirm so more per-track actions can slot in later without
 * changing the long-press contract.
 */
@Composable
fun DownloadTrackActionDialog(
    track: Track,
    onDeleteDownload: () -> Unit,
    onDismiss: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(track.id) { runCatching { firstFocus.requestFocus() } }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .width(420.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(AppColors.BgPanel)
                .padding(horizontal = 22.dp, vertical = 20.dp),
        ) {
            Text("歌曲操作", color = AppColors.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.size(4.dp))
            Text(
                track.name,
                color = AppColors.TextSecondary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.size(14.dp))
            TvPill(
                onClick = onDeleteDownload,
                modifier = Modifier.fillMaxWidth().focusRequester(firstFocus),
                accent = AppColors.Danger,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text("删除下载", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.size(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TvPill(onClick = onDismiss, contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp)) {
                    Text("取消", fontSize = 13.sp)
                }
            }
        }
    }
}

/** Second confirmation before a download is deleted — shows the song name to avoid mistakes. */
@Composable
fun DeleteDownloadConfirmDialog(
    track: Track,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cancelFocus = remember { FocusRequester() }
    // Focus 取消 (the safe choice) by default so a stray OK press never deletes.
    LaunchedEffect(track.id) { runCatching { cancelFocus.requestFocus() } }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .width(440.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(AppColors.BgPanel)
                .padding(horizontal = 22.dp, vertical = 20.dp),
        ) {
            Text("删除下载", color = AppColors.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.size(10.dp))
            Text("确定删除这首已下载的歌曲吗？", color = AppColors.TextSecondary, fontSize = 14.sp)
            Spacer(Modifier.size(8.dp))
            Text(
                track.name,
                color = AppColors.TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.size(4.dp))
            Text(
                "仅删除本机已下载文件，不会影响在线播放、歌单和收藏。",
                color = AppColors.TextMuted,
                fontSize = 11.sp,
            )
            Spacer(Modifier.size(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TvPill(
                    onClick = onDismiss,
                    modifier = Modifier.focusRequester(cancelFocus),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                ) {
                    Text("取消", fontSize = 13.sp)
                }
                TvPill(
                    onClick = onConfirm,
                    accent = AppColors.Danger,
                    contentPadding = PaddingValues(horizontal = 22.dp, vertical = 8.dp),
                ) {
                    Text("删除", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
