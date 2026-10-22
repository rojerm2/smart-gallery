package dev.smartgallery

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import org.junit.Assume.assumeTrue

/** Tests that reconcile MediaStore or capture UI must see only their own generated fixtures. */
internal fun requireFixtureOnlyGalleryAccess(context: Context) {
    val permissions =
        when {
            Build.VERSION.SDK_INT >= 34 ->
                listOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                )
            Build.VERSION.SDK_INT >= 33 ->
                listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
            else -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    assumeTrue(
        "Fixture tests require no shared-gallery permission; use a separate validation installation",
        permissions.none {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        },
    )
}
