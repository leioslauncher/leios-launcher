package tv.leios.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.kxml2.io.KXmlParser;
import org.xmlpull.v1.XmlPullParser;

import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;

public class IconPacksTest {

    private static Map<String, String> parse(String xml) {
        Map<String, String> map = new HashMap<>();
        try {
            XmlPullParser p = new KXmlParser();
            p.setInput(new StringReader(xml));
            IconPacks.parse(p, map);
        } catch (Exception broken) {
            // A broken file keeps what was read before the break.
        }
        return map;
    }

    @Test
    public void readsComponentsAndPackages() {
        Map<String, String> m = parse("<resources>"
                + "<item component=\"ComponentInfo{com.netflix.ninja/com.netflix.ninja.MainActivity}\" drawable=\"netflix\"/>"
                + "<item component=\"ComponentInfo{com.app/.Main}\" drawable=\"app\"/>"
                + "</resources>");
        assertEquals("netflix", m.get("com.netflix.ninja/com.netflix.ninja.MainActivity"));
        assertEquals("netflix", m.get("com.netflix.ninja"));
        // A class starting with "." is relative to the package.
        assertEquals("app", m.get("com.app/com.app.Main"));
    }

    @Test
    public void theFirstEntryForAnAppWinsForThePackage() {
        Map<String, String> m = parse("<resources>"
                + "<item component=\"ComponentInfo{com.x/com.x.A}\" drawable=\"first\"/>"
                + "<item component=\"ComponentInfo{com.x/com.x.B}\" drawable=\"second\"/>"
                + "</resources>");
        assertEquals("first", m.get("com.x"));
        assertEquals("second", m.get("com.x/com.x.B"));
    }

    @Test
    public void skipsItemsThatArentApps() {
        Map<String, String> m = parse("<resources>"
                + "<item component=\":LAUNCHER_ACTION_APP_DRAWER\" drawable=\"drawer\"/>"
                + "<item component=\"ComponentInfo{}\" drawable=\"empty\"/>"
                + "<item component=\"ComponentInfo{com.y/}\" drawable=\"noclass\"/>"
                + "<item component=\"ComponentInfo{com.z/com.z.M}\" drawable=\"\"/>"
                + "<item drawable=\"nocomponent\"/>"
                + "<scale factor=\"0.8\"/><iconback img1=\"back\"/>"
                + "</resources>");
        assertEquals(0, m.size());
    }

    @Test
    public void aBrokenFileKeepsWhatCameBefore() {
        Map<String, String> m = parse("<resources>"
                + "<item component=\"ComponentInfo{com.good/com.good.Main}\" drawable=\"good\"/>"
                + "<item component=\"ComponentInfo{com.bad/com.bad.Main}\" drawable=");
        assertEquals("good", m.get("com.good"));
        assertNull(m.get("com.bad"));
    }

    @Test
    public void anEmptyFileMatchesNothing() {
        assertFalse(parse("<resources/>").containsKey("anything"));
        assertEquals(0, parse("").size());
    }

    @Test
    public void parserErrorsAreThrownNotSwallowed() {
        try {
            XmlPullParser p = new KXmlParser();
            p.setInput(new StringReader("<resources><item"));
            IconPacks.parse(p, new HashMap<>());
            fail("expected a parse error");
        } catch (Exception expected) {
            // appfilter() catches this and keeps the partial map.
        }
    }
}
