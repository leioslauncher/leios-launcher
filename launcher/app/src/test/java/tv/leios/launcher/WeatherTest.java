package tv.leios.launcher;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class WeatherTest {

    @Test
    public void aReadingInTheOtherUnitIsConverted() {
        assertEquals(74, Math.round(Weather.convert(23.3, false, true)));
        assertEquals(23, Math.round(Weather.convert(74, true, false)));
        assertEquals(32, Math.round(Weather.convert(0, false, true)));
        assertEquals(-40, Math.round(Weather.convert(-40, true, false)));
    }

    @Test
    public void theSameUnitIsLeftAlone() {
        assertEquals(21.5, Weather.convert(21.5, false, false), 0);
        assertEquals(70.4, Weather.convert(70.4, true, true), 0);
    }
}
