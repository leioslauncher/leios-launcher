package tv.leios.launcher;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Settings backups and theme files.
 *
 * <p>Automatic backups are JSON files in the app's own storage (the last 10). Exported backups and
 * themes are PNG pictures with the settings stored inside them, in a private chunk ("clHm") next to
 * a preview: this projector has no file picker, and Android only lets Home see files of other
 * apps (a USB drive, another TV) when they're pictures. Any picture viewer shows the preview;
 * only Home reads the settings.
 */
final class Backups {

    private Backups() {
    }

    static final int KEEP = 10;
    static final String SUFFIX = ".leios.png";
    /** Saved by the app before it was renamed Leios (Pictures/Clean Home); still read, never written. */
    static final String OLD_SUFFIX = ".cleanhome.png";
    private static final String FOLDER = "Pictures/Leios/";
    private static final byte[] CHUNK = {'c', 'l', 'H', 'm'};
    /** Files that travel with a full backup (icons and photos you picked), and the size limit for each. */
    private static final String[] FILES = {"background.jpg", "background.anim"};
    private static final long MAX_FILE = 8L << 20;

    // ---- what's in a backup ----------------------------------------------------------------

    /** Every Home setting (and usage counts), plus your background photo and custom icons. */
    static JSONObject snapshot(Context c, boolean withFiles) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("kind", "backup");
        o.put("version", 1);
        o.put("time", System.currentTimeMillis());
        o.put("focus", prefsToJson(c.getSharedPreferences("focus", Context.MODE_PRIVATE), null));
        o.put("usage", prefsToJson(c.getSharedPreferences("usage", Context.MODE_PRIVATE), null));
        if (withFiles) o.put("files", filesToJson(c));
        return o;
    }

    /** Only these keys from "focus" (a theme), with the background photo if it's one. */
    static JSONObject subset(Context c, Set<String> keys, boolean withPhoto) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("kind", "theme");
        o.put("version", 1);
        o.put("focus", prefsToJson(c.getSharedPreferences("focus", Context.MODE_PRIVATE), keys));
        if (withPhoto) {
            File f = new File(c.getFilesDir(), "background.jpg");
            if (f.exists() && f.length() <= MAX_FILE) {
                JSONObject files = new JSONObject();
                files.put("background.jpg", base64(f));
                o.put("files", files);
            }
        }
        return o;
    }

    /**
     * Puts a backup or theme in place. A full backup ({@code themeKeys} null) replaces everything.
     * A theme clears and sets only {@code themeKeys} (look settings): whatever else a theme file
     * carries is ignored, so a theme can never change a PIN or your apps.
     */
    // commit, not apply: the settings must be on disk before Home restarts into them.
    @android.annotation.SuppressLint("ApplySharedPref")
    static void apply(Context c, JSONObject o, Set<String> themeKeys) throws JSONException {
        SharedPreferences focus = c.getSharedPreferences("focus", Context.MODE_PRIVATE);
        boolean full = themeKeys == null;
        if (full && !"backup".equals(o.optString("kind"))) throw new JSONException("not a backup");
        SharedPreferences.Editor e = focus.edit();
        if (full) e.clear();
        else for (String k : themeKeys) e.remove(k);
        JSONObject values = o.getJSONObject("focus");
        if (!full) {
            JSONObject only = new JSONObject();
            for (String k : themeKeys) if (values.has(k)) only.put(k, values.get(k));
            values = only;
        }
        jsonToPrefs(values, e);
        e.commit();
        if (full && o.has("usage")) {
            SharedPreferences.Editor u = c.getSharedPreferences("usage", Context.MODE_PRIVATE).edit().clear();
            jsonToPrefs(o.getJSONObject("usage"), u);
            u.commit();
        }
        JSONObject files = o.optJSONObject("files");
        if (full) {
            for (String n : FILES) //noinspection ResultOfMethodCallIgnored
                new File(c.getFilesDir(), n).delete();
            File icons = new File(c.getFilesDir(), "icons");
            File[] old = icons.listFiles();
            if (old != null) for (File f : old) //noinspection ResultOfMethodCallIgnored
                f.delete();
        }
        if (files != null) {
            for (Iterator<String> it = files.keys(); it.hasNext(); ) {
                String name = it.next();
                // Only the files Home itself keeps: a backup can't write anywhere else.
                boolean known = Arrays.asList(FILES).contains(name)
                        || (name.startsWith("icons/") && name.endsWith(".png") && name.indexOf('/', 6) < 0);
                if (!known || name.contains("..") || (!full && !name.equals("background.jpg"))) continue;
                File f = new File(c.getFilesDir(), name);
                //noinspection ResultOfMethodCallIgnored
                f.getParentFile().mkdirs();
                try (FileOutputStream out = new FileOutputStream(f)) {
                    out.write(java.util.Base64.getDecoder().decode(files.getString(name)));
                } catch (IOException | IllegalArgumentException ignored) {
                }
            }
        }
    }

    static JSONObject prefsToJson(SharedPreferences p, Set<String> only) throws JSONException {
        JSONObject o = new JSONObject();
        for (Map.Entry<String, ?> e : p.getAll().entrySet()) {
            if (only != null && !only.contains(e.getKey())) continue;
            Object v = e.getValue();
            JSONObject t = new JSONObject();
            if (v instanceof Boolean) t.put("b", v);
            else if (v instanceof Integer) t.put("i", v);
            else if (v instanceof Long) t.put("l", v);
            else if (v instanceof Float) t.put("f", (double) (Float) v);
            else if (v instanceof String) t.put("s", v);
            else if (v instanceof Set) t.put("set", new JSONArray((Set<?>) v));
            else continue;
            o.put(e.getKey(), t);
        }
        return o;
    }

    static void jsonToPrefs(JSONObject o, SharedPreferences.Editor e) throws JSONException {
        for (Iterator<String> it = o.keys(); it.hasNext(); ) {
            String k = it.next();
            JSONObject t = o.getJSONObject(k);
            if (t.has("b")) e.putBoolean(k, t.getBoolean("b"));
            else if (t.has("i")) e.putInt(k, t.getInt("i"));
            else if (t.has("l")) e.putLong(k, t.getLong("l"));
            else if (t.has("f")) e.putFloat(k, (float) t.getDouble("f"));
            else if (t.has("s")) e.putString(k, t.getString("s"));
            else if (t.has("set")) {
                JSONArray a = t.getJSONArray("set");
                Set<String> s = new HashSet<>();
                for (int i = 0; i < a.length(); i++) s.add(a.getString(i));
                e.putStringSet(k, s);
            }
        }
    }

    private static JSONObject filesToJson(Context c) throws JSONException {
        JSONObject files = new JSONObject();
        for (String n : FILES) {
            File f = new File(c.getFilesDir(), n);
            if (f.exists() && f.length() <= MAX_FILE) files.put(n, base64(f));
        }
        File[] icons = new File(c.getFilesDir(), "icons").listFiles();
        if (icons != null) for (File f : icons) if (f.length() <= MAX_FILE) files.put("icons/" + f.getName(), base64(f));
        return files;
    }

    private static String base64(File f) {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[64 << 10];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return java.util.Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (IOException e) {
            return "";
        }
    }

    // ---- automatic backups (app storage) -----------------------------------------------------

    static final class Entry {
        final String label;
        final long time;
        final File file;
        final Uri uri;

        Entry(String label, long time, File file, Uri uri) {
            this.label = label;
            this.time = time;
            this.file = file;
            this.uri = uri;
        }
    }

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "backups");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    /** Saves one now ({@code reason} is shown in the list: "Before reset", "After update"…). */
    static void auto(Context c, String reason) {
        try {
            JSONObject o = snapshot(c, true);
            o.put("reason", reason);
            File f = new File(dir(c), "auto-" + System.currentTimeMillis() + ".json");
            try (OutputStream out = new FileOutputStream(f)) {
                out.write(o.toString().getBytes(StandardCharsets.UTF_8));
            }
            File[] all = dir(c).listFiles((d, n) -> n.startsWith("auto-"));
            if (all != null && all.length > KEEP) {
                Arrays.sort(all, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
                for (int i = 0; i < all.length - KEEP; i++) //noinspection ResultOfMethodCallIgnored
                    all[i].delete();
            }
        } catch (IOException | JSONException ignored) {
        }
    }

    /** Automatic backups (newest first), then exported ones found on the TV and USB drives. */
    static List<Entry> list(Context c, boolean canSeePictures) {
        List<Entry> out = new ArrayList<>();
        File[] all = dir(c).listFiles((d, n) -> n.startsWith("auto-") && n.endsWith(".json"));
        if (all != null) {
            Arrays.sort(all, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (File f : all) {
                String reason = "";
                try {
                    reason = new JSONObject(readText(new FileInputStream(f))).optString("reason", "");
                } catch (IOException | JSONException ignored) {
                }
                out.add(new Entry(reason.isEmpty() ? "Automatic" : reason, f.lastModified(), f, null));
            }
        }
        if (canSeePictures) out.addAll(exported(c, "backup"));
        return out;
    }

    /** Exported backups or themes ({@code kind}), by the name they were saved under. */
    static List<Entry> exported(Context c, String kind) {
        List<Entry> out = new ArrayList<>();
        Uri col = Media.images();
        String[] cols = {MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_MODIFIED};
        String byName = MediaStore.Images.Media.DISPLAY_NAME + " LIKE ?";
        try (Cursor cur = c.getContentResolver().query(col, cols, byName + " OR " + byName,
                new String[] {"%" + kind + "%" + SUFFIX, "%" + kind + "%" + OLD_SUFFIX},
                MediaStore.Images.Media.DATE_MODIFIED + " DESC")) {
            while (cur != null && cur.moveToNext()) {
                String name = cur.getString(1);
                boolean old = name.endsWith(OLD_SUFFIX);
                String label = name.substring(0, name.length() - (old ? OLD_SUFFIX : SUFFIX).length());
                // Saved backups are told apart by their date, shown beside them.
                if (label.startsWith("Leios backup")) label = "Saved backup";
                else if (label.startsWith("Clean Home backup")) label = "Older backup";
                out.add(new Entry(label, cur.getLong(2) * 1000, null, ContentUris.withAppendedId(col, cur.getLong(0))));
            }
        } catch (RuntimeException ignored) {
        }
        return out;
    }

    // ---- reading and writing files ----------------------------------------------------------

    /** The settings in a backup or theme; IOException if it isn't one, or is damaged. */
    static JSONObject read(Context c, Entry e) throws IOException {
        try {
            if (e.file != null) return new JSONObject(readText(new FileInputStream(e.file)));
            try (InputStream in = c.getContentResolver().openInputStream(e.uri)) {
                if (in == null) throw new IOException("unreadable");
                return fromPng(readAll(in));
            }
        } catch (JSONException | RuntimeException ex) {
            throw new IOException("damaged", ex);
        }
    }

    /**
     * Writes {@code o} as a picture named "{@code name}.leios.png" in Pictures/Leios on
     * {@code volume} (this TV, or a USB drive's volume name). Returns where, or null.
     */
    static Uri export(Context c, JSONObject o, String name, String volume, Bitmap preview) {
        // Saving into a folder of the shared Pictures needs Android 10; volumes() is empty before.
        if (!Media.volumes()) return null;
        try {
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            preview.compress(Bitmap.CompressFormat.PNG, 100, png);
            byte[] bytes = withChunk(png.toByteArray(), gzip(o.toString().getBytes(StandardCharsets.UTF_8)));
            ContentResolver cr = c.getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, name + SUFFIX);
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            v.put(MediaStore.Images.Media.RELATIVE_PATH, FOLDER);
            v.put(MediaStore.Images.Media.IS_PENDING, 1);
            Uri uri = cr.insert(MediaStore.Images.Media.getContentUri(volume), v);
            if (uri == null) return null;
            try (OutputStream out = cr.openOutputStream(uri)) {
                if (out == null) return null;
                out.write(bytes);
            }
            v.clear();
            v.put(MediaStore.Images.Media.IS_PENDING, 0);
            cr.update(uri, v, null, null);
            return uri;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Where exports can go: this TV, and any USB drive that's plugged in. None on Android 9, where
     * an app can't save into shared Pictures (automatic backups still work there).
     */
    static List<String> volumes(Context c) {
        if (!Media.volumes()) return new ArrayList<>();
        List<String> v = new ArrayList<>(MediaStore.getExternalVolumeNames(c));
        v.remove(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        v.add(0, MediaStore.VOLUME_EXTERNAL_PRIMARY);
        return v;
    }

    static String when(long time) {
        return new SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(new Date(time));
    }

    static String stamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.ROOT).format(new Date());
    }

    // PNG: signature, then chunks of [length][type][data][crc]. Ours goes right after IHDR.

    static byte[] withChunk(byte[] png, byte[] data) {
        int ihdrEnd = 8 + 4 + 4 + 13 + 4;
        ByteArrayOutputStream out = new ByteArrayOutputStream(png.length + data.length + 12);
        out.write(png, 0, ihdrEnd);
        writeInt(out, data.length);
        CRC32 crc = new CRC32();
        crc.update(CHUNK);
        crc.update(data);
        out.write(CHUNK, 0, 4);
        out.write(data, 0, data.length);
        writeInt(out, (int) crc.getValue());
        out.write(png, ihdrEnd, png.length - ihdrEnd);
        return out.toByteArray();
    }

    static JSONObject fromPng(byte[] png) throws IOException, JSONException {
        if (png.length < 8 || png[1] != 'P' || png[2] != 'N' || png[3] != 'G') throw new IOException("not a PNG");
        int i = 8;
        while (i + 12 <= png.length) {
            int len = readInt(png, i);
            if (len < 0 || i + 12 + len > png.length) break;
            if (png[i + 4] == CHUNK[0] && png[i + 5] == CHUNK[1] && png[i + 6] == CHUNK[2] && png[i + 7] == CHUNK[3]) {
                CRC32 crc = new CRC32();
                crc.update(png, i + 4, 4 + len);
                if ((int) crc.getValue() != readInt(png, i + 8 + len)) throw new IOException("damaged");
                byte[] data = Arrays.copyOfRange(png, i + 8, i + 8 + len);
                return new JSONObject(new String(gunzip(data), StandardCharsets.UTF_8));
            }
            i += 12 + len;
        }
        throw new IOException("no settings in this picture");
    }

    private static void writeInt(ByteArrayOutputStream out, int v) {
        out.write(v >>> 24);
        out.write(v >>> 16);
        out.write(v >>> 8);
        out.write(v);
    }

    private static int readInt(byte[] b, int i) {
        return ((b[i] & 0xFF) << 24) | ((b[i + 1] & 0xFF) << 16) | ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
    }

    static byte[] gzip(byte[] in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream z = new GZIPOutputStream(out)) {
            z.write(in);
        }
        return out.toByteArray();
    }

    private static byte[] gunzip(byte[] in) throws IOException {
        try (GZIPInputStream z = new GZIPInputStream(new java.io.ByteArrayInputStream(in))) {
            return readAll(z);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[64 << 10];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static String readText(InputStream in) throws IOException {
        try (InputStream s = in) {
            return new String(readAll(s), StandardCharsets.UTF_8);
        }
    }
}
