package com.walkman.tv.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.walkman.tv.playback.external.ExternalPlayerChoice
import com.walkman.tv.playback.external.ExternalPlayerRef
import com.walkman.tv.ui.theme.AppColors

/**
 * Walkman's own player list — the replacement for Android's chooser.
 *
 * The list is produced by [com.walkman.tv.playback.external.ExternalPlayerLauncher.findPlayers]
 * (three probe intents + `queryIntentActivities`), never by the system resolver, so the TV's own
 * media player shows up even though it does not answer a plain `ACTION_VIEW` with our MIME.
 * Icons are captured at query time and passed in as a bitmap, since the picked package may not be
 * queryable here.
 */
@Composable
fun ExternalPlayerPickerDialog(
    players: List<ExternalPlayerChoice>,
    onPick: (ExternalPlayerRef) -> Unit,
    onDismiss: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .width(520.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(AppColors.BgPanel)
                .padding(horizontal = 22.dp, vertical = 20.dp),
        ) {
            Text(
                "选择外部播放器",
                color = AppColors.TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "将当前歌曲的播放地址交给选中的播放器打开；该方式会把媒体直链暴露给外部应用。",
                color = AppColors.TextMuted,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(14.dp))

            if (players.isEmpty()) {
                Text("未找到可用的外部播放器", color = AppColors.TextSecondary, fontSize = 14.sp)
            } else {
                Column(
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    players.forEachIndexed { index, choice ->
                        PlayerRow(
                            choice = choice,
                            modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                            onClick = { onPick(choice.ref) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            TvPill(onClick = onDismiss) { Text("取消", fontSize = 13.sp) }
        }
    }
}

@Composable
private fun PlayerRow(
    choice: ExternalPlayerChoice,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val icon = remember(choice.icon) {
        runCatching { choice.icon?.toBitmap(96, 96)?.asImageBitmap() }.getOrNull()
    }
    TvFocusable(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(26.dp))
            } else {
                Icon(
                    Icons.Filled.MusicNote,
                    contentDescription = null,
                    tint = AppColors.TextMuted,
                    modifier = Modifier.size(26.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                choice.ref.displayName,
                color = AppColors.TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
