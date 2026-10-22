package dev.smartgallery.data

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri

internal fun mediaShareIntent(context: Context, media: Media): Intent {
    val uri = Uri.parse(media.uri)
    return Intent(Intent.ACTION_SEND).apply {
        type = media.mime
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newUri(context.contentResolver, "Media", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
