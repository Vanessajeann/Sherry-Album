/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.vanessajeann.sherry.album

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.vanessajeann.sherry.album.duplicate.ScanProgress
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScanProgressUiTest {
    @get:Rule val compose = createComposeRule()

    private fun assertFiniteSemantics(): ProgressBarRangeInfo {
        val info = compose.onNodeWithTag("scan-progress", useUnmergedTree = true)
            .fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertTrue("Progress semantics must never contain NaN/Infinity", info.current.isFinite())
        return info
    }

    @Test fun visualGroupingAndCompletionRenderWithoutNaN() {
        val state = mutableStateOf(ScanProgress("visual", 739, 743))
        compose.setContent { PhotoTimeFixerTheme { ScanProgressIndicator(state.value) } }
        val phases = listOf(
            ScanProgress("visual", 739, 743),
            ScanProgress("group"),
            ScanProgress("complete", 1111, 1111),
            ScanProgress("done"),
            ScanProgress("hash", 0, 1111)
        )
        repeat(8) {
            phases.forEach { phase ->
                compose.runOnIdle { state.value = phase }
                val info = assertFiniteSemantics()
                if (phase.fraction == null) assertEquals(ProgressBarRangeInfo.Indeterminate, info)
                else assertEquals(phase.fraction!!, info.current, 0.00001f)
            }
        }
    }

    @Test fun semanticsRemainSafeBeforeAStageChangeRecomposes() {
        val state = mutableStateOf(ScanProgress("visual", 739, 743))
        compose.setContent { PhotoTimeFixerTheme { ScanProgressIndicator(state.value) } }
        assertFiniteSemantics()
        compose.mainClock.autoAdvance = false
        try {
            repeat(12) {
                compose.runOnUiThread { state.value = ScanProgress("group") }
                // Read semantics while the old determinate node can still be present.
                assertFiniteSemantics()
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                assertEquals(ProgressBarRangeInfo.Indeterminate, assertFiniteSemantics())
                compose.runOnUiThread { state.value = ScanProgress("visual", 739, 743) }
                assertFiniteSemantics()
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                assertFiniteSemantics()
            }
        } finally { compose.mainClock.autoAdvance = true }
    }
}
