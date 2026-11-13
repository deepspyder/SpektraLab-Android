package com.spektralab.android.core.model

import java.text.Normalizer
import java.util.Locale

internal enum class PhotoLabel(val id: String) {
    NONE("none"), RED("red"), YELLOW("yellow"), GREEN("green"), BLUE("blue"), PURPLE("purple");

    companion object {
        fun fromId(id: String): PhotoLabel = entries.firstOrNull { it.id == id } ?: error("UNSUPPORTED_PHOTO_LABEL")
    }
}

internal data class PhotoOrganization(
    val rating: Int = 0,
    val label: PhotoLabel = PhotoLabel.NONE,
    val albums: Set<String> = emptySet(),
    val revision: Long = 0,
) {
    init { require(rating in 0..5 && revision >= 0) }
}

internal data class PhotoAlbum(val id: String, val name: String, val count: Long)

internal data class PhotoLibraryFilter(
    val albumId: String? = null,
    val minimumRating: Int = 0,
    val label: PhotoLabel? = null,
    val unratedOnly: Boolean = false,
) {
    init { require(minimumRating in 0..5 && !(unratedOnly && minimumRating > 0)) }
    val active: Boolean get() = albumId != null || minimumRating != 0 || label != null || unratedOnly
}

internal object AlbumNames {
    fun normalize(name: String): String = Normalizer.normalize(name.trim(), Normalizer.Form.NFC).also {
        require(it.isNotBlank() && it.codePointCount(0, it.length) <= 40 && it.none(Char::isISOControl)) { "INVALID_ALBUM_NAME" }
    }
    fun key(name: String): String = normalize(name).lowercase(Locale.ROOT)
}
