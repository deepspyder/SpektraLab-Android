package com.spektralab.android.core.model

import org.json.JSONObject

/** Version is explicit so loading a previous edit never changes its film optics. */
enum class HalationModel(val version: Int) {
    CLASSIC(1),
    BACK_REFLECTION(2);

    companion object {
        fun requireReadable(json: JSONObject) {
            if (json.has("halation_model_version")) {
                val raw = json.get("halation_model_version")
                check(raw is Number && entries.any { it.version.toDouble() == raw.toDouble() }) { "FUTURE_EDIT_READ_ONLY" }
            }
            if (json.has("halation_headroom_ev")) {
                val raw = json.get("halation_headroom_ev")
                check(raw is Number && raw.toDouble().isFinite() && raw.toDouble() in 0.0..8.0) { "INVALID_EDIT_PAYLOAD" }
            }
        }
    }
}
