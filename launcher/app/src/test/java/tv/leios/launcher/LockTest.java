package tv.leios.launcher;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public class LockTest {

    private static int at(int h, int m) {
        return h * 60 + m;
    }

    @Test
    public void alwaysWhenThereIsNoSchedule() {
        assertTrue(Lock.inSchedule("", at(12, 0)));
        assertTrue(Lock.inSchedule(null, at(3, 0)));
    }

    @Test
    public void bedtimeAcrossMidnight() {
        String bed = "1260-420"; // 21:00 to 7:00
        assertFalse(Lock.inSchedule(bed, at(20, 59)));
        assertTrue(Lock.inSchedule(bed, at(21, 0)));
        assertTrue(Lock.inSchedule(bed, at(23, 59)));
        assertTrue(Lock.inSchedule(bed, at(0, 0)));
        assertTrue(Lock.inSchedule(bed, at(6, 59)));
        assertFalse(Lock.inSchedule(bed, at(7, 0)));
        assertFalse(Lock.inSchedule(bed, at(12, 0)));
    }

    @Test
    public void scheduleWithinOneDay() {
        String school = "480-900"; // 8:00 to 15:00
        assertFalse(Lock.inSchedule(school, at(7, 59)));
        assertTrue(Lock.inSchedule(school, at(8, 0)));
        assertTrue(Lock.inSchedule(school, at(14, 59)));
        assertFalse(Lock.inSchedule(school, at(15, 0)));
    }

    @Test
    public void aDamagedScheduleKeepsTheLockOn() {
        assertTrue(Lock.inSchedule("bedtime", at(12, 0)));
        assertTrue(Lock.inSchedule("12-", at(12, 0)));
        assertTrue(Lock.inSchedule("a-b", at(12, 0)));
        assertTrue(Lock.inSchedule("600-600", at(3, 0)));
    }

    @Test
    public void theHashIsStableAndSalted() {
        byte[] salt1 = new byte[16], salt2 = new byte[16];
        Arrays.fill(salt2, (byte) 7);
        byte[] h = Lock.hash("1234", salt1);
        assertEquals(32, h.length);
        assertArrayEquals(h, Lock.hash("1234", salt1));
        assertFalse(Arrays.equals(h, Lock.hash("1235", salt1)));
        assertFalse(Arrays.equals(h, Lock.hash("1234", salt2)));
    }
}
