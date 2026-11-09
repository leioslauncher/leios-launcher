package tv.leios.launcher;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Movies and shows from TMDB, only when online title search is on. Sends nothing but the search
 * text, the language and the region; no account, no device details. The key comes from the build
 * (BuildConfig.TMDB_KEY) and is never logged or shown. Answers are cached on this TV for a day
 * (posters for a month, far inside TMDB's six-month limit), so typing the same thing again sends
 * nothing. Call everything here off the main thread.
 */
final class Titles {

    private Titles() {
    }

    static final class Title {
        final int id;
        final boolean tv;
        final String name, year, poster;
        /** TMDB's popularity score and vote count, for putting well-known titles first. */
        double popularity;
        int votes;

        Title(int id, boolean tv, String name, String year, String poster) {
            this.id = id;
            this.tv = tv;
            this.name = name;
            this.year = year;
            this.poster = poster;
        }
    }

    /**
     * TMDB orders search results mostly by how closely names match, so little-known titles with
     * similar names come before famous ones ("Severa" found Severance only far down). Re-ordered
     * here: first by how well the name matches what was typed (a match at the start beats one in
     * the middle), then by how well known it is. Pure, so it's unit-tested.
     */
    static List<Title> rank(String query, List<Title> titles, int max) {
        SearchRank.Prepared q = SearchRank.prepare(query);
        List<Title> sorted = new ArrayList<>(titles);
        java.util.Map<Title, Integer> tier = new java.util.HashMap<>();
        for (Title t : titles) {
            int s = SearchRank.score(q, SearchRank.prepare(t.name));
            // Levels: the name starts with it (prefix scores fall a little with length, down to
            // PREFIX - 99, all of them above WORD_START), a word in it does, it's in there somewhere.
            tier.put(t, s > SearchRank.WORD_START ? 3 : s >= SearchRank.ALL_WORDS ? 2 : s > 0 ? 1 : 0);
        }
        sorted.sort((a, b) -> {
            int byTier = Integer.compare(tier.get(b), tier.get(a));
            return byTier != 0 ? byTier : Double.compare(fame(b), fame(a));
        });
        return sorted.size() > max ? new ArrayList<>(sorted.subList(0, max)) : sorted;
    }

    /** How well known: popularity, weighted up by how many people rated it. */
    static double fame(Title t) {
        return (t.popularity + 1) * Math.log10(10 + Math.max(0, t.votes));
    }

    /** TMDB said to slow down, or the network failed: the caller shows a calm message. */
    static final class Unavailable extends IOException {
        final boolean busy;

        Unavailable(String why, boolean busy) {
            super(why);
            this.busy = busy;
        }
    }

    private static final String API = "https://api.themoviedb.org/3/";
    private static final String IMAGES = "https://image.tmdb.org/t/p/w154";
    private static final long DAY = 24 * 3600_000L;
    private static final long SEARCH_TTL = DAY, SERVICES_TTL = DAY, POSTER_TTL = 30 * DAY;
    /** Never faster than this between requests (TMDB allows about 40 a second). */
    private static final long GAP_MS = 60;

    private static final Object lock = new Object();
    private static final TtlCache<String> memory = new TtlCache<>(64, DAY);
    private static long lastRequest, busyUntil;

    static boolean hasKey() {
        return !BuildConfig.TMDB_KEY.isEmpty();
    }

    /** Movies and shows matching {@code query}, most popular first (TMDB's order), at most {@code max}. */
    static List<Title> search(Context c, String query, String language, String region, int max) throws IOException {
        String path = "search/multi?include_adult=false&page=1&query=" + enc(query)
                + "&language=" + enc(language) + "&region=" + enc(region);
        List<Title> out = new ArrayList<>();
        try {
            JSONArray results = new JSONObject(getJson(c, path, SEARCH_TTL)).optJSONArray("results");
            // The whole first page (20), so ranking can lift a famous title from far down it.
            for (int i = 0; results != null && i < results.length(); i++) {
                JSONObject r = results.getJSONObject(i);
                String type = r.optString("media_type");
                boolean tv = "tv".equals(type);
                if (!tv && !"movie".equals(type)) continue;
                String name = r.optString(tv ? "name" : "title", "");
                if (name.isEmpty()) continue;
                String date = r.optString(tv ? "first_air_date" : "release_date", "");
                String poster = r.isNull("poster_path") ? "" : r.optString("poster_path", "");
                Title t = new Title(r.getInt("id"), tv, name, date.length() >= 4 ? date.substring(0, 4) : "", poster);
                t.popularity = r.optDouble("popularity", 0);
                t.votes = r.optInt("vote_count", 0);
                out.add(t);
            }
        } catch (JSONException e) {
            throw new Unavailable("unexpected answer", false);
        }
        return rank(query, out, max);
    }

    /** A service that has a title: in a subscription (or free), or only to rent or buy. */
    static final class Offer {
        final String service;
        final boolean rent;

        Offer(String service, boolean rent) {
            this.service = service;
            this.rent = rent;
        }
    }

    /**
     * The services that have a title in {@code region}: first those you watch it on with a
     * subscription, free or with ads, then those that only rent or sell it (YouTube often does).
     */
    static List<Offer> services(Context c, Title t, String region) throws IOException {
        String path = (t.tv ? "tv/" : "movie/") + t.id + "/watch/providers";
        List<Offer> out = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        try {
            JSONObject all = new JSONObject(getJson(c, path, SERVICES_TTL)).optJSONObject("results");
            JSONObject here = all == null ? null : all.optJSONObject(region.toUpperCase(Locale.ROOT));
            if (here == null) return out;
            for (String kind : new String[] {"flatrate", "free", "ads", "rent", "buy"}) {
                JSONArray list = here.optJSONArray(kind);
                boolean rent = kind.equals("rent") || kind.equals("buy");
                for (int i = 0; list != null && i < list.length(); i++) {
                    String name = list.getJSONObject(i).optString("provider_name", "");
                    if (name.isEmpty() || seen.contains(name)) continue;
                    seen.add(name);
                    out.add(new Offer(name, rent));
                }
            }
        } catch (JSONException e) {
            throw new Unavailable("unexpected answer", false);
        }
        return out;
    }

    /** Wikidata properties that hold a streaming app's own ID for a title (see AppLinks). */
    static final String[] LINK_PROPERTIES = {"P1874", "P9586", "P9751", "P8298", "P8055", "P6466", "P6467",
            "P7595", "P7596"};
    private static final long LINKS_TTL = 30 * DAY;
    private static final String WIKIDATA = "https://www.wikidata.org/w/api.php?action=wbgetentities&props=claims&format=json&ids=";

    /**
     * Each app's own ID for a title (Netflix's, Apple TV's, Max's, Prime Video's…), from Wikidata,
     * keyed by Wikidata property. Only asked when you pick a title, and only with the title's ID
     * numbers: TMDB's ID goes to TMDB, the Wikidata ID it returns goes to Wikidata. Nothing you typed.
     * Empty when Wikidata doesn't know the title.
     */
    static java.util.Map<String, String> appIds(Context c, Title t) throws IOException {
        java.util.Map<String, String> out = new java.util.HashMap<>();
        try {
            JSONObject ext = new JSONObject(getJson(c, (t.tv ? "tv/" : "movie/") + t.id + "/external_ids", LINKS_TTL));
            String q = ext.optString("wikidata_id", "");
            if (!q.matches("Q\\d+")) return out;
            JSONObject claims = new JSONObject(getOther(c, WIKIDATA + q, LINKS_TTL)).getJSONObject("entities")
                    .getJSONObject(q).optJSONObject("claims");
            if (claims == null) return out;
            for (String p : LINK_PROPERTIES) {
                JSONArray list = claims.optJSONArray(p);
                if (list == null || list.length() == 0) continue;
                JSONObject value = list.getJSONObject(0).optJSONObject("mainsnak");
                value = value == null ? null : value.optJSONObject("datavalue");
                String v = value == null ? "" : value.optString("value", "");
                if (!v.isEmpty()) out.put(p, v);
            }
        } catch (JSONException e) {
            throw new Unavailable("unexpected answer", false);
        }
        return out;
    }

    private static final long CATALOG_TTL = 7 * DAY;

    /**
     * Well-known movies and shows, kept on the TV for a week: the 300 shows and 200 movies the most
     * people have rated on TMDB (a steadier sign of "well known" than daily popularity, which
     * missed The Boys and Stranger Things), plus what's popular now. About 500 titles from 29
     * requests a week. TMDB's search only matches whole words ("the bo" never finds The Boys),
     * so Leios also matches the start of any word against this list itself. Sends nothing typed.
     */
    static List<Title> catalog(Context c, String language) throws IOException {
        List<Title> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        IOException failed = null;
        String[] lists = {"discover/tv?sort_by=vote_count.desc&include_adult=false", "discover/movie?sort_by=vote_count.desc&include_adult=false",
                "tv/popular?x=1", "movie/popular?x=1"};
        int[] pages = {15, 10, 2, 2};
        for (int l = 0; l < lists.length; l++) {
            String list = lists[l];
            boolean tv = list.contains("tv");
            for (int page = 1; page <= pages[l]; page++) {
                try {
                    JSONArray results = new JSONObject(getJson(c, list + "&page=" + page + "&language=" + enc(language),
                            CATALOG_TTL)).optJSONArray("results");
                    for (int i = 0; results != null && i < results.length(); i++) {
                        Title t = parse(results.getJSONObject(i), tv);
                        if (t != null && seen.add((tv ? "t" : "m") + t.id)) out.add(t);
                    }
                } catch (JSONException e) {
                    failed = new Unavailable("unexpected answer", false);
                } catch (IOException e) {
                    failed = e;
                }
            }
        }
        if (out.isEmpty() && failed != null) throw failed;
        return out;
    }

    /** One result from a TMDB list (which says movie or show only in multi-search). */
    private static Title parse(JSONObject r, boolean tv) {
        String name = r.optString(tv ? "name" : "title", "");
        if (name.isEmpty()) return null;
        String date = r.optString(tv ? "first_air_date" : "release_date", "");
        String poster = r.isNull("poster_path") ? "" : r.optString("poster_path", "");
        Title t = new Title(r.optInt("id"), tv, name, date.length() >= 4 ? date.substring(0, 4) : "", poster);
        t.popularity = r.optDouble("popularity", 0);
        t.votes = r.optInt("vote_count", 0);
        return t;
    }

    /**
     * TMDB's search results together with the well-known titles whose name has a word starting with
     * what's typed, no repeats, ranked by {@link #rank}.
     */
    static List<Title> merge(String query, List<Title> searched, List<Title> catalog, int max) {
        List<Title> all = new ArrayList<>(searched);
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Title t : searched) seen.add((t.tv ? "t" : "m") + t.id);
        SearchRank.Prepared q = SearchRank.prepare(query);
        if (catalog != null) {
            for (Title t : catalog) {
                if (seen.contains((t.tv ? "t" : "m") + t.id)) continue;
                // Only good matches from the list: the name or one of its words starts with it.
                if (SearchRank.score(q, SearchRank.prepare(t.name)) >= SearchRank.ALL_WORDS) all.add(t);
            }
        }
        return rank(query, all, max);
    }

    /**
     * Popular movie and show names this week, most popular first, for completing what you type.
     * One request a day (cached), only with online search on; nothing typed is sent.
     */
    static List<String> popular(Context c, String language) throws IOException {
        List<String> out = new ArrayList<>();
        try {
            for (int page = 1; page <= 2; page++) {
                JSONArray results = new JSONObject(getJson(c, "trending/all/week?page=" + page + "&language="
                        + enc(language), DAY)).optJSONArray("results");
                for (int i = 0; results != null && i < results.length(); i++) {
                    JSONObject r = results.getJSONObject(i);
                    String type = r.optString("media_type");
                    if (!"tv".equals(type) && !"movie".equals(type)) continue;
                    String name = r.optString("tv".equals(type) ? "name" : "title", "");
                    if (!name.isEmpty() && !out.contains(name)) out.add(name);
                }
            }
        } catch (JSONException e) {
            throw new Unavailable("unexpected answer", false);
        }
        return out;
    }

    /** A small poster, or null. */
    static byte[] poster(Context c, String posterPath) {
        if (posterPath == null || posterPath.isEmpty()) return null;
        File f = cacheFile(c, "poster" + posterPath);
        byte[] cached = readFresh(f, POSTER_TTL);
        if (cached != null) return cached;
        try {
            byte[] b = fetch(IMAGES + posterPath, false);
            write(f, b);
            return b;
        } catch (IOException e) {
            return null;
        }
    }

    /** Forget everything cached (Search & privacy, or online search turned off). */
    static void clearCache(Context c) {
        synchronized (lock) {
            memory.clear();
        }
        File[] files = new File(c.getCacheDir(), "tmdb").listFiles();
        if (files != null) for (File f : files) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }

    // ---- requests -------------------------------------------------------------------------

    private static String getJson(Context c, String path, long ttl) throws IOException {
        long now = System.currentTimeMillis();
        synchronized (lock) {
            String m = memory.get(path, now);
            if (m != null) return m;
        }
        File f = cacheFile(c, path);
        byte[] cached = readFresh(f, ttl);
        if (cached != null) {
            String s = new String(cached, StandardCharsets.UTF_8);
            synchronized (lock) {
                memory.put(path, s, now);
            }
            return s;
        }
        if (!hasKey()) throw new Unavailable("no key", false);
        byte[] body = fetch(API + path, true);
        write(f, body);
        String s = new String(body, StandardCharsets.UTF_8);
        synchronized (lock) {
            memory.put(path, s, System.currentTimeMillis());
        }
        return s;
    }

    /** Like getJson, for a service other than TMDB (Wikidata): no key, cached on disk only. */
    private static String getOther(Context c, String url, long ttl) throws IOException {
        File f = cacheFile(c, url);
        byte[] cached = readFresh(f, ttl);
        if (cached == null) {
            cached = fetch(url, false);
            write(f, cached);
        }
        return new String(cached, StandardCharsets.UTF_8);
    }

    private static byte[] fetch(String address, boolean api) throws IOException {
        synchronized (lock) {
            long now = System.currentTimeMillis();
            if (now < busyUntil) throw new Unavailable("busy", true);
            long wait = lastRequest + GAP_MS - now;
            if (wait > 0) {
                try {
                    lock.wait(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new Unavailable("stopped", false);
                }
            }
            lastRequest = System.currentTimeMillis();
        }
        String key = BuildConfig.TMDB_KEY;
        // A read access token (long) goes in a header; a short API key goes in the address.
        boolean token = key.length() > 40;
        if (api && !token) address += (address.contains("?") ? "&" : "?") + "api_key=" + key;
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(address).openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(8000);
            // Neutral: the app's name only, nothing about this TV or you. Wikimedia asks every client
            // for a way to reach its makers, so Wikidata also gets the project's public address.
            boolean wikidata = address.startsWith("https://www.wikidata.org/");
            conn.setRequestProperty("User-Agent", wikidata
                    ? "LeiosLauncher/" + BuildConfig.VERSION_NAME + " (+https://github.com/leioslauncher/leios-launcher)"
                    : "LeiosLauncher");
            conn.setRequestProperty("Accept", api || wikidata ? "application/json" : "image/*");
            if (api && token) conn.setRequestProperty("Authorization", "Bearer " + key);
            int code = conn.getResponseCode();
            if (code == 429) {
                int after = 10;
                try {
                    after = Math.max(1, Math.min(120, Integer.parseInt(conn.getHeaderField("Retry-After"))));
                } catch (RuntimeException ignored) {
                }
                synchronized (lock) {
                    busyUntil = System.currentTimeMillis() + after * 1000L;
                }
                throw new Unavailable("busy", true);
            }
            if (code != 200) throw new Unavailable("HTTP " + code, false);
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16 << 10];
                for (int n; (n = in.read(buf)) > 0; ) {
                    out.write(buf, 0, n);
                    if (out.size() > (4 << 20)) throw new Unavailable("too large", false);
                }
                return out.toByteArray();
            }
        } catch (Unavailable u) {
            throw u;
        } catch (IOException | RuntimeException e) {
            // Never pass on the original message: it can hold the address, and the address the key.
            throw new Unavailable("network", false);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return "";
        }
    }

    // ---- disk cache: one file per request, named by a hash, so nothing typed shows in file names ----

    private static File cacheFile(Context c, String key) {
        File dir = new File(c.getCacheDir(), "tmdb");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i++) sb.append(String.format(Locale.ROOT, "%02x", h[i]));
            return new File(dir, sb.toString());
        } catch (java.security.NoSuchAlgorithmException e) {
            return new File(dir, Integer.toHexString(key.hashCode()));
        }
    }

    private static byte[] readFresh(File f, long ttl) {
        long age = System.currentTimeMillis() - f.lastModified();
        if (!f.isFile() || age < 0 || age >= ttl) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            return null;
        }
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 << 10];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    private static void write(File f, byte[] b) {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(b);
        } catch (IOException ignored) {
        }
    }
}
