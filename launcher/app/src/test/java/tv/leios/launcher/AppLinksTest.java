package tv.leios.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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
    public void watchBeforeRentEachMostUsedFirst() {
        Set<String> installed = new HashSet<>(Arrays.asList("com.netflix.ninja", "com.google.android.youtube.tv",
                "com.apple.atve.androidtv.appletv"));
        Map<String, Integer> uses = new HashMap<>();
        uses.put("com.google.android.youtube.tv", 50);
        List<AppLinks.Offer> offers = AppLinks.yourOffers(Arrays.asList(
                new Titles.Offer("YouTube", true), new Titles.Offer("Apple TV", true),
                new Titles.Offer("Netflix", false)), installed, uses);
        assertEquals(3, offers.size());
        assertEquals("com.netflix.ninja", offers.get(0).app.pkg);
        assertFalse(offers.get(0).rent);
        assertEquals("com.google.android.youtube.tv", offers.get(1).app.pkg);
        assertTrue(offers.get(1).rent);
    }

    @Test
    public void rentOnlyTitleStillCountsAsOnYourApps() {
        // Iron Lung: only to rent or buy on YouTube, which is installed.
        List<AppLinks.Offer> offers = AppLinks.yourOffers(Arrays.asList(new Titles.Offer("YouTube", true)),
                new HashSet<>(Arrays.asList("com.google.android.youtube.tv")), new HashMap<>());
        assertEquals(1, offers.size());
        assertTrue(offers.get(0).rent);
    }

    @Test
    public void titleLinksFromAppIds() {
        Map<String, String> ids = new HashMap<>();
        ids.put("P1874", "80057281");
        ids.put("P9751", "umc.cmc.1srk2goyh2q2zdxcx605w8vtx");
        ids.put("P8298", "show/93ba22b1-833e-47ba-ae94-8ee7b9eefa9a");
        ids.put("P8055", "B07QQQ52B3");
        assertEquals("https://www.netflix.com/title/80057281",
                AppLinks.titleLink(AppLinks.byPackage("com.netflix.ninja"), true, ids));
        assertEquals("https://tv.apple.com/show/title/umc.cmc.1srk2goyh2q2zdxcx605w8vtx",
                AppLinks.titleLink(AppLinks.byPackage("com.apple.atve.androidtv.appletv"), true, ids));
        assertEquals("https://play.max.com/show/93ba22b1-833e-47ba-ae94-8ee7b9eefa9a",
                AppLinks.titleLink(AppLinks.byPackage("com.wbd.stream"), true, ids));
        assertEquals("https://app.primevideo.com/detail?asin=B07QQQ52B3",
                AppLinks.titleLink(AppLinks.byPackage("com.amazon.amazonvideo.livingroom"), true, ids));
    }

    @Test
    public void noLinkWithoutAnIdOrForAppsThatCantTakeOne() {
        Map<String, String> ids = new HashMap<>();
        ids.put("P7596", "3jLIGMDYINqD");
        ids.put("P6467", "05eb6a8e-90ed-4947-8c0b-e6536cbddd5f");
        assertNull(AppLinks.titleLink(AppLinks.byPackage("com.netflix.ninja"), true, ids));
        assertNull(AppLinks.titleLink(AppLinks.byPackage("com.disney.disneyplus"), true, ids));
        assertNull(AppLinks.titleLink(AppLinks.byPackage("com.hulu.livingroomplus"), true, ids));
        // Max's old HBO-style IDs and its movies aren't links that work.
        ids.put("P8298", "feature/urn:hbo:feature:GXSycSQud4r29bAEAAA10");
        assertNull(AppLinks.titleLink(AppLinks.byPackage("com.wbd.stream"), false, ids));
        assertNull(AppLinks.titleLink(AppLinks.byPackage("com.wbd.stream"), true, ids));
        // Something that isn't an ID never goes into a link.
        ids.put("P1874", "80057281/../../evil");
        assertNull(AppLinks.titleLink(AppLinks.byPackage("com.netflix.ninja"), false, ids));
    }

    @Test
    public void notInstalledMeansNoApps() {
        assertEquals(0, AppLinks.yourApps(Arrays.asList("Netflix"), new HashSet<>(), new HashMap<>()).size());
    }
}
