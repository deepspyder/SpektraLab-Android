package com.spektralab.android.core.model

import org.json.JSONObject

/** An owned value: queued work never reads the editor's mutable Compose state. */
class EditSnapshot private constructor(val json: String) {
    val fingerprint: String by lazy { CanonicalJson.fingerprint(json) }

    /** Each consumer gets its own mutable adapter; the snapshot remains unchanged. */
    fun toParams(): EditorParams = EditorParams.fromJson(JSONObject(json))

    override fun equals(other: Any?): Boolean = other is EditSnapshot && json == other.json

    override fun hashCode(): Int = json.hashCode()

    companion object {
        fun capture(params: EditorParams): EditSnapshot = fromJson(params.toJson())

        internal fun fromJson(json: JSONObject): EditSnapshot =
            EditSnapshot(CanonicalJson.encode(json))
    }
}
