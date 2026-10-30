package tv.leios.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.SoundPool;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.provider.Settings;

import java.util.Calendar;

/**
 * Home's sounds (Customize › Look › Sounds), off by default. Sound only adds to what's on screen.
 * Everything happens on its own thread, so a sound can never hold up a key press; the samples are
 * in memory only while Home is visible. Rendered by tools/make-sounds.py.
 */
final class Sounds {

    static final int FOCUS = 0, STEP = 1, SELECT = 2, BACK = 3, OPEN = 4, CLOSE = 5, ON = 6, OFF = 7, ERROR = 8,
            STARTUP = 9, LAUNCH = 10;
    private static final int[] RAW = {R.raw.snd_focus, R.raw.snd_step, R.raw.snd_select, R.raw.snd_back, R.raw.snd_open,
            R.raw.snd_close, R.raw.snd_toggle_on, R.raw.snd_toggle_off, R.raw.snd_error, R.raw.snd_startup, R.raw.snd_launch};

    static final int MODE_OFF = 0, MODE_SUBTLE = 1, MODE_NORMAL = 2;
    static final int LEVEL_DEFAULT = 3, LEVEL_MAX = 5;
    /** The ≡ panel's open and close, well under Home's own sounds: it plays over apps. */
    private static final float PANEL_GAIN = 0.5f;
    /** Long enough for a sound that just started to finish before the samples go. */
    private static final long RELEASE_DELAY_MS = 500;
    /** Two sounds from one press (a toggle that also leaves a menu): the first one is enough. */
    private static final long SAME_PRESS_MS = 40;

    /** Keeps a held D-pad from rattling: a tick at most every 90 ms, fading out over a long repeat. */
    static final class Limiter {
        static final long MIN_GAP_MS = 90;
        /** Ticks of a held key before they start to fade, and how many it takes to fade away. */
        static final int FADE_AFTER = 4, FADE_OVER = 10;
        private long last = -MIN_GAP_MS;
        private int held;

        /** The gain for a tick now, or 0 for no tick. */
        float next(long nowMs, boolean repeat) {
            if (!repeat) held = 0;
            if (nowMs - last < MIN_GAP_MS) return 0f;
            last = nowMs;
            if (!repeat) return 1f;
            held++;
            if (held <= FADE_AFTER) return 1f;
            return Math.max(0f, 1f - (held - FADE_AFTER) / (float) FADE_OVER);
        }
    }

    /** The sample's own level times this. Too low and the projector's small speaker loses it entirely. */
    static float gain(int mode, int level) {
        if (mode == MODE_OFF) return 0f;
        int l = Math.max(1, Math.min(LEVEL_MAX, level));
        return (mode == MODE_NORMAL ? 1f : 0.7f) * l / LEVEL_MAX;
    }

    /** Inside the lock schedule. Unlike the lock, no schedule at all means it's never bedtime. */
    static boolean bedtime(String sched, int minuteOfDay) {
        return sched != null && !sched.isEmpty() && Lock.inSchedule(sched, minuteOfDay);
    }

    @android.annotation.SuppressLint("InlinedApi")
    static boolean slowOutput(int type) {
        return type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || type == AudioDeviceInfo.TYPE_BLE_HEADSET
                || type == AudioDeviceInfo.TYPE_BLE_SPEAKER || type == AudioDeviceInfo.TYPE_HDMI_ARC
                || type == AudioDeviceInfo.TYPE_HDMI_EARC;
    }

    private final Context ctx;
    private final SharedPreferences prefs;
    private final AudioManager audio;
    private final boolean panel;
    private final HandlerThread thread = new HandlerThread("sounds", Process.THREAD_PRIORITY_AUDIO);
    private final Handler handler;
    private final Limiter limiter = new Limiter();
    private final Runnable release = this::releaseNow;
    private final int[] sample = new int[RAW.length];
    private final boolean[] loaded = new boolean[RAW.length];

    private SoundPool pool;
    private AudioDeviceCallback outputs;
    private volatile boolean visible;
    private boolean slow, systemOn, startupPending;
    private int pendingSound = -1;
    private long lastPress = -SAME_PRESS_MS;

