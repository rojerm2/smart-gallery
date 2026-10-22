package dev.smartgallery

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.work.WorkManager
import dev.smartgallery.analysis.IndexScheduler
import dev.smartgallery.data.GalleryRepository
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit modern-permission lifecycle; never grants/revokes the personal installation. */
@RunWith(AndroidJUnit4::class)
class PermissionLifecycleTest {
    @Test
    fun realPermissionSelectionAndRevocationReconcileAccessibleFixtures() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val mode = args.getString("permissionMode")
        assumeTrue("Requires the explicit permission-test host", mode != null)
        assumeTrue(Build.VERSION.SDK_INT >= 34)
        val context = instrumentation.targetContext
        check(context.packageName == "dev.smartgallery.validation")
        val run = UUID.fromString(args.getString("permissionRun")).toString()
        val marker = File(context.noBackupFilesDir, "permission-$run.json")
        val repo = GalleryRepository(context)
        IndexScheduler.pause(context)
        WorkManager.getInstance(context).cancelAllWork().result.get()

        fun fixtureActivity(cleanup: Boolean): Bundle {
            val ready = CountDownLatch(1)
            var outcome = 0
            var result = Bundle()
            val reply =
                object : ResultReceiver(Handler(Looper.getMainLooper())) {
                    override fun onReceiveResult(code: Int, data: Bundle?) {
                        outcome = code
                        result = data ?: Bundle()
                        ready.countDown()
                    }
                }
            context.startActivity(
                Intent().apply {
                    component =
                        ComponentName(
                            instrumentation.context.packageName,
                            "dev.smartgallery.PermissionFixtureActivity",
                        )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                    putExtra("permissionRun", run)
                    putExtra("permissionCleanup", cleanup)
                    putExtra("permissionReply", reply)
                }
            )
            assertTrue("Separate fixture owner must respond", ready.await(30, TimeUnit.SECONDS))
            assertEquals("Separate fixture operation failed", 1, outcome)
            assertNotEquals(context.applicationInfo.uid, result.getInt("uid"))
            return result
        }

