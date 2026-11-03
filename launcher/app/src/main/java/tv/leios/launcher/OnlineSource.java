package tv.leios.launcher;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Backgrounds from the internet, only when you pick one (Customize › Background › Photo of the
 * day). A source says what it is and fetches one picture with its credit; Home caches it and
 * shows the credit on screen. Nothing is sent but the request for the picture itself.
 */
interface OnlineSource {

    final class Picture {
        final byte[] bytes;
        final String credit;

        Picture(byte[] bytes, String credit) {
            this.bytes = bytes;
            this.credit = credit;
        }
    }

    String name();

    /** One line for the menu: where the pictures come from. */
    String about();

    /** Today's picture. Call off the main thread. */
    Picture fetch() throws IOException;

    /**
     * Wikimedia Commons' Picture of the Day: a freely licensed photo, chosen by Commons each day.
     * Read from Wikimedia's public feed (no account, no key); the credit names the author and licence.
     */
    final class WikimediaToday implements OnlineSource {

        @Override
        public String name() {
            return "Wikimedia picture of the day";
        }

        @Override
        public String about() {
            return "A freely licensed photo from Wikimedia Commons, new each day.";
        }

        @Override
        public Picture fetch() throws IOException {
            SimpleDateFormat day = new SimpleDateFormat("yyyy/MM/dd", Locale.ROOT);
            day.setTimeZone(TimeZone.getTimeZone("UTC"));
            String feed = new String(get("https://api.wikimedia.org/feed/v1/wikipedia/en/featured/"
                    + day.format(new Date())), StandardCharsets.UTF_8);
            try {
                JSONObject image = new JSONObject(feed).getJSONObject("image");
                String thumb = image.getJSONObject("thumbnail").getString("source");
                int width = image.getJSONObject("image").optInt("width", 0);
                // Commons makes any width from the thumbnail address; 1920 fills the screen.
                String url = thumb.replaceFirst("/\\d+px-", "/1920px-").replaceFirst("\\?.*$", "");
                if (width > 0 && width < 1920) url = image.getJSONObject("image").getString("source").replaceFirst("\\?.*$", "");
                String artist = image.optJSONObject("artist") != null ? image.getJSONObject("artist").optString("text", "") : "";
                String license = image.optJSONObject("license") != null ? image.getJSONObject("license").optString("type", "") : "";
                String credit = "Photo: " + (artist.isEmpty() ? "Wikimedia Commons" : artist.trim() + ", Wikimedia Commons")
                        + (license.isEmpty() ? "" : " (" + license + ")");
                return new Picture(get(url), credit);
            } catch (org.json.JSONException e) {
                throw new IOException("unexpected feed", e);
            }
        }

        private static byte[] get(String address) throws IOException {
            HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            // Wikimedia asks every client to say who it is.
            c.setRequestProperty("User-Agent", "LeiosLauncher/" + BuildConfig.VERSION_NAME
                    + " (Android TV launcher; +https://github.com/leioslauncher/leios-launcher)");
            try (InputStream in = c.getInputStream()) {
                if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[64 << 10];
                for (int n; (n = in.read(buf)) > 0; ) {
                    out.write(buf, 0, n);
                    if (out.size() > (30 << 20)) throw new IOException("too large");
                }
                return out.toByteArray();
            } finally {
                c.disconnect();
            }
        }
    }
}
