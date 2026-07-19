package com.hik.otest

import org.json.JSONObject

/**
 * Minimal manifest.json reader — the SDK only needs the OTA lock flags.
 * Unknown fields are ignored by construction (manifest v2 contract: adding
 * fields is non-breaking), and a malformed manifest never crashes the SDK.
 */
internal object HikoManifest {

    /** Names of functions the panel marked `"ota": false` (frozen at a pinned release). */
    fun lockedFunctions(manifestJson: String?): Set<String> {
        if (manifestJson.isNullOrBlank()) return emptySet()
        return runCatching {
            val functions = JSONObject(manifestJson).optJSONArray("functions") ?: return emptySet()
            buildSet {
                for (i in 0 until functions.length()) {
                    val fn = functions.optJSONObject(i) ?: continue
                    // "ota" yoksa OTA açıktır; yalnız açıkça false kilit sayılır.
                    if (fn.has("ota") && !fn.optBoolean("ota", true)) add(fn.optString("name"))
                }
            }
        }.getOrDefault(emptySet())
    }
}
