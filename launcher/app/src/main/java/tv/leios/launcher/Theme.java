package tv.leios.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;

import java.util.Calendar;

/**
 * Colour tokens for Home, its menus and the ≡ panel, from res/values/colors.xml (dark_* or light_*).
 * "auto" is light between sunrise and sunset in the weather city (fetched with the weather), or
 * 7:00–19:00 when no city is set.
 */
final class Theme {

    static final String DARK = "dark", LIGHT = "light", AUTO = "auto";

    final boolean light;
    final int bg, surface, surfaceRaised, surfaceSoft, stroke;
    final int text, textSecondary, textTertiary, disabled, track;
    /** Over photos: a dimming layer (dark only), the edge gradients behind text, the grid backdrop. */
    final int scrimPhoto, scrimEdge, scrimGrid, shadow;
    final int onAccent, tileFallback;
    /** Not final: with Look › Accent colour › From background they follow the background live. */
    int accent, accentFill;

    private Theme(Resources r, boolean light, SharedPreferences p) {
        this.light = light;
        bg = r.getColor(light ? R.color.light_bg : R.color.dark_bg, null);
        surface = r.getColor(light ? R.color.light_surface : R.color.dark_surface, null);
        surfaceRaised = r.getColor(light ? R.color.light_surface_raised : R.color.dark_surface_raised, null);
        surfaceSoft = r.getColor(light ? R.color.light_surface_soft : R.color.dark_surface_soft, null);
        stroke = r.getColor(light ? R.color.light_stroke : R.color.dark_stroke, null);
        text = r.getColor(light ? R.color.light_text : R.color.dark_text, null);
        textSecondary = r.getColor(light ? R.color.light_text_secondary : R.color.dark_text_secondary, null);
        textTertiary = r.getColor(light ? R.color.light_text_tertiary : R.color.dark_text_tertiary, null);
        disabled = r.getColor(light ? R.color.light_disabled : R.color.dark_disabled, null);
        track = r.getColor(light ? R.color.light_track : R.color.dark_track, null);
        scrimPhoto = r.getColor(light ? R.color.light_scrim_photo : R.color.dark_scrim_photo, null);
        scrimEdge = r.getColor(light ? R.color.light_scrim_edge : R.color.dark_scrim_edge, null);
        scrimGrid = r.getColor(light ? R.color.light_scrim_grid : R.color.dark_scrim_grid, null);
        shadow = r.getColor(light ? R.color.light_shadow : R.color.dark_shadow, null);
        accent = defaultAccent = r.getColor(light ? R.color.accent_fill : R.color.accent, null);
        accentFill = defaultFill = r.getColor(R.color.accent_fill, null);
        onAccent = r.getColor(R.color.on_accent, null);
        tileFallback = r.getColor(R.color.tile_fallback, null);
        if (p.getBoolean("accent_auto", false)) setSeed(p.getInt("accent_seed", 0));
    }

    /** Accent built from a background colour; the default azure if that colour can't stay readable. */
    boolean setSeed(int seed) {
        int[] pair = Wallpaper.accentPair(seed, light, bg);
        int a = pair == null ? defaultAccent : pair[0], f = pair == null ? defaultFill : pair[1];
        if (a == accent && f == accentFill) return false;
        accent = a;
        accentFill = f;
        return true;
    }

    private int defaultAccent, defaultFill;

    static String mode(SharedPreferences p) {
        return p.getString("appearance", DARK);
    }

    static Theme of(Context c) {
        SharedPreferences p = SafePrefs.focus(c);
        return new Theme(c.getResources(), isLight(p), p);
    }

    static boolean isLight(SharedPreferences p) {
        String m = mode(p);
        if (LIGHT.equals(m)) return true;
        if (!AUTO.equals(m)) return false;
        return isDaytime(p);
    }

    /** Compares time of day, so yesterday's sunrise and sunset still work if today's isn't in yet. */
    static boolean isDaytime(SharedPreferences p) {
        int now = minuteOfDay(System.currentTimeMillis());
        long rise = p.getLong("w_sunrise", 0), set = p.getLong("w_sunset", 0);
        int r = rise > 0 ? minuteOfDay(rise * 1000) : 7 * 60;
        int s = set > 0 ? minuteOfDay(set * 1000) : 19 * 60;
        return now >= r && now < s;
    }

    private static int minuteOfDay(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
    }
}
