package dev.smartgallery

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import java.io.File

class GalleryApp : Application(), ImageLoaderFactory {
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
