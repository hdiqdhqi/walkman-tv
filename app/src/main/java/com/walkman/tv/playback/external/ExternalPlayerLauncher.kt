package com.walkman.tv.playback.external

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.Log

/**
 * A discovered player plus the icon captured while querying. The icon is read at query time on
 * purpose: once the picker closes the package may no longer be visible to us.
 */
data class ExternalPlayerChoice(
    val ref: ExternalPlayerRef,
    val icon: Drawable?,
)

/**
 * The Android-touching half of "跳转播放": it enumerates the players that can open the current
 * (real) play URL and starts the one the user picked explicitly.
 *
 * Discovery probes three intents (see [ExternalPlayerIntents.enumerationSpecs]) and merges the
 * `queryIntentActivities` hits itself — no system chooser, no `EXTRA_CHOSEN_COMPONENT`, no
 * placeholder URL. The probe shapes are copied from ChaiChaiEmbyTV, which is verified working on a
 * TCL T7L Ultra. No player package is ever hard-coded.
 */
class ExternalPlayerLauncher(private val appContext: Context) {

    /**
     * Every app on this TV that can open [url], deduped by component and sorted by name.
     *
     * NOTE: on Android 11+ this result is subject to package visibility — an app that declares no
     * `<queries>` only sees itself, a few system packages and apps that interact with it. The call
     * itself never throws; an empty list simply means "nothing visible can handle this".
     */
    fun findPlayers(url: String, mimeType: String): List<ExternalPlayerChoice> {
        val packageManager = appContext.packageManager
        val candidates = ArrayList<ExternalPlayerCandidate>()
        val icons = HashMap<String, Drawable?>()
        for (probe in ExternalPlayerIntents.enumerationSpecs(url, mimeType)) {
            val hits = runCatching {
                packageManager.queryIntentActivities(toIntent(probe.spec), PackageManager.MATCH_DEFAULT_ONLY)
            }.getOrElse { emptyList() }
            Log.d(
                TAG,
                "probe=${probe.probe.label} action=${probe.spec.action} scheme=${schemeOf(url)} " +
                    "mime=${probe.spec.mimeType.ifBlank { "(none)" }} " +
                    "host=${ExternalPlayerLog.hostOf(url)} hits=${hits.size}",
            )
            for (hit in hits) {
                val info = hit.activityInfo ?: continue
                val pkg = info.packageName ?: continue
                val name = info.name ?: continue
                val key = "$pkg/$name"
                if (icons.containsKey(key)) continue
                icons[key] = runCatching { hit.loadIcon(packageManager) }.getOrNull()
                candidates += ExternalPlayerCandidate(
                    packageName = pkg,
                    activityName = name,
                    label = runCatching { hit.loadLabel(packageManager).toString() }.getOrNull(),
                )
            }
        }
        val players = ExternalPlayerDiscovery.collect(candidates, appContext.packageName)
        Log.d(TAG, "players=${players.size} " + players.joinToString { it.componentString })
        return players.map { ExternalPlayerChoice(ref = it, icon = icons[it.componentString]) }
    }

    /**
     * Explicit start of [ref] with the real play URL. An explicitly addressed component starts
     * regardless of package visibility, so a remembered player keeps working without `<queries>`.
     * Never throws; see [ExternalLaunchResult].
     */
    fun launch(ref: ExternalPlayerRef, url: String, mimeType: String): ExternalLaunchResult =
        ExternalLaunchAttempt.run {
            val spec = ExternalPlayerIntents.explicit(url, mimeType, ref.componentString)
            appContext.startActivity(toIntent(spec))
        }

    /** Convenience wrapper for the player screen, which already holds an [ExternalPlaybackTarget]. */
    fun launchExplicit(ref: ExternalPlayerRef, target: ExternalPlaybackTarget): ExternalLaunchResult =
        launch(ref, target.url, target.mimeType)

    private fun schemeOf(url: String): String =
        runCatching { Uri.parse(url).scheme }.getOrNull() ?: "?"

    private fun toIntent(spec: ViewIntentSpec): Intent {
        val intent = Intent(spec.action)
        val uri = spec.data.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
        if (uri != null) {
            // A blank type means "no type at all" (probe 2/3); setDataAndType(uri, "") would leave
            // an empty type on the intent and change what the system matches.
            if (spec.mimeType.isBlank()) intent.data = uri else intent.setDataAndType(uri, spec.mimeType)
        } else if (spec.mimeType.isNotBlank()) {
            intent.type = spec.mimeType
        }
        if (spec.defaultCategory) intent.addCategory(Intent.CATEGORY_DEFAULT)
        spec.component?.let { ComponentName.unflattenFromString(it)?.let { cn -> intent.component = cn } }
        intent.addFlags(spec.flags)
        return intent
    }

    companion object {
        const val TAG = "WalkmanHandoff"
    }
}
