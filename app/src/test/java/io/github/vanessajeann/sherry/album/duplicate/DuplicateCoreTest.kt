/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.vanessajeann.sherry.album.duplicate

import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class DuplicateCoreTest {
    private fun item(id: String, hash: String? = null, fp: Fingerprint? = null, width: Int = 2000,
                     height: Int = 1000, bytes: Long = 1000) = AnalyzedPhoto(
        Photo(id, "$id.jpg", bytes, width, height, 100, 100, "image/jpeg", "DCIM/"), hash, fp)
    private fun fp(hash: Long, color: Float = 0.5f, variance: Double = 0.05) =
        Fingerprint(hash, 0, ByteArray(192) { (color * 255).toInt().toByte() }, variance)

    @Test fun renamedIdenticalFilesAreExact() {
        val result = DuplicateCore.group(listOf(item("A", "same"), item("B", "same"), item("C", "other")),
            ScanMode.EXACT, Sensitivity.BALANCED)
        assertEquals(1, result.size)
        assertEquals(GroupKind.EXACT, result.single().kind)
        assertEquals(setOf("A", "B"), result.single().photos.map { it.uri }.toSet())
    }
    @Test fun failedHashesDoNotBecomeDuplicateEvidence() {
        assertTrue(DuplicateCore.group(listOf(item("A"), item("B")), ScanMode.EXACT, Sensitivity.LOOSE).isEmpty())
    }
    @Test fun sameShapeDifferentColorsIsNotEnough() {
        assertFalse(DuplicateCore.visuallyMatches(item("A", fp = fp(1, 0.1f)),
            item("B", fp = fp(1, 0.9f)), Sensitivity.LOOSE))
    }
    @Test fun plainFramesAndDifferentAspectRatiosAreRejected() {
        assertFalse(DuplicateCore.visuallyMatches(item("A", fp = fp(1, variance = 0.0)),
            item("B", fp = fp(1, variance = 0.0)), Sensitivity.LOOSE))
        assertFalse(DuplicateCore.visuallyMatches(item("A", fp = fp(1)),
            item("B", fp = fp(1), width = 1000, height = 1000), Sensitivity.LOOSE))
    }
    @Test fun compressedSizeVariantsAreSimilarRatherThanExact() {
        val result = DuplicateCore.group(listOf(item("A", "shaA", fp(123), width = 4000, height = 2000),
            item("B", "shaB", fp(123), width = 1000, height = 500)), ScanMode.SIMILAR, Sensitivity.BALANCED)
        assertEquals(GroupKind.SIMILAR, result.single().kind)
        assertEquals("A", result.single().keeper.uri)
    }
    @Test fun groupingDoesNotFollowTransitiveSimilarityChains() {
        val result = DuplicateCore.group(listOf(item("A", fp = fp(0), bytes = 3000),
            item("B", fp = fp(15), bytes = 2000), item("C", fp = fp(255), bytes = 1000)),
            ScanMode.SIMILAR, Sensitivity.STRICT)
        assertEquals(setOf("A", "B"), result.single().photos.map { it.uri }.toSet())
    }
    @Test fun exactAtomsStayTogetherInVisualScan() {
        val result = DuplicateCore.group(listOf(item("A", "x", fp(1)), item("B", "x", fp(1)),
            item("C", "y", fp(1))), ScanMode.SIMILAR, Sensitivity.BALANCED)
        assertEquals(3, result.single().photos.size)
        assertEquals(GroupKind.SIMILAR, result.single().kind)
    }
    @Test fun automaticSelectionNeverIncludesSimilarGroups() {
        val groups = listOf(DuplicateGroup("a", GroupKind.EXACT, listOf(item("a").photo, item("b").photo)),
            DuplicateGroup("c", GroupKind.SIMILAR, listOf(item("c").photo, item("d").photo)))
        assertEquals(setOf("b"), SelectionPolicy.exactRecommendations(groups))
        val selected = SelectionPolicy.toggle(groups, setOf("b"), "a")
        assertEquals(setOf("b"), selected)
        assertTrue(SelectionPolicy.valid(groups, selected))
        assertFalse(SelectionPolicy.valid(groups, setOf("a", "b")))
        assertFalse(SelectionPolicy.valid(groups, setOf("unknown")))
    }
    @Test fun hammingIndexMatchesBruteForceIncludingSignBitAndCollisions() {
        val random = Random(42)
        val values = List(1500) { random.nextLong() } + listOf(0L, 0L, Long.MIN_VALUE, -1L)
        val index = HammingIndex<Int>()
        values.forEachIndexed { i, hash -> index.add(hash, i) }
        val probes = values.take(20) + listOf(0L, Long.MIN_VALUE, -1L)
        for (hash in probes) for (radius in listOf(0, 4, 10, 32, 64)) {
            val expected = values.indices.filter { java.lang.Long.bitCount(hash xor values[it]) <= radius }.toSet()
            assertEquals(expected, index.within(hash, radius).toSet())
        }
    }
    @Test fun fingerprintRespondsToImageContent() {
        val pixels = IntArray(1024) { i ->
            val x = i % 32; val y = i / 32
            (255 shl 24) or ((x * 8) shl 16) or ((y * 8) shl 8) or ((x * y) % 256)
        }
        val first = DuplicateCore.fingerprint(pixels)
        val again = DuplicateCore.fingerprint(pixels.copyOf())
        assertEquals(first.phash, again.phash)
        assertEquals(first.dhash, again.dhash)
        assertTrue(first.variance > 0.0008)
        val black = DuplicateCore.fingerprint(IntArray(1024) { 0xff000000.toInt() })
        assertEquals(0.0, black.variance, 0.00001)
        assertTrue(first.colors.zip(black.colors).any { (a, b) -> a != b })
    }
}
