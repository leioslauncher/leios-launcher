package tv.leios.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps search's settings index complete: every Customize menu that holds settings is in
 * SettingsIndex, every row in those menus is an entry, and search can open every menu. A new menu
 * or row fails here until it's added to SettingsIndex (or, for a menu that isn't settings, to
 * NOT_SETTINGS below).
 */
public class SettingsIndexTest {

    /** open… methods that aren't menus of settings: lists, pickers, confirmations, other screens. */
    private static final Set<String> NOT_SETTINGS = new HashSet<>(Arrays.asList(
            "openChoice", "openNotifications", "openInputsMenu", "openQuickActions", "openMoreMenu", "openNameMenu",
            "openTileMenu", "openIconPicture", "openIconPackFor", "openPackIcons", "openMoveToCategory",
            "openCustomize", "openDockArrangeMenu", "openFull", "openOnlineMenu", "openColoursMenu", "openShapesMenu",
            "openPhotoMenu", "openSlideFolderMenu", "openVideoMenu", "openStartOnMenu", "openCategoryMenu",
            "openCategoryApps", "openIconPackMenu", "openHideMenu", "openPinMenu", "openLockWhat", "openLockWhen",
            "openHoldChoice", "openSavedTheme", "openThemeImport", "openPatternsMenu", "openTargetChoice",
            "openSleepMenu", "openBackupSave", "openRestoreList", "openResetMenu", "openRegionChoice",
            "openSection", "openSettingMenu", "openSettingFromSearch", "openSearch", "openTitleNow",
            "openTitleFallback"));

    /** Rows that are part of a confirmation, not a setting. */
    private static final Set<String> NOT_ROWS = new HashSet<>(Arrays.asList("Cancel", "@action_cancel"));

    private static String source;
    private static final Map<String, String> bodies = new LinkedHashMap<>();

    @BeforeClass
    public static void readSource() throws Exception {
        SettingsIndex.localize("test", TestStrings::get);
        File f = new File("src/main/java/tv/leios/launcher/MainActivity.java");
        if (!f.exists()) f = new File("app/src/main/java/tv/leios/launcher/MainActivity.java");
        source = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("\\n    (?:private |static )*void (open\\w+)\\(").matcher(source);
        List<int[]> starts = new ArrayList<>();
        List<String> names = new ArrayList<>();
        while (m.find()) {
            starts.add(new int[] {m.start()});
            names.add(m.group(1));
        }
        Pattern end = Pattern.compile("\\n    (?:private |static |final |public |protected |@|/\\*\\*|// )");
        for (int i = 0; i < names.size(); i++) {
            int s = starts.get(i)[0];
            Matcher e = end.matcher(source);
            int stop = e.find(s + 5) ? e.start() : source.length();
            // Overloads (e.g. two openQuickActions) join into one body.
            bodies.merge(names.get(i), source.substring(s, stop), String::concat);
        }
    }

    /** Row labels in a menu's code: "@name" for tr(R.string.name), or the text of a literal. */
    private static List<String> labels(String body) {
        List<String> out = new ArrayList<>();
        String label = "(?:\"([^\"]+)\"|tr\\(R\\.string\\.(\\w+)\\))";
        Pattern[] ps = {
                Pattern.compile("Row\\.(?:nav|action|slider|stepper)\\(\\s*(?:R\\.drawable\\.\\w+|0|\\w+)\\s*,\\s*" + label),
                Pattern.compile("addToggle\\(\\s*" + label),
                Pattern.compile("tileOption\\(\\s*\\d+\\s*,\\s*" + label),
        };
        for (Pattern p : ps) {
            Matcher m = p.matcher(body);
            while (m.find()) {
                String found = m.group(1) != null ? m.group(1) : "@" + m.group(2);
                if (!NOT_ROWS.contains(found)) out.add(found);
            }
        }
        return out;
    }

