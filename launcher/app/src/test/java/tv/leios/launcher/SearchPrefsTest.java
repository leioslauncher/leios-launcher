package tv.leios.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class SearchPrefsTest {

    @Test
    public void onlineSearchIsOffUntilAskedAndHiddenWithoutAKey() {
        FakePrefs p = new FakePrefs();
        assertEquals(SearchPrefs.UNDECIDED, SearchPrefs.online(p, true));
        assertEquals(SearchPrefs.UNAVAILABLE, SearchPrefs.online(p, false));
        SearchPrefs.setOnline(p, true);
        assertEquals(SearchPrefs.ON, SearchPrefs.online(p, true));
        assertEquals(SearchPrefs.UNAVAILABLE, SearchPrefs.online(p, false));
        SearchPrefs.setOnline(p, false);
        assertEquals(SearchPrefs.OFF, SearchPrefs.online(p, true));
    }

    @Test
    public void regionFromSettingThenDeviceThenUs() {
        FakePrefs p = new FakePrefs();
        assertEquals("GB", SearchPrefs.region(p, Locale.UK));
        assertEquals("US", SearchPrefs.region(p, new Locale("en")));
        p.edit().putString("search_region", "de").apply();
        assertEquals("DE", SearchPrefs.region(p, Locale.UK));
    }

    @Test
    public void recentSearchesNewestFirstNoRepeatsCapped() {
        FakePrefs p = new FakePrefs();
        for (int i = 0; i < 12; i++) SearchPrefs.addRecent(p, "q" + i);
        SearchPrefs.addRecent(p, "Q5");
        List<String> r = SearchPrefs.recent(p);
        assertEquals(SearchPrefs.RECENT_MAX, r.size());
        assertEquals("Q5", r.get(0));
        assertEquals(1, Collections.frequency(r, "q5") + Collections.frequency(r, "Q5"));
        SearchPrefs.addRecent(p, "   ");
        assertEquals("Q5", SearchPrefs.recent(p).get(0));
        SearchPrefs.clearRecent(p);
        assertTrue(SearchPrefs.recent(p).isEmpty());
    }

    @Test
    public void recentSearchesOffRemembersNothing() {
        FakePrefs p = new FakePrefs();
        p.edit().putBoolean("search_recent_on", false).apply();
        SearchPrefs.addRecent(p, "netflix");
        assertTrue(SearchPrefs.recent(p).isEmpty());
    }

    @Test
    public void hiddenAppsNeverShowLockedOnesShowAsLocked() {
        Set<String> hidden = new HashSet<>(Arrays.asList("hidden.app"));
        Set<String> lockedApps = new HashSet<>(Arrays.asList("locked.app"));
        Set<String> lockedCats = new HashSet<>(Arrays.asList("kids"));
        assertFalse(SearchPrefs.appVisible("hidden.app", hidden));
        assertTrue(SearchPrefs.appVisible("locked.app", hidden));
        // Locked (by itself or its category) while the lock applies: shown, marked, PIN to open.
        assertTrue(SearchPrefs.appLocked("locked.app", null, lockedApps, lockedCats, true));
        assertTrue(SearchPrefs.appLocked("game", "kids", lockedApps, lockedCats, true));
        assertFalse(SearchPrefs.appLocked("free.app", "movies", lockedApps, lockedCats, true));
        // After the right PIN, or outside the lock's hours, nothing is marked.
        assertFalse(SearchPrefs.appLocked("locked.app", null, lockedApps, lockedCats, false));
        assertFalse(SearchPrefs.appLocked("game", "kids", lockedApps, lockedCats, false));
    }

    @Test
    public void guestModeHidesSettings() {
        assertFalse(SearchPrefs.settingsVisible(true));
        assertTrue(SearchPrefs.settingsVisible(false));
    }
}
