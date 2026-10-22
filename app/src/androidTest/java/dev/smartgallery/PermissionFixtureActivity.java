package dev.smartgallery;

import android.app.Activity;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.os.ResultReceiver;
import android.provider.MediaStore;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Owns only public permission-test fixtures, under the test APK's separate UID. */
public class PermissionFixtureActivity extends Activity {
    @Override
    @SuppressWarnings("deprecation")
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ResultReceiver reply = getIntent().getParcelableExtra("permissionReply");
        Bundle response = new Bundle();
        int outcome = 0;
        try {
            if (android.os.Build.VERSION.SDK_INT < 34 || !getPackageName().equals("dev.smartgallery.validation.test")) {
                throw new IllegalStateException("Disposable validation installation only");
            }
            String run = UUID.fromString(getIntent().getStringExtra("permissionRun")).toString();
            File marker = new File(getNoBackupFilesDir(), "permission-fixtures-" + run + ".json");
            if (getIntent().getBooleanExtra("permissionCleanup", false)) {
                if (marker.isFile()) {
                    JSONArray items = new JSONArray(Files.readString(marker.toPath()));
                    for (int i = 0; i < items.length(); i++) {
                        getContentResolver().delete(Uri.parse(items.getJSONObject(i).getString("uri")), null, null);
                    }
                    Files.delete(marker.toPath());
                }
            } else {
                if (marker.exists()) throw new IllegalStateException("Run already exists");
                JSONArray items = new JSONArray();
                String folder = "DCIM/SG-Permissions-" + run.substring(0, 8) + "/";
                String[] assets = {"astronaut.png", "grace_hopper.jpg", "later-appearance.mp4"};
                for (int i = 0; i < assets.length; i++) {
                    boolean video = i == 2;
                    String name = "public-" + i + (video ? ".mp4" : i == 0 ? ".png" : ".jpg");
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                    values.put(MediaStore.MediaColumns.RELATIVE_PATH, folder);
                    values.put(MediaStore.MediaColumns.MIME_TYPE, video ? "video/mp4" : i == 0 ? "image/png" : "image/jpeg");
                    values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                    Uri uri = getContentResolver().insert(video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                    if (uri == null) throw new IllegalStateException("Fixture creation failed");
                    JSONObject item = new JSONObject().put("uri", uri.toString()).put("name", name);
                    items.put(item);
                    Files.writeString(marker.toPath(), items.toString(), StandardCharsets.UTF_8);
                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    try (InputStream input = getAssets().open(assets[i]); OutputStream output = getContentResolver().openOutputStream(uri)) {
                        if (output == null) throw new IllegalStateException("Fixture stream missing");
                        byte[] buffer = new byte[8192];
                        int count;
                        while ((count = input.read(buffer)) != -1) {
                            output.write(buffer, 0, count);
                            digest.update(buffer, 0, count);
                        }
                    }
                    StringBuilder hex = new StringBuilder();
                    for (byte value : digest.digest()) hex.append(String.format("%02x", value & 255));
                    item.put("sha256", hex.toString());
                    values.clear();
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    getContentResolver().update(uri, values, null, null);
                    // Keep these owned public examples easy to find in the system's recent grid.
                    values.clear();
                    values.put(MediaStore.MediaColumns.DATE_TAKEN, System.currentTimeMillis());
                    getContentResolver().update(uri, values, null, null);
                    Files.writeString(marker.toPath(), items.toString(), StandardCharsets.UTF_8);
                }
                response.putString("items", items.toString());
                response.putString("folder", folder);
            }
            response.putInt("uid", Process.myUid());
            outcome = 1;
        } catch (Exception failure) {
            // Never export provider/private exception messages or gallery information.
        } finally {
            if (reply != null) reply.send(outcome, response);
            finish();
        }
    }
}
