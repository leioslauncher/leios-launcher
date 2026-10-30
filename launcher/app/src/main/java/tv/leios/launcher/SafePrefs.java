package tv.leios.launcher;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;
import java.util.Set;

/**
 * Settings that read a value of the wrong type (from a hand-edited or old backup or theme) as its
 * default instead of throwing, so a bad file can never stop Home from starting.
 */
final class SafePrefs implements SharedPreferences {

    private final SharedPreferences p;

    SafePrefs(SharedPreferences p) {
        this.p = p;
    }

    /** Home's settings ("focus"). */
    static SharedPreferences focus(Context c) {
        return new SafePrefs(c.getSharedPreferences("focus", Context.MODE_PRIVATE));
    }

    static SharedPreferences usage(Context c) {
        return new SafePrefs(c.getSharedPreferences("usage", Context.MODE_PRIVATE));
    }

    @Override
    public Map<String, ?> getAll() {
        return p.getAll();
    }

    @Override
    public String getString(String key, String def) {
        try {
            return p.getString(key, def);
        } catch (ClassCastException e) {
            return def;
        }
    }

    @Override
    public Set<String> getStringSet(String key, Set<String> def) {
        try {
            return p.getStringSet(key, def);
        } catch (ClassCastException e) {
            return def;
        }
    }

    @Override
    public int getInt(String key, int def) {
        try {
            return p.getInt(key, def);
        } catch (ClassCastException e) {
            return def;
        }
    }

    @Override
    public long getLong(String key, long def) {
        try {
            return p.getLong(key, def);
        } catch (ClassCastException e) {
            return def;
        }
    }

    @Override
    public float getFloat(String key, float def) {
        try {
            return p.getFloat(key, def);
        } catch (ClassCastException e) {
            return def;
        }
    }

    @Override
    public boolean getBoolean(String key, boolean def) {
        try {
            return p.getBoolean(key, def);
        } catch (ClassCastException e) {
            return def;
        }
    }

    @Override
    public boolean contains(String key) {
        return p.contains(key);
    }

    @Override
    public Editor edit() {
        return p.edit();
    }

    @Override
    public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {
        p.registerOnSharedPreferenceChangeListener(l);
    }

    @Override
    public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {
        p.unregisterOnSharedPreferenceChangeListener(l);
    }
}
