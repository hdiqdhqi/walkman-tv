package com.walkman.tv.source.catalog

import com.walkman.tv.data.model.OnlinePlaylistException
import com.walkman.tv.data.model.SourceID
import org.json.JSONException
import org.json.JSONObject

/**
 * Parse a platform body as JSON.
 *
 * "This is not JSON" is a *content* failure - an HTML risk-control/verification page, a truncated
 * read or an empty body - and not a schema change, so it gets its own kind and stays retryable.
 * Folding it into "we cannot parse the data" (the old behaviour) is what made a blocked request
 * look like a broken parser.
 */
internal fun parseCatalogJson(text: String, source: SourceID, stage: String): JSONObject = try {
    JSONObject(text)
} catch (e: JSONException) {
    throw OnlinePlaylistException(
        OnlinePlaylistException.Kind.CONTENT,
        "$stage: response is not JSON (${describeBody(text)})",
        e,
        source,
    )
}

/** Describe the *shape* of a body for the log - never the body itself. */
internal fun describeBody(text: String): String {
    val head = text.trimStart()
    val kind = when {
        head.isEmpty() -> "empty"
        head.startsWith("<") -> "html"
        else -> "text"
    }
    return "$kind len=${text.length}"
}

/**
 * Give a page/batch failure the platform and stage it came from, without *re-classifying* it: a
 * plain transport exception is handed back untouched so
 * [com.walkman.tv.data.online.OnlinePlaylistRepository] stays the single place that turns an
 * exception into an [OnlinePlaylistException.Kind].
 */
internal fun catalogFailure(e: Throwable, source: SourceID, stage: String): Throwable = when {
    e !is OnlinePlaylistException -> e
    e.source != null -> e
    else -> OnlinePlaylistException(e.kind, "$stage: ${e.message}", e, source)
}
