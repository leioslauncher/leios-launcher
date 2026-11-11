package tv.leios.launcher;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class SavedMessageTest {

    @Test
    public void keyAndValuesRoundTrip() {
        SavedMessage m = SavedMessage.decode(new SavedMessage("toast_theme_applied", "Movie night").encode());
        assertEquals("toast_theme_applied", m.key);
        assertArrayEquals(new String[] {"Movie night"}, m.args);
        assertEquals(0, SavedMessage.decode(new SavedMessage("toast_backup_restored").encode()).args.length);
    }

    @Test
    public void anythingElseIsDroppedWithoutCrashing() {
        assertNull(SavedMessage.decode(null));
        assertNull(SavedMessage.decode(""));
        // A sentence saved by 1.3 (it lives under the old pref, but must never break this one).
        assertNull(SavedMessage.decode("Backup restored"));
        assertNull(SavedMessage.decode("{\"args\":[]}"));
        assertNull(SavedMessage.decode("{\"key\":\"../evil\"}"));
        assertNull(SavedMessage.decode("{\"key\":"));
    }
}
