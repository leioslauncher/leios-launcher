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
    public void keepsAtMostMax() {
        List<Titles.Title> tmdb = Arrays.asList(t("A", 1, 1), t("Ab", 2, 2), t("Abc", 3, 3));
        assertEquals(2, Titles.rank("a", tmdb, 2).size());
    }
}
