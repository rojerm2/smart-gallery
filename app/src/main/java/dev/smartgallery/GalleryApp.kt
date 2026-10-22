package dev.smartgallery

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import java.io.File

class GalleryApp : Application(), ImageLoaderFactory {
    @androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
    override fun onCreate() {
        super.onCreate()
        // Media3 source exceptions can include content URIs; keep them out of diagnostic logs.
        androidx.media3.common.util.Log.setLogLevel(androidx.media3.common.util.Log.LOG_LEVEL_OFF)
    }

    override fun newImageLoader() =
        ImageLoader.Builder(this)
            .components { add(VideoFrameDecoder.Factory()) }
            .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.07).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(File(noBackupFilesDir, "thumbnails"))
                    .maxSizeBytes(32L * 1024 * 1024)
                    .build()
            }
            .respectCacheHeaders(false)
            .build()
}
