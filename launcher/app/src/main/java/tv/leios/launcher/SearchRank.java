package tv.leios.launcher;

import java.text.Normalizer;
import java.util.Locale;

/**
 * How well a name matches what was typed, for search. Pure Java, so it's unit-tested. Higher is
 * better; 0 means no match. Case, accents and punctuation don't matter ("pokemon" finds "Pokémon",
 * "disney" finds "Disney+").
 */
final class SearchRank {

    private SearchRank() {
    }

    static final int EXACT = 1000, PREFIX = 900, WORD_START = 700, ALL_WORDS = 600, CONTAINS = 400,
            INITIALS = 300, SCATTERED = 100;

    /** Lower case, no accents, punctuation as spaces, single spaces. "+" and "&" are dropped. */
    static String normalize(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        n = n.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
        return n.replaceAll(" +", " ");
    }

    static int score(String query, String text) {
        String q = normalize(query), t = normalize(text);
        if (q.isEmpty() || t.isEmpty()) return 0;
        if (t.equals(q)) return EXACT;
        // The same without spaces: "youtube" for "You Tube", "wifi" for "Wi-Fi".
        String qs = q.replace(" ", ""), ts = t.replace(" ", "");
        if (ts.equals(qs)) return EXACT - 1;
        if (t.startsWith(q) || ts.startsWith(qs)) return PREFIX - Math.min(99, t.length() - q.length());
        String[] words = t.split(" ");
        for (String w : words) if (w.startsWith(q)) return WORD_START;
        String[] qWords = q.split(" ");
        if (qWords.length > 1) {
            boolean all = true;
            for (String qw : qWords) {
                boolean found = false;
                for (String w : words) found |= w.startsWith(qw);
                all &= found;
            }
            if (all) return ALL_WORDS;
        }
        if (q.length() >= 3 && t.contains(q)) return CONTAINS;
        if (qs.length() >= 2 && words.length >= qs.length()) {
            StringBuilder initials = new StringBuilder();
            for (String w : words) initials.append(w.charAt(0));
            if (initials.toString().startsWith(qs)) return INITIALS;
        }
        if (qs.length() >= 4 && scattered(qs, ts)) return SCATTERED;
        return 0;
    }

    /** Every typed letter appears in order ("ntflx" in "netflix"), starting at the first letter. */
    private static boolean scattered(String q, String t) {
        if (q.charAt(0) != t.charAt(0)) return false;
        int i = 0;
        for (int j = 0; j < t.length() && i < q.length(); j++) if (t.charAt(j) == q.charAt(i)) i++;
        return i == q.length();
    }
}
