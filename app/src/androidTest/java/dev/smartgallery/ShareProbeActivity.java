package dev.smartgallery;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.os.ResultReceiver;
import java.io.InputStream;
import java.security.MessageDigest;

/** Runs as the test APK's own UID, with no storage permission or target-app runtime dependency. */
public class ShareProbeActivity extends Activity {
    private ResultReceiver reply;

    @Override
    @SuppressWarnings("deprecation")
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        reply = getIntent().getParcelableExtra("probeReply");
        Bundle result = new Bundle();
        result.putInt("uid", Process.myUid());
        int outcome = 0;
        try {
            Uri uri = getIntent().getParcelableExtra(Intent.EXTRA_STREAM);
            result.putBoolean("readGrant", checkUriPermission(uri, Process.myPid(), Process.myUid(),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED);
            result.putBoolean("writeGrant", checkUriPermission(uri, Process.myPid(), Process.myUid(),
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IllegalStateException("No stream");
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            StringBuilder hex = new StringBuilder();
            for (byte value : digest.digest()) hex.append(String.format("%02x", value & 255));
            result.putString("sha256", hex.toString());
            outcome = 1;
        } catch (Exception expectedWithoutGrant) {
            // No filenames, URI contents or private exception messages enter logs/results.
        } finally {
            if (reply != null) reply.send(outcome, result);
            finish();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (reply != null) reply.send(2, new Bundle());
    }
}
