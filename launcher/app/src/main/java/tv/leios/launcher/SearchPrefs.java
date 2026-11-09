package tv.leios.launcher;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Search settings (Customization › System › Search & privacy) and the rules for what search may
 * show. Pure Java over SharedPreferences, so it's unit-tested.
 */
final class SearchPrefs {

    private SearchPrefs() {
    }

    /** Online title search: not asked yet, on, off, or not in this build (no TMDB key). */
    static final int UNDECIDED = 0, ON = 1, OFF = 2, UNAVAILABLE = 3;

    static final int RECENT_MAX = 8;

    static int online(SharedPreferences p, boolean hasKey) {
        if (!hasKey) return UNAVAILABLE;
        if (!p.contains("search_online")) return UNDECIDED;
        return p.getBoolean("search_online", false) ? ON : OFF;
    }

    static void setOnline(SharedPreferences p, boolean on) {
        p.edit().putBoolean("search_online", on).apply();
    }

    /** The region chosen in settings, or the TV's own country, or US. */
    static String region(SharedPreferences p, Locale device) {
        String r = p.getString("search_region", "");
        if (r != null && r.length() == 2) return r.toUpperCase(Locale.ROOT);
        String c = device == null ? "" : device.getCountry();
        return c != null && c.length() == 2 ? c.toUpperCase(Locale.ROOT) : "US";
    }

    /** Titles none of your apps have: shown quietly (the default) or hidden. */
    static boolean showElsewhere(SharedPreferences p) {
        return p.getBoolean("search_elsewhere", true);
    }

    static boolean remembersRecent(SharedPreferences p) {
        return p.getBoolean("search_recent_on", true);
    }

    static List<String> recent(SharedPreferences p) {
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(p.getString("search_recent", "[]"));
            for (int i = 0; i < a.length() && out.size() < RECENT_MAX; i++) {
                String s = a.optString(i, "").trim();
                if (!s.isEmpty() && !out.contains(s)) out.add(s);
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    /** Newest first, no repeats (ignoring case), at most {@link #RECENT_MAX}. */
    static void addRecent(SharedPreferences p, String query) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty() || !remembersRecent(p)) return;
        List<String> list = recent(p);
        list.removeIf(s -> s.equalsIgnoreCase(q));
        list.add(0, q);
        while (list.size() > RECENT_MAX) list.remove(list.size() - 1);
        p.edit().putString("search_recent", new JSONArray(list).toString()).apply();
    }

    static void clearRecent(SharedPreferences p) {
        p.edit().remove("search_recent").apply();
    }

    /** May search show this app? Not one you've hidden from Home. Locked apps show (see {@link #appLocked}). */
    static boolean appVisible(String pkg, Set<String> hidden) {
        return !hidden.contains(pkg);
    }

    /**
     * Does this app show as locked in search? Yes for a locked app or one in a locked category while
     * the lock applies ({@code lockActive}: a PIN is set, not just unlocked, in its hours). Opening
     * it asks for the PIN, as on Home, so search is never a way around a lock.
     */
    static boolean appLocked(String pkg, String categoryId, Set<String> lockedApps, Set<String> lockedCats,
                             boolean lockActive) {
        if (!lockActive) return false;
        return lockedApps.contains(pkg) || (categoryId != null && lockedCats.contains(categoryId));
    }

    /** Settings results: none in guest mode, which hides Customize itself. */
    static boolean settingsVisible(boolean guest) {
        return !guest;
    }
}
