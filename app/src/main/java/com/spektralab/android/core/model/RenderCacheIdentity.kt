package com.spektralab.android.core.model

import org.json.JSONObject

/**
 * Conservative whole-result identity: includes even downstream edits until a separately validated
 * stage classifier can safely omit them. Source/resource fingerprints must identify content, not
 * just filenames or a URI string.
 */
internal data class RenderCacheIdentity(
    val sourceFingerprint: String,
    val decoderVersion: String,
    val engineVersion: String,
    val resourcesFingerprint: String,
    val width: Int,
    val height: Int,
    val inputColorSpace: String,
    val outputColorSpace: String,
    val correctLens: Boolean,
    val renderSeed: Long,
    val edit: EditSnapshot,
) {
    init {
        require(sourceFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(resourcesFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(width > 0 && height > 0)
        require(
            listOf(decoderVersion, engineVersion, inputColorSpace, outputColorSpace)
                .all(String::isNotBlank)
        )
    }

    val key: String by lazy {
        CanonicalJson.fingerprint(
            CanonicalJson.encode(
                JSONObject()
                    .put("formatVersion", 1)
                    .put("source", sourceFingerprint)
                    .put("decoder", decoderVersion)
                    .put("engine", engineVersion)
                    .put("resources", resourcesFingerprint)
                    .put("width", width)
                    .put("height", height)
                    .put("inputSpace", inputColorSpace)
                    .put("outputSpace", outputColorSpace)
                    .put("lens", correctLens)
                    .put("seed", renderSeed)
                    .put("edit", JSONObject(edit.json))
            )
        )
    }
}
