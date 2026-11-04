package tv.leios.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SoundsTest {

    @Test
    public void separatePressesAlwaysTickOnceApart() {
        Sounds.Limiter l = new Sounds.Limiter();
        assertEquals(1f, l.next(1000, false), 0f);
        assertEquals(1f, l.next(1200, false), 0f);
    }

    @Test
    public void ticksNeverCloserThan90Ms() {
        Sounds.Limiter l = new Sounds.Limiter();
        assertTrue(l.next(1000, false) > 0f);
        assertEquals(0f, l.next(1050, true), 0f);
        assertEquals(0f, l.next(1089, true), 0f);
        assertTrue(l.next(1090, true) > 0f);
    }

    @Test
    public void heldKeyFadesToSilence() {
        Sounds.Limiter l = new Sounds.Limiter();
        l.next(0, false);
        float last = 1f;
        long t = 0;
        for (int i = 0; i < Sounds.Limiter.FADE_AFTER; i++) assertEquals(1f, l.next(t += 100, true), 0f);
        for (int i = 0; i < Sounds.Limiter.FADE_OVER; i++) {
            float g = l.next(t += 100, true);
            assertTrue(g < last);
            last = g;
        }
        assertEquals(0f, last, 0f);
        assertEquals(0f, l.next(t += 100, true), 0f);
    }

    @Test
    public void aNewPressAfterHoldingIsHeardAgain() {
        Sounds.Limiter l = new Sounds.Limiter();
        long t = 0;
        for (int i = 0; i < 30; i++) l.next(t += 100, true);
        assertEquals(1f, l.next(t + 100, false), 0f);
    }

    @Test
    public void gainRange() {
        assertEquals(0f, Sounds.gain(Sounds.MODE_OFF, 5), 0f);
        assertTrue(Sounds.gain(Sounds.MODE_SUBTLE, Sounds.LEVEL_DEFAULT) < 0.5f);
        assertTrue(Sounds.gain(Sounds.MODE_NORMAL, 5) <= 1f);
        assertTrue(Sounds.gain(Sounds.MODE_NORMAL, 3) > Sounds.gain(Sounds.MODE_SUBTLE, 3));
    }

    @Test
    public void gainCopesWithBadLevels() {
        assertEquals(Sounds.gain(Sounds.MODE_SUBTLE, 1), Sounds.gain(Sounds.MODE_SUBTLE, -3), 0f);
        assertEquals(Sounds.gain(Sounds.MODE_SUBTLE, 5), Sounds.gain(Sounds.MODE_SUBTLE, 99), 0f);
    }

    @Test
    public void automaticUsesSystemSoundsWhileTheyCanBeHeard() {
        assertEquals(Sounds.ROUTE_SYSTEM, Sounds.route(Sounds.OUTPUT_AUTO, false, 26));
    }

    @Test
    public void automaticFallsBackToMediaWhenSystemIsMuted() {
        // The JMGO N3 4K: Android mutes the System channel at start, its level still reads 26.
        assertEquals(Sounds.ROUTE_MEDIA, Sounds.route(Sounds.OUTPUT_AUTO, true, 26));
    }

    @Test
    public void automaticFallsBackToMediaWhenSystemIsAtZero() {
        assertEquals(Sounds.ROUTE_MEDIA, Sounds.route(Sounds.OUTPUT_AUTO, false, 0));
    }

    @Test
    public void aChosenOutputIsAlwaysKept() {
        assertEquals(Sounds.ROUTE_SYSTEM, Sounds.route(Sounds.OUTPUT_SYSTEM, true, 0));
        assertEquals(Sounds.ROUTE_MEDIA, Sounds.route(Sounds.OUTPUT_MEDIA, false, 26));
    }

    @Test
    public void unknownOutputBehavesLikeAutomatic() {
        assertEquals(Sounds.ROUTE_MEDIA, Sounds.route(7, true, 26));
        assertEquals(Sounds.ROUTE_SYSTEM, Sounds.route(-1, false, 26));
    }

    @Test
    public void audibleFollowsTheChannelInUse() {
        assertFalse(Sounds.audible(Sounds.ROUTE_SYSTEM, true, 26, false, 26));
        assertTrue(Sounds.audible(Sounds.ROUTE_MEDIA, true, 26, false, 26));
        assertFalse(Sounds.audible(Sounds.ROUTE_MEDIA, false, 26, true, 26));
        assertFalse(Sounds.audible(Sounds.ROUTE_MEDIA, false, 26, false, 0));
        assertTrue(Sounds.audible(Sounds.ROUTE_SYSTEM, false, 5, true, 0));
    }

    @Test
    public void noScheduleIsNeverBedtime() {
        assertFalse(Sounds.bedtime("", 23 * 60));
        assertFalse(Sounds.bedtime(null, 23 * 60));
    }

    @Test
    public void bedtimeCrossesMidnight() {
        String night = (21 * 60) + "-" + (7 * 60);
        assertTrue(Sounds.bedtime(night, 23 * 60));
        assertTrue(Sounds.bedtime(night, 6 * 60));
        assertFalse(Sounds.bedtime(night, 12 * 60));
    }
}
