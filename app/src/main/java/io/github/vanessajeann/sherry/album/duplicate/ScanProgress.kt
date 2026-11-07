/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.vanessajeann.sherry.album.duplicate

/** One immutable stage snapshot. Unknown totals never produce a determinate fraction. */
data class ScanProgress(val stage: String, val done: Int = 0, val total: Int = 0) {
    val fraction: Float?
        get() = if (total <= 0) null else
            (done.toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat()

    val displayDone: Int get() = if (total > 0) done.coerceIn(0, total) else 0
}
