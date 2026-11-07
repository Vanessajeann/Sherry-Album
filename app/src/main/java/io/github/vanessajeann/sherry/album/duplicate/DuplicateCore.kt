/* SPDX-License-Identifier: GPL-3.0-or-later
 * Sherry Album additions, 2026. Based on PhotoTimeFixer by SiRan-Dev.
 */
package io.github.vanessajeann.sherry.album.duplicate

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

data class Photo(
    val uri: String, val name: String, val bytes: Long,
    val width: Int, val height: Int, val modified: Long,
    val taken: Long, val mime: String, val folder: String,
    val generation: Long = 0,
    val isVideo: Boolean = false,
    val durationMillis: Long = 0,
) {
    val pixels: Long get() = width.toLong() * height
}

data class Fingerprint(val phash: Long, val dhash: Long, val colors: ByteArray, val variance: Double)
data class AnalyzedPhoto(val photo: Photo, val sha256: String?, val fingerprint: Fingerprint?)
enum class GroupKind { EXACT, SIMILAR }
data class DuplicateGroup(val id: String, val kind: GroupKind, val photos: List<Photo>) {
    val keeper: Photo get() = photos.first()
}
enum class MediaType { PHOTO, VIDEO }
enum class ScanMode { EXACT, SIMILAR }
enum class Sensitivity(val radius: Int, val difference: Int, val colorError: Double) {
    STRICT(4, 6, 0.045), BALANCED(7, 10, 0.065), LOOSE(10, 14, 0.085)
}

/** A representative is fixed when a group is created: A~B and B~C never imply A~C. */
object DuplicateCore {
    val qualityOrder = compareByDescending<AnalyzedPhoto> { it.photo.pixels }
        .thenByDescending { it.photo.bytes }.thenBy { if (it.photo.taken > 0) it.photo.taken else Long.MAX_VALUE }
        .thenBy { it.photo.uri }

    fun group(input: List<AnalyzedPhoto>, mode: ScanMode, sensitivity: Sensitivity,
              checkCancelled: () -> Unit = {}): List<DuplicateGroup> {
        // A null digest is never evidence of equality. Exact atoms cannot be split by visual grouping.
        val atoms = input.groupBy { it.sha256?.let { hash -> "hash:$hash" } ?: "uri:${it.photo.uri}" }
            .values.map { it.sortedWith(qualityOrder) }.sortedWith { a, b -> qualityOrder.compare(a.first(), b.first()) }
        if (mode == ScanMode.EXACT) return atoms.filter { it.size > 1 }.map {
            DuplicateGroup(it.first().photo.uri, GroupKind.EXACT, it.map { p -> p.photo })
        }
        val index = HammingIndex<Int>()
        val groups = mutableListOf<MutableList<AnalyzedPhoto>>()
        atoms.forEach { atom ->
            checkCancelled()
            val item = atom.first()
            val fp = item.fingerprint
            val candidate = if (fp == null || fp.variance < 0.0008) null else
                index.within(fp.phash, sensitivity.radius).filter { id ->
                    visuallyMatches(item, groups[id].first(), sensitivity)
                }.minByOrNull { id -> java.lang.Long.bitCount(fp.phash xor groups[id].first().fingerprint!!.phash) }
            if (candidate != null) groups[candidate].addAll(atom) else {
                val id = groups.size
                groups.add(atom.toMutableList())
                if (fp != null && fp.variance >= 0.0008) index.add(fp.phash, id)
            }
        }
        return groups.filter { it.size > 1 }.map { group ->
            val sorted = group.sortedWith(qualityOrder)
            val exact = sorted.first().sha256 != null && sorted.all { it.sha256 == sorted.first().sha256 }
            DuplicateGroup(sorted.first().photo.uri, if (exact) GroupKind.EXACT else GroupKind.SIMILAR,
                sorted.map { it.photo })
        }
    }

    fun visuallyMatches(a: AnalyzedPhoto, b: AnalyzedPhoto, s: Sensitivity): Boolean {
        val x = a.fingerprint ?: return false
        val y = b.fingerprint ?: return false
        if (x.variance < 0.0008 || y.variance < 0.0008) return false
        // Canonical dimensions take EXIF orientation into account in the scanner.
        if (a.photo.width <= 0 || a.photo.height <= 0 || b.photo.width <= 0 || b.photo.height <= 0) return false
        val ar = a.photo.width.toDouble() / a.photo.height
        val br = b.photo.width.toDouble() / b.photo.height
        if (abs(ar - br) / maxOf(ar, br) > 0.025) return false
        if (java.lang.Long.bitCount(x.phash xor y.phash) > s.radius ||
            java.lang.Long.bitCount(x.dhash xor y.dhash) > s.difference) return false
        val error = sqrt(x.colors.indices.sumOf { i ->
            val diff = ((x.colors[i].toInt() and 255) - (y.colors[i].toInt() and 255)) / 255.0; diff * diff
        } / x.colors.size)
        return error <= s.colorError
    }

