package tv.leios.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.zip.CRC32;

public class BackupsTest {

    @Test
    public void recentSearchesStayOutOfBackupsButSearchSettingsGoIn() throws Exception {
        JSONObject focus = new JSONObject();
        focus.put("search_recent", "[\"netflix\"]");
        focus.put("search_online", true);
        focus.put("bar_search", false);
        JSONObject out = Backups.withoutPrivate(focus);
        assertFalse(out.has("search_recent"));
        assertTrue(out.has("search_online"));
        assertTrue(out.has("bar_search"));
    }

    @Test
    public void themesCarryTheSearchButtonOnly() {
        assertTrue(Themes.KEYS.contains("bar_search"));
        assertFalse(Themes.KEYS.contains("search_online"));
        assertFalse(Themes.KEYS.contains("search_recent"));
    }

    /** The smallest valid PNG header: signature, IHDR (1×1), IEND. */
    private static byte[] tinyPng() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10}, 0, 8);
        chunk(out, "IHDR", new byte[] {0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0});
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        int n = data.length;
        out.write(new byte[] {(byte) (n >>> 24), (byte) (n >>> 16), (byte) (n >>> 8), (byte) n}, 0, 4);
        byte[] t = type.getBytes(StandardCharsets.US_ASCII);
        out.write(t, 0, 4);
        out.write(data, 0, n);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        int c = (int) crc.getValue();
        out.write(new byte[] {(byte) (c >>> 24), (byte) (c >>> 16), (byte) (c >>> 8), (byte) c}, 0, 4);
    }

    private static byte[] withSettings(String json) throws IOException {
        return Backups.withChunk(tinyPng(), Backups.gzip(json.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void settingsSurviveThePictureRoundTrip() throws Exception {
        JSONObject back = Backups.fromPng(withSettings("{\"kind\":\"backup\",\"version\":1,\"focus\":{}}"));
        assertEquals("backup", back.getString("kind"));
        assertEquals(1, back.getInt("version"));
    }

    @Test
    public void theFileIsStillAValidPng() throws Exception {
        byte[] png = withSettings("{}");
        assertEquals((byte) 0x89, png[0]);
        assertEquals('P', png[1]);
        // IHDR is still the first chunk, as PNG requires.
        assertEquals("IHDR", new String(png, 12, 4, StandardCharsets.US_ASCII));
    }

    @Test
    public void aDamagedFileIsRefused() throws Exception {
        byte[] png = withSettings("{\"kind\":\"backup\"}");
        png[45] ^= 0x5A; // inside our chunk's data
        expectRefused(png);
    }

    @Test
    public void otherFilesAreRefused() {
        expectRefused(tinyPng());                           // a picture without settings
        expectRefused("not a picture".getBytes());          // not a PNG
        expectRefused(Arrays.copyOf(tinyPng(), 20));        // cut short
        expectRefused(new byte[0]);
    }

    private static void expectRefused(byte[] file) {
        try {
            Backups.fromPng(file);
            fail("should have been refused");
        } catch (Exception expected) {
            // IOException or JSONException: either way nothing is applied.
        }
    }

    @Test
    public void everySettingTypeRoundTrips() throws Exception {
        FakePrefs p = new FakePrefs();
        p.values.put("s", "text with “quotes” and ≡");
        p.values.put("i", 42);
        p.values.put("l", 1793290863824L);
        p.values.put("f", 79.3f);
        p.values.put("b", true);
        p.values.put("set", new HashSet<>(Arrays.asList("x", "y")));
        JSONObject json = Backups.prefsToJson(p, null);

        FakePrefs back = new FakePrefs();
        android.content.SharedPreferences.Editor e = back.edit();
        Backups.jsonToPrefs(new JSONObject(json.toString()), e);
        e.commit();
        assertEquals(p.values, back.values);
    }

    @Test
    public void aThemeOnlyTakesLookSettings() throws Exception {
        FakePrefs p = new FakePrefs();
        p.values.put("font", "Inter");
        p.values.put("pin_hash", "secret");
        JSONObject theme = Backups.prefsToJson(p, Themes.KEYS);
        assertTrue(theme.has("font"));
        assertFalse("a theme must never carry the PIN", theme.has("pin_hash"));
    }

    // ---- telling files apart: markers now, English words before 1.4 ----------------------------

    private static void assertName(String file, int kind, int label, String title) {
        Backups.FileName n = Backups.parseName(file);
        assertNotNull(file, n);
        assertEquals(file, kind, n.kind);
        assertEquals(file, label, n.label);
        assertEquals(file, title, n.title);
    }

    @Test
    public void oldFileNamesAreStillRecognised() {
        // As Leios 1.3 and Clean Home named them.
        assertName("Leios backup 2025-06-01 10.00.leios.png", Backups.KIND_BACKUP, Backups.LABEL_SAVED, "");
        assertName("Clean Home backup 2024-05-01 09.30.cleanhome.png", Backups.KIND_BACKUP, Backups.LABEL_OLDER, "");
        assertName("Movie night theme.leios.png", Backups.KIND_THEME, Backups.LABEL_NAME, "Movie night");
        assertName("Cosy theme.cleanhome.png", Backups.KIND_THEME, Backups.LABEL_NAME, "Cosy");
    }

    @Test
    public void newFileNamesCarryAMarkerWhateverTheLanguage() {
        assertName("Copia de seguridad de Leios 2026-11-10 16.00.backup.leios.png",
                Backups.KIND_BACKUP, Backups.LABEL_SAVED, "");
        assertName("Noche de cine.theme.leios.png", Backups.KIND_THEME, Backups.LABEL_NAME, "Noche de cine");
        // A theme whose name has "backup" in it is still a theme.
        assertName("My backup look.theme.leios.png", Backups.KIND_THEME, Backups.LABEL_NAME, "My backup look");
        // Older versions find new files too: they look for "backup" or "theme" before .leios.png.
        assertTrue("x.backup.leios.png".matches(".*backup.*\\.leios\\.png"));
    }

    @Test
    public void otherPicturesAreNotBackups() {
        assertNull(Backups.parseName("IMG_0001.jpg"));
        assertNull(Backups.parseName("Holiday.png"));
        assertNull(Backups.parseName("Holiday.leios.png"));
        assertNull(Backups.parseName(null));
    }

    @Test
    public void automaticBackupReasonsAreKeysAndOldEnglishOnesMap() throws Exception {
        assertEquals(Backups.REASON_BEFORE_RESET, Backups.reasonKey(new JSONObject("{\"reason\":\"Before reset\"}")));
        assertEquals(Backups.REASON_AFTER_UPDATE, Backups.reasonKey(new JSONObject("{\"reason\":\"After update\"}")));
        assertEquals(Backups.REASON_BEFORE_THEME, Backups.reasonKey(new JSONObject("{\"reason_key\":\"before_theme\"}")));
        assertNull(Backups.reasonKey(new JSONObject("{\"reason\":\"Something newer\"}")));
        assertNull(Backups.reasonKey(new JSONObject("{}")));
    }

    @Test
    public void anOldFormatBackupStillRestores() throws Exception {
        // A backup picture exactly as 1.3 saved it: English reason, settings in the clHm chunk.
        String old = "{\"kind\":\"backup\",\"version\":1,\"time\":1748772000000,\"reason\":\"Before reset\","
                + "\"focus\":{\"bg\":{\"s\":\"shape:3\"},\"columns\":{\"i\":6},\"weather_on\":{\"b\":false},"
                + "\"categories\":{\"s\":\"{\\\"list\\\":[{\\\"id\\\":\\\"apps\\\",\\\"name\\\":\\\"Apps\\\"}]}\"}},"
                + "\"usage\":{\"com.netflix.ninja\":{\"i\":12}}}";
        JSONObject back = Backups.fromPng(withSettings(old));
        assertEquals("backup", back.getString("kind"));
        assertEquals(Backups.REASON_BEFORE_RESET, Backups.reasonKey(back));
        FakePrefs focus = new FakePrefs();
        android.content.SharedPreferences.Editor e = focus.edit();
        Backups.jsonToPrefs(back.getJSONObject("focus"), e);
        e.commit();
        assertEquals("shape:3", focus.values.get("bg"));
        assertEquals(6, focus.values.get("columns"));
        assertEquals(false, focus.values.get("weather_on"));
        // Its "Apps" row, saved as English text, comes back as the default (shown in any language).
        assertNull(Categories.load(focus).find(Categories.APPS).name);
    }

    @Test
    public void themeKeysNeverIncludeLocksOrApps() {
        for (String k : new String[] {"pin_hash", "pin_salt", "locked_apps", "locked_cats", "guest", "categories",
                "hidden", "dock_order", "weather_city"}) {
            assertFalse(k, Themes.KEYS.contains(k));
        }
    }
}
