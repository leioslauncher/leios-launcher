package tv.leios.launcher;

import java.text.Normalizer;

/**
 * How well a name matches what was typed, for search. Pure Java, so it's unit-tested. Higher is
 * better; 0 means no match. Case, accents and punctuation don't matter ("pokemon" finds "Pokémon",
 * "disney" finds "Disney+").
 *
 * <p>Speed matters: search scores hundreds of names on every key press, on a slow TV CPU. So names
 * are normalised once ({@link #prepare}), and each press only compares prepared strings, with no
 * regular expressions.
 */
final class SearchRank {

    private SearchRank() {
    }

    static final int EXACT = 1000, PREFIX = 900, WORD_START = 700, ALL_WORDS = 600, CONTAINS = 400,
            INITIALS = 300, SCATTERED = 100;

    /** A name made ready for matching: normalised, without spaces, split into words, with initials. */
    static final class Prepared {
        final String text, joined, initials;
        final String[] words;

        private Prepared(String text) {
            this.text = text;
            this.joined = text.replace(" ", "");
            this.words = text.isEmpty() ? new String[0] : text.split(" ");
            StringBuilder sb = new StringBuilder(words.length);
            for (String w : words) sb.append(w.charAt(0));
            this.initials = sb.toString();
        }
    }

    static Prepared prepare(String s) {
        return new Prepared(normalize(s));
    }

    /** Lower case, no accents, punctuation as spaces, single spaces. "+" and "&" are dropped. */
    static String normalize(String s) {
        if (s == null || s.isEmpty()) return "";
        boolean ascii = true;
        for (int i = 0; i < s.length() && ascii; i++) ascii = s.charAt(i) < 128;
        // Accents come off only when there are any (decomposing costs more than the rest).
        String src = ascii ? s : Normalizer.normalize(s, Normalizer.Form.NFD);
        StringBuilder out = new StringBuilder(src.length());
        boolean space = false;
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            if (!ascii && Character.getType(c) == Character.NON_SPACING_MARK) continue;
            if (Character.isLetterOrDigit(c)) {
                if (space && out.length() > 0) out.append(' ');
                space = false;
                out.append(Character.toLowerCase(c));
            } else {
                space = true;
            }
        }
        return out.toString();
    }

    static int score(String query, String text) {
        return score(prepare(query), prepare(text));
    }

    static int score(Prepared q, Prepared t) {
        String qt = q.text, tt = t.text;
        if (qt.isEmpty() || tt.isEmpty()) return 0;
        if (tt.equals(qt)) return EXACT;
        // The same without spaces: "youtube" for "You Tube", "wifi" for "Wi-Fi".
        if (t.joined.equals(q.joined)) return EXACT - 1;
        if (tt.startsWith(qt) || t.joined.startsWith(q.joined)) return PREFIX - Math.min(99, tt.length() - qt.length());
        for (String w : t.words) if (w.startsWith(qt)) return WORD_START;
        if (q.words.length > 1) {
            boolean all = true;
            for (String qw : q.words) {
                boolean found = false;
                for (String w : t.words) {
                    if (w.startsWith(qw)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    all = false;
                    break;
                }
            }
            if (all) return ALL_WORDS;
        }
        if (qt.length() >= 3 && tt.contains(qt)) return CONTAINS;
        if (q.joined.length() >= 2 && t.initials.startsWith(q.joined)) return INITIALS;
        if (q.joined.length() >= 4 && scattered(q.joined, t.joined)) return SCATTERED;
        return 0;
    }

    /** Every typed letter appears in order ("ntflx" in "netflix"), starting at the first letter. */
    private static boolean scattered(String q, String t) {
        if (t.isEmpty() || q.charAt(0) != t.charAt(0)) return false;
        int i = 0;
        for (int j = 0; j < t.length() && i < q.length(); j++) if (t.charAt(j) == q.charAt(i)) i++;
        return i == q.length();
    }
}