        if (mode == "cleanup") {
            fixtureActivity(true)
            repo.reset()
            marker.delete()
            return@runBlocking
        }
        if (mode == "prepare") {
            requireFixtureOnlyGalleryAccess(context)
            check(!marker.exists())
            repo.reset()
            repo.settings.paused = true
            val result = fixtureActivity(false)
            marker.writeText(
                JSONObject()
                    .put("items", JSONArray(result.getString("items")))
                    .put("folder", result.getString("folder"))
                    .toString()
            )
            return@runBlocking
        }
        require(mode in setOf("denied", "full", "selected", "reselected", "revoked"))
        val state = JSONObject(marker.readText())
        val items = state.getJSONArray("items")
        val folder = state.getString("folder")
        fun granted(permission: String) =
            context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        val device = UiDevice.getInstance(instrumentation)
        fun pickerControls() {
            val ids =
                device
                    .findObjects(By.res(Pattern.compile(".*")))
                    .mapNotNull { it.resourceName }
                    .distinct()
                    .take(80)
            val knownLabels =
                listOf(
                        "Photos",
                        "Albums",
                        "Collections",
                        "Selected",
                        "Done",
                        "Add",
                        "Allow",
                        "Videos",
                        "Recents",
                        "Recent",
                        "Cancel",
                    )
                    .filter { device.hasObject(By.text(it)) || device.hasObject(By.desc(it)) }
            instrumentation.sendStatus(
                2,
                Bundle().apply {
                    putString(
                        "stream",
                        "Permission picker controls: knownLabels=$knownLabels resourceIds=$ids\n",
                    )
                },
            )
        }
        if (mode in setOf("denied", "full", "selected", "reselected")) {
            ActivityScenario.launch(MainActivity::class.java).use {
                val action =
                    if (mode == "reselected") "Manage selection" else "Allow photos & videos"
                assertTrue(
                    "Production access action must be visible",
                    device.wait(Until.hasObject(By.text(action)), 15_000),
                )
                device.findObject(By.text(action)).click()
                when (mode) {
                    "denied" -> {
                        val deny =
                            device.wait(
                                Until.findObject(
                                    By.res(Pattern.compile(".*:id/permission_deny_button"))
                                ),
                                10_000,
                            )
                        assertNotNull("System permission dialog must offer denial", deny)
                        deny!!.click()
                        assertTrue(
                            device.wait(Until.hasObject(By.text("Allow photos & videos")), 10_000)
                        )
                    }
                    "full" -> {
                        val allow =
                            device.wait(
                                Until.findObject(
                                    By.res(Pattern.compile(".*:id/permission_allow_all_button"))
                                ),
                                10_000,
                            ) ?: device.findObject(By.text("Allow all"))
                        assertNotNull("System permission dialog must offer full access", allow)
                        allow!!.click()
                        withTimeout(10_000) {
                            while (
                                !granted(Manifest.permission.READ_MEDIA_IMAGES) ||
                                    !granted(Manifest.permission.READ_MEDIA_VIDEO)
                            ) delay(100)
                        }
                    }
                    else -> {
                        if (mode == "selected") {
                            val select =
                                device.wait(
                                    Until.findObject(
                                        By.res(
                                            Pattern.compile(
                                                ".*:id/permission_allow_selected_button"
                                            )
                                        )
                                    ),
                                    10_000,
                                ) ?: device.findObject(By.textContains("Select photos"))
                            assertNotNull(
                                "System permission dialog must offer selected access",
                                select,
                            )
                            select!!.click()
                        }
                        if (args.getString("manualPermissionSelection") == "true") {
                            val expected = if (mode == "selected") 1 else 2
                            instrumentation.sendStatus(
                                2,
                                Bundle().apply {
                                    putString(
                                        "stream",
                                        "Waiting for manual public-fixture selection: $expected total fixture items, then confirm in the system picker.\n",
                                    )
                                },
                            )
                            withTimeout(360_000) {
                                while (true) {
                                    val visible =
                                        (0 until items.length()).count { index ->
                                            try {
                                                context.contentResolver
                                                    .openAssetFileDescriptor(
                                                        Uri.parse(
                                                            items
                                                                .getJSONObject(index)
                                                                .getString("uri")
                                                        ),
                                                        "r",
                                                    )
                                                    ?.use { true } ?: false
                                            } catch (_: SecurityException) {
                                                false
                                            } catch (_: java.io.FileNotFoundException) {
                                                false
                                            }
                                        }
                                    if (
                                        visible == expected &&
                                            granted(
                                                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                                            ) &&
                                            device.currentPackageName == context.packageName
                                    )
                                        break
                                    delay(300)
                                }
                            }
                        } else {
                            val albumTab =
                                Pattern.compile("Albums|Collections", Pattern.CASE_INSENSITIVE)
                            val albums =
                                device.wait(Until.findObject(By.text(albumTab)), 10_000)
                                    ?: device.findObject(By.desc(albumTab))
                            if (albums == null) pickerControls()
                            assertNotNull("System picker must expose albums", albums)
                            albums!!.click()
                            val albumName = folder.trimEnd('/').substringAfterLast('/')
                            val album = device.wait(Until.findObject(By.text(albumName)), 10_000)
                            if (album == null) {
                                // Only resource IDs enter diagnostics, never private picker
                                // text/descriptions.
                                val ids =
                                    device
                                        .findObjects(By.res(Pattern.compile(".*")))
                                        .mapNotNull { it.resourceName }
                                        .distinct()
                                instrumentation.sendStatus(
                                    2,
                                    Bundle().apply {
                                        putString(
                                            "stream",
                                            "Permission picker resource IDs: $ids\n",
                                        )
                                    },
                                )
                            }
                            assertNotNull(
                                "Only the newly created public fixture album may be selected",
                                album,
                            )
                            album!!.click()
                            val thumbs =
                                device.wait(
                                    Until.findObjects(
                                        By.res(Pattern.compile(".*:id/icon_thumbnail"))
                                    ),
                                    10_000,
                                )
                            assertEquals(
                                "Public fixture album must contain exactly three items",
                                3,
                                thumbs?.size,
                            )
                            // First selection chooses one item. Reselection adds the second fixed
                            // item;
                            // the OS retains the previous selection. Membership assertions below
                            // verify
                            // it.
                            thumbs!![if (mode == "selected") 0 else 1].click()
                            val done =
                                device.findObject(By.res(Pattern.compile(".*:id/button_add")))
                                    ?: device.findObject(
                                        By.text(Pattern.compile("(Allow|Add|Done).*"))
                                    )
                            assertNotNull("System picker must confirm selected access", done)
                            done!!.click()
                            withTimeout(10_000) {
                                while (
                                    !granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                                ) delay(100)
                            }
                        }
                    }
                }
            }
        }
        if (mode == "full") {
            assertTrue(granted(Manifest.permission.READ_MEDIA_IMAGES))
            assertTrue(granted(Manifest.permission.READ_MEDIA_VIDEO))
        } else if (mode in setOf("selected", "reselected")) {
            assertFalse(granted(Manifest.permission.READ_MEDIA_IMAGES))
            assertFalse(granted(Manifest.permission.READ_MEDIA_VIDEO))
            assertTrue(granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
        } else requireFixtureOnlyGalleryAccess(context)

        fun verifyOriginal(uri: Uri, fixture: JSONObject) {
            val digest =
                context.contentResolver.openInputStream(uri)!!.use { input ->
                    val sha = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        sha.update(buffer, 0, count)
                    }
                    sha.digest().joinToString("") { "%02x".format(it) }
                }
            assertEquals(
                "Fixture original must remain unchanged",
                fixture.getString("sha256"),
                digest,
            )
        }
        if (mode == "full") {
            // Full access is checked through the actual system dialog and exact fixture URIs.
            // Do not run an extra full-library metadata scan just to count three public fixtures.
            for (i in 0 until items.length()) {
                val fixture = items.getJSONObject(i)
                verifyOriginal(Uri.parse(fixture.getString("uri")), fixture)
            }
            instrumentation.sendStatus(
                2,
                Bundle().apply {
                    putString(
                        "stream",
                        "Permission lifecycle full: actual system grant and all three separate-UID original reads passed. No private gallery data exported.\n",
                    )
                },
            )
            return@runBlocking
        }
        repo.reconcile()
        val snapshot = repo.dao.accessibleMedia()
        if (mode in setOf("selected", "reselected"))
            assertTrue("Only public fixtures may be selected", snapshot.all { it.folder == folder })
        val accessible = snapshot.filter { it.folder == folder }
        val expectedCount =
            when (mode) {
                "full" -> 3
                "selected" -> 1
                "reselected" -> 2
                else -> 0
            }
        assertEquals(
            "Production reconciliation must track actual permission-visible fixture membership",
            expectedCount,
            accessible.size,
        )
        for (media in accessible) {
            val fixture =
                (0 until items.length())
                    .map { items.getJSONObject(it) }
                    .single { it.getString("name") == media.name }
            verifyOriginal(Uri.parse(media.uri), fixture)
        }
        if (mode == "reselected") {
            state.put("ids", JSONArray(accessible.map { it.id }))
            marker.writeText(state.toString())
        }
        if (mode == "revoked") {
            val ids = state.getJSONArray("ids")
            for (i in 0 until ids.length()) assertFalse(
                repo.dao.mediaById(ids.getString(i))!!.accessible
            )
            ActivityScenario.launch(MainActivity::class.java).use {
                assertTrue(device.wait(Until.hasObject(By.text("Allow photos & videos")), 10_000))
            }
        }
        assertTrue("Permission checks must leave analysis paused", repo.settings.paused)
        instrumentation.sendStatus(
            2,
            Bundle().apply {
                putString(
                    "stream",
                    "Permission lifecycle $mode: expected fixture count=$expectedCount, production reconciliation and accessible original digests passed. No private gallery data exported.\n",
                )
            },
        )
    }
}
