package tv.leios.launcher;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class TitlesRankTest {

    private static Titles.Title t(String name, double popularity, int votes) {
        Titles.Title t = new Titles.Title(name.hashCode(), true, name, "", "");
        t.popularity = popularity;
        t.votes = votes;
        return t;
    }

    @Test
    public void famousTitleBeatsObscureOnesWithTheSameStart() {
        // As TMDB returned "severa": little-known matches first, Severance far down.
        List<Titles.Title> tmdb = Arrays.asList(t("Severa", 0.6, 0), t("Severan", 1.2, 3),
                t("Severance Pay", 2.0, 10), t("Severance", 180.0, 2300));
        assertEquals("Severance", Titles.rank("severa", tmdb, 5).get(0).name);
    }

    @Test
    public void theBoysFirstForTheBo() {
        List<Titles.Title> tmdb = Arrays.asList(t("The Bob's Burgers Movie", 30, 900), t("The Boy", 40, 4000),
                t("The Bodyguard", 20, 3000), t("The Boys", 400, 11000), t("The Book of Life", 25, 2800));
        assertEquals("The Boys", Titles.rank("the bo", tmdb, 5).get(0).name);
    }

    @Test
    public void aMatchAtTheStartBeatsAFamousMatchInTheMiddle() {
        List<Titles.Title> tmdb = Arrays.asList(t("Love, Death & Robots", 300, 5000), t("Deathloop", 5, 50));
        assertEquals("Deathloop", Titles.rank("death", tmdb, 5).get(0).name);
    }

    @Test
    public void wellKnownListFillsInWhatTmdbSearchMisses() {
        // TMDB's search for "the bo" never contains The Boys (it matches whole words only).
        List<Titles.Title> searched = Arrays.asList(t("Bo! in the USA", 0.6, 0), t("The Love Boat", 128, 208));
        List<Titles.Title> known = Arrays.asList(t("The Boys", 89, 13653), t("Breaking Bad", 300, 15000));
        List<Titles.Title> merged = Titles.merge("the bo", searched, known, 10);
        assertEquals("The Boys", merged.get(0).name);
        // Breaking Bad doesn't match, so it isn't added.
        for (Titles.Title x : merged) org.junit.Assert.assertNotEquals("Breaking Bad", x.name);
    }

    @Test
    public void mergeWithoutAListIsJustTheSearch() {
        List<Titles.Title> searched = Arrays.asList(t("Severance", 180, 2300));
        assertEquals(1, Titles.merge("sev", searched, null, 10).size());
    }

    @Test
    public void keepsAtMostMax() {
        List<Titles.Title> tmdb = Arrays.asList(t("A", 1, 1), t("Ab", 2, 2), t("Abc", 3, 3));
        assertEquals(2, Titles.rank("a", tmdb, 2).size());
    }
}
