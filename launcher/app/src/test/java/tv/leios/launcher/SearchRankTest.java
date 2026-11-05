package tv.leios.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SearchRankTest {

    @Test
    public void normalizeIgnoresCaseAccentsAndPunctuation() {
        assertEquals("pokemon", SearchRank.normalize("Pokémon"));
        assertEquals("disney", SearchRank.normalize("Disney+"));
        assertEquals("wi fi", SearchRank.normalize("  Wi-Fi "));
        assertEquals("", SearchRank.normalize(null));
    }

    @Test
    public void exactBeatsPrefixBeatsWordBeatsContains() {
        int exact = SearchRank.score("netflix", "Netflix");
        int prefix = SearchRank.score("net", "Netflix");
        int word = SearchRank.score("video", "Prime Video");
        int contains = SearchRank.score("flix", "Netflix");
        assertTrue(exact > prefix);
        assertTrue(prefix > word);
        assertTrue(word > contains);
        assertTrue(contains > 0);
    }

    @Test
    public void shorterNameWinsAmongPrefixes() {
        assertTrue(SearchRank.score("you", "YouTube") > SearchRank.score("you", "YouTube Music"));
    }

    @Test
    public void spacesDontMatter() {
        assertTrue(SearchRank.score("youtube", "You Tube") >= SearchRank.PREFIX);
        assertTrue(SearchRank.score("wifi", "Wi-Fi") >= SearchRank.PREFIX);
    }

    @Test
    public void allWordsAndInitialsAndScattered() {
        assertEquals(SearchRank.ALL_WORDS, SearchRank.score("pri vid", "Amazon Prime Video"));
        assertEquals(SearchRank.INITIALS, SearchRank.score("pv", "Prime Video"));
        assertEquals(SearchRank.SCATTERED, SearchRank.score("ntflx", "Netflix"));
    }

    @Test
    public void noMatch() {
        assertEquals(0, SearchRank.score("zzz", "Netflix"));
        assertEquals(0, SearchRank.score("", "Netflix"));
        // Two letters inside a word aren't enough to count.
        assertEquals(0, SearchRank.score("fl", "Netflix"));
    }
}
