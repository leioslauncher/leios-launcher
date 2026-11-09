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

        Title(int id, boolean tv, String name, String year, String poster) {
            this.id = id;
            this.tv = tv;
            this.name = name;
            this.year = year;
            this.poster = poster;
        }
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
            for (int i = 0; results != null && i < results.length() && out.size() < max; i++) {
                JSONObject r = results.getJSONObject(i);
                String type = r.optString("media_type");
                boolean tv = "tv".equals(type);
                if (!tv && !"movie".equals(type)) continue;
                String name = r.optString(tv ? "name" : "title", "");
                if (name.isEmpty()) continue;
                String date = r.optString(tv ? "first_air_date" : "release_date", "");
                String poster = r.isNull("poster_path") ? "" : r.optString("poster_path", "");
                out.add(new Title(r.getInt("id"), tv, name, date.length() >= 4 ? date.substring(0, 4) : "", poster));
            }
        } catch (JSONException e) {
            throw new Unavailable("unexpected answer", false);
        }
        return out;
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
