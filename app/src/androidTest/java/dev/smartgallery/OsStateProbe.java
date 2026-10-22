package dev.smartgallery;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.json.JSONObject;

/** Read-only app_process probe. Does not construct Application, Room, WorkManager or a worker. */
public final class OsStateProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected run UUID");
        String run = UUID.fromString(args[0]).toString();
        File root = new File(".").getCanonicalFile();
        if (!root.getName().equals("dev.smartgallery.validation"))
            throw new SecurityException("Validation UID only");
        JSONObject state = new JSONObject(new String(Files.readAllBytes(new File(root,
                "no_backup/os-recovery-" + run + "/state.json").toPath()), StandardCharsets.UTF_8));
        JSONObject result = new JSONObject();
        result.put("ready", state.optBoolean("ready"));
        if (state.optBoolean("ready")) {
            try (SQLiteDatabase db = SQLiteDatabase.openDatabase(new File(root,
                    "databases/gallery.db").getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
                try (Cursor c = db.rawQuery("SELECT checkpoint,state FROM stages WHERE mediaId=? AND kind='1video'",
                        new String[] {state.getString("mediaId")})) {
                    if (!c.moveToFirst()) throw new IllegalStateException("Owned stage absent");
                    result.put("checkpoint", c.getInt(0));
                    result.put("state", c.getString(1));
                }
            }
            result.put("before", state.getInt("checkpoint"));
            result.put("planned", state.getInt("planned"));
        }
        System.out.println(result);
    }
}
