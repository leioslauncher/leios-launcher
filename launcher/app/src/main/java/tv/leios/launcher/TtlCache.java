package tv.leios.launcher;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A small in-memory cache: entries expire after {@code ttlMs}, and the least recently used goes
 * first when it's full. Time is passed in, so tests don't wait. Not thread-safe: callers lock.
 */
final class TtlCache<V> {

    private static final class Item<V> {
        final long at;
        final V value;

        Item(long at, V value) {
            this.at = at;
            this.value = value;
        }
    }

    private final long ttlMs;
    private final LinkedHashMap<String, Item<V>> map;

    TtlCache(int maxEntries, long ttlMs) {
        this.ttlMs = ttlMs;
        this.map = new LinkedHashMap<String, Item<V>>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Item<V>> eldest) {
                return size() > maxEntries;
            }
        };
    }

    V get(String key, long now) {
        Item<V> i = map.get(key);
        if (i == null) return null;
        if (now - i.at >= ttlMs || now < i.at) {
            map.remove(key);
            return null;
        }
        return i.value;
    }

    void put(String key, V value, long now) {
        map.put(key, new Item<>(now, value));
    }

    void clear() {
        map.clear();
    }

    int size() {
        return map.size();
    }
}