    private static boolean indexed(String menu, String label) {
        for (SettingsIndex.Entry e : SettingsIndex.ENTRIES) {
            if (!e.menu.equals(menu)) continue;
            if (label.startsWith("@") ? label.substring(1).equals(TestStrings.name(e.label)) : label.equals(e.labelText)) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void everySettingsMenuIsInTheIndex() {
        Set<String> indexedMethods = new HashSet<>();
        for (SettingsIndex.Menu m : SettingsIndex.MENUS.values()) indexedMethods.add(m.method);
        List<String> missing = new ArrayList<>();
        for (String method : bodies.keySet()) {
            if (!NOT_SETTINGS.contains(method) && !indexedMethods.contains(method)) missing.add(method);
        }
        if (!missing.isEmpty()) fail("Menus missing from SettingsIndex (or add them to NOT_SETTINGS): " + missing);
        for (SettingsIndex.Menu m : SettingsIndex.MENUS.values()) {
            assertTrue("No method " + m.method + " for " + m.id, bodies.containsKey(m.method));
        }
    }

    @Test
    public void everyRowIsInTheIndex() {
        List<String> missing = new ArrayList<>();
        int seen = 0;
        for (SettingsIndex.Menu m : SettingsIndex.MENUS.values()) {
            String body = bodies.get(m.method);
            if (body == null) continue;
            for (String label : labels(body)) {
                seen++;
                if (!indexed(m.id, label)) missing.add(m.id + ": " + label);
            }
        }
        if (!missing.isEmpty()) fail("Settings missing from SettingsIndex: " + missing);
        // The scan itself works: it finds most of the rows (a few are built from lists, not literals).
        assertTrue("only " + seen + " rows found in MainActivity", seen >= 100);
    }

    @Test
    public void searchCanOpenEveryMenu() {
        String opener = bodies.get("openSettingMenu");
        for (String id : SettingsIndex.MENUS.keySet()) {
            assertTrue("openSettingMenu has no case for " + id, opener.contains("case \"" + id + "\":"));
        }
    }

    @Test
    public void everyEntryHasAPlaceAndADescription() {
        for (SettingsIndex.Entry e : SettingsIndex.ENTRIES) {
            assertFalse(e.labelText, e.crumb().isEmpty());
            assertFalse(e.labelText, e.descriptionText.isEmpty());
            int s = SettingsIndex.section(e.menu);
            assertTrue(s >= 0 && s < SettingsIndex.SECTIONS.length);
        }
        assertEquals("Look & sound  ›  Sounds", SettingsIndex.crumb("look.sounds"));
        assertEquals(3, SettingsIndex.section("look.sounds.quiet"));
    }

    @Test
    public void synonymsFindTheRightSetting() {
        assertEquals("background", SettingsIndex.search("wallpaper", 5).get(0).menu);
        SettingsIndex.Entry dark = SettingsIndex.search("dark", 5).get(0);
        assertTrue(dark.labelText, dark.labelText.equals("Appearance") || dark.labelText.equals("Mode"));
        assertEquals("PIN lock", SettingsIndex.search("pin", 5).get(0).labelText);
        String mute = SettingsIndex.search("mute", 5).get(0).labelText;
        assertTrue(mute, mute.equals("Volume") || mute.equals("Sounds"));
        assertTrue(SettingsIndex.search("zzzz", 5).isEmpty());
    }

    @Test
    public void searchMatchesTheLanguageShown() {
        // Another language: the clock setting is "Uhr", its words "Zeit，Wecker" (a wide comma).
        SettingsIndex.Entry clock = SettingsIndex.search("clock", 5).get(0);
        try {
            SettingsIndex.localize("de", id -> id == clock.label ? "Uhr"
                    : id == clock.words ? "Zeit，Wecker" : TestStrings.get(id));
            assertEquals(clock, SettingsIndex.search("uhr", 5).get(0));
            assertEquals(clock, SettingsIndex.search("wecker", 5).get(0));
            assertEquals("Uhr", clock.labelText);
        } finally {
            SettingsIndex.localize("test", TestStrings::get);
        }
        assertEquals("Clock", clock.labelText);
    }

    @Test
    public void exactNameBeatsSynonym() {
        assertEquals("Clock", SettingsIndex.search("clock", 5).get(0).labelText);
    }
}