    /** Pixels are an orientation-correct 32x32, opaque RGB image. */
    fun fingerprint(pixels: IntArray): Fingerprint {
        require(pixels.size == 1024)
        val luma = DoubleArray(1024) { i ->
            val c = pixels[i]; (0.299 * (c shr 16 and 255) + 0.587 * (c shr 8 and 255) + 0.114 * (c and 255)) / 255
        }
        val mean = luma.average()
        val variance = luma.sumOf { (it - mean) * (it - mean) } / 1024
        val dct = DoubleArray(64)
        // Separable low-frequency DCT, excluding DC from the median and hash.
        val horizontal = Array(32) { DoubleArray(8) }
        for (y in 0..31) for (u in 0..7) for (x in 0..31)
            horizontal[y][u] += luma[y * 32 + x] * cosine[u][x]
        for (v in 0..7) for (u in 0..7) for (y in 0..31)
            dct[v * 8 + u] += horizontal[y][u] * cosine[v][y]
        val median = dct.drop(1).sorted()[31]
        var phash = 0L
        for (i in 1..63) if (dct[i] > median) phash = phash or (1L shl i)
        var dhash = 0L
        for (y in 0..7) for (x in 0..7) {
            val row = (y * 4 + 2) * 32
            if (luma[row + x * 3 + 2] > luma[row + (x + 1) * 3 + 2]) dhash = dhash or (1L shl (y * 8 + x))
        }
        val sums = IntArray(8 * 8 * 3)
        for (y in 0..31) for (x in 0..31) {
            val cell = ((y / 4) * 8 + x / 4) * 3
            val c = pixels[y * 32 + x]
            sums[cell] += c shr 16 and 255
            sums[cell + 1] += c shr 8 and 255
            sums[cell + 2] += c and 255
        }
        val colors = ByteArray(sums.size) { ((sums[it] + 8) / 16).toByte() }
        return Fingerprint(phash, dhash, colors, variance)
    }
    private val cosine = Array(8) { u -> DoubleArray(32) { x -> cos((2 * x + 1) * u * Math.PI / 64) } }
}

/** BK tree avoids an unconditional quadratic all-pairs comparison. */
class HammingIndex<T> {
    private class Node<T>(val hash: Long, val values: MutableList<T>, val children: MutableMap<Int, Node<T>> = mutableMapOf())
    private var root: Node<T>? = null
    fun add(hash: Long, value: T) {
        var node = root
        if (node == null) { root = Node(hash, mutableListOf(value)); return }
        while (node != null) {
            val distance = java.lang.Long.bitCount(hash xor node.hash)
            if (distance == 0) { node.values.add(value); return }
            val next = node.children[distance]
            if (next == null) { node.children[distance] = Node(hash, mutableListOf(value)); return }
            node = next
        }
    }
    fun within(hash: Long, radius: Int): List<T> {
        val result = mutableListOf<T>()
        val pending = java.util.ArrayDeque<Node<T>>()
        root?.let { pending.add(it) }
        while (!pending.isEmpty()) {
            val node = pending.removeFirst()
            val distance = java.lang.Long.bitCount(hash xor node.hash)
            if (distance <= radius) result.addAll(node.values)
            node.children.forEach { (edge, child) -> if (edge in (distance - radius)..(distance + radius)) pending.add(child) }
        }
        return result
    }
}

object SelectionPolicy {
    fun toggle(groups: List<DuplicateGroup>, selected: Set<String>, uri: String): Set<String> {
        if (uri in selected) return selected - uri
        val group = groups.firstOrNull { g -> g.photos.any { it.uri == uri } } ?: return selected
        if (group.photos.count { it.uri in selected } >= group.photos.size - 1) return selected
        return selected + uri
    }
    fun exactRecommendations(groups: List<DuplicateGroup>): Set<String> = groups
        .filter { it.kind == GroupKind.EXACT }.flatMap { it.photos.drop(1) }.map { it.uri }.toSet()
    fun valid(groups: List<DuplicateGroup>, selected: Set<String>): Boolean = selected.isNotEmpty() &&
        selected.all { uri -> groups.any { g -> g.photos.any { it.uri == uri } } } &&
        groups.all { g -> g.photos.any { it.uri !in selected } }
}
