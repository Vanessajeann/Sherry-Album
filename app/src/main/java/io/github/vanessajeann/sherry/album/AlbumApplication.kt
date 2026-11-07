/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.vanessajeann.sherry.album

import android.app.Application
import io.github.vanessajeann.sherry.album.duplicate.ScanDiagnostics

class AlbumApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ScanDiagnostics.install(this)
    }
}
