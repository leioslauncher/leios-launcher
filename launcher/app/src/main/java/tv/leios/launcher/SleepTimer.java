package tv.leios.launcher;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.admin.DeviceAdminReceiver;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/**
 * Sleep timer: one alarm a minute early for a warning, one to sleep. Sleeping uses the device-admin
 * "force lock" policy, the only way an ordinary app can turn the screen off; you approve it once.
 */
final class SleepTimer {

    static final String ACTION_SLEEP = "tv.leios.launcher.SLEEP";
    static final String ACTION_WARN = "tv.leios.launcher.SLEEP_WARN";

    /** Device admin with only the force-lock policy (res/xml/device_admin.xml). */
    public static class Admin extends DeviceAdminReceiver {
    }

    /** Receives the two alarms. */
    public static class Receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            if (ACTION_WARN.equals(i.getAction())) {
                Toast.makeText(c, R.string.toast_sleep_one_minute, Toast.LENGTH_LONG).show();
            } else if (ACTION_SLEEP.equals(i.getAction())) {
                c.getSharedPreferences("focus", Context.MODE_PRIVATE).edit().remove("sleep_at").apply();
                if (isReady(c)) c.getSystemService(DevicePolicyManager.class).lockNow();
            }
        }
    }

    static ComponentName admin(Context c) {
        return new ComponentName(c, Admin.class);
    }

    static boolean isReady(Context c) {
        DevicePolicyManager dpm = c.getSystemService(DevicePolicyManager.class);
        return dpm != null && dpm.isAdminActive(admin(c));
    }

    static void set(Context c, int minutes) {
        cancel(c);
        long at = System.currentTimeMillis() + minutes * 60_000L;
        AlarmManager am = c.getSystemService(AlarmManager.class);
        // setWindow needs no exact-alarm permission; a 30 s window is fine for a sleep timer.
        am.setWindow(AlarmManager.RTC_WAKEUP, at - 60_000L, 30_000L, pending(c, ACTION_WARN));
        am.setWindow(AlarmManager.RTC_WAKEUP, at, 30_000L, pending(c, ACTION_SLEEP));
        c.getSharedPreferences("focus", Context.MODE_PRIVATE).edit().putLong("sleep_at", at).apply();
    }

    static void cancel(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        am.cancel(pending(c, ACTION_WARN));
        am.cancel(pending(c, ACTION_SLEEP));
        c.getSharedPreferences("focus", Context.MODE_PRIVATE).edit().remove("sleep_at").apply();
    }

    /** Minutes left, or 0 when no timer is running. */
    static int minutesLeft(Context c) {
        long at = SafePrefs.focus(c).getLong("sleep_at", 0);
        long left = at - System.currentTimeMillis();
        return left <= 0 ? 0 : (int) Math.ceil(left / 60_000.0);
    }

    private static PendingIntent pending(Context c, String action) {
        return PendingIntent.getBroadcast(c, action.hashCode(),
                new Intent(c, Receiver.class).setAction(action),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
