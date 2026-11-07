/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.vanessajeann.sherry.album

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.vanessajeann.sherry.album.duplicate.ScanProgress

@Composable
fun ScanProgressIndicator(progress: ScanProgress, modifier: Modifier = Modifier) {
    // Capture a finite number now. The semantics/draw lambda must not reread a mutable
    // ViewModel after the next stage has reset its total to zero.
    val fraction = progress.fraction
    Column(modifier) {
        Text(progress.stage + if (fraction != null) " ${progress.displayDone}/${progress.total}" else "")
        val barModifier = Modifier.fillMaxWidth().testTag("scan-progress")
        if (fraction == null) {
            LinearProgressIndicator(modifier = barModifier)
        } else {
            LinearProgressIndicator(progress = { fraction }, modifier = barModifier)
        }
    }
}
