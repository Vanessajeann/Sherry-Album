/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.vanessajeann.sherry.album.duplicate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import java.io.IOException

/** The scanner and visible thumbnails share one decode slot, including bitmap transforms. */
class SafePhotoDecoder(private val context: Context) {
    fun decode(uri: String, maxSide: Int): Bitmap? = synchronized(decodeLock) {
        ImageReadGuard.read(onFailure = { ScanDiagnostics.imageFailure(context, "decode", it) }) {
            val parsed = Uri.parse(uri)
            val bitmap = if (Build.VERSION.SDK_INT >= 28) {
                // Explicit software/8-bit target dimensions; never materialize the camera's full frame.
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, parsed)) { decoder, info, _ ->
                    val target = DecodeDimensions.fit(info.size.width, info.size.height, maxSide)
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
                    decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                    decoder.setTargetSize(target.first, target.second)
                    decoder.setOnPartialImageListener { false }
                }
            } else decodeLegacy(parsed, maxSide)
            if (bitmap != null && (bitmap.width > maxSide || bitmap.height > maxSide || bitmap.allocationByteCount > 4 * 1024 * 1024)) {
                bitmap.recycle()
                throw IOException("Decoder exceeded thumbnail memory budget")
            }
            bitmap
        }
    }

    private fun decodeLegacy(uri: Uri, maxSide: Int): Bitmap? {
        val resolver = context.contentResolver
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        options.inSampleSize = DecodeDimensions.sampleSize(options.outWidth, options.outHeight, maxSide)
        options.inJustDecodeBounds = false
        options.inPreferredConfig = Bitmap.Config.ARGB_8888
        val orientation = ImageReadGuard.read {
            resolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }
        } ?: 1
        val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        try {
            val matrix = Matrix().apply {
                when (orientation) {
                    2 -> setScale(-1f, 1f)
                    3 -> setRotate(180f)
                    4 -> setScale(1f, -1f)
                    5 -> { setRotate(90f); postScale(-1f, 1f) }
                    6 -> setRotate(90f)
                    7 -> { setRotate(-90f); postScale(-1f, 1f) }
                    8 -> setRotate(-90f)
                }
            }
            if (matrix.isIdentity) return bitmap
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated !== bitmap) bitmap.recycle()
            return rotated
        } catch (error: Throwable) {
            bitmap.recycle()
            throw error
        }
    }

    companion object { private val decodeLock = Any() }
}
