package tv.leios.launcher;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Current weather and city search from Open-Meteo (free, no key). Call off the main thread. */
final class Weather {

    static final class Now {
        double temp;
        int code;
        boolean day;
        /** Today's sunrise and sunset, Unix seconds (0 if missing); used by automatic light mode. */
        long sunrise, sunset;
    }

    static final class Place {
        String name, detail;
        double lat, lon;
    }

    static Now fetch(double lat, double lon, boolean fahrenheit) throws IOException, JSONException {
        // Two decimals is about 1 km: plenty for weather, no more precise than a city.
        String url = String.format(Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f"
                        + "&current=temperature_2m,weather_code,is_day&daily=sunrise,sunset"
                        + "&timezone=auto&timeformat=unixtime&forecast_days=1%s",
                lat, lon, fahrenheit ? "&temperature_unit=fahrenheit" : "");
        JSONObject root = new JSONObject(get(url));
        JSONObject cur = root.getJSONObject("current");
        Now n = new Now();
        n.temp = cur.getDouble("temperature_2m");
        n.code = cur.getInt("weather_code");
        n.day = cur.optInt("is_day", 1) == 1;
        JSONObject daily = root.optJSONObject("daily");
        if (daily != null) {
            JSONArray rise = daily.optJSONArray("sunrise"), set = daily.optJSONArray("sunset");
            if (rise != null && rise.length() > 0) n.sunrise = rise.optLong(0);
            if (set != null && set.length() > 0) n.sunset = set.optLong(0);
        }
        return n;
    }

    static List<Place> search(String query) throws IOException, JSONException {
        String url = "https://geocoding-api.open-meteo.com/v1/search?count=8&language=en&format=json&name="
                + URLEncoder.encode(query.trim(), "UTF-8");
        JSONArray results = new JSONObject(get(url)).optJSONArray("results");
        List<Place> places = new ArrayList<>();
        for (int i = 0; results != null && i < results.length(); i++) {
            JSONObject r = results.getJSONObject(i);
            Place p = new Place();
            p.name = r.getString("name");
            String admin = r.optString("admin1", ""), country = r.optString("country", "");
            p.detail = admin.isEmpty() ? country : admin + ", " + country;
            p.lat = r.getDouble("latitude");
            p.lon = r.getDouble("longitude");
            places.add(p);
        }
        return places;
    }

    /** WMO weather codes. */
    static String describe(int code) {
        if (code == 0) return "Clear";
        if (code <= 2) return "Partly cloudy";
        if (code == 3) return "Cloudy";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if (code >= 61 && code <= 67) return "Rain";
        if (code >= 71 && code <= 77) return "Snow";
        if (code >= 80 && code <= 82) return "Showers";
        if (code == 85 || code == 86) return "Snow showers";
        if (code >= 95) return "Thunderstorm";
        return "";
    }

    static String emoji(int code, boolean day) {
        if (code == 0) return day ? "☀️" : "🌙";
        if (code <= 2) return day ? "⛅" : "☁️";
        if (code == 3) return "☁️";
        if (code == 45 || code == 48) return "🌫️";
        if (code >= 51 && code <= 67) return "🌧️";
        if (code >= 71 && code <= 77) return "❄️";
        if (code >= 80 && code <= 82) return "🌦️";
        if (code == 85 || code == 86) return "🌨️";
        if (code >= 95) return "⛈️";
        return "";
    }

    private static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        } finally {
            c.disconnect();
        }
    }
}
