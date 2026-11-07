/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.vanessajeann.sherry.album

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.text.format.Formatter
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import io.github.vanessajeann.sherry.album.duplicate.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

class AlbumActivity : ComponentActivity() {
    private val model: DuplicateViewModel by viewModels()
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasMediaAccess()) model.scan() else model.showMessage("尚未获得媒体访问权限。可在应用设置中授权后重新扫描。")
    }
    private val trashLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        model.completeBatch(it.resultCode == Activity.RESULT_OK)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PhotoTimeFixerTheme { AlbumScreen() } }
    }

    private fun mediaPermission(): String = if (model.mediaType == MediaType.VIDEO)
        Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_MEDIA_IMAGES

    private fun hasMediaAccess(): Boolean = if (Build.VERSION.SDK_INT >= 33) {
        ContextCompat.checkSelfPermission(this, mediaPermission()) == PackageManager.PERMISSION_GRANTED ||
            (Build.VERSION.SDK_INT >= 34 && ContextCompat.checkSelfPermission(this,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED)
    } else ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun scanWithPermission() {
        val full = if (Build.VERSION.SDK_INT >= 33)
            ContextCompat.checkSelfPermission(this, mediaPermission()) == PackageManager.PERMISSION_GRANTED
        else hasMediaAccess()
        if (full) model.scan()
        else permissionLauncher.launch(if (Build.VERSION.SDK_INT >= 34)
            arrayOf(mediaPermission(), Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            else if (Build.VERSION.SDK_INT >= 33) arrayOf(mediaPermission())
            else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))
    }

    private fun gallery(photo: Photo) {
        try { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(photo.uri), photo.mime.ifBlank { if (photo.isVideo) "video/*" else "image/*" })
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
        catch (_: Exception) { model.showMessage("无法打开文件，请确认相册应用和访问权限") }
    }
    private fun size(bytes: Long) = Formatter.formatFileSize(this, bytes)

    private fun exportDiagnostics() {
        lifecycleScope.launch {
            try {
                val report = withContext(Dispatchers.IO) { ScanDiagnostics.report(this@AlbumActivity) }
                val uri = FileProvider.getUriForFile(this@AlbumActivity, "$packageName.diagnostics", report)
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "导出去重诊断"))
            } catch (_: Exception) { model.showMessage("诊断导出失败，请重试") }
        }
    }

    @Composable
    private fun AlbumScreen() {
        val progressSnapshot = model.progress
        var confirm by rememberSaveable { mutableStateOf(false) }
        var filter by rememberSaveable { mutableStateOf("全部") }
        val active = model.plan
        LaunchedEffect(active?.batchIndex, model.busy) {
            if (active != null && !model.busy && !model.awaitingSystem && Build.VERSION.SDK_INT >= 30) {
                model.awaitingSystem = true
                try {
                    val pending = MediaStore.createTrashRequest(contentResolver,
                        active.batches[active.batchIndex].map { Uri.parse(it.uri) }, true)
                    trashLauncher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                } catch (_: Exception) { model.systemFailed("系统未能打开回收站确认窗口，照片未由本应用直接删除") }
            }
        }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 16.dp)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Sherry 相册管家", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text("照片和视频去重 · 时间修复", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(enabled = model.canChange, onClick = {
                        startActivity(Intent(this@AlbumActivity, MainActivity::class.java))
                    }) { Text("时间修复") }
                }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)) {
                    item {
                        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("选择媒体", style = MaterialTheme.typography.titleMedium)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(selected = model.mediaType == MediaType.PHOTO, enabled = model.canChange,
                                        onClick = { model.configureMedia(MediaType.PHOTO); filter = "全部" }, label = { Text("照片去重") })
                                    FilterChip(selected = model.mediaType == MediaType.VIDEO, enabled = model.canChange,
                                        onClick = { model.configureMedia(MediaType.VIDEO); filter = "全部" }, label = { Text("视频去重") })
                                }
                                if (model.mediaType == MediaType.PHOTO) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(selected = model.mode == ScanMode.EXACT, enabled = model.canChange,
                                        onClick = { model.configure(ScanMode.EXACT, model.sensitivity) }, label = { Text("完全重复") })
                                    FilterChip(selected = model.mode == ScanMode.SIMILAR, enabled = model.canChange,
                                        onClick = { model.configure(ScanMode.SIMILAR, model.sensitivity) }, label = { Text("包含相似照片") })
                                }
                                if (model.mode == ScanMode.SIMILAR) {
                                    Text("相似识别强度", style = MaterialTheme.typography.labelMedium)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Sensitivity.entries.forEach { sensitivity ->
                                            FilterChip(selected = model.sensitivity == sensitivity, enabled = model.canChange,
                                                onClick = { model.configure(model.mode, sensitivity) }, label = {
                                                    Text(when(sensitivity) { Sensitivity.STRICT -> "严格"; Sensitivity.BALANCED -> "均衡"; Sensitivity.LOOSE -> "宽松" })
                                                })
                                        }
                                    }
                                }
                                Text("本机分析，不上传照片。完全重复按文件内容核对；相似照片可能包含连拍、压缩版或不同瞬间，请逐组预览。",
                                    style = MaterialTheme.typography.bodySmall)
                                } else {
                                    Text("本机按完整文件内容查找完全相同的视频；压缩或重新编码后的视频不会匹配。请预览确认后再清理。",
                                        style = MaterialTheme.typography.bodySmall)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { scanWithPermission() }, enabled = model.canChange) {
                                        Text(if (model.scanned) "重新扫描" else "开始扫描")
                                    }
                                    if (model.busy && model.plan == null) TextButton(onClick = { model.cancelScan() }) { Text("停止扫描") }
                                }
                            }
                        }
                    }
                    item {
                        val partial = Build.VERSION.SDK_INT >= 34 && hasMediaAccess() &&
                            ContextCompat.checkSelfPermission(this@AlbumActivity, mediaPermission()) != PackageManager.PERMISSION_GRANTED
                        if (partial) {
                            Text("当前仅能扫描你授权的部分媒体。可在应用设置中允许访问全部媒体。", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:$packageName"))) }) { Text("媒体权限设置") }
                        }
                        if (model.busy) ScanProgressIndicator(progressSnapshot)
                        if (model.message.isNotBlank()) Text(model.message, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 8.dp))
                        if (!model.busy) TextButton(onClick = { exportDiagnostics() }) { Text("导出诊断") }
                    }
                    if (model.groups.isNotEmpty()) {
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                (if (model.mediaType == MediaType.VIDEO) listOf("全部", "完全重复") else listOf("全部", "完全重复", "相似照片")).forEach { option ->
                                    FilterChip(selected = filter == option, onClick = { filter = option }, label = { Text(option) })
                                }
                            }
                            Row {
                                TextButton(enabled = model.canChange, onClick = { model.recommend() }) { Text("勾选完全重复副本") }
                                TextButton(enabled = model.canChange, onClick = { model.clearSelection() }) { Text("清空勾选") }
                            }
                        }
                        model.groups.filter { filter == "全部" ||
                            (filter == "完全重复" && it.kind == GroupKind.EXACT) ||
                            (filter == "相似照片" && it.kind == GroupKind.SIMILAR) }.forEach { group ->
                            item(key = "header:${group.id}") { GroupHeader(group) }
                            itemsIndexed(group.photos, key = { _, photo -> "photo:${photo.uri}" }) { i, photo ->
                                PhotoRow(group, i, photo)
                            }
                        }
                    } else if (model.scanned) item {
                        Text(if (model.mediaType == MediaType.VIDEO) "没有发现完全相同的视频。" else "没有发现符合当前条件的重复照片。你可以尝试相似扫描或调整识别强度。", Modifier.padding(vertical = 24.dp))
                    }
                    item {
                        Text("基于 SiRan-Dev / PhotoTimeFixer，遵循 GPL-3.0-or-later。相似识别为本应用实现，未使用 Apple 或爱思的私有算法。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { startActivity(Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/SiRan-Dev/PhotoTimeFixer"))) }) { Text("原项目与开源声明") }
                    }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("已选 ${model.selected.size} 个 · ${size(model.selectedBytes)}", style = MaterialTheme.typography.titleSmall)
                        Text("每组至少保留一个", style = MaterialTheme.typography.bodySmall)
                    }
                    Button(enabled = model.canChange && model.selected.isNotEmpty(), onClick = {
                        if (Build.VERSION.SDK_INT < 30) model.showMessage("Android 11 以下请点开文件，在系统相册中手动清理；本应用不直接永久删除文件")
                        else confirm = true
                    }) { Text(if (model.plan != null) "等待系统确认" else "移入回收站") }
                }
            }
        }
        if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("清理所选文件？") },
            text = { Text("将 ${model.selected.size} 个文件（${size(model.selectedBytes)}）交给系统移入回收站。\n\n每组至少保留一个。相似照片请先核对细节、动态内容与拍摄瞬间。系统会再次确认；超过 200 个时分批处理。回收站保留期限由系统相册决定，期间未必立即释放空间。") },
            confirmButton = { TextButton(onClick = { confirm = false; model.prepareTrash() }) { Text("核对并继续") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("返回检查") } })
    }

    @Composable
    private fun GroupHeader(group: DuplicateGroup) {
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${if (group.kind == GroupKind.EXACT) "完全重复" else "相似照片 · 请预览"} · ${group.photos.size} ${if (group.photos.first().isVideo) "段" else "张"}",
                    style = MaterialTheme.typography.titleMedium)
                Text(if (group.photos.first().isVideo) "推荐保留第一个文件；请点开预览并核对。" else "推荐保留第一张：优先分辨率、文件大小，再比较拍摄日期。", style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    @Composable
    private fun PhotoRow(group: DuplicateGroup, i: Int, photo: Photo) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Box(Modifier.padding(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        PhotoThumbnail(photo)
                        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                            Text(photo.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                            Text("${photo.width} × ${photo.height} · ${size(photo.bytes)}" + if (photo.isVideo && photo.durationMillis > 0) " · ${photo.durationMillis / 1000}秒" else "", style = MaterialTheme.typography.bodySmall)
                            Text(photo.folder, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(if (photo.uri in model.selected) "待清理" else if (i == 0) "推荐保留" else "保留",
                                    color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                                TextButton(enabled = model.canChange, onClick = { model.retain(group, photo.uri) }) { Text(if (photo.isVideo) "只保留这段" else "只保留这张") }
                            }
                        }
                        Checkbox(checked = photo.uri in model.selected, enabled = model.canChange,
                            onCheckedChange = { model.toggle(photo.uri) })
                    }
            }
        }
    }

    @Composable
    private fun PhotoThumbnail(photo: Photo) {
        val bitmap by produceState<Bitmap?>(null, photo.uri, photo.modified) {
            value = withContext(Dispatchers.IO) {
                if (photo.isVideo) ImageReadGuard.read {
                    if (Build.VERSION.SDK_INT >= 29) contentResolver.loadThumbnail(Uri.parse(photo.uri), Size(160, 160), null)
                    else @Suppress("DEPRECATION") MediaStore.Video.Thumbnails.getThumbnail(contentResolver,
                        ContentUris.parseId(Uri.parse(photo.uri)), MediaStore.Video.Thumbnails.MINI_KIND, null)
                } else PhotoScanner(this@AlbumActivity).decode(photo.uri, 160)
            }
        }
        Box(Modifier.size(76.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { gallery(photo) }, contentAlignment = Alignment.Center) {
            val image = bitmap
            if (image == null) Text("预览") else Image(image.asImageBitmap(), contentDescription = "预览 ${photo.name}",
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            if (photo.isVideo) Text("▶", color = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f)).padding(4.dp))
        }
    }
}
