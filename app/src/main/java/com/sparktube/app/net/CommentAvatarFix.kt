package com.sparktube.app.net

import org.json.JSONArray
import org.json.JSONObject

/**
 * Restores comment avatars.
 *
 * YouTube's comment payloads (`commentEntityPayload`) now often carry the
 * picture only as `author.avatarThumbnailUrl` and no longer include the
 * `avatar.image.sources` array. The bundled NewPipeExtractor (v0.26.5) only
 * reads `avatar.image.sources`, so every comment came back without an avatar
 * (upstream fixed this after 0.26.5 with the same fallback).
 *
 * The extractor is a compiled jar, so instead of patching it this rewrites the
 * `/youtubei/v1/next` response before it is parsed: wherever a comment payload
 * has no usable `avatar`, one is synthesised from `author.avatarThumbnailUrl`.
 * Any failure returns the original body untouched.
 */
internal object CommentAvatarFix {

    fun apply(url: String, body: String): String {
        if (!url.contains("/youtubei/v1/next")) return body
        if (!body.contains("commentEntityPayload") || !body.contains("avatarThumbnailUrl")) {
            return body
        }
        return try {
            val root = JSONObject(body)
            if (patch(root)) root.toString() else body
        } catch (e: Exception) {
            body
        }
    }

    /** @return true when at least one payload was changed. */
    private fun patch(node: Any?): Boolean {
        var changed = false
        when (node) {
            is JSONObject -> {
                val payload = node.optJSONObject("commentEntityPayload")
                if (payload != null && addAvatar(payload)) changed = true
                val keys = node.keys()
                while (keys.hasNext()) {
                    if (patch(node.opt(keys.next()))) changed = true
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    if (patch(node.opt(i))) changed = true
                }
            }
        }
        return changed
    }

    private fun addAvatar(payload: JSONObject): Boolean {
        val sources = payload.optJSONObject("avatar")
            ?.optJSONObject("image")
            ?.optJSONArray("sources")
        if (sources != null && sources.length() > 0) return false

        val url = payload.optJSONObject("author")
            ?.optString("avatarThumbnailUrl")
            ?.takeIf { it.isNotBlank() }
            ?: return false

        val source = JSONObject()
            .put("url", url)
            .put("width", 88)
            .put("height", 88)
        payload.put(
            "avatar",
            JSONObject().put("image", JSONObject().put("sources", JSONArray().put(source)))
        )
        return true
    }
}
