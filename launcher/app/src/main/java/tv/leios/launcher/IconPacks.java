package tv.leios.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Icon packs in the common format: an app that answers one of the launcher-theme intents and ships
 * appfilter.xml (as res/xml or in assets), mapping app components to its drawables. Packs built
 * with the Candybar or Blueprint templates, and most packs made for Nova and similar launchers,
 * work this way. Read-only: nothing in a pack runs.
 */
final class IconPacks {

    private IconPacks() {
    }

    static final String[] ACTIONS = {"org.adw.launcher.THEMES", "com.novalauncher.THEME",
            "com.gau.go.launcherex.theme", "org.adw.launcher.icons.ACTION_PICK_ICON"};

    static final class Pack {
        final String pkg, label;

        Pack(String pkg, String label) {
            this.pkg = pkg;
            this.label = label;
        }
    }

    static List<Pack> installed(Context c) {
        PackageManager pm = c.getPackageManager();
        Map<String, Pack> found = new HashMap<>();
        for (String action : ACTIONS) {
            for (ResolveInfo ri : pm.queryIntentActivities(new Intent(action), PackageManager.GET_META_DATA)) {
                String pkg = ri.activityInfo.packageName;
                if (!found.containsKey(pkg)) {
                    found.put(pkg, new Pack(pkg, String.valueOf(ri.activityInfo.applicationInfo.loadLabel(pm))));
                }
            }
        }
        List<Pack> list = new ArrayList<>(found.values());
        list.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
        return list;
    }

    private static final Map<String, Map<String, String>> filters = new HashMap<>();

    /**
     * component ("pkg/cls") and plain package → drawable name. Cached per pack. Items look like
     * {@code <item component="ComponentInfo{com.app/com.app.Main}" drawable="app" />}.
     */
    static synchronized Map<String, String> appfilter(Context c, String pack) {
        Map<String, String> cached = filters.get(pack);
        if (cached != null) return cached;
        Map<String, String> map = new HashMap<>();
        try {
            Resources res = c.getPackageManager().getResourcesForApplication(pack);
            XmlPullParser parser;
            InputStream asset = null;
            int id = res.getIdentifier("appfilter", "xml", pack);
            if (id != 0) {
                parser = res.getXml(id);
            } else {
                asset = c.createPackageContext(pack, 0).getAssets().open("appfilter.xml");
                parser = XmlPullParserFactory.newInstance().newPullParser();
                parser.setInput(asset, null);
            }
            try {
                parse(parser, map);
            } finally {
                if (asset != null) asset.close();
            }
        } catch (Exception e) {
            // A missing appfilter matches nothing; a broken one keeps what was read before the break.
        }
        filters.put(pack, map);
        return map;
    }

    /** Reads appfilter items into {@code map}; stops (keeping what it has) at the first broken spot. */
    static void parse(XmlPullParser parser, Map<String, String> map) throws Exception {
        for (int ev = parser.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = parser.next()) {
            if (ev != XmlPullParser.START_TAG || !"item".equals(parser.getName())) continue;
            String comp = parser.getAttributeValue(null, "component");
            String drawable = parser.getAttributeValue(null, "drawable");
            if (comp == null || drawable == null || drawable.isEmpty() || !comp.startsWith("ComponentInfo{")) continue;
            String inner = comp.substring("ComponentInfo{".length(), comp.length() - (comp.endsWith("}") ? 1 : 0));
            int slash = inner.indexOf('/');
            if (slash <= 0 || slash == inner.length() - 1) continue;
            String pkg = inner.substring(0, slash);
            String cls = inner.substring(slash + 1);
            if (cls.startsWith(".")) cls = pkg + cls;
            map.put(pkg + "/" + cls, drawable);
            // Any of the app's activities will do when the exact one isn't listed
            // (packs list the phone activity; TV apps often start from another).
            map.putIfAbsent(pkg, drawable);
        }
    }

    /** The pack's icon for an app, or null. */
    static Drawable iconFor(Context c, String pack, String pkg, String cls) {
        Map<String, String> f = appfilter(c, pack);
        String name = f.get(pkg + "/" + cls);
        if (name == null) name = f.get(pkg);
        return name == null ? null : load(c, pack, name);
    }

    static Drawable load(Context c, String pack, String name) {
        try {
            Resources res = c.getPackageManager().getResourcesForApplication(pack);
            int id = res.getIdentifier(name, "drawable", pack);
            if (id == 0) id = res.getIdentifier(name, "mipmap", pack);
            return id == 0 ? null : res.getDrawable(id, null);
        } catch (Exception e) {
            return null;
        }
    }

    /** Every icon in a pack by name (drawable.xml if it has one, else what appfilter uses), A–Z. */
    static List<String> allIcons(Context c, String pack) {
        Set<String> names = new LinkedHashSet<>();
        try {
            Resources res = c.getPackageManager().getResourcesForApplication(pack);
            int id = res.getIdentifier("drawable", "xml", pack);
            if (id != 0) {
                XmlPullParser p = res.getXml(id);
                for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
                    if (ev == XmlPullParser.START_TAG && "item".equals(p.getName())) {
                        String d = p.getAttributeValue(null, "drawable");
                        if (d != null) names.add(d);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        if (names.isEmpty()) names.addAll(appfilter(c, pack).values());
        List<String> list = new ArrayList<>(names);
        list.sort(String::compareToIgnoreCase);
        return list;
    }

    /** Forget cached appfilters (a pack was updated or removed). */
    static synchronized void clear() {
        filters.clear();
    }
}
