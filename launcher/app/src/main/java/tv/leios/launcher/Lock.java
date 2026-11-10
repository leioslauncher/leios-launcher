package tv.leios.launcher;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Set;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * The PIN (Customize › Lock & backup › PIN lock). Stored only as a salted PBKDF2 hash, never the
 * digits. What it locks: Customize and Android's Settings, chosen apps and categories, and guest
 * mode's way out; optionally only during a schedule (bedtime). After the right PIN, things stay
 * open for a few minutes.
 *
 * <p>A family lock, not security: anyone with ADB, or who resets the projector, gets past it.
 * Forgotten PIN: {@code adb shell am broadcast -n tv.leios.launcher/.Lock\$Reset} (only ADB can send it).
 */
final class Lock {

    private Lock() {
    }

    static final int DIGITS = 4;
    private static final int ITERATIONS = 20_000;
    private static final long OPEN_FOR = 5 * 60_000L;
    private static long unlockedUntil;

    private static SharedPreferences prefs(Context c) {
        return SafePrefs.focus(c);
    }

    static boolean hasPin(Context c) {
        return prefs(c).contains("pin_hash");
    }

    static void setPin(Context c, String pin) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        prefs(c).edit()
                .putString("pin_salt", java.util.Base64.getEncoder().encodeToString(salt))
                .putString("pin_hash", java.util.Base64.getEncoder().encodeToString(hash(pin, salt)))
                .apply();
        unlockedUntil = System.currentTimeMillis() + OPEN_FOR;
    }

    /** Turns the lock off completely: PIN, locked apps and categories, guest mode, schedule. */
    static void clear(Context c) {
        prefs(c).edit().remove("pin_hash").remove("pin_salt").remove("lock_settings").remove("locked_apps")
                .remove("locked_cats").remove("guest").remove("lock_sched").apply();
        unlockedUntil = 0;
    }

    static boolean check(Context c, String pin) {
        SharedPreferences p = prefs(c);
        try {
            byte[] salt = java.util.Base64.getDecoder().decode(p.getString("pin_salt", ""));
            byte[] want = java.util.Base64.getDecoder().decode(p.getString("pin_hash", ""));
            boolean ok = want.length > 0 && java.security.MessageDigest.isEqual(want, hash(pin, salt));
            if (ok) unlockedUntil = System.currentTimeMillis() + OPEN_FOR;
            return ok;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** PBKDF2-HMAC-SHA256: the same PIN and salt always give the same bytes. */
    static byte[] hash(String pin, byte[] salt) {
        try {
            KeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            return new byte[0];
        }
    }

    /** Forget the "recently unlocked" grace (the projector went to sleep, or you locked it now). */
    static void relock() {
        unlockedUntil = 0;
    }

    /** Locks apply now: a PIN is set, it isn't open from a recent unlock, and the schedule allows. */
    static boolean active(Context c) {
        if (!hasPin(c) || System.currentTimeMillis() < unlockedUntil) return false;
        Calendar now = Calendar.getInstance();
        return inSchedule(prefs(c).getString("lock_sched", ""),
                now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE));
    }

    /**
     * "from-to" in minutes after midnight (it may wrap past midnight: 1260-420 is 21:00 to 7:00).
     * Empty means always; a damaged value also means always, so a lock never silently turns off.
     */
    static boolean inSchedule(String sched, int minuteOfDay) {
        if (sched == null || sched.isEmpty()) return true;
        String[] p = sched.split("-");
        if (p.length != 2) return true;
        int from, to;
        try {
            from = Integer.parseInt(p[0].trim());
            to = Integer.parseInt(p[1].trim());
        } catch (NumberFormatException e) {
            return true;
        }
        if (from == to) return true;
        return from < to ? minuteOfDay >= from && minuteOfDay < to : minuteOfDay >= from || minuteOfDay < to;
    }

    static boolean settingsLocked(Context c) {
        return active(c) && (prefs(c).getBoolean("lock_settings", true) || guest(c));
    }

    static boolean guest(Context c) {
        return prefs(c).getBoolean("guest", false);
    }

    static Set<String> lockedApps(Context c) {
        return new HashSet<>(Arrays.asList(prefs(c).getString("locked_apps", "").split(",")));
    }

    static Set<String> lockedCats(Context c) {
        return new HashSet<>(Arrays.asList(prefs(c).getString("locked_cats", "").split(",")));
    }

    /** An app is locked by itself, or by being in a locked category. */
    static boolean appLocked(Context c, String pkg, String categoryId) {
        if (!active(c)) return false;
        return lockedApps(c).contains(pkg) || (categoryId != null && lockedCats(c).contains(categoryId));
    }

    static void toggle(Context c, String key, String value) {
        Set<String> s = new HashSet<>(Arrays.asList(prefs(c).getString(key, "").split(",")));
        s.remove("");
        if (!s.remove(value)) s.add(value);
        prefs(c).edit().putString(key, android.text.TextUtils.join(",", s)).apply();
    }

    /** Only the system and ADB can send this (it needs the DUMP permission): the forgotten-PIN way out. */
    public static class Reset extends android.content.BroadcastReceiver {
        @Override
        public void onReceive(Context c, android.content.Intent i) {
            clear(c);
            android.widget.Toast.makeText(c, R.string.toast_pin_removed, android.widget.Toast.LENGTH_LONG).show();
        }
    }
}
