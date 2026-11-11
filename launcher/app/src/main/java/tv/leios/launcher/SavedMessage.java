package tv.leios.launcher;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * A message saved to show after Home restarts itself (a theme, a restore, a reset). Saved as the
 * string resource's name plus its values, never as a sentence, so it shows in the language Leios
 * has when it appears. Pref "toast_msg"; "toast" held a sentence before 1.4 and is shown as it is.
 */
final class SavedMessage {

    static final String PREF = "toast_msg";
    /** What 1.3 and earlier saved: a finished English sentence. */
    static final String LEGACY_PREF = "toast";

    final String key;
    final String[] args;

    SavedMessage(String key, String... args) {
        this.key = key;
        this.args = args;
    }

    String encode() {
        try {
            JSONObject o = new JSONObject();
            o.put("key", key);
            JSONArray a = new JSONArray();
            for (String s : args) a.put(s);
            o.put("args", a);
            return o.toString();
        } catch (JSONException e) {
            return null;
        }
    }

    /** The saved message, or null if there's none or it can't be read (it's then dropped). */
    static SavedMessage decode(String saved) {
        if (saved == null || saved.isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(saved);
            String key = o.getString("key");
            if (!key.matches("[a-z0-9_]+")) return null;
            JSONArray a = o.optJSONArray("args");
            List<String> args = new ArrayList<>();
            for (int i = 0; a != null && i < a.length(); i++) args.add(a.optString(i, ""));
            return new SavedMessage(key, args.toArray(new String[0]));
        } catch (JSONException e) {
            return null;
        }
    }
}
