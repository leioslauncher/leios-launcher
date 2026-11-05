package tv.leios.launcher;

import android.app.SearchManager;
import android.content.Intent;
import android.net.Uri;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which TV app plays a streaming service, and how to send a title to it. TMDB names the services
 * (from JustWatch) but gives no app's own ID for a title, so the best any app can get is its own
 * search with the title filled in. Each app's way was read from what it declares to Android and
 * checked on a JMGO N3 4K; an app with no way in just opens, with a toast saying what to search for.
 */
final class AppLinks {

    private AppLinks() {
    }

    /** How a title reaches an app. */
    static final int OPEN = 0, SEARCH_ACTION = 1, SEARCH_LINK = 2;

    static final class App {
        final String pkg, name;
        final int how;
        /** For SEARCH_LINK: the address, with %s where the title goes (URL-encoded). */
        final String link;
        /** Lower-case starts of the service names TMDB uses for it. */
        final String[] services;

        App(String pkg, String name, int how, String link, String... services) {
            this.pkg = pkg;
            this.name = name;
            this.how = how;
            this.link = link;
            this.services = services;
        }
    }

    static final App[] APPS = {
            new App("com.netflix.ninja", "Netflix", OPEN, null, "netflix"),
            new App("com.google.android.youtube.tv", "YouTube", SEARCH_LINK,
                    "https://www.youtube.com/results?search_query=%s", "youtube"),
            new App("com.amazon.amazonvideo.livingroom", "Prime Video", OPEN, null, "amazon prime video", "amazon video"),
            new App("com.disney.disneyplus", "Disney+", OPEN, null, "disney plus", "disney+"),
            new App("com.hulu.livingroomplus", "Hulu", SEARCH_ACTION, null, "hulu"),
            new App("com.wbd.stream", "Max", OPEN, null, "max", "hbo max"),
            new App("com.cbs.ott", "Paramount+", OPEN, null, "paramount plus", "paramount+"),
            new App("com.apple.atve.androidtv.appletv", "Apple TV", OPEN, null, "apple tv plus", "apple tv+"),
            new App("com.crunchyroll.crunchyroid", "Crunchyroll", SEARCH_ACTION, null, "crunchyroll"),
            new App("com.tubitv", "Tubi", SEARCH_ACTION, null, "tubi"),
            new App("com.peacocktv.peacockandroid", "Peacock", OPEN, null, "peacock"),
    };

    static App byPackage(String pkg) {
        for (App a : APPS) if (a.pkg.equals(pkg)) return a;
        return null;
    }

    /**
     * The app that plays a TMDB service name, or null. A channel sold through another store plays in
     * that store's app ("Paramount Plus Amazon Channel" is in Prime Video); "with Ads" and "Premium"
     * plans are the same app.
     */
    static App forService(String serviceName) {
        String s = SearchRank.normalize(serviceName);
        if (s.isEmpty()) return null;
        if (s.endsWith("amazon channel")) return byPackage("com.amazon.amazonvideo.livingroom");
        if (s.endsWith("apple tv channel")) return byPackage("com.apple.atve.androidtv.appletv");
        // Other channels, and YouTube TV (its own app), aren't in any app below.
        if (s.contains("channel") || s.startsWith("youtube tv")) return null;
        App best = null;
        int bestLen = 0;
        for (App a : APPS) {
            for (String start : a.services) {
                String n = SearchRank.normalize(start);
                // A whole-word start: "max" is Max, "maxdome" isn't.
                boolean match = s.equals(n) || s.startsWith(n + " ");
                if (match && n.length() > bestLen) {
                    best = a;
                    bestLen = n.length();
                }
            }
        }
        return best;
    }

    /**
     * Your apps that stream a title: the services TMDB lists, matched to installed apps you can open,
     * most-used first (ties keep TMDB's order).
     */
    static List<App> yourApps(List<String> services, Set<String> openable, Map<String, Integer> uses) {
        Set<App> found = new LinkedHashSet<>();
        for (String s : services) {
            App a = forService(s);
            if (a != null && openable.contains(a.pkg)) found.add(a);
        }
        List<App> list = new ArrayList<>(found);
        list.sort(Comparator.comparingInt((App a) -> -uses.getOrDefault(a.pkg, 0)));
        return list;
    }

    /** The intent that takes {@code title} to {@code app}, or null when the app can only be opened. */
    static Intent searchIntent(App app, String title) {
        switch (app.how) {
            case SEARCH_ACTION:
                return new Intent(Intent.ACTION_SEARCH).setPackage(app.pkg)
                        .putExtra(SearchManager.QUERY, title)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            case SEARCH_LINK:
                return new Intent(Intent.ACTION_VIEW, Uri.parse(String.format(app.link, Uri.encode(title))))
                        .setPackage(app.pkg)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            default:
                return null;
        }
    }
}
