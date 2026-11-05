package tv.leios.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class TtlCacheTest {

    @Test
    public void entriesExpire() {
        TtlCache<String> c = new TtlCache<>(10, 1000);
        c.put("a", "1", 0);
        assertEquals("1", c.get("a", 999));
        assertNull(c.get("a", 1000));
        assertEquals(0, c.size());
    }

    @Test
    public void clockGoingBackwardsExpiresToo() {
        TtlCache<String> c = new TtlCache<>(10, 1000);
        c.put("a", "1", 500);
        assertNull(c.get("a", 100));
    }

    @Test
    public void leastRecentlyUsedGoesFirst() {
        TtlCache<String> c = new TtlCache<>(2, 1000);
        c.put("a", "1", 0);
        c.put("b", "2", 0);
        c.get("a", 1);
        c.put("c", "3", 2);
        assertEquals("1", c.get("a", 3));
        assertNull(c.get("b", 3));
        assertEquals("3", c.get("c", 3));
    }
}
