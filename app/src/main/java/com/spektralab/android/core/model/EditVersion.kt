package com.spektralab.android.core.model

import android.net.Uri
import java.util.UUID

/** A version references an asset; it never owns a second source file. */
internal data class EditVersion(
    val id: String,
    val assetId: String,
    val name: String,
    val isDefault: Boolean,
    val sourceUri: String,
    val sourceName: String,
    val paramsJson: String,
    val revision: Long,
) {
    val editUri: Uri get() = if (isDefault) Uri.parse(sourceUri) else EditVersionUri.forId(id)
}

/** Stable, private resolver identity. This is an edit reference, not a document-provider alias. */
internal object EditVersionUri {
    const val AUTHORITY = "com.spektralab.android.editversions"
    fun owns(uri: Uri): Boolean = uri.authority == AUTHORITY
    fun forId(id: String): Uri {
        require(UUID.fromString(id).toString() == id)
        return Uri.Builder().scheme("content").authority(AUTHORITY).appendPath("copies").appendPath(id).build()
    }
    fun id(uri: Uri): String {
        require(uri.scheme == "content" && owns(uri) && uri.pathSegments.size == 2 && uri.pathSegments[0] == "copies" &&
            uri.query == null && uri.fragment == null) { "INVALID_EDIT_REFERENCE" }
        val id = uri.pathSegments[1]
        require(forId(id) == uri) { "INVALID_EDIT_REFERENCE" }
        return id
    }
}
