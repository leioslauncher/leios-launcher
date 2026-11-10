package tv.leios.launcher;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * The app's string resources for JVM tests, read from res/values/strings*.xml (Android's
 * getString isn't available there). Unescapes the way Android does for the forms Leios uses:
 * "quoted" text keeps its spaces, \' \" \n \\u0020 are escapes, other whitespace runs become one space.
 */
final class TestStrings {

    private static Map<String, String> byName;
    private static Map<Integer, String> nameOf;

    private TestStrings() {
    }

    static synchronized Map<String, String> all() {
        if (byName != null) return byName;
        byName = new HashMap<>();
        File dir = new File("src/main/res/values");
        if (!dir.exists()) dir = new File("app/src/main/res/values");
        File[] files = dir.listFiles((d, n) -> n.startsWith("strings") && n.endsWith(".xml"));
        if (files == null) throw new IllegalStateException("No res/values in " + new File(".").getAbsolutePath());
        try {
            for (File f : files) {
                NodeList list = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
                        .getElementsByTagName("string");
                for (int i = 0; i < list.getLength(); i++) {
                    Element s = (Element) list.item(i);
                    byName.put(s.getAttribute("name"), unescape(s.getTextContent()));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return byName;
    }

    /** The resource name (R.string's field) for an id. */
    static synchronized String name(int id) {
        if (nameOf == null) {
            nameOf = new HashMap<>();
            for (Field f : R.string.class.getFields()) {
                try {
                    nameOf.put(f.getInt(null), f.getName());
                } catch (IllegalAccessException ignored) {
                }
            }
        }
        return nameOf.get(id);
    }

    /** Like Context.getString(id). */
    static String get(int id) {
        String name = name(id);
        String text = name == null ? null : all().get(name);
        if (text == null) throw new IllegalArgumentException("No string for id " + id + " (" + name + ")");
        return text;
    }

    static String unescape(String raw) {
        StringBuilder out = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (c == '\\' && i + 1 < raw.length()) {
                char n = raw.charAt(++i);
                if (n == 'n') out.append('\n');
                else if (n == 't') out.append('\t');
                else if (n == 'u') {
                    out.append((char) Integer.parseInt(raw.substring(i + 1, i + 5), 16));
                    i += 4;
                } else out.append(n);
            } else if (Character.isWhitespace(c) && !quoted) {
                if (out.length() > 0 && out.charAt(out.length() - 1) != ' ') out.append(' ');
            } else {
                out.append(c);
            }
        }
        // Android trims leading and trailing whitespace that isn't quoted or escaped.
        String s = out.toString();
        return raw.trim().startsWith("\"") || raw.contains("\\u0020") ? s : s.trim();
    }
}
