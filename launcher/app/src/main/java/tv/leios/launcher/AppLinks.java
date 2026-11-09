package tv.leios.launcher;

import android.app.SearchManager;
import android.content.Intent;
import android.net.Uri;

import java.util.ArrayList;
import java.util.Comparator;
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
        /**
         * Straight to a title's own page, from the app's ID for it (Wikidata): property and address
         * for shows, then for movies; null where the app has none or none that works.
         */
        String showId, showLink, movieId, movieLink;

        App(String pkg, String name, int how, String link, String... services) {
            this.pkg = pkg;
            this.name = name;
            this.how = how;
            this.link = link;
            this.services = services;
        }

        App titles(String showId, String showLink, String movieId, String movieLink) {
            this.showId = showId;
            this.showLink = showLink;
            this.movieId = movieId;
            this.movieLink = movieLink;
            return this;
        }
    }

    // Tried on a JMGO N3 4K (2026-11): Netflix, Apple TV, Max and Prime Video open the title's page
    // from these links. Hulu's show link starts playing episode 1, so Hulu gets its search instead.
    // Disney+ only reached its general series page with either link style, so it just opens.
    static final App[] APPS = {
            new App("com.netflix.ninja", "Netflix", OPEN, null, "netflix")
                    .titles("P1874", "https://www.netflix.com/title/%s", "P1874", "https://www.netflix.com/title/%s"),
            new App("com.google.android.youtube.tv", "YouTube", SEARCH_LINK,
                    "https://www.youtube.com/results?search_query=%s", "youtube"),
            new App("com.amazon.amazonvideo.livingroom", "Prime Video", OPEN, null, "amazon prime video", "amazon video")
                    .titles("P8055", "https://app.primevideo.com/detail?asin=%s",
                            "P8055", "https://app.primevideo.com/detail?asin=%s"),
            new App("com.disney.disneyplus", "Disney+", OPEN, null, "disney plus", "disney+"),
            new App("com.hulu.livingroomplus", "Hulu", SEARCH_ACTION, null, "hulu"),
            new App("com.wbd.stream", "Max", OPEN, null, "max", "hbo max")
                    .titles("P8298", "https://play.max.com/%s", null, null),
            new App("com.cbs.ott", "Paramount+", OPEN, null, "paramount plus", "paramount+"),
            new App("com.apple.atve.androidtv.appletv", "Apple TV", OPEN, null, "apple tv plus", "apple tv+")
                    .titles("P9751", "https://tv.apple.com/show/title/%s", "P9586", "https://tv.apple.com/movie/title/%s"),
            new App("com.crunchyroll.crunchyroid", "Crunchyroll", SEARCH_ACTION, null, "crunchyroll"),
            new App("com.tubitv", "Tubi", SEARCH_ACTION, null, "tubi"),
            new App("com.peacocktv.peacockandroid", "Peacock", OPEN, null, "peacock"),
    };

    /**
     * The link to a title's own page in {@code app}, from the IDs Wikidata has ({@link Titles#appIds}),
     * or null. Max's IDs are only usable for shows in its current "show/…" form.
     */
    static String titleLink(App app, boolean tv, Map<String, String> ids) {
        String prop = tv ? app.showId : app.movieId, link = tv ? app.showLink : app.movieLink;
        if (prop == null || link == null || ids == null) return null;
        String id = ids.get(prop);
        if (id == null || id.isEmpty()) return null;
        if (app.pkg.equals("com.wbd.stream")) {
            if (!id.matches("show/[0-9a-f-]{36}")) return null;
            return String.format(link, id);
        }
        // IDs are letters, digits, dots, dashes, colons (all safe in an address as they are);
        // anything else isn't one we trust in a link.
        if (!id.matches("[A-Za-z0-9._:-]{1,80}")) return null;
        return String.format(link, id);
    }

    /** One of your apps that has a title: to watch, or only to rent or buy there. */
    static final class Offer {
        final App app;
        final boolean rent;

        Offer(App app, boolean rent) {
            this.app = app;
            this.rent = rent;
        }
    }

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
        List<App> list = new ArrayList<>();
        for (Offer o : yourOffers(toOffers(services), openable, uses)) list.add(o.app);
        return list;
    }

    private static List<Titles.Offer> toOffers(List<String> services) {
        List<Titles.Offer> out = new ArrayList<>();
        for (String s : services) out.add(new Titles.Offer(s, false));
        return out;
    }

    /**
     * Your apps that have a title: ones you can watch it on first, then ones that only rent or sell
     * it, each group most-used first (ties keep TMDB's order). An app with both counts as "watch".
     */
    static List<Offer> yourOffers(List<Titles.Offer> offers, Set<String> openable, Map<String, Integer> uses) {
        Map<App, Boolean> found = new java.util.LinkedHashMap<>();
        for (Titles.Offer o : offers) {
            App a = forService(o.service);
            if (a == null || !openable.contains(a.pkg)) continue;
            Boolean rent = found.get(a);
            found.put(a, rent == null ? o.rent : rent && o.rent);
        }
        List<Offer> list = new ArrayList<>();
        for (Map.Entry<App, Boolean> e : found.entrySet()) list.add(new Offer(e.getKey(), e.getValue()));
        list.sort(Comparator.comparingInt((Offer o) -> o.rent ? 1 : 0)
                .thenComparingInt(o -> -uses.getOrDefault(o.app.pkg, 0)));
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
