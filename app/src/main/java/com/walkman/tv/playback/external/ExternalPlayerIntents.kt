package com.walkman.tv.playback.external

/**
 * Builds the framework-free [ViewIntentSpec] for the hand-off.
 *
 * We use Android's standard `ACTION_VIEW` with the resolved URL as data and the inferred MIME as
 * type. The constant strings match `android.content.Intent` exactly, but are declared here so the
 * construction is assertable in a plain JVM test (the Android layer maps them onto a real Intent).
 *
 * Flags:
 *  - `FLAG_ACTIVITY_NEW_TASK` is set for launches from the application context. The chooser path
 *    starts from an Activity instead and passes `newTask = false`.
 *  - `FLAG_GRANT_READ_URI_PERMISSION` is added only for `content://` URLs (SAF-imported local
 *    tracks) so the external player may read the granted document.
 *
 * Player discovery no longer goes through Android's chooser: it probes a small set of intents with
 * `queryIntentActivities` and builds its own list. [enumerationSpecs] describes those probes; the
 * shapes are copied from ChaiChaiEmbyTV, which is verified working on a TCL T7L Ultra.
 */
object ExternalPlayerIntents {

    const val ACTION_VIEW = "android.intent.action.VIEW"
    /**
     * Vendor extension action some TV media players (e.g. MiTV's) accept for a play URL. It is
     * probed like any other action — nothing here assumes a specific OEM or package.
     */
    const val ACTION_VENDOR_SCHEME_PLAY = "tv.intent.action.external.SCHEME_PLAY"
    const val CATEGORY_DEFAULT = "android.intent.category.DEFAULT"
    const val FLAG_ACTIVITY_NEW_TASK = 0x10000000
    const val FLAG_GRANT_READ_URI_PERMISSION = 0x00000001

    fun view(url: String, mimeType: String, newTask: Boolean = true): ViewIntentSpec {
        var flags = if (newTask) FLAG_ACTIVITY_NEW_TASK else 0
        if (url.startsWith("content://", ignoreCase = true)) {
            flags = flags or FLAG_GRANT_READ_URI_PERMISSION
        }
        val type = mimeType.trim().ifBlank { AudioMime.DEFAULT }
        return ViewIntentSpec(action = ACTION_VIEW, data = url, mimeType = type, flags = flags)
    }

    /** Explicit hand-off to a remembered component (`packageName/activityName`). */
    fun explicit(url: String, mimeType: String, component: String): ViewIntentSpec =
        view(url, mimeType).copy(component = component)

    /**
     * The probe intents handed to `queryIntentActivities` when building the player list, for a
     * *real* play URL.
     *
     * 1. `ACTION_VIEW` + url + resolved MIME — a player whose filter names the media type.
     * 2. `ACTION_VIEW` + url, no MIME — a player whose filter only matches the scheme (per
     *    `IntentFilter.matchData`, a filter naming no MIME type never matches a typed intent, and
     *    vice versa: the two shapes are mutually exclusive, so both must be probed).
     * 3. vendor `SCHEME_PLAY` + url — a TV vendor extension some players answer instead.
     */
    fun enumerationSpecs(url: String, mimeType: String): List<HandoffProbeSpec> {
        val type = mimeType.trim()
        return listOf(
            HandoffProbeSpec(
                probe = HandoffProbe.ViewWithType,
                spec = ViewIntentSpec(
                    action = ACTION_VIEW,
                    data = url,
                    mimeType = type.ifBlank { AudioMime.DEFAULT },
                    flags = 0,
                    defaultCategory = true,
                ),
            ),
            HandoffProbeSpec(
                probe = HandoffProbe.ViewWithoutType,
                spec = ViewIntentSpec(
                    action = ACTION_VIEW,
                    data = url,
                    mimeType = "",
                    flags = 0,
                    defaultCategory = true,
                ),
            ),
            HandoffProbeSpec(
                probe = HandoffProbe.VendorSchemePlay,
                spec = ViewIntentSpec(
                    action = ACTION_VENDOR_SCHEME_PLAY,
                    data = url,
                    mimeType = "",
                    flags = 0,
                    defaultCategory = true,
                ),
            ),
        )
    }
}

/**
 * Runs the actual launch and classifies the outcome. Kept as a tiny seam so the "start failed"
 * branch is unit-testable without an Android runtime.
 */
object ExternalLaunchAttempt {

    /** Any failure (ActivityNotFoundException / SecurityException / ...) → [ExternalLaunchResult.START_FAILED]. */
    inline fun run(start: () -> Unit): ExternalLaunchResult =
        try {
            start()
            ExternalLaunchResult.STARTED
        } catch (t: Throwable) {
            ExternalLaunchResult.START_FAILED
        }
}
