package tv.leios.launcher;

import android.os.IBinder;
import android.os.Parcel;

/**
 * The same projector-settings calls JMGO's own Brightness screen makes:
 * system service "jmgomiddle-01" hands out the DLP service, whose get/set take a key.
 * Only the keys below are ever used; nothing else is sent to the service.
 */
final class JmgoDlp {

    /** Projector brightness, 0..100; the Settings screen shows it as 1..10 (value = (step-1) * 11). */
    static final int KEY_BACK_LIGHT = 512;

    private static final String SYSTEM = "com.jmgo.middleware.service.IJmGOSystemService";
    private static final String DLP = "com.jmgo.middleware.dlp.IJmGODlpManagerService";
    private static final int SYSTEM_GET_DLP = 5;
    private static final int DLP_SET = 1, DLP_GET = 2;

    private static IBinder dlp, system;

    private JmgoDlp() {
    }

    /**
     * Ordinary apps can't look up "jmgomiddle-01" (SELinux), but JMGO's hippo app hands out the
     * same IJmGOSystemService through its bindable LocalService. Call once; stays bound.
     */
    static void connect(android.content.Context ctx) {
        if (system != null && system.isBinderAlive()) return;
        try {
            ctx.getApplicationContext().bindService(new android.content.Intent("action.jmgo.middleware.service")
                    .setPackage("com.jmgo.hippo"), new android.content.ServiceConnection() {
                @Override
                public void onServiceConnected(android.content.ComponentName n, IBinder b) {
                    system = b;
                    dlp = null;
                }

                @Override
                public void onServiceDisconnected(android.content.ComponentName n) {
                    system = null;
                    dlp = null;
                }
            }, android.content.Context.BIND_AUTO_CREATE);
        } catch (RuntimeException ignored) {
        }
    }

    static boolean available() {
        return system != null && system.isBinderAlive();
    }

    /** The value, or -1 if the service can't be reached. */
    static int get(int key) {
        return call(DLP_GET, key, 0, false);
    }

    /** Returns the service's result, or -1 if it can't be reached. */
    static int set(int key, int value) {
        return call(DLP_SET, key, value, true);
    }

    /** Brightness as the 1..10 steps the projector's own screen shows; -1 if unavailable. */
    static int brightnessStep() {
        int v = get(KEY_BACK_LIGHT);
        if (v < 0) return -1;
        return v >= 100 ? 10 : Math.max(1, v / 11 + 1);
    }

    static boolean setBrightnessStep(int step) {
        step = Math.max(1, Math.min(10, step));
        return set(KEY_BACK_LIGHT, step >= 10 ? 100 : (step - 1) * 11) >= 0;
    }

    private static synchronized int call(int code, int key, int value, boolean withValue) {
        IBinder b = service();
        if (b == null) return -1;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DLP);
            data.writeInt(key);
            if (withValue) data.writeInt(value);
            b.transact(code, data, reply, 0);
            reply.readException();
            return reply.readInt();
        } catch (Exception e) {
            dlp = null;
            return -1;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static IBinder service() {
        if (dlp != null && dlp.isBinderAlive()) return dlp;
        if (system == null || !system.isBinderAlive()) return null;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SYSTEM);
            system.transact(SYSTEM_GET_DLP, data, reply, 0);
            reply.readException();
            dlp = reply.readStrongBinder();
            return dlp;
        } catch (Exception e) {
            return null;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }
}
