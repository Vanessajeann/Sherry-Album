/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.vanessajeann.sherry.album.duplicate

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class ImageReadGuardTest {
    @Test fun oversizedCameraAndPanoramaDimensionsStayWithinBudget() {
        for ((width, height) in listOf(16384 to 12288, 100000 to 300, Int.MAX_VALUE to Int.MAX_VALUE, 1 to 100000)) {
            val fit = DecodeDimensions.fit(width, height, 192)
            assertTrue(fit.first in 1..192 && fit.second in 1..192)
            assertTrue(fit.first.toLong() * fit.second * 4 <= 192L * 192 * 4)
            val sample = DecodeDimensions.sampleSize(width, height, 192)
            assertTrue(sample > 0)
            assertTrue((maxOf(width, height).toLong() + sample - 1) / sample <= 192)
        }
    }
    @Test fun simulatedDecoderOomSkipsOnlyTheFailingImage() {
        val failures = mutableListOf<String>()
        val results = (0..2).map { i -> ImageReadGuard.read(onFailure = { failures.add(it.javaClass.simpleName) }) {
            if (i == 1) throw OutOfMemoryError("injected image decoder failure")
            i
        } }
        assertEquals(listOf(0, null, 2), results)
        assertEquals(listOf("OutOfMemoryError"), failures)
    }
    @Test fun decoderRuntimeFailureDoesNotEscape() {
        assertNull(ImageReadGuard.read<Int> { throw IllegalStateException("corrupt codec header") })
    }
    @Test(expected = CancellationException::class) fun cancellationStillStopsScan() {
        ImageReadGuard.read<Int> { throw CancellationException() }
    }
    @Test fun failedDiagnosticWriterCannotCauseAnotherCrash() {
        assertNull(ImageReadGuard.read<Int>(onFailure = { throw OutOfMemoryError("logging unavailable") }) {
            throw OutOfMemoryError("decode")
        })
    }
}
