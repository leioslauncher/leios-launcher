package tv.leios.launcher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every Leios setting in one place, for search: its name as the menu shows it, a short description,
 * where it lives, and other words people use for it. Picking one opens its menu in Customization with
 * the row highlighted.
 *
 * <p>Adding a setting: add its row to the menu in MainActivity, then one {@link #e} line here. A
 * new menu gets a {@link #menu} line too, and a case in MainActivity.openSettingMenu.
 * SettingsIndexTest fails when a row or a menu is missing here.
 */
final class SettingsIndex {

    private SettingsIndex() {
    }

    /** A menu that holds settings. {@code method} is the MainActivity method that opens it. */
    static final class Menu {
        final String id, title, parent, method;

        Menu(String id, String title, String parent, String method) {
            this.id = id;
            this.title = title;
            this.parent = parent;
            this.method = method;
        }
    }

    /** One setting: a row in a menu. */
    static final class Entry {
        final String menu, label, description;
        final String[] synonyms;

        Entry(String menu, String label, String description, String[] synonyms) {
            this.menu = menu;
            this.label = label;
            this.description = description;
            this.synonyms = synonyms;
        }

        /** Every name of this setting, made ready for matching once (search runs on each key press). */
        private SearchRank.Prepared pLabel, pMenu, pDescription;
        private SearchRank.Prepared[] pSynonyms;

        private void prepare() {
            if (pLabel != null) return;
            pLabel = SearchRank.prepare(label);
            pMenu = SearchRank.prepare(MENUS.get(menu).title);
            pDescription = SearchRank.prepare(description);
            pSynonyms = new SearchRank.Prepared[synonyms.length];
            for (int i = 0; i < synonyms.length; i++) pSynonyms[i] = SearchRank.prepare(synonyms[i]);
        }

        /** Where it is, e.g. "Look & sound › Sounds". */
        String crumb() {
            return SettingsIndex.crumb(menu);
        }
    }

    /** The seven sections of Customization, in order (MainActivity's SEC_* numbers). */
    static final String[] SECTIONS = {"background", "home", "apps", "look", "projector", "lock", "system"};

    static final Map<String, Menu> MENUS = new LinkedHashMap<>();
    static final List<Entry> ENTRIES = new ArrayList<>();

    private static void menu(String id, String title, String parent, String method) {
        MENUS.put(id, new Menu(id, title, parent, method));
    }

    private static void e(String menu, String label, String description, String... synonyms) {
        if (!MENUS.containsKey(menu)) throw new IllegalStateException("No menu " + menu);
        ENTRIES.add(new Entry(menu, label, description, synonyms));
    }

    static {
        menu("background", "Background", null, "openBackgroundMenu");
        menu("background.slideshow", "Slideshow", "background", "openSlideshowMenu");
        menu("background.adjust", "Adjust", "background", "openAdjustMenu");
        menu("home", "Home layout", null, "openHomeMenu");
        menu("home.layout", "Layout", "home", "openLayoutMenu");
        menu("home.categories", "Categories", "home", "openCategoriesMenu");
        menu("home.dock", "Dock", "home", "openDockMenu");
        menu("home.weather", "Weather", "home", "openWeatherMenu");
        menu("apps", "Apps", null, "openAppsMenu");
        menu("look", "Look & sound", null, "openLookMenu");
        menu("look.appearance", "Appearance", "look", "openAppearanceMenu");
        menu("look.font", "Font and text size", "look", "openFontMenu");
        menu("look.clock", "Clock", "look", "openClockMenu");
        menu("look.bar", "Top bar", "look", "openBarMenu");
        menu("look.bar.notif", "Notifications", "look.bar", "openNotifSetting");
        menu("look.tiles", "Tiles", "look", "openTilesMenu");
        menu("look.tiles.advanced", "Advanced", "look.tiles", "openTilesAdvanced");
        menu("look.themes", "Themes", "look", "openThemesMenu");
        menu("look.sounds", "Sounds", "look", "openSoundsMenu");
        menu("look.sounds.output", "Output", "look.sounds", "openSoundsOutputMenu");
        menu("look.sounds.quiet", "Quiet times", "look.sounds", "openSoundsQuietMenu");
        menu("projector", "Projector & tools", null, "openProjectorMenu");
        menu("projector.startup", "Start-up and wake", "projector", "openStartupMenu");
        menu("projector.idle", "When Home is idle", "projector", "openIdleMenu");
        menu("lock", "Lock & remote", null, "openLockMenu");
        menu("lock.shortcuts", "Remote shortcuts", "lock", "openShortcutsMenu");
        menu("system", "System", null, "openBackupMenu");
        menu("system.search", "Search & privacy", "system", "openSearchSettings");
        menu("system.about", "About", "system", "openAboutMenu");

        // Background
        e("background", "Colours", "A plain colour behind Home", "wallpaper", "background colour", "color", "solid");
        e("background", "Shapes", "Soft shapes and gradients", "wallpaper", "pattern", "gradient", "abstract");
        e("background", "Photo", "A photo or animated picture from this TV or a USB drive", "wallpaper", "picture", "image", "gif", "usb");
        e("background", "Slideshow", "Your photos, changing on their own", "wallpaper", "photos", "rotate", "gallery");
        e("background", "Video", "A looping video behind Home", "wallpaper", "live wallpaper", "movie", "mp4");
        e("background", "Photo of the day", "A new free photo from Wikimedia Commons each day", "wallpaper", "daily", "online", "internet", "wikimedia");
        e("background", "Adjust", "Brightness, contrast, colour and blur of the background", "wallpaper", "dim", "darker", "lighter");
        e("background.slideshow", "Photos from", "Which folder the slideshow uses", "folder", "usb", "source");
        e("background.slideshow", "Change every", "How long each photo stays", "interval", "speed", "timer", "duration");
        e("background.adjust", "Brightness", "Make the background darker or lighter", "dim", "darker", "lighter");
        e("background.adjust", "Contrast", "More or less contrast in the background");
        e("background.adjust", "Saturation", "More or less colour in the background", "color", "colour", "grey", "black and white");
        e("background.adjust", "Blur", "Soften the background behind your apps", "soft", "frosted");
        e("background.adjust", "Reset adjustments", "Background back to how it was", "undo");

        // Home layout
        e("home", "Categories", "Rows of apps and how they're sorted", "rows", "groups", "folders");
        e("home", "Dock", "The row of apps at the top", "favourites", "favorites", "pinned");
        e("home", "Layout", "Tile size, spacing and margins", "grid", "columns", "size");
        e("home", "Home button goes to", "Where focus lands when you press Home", "start", "focus", "landing");
        e("home", "Weather", "Weather under the clock", "forecast", "temperature");
        e("home.layout", "Tiles per row", "How many apps fit in a row", "columns", "tile size", "bigger", "smaller");
        e("home.layout", "Tile spacing", "Space between apps", "gap", "padding");
        e("home.layout", "Row spacing", "Space between rows", "gap");
        e("home.layout", "Side margins", "Space at the screen edges", "overscan", "edges", "border");
        e("home.layout", "Reset layout", "Layout back to the default", "undo");
        e("home.categories", "New category…", "Make your own row of apps", "add row", "group", "folder");
        e("home.categories", "All apps row", "Show a row with every app", "all apps");
        e("home.categories", "Inputs row (HDMI)", "Show HDMI inputs as a row", "hdmi", "inputs", "sources");
        e("home.categories", "Empty categories", "Show or hide rows with no apps", "empty");
        e("home.categories", "Reset categories", "Categories back to the default", "undo");
        e("home.dock", "Rearrange", "Move apps around in the dock", "order", "move", "sort dock");
        e("home.dock", "Reset dock to default", "Default apps in the dock", "undo");
        e("home.weather", "Show weather", "Weather under the clock on Home", "forecast");
        e("home.weather", "City", "Where the weather is for", "location", "town", "place");
        e("home.weather", "Units", "Celsius or Fahrenheit", "celsius", "fahrenheit", "temperature");
        e("home.weather", "Reset weather", "Forget the city and units", "undo");

        // Apps
        e("apps", "Show or hide apps", "Choose which apps Home shows", "hide", "hidden", "unhide");
        e("apps", "Arrange apps", "Move apps around on Home", "order", "move", "sort");
        e("apps", "Sort and categories", "Rows of apps and how they're sorted", "categories", "a-z", "alphabetical");
        e("apps", "Icon pack", "Change every app's icon with an icon pack", "icons", "theme");
        e("apps", "Reset names, tiles and icons", "Every app gets its own name and icon back", "rename", "undo");

        // Look & sound
        e("look", "Appearance", "Light, dark or automatic, and the accent colour", "dark", "light", "dark mode", "night", "theme", "accent");
        e("look", "Font and text size", "The font and how big the text is", "typeface", "bigger text", "readability");
        e("look", "Clock", "Clock format, size and position", "time", "12 hour", "24 hour");
        e("look", "Top bar", "Which buttons show at the top", "buttons", "bar", "pills");
        e("look", "Tiles", "How app tiles look", "icons", "banners", "corners");
        e("look", "Themes", "Save and apply whole looks", "presets", "style", "import");
        e("look", "Sounds", "Interface sounds and their volume", "audio", "beep", "click", "mute", "sound effects");
        e("look.appearance", "Mode", "Dark, light, or automatic from sunrise to sunset", "dark", "light", "dark mode", "night mode", "automatic");
        e("look.appearance", "Accent colour", "Azure, or a colour from the background", "color", "highlight", "focus colour");
        e("look.font", "Font", "The typeface used everywhere", "typeface", "lettering");
        e("look.font", "Text size", "Smaller or bigger text", "bigger text", "zoom", "large text", "readability");
        e("look.font", "Reset font and size", "Default font and size", "undo");
        e("look.clock", "Format", "12-hour, 24-hour or automatic", "12 hour", "24 hour", "am pm");
        e("look.clock", "Size", "How big the clock is", "bigger clock", "smaller clock");
        e("look.clock", "Position", "Where the clock sits", "place", "left", "centre", "center");
        e("look.clock", "Date", "Show the date under the clock", "day");
        e("look.clock", "Greeting", "Good morning above the clock", "hello", "message");
        e("look.clock", "Shadow", "A soft shadow behind the clock", "outline", "readability");
        e("look.clock", "Reset clock", "Clock back to the default", "undo");
        e("look.bar", "Inputs button", "Show the Inputs button", "hdmi", "sources");
        e("look.bar", "Search button", "Show the Search button", "find", "magnifier");
        e("look.bar", "Wi-Fi", "Show the Wi-Fi name and signal", "wifi", "network", "internet");
        e("look.bar", "Notifications", "A bell when apps have notifications", "bell", "alerts");
        e("look.bar", "Settings button", "Show the Settings button", "gear");
        e("look.bar", "Size", "Normal or compact buttons", "compact", "smaller buttons");
        e("look.bar", "Reset top bar", "Top bar back to the default", "undo");
        e("look.bar.notif", "Show the bell", "A bell in the top bar for notifications", "alerts");
        e("look.bar.notif", "Notification access", "Android's permission for the bell", "permission");
        e("look.tiles", "Style", "Clean, soft or crisp tiles", "look", "corners");
        e("look.tiles", "Show apps as", "Icons or wide banners", "banners", "icons");
        e("look.tiles", "App names", "When app names show under tiles", "labels", "titles");
        e("look.tiles", "Advanced", "Corners, focus colour and icon backgrounds", "corners", "rounded");
        e("look.tiles", "Reset tiles", "Tiles back to the default", "undo");
        e("look.tiles.advanced", "Corners", "How round tile corners are", "rounded", "radius");
        e("look.tiles.advanced", "Focus colour", "The colour around the app you're on", "highlight", "accent");
        e("look.tiles.advanced", "Icon tile background", "What's behind each app icon", "glass", "plain");
        e("look.tiles.advanced", "Glow behind the dock", "A soft glow in the accent colour", "light", "halo");
        e("look.themes", "Save this look…", "Save the current look as a theme", "save theme", "preset");
        e("look.themes", "Import from a picture", "Load a theme someone shared", "import", "load theme");
        e("look.themes", "Undo last theme", "Go back to the look before the last theme", "undo", "revert");
        e("look.sounds", "Volume", "How loud interface sounds are, or off", "loud", "quiet", "mute", "sound volume", "off");
        e("look.sounds", "Output", "System sounds or media volume", "speaker", "channel");
        e("look.sounds", "Quiet times", "When sounds stay silent", "bedtime", "silent", "night");
        e("look.sounds", "Start-up sound", "A sound when Home starts", "boot", "chime");
        e("look.sounds", "≡ panel open and close", "Sounds when the quick panel opens", "menu sound", "panel");
        e("look.sounds", "Reset sounds", "Sounds back to the default", "undo");
        e("look.sounds.output", "Sound output", "Automatic, system sounds or media volume", "channel", "speaker");
        e("look.sounds.output", "Silent on Bluetooth or ARC", "No sounds through headphones or a soundbar", "headphones", "soundbar", "hdmi arc");
        e("look.sounds.quiet", "Silent at bedtime", "No sounds while the bedtime lock is on", "night", "sleep");
        e("look.sounds.quiet", "Silent in guest mode", "No sounds in guest mode", "guest");

        // Projector & tools
        e("projector", "Picture", "The projector's picture mode, colour and HDR", "image", "colour", "hdr", "picture mode");
        e("projector", "Brightness", "The projector's laser brightness", "laser", "light", "dim");
        e("projector", "Keystone", "Straighten the image", "angle", "skew", "correction", "focus");
        e("projector", "Sleep timer", "Turn off after a while", "timer", "turn off", "shutdown", "power off");
        e("projector", "Test patterns", "Patterns for focus, levels and colour", "calibration", "focus", "grid");
        e("projector", "Start-up and wake", "What opens when the projector starts or wakes", "boot", "power on", "autostart");
        e("projector", "When Home is idle", "Dim Home or show a clock when idle", "screensaver", "idle", "clock", "dim");
        e("projector.startup", "When it starts", "An app or input to open at start-up", "boot", "autostart", "power on");
        e("projector.startup", "When it wakes", "An app or input to open on wake", "resume", "wake up");
        e("projector.idle", "Do this", "Dim Home or show a clock", "screensaver", "clock");
        e("projector.idle", "After", "How long before Home goes idle", "minutes", "timeout");

        // Lock & remote
        e("lock", "PIN lock", "Set or change the PIN", "password", "code", "parental", "kids", "child lock");
        e("lock", "What it locks", "Customize, Settings, apps and categories", "parental", "lock apps", "restrict");
        e("lock", "When", "Always, or only at bedtime", "schedule", "bedtime", "hours");
        e("lock", "Guest mode", "Hide Customize and Settings for visitors", "visitor", "kiosk", "kids");
        e("lock", "Remote shortcuts", "What holding ≡ or the Settings button does", "buttons", "hold", "long press");
        e("lock.shortcuts", "Hold ≡", "What holding the ≡ button does", "menu button", "long press");
        e("lock.shortcuts", "Hold the Settings button", "What holding the gear does", "gear", "long press");
        e("lock.shortcuts", "Hold Home for search", "Holding Home on Home opens search", "long press", "home button", "search");

        // System
        e("system", "Search & privacy", "Online title search, region and recent searches", "search", "tmdb", "privacy", "movies", "online");
        e("system", "Save a backup", "Save all your settings to a picture file", "backup", "export");
        e("system", "Restore a backup", "Load settings from a backup", "restore", "import");
        e("system", "Automatic backups", "Back up before big changes", "auto backup");
        e("system", "Reset everything", "Every setting back to the default", "factory", "reset", "clear", "start over");
        e("system", "About", "Version, licences and credits", "version", "credits", "licences", "licenses", "tmdb", "justwatch");
        e("system.search", "Online title search", "Find movies and shows with TMDB", "tmdb", "movies", "tv shows", "internet", "online");
        e("system.search", "Region", "Which country's streaming apps to show", "country", "location");
        e("system.search", "Titles not on your apps", "Show or hide titles your apps don't have", "elsewhere", "other services");
        e("system.search", "Recent searches", "Remember what you searched for", "history");
        e("system.search", "Clear recent searches", "Forget what you searched for", "history", "delete");
    }

    /** "Look & sound › Sounds" for "look.sounds". */
    static String crumb(String menuId) {
        List<String> parts = new ArrayList<>();
        for (Menu m = MENUS.get(menuId); m != null; m = m.parent == null ? null : MENUS.get(m.parent)) {
            parts.add(m.title);
        }
        Collections.reverse(parts);
        return String.join("  ›  ", parts);
    }

    /** The section (0–6) a menu belongs to. */
    static int section(String menuId) {
        String top = menuId.contains(".") ? menuId.substring(0, menuId.indexOf('.')) : menuId;
        for (int i = 0; i < SECTIONS.length; i++) if (SECTIONS[i].equals(top)) return i;
        return 0;
    }

    /** Settings matching {@code query}, best first. */
    static List<Entry> search(String query, int limit) {
        SearchRank.Prepared q = SearchRank.prepare(query);
        List<Entry> found = new ArrayList<>();
        List<Integer> scores = new ArrayList<>();
        if (q.text.isEmpty()) return found;
        for (Entry e : ENTRIES) {
            int s = score(q, e);
            if (s <= 0) continue;
            int at = 0;
            while (at < scores.size() && scores.get(at) >= s) at++;
            found.add(at, e);
            scores.add(at, s);
        }
        return found.size() > limit ? new ArrayList<>(found.subList(0, limit)) : found;
    }

    static int score(SearchRank.Prepared q, Entry e) {
        e.prepare();
        int best = SearchRank.score(q, e.pLabel);
        for (SearchRank.Prepared s : e.pSynonyms) best = Math.max(best, SearchRank.score(q, s) * 9 / 10);
        best = Math.max(best, SearchRank.score(q, e.pMenu) * 6 / 10);
        // A word in the description counts a little, only as a whole-word start (no "in" in "brightness").
        int d = SearchRank.score(q, e.pDescription);
        if (d >= SearchRank.WORD_START) best = Math.max(best, d * 4 / 10);
        return best;
    }
}
