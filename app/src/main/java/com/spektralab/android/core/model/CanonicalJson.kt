package com.spektralab.android.core.model

import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Canonical values for task/cache identity; not a rewrite of the legacy sidecar format. */
internal object CanonicalJson {
    fun fingerprint(encoded: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(encoded.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }

    fun encode(value: Any?): String =
        when (value) {
            null,
            JSONObject.NULL -> "null"
            is JSONObject ->
                value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") {
                    JSONObject.quote(it) + ":" + encode(value.get(it))
                }
            is JSONArray ->
                (0 until value.length()).joinToString(",", "[", "]") { encode(value.get(it)) }
            is Number -> {
                require(value.toDouble().isFinite()) { "Non-finite parameter" }
                if (value.toDouble() == 0.0) "0" else JSONObject.numberToString(value)
            }
            is Boolean -> value.toString()
            is String -> JSONObject.quote(value)
            else -> error("Unsupported parameter type: ${value.javaClass.simpleName}")
        }
}
