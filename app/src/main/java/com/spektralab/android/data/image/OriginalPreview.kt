package com.spektralab.android.data.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.spektralab.android.engine.RawDecodeCancellation
import com.spektralab.android.engine.RawDecoder
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/** Display intent is distinct from scene-linear sensor data consumed by the film model. */
internal object OriginalPreview {
    fun raw(context: Context, uri: Uri, cancellation: RawDecodeCancellation, maxLongEdge: Int = 2048): Bitmap? {
        require(maxLongEdge in 1..2048)
        val jpeg = RawDecoder.cameraPreview(context, uri, cancellation)
        if (jpeg != null) {
            val decoded = try {
                if (Build.VERSION.SDK_INT >= 28) {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(jpeg))) { decoder, info, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        val scale = minOf(1.0, maxLongEdge.toDouble() / maxOf(info.size.width, info.size.height))
                        decoder.setTargetSize((info.size.width * scale).roundToInt().coerceAtLeast(1),
                            (info.size.height * scale).roundToInt().coerceAtLeast(1))
                    }
                } else {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxLongEdge) sample *= 2
                    val options = BitmapFactory.Options().apply { inSampleSize = sample }
                    BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)?.let { bitmap ->
                        val exif = ExifInterface(jpeg.inputStream())
                        val matrix = Matrix().apply {
                            if (exif.isFlipped) postScale(-1f, 1f)
                            postRotate(exif.rotationDegrees.toFloat())
                        }
                        if (matrix.isIdentity) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                            .also { if (it !== bitmap) bitmap.recycle() }
                    }
                }
            } catch (_: Exception) { null }
            if (decoded != null) return decoded
        }
        return RawDecoder.displayPreview(context, uri, maxLongEdge, cancellation)
    }
}
