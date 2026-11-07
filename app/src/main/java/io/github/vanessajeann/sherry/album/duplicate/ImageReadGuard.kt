/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.vanessajeann.sherry.album.duplicate

import java.util.concurrent.CancellationException
import kotlin.math.roundToInt

object DecodeDimensions {
    fun fit(width: Int, height: Int, maxSide: Int): Pair<Int, Int> {
        require(width > 0 && height > 0 && maxSide in 32..512)
        val scale = minOf(1.0, maxSide.toDouble() / maxOf(width, height))
        return maxOf(1, (width * scale).roundToInt()) to maxOf(1, (height * scale).roundToInt())
    }

    fun sampleSize(width: Int, height: Int, maxSide: Int): Int {
        fit(width, height, maxSide) // validates inputs without width*height overflow
        var sample = 1
        while ((maxOf(width, height).toLong() + sample - 1) / sample > maxSide) sample *= 2
        return sample
    }
}

/** OOM is an Error, not an Exception. A single image failure must not kill the scan. */
object ImageReadGuard {
    fun <T> read(onFailure: (Throwable) -> Unit = {}, block: () -> T): T? {
        return try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: OutOfMemoryError) { report(onFailure, error); null }
        catch (error: Exception) { report(onFailure, error); null }
    }
    private fun report(callback: (Throwable) -> Unit, error: Throwable) {
        try { callback(error) } catch (_: Exception) { } catch (_: OutOfMemoryError) { }
    }
}
