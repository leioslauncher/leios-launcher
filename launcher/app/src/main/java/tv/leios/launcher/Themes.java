package tv.leios.launcher;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A theme is the whole look and nothing else: background (and its photo), adjustments, light or
 * dark, font, clock, top bar, tiles and layout. Not your apps, categories, weather city or PIN.
 * Saved themes live in app storage; built-in ones are below; themes travel as Backups pictures.
 */
final class Themes {

    private Themes() {
    }

    static final Set<String> KEYS = new HashSet<>(Arrays.asList(
            "appearance", "bg", "bg_light", "slide_seconds", "slide_folder", "bg_video",
            "adj_b", "adj_c", "adj_s", "adj_blur", "accent_auto",
            "font", "text_scale", "clock_fmt", "clock_size", "clock_pos", "clock_date", "greeting", "clock_shadow",
            "bar_search", "bar_inputs", "bar_wifi", "bar_settings", "bar_size",
            "tile_corners", "focus_color", "icon_bg", "tile_style", "labels", "glow",
            "columns", "gap", "row_gap", "margin"));

    /** Built in: name, then settings (anything not listed goes back to its default). */
    static final String[][] PRESETS = {
            {"Studio", "{\"appearance\":{\"s\":\"dark\"},\"bg\":{\"s\":\"shape:0\"},\"font\":{\"s\":\"Outfit\"}}"},
            {"Daylight", "{\"appearance\":{\"s\":\"light\"},\"bg_light\":{\"s\":\"shape:1\"},\"font\":{\"s\":\"Inter\"},"
                    + "\"clock_pos\":{\"s\":\"centre\"},\"tile_corners\":{\"i\":2},\"focus_color\":{\"i\":1},\"icon_bg\":{\"i\":1}}"},
            {"Cinema", "{\"appearance\":{\"s\":\"dark\"},\"bg\":{\"s\":\"shape:3\"},\"font\":{\"s\":\"Lexend\"},"
                    + "\"clock_size\":{\"i\":0},\"labels\":{\"i\":2},\"glow\":{\"b\":false},\"tile_corners\":{\"i\":0},"
                    + "\"focus_color\":{\"i\":2},\"icon_bg\":{\"i\":2},\"bar_size\":{\"i\":1}}"},
    };

    static JSONObject preset(int i) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("kind", "theme");
        o.put("version", 1);
        o.put("name", PRESETS[i][0]);
        o.put("focus", new JSONObject(PRESETS[i][1]));
        return o;
    }

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "themes");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    /** Your saved themes, A–Z by name. */
    static List<File> saved(Context c) {
        File[] f = dir(c).listFiles((d, n) -> n.startsWith("theme-") && n.endsWith(".json"));
        List<File> out = new ArrayList<>(f == null ? new ArrayList<>() : Arrays.asList(f));
        out.sort((a, b) -> name(a).compareToIgnoreCase(name(b)));
        return out;
    }

    /** The name it was saved with, or null (the caller shows the translated "Theme"). */
    static String name(File f) {
        try {
            JSONObject o = load(f);
            return o.has("name") ? o.optString("name") : null;
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    static JSONObject load(File f) throws IOException, JSONException {
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int off = 0;
            for (int n; off < b.length && (n = in.read(b, off, b.length - off)) > 0; ) off += n;
            return new JSONObject(new String(b, StandardCharsets.UTF_8));
        }
    }

    /** Saves the current look as {@code name} (or as the one-step undo, with name null). */
    static File save(Context c, String name) throws IOException, JSONException {
        JSONObject o = Backups.subset(c, KEYS, true);
        if (name != null) o.put("name", name);
        File f = new File(dir(c), name == null ? "undo.json" : "theme-" + System.currentTimeMillis() + ".json");
        write(f, o);
        return f;
    }

    static void write(File f, JSONObject o) throws IOException {
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(o.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    static File undo(Context c) {
        return new File(dir(c), "undo.json");
    }

    /** Remembers the look for Undo, then puts the theme in place. The caller restarts Home. */
    static void apply(Context c, JSONObject theme) throws IOException, JSONException {
        save(c, null);
        Backups.apply(c, theme, KEYS);
    }
}