    /** {@code panel}: only the ≡ panel's open and close, for its own setting. */
    Sounds(Context c, boolean panel) {
        ctx = c.getApplicationContext();
        prefs = SafePrefs.focus(ctx);
        audio = ctx.getSystemService(AudioManager.class);
        this.panel = panel;
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    private int mode() {
        int m = prefs.getInt("sound_mode", MODE_OFF);
        return m < MODE_OFF || m > MODE_NORMAL ? MODE_OFF : m;
    }

    private boolean enabled() {
        return mode() != MODE_OFF && (!panel || prefs.getBoolean("sound_panel", false));
    }

    /** Home is on screen (or the panel opened): samples in, sounds allowed. */
    void start() {
        visible = true;
        handler.post(() -> {
            handler.removeCallbacks(release);
            if (enabled()) load();
            else releaseNow();
        });
    }

    /** Home left the screen: nothing more plays, and the samples go right after. */
    void stop() {
        visible = false;
        handler.postDelayed(release, RELEASE_DELAY_MS);
    }

    /** A setting changed. */
    void refresh() {
        if (visible) start();
    }

    void destroy() {
        visible = false;
        handler.post(() -> {
            releaseNow();
            thread.quitSafely();
        });
    }

    /** The first Home after the projector starts. */
    void startup() {
        handler.post(() -> {
            if (!enabled() || !prefs.getBoolean("sound_startup", false)) return;
            if (pool != null && loaded[STARTUP]) playNow(STARTUP, 1f);
            else startupPending = true;
        });
    }

    void play(int id) {
        if (!visible) return;
        handler.post(() -> {
            long now = android.os.SystemClock.uptimeMillis();
            if (now - lastPress < SAME_PRESS_MS) return;
            lastPress = now;
            playNow(id, 1f);
        });
    }

    /** A focus move or slider step; {@code repeat} when it comes from a held key. */
    void tick(int id, boolean repeat) {
        if (!visible) return;
        handler.post(() -> {
            float g = limiter.next(android.os.SystemClock.uptimeMillis(), repeat);
            if (g > 0f) playNow(id, g);
        });
    }

    private void load() {
        if (pool != null) return;
        pool = new SoundPool.Builder().setMaxStreams(2).setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
        java.util.Arrays.fill(loaded, false);
        pool.setOnLoadCompleteListener((p, id, status) -> {
            for (int i = 0; i < sample.length; i++) {
                if (sample[i] != id) continue;
                loaded[i] = status == 0;
                if (loaded[i] && i == STARTUP && startupPending) {
                    startupPending = false;
                    playNow(STARTUP, 1f);
                }
                if (loaded[i] && i == pendingSound) {
                    pendingSound = -1;
                    playNow(i, 1f);
                }
            }
        });
        for (int i = 0; i < RAW.length; i++) {
            sample[i] = !panel || i == OPEN || i == CLOSE ? pool.load(ctx, RAW[i], 1) : 0;
        }
        systemOn = Settings.System.getInt(ctx.getContentResolver(), Settings.System.SOUND_EFFECTS_ENABLED, 1) != 0;
        outputs = new AudioDeviceCallback() {
            @Override
            public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
                readOutputs();
            }

            @Override
            public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
                readOutputs();
            }
        };
        audio.registerAudioDeviceCallback(outputs, handler);
        readOutputs();
    }

    private void readOutputs() {
        boolean found = false;
        for (AudioDeviceInfo d : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) found |= slowOutput(d.getType());
        slow = found;
    }

    private boolean muted() {
        if (!systemOn) return true;
        if (slow && prefs.getBoolean("sound_quiet_slow", true)) return true;
        if (prefs.getBoolean("sound_mute_guest", false) && prefs.getBoolean("guest", false)) return true;
        if (!prefs.getBoolean("sound_mute_bed", false)) return false;
        Calendar now = Calendar.getInstance();
        return bedtime(prefs.getString("lock_sched", ""), now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE));
    }

    private void playNow(int id, float scale) {
        if (pool == null || !visible || !enabled() || muted()) return;
        if (!loaded[id]) {
            // Only the panel gets here in practice: it loads as it opens.
            if (panel) pendingSound = id;
            return;
        }
        float g = gain(mode(), prefs.getInt("sound_level", LEVEL_DEFAULT)) * scale * (panel ? PANEL_GAIN : 1f);
        pool.play(sample[id], g, g, 1, 0, 1f);
        if (BuildConfig.DEBUG) android.util.Log.d("Sounds", "play " + id + " gain " + g);
    }

    private void releaseNow() {
        if (pool == null) return;
        pool.release();
        pool = null;
        audio.unregisterAudioDeviceCallback(outputs);
        outputs = null;
        pendingSound = -1;
    }

    /** The panel is closing: its last sound, then the samples go. */
    void finish(int id) {
        handler.post(() -> {
            playNow(id, 1f);
            stop();
        });
    }

    /** For the Sounds menu's explanation. */
    boolean systemEffectsOn() {
        return Settings.System.getInt(ctx.getContentResolver(), Settings.System.SOUND_EFFECTS_ENABLED, 1) != 0;
    }
}
