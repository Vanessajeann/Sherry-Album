/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.vanessajeann.sherry.album.duplicate

import org.junit.Assert.*
import org.junit.Test

class ScanProgressTest {
    @Test fun unknownTotalIsNeverDivided() {
        for (done in listOf(0, 739, 1111, Int.MAX_VALUE, Int.MIN_VALUE)) {
            assertNull(ScanProgress("group", done, 0).fraction)
            assertNull(ScanProgress("query", done, -1).fraction)
        }
    }
    @Test fun crashTraceTransitionsHaveValidSemanticsInputs() {
        val phases = listOf(
            ScanProgress("visual", 739, 743),
            ScanProgress("group"),
            ScanProgress("complete", 1111, 1111),
            ScanProgress("done")
        )
        phases.forEach { phase -> phase.fraction?.let {
            assertTrue(it.isFinite()); assertTrue(it in 0f..1f)
        } }
        assertEquals(739f / 743f, phases[0].fraction!!, 0.00001f)
        assertNull(phases[1].fraction)
        assertEquals(1f, phases[2].fraction!!, 0f)
        assertNull(phases[3].fraction)
    }
    @Test fun countsOutsideRangeAndExtremeValuesRemainFinite() {
        assertEquals(0f, ScanProgress("", -1, 1).fraction!!, 0f)
        assertEquals(1f, ScanProgress("", Int.MAX_VALUE, 1).fraction!!, 0f)
        assertEquals(0f, ScanProgress("", Int.MIN_VALUE, Int.MAX_VALUE).fraction!!, 0f)
        assertEquals(1f, ScanProgress("", Int.MAX_VALUE, Int.MAX_VALUE).fraction!!, 0f)
    }
}
