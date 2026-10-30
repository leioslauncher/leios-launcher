package tv.leios.launcher;

import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** SharedPreferences in memory, for tests on the computer. */
final class FakePrefs implements SharedPreferences {

    final Map<String, Object> values = new HashMap<>();

    @Override
    public Map<String, ?> getAll() {
        return new HashMap<>(values);
    }

    @Override
    public String getString(String k, String d) {
        Object v = values.get(k);
        return v instanceof String ? (String) v : d;
    }

    @SuppressWarnings("unchecked")
    @Override
    public Set<String> getStringSet(String k, Set<String> d) {
        Object v = values.get(k);
        return v instanceof Set ? (Set<String>) v : d;
    }

    @Override
    public int getInt(String k, int d) {
        Object v = values.get(k);
        return v instanceof Integer ? (Integer) v : d;
    }

    @Override
    public long getLong(String k, long d) {
        Object v = values.get(k);
        return v instanceof Long ? (Long) v : d;
    }

    @Override
    public float getFloat(String k, float d) {
        Object v = values.get(k);
        return v instanceof Float ? (Float) v : d;
    }

    @Override
    public boolean getBoolean(String k, boolean d) {
        Object v = values.get(k);
        return v instanceof Boolean ? (Boolean) v : d;
    }

    @Override
    public boolean contains(String k) {
        return values.containsKey(k);
    }

    @Override
    public Editor edit() {
        return new Ed();
    }

    @Override
    public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {
    }

    @Override
    public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {
    }

    private final class Ed implements Editor {
        private final Map<String, Object> put = new HashMap<>();
        private final java.util.List<String> remove = new java.util.ArrayList<>();
        private boolean clear;

        @Override
        public Editor putString(String k, String v) {
            put.put(k, v);
            return this;
        }

        @Override
        public Editor putStringSet(String k, Set<String> v) {
            put.put(k, v);
            return this;
        }

        @Override
        public Editor putInt(String k, int v) {
            put.put(k, v);
            return this;
        }

        @Override
        public Editor putLong(String k, long v) {
            put.put(k, v);
            return this;
        }

        @Override
        public Editor putFloat(String k, float v) {
            put.put(k, v);
            return this;
        }

        @Override
        public Editor putBoolean(String k, boolean v) {
            put.put(k, v);
            return this;
        }

        @Override
        public Editor remove(String k) {
            remove.add(k);
            return this;
        }

        @Override
        public Editor clear() {
            clear = true;
            return this;
        }

        @Override
        public boolean commit() {
            if (clear) values.clear();
            for (String k : remove) values.remove(k);
            values.putAll(put);
            return true;
        }

        @Override
        public void apply() {
            commit();
        }
    }
}
