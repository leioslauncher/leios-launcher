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

    /** Customize › Look › Sounds › Volume: 0 is off (the default), up to {@link #VOLUME_MAX}. */
    static final int VOLUME_MAX = 10;
    /** Settings from before the single volume (1.2.1 and earlier): Off/Subtle/Normal and a level of 1–5. */
    static final int OLD_MODE_OFF = 0, OLD_MODE_NORMAL = 2, OLD_LEVEL_DEFAULT = 3, OLD_LEVEL_MAX = 5;
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

    /** Customize › Look › Sounds › Output: which of Android's channels the sounds use. */
    static final int OUTPUT_AUTO = 0, OUTPUT_SYSTEM = 1, OUTPUT_MEDIA = 2;
    /** The channel actually used: Android's system sounds, or the media channel (as a game sound). */
    static final int ROUTE_SYSTEM = 0, ROUTE_MEDIA = 1;

    /**
     * Some TVs (the JMGO N3 4K among them) mute Android's System channel at every start, so sounds
     * sent there are never heard. Automatic uses system sounds while they can be heard, and the media
     * channel otherwise; it never takes audio focus, so other apps are never paused or turned down.
     */
    static int route(int output, boolean systemMuted, int systemVolume) {
        if (output == OUTPUT_SYSTEM) return ROUTE_SYSTEM;
        if (output == OUTPUT_MEDIA) return ROUTE_MEDIA;
        return systemMuted || systemVolume <= 0 ? ROUTE_MEDIA : ROUTE_SYSTEM;
    }

    /** Whether sounds on this route can be heard at all, for the Output menu's explanation. */
    static boolean audible(int route, boolean systemMuted, int systemVolume, boolean mediaMuted, int mediaVolume) {
        return route == ROUTE_SYSTEM ? !systemMuted && systemVolume > 0 : !mediaMuted && mediaVolume > 0;
    }

    static AudioAttributes attributes(int route) {
        return new AudioAttributes.Builder()
                .setUsage(route == ROUTE_SYSTEM ? AudioAttributes.USAGE_ASSISTANCE_SONIFICATION : AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
    }

    /** The sample's own level times this. Too low and the projector's small speaker loses it entirely. */
    static float gain(int volume) {
        return Math.max(0, Math.min(VOLUME_MAX, volume)) / (float) VOLUME_MAX;
    }

    /** The old mode and level as the new volume, at the same loudness, rounded. */
    static int fromOld(int mode, int level) {
        if (mode <= OLD_MODE_OFF || mode > OLD_MODE_NORMAL) return 0;
        int l = Math.max(1, Math.min(OLD_LEVEL_MAX, level));
        float g = (mode == OLD_MODE_NORMAL ? 1f : 0.7f) * l / OLD_LEVEL_MAX;
        return Math.max(1, Math.min(VOLUME_MAX, Math.round(g * VOLUME_MAX)));
    }

    /** The sounds' volume, 0 for off; read from the older settings until the slider is first moved. */
    static int volume(SharedPreferences p) {
        int v;
        if (p.contains("sound_volume")) v = p.getInt("sound_volume", 0);
        else v = fromOld(p.getInt("sound_mode", OLD_MODE_OFF), p.getInt("sound_level", OLD_LEVEL_DEFAULT));
        return Math.max(0, Math.min(VOLUME_MAX, v));
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
    /** The route {@link #pool} was built for. */
    private int poolRoute = ROUTE_SYSTEM;
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

    private boolean enabled() {
        return volume(prefs) > 0 && (!panel || prefs.getBoolean("sound_panel", false));
    }

    private int output() {
        int o = prefs.getInt("sound_output", OUTPUT_AUTO);
        return o < OUTPUT_AUTO || o > OUTPUT_MEDIA ? OUTPUT_AUTO : o;
    }

    private boolean systemMuted() {
        return muted(AudioManager.STREAM_SYSTEM);
    }

    private boolean muted(int stream) {
        try {
            return audio.isStreamMute(stream);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private int volume(int stream) {
        try {
            return audio.getStreamVolume(stream);
        } catch (RuntimeException e) {
            return 1;
        }
    }

    /** The route for the current setting and what Android reports right now. */
    int currentRoute() {
        return route(output(), systemMuted(), volume(AudioManager.STREAM_SYSTEM));
    }

    /** Whether sounds would be heard on the current route; for the Output menu. */
    boolean audibleNow() {
        return audible(currentRoute(), systemMuted(), volume(AudioManager.STREAM_SYSTEM),
                muted(AudioManager.STREAM_MUSIC), volume(AudioManager.STREAM_MUSIC));
    }

    /** Android's System channel is muted or at 0, as some TVs leave it. */
    boolean systemSilent() {
        return systemMuted() || volume(AudioManager.STREAM_SYSTEM) <= 0;
    }

    /** Home is on screen (or the panel opened): samples in, sounds allowed. */
    void start() {
        visible = true;
        handler.post(() -> {
            handler.removeCallbacks(release);
            if (!enabled()) releaseNow();
            else {
                // Back on screen or a setting changed: the channel may have changed since.
                if (pool != null && currentRoute() != poolRoute) releaseNow();
                load();
            }
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

    /**
     * A sound right after sounds were turned on or their output changed: the samples are still
     * loading then, so it waits for its own sample instead of being dropped.
     */
    void playWhenReady(int id) {
        if (!visible) return;
        handler.post(() -> {
            if (pool != null && loaded[id]) playNow(id, 1f);
            else pendingSound = id;
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
        poolRoute = currentRoute();
        pool = new SoundPool.Builder().setMaxStreams(2).setAudioAttributes(attributes(poolRoute)).build();
        if (BuildConfig.DEBUG) android.util.Log.d("Sounds", "route " + (poolRoute == ROUTE_SYSTEM ? "system" : "media"));
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
                outputsChanged();
            }

            @Override
            public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
                outputsChanged();
            }
        };
        audio.registerAudioDeviceCallback(outputs, handler);
        readOutputs();
    }

    /** HDMI or Bluetooth came or went: the System channel may be heard differently there. */
    private void outputsChanged() {
        readOutputs();
        if (pool != null && visible && currentRoute() != poolRoute) {
            releaseNow();
            load();
        }
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
        float g = gain(volume(prefs)) * scale * (panel ? PANEL_GAIN : 1f);
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
