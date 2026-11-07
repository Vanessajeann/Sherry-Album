/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.vanessajeann.sherry.album.duplicate

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.security.MessageDigest

data class ScanResult(val groups: List<DuplicateGroup>, val total: Int, val skipped: Int)

class PhotoScanner(private val context: Context) {
    private val decoder = SafePhotoDecoder(context)
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
        ScanDiagnostics.stage(context, "query", 0, 0)
        val photos = query()
        val repeatedSizes = photos.groupingBy { it.bytes }.eachCount().filterValues { it > 1 }.keys
        val analyzed = mutableListOf<AnalyzedPhoto>()
        val skipped = mutableSetOf<String>()
        photos.forEachIndexed { i, photo ->
            cancellation.ensureActive()
            if (i % 10 == 0) onProgress(ScanProgress("核对文件内容", i, photos.size))
            if (i % 25 == 0) ScanDiagnostics.stage(context, "hash", i, photos.size, photo)
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
                ScanDiagnostics.stage(context, "visual", i, representatives.size, best.photo)
                val visual = if (best.photo.mime in STILL_FORMATS) analyzeVisual(best.photo) else null
                if (visual == null) {
                    // Animated/unsupported images still participate in byte-identical grouping.
                    if (best.photo.mime in STILL_FORMATS) skipped.add(best.photo.uri)
                    updated.addAll(atom)
                } else {
                    sorted.forEach { entry ->
                        val swap = (entry.photo.width > entry.photo.height) != visual.landscape
                        updated.add(entry.copy(photo = entry.photo.copy(
                            width = if (swap) entry.photo.height else entry.photo.width,
                            height = if (swap) entry.photo.width else entry.photo.height), fingerprint = visual.fingerprint))
                    }
                }
            }
            updated
        } else analyzed
        cancellation.ensureActive()
        onProgress(ScanProgress("整理重复分组"))
        ScanDiagnostics.stage(context, "group", withVisual.size, withVisual.size)
        val groups = DuplicateCore.group(withVisual, mode, sensitivity) { cancellation.ensureActive() }
        ScanDiagnostics.stage(context, "complete", photos.size, photos.size)
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

    fun decode(uri: String, maxSide: Int): Bitmap? = decoder.decode(uri, maxSide)

    private data class Visual(val fingerprint: Fingerprint, val landscape: Boolean)

    private fun analyzeVisual(photo: Photo): Visual? = ImageReadGuard.read(
        onFailure = { ScanDiagnostics.imageFailure(context, "fingerprint", it) }
    ) {
        val bitmap = decoder.decode(photo.uri, 192) ?: return@read null
        var square: Bitmap? = null
        var opaque: Bitmap? = null
        try {
            val scaled = Bitmap.createScaledBitmap(bitmap, 32, 32, true)
            square = scaled
            val flattened = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            opaque = flattened
            Canvas(flattened).apply { drawColor(Color.WHITE); drawBitmap(scaled, 0f, 0f, null) }
            val pixels = IntArray(1024)
            flattened.getPixels(pixels, 0, 32, 0, 0, 32, 32)
            Visual(DuplicateCore.fingerprint(pixels), bitmap.width > bitmap.height)
        } finally {
            opaque?.recycle()
            if (square !== bitmap) square?.recycle()
            bitmap.recycle()
        }
    }

    companion object {
        // GIF/WebP may contain animation. Never infer their equivalence from a first frame.
        val STILL_FORMATS = setOf("image/jpeg", "image/png", "image/heic", "image/heif", "image/avif")
    }
}
