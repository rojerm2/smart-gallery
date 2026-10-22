package dev.smartgallery

import android.content.ComponentName
import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.smartgallery.data.GalleryRepository
import dev.smartgallery.data.Media
import dev.smartgallery.data.mediaShareIntent
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharingIntegrationTest {
    @Test
    fun separateUidCanReadTheOriginalOnlyWithTheProductionReadGrant() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val bytes = instrumentation.context.assets.open("astronaut.png").use { it.readBytes() }
        val name = "share-${UUID.randomUUID()}.png"
        val uri =
            context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/SmartGalleryTests/")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                },
            )!!
        try {
            context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
            context.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            val media =
                Media(
                    "fixture",
                    uri.toString(),
                    name,
                    "Pictures/SmartGalleryTests/",
                    "image/png",
                    0,
                    0,
                    bytes.size.toLong(),
                )
            val receiverUid =
                context.packageManager.getApplicationInfo("dev.smartgallery.test", 0).uid
            assertNotEquals(context.applicationInfo.uid, receiverUid)
            ActivityScenario.launch(MainActivity::class.java).use {
                for (grant in listOf(false, true, false)) {
                    val ready = CountDownLatch(1)
                    val finished = CountDownLatch(1)
                    var code = -1
                    var response = Bundle()
                    val reply =
                        object : ResultReceiver(Handler(Looper.getMainLooper())) {
                            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                                if (resultCode == 2) {
                                    finished.countDown()
                                    return
                                }
                                code = resultCode
                                response = resultData ?: Bundle()
                                ready.countDown()
                            }
                        }
                    val send =
                        mediaShareIntent(context, media).apply {
                            component =
                                ComponentName(
                                    "dev.smartgallery.test",
                                    "dev.smartgallery.ShareProbeActivity",
                                )
                            putExtra("probeReply", reply)
                            addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                            )
                            if (!grant) removeFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    assertEquals(uri, send.clipData!!.getItemAt(0).uri)
                    context.startActivity(send)
                    assertTrue(
                        "Separate receiver must report its actual open result",
                        ready.await(15, TimeUnit.SECONDS),
                    )
                    assertEquals(receiverUid, response.getInt("uid"))
                    assertFalse(response.getBoolean("writeGrant"))
                    assertEquals(grant, response.getBoolean("readGrant"))
                    assertEquals(if (grant) 1 else 0, code)
                    if (grant) {
                        val digest =
                            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                                value ->
                                "%02x".format(value)
                            }
                        assertEquals(digest, response.getString("sha256"))
                    }
                    assertTrue(
                        "Receiving activity must finish before checking grant expiry",
                        finished.await(15, TimeUnit.SECONDS),
                    )
                }
            }
            assertArrayEquals(
                bytes,
                context.contentResolver.openInputStream(uri)!!.use { it.readBytes() },
            )
        } finally {
            // Only this test's generated public-domain fixture is removed.
            context.contentResolver.delete(uri, null, null)
            runBlocking { GalleryRepository(context).reconcile() }
        }
    }
}
