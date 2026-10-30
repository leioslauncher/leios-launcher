package tv.leios.launcher;

import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Your rows of apps on Home, in order, saved as JSON in prefs ("categories").
 * "Apps" is always there and holds every app that isn't docked, hidden or in another category, so a
 * fresh install (or an update from before categories) looks exactly as Home did before.
 * An app belongs to at most one category; for "Apps" the list is only its custom order.
 */
final class Categories {

    static final String APPS = "apps";
    static final String DOCK = "dock";
    static final String SORT_CUSTOM = "custom", SORT_AZ = "az", SORT_RECENT = "recent", SORT_USED = "used";
    static final String[] SORTS = {SORT_CUSTOM, SORT_AZ, SORT_RECENT, SORT_USED};
    static final String[] SORT_NAMES = {"Your order", "A–Z", "Recently used", "Most used"};

    static final class Cat {
        String id, name, sort = SORT_AZ;
        boolean singleRow;
        final List<String> apps = new ArrayList<>();

        boolean isApps() {
            return APPS.equals(id);
        }

        String sortName() {
            return SORT_NAMES[Math.max(0, Arrays.asList(SORTS).indexOf(sort))];
        }
    }

    final List<Cat> list = new ArrayList<>();
    /** An extra row at the end with every app, A–Z. */
    boolean allAppsRow;
    /** A row of HDMI input tiles. */
    boolean inputsRow;
    boolean showEmpty;
    /** Where Home puts focus when you press Home: {@link #DOCK} or a category id. */
    String startOn = DOCK;

    static Categories load(SharedPreferences p) {
        Categories c = new Categories();
        String json = p.getString("categories", null);
        if (json != null) {
            try {
                JSONObject o = new JSONObject(json);
                c.allAppsRow = o.optBoolean("allApps", false);
                c.inputsRow = o.optBoolean("inputs", false);
                c.showEmpty = o.optBoolean("showEmpty", false);
                c.startOn = o.optString("startOn", DOCK);
                JSONArray arr = o.getJSONArray("list");
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject j = arr.getJSONObject(i);
                    Cat cat = new Cat();
                    cat.id = j.getString("id");
                    cat.name = j.optString("name", "Apps");
                    cat.sort = j.optString("sort", SORT_AZ);
                    cat.singleRow = j.optBoolean("single", false);
                    JSONArray apps = j.optJSONArray("apps");
                    if (apps != null) for (int k = 0; k < apps.length(); k++) cat.apps.add(apps.getString(k));
                    if (c.find(cat.id) == null) c.list.add(cat);
                }
            } catch (JSONException e) {
                c.list.clear();
            }
        }
        if (c.find(APPS) == null) {
            // First run, or Home from before categories: the old sort and custom order become "Apps".
            Cat a = new Cat();
            a.id = APPS;
            a.name = "Apps";
            String old = p.getString("sort", SORT_AZ);
            a.sort = "used".equals(old) ? SORT_USED : "custom".equals(old) ? SORT_CUSTOM : SORT_AZ;
            for (String pkg : p.getString("grid_order", "").split(",")) if (!pkg.isEmpty()) a.apps.add(pkg);
            c.list.add(a);
        }
        if (!DOCK.equals(c.startOn) && c.find(c.startOn) == null) c.startOn = DOCK;
        return c;
    }

    void save(SharedPreferences p) {
        try {
            JSONObject o = new JSONObject();
            o.put("allApps", allAppsRow);
            o.put("inputs", inputsRow);
            o.put("showEmpty", showEmpty);
            o.put("startOn", startOn);
            JSONArray arr = new JSONArray();
            for (Cat cat : list) {
                JSONObject j = new JSONObject();
                j.put("id", cat.id);
                j.put("name", cat.name);
                j.put("sort", cat.sort);
                j.put("single", cat.singleRow);
                j.put("apps", new JSONArray(cat.apps));
                arr.put(j);
            }
            o.put("list", arr);
            p.edit().putString("categories", o.toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    Cat find(String id) {
        for (Cat c : list) if (c.id.equals(id)) return c;
        return null;
    }

    /** The category an app was put in, or "Apps". */
    Cat of(String pkg) {
        for (Cat c : list) if (!c.isApps() && c.apps.contains(pkg)) return c;
        return find(APPS);
    }

    Cat create(String name) {
        Cat c = new Cat();
        c.id = "c" + Long.toString(System.currentTimeMillis(), 36);
        c.name = name;
        c.sort = SORT_CUSTOM;
        // New rows go above "Apps", which stays the catch-all at the end.
        int at = list.indexOf(find(APPS));
        list.add(at < 0 ? list.size() : at, c);
        return c;
    }

    /** Takes the app out of every other category and adds it to {@code to} ("Apps" = no category). */
    void moveApp(String pkg, Cat to, int at) {
        for (Cat c : list) if (!c.isApps() && c != to) c.apps.remove(pkg);
        if (to.isApps()) {
            if (at >= 0) {
                to.apps.remove(pkg);
                to.apps.add(Math.min(at, to.apps.size()), pkg);
            }
            return;
        }
        to.apps.remove(pkg);
        to.apps.add(at < 0 ? to.apps.size() : Math.min(at, to.apps.size()), pkg);
    }

    /**
     * Puts a row's apps in its order. {@code az} is every app A–Z (ties in the other orders stay
     * A–Z); {@code used} and {@code recent} give each app's open count and last-opened time.
     */
    static <T> void sort(List<T> apps, Cat cat, java.util.function.Function<T, String> pkg, List<T> az,
                         java.util.function.ToIntFunction<T> used, java.util.function.ToLongFunction<T> recent) {
        String sort = cat == null ? SORT_AZ : cat.sort;
        apps.sort(java.util.Comparator.comparingInt(az::indexOf));
        if (SORT_USED.equals(sort)) {
            apps.sort((x, y) -> Integer.compare(used.applyAsInt(y), used.applyAsInt(x)));
        } else if (SORT_RECENT.equals(sort)) {
            apps.sort((x, y) -> Long.compare(recent.applyAsLong(y), recent.applyAsLong(x)));
        } else if (SORT_CUSTOM.equals(sort)) {
            // Your order; apps not placed yet go at the end, A–Z.
            List<String> order = cat.apps;
            apps.sort(java.util.Comparator.comparingInt(a -> {
                int i = order.indexOf(pkg.apply(a));
                return i < 0 ? Integer.MAX_VALUE : i;
            }));
        }
    }

    void delete(Cat c) {
        if (c.isApps()) return;
        list.remove(c);
        if (c.id.equals(startOn)) startOn = DOCK;
    }

    /** "Movies, Games" for a toast or a summary. */
    String names() {
        List<String> n = new ArrayList<>();
        for (Cat c : list) n.add(c.name);
        return TextUtils.join(", ", n);
    }
}
