package com.walkman.tv.playback.external

import com.walkman.tv.data.model.Quality
import com.walkman.tv.data.model.Track

/**
 * A remembered "跳转播放" (hand-off) target, addressed as a full Android component
 * (`packageName/activityName`) rather than a bare package: one player app can expose several
 * `ACTION_VIEW` activities and the user picked one specific entry.
 *
 * Pure data + string parsing (no `android.content.ComponentName`) so the format/parse round-trip
 * stays assertable in a plain JVM test; the Android layer converts to/from ComponentName.
 */
data class ExternalPlayerRef(
    val packageName: String,
    val activityName: String,
    val label: String?,
) {
    /** Persisted form, same shape as `ComponentName.flattenToString()`: `pkg/activity`. */
    val componentString: String get() = "$packageName/$activityName"

    /**
     * Name for the UI: the stored label when we managed to read it, else a readable fallback
     * derived from the activity class (never the raw package name). Package visibility can hide
     * the real label because Walkman declares no `<queries>`.
     */
    val displayName: String
        get() = label?.takeIf { it.isNotBlank() } ?: activityName.substringAfterLast('.')

    companion object {
        /** Parse `packageName/activityName`; null when blank or malformed. */
        fun parse(value: String?, label: String? = null): ExternalPlayerRef? {
            val v = value?.trim().orEmpty()
            val slash = v.indexOf('/')
            if (slash <= 0 || slash == v.length - 1) return null
            val pkg = v.substring(0, slash)
            val activity = v.substring(slash + 1)
            if (pkg.isBlank() || activity.isBlank()) return null
            return ExternalPlayerRef(pkg, activity, label)
        }
    }
}

/**
 * Framework-free description of the `ACTION_VIEW` intent we hand to the OS. Keeping it plain data
 * lets the exact action / data / MIME / flags / component be asserted in a JVM test; the Android
 * layer only maps it onto a real `android.content.Intent`.
 */
data class ViewIntentSpec(
    val action: String,
    val data: String,
    val mimeType: String,
    val flags: Int,
    /** `packageName/activityName` when the intent must be addressed explicitly, else null. */
    val component: String? = null,
    /**
     * Query intents must carry [android.content.Intent.CATEGORY_DEFAULT] so that a
     * `MATCH_DEFAULT_ONLY` query returns them; launch intents do not need it.
     */
    val defaultCategory: Boolean = false,
)

/** Which probe intent produced a hit. Diagnostics only — never shown to the user. */
enum class HandoffProbe(val label: String) {
    ViewWithType("view+mime"),
    ViewWithoutType("view"),
    VendorSchemePlay("vendor-scheme-play"),
}

/** A probe intent plus the label of the probe that produced it. */
data class HandoffProbeSpec(val probe: HandoffProbe, val spec: ViewIntentSpec)

/** One `queryIntentActivities` hit, before filtering/dedup (pure data → JVM-assertable). */
data class ExternalPlayerCandidate(
    val packageName: String,
    val activityName: String,
    val label: String?,
)

/**
 * Merge rules for the multi-probe player discovery. Mirrors ChaiChaiEmbyTV, which is verified
 * working on a TCL T7L Ultra: the same player usually answers several of the probe intents, so
 * dedupe on the full component (`packageName/activityName`), drop Walkman itself and the Android
 * TV framework stub package, then sort by the name the user will see.
 */
object ExternalPlayerDiscovery {

    /** Android TV's generic intent-handling stub — never a real player. */
    const val FRAMEWORK_STUBS_PACKAGE = "com.android.tv.frameworkpackagestubs"

    fun collect(
        candidates: List<ExternalPlayerCandidate>,
        selfPackage: String,
    ): List<ExternalPlayerRef> {
        val unique = LinkedHashMap<String, ExternalPlayerRef>()
        for (candidate in candidates) {
            if (candidate.packageName.isBlank() || candidate.activityName.isBlank()) continue
            if (candidate.packageName == selfPackage) continue
            if (candidate.packageName == FRAMEWORK_STUBS_PACKAGE) continue
            val key = "${candidate.packageName}/${candidate.activityName}"
            if (unique.containsKey(key)) continue
            unique[key] = ExternalPlayerRef(
                packageName = candidate.packageName,
                activityName = candidate.activityName,
                label = candidate.label?.takeIf { it.isNotBlank() },
            )
        }
        return unique.values.sortedWith(
            compareBy({ it.displayName.lowercase() }, { it.componentString }),
        )
    }
}

/**
 * Everything Walkman can hand an external player for the currently playing track.
 *
 * Deliberately minimal: the resolve layer only ever produced a URL + quality (see
 * [com.walkman.tv.source.ResolvedTrack]), so an external player receives a URL + best-effort MIME
 * and nothing else. No headers, cookies or signed query parameters are attached — whatever auth
 * the URL already carries in its query is what the external player gets.
 */
data class ExternalPlaybackTarget(
    val track: Track,
    val url: String,
    val mimeType: String,
    val quality: Quality?,
    val codec: String?,
    val transitionId: Long,
)

/** Outcome of the actual `startActivity` attempt. */
enum class ExternalLaunchResult { STARTED, START_FAILED }
