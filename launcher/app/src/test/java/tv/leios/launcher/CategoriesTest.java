package tv.leios.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CategoriesTest {

    @Test
    public void freshInstallHasOnlyApps() {
        Categories c = Categories.load(new FakePrefs());
        assertEquals(1, c.list.size());
        assertTrue(c.list.get(0).isApps());
        assertEquals(Categories.SORT_AZ, c.list.get(0).sort);
        assertEquals(Categories.DOCK, c.startOn);
    }

    @Test
    public void migratesTheOldSortAndOrder() {
        FakePrefs p = new FakePrefs();
        p.values.put("sort", "custom");
        p.values.put("grid_order", "b.app,a.app,,c.app");
        Categories c = Categories.load(p);
        Categories.Cat apps = c.find(Categories.APPS);
        assertEquals(Categories.SORT_CUSTOM, apps.sort);
        assertEquals(Arrays.asList("b.app", "a.app", "c.app"), apps.apps);

        p.values.put("sort", "used");
        assertEquals(Categories.SORT_USED, Categories.load(p).find(Categories.APPS).sort);
    }

    @Test
    public void damagedJsonFallsBackToApps() {
        FakePrefs p = new FakePrefs();
        p.values.put("categories", "{\"list\": [ {\"id\": ");
        Categories c = Categories.load(p);
        assertEquals(1, c.list.size());
        assertNotNull(c.find(Categories.APPS));
    }

    @Test
    public void startOnAMissingCategoryGoesToTheDock() {
        FakePrefs p = new FakePrefs();
        p.values.put("categories", "{\"startOn\":\"gone\",\"list\":[{\"id\":\"apps\",\"name\":\"Apps\"}]}");
        assertEquals(Categories.DOCK, Categories.load(p).startOn);
    }

    @Test
    public void saveAndLoadKeepEverything() {
        FakePrefs p = new FakePrefs();
        Categories c = Categories.load(p);
        Categories.Cat movies = c.create("Movies");
        movies.singleRow = true;
        movies.sort = Categories.SORT_RECENT;
        c.moveApp("x.app", movies, -1);
        c.allAppsRow = true;
        c.inputsRow = true;
        c.startOn = movies.id;
        c.save(p);

        Categories back = Categories.load(p);
        assertEquals(2, back.list.size());
        Categories.Cat m = back.find(movies.id);
        assertEquals("Movies", m.name);
        assertTrue(m.singleRow);
        assertEquals(Categories.SORT_RECENT, m.sort);
        assertEquals(Arrays.asList("x.app"), m.apps);
        assertTrue(back.allAppsRow);
        assertTrue(back.inputsRow);
        assertEquals(movies.id, back.startOn);
    }

    @Test
    public void newCategoriesGoAboveApps() {
        Categories c = Categories.load(new FakePrefs());
        c.create("One");
        c.create("Two");
        assertEquals("One", c.list.get(0).name);
        assertEquals("Two", c.list.get(1).name);
        assertTrue(c.list.get(2).isApps());
    }

    @Test
    public void anAppIsInOneCategoryAtATime() {
        Categories c = Categories.load(new FakePrefs());
        Categories.Cat a = c.create("A"), b = c.create("B");
        c.moveApp("x", a, -1);
        c.moveApp("x", b, 0);
        assertFalse(a.apps.contains("x"));
        assertEquals(b, c.of("x"));
        c.moveApp("x", c.find(Categories.APPS), -1);
        assertTrue(c.of("x").isApps());
        assertFalse(b.apps.contains("x"));
    }

    @Test
    public void deletingTheStartCategoryGoesBackToTheDock() {
        Categories c = Categories.load(new FakePrefs());
        Categories.Cat a = c.create("A");
        c.startOn = a.id;
        c.delete(a);
        assertEquals(Categories.DOCK, c.startOn);
        c.delete(c.find(Categories.APPS));
        assertNotNull("Apps can't be deleted", c.find(Categories.APPS));
    }

    // Sorting: apps are plain package names here; az is the A–Z order.
    private final List<String> az = Arrays.asList("a", "b", "c", "d");
    private final Map<String, Integer> used = new HashMap<>();
    private final Map<String, Long> last = new HashMap<>();

    private List<String> sorted(List<String> in, Categories.Cat cat) {
        List<String> apps = new ArrayList<>(in);
        Categories.sort(apps, cat, s -> s, az, s -> used.getOrDefault(s, 0), s -> last.getOrDefault(s, 0L));
        return apps;
    }

    private static Categories.Cat cat(String sort, String... order) {
        Categories.Cat c = new Categories.Cat();
        c.id = "c";
        c.sort = sort;
        c.apps.addAll(Arrays.asList(order));
        return c;
    }

    @Test
    public void sortsAToZ() {
        assertEquals(az, sorted(Arrays.asList("d", "b", "a", "c"), cat(Categories.SORT_AZ)));
        assertEquals(az, sorted(Arrays.asList("c", "a", "d", "b"), null));
    }

    @Test
    public void mostUsedWithTiesAToZ() {
        used.put("c", 5);
        used.put("a", 2);
        used.put("d", 2);
        assertEquals(Arrays.asList("c", "a", "d", "b"), sorted(Arrays.asList("d", "b", "a", "c"), cat(Categories.SORT_USED)));
    }

    @Test
    public void recentlyUsedFirst() {
        last.put("b", 300L);
        last.put("d", 100L);
        assertEquals(Arrays.asList("b", "d", "a", "c"), sorted(az, cat(Categories.SORT_RECENT)));
    }

    @Test
    public void yourOrderThenNewAppsAToZ() {
        assertEquals(Arrays.asList("c", "a", "b", "d"), sorted(az, cat(Categories.SORT_CUSTOM, "c", "a", "gone")));
    }
}
