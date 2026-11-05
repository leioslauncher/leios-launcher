package tv.leios.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class AppLinksTest {

    private static String pkg(String service) {
        AppLinks.App a = AppLinks.forService(service);
        return a == null ? null : a.pkg;
    }

    @Test
    public void tmdbServiceNamesMatchApps() {
        assertEquals("com.netflix.ninja", pkg("Netflix"));
        assertEquals("com.netflix.ninja", pkg("Netflix basic with Ads"));
        assertEquals("com.disney.disneyplus", pkg("Disney Plus"));
        assertEquals("com.amazon.amazonvideo.livingroom", pkg("Amazon Prime Video"));
        assertEquals("com.amazon.amazonvideo.livingroom", pkg("Amazon Prime Video with Ads"));
        assertEquals("com.wbd.stream", pkg("Max"));
        assertEquals("com.wbd.stream", pkg("HBO Max"));
        assertEquals("com.cbs.ott", pkg("Paramount Plus"));
        assertEquals("com.cbs.ott", pkg("Paramount+ with Showtime"));
        assertEquals("com.apple.atve.androidtv.appletv", pkg("Apple TV Plus"));
        assertEquals("com.hulu.livingroomplus", pkg("Hulu"));
        assertEquals("com.crunchyroll.crunchyroid", pkg("Crunchyroll"));
        assertEquals("com.peacocktv.peacockandroid", pkg("Peacock Premium"));
        assertEquals("com.tubitv", pkg("Tubi TV"));
    }

    @Test
    public void channelsPlayInTheStoreApp() {
        assertEquals("com.amazon.amazonvideo.livingroom", pkg("Paramount Plus Amazon Channel"));
        assertEquals("com.apple.atve.androidtv.appletv", pkg("Starz Apple TV Channel"));
        assertNull(pkg("Starz Roku Premium Channel"));
    }

    @Test
    public void unrelatedNamesDontMatch() {
        assertNull(pkg("Maxdome"));
        assertNull(pkg("YouTube TV"));
        assertNull(pkg(""));
        assertNull(pkg("Fandango At Home"));
    }

    @Test
    public void yourAppsAreInstalledOnesMostUsedFirst() {
        Set<String> installed = new HashSet<>(Arrays.asList("com.netflix.ninja", "com.hulu.livingroomplus"));
        Map<String, Integer> uses = new HashMap<>();
        uses.put("com.hulu.livingroomplus", 9);
        uses.put("com.netflix.ninja", 2);
        List<AppLinks.App> apps = AppLinks.yourApps(
                Arrays.asList("Netflix", "Disney Plus", "Hulu", "Netflix basic with Ads"), installed, uses);
        assertEquals(2, apps.size());
        assertEquals("com.hulu.livingroomplus", apps.get(0).pkg);
        assertEquals("com.netflix.ninja", apps.get(1).pkg);
    }

    @Test
    public void notInstalledMeansNoApps() {
        assertEquals(0, AppLinks.yourApps(Arrays.asList("Netflix"), new HashSet<>(), new HashMap<>()).size());
    }
}
