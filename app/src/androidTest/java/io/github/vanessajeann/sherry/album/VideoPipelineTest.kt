/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.vanessajeann.sherry.album

import android.Manifest
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.vanessajeann.sherry.album.duplicate.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class VideoPipelineTest {
    @Test fun realVideosAreGroupedOnlyWhenBytesMatch() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.READ_MEDIA_VIDEO)
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.READ_MEDIA_IMAGES)
        }
        val resolver = context.contentResolver
        val prefix = "SherryVideoRegression-${UUID.randomUUID()}"
        val inserted = mutableListOf<Uri>()
        fun insert(suffix: String, data: ByteArray): Uri {
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "$prefix-$suffix.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= 29) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/SherryRegression/")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            })!!
            inserted.add(uri)
            resolver.openOutputStream(uri)!!.use { it.write(data) }
            if (Build.VERSION.SDK_INT >= 29) resolver.update(uri, ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }, null, null)
            return uri
        }
        try {
            val original = instrumentation.context.assets.open("fixture.mp4").use { it.readBytes() }
            val changed = original.clone().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
            val first = insert("first", original)
            val copy = insert("copy", original)
            val different = insert("different", changed)
            val scanner = PhotoScanner(context)
            val stages = mutableListOf<String>()
            // Even if the photo UI previously selected similar mode, videos must use exact mode.
            val result = scanner.scan(ScanMode.SIMILAR, Sensitivity.BALANCED, MediaType.VIDEO) { stages.add(it.stage) }
            val group = result.groups.firstOrNull { it.photos.any { photo -> photo.name.startsWith(prefix) } }
            assertNotNull(group)
            assertEquals(GroupKind.EXACT, group!!.kind)
            assertEquals(setOf(first.toString(), copy.toString()), group.photos.map { it.uri }.toSet())
            assertFalse(group.photos.any { it.uri == different.toString() })
            assertTrue(group.photos.all { it.isVideo && it.mime == "video/mp4" })
            assertFalse(stages.any { it.contains("画面") })
            assertTrue(scanner.query(MediaType.PHOTO).none { it.uri in inserted.map(Uri::toString) })
            val selected = SelectionPolicy.exactRecommendations(listOf(group))
            assertEquals(1, selected.size)
            assertTrue(SelectionPolicy.valid(listOf(group), selected))
            assertFalse(SelectionPolicy.valid(listOf(group), group.photos.map { it.uri }.toSet()))
            if (Build.VERSION.SDK_INT >= 29) {
                val thumbnail = resolver.loadThumbnail(first, Size(160, 160), null)
                assertTrue(thumbnail.width > 0 && thumbnail.height > 0)
                thumbnail.recycle()
            }
            // The real system trash API accepts the selected video URI on Android 11+.
            if (Build.VERSION.SDK_INT >= 30) assertNotNull(MediaStore.createTrashRequest(resolver, listOf(copy), true))
        } finally {
            inserted.forEach { resolver.delete(it, null, null) }
        }
    }
}
