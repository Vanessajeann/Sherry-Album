/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.vanessajeann.sherry.album.duplicate

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.security.MessageDigest

data class ScanProgress(val stage: String, val done: Int = 0, val total: Int = 0)
data class ScanResult(val groups: List<DuplicateGroup>, val total: Int, val skipped: Int)

class PhotoScanner(private val context: Context) {
    private val resolver get() = context.contentResolver
    fun query(): List<Photo> {
        val collection = if (Build.VERSION.SDK_INT >= 29) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val fields = mutableListOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE, MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.DATE_MODIFIED, MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.MIME_TYPE, MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
        if (Build.VERSION.SDK_INT >= 29) fields.add(MediaStore.Images.Media.RELATIVE_PATH)
        if (Build.VERSION.SDK_INT >= 30) fields.add(MediaStore.Images.Media.GENERATION_MODIFIED)
        val selection = buildList {
            add("${MediaStore.Images.Media.SIZE} > 0")
            if (Build.VERSION.SDK_INT >= 29) add("${MediaStore.Images.Media.IS_PENDING} = 0")
            if (Build.VERSION.SDK_INT >= 30) add("${MediaStore.Images.Media.IS_TRASHED} = 0")
        }.joinToString(" AND ")
        return resolver.query(collection, fields.toTypedArray(), selection, null, "${MediaStore.Images.Media._ID} ASC")?.use { c ->
            val columns = fields.associateWith { c.getColumnIndexOrThrow(it) }
            fun str(field: String) = c.getString(columns.getValue(field)) ?: ""
            fun num(field: String) = c.getLong(columns.getValue(field))
            buildList {
                while (c.moveToNext()) {
                    add(Photo(ContentUris.withAppendedId(collection, num(MediaStore.Images.Media._ID)).toString(),
                        str(MediaStore.Images.Media.DISPLAY_NAME), num(MediaStore.Images.Media.SIZE),
                        num(MediaStore.Images.Media.WIDTH).toInt(), num(MediaStore.Images.Media.HEIGHT).toInt(),
                        num(MediaStore.Images.Media.DATE_MODIFIED), num(MediaStore.Images.Media.DATE_TAKEN),
                        str(MediaStore.Images.Media.MIME_TYPE),
                        if (Build.VERSION.SDK_INT >= 29) str(MediaStore.Images.Media.RELATIVE_PATH) else str(MediaStore.Images.Media.BUCKET_DISPLAY_NAME),
                        if (Build.VERSION.SDK_INT >= 30) num(MediaStore.Images.Media.GENERATION_MODIFIED) else 0))
                }
            }
        } ?: throw IOException("无法读取系统相册，请检查照片权限")
    }

