package tv.leios.launcher;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

public class SearchKeyboardTest {

    @Test
    public void theEnglishRowsFitTheKeyboard() {
        String spec = TestStrings.all().get("search_keyboard_rows");
        assertArrayEquals(SearchKeyboard.DEFAULT_ROWS, SearchKeyboard.rows(spec));
    }

    @Test
    public void aLanguageCanListItsOwnLetters() {
        String[] german = {"abcdefg", "hijklmn", "opqrstu", "vwxyzäö", "üß12345", "67890-."};
        assertArrayEquals(german, SearchKeyboard.rows(String.join("|", german)));
    }

    @Test
    public void rowsThatDontFitFallBackToTheDefault() {
        // Too few rows, a short row, a long one, or a translation tool's brackets around everything.
        assertArrayEquals(SearchKeyboard.DEFAULT_ROWS, SearchKeyboard.rows("abcdefg|hijklmn"));
        assertArrayEquals(SearchKeyboard.DEFAULT_ROWS, SearchKeyboard.rows("abcdef|hijklmn|opqrstu|vwxyz12|3456789|0'&-.:!"));
        assertArrayEquals(SearchKeyboard.DEFAULT_ROWS, SearchKeyboard.rows("[ȧƀƈḓḗƒɠ|ħīĵķŀḿƞ|ǿƥɋřşŧŭ|ṽẇẋẏẑ12|3456789|0'&-.:! one two]"));
        assertArrayEquals(SearchKeyboard.DEFAULT_ROWS, SearchKeyboard.rows(null));
    }
}
