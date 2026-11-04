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
        assertEquals(0f, Sounds.gain(0), 0f);
        assertTrue(Sounds.gain(Sounds.VOLUME_FIRST) < 0.5f);
        assertEquals(1f, Sounds.gain(Sounds.VOLUME_MAX), 0f);
        assertTrue(Sounds.gain(6) > Sounds.gain(5));
    }

    @Test
    public void gainCopesWithBadVolumes() {
        assertEquals(0f, Sounds.gain(-3), 0f);
        assertEquals(1f, Sounds.gain(99), 0f);
    }

    @Test
    public void oldOffStaysOff() {
        assertEquals(0, Sounds.fromOld(0, 5));
        assertEquals(0, Sounds.fromOld(-1, 3));
        assertEquals(0, Sounds.fromOld(7, 3));
    }

    @Test
    public void oldSettingsKeepTheirLoudness() {
        // Subtle at its default level 3 was 0.42 of full: about 4 of 10, the new first step.
        assertEquals(Sounds.VOLUME_FIRST, Sounds.fromOld(1, 3));
        assertEquals(Sounds.VOLUME_MAX, Sounds.fromOld(2, 5));
        assertEquals(2, Sounds.fromOld(2, 1));
        // Never rounded down to off while sounds were on.
        assertEquals(1, Sounds.fromOld(1, 1));
        assertEquals(Sounds.fromOld(1, 1), Sounds.fromOld(1, -4));
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
