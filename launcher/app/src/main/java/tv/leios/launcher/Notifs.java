package tv.leios.launcher;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.pm.PackageManager;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.List;

/**
 * The bell in the top bar (Customize › Look › Top bar › Notifications). Android only runs this
 * once you allow it in Settings › Privacy › Notification access; until then it does nothing.
 * It keeps titles and app names in memory for the list on Home, and nothing leaves the projector.
 */
public class Notifs extends NotificationListenerService {

    static final class Item {
        final String key, title, app;
        final PendingIntent open;
        final boolean autoCancel;

        Item(String key, String title, String app, PendingIntent open, boolean autoCancel) {
            this.key = key;
            this.title = title;
            this.app = app;
            this.open = open;
            this.autoCancel = autoCancel;
        }
    }

    private static Notifs instance;
    private static final List<Item> items = new ArrayList<>();
    /** Set by Home; called on any thread when the list changes. */
    static Runnable onChange;

    static boolean connected() {
        return instance != null;
    }

    static synchronized int count() {
        return items.size();
    }

    static synchronized List<Item> items() {
        return new ArrayList<>(items);
    }

    /** Opens what the notification points to; false if it can't be. */
    static boolean open(Item n) {
        if (n.open == null) return false;
        try {
            n.open.send();
        } catch (PendingIntent.CanceledException e) {
            return false;
        }
        if (n.autoCancel && instance != null) instance.cancelNotification(n.key);
        return true;
    }

    static void clearAll() {
        if (instance != null) instance.cancelAllNotifications();
    }

    @Override
    public void onListenerConnected() {
        instance = this;
        refresh();
    }

    @Override
    public void onListenerDisconnected() {
        instance = null;
        synchronized (Notifs.class) {
            items.clear();
        }
        changed();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        refresh();
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        refresh();
    }

    /** Only notifications you'd want to see: not ongoing (media, downloads), not group summaries. */
    private void refresh() {
        List<Item> next = new ArrayList<>();
        StatusBarNotification[] active;
        try {
            active = getActiveNotifications();
        } catch (RuntimeException e) {
            active = null;
        }
        PackageManager pm = getPackageManager();
        if (active != null) {
            for (StatusBarNotification sbn : active) {
                Notification n = sbn.getNotification();
                if (sbn.isOngoing() || !sbn.isClearable() || getPackageName().equals(sbn.getPackageName())
                        || (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) continue;
                CharSequence title = n.extras.getCharSequence(Notification.EXTRA_TITLE);
                if (title == null) title = n.extras.getCharSequence(Notification.EXTRA_TEXT);
                if (title == null) continue;
                String app;
                try {
                    app = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(sbn.getPackageName(), 0)));
                } catch (PackageManager.NameNotFoundException e) {
                    app = "";
                }
                next.add(new Item(sbn.getKey(), String.valueOf(title), app, n.contentIntent,
                        (n.flags & Notification.FLAG_AUTO_CANCEL) != 0));
            }
        }
        synchronized (Notifs.class) {
            items.clear();
            items.addAll(next);
        }
        changed();
    }

    private static void changed() {
        Runnable r = onChange;
        if (r != null) r.run();
    }
}