    suspend fun scan(mode: ScanMode, sensitivity: Sensitivity, onProgress: suspend (ScanProgress) -> Unit): ScanResult {
        val cancellation = currentCoroutineContext()
        onProgress(ScanProgress("读取相册"))
        val photos = query()
        val repeatedSizes = photos.groupingBy { it.bytes }.eachCount().filterValues { it > 1 }.keys
        val analyzed = mutableListOf<AnalyzedPhoto>()
        val skipped = mutableSetOf<String>()
        photos.forEachIndexed { i, photo ->
            cancellation.ensureActive()
            if (i % 10 == 0) onProgress(ScanProgress("核对文件内容", i, photos.size))
            var digest: String? = null
            if (photo.bytes in repeatedSizes) {
                try { digest = sha256(photo) } catch (_: IOException) { skipped.add(photo.uri) }
                catch (_: SecurityException) { skipped.add(photo.uri) }
            }
            analyzed.add(AnalyzedPhoto(photo, digest, null))
        }
        val withVisual = if (mode == ScanMode.SIMILAR) {
            // Decode only one representative of each byte-identical atom.
            val representatives = analyzed.groupBy { it.sha256 ?: it.photo.uri }.values
            val updated = mutableListOf<AnalyzedPhoto>()
            representatives.forEachIndexed { i, atom ->
                cancellation.ensureActive()
                if (i % 5 == 0) onProgress(ScanProgress("分析照片画面", i, representatives.size))
                val sorted = atom.sortedWith(DuplicateCore.qualityOrder)
                val best = sorted.first()
                val bitmap = if (best.photo.mime in STILL_FORMATS) decode(best.photo.uri, 256) else null
                if (bitmap == null) {
                    // Animated/unsupported images still participate in byte-identical grouping.
                    if (best.photo.mime in STILL_FORMATS) skipped.add(best.photo.uri)
                    updated.addAll(atom)
                } else {
                    try {
                        val square = Bitmap.createScaledBitmap(bitmap, 32, 32, true)
                        val opaque = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
                        Canvas(opaque).apply { drawColor(Color.WHITE); drawBitmap(square, 0f, 0f, null) }
                        val pixels = IntArray(1024)
                        opaque.getPixels(pixels, 0, 32, 0, 0, 32, 32)
                        val fingerprint = DuplicateCore.fingerprint(pixels)
                        opaque.recycle()
                        if (square !== bitmap) square.recycle()
                        updated.addAll(sorted.map { entry -> entry.copy(photo = entry.photo.copy(
                            width = bitmap.width, height = bitmap.height), fingerprint = fingerprint) })
                        // Keep source resolution for quality ordering, oriented to the decoded image.
                        val start = updated.size - sorted.size
                        sorted.forEachIndexed { offset, entry ->
                            val sourceW = entry.photo.width
                            val sourceH = entry.photo.height
                            val swap = (sourceW > sourceH) != (bitmap.width > bitmap.height)
                            updated[start + offset] = updated[start + offset].copy(photo = entry.photo.copy(
                                width = if (swap) sourceH else sourceW, height = if (swap) sourceW else sourceH))
                        }
                    } finally { bitmap.recycle() }
                }
            }
            updated
        } else analyzed
        cancellation.ensureActive()
        onProgress(ScanProgress("整理重复分组"))
        val groups = DuplicateCore.group(withVisual, mode, sensitivity) { cancellation.ensureActive() }
        return ScanResult(groups, photos.size, skipped.size)
    }

    suspend fun sha256(photo: Photo): String {
        val cancellation = currentCoroutineContext()
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        resolver.openInputStream(Uri.parse(photo.uri))?.use { stream ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                cancellation.ensureActive()
                val n = stream.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n); count += n
            }
        } ?: throw IOException("照片无法读取")
        if (count != photo.bytes) throw IOException("扫描期间文件发生变化")
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    /** Uses an uncropped full frame; honors all eight EXIF rotation/mirror states. */
    fun decode(uri: String, maxSide: Int): Bitmap? {
        try {
            val parsed = Uri.parse(uri)
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it, null, options) }
            if (options.outWidth <= 0 || options.outHeight <= 0) return null
            var sample = 1
            while (maxOf(options.outWidth, options.outHeight) / sample > maxSide * 2) sample *= 2
            options.inJustDecodeBounds = false
            options.inSampleSize = sample
            options.inPreferredConfig = Bitmap.Config.ARGB_8888
            val bitmap = resolver.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
            val orientation = try { resolver.openInputStream(parsed)?.use { ExifInterface(it).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) } ?: 1 } catch (_: IOException) { 1 }
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
            val oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (oriented !== bitmap) bitmap.recycle()
            return oriented
        } catch (_: IOException) { return null }
        catch (_: SecurityException) { return null }
        catch (_: IllegalArgumentException) { return null }
    }

    companion object {
        // GIF/WebP may contain animation. Never infer their equivalence from a first frame.
        val STILL_FORMATS = setOf("image/jpeg", "image/png", "image/heic", "image/heif", "image/avif")
    }
}
