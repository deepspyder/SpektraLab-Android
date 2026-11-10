package com.spektralab.android.data.export

import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Explicit allowlists keep location independent of camera data and exclude maker notes/thumbnails. */
data class ExportMetadata(val attributes: Map<String, String>) {
    fun attach(file: File, width: Int, height: Int) {
        if (attributes.isEmpty()) return
        val target = ExifInterface(file)
        attributes.forEach { (tag, value) -> target.setAttribute(tag, value) }
        target.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        target.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, width.toString())
        target.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, height.toString())
        target.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, width.toString())
        target.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, height.toString())
        target.saveAttributes()
    }

    internal data class Field(val id: Int, val type: Int, val count: Int, val bytes: ByteArray)
    private fun field(id: Int, type: Int, key: String): Field? {
        val raw = attributes[key] ?: return null
        return runCatching {
            when (type) {
                2 -> (raw.toByteArray(Charsets.US_ASCII) + byteArrayOf(0)).let { Field(id, type, it.size, it) }
                1 -> Field(id, type, 1, byteArrayOf(raw.toInt().toByte()))
                3 -> Field(id, type, 1, ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(raw.toInt().toShort()).array())
                5 -> {
                    val values = if (key == "GPSTimeStamp" && ':' in raw) raw.split(':') else raw.split(',')
                    val bytes = ByteBuffer.allocate(values.size * 8).order(ByteOrder.LITTLE_ENDIAN)
                    for (value in values) {
                        val parts = value.trim().split('/')
                        val numerator: Long; val denominator: Long
                        if (parts.size == 2) { numerator = parts[0].toLong(); denominator = parts[1].toLong() }
                        else { denominator = 1_000_000L; numerator = (value.toDouble() * denominator).toLong() }
                        require(numerator in 0..0xFFFFFFFFL && denominator in 1..0xFFFFFFFFL)
                        bytes.putInt(numerator.toInt()).putInt(denominator.toInt())
                    }
                    Field(id, type, values.size, bytes.array())
                }
                else -> null
            }
        }.getOrNull()
    }

    // TIFF writing uses native EXIF/GPS directories: ExifInterface cannot rewrite 16-bit TIFF.
    internal fun rootFields() = listOfNotNull(field(271, 2, "Make"), field(272, 2, "Model"), field(306, 2, "DateTime"))
    internal fun exifFields() = listOfNotNull(
        field(33434, 5, "ExposureTime"), field(33437, 5, "FNumber"),
        field(34855, 3, "PhotographicSensitivity"), field(36867, 2, "DateTimeOriginal"),
        field(36868, 2, "DateTimeDigitized"), field(37385, 3, "Flash"),
        field(37386, 5, "FocalLength"), field(37521, 2, "SubSecTimeOriginal"),
        field(41987, 3, "WhiteBalance"), field(41989, 3, "FocalLengthIn35mmFilm"),
        field(42035, 2, "LensMake"), field(42036, 2, "LensModel"),
    )
    internal fun gpsFields(): List<Field> {
        val fields = listOfNotNull(field(1, 2, "GPSLatitudeRef"), field(2, 5, "GPSLatitude"),
            field(3, 2, "GPSLongitudeRef"), field(4, 5, "GPSLongitude"),
            field(5, 1, "GPSAltitudeRef"), field(6, 5, "GPSAltitude"),
            field(7, 5, "GPSTimeStamp"), field(16, 2, "GPSImgDirectionRef"),
            field(17, 5, "GPSImgDirection"), field(29, 2, "GPSDateStamp"))
        return if (fields.isEmpty()) fields else listOf(Field(0, 1, 4, byteArrayOf(2, 3, 0, 0))) + fields
    }

    companion object {
        val EMPTY = ExportMetadata(emptyMap())
        private val captureTags = listOf("Make", "Model", "DateTime", "DateTimeOriginal", "DateTimeDigitized",
            "ExposureTime", "FNumber", "PhotographicSensitivity", "FocalLength", "FocalLengthIn35mmFilm",
            "Flash", "WhiteBalance", "SubSecTimeOriginal", "LensMake", "LensModel")
        private val locationTags = listOf("GPSLatitudeRef", "GPSLatitude", "GPSLongitudeRef", "GPSLongitude",
            "GPSAltitudeRef", "GPSAltitude", "GPSTimeStamp", "GPSDateStamp", "GPSImgDirectionRef", "GPSImgDirection")
        fun from(source: ExifInterface?, capture: Boolean, location: Boolean): ExportMetadata {
            if (source == null) return EMPTY
            val tags = (if (capture) captureTags else emptyList()) + (if (location) locationTags else emptyList())
            return ExportMetadata(tags.mapNotNull { tag -> source.getAttribute(tag)?.let { tag to it } }.toMap())
        }
    }
}
