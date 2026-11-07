/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.vanessajeann.sherry.album.duplicate

import android.app.Application
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class TrashPlan(val batches: List<List<Photo>>, val completed: Int = 0, val batchIndex: Int = 0)

class DuplicateViewModel(application: Application) : AndroidViewModel(application) {
    private val scanner = PhotoScanner(application)
    var groups by mutableStateOf<List<DuplicateGroup>>(emptyList()); private set
    var selected by mutableStateOf<Set<String>>(emptySet()); private set
    var mode by mutableStateOf(ScanMode.SIMILAR); private set
    var sensitivity by mutableStateOf(Sensitivity.BALANCED); private set
    var progress by mutableStateOf(ScanProgress("选择扫描方式，开始整理相册")); private set
    var busy by mutableStateOf(false); private set
    var scanned by mutableStateOf(false); private set
    var message by mutableStateOf(""); private set
    var plan by mutableStateOf<TrashPlan?>(null); private set
    var awaitingSystem = false
    private var scanJob: Job? = null
    private var validationJob: Job? = null
    val canChange: Boolean get() = !busy && plan == null
    val selectedBytes: Long get() = groups.flatMap { it.photos }.filter { it.uri in selected }.sumOf { it.bytes }

    fun configure(newMode: ScanMode, newSensitivity: Sensitivity) {
        if (!canChange) return
        if (mode != newMode || sensitivity != newSensitivity) {
            mode = newMode; sensitivity = newSensitivity
            groups = emptyList(); selected = emptySet(); scanned = false
            message = "扫描方式已改变，请重新扫描"
        }
    }

    fun scan() {
        if (!canChange) return
        busy = true; scanned = false; selected = emptySet(); groups = emptyList(); message = ""
        scanJob = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    scanner.scan(mode, sensitivity) { p -> withContext(Dispatchers.Main) { progress = p } }
                }
                groups = result.groups
                scanned = true
                message = "已扫描 ${result.total} 张照片，发现 ${groups.size} 组。无法读取或不支持画面分析：${result.skipped} 张。"
                progress = ScanProgress(if (groups.isEmpty()) "没有发现重复照片" else "扫描完成")
            } catch (e: CancellationException) {
                message = "扫描已停止，未修改任何照片"
                throw e
            } catch (e: Exception) {
                ImageReadGuard.read { ScanDiagnostics.imageFailure(getApplication<Application>(), "scan", e) }
                message = "扫描失败：${e.localizedMessage ?: "请检查权限后重试"}"
            } catch (e: OutOfMemoryError) {
                groups = emptyList(); selected = emptySet()
                ImageReadGuard.read { ScanDiagnostics.imageFailure(getApplication<Application>(), "scan-memory", e) }
                message = "内存不足，扫描已安全停止，未修改照片。请导出诊断，或先使用完全重复模式。"
            } finally { busy = false; scanJob = null }
        }
    }

    fun cancelScan() { scanJob?.cancel(); validationJob?.cancel() }
    fun toggle(uri: String) { if (canChange) selected = SelectionPolicy.toggle(groups, selected, uri) }
    fun clearSelection() { if (canChange) selected = emptySet() }
    fun recommend() { if (canChange) selected = SelectionPolicy.exactRecommendations(groups) }
    fun retain(group: DuplicateGroup, keeper: String) {
        if (!canChange || group.photos.none { it.uri == keeper }) return
        // An explicit choice inside one reviewed group. Never select all similar groups globally.
        selected = (selected - group.photos.map { it.uri }.toSet()) + group.photos.filter { it.uri != keeper }.map { it.uri }
    }
    fun showMessage(text: String) { message = text }

    fun prepareTrash() {
        if (!canChange || !SelectionPolicy.valid(groups, selected)) return
        busy = true; progress = ScanProgress("核对清理计划"); message = "正在核对保留项和待清理文件…"
        val snapshot = groups
        val selection = selected
        validationJob = viewModelScope.launch {
            try {
                val photos = withContext(Dispatchers.IO) {
                    val current = scanner.query().associateBy { it.uri }
                    val targets = mutableListOf<Photo>()
                    for (group in snapshot.filter { g -> g.photos.any { it.uri in selection } }) {
                        val relevant = group.photos
                        // Revalidate every member, including the keeper, before handing control to the system.
                        require(relevant.all { old -> current[old.uri]?.let {
                            it.bytes == old.bytes && it.modified == old.modified && it.generation == old.generation
                        } == true }) { "照片已被移动、修改或删除，请重新扫描" }
                        val keeper = relevant.firstOrNull { it.uri !in selection }
                            ?: error("每组至少需要保留一张照片")
                        if (group.kind == GroupKind.EXACT) {
                            val hash = scanner.sha256(current.getValue(keeper.uri))
                            relevant.filter { it.uri in selection }.forEach { target ->
                                require(scanner.sha256(current.getValue(target.uri)) == hash) {
                                    "完全重复组的文件内容已改变，请重新扫描"
                                }
                            }
                        }
                        targets.addAll(relevant.filter { it.uri in selection })
                    }
                    targets
                }
                require(photos.isNotEmpty()) { "没有可清理的照片" }
                plan = TrashPlan(photos.chunked(200))
            } catch (e: CancellationException) { message = "核对已取消，未发起系统清理请求"; throw e }
            catch (e: Exception) { message = e.localizedMessage ?: "核对失败，请重新扫描" }
            finally { busy = false; validationJob = null }
        }
    }

    fun systemFailed(reason: String) {
        awaitingSystem = false; plan = null; message = reason
    }

    fun completeBatch(approved: Boolean) {
        if (Build.VERSION.SDK_INT < 30) { systemFailed("当前系统不支持照片回收站请求"); return }
        awaitingSystem = false
        val active = plan ?: run { message = "操作已结束，请重新扫描确认相册状态"; return }
        if (!approved) {
            plan = null
            message = "已取消后续清理；此前已移入回收站 ${active.completed} 张"
            return
        }
        busy = true
        viewModelScope.launch {
            try {
                val batch = active.batches[active.batchIndex]
                val trashed = withContext(Dispatchers.IO) {
                    batch.filter { photo ->
                        try {
                            getApplication<Application>().contentResolver.query(Uri.parse(photo.uri),
                                arrayOf(MediaStore.Images.Media.IS_TRASHED), Bundle().apply {
                                    putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
                                }, null)?.use { c ->
                                c.moveToFirst() && c.getInt(0) == 1
                            } == true
                        } catch (_: Exception) { false }
                    }.map { it.uri }.toSet()
                }
                groups = groups.map { g -> g.copy(photos = g.photos.filter { it.uri !in trashed }) }
                    .filter { it.photos.size > 1 }
                selected = selected - batch.map { it.uri }.toSet()
                val completed = active.completed + trashed.size
                if (trashed.size != batch.size) {
                    plan = null
                    message = "已确认移入回收站 $completed 张，部分结果无法确认，请重新扫描"
                } else if (active.batchIndex + 1 < active.batches.size) {
                    plan = active.copy(completed = completed, batchIndex = active.batchIndex + 1)
                } else {
                    plan = null
                    message = "已将 $completed 张照片移入系统回收站，可在系统相册中查看或恢复"
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                plan = null; message = "清理结果核对失败，请重新扫描"
            } finally { busy = false }
        }
    }
}
