package tv.leios.launcher;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.media.AudioManager;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.FrameLayout;

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Quick settings panel on the remote's ≡ (Menu) button, over Home and over any app. As an
 * accessibility service it only watches key presses: Menu toggles the panel, the gear button opens
 * Settings, and while the panel is open the D-pad, OK and Back drive it. Every other key passes
 * straight on.
 * Trade-off measured on this projector: while any key-filtering service is on, a held volume
 * button steps once per press instead of ramping. Volume keys never reach the service (the
 * audio system takes them first), so that can't be repaired here.
 */
public class PanelService extends AccessibilityService {

    /** The running service, so Home can reach it; cleared in onUnbind, so it never outlives it. */
    @android.annotation.SuppressLint("StaticFieldLeak")
    private static PanelService instance;

    /** Running and enabled in Settings › Accessibility. */
    static boolean isRunning() {
        return instance != null;
    }

    static void toggleFromLauncher() {
        if (instance != null) instance.toggle();
    }

    /** What a row does: OK / Right runs {@code run}; Left / Right on a slider or stepper calls {@code adjust}. */
    private interface Adjust {
        /** Changes the value; false when the row can't change (the owner then explains why). */
        boolean by(int dir);
    }

    private static final class Item {
        final MenuView.Row row;
        final Runnable run;
        final Adjust adjust;
        final Runnable refresh;

        Item(MenuView.Row row, Runnable run, Adjust adjust, Runnable refresh) {
            this.row = row;
            this.run = run;
            this.adjust = adjust;
            this.refresh = refresh;
        }
    }

    private WindowManager wm;
    private FrameLayout overlay;
    private MenuView menu;
    private Theme theme;
    private Tokens tk;
    private final List<Item> items = new ArrayList<>();
    private boolean open, inSubmenu;
    private int mainFocus;
    private AudioManager audio;

    private static final int[] SLEEP = {0, 15, 30, 60, 90, 120};

    /**
     * Keys this service consumes never reach Android's own key repeat, so a held key is repeated
     * here until it's let go: Up/Down at Android's own rate (the same as Home's menus), Left/Right
     * on a slider at the slider's rate.
     */
    private final Handler repeat = new Handler(Looper.getMainLooper());
    private int repeatKey, repeatDir;
    private final Runnable repeatStep = new Runnable() {
        @Override
        public void run() {
            boolean vertical = repeatKey == KeyEvent.KEYCODE_DPAD_UP || repeatKey == KeyEvent.KEYCODE_DPAD_DOWN;
            boolean moved = open && repeatDir != 0 && (vertical ? menu.moveFocus(repeatDir) : adjustFocused(repeatDir));
            if (!moved) {
                stopRepeat();
                return;
            }
            repeat.postDelayed(this, vertical ? ViewConfiguration.getKeyRepeatDelay() : tk.repeatEvery);
        }
    };

    private void startRepeat(int key, int dir, long delay) {
        stopRepeat();
        repeatKey = key;
        repeatDir = dir;
        repeat.postDelayed(repeatStep, delay);
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
        wm = getSystemService(WindowManager.class);
        audio = getSystemService(AudioManager.class);
        tk = Tokens.of(this);
        filterKeys(true);
        JmgoDlp.connect(this);
        updateEvents();
        android.content.IntentFilter f = new android.content.IntentFilter(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(wake, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(wake, f);
        android.content.IntentFilter d = new android.content.IntentFilter(Intent.ACTION_DREAMING_STARTED);
        d.addAction(Intent.ACTION_DREAMING_STOPPED);
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(dreams, d, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(dreams, d);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        close();
        if (sounds != null) sounds.destroy();
        instance = null;
        try {
            unregisterReceiver(wake);
            unregisterReceiver(dreams);
        } catch (RuntimeException ignored) {
        }
        return super.onUnbind(intent);
    }

    /**
     * Customize › Projector & tools › Start-up and wake › When it wakes: Home, an app or an input,
     * a moment after the picture comes back. "stay" (the default) leaves it as it was.
     */
    private final android.content.BroadcastReceiver wake = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) {
                // Going to sleep locks again.
                Lock.relock();
                return;
            }
            // In case the end of a screensaver was missed, the buttons always come back on wake.
            dreaming = false;
            filterKeys(true);
            String target = SafePrefs.focus(PanelService.this).getString("wake_app", "stay");
            if ("stay".equals(target)) return;
            repeat.postDelayed(() -> {
                if (target.isEmpty()) {
                    launch(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME));
                } else if (target.startsWith("input:")) {
                    launch(new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(target.substring(6))));
                } else {
                    Intent app = getPackageManager().getLeanbackLaunchIntentForPackage(target);
                    if (app != null) launch(app);
                }
            }, 1500);
        }
    };

    /**
     * Only while apps are locked does the helper hear which app comes to the front (its package
     * name, nothing on screen), so it can ask for the PIN before showing it.
     */
    private void updateEvents() {
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) return;
        boolean any = false;
        for (String s : Lock.lockedApps(this)) any |= !s.isEmpty();
        for (String s : Lock.lockedCats(this)) any |= !s.isEmpty();
        boolean watch = Lock.hasPin(this) && any;
        int types = AccessibilityEvent.TYPE_ANNOUNCEMENT | (watch ? AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED : 0);
        if (info.eventTypes != types) {
            info.eventTypes = types;
            setServiceInfo(info);
        }
    }

    /** Home changed the PIN or what it locks. */
    static void locksChanged() {
        if (instance != null) instance.updateEvents();
    }

    /** Home came to the front: a PIN screen that was covering a locked app has nothing left to cover. */
    static void homeShown() {
        if (instance != null && instance.pinFor != null) instance.removePin();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence p = event.getPackageName();
        if (p == null) return;
        String pkg = p.toString();
        if (pkg.equals("android") || pkg.startsWith("com.android.systemui")) return;
        // While the PIN screen shows, windows of other packages (a keyboard, a splash screen) come
        // and go behind it; only Home coming back ends it (homeShown).
        if (pinView != null) return;
        if (pkg.equals(getPackageName())) return;
        String cat = Categories.load(SafePrefs.focus(this)).of(pkg).id;
        if (!Lock.appLocked(this, pkg, cat)) return;
        CharSequence label;
        try {
            label = getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0));
        } catch (Exception e) {
            label = getString(R.string.panel_this_app);
        }
        // Covers the app until the right PIN; Back goes Home instead.
        askPin(getString(R.string.pin_title_open_app, label), null, () -> performGlobalAction(GLOBAL_ACTION_HOME));
        pinFor = pkg;
    }

    // ---- PIN over other apps -----------------------------------------------------------------

    private FrameLayout pinView;
    private PinPad pinPad;
    /** The locked app the PIN screen covers; null when it guards Settings instead. */
    private String pinFor;
    private int wrongPins;
    private long pinWaitUntil;

    /** A full-screen PIN screen over whatever is showing. {@code then} runs after the right PIN. */
    private void askPin(String title, Runnable then, Runnable cancelled) {
        if (wm == null || pinView != null) return;
        close();
        Theme th = Theme.of(this);
        tk = Tokens.of(this);
        pinView = new FrameLayout(this);
        pinView.setBackgroundColor(getColor(R.color.idle_bg));
        pinPad = new PinPad(this, th, null, title, getString(R.string.pin_enter), new PinPad.Listener() {
            @Override
            public void done(String pin) {
                if (System.currentTimeMillis() < pinWaitUntil) {
                    pinPad.error(getString(R.string.pin_too_many));
                    return;
                }
                if (Lock.check(PanelService.this, pin)) {
                    wrongPins = 0;
                    removePin();
                    if (then != null) then.run();
                } else {
                    if (++wrongPins >= 5) {
                        wrongPins = 0;
                        pinWaitUntil = System.currentTimeMillis() + 30_000;
                    }
                    pinPad.error(getString(R.string.pin_wrong));
                }
            }

            @Override
            public void cancel() {
                removePin();
                if (cancelled != null) cancelled.run();
            }
        });
        pinView.addView(pinPad, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        WindowManager.LayoutParams wlp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.OPAQUE);
        try {
            wm.addView(pinView, wlp);
        } catch (RuntimeException ex) {
            pinView = null;
            pinPad = null;
        }
    }

    private void removePin() {
        pinFor = null;
        if (pinView == null) return;
        try {
            wm.removeView(pinView);
        } catch (RuntimeException ignored) {
        }
        pinView = null;
        pinPad = null;
    }

    /** Android's Settings, behind the PIN when Customize and Settings are locked. */
    private void openSettings() {
        Runnable go = () -> launch(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK));
        if (Lock.settingsLocked(this)) askPin(getString(R.string.settings), go, null);
        else go.run();
    }

    // ---- holding Home on Home opens search ----------------------------------------------------

    /**
     * Only watched, never held back: the press goes on to Android at once, and a timer starts. If
     * Home is still down after Android's own long-press time, and Leios's Home is what's showing,
     * search opens. Let go sooner and the timer is simply cancelled. Inside other apps nothing
     * happens, so their Home button is untouched.
     */
    private final Runnable homeHold = () -> {
        MainActivity home = MainActivity.resumedHome();
        if (home != null && SafePrefs.focus(this).getBoolean("hold_home_search", true)) home.openSearchFromHold();
    };

    private void watchHomeHold(boolean down, int repeatCount) {
        if (down && repeatCount == 0) {
            repeat.removeCallbacks(homeHold);
            if (MainActivity.resumedHome() != null) repeat.postDelayed(homeHold, ViewConfiguration.getLongPressTimeout());
        } else if (!down) {
            repeat.removeCallbacks(homeHold);
        }
    }

    // ---- remote shortcuts: hold ≡ or the Settings button ------------------------------------

    private boolean holdFired;
    private int holdKey;
    private final Runnable holdRun = () -> {
        holdFired = true;
        doHold(SafePrefs.focus(this).getString(
                holdKey == KeyEvent.KEYCODE_MENU ? "hold_menu" : "hold_gear", ""));
    };

    private void doHold(String action) {
        close();
        switch (action) {
            case "home":
                performGlobalAction(GLOBAL_ACTION_HOME);
                break;
            case "customize":
                launch(new Intent(this, MainActivity.class).putExtra("open", "customize"));
                break;
            case "search":
                launch(new Intent(this, MainActivity.class).putExtra("open", "search"));
                break;
            case "next_bg": {
                MainActivity home = MainActivity.resumedHome();
                if (home != null) home.nextBackground();
                else launch(new Intent(this, MainActivity.class).putExtra("open", "next_bg"));
                break;
            }
            case "sleep30":
                if (SleepTimer.isReady(this)) {
                    SleepTimer.set(this, 30);
                    android.widget.Toast.makeText(this, R.string.toast_sleep_30, android.widget.Toast.LENGTH_SHORT).show();
                } else {
                    android.widget.Toast.makeText(this, R.string.toast_sleep_set_once,
                            android.widget.Toast.LENGTH_LONG).show();
                }
                break;
            default:
                break;
        }
    }

    /**
     * ≡ and the Settings button. Without a hold shortcut they act the moment they're pressed, as
     * always; with one, a short press acts on release and holding does the shortcut instead.
     */
    private boolean shortcutKey(int code, boolean down, int repeatCount) {
        String hold = SafePrefs.focus(this).getString(
                code == KeyEvent.KEYCODE_MENU ? "hold_menu" : "hold_gear", "");
        Runnable press = code == KeyEvent.KEYCODE_MENU ? this::toggle : this::openSettings;
        if (hold.isEmpty()) {
            if (down && repeatCount == 0) press.run();
            return true;
        }
        if (down && repeatCount == 0) {
            holdFired = false;
            holdKey = code;
            repeat.removeCallbacks(holdRun);
            repeat.postDelayed(holdRun, tk.longPress);
        } else if (!down) {
            repeat.removeCallbacks(holdRun);
            if (!holdFired && holdKey == code) press.run();
            holdKey = 0;
        }
        return true;
    }

    @Override
    public void onInterrupt() {
    }

    // ---- keys -------------------------------------------------------------------------------

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        // Backstop for a key that arrives before the filter is switched off: let it through.
        if (dreaming && pinPad == null) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN && mayTake(e.getKeyCode())) countAsUse();
        return takeKey(e);
    }

    /** A screensaver is showing, from Android's own broadcasts. */
    private volatile boolean dreaming;
    private final android.content.BroadcastReceiver dreams = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            dreaming = Intent.ACTION_DREAMING_STARTED.equals(i.getAction());
            // On this projector the gear ends the screensaver before any app sees it, and the TV
            // then turns off unless Android has counted the press as use. Passing every key
            // through this service delays that count just enough, so the TV turned off. While the
            // screensaver shows, keys skip this service and go straight to Android.
            filterKeys(!dreaming);
        }
    };

    /** Whether remote buttons pass through this service first. */
    private void filterKeys(boolean on) {
        try {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info == null) return;
            int flags = on ? info.flags | AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
                    : info.flags & ~AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            if (flags == info.flags) return;
            info.flags = flags;
            setServiceInfo(info);
        } catch (RuntimeException ignored) {
        }
    }

    /** Keys this service might take: the PIN screen's, ≡ and the gear always, the rest while the panel is open. */
    private boolean mayTake(int code) {
        return pinPad != null || open || code == KeyEvent.KEYCODE_NOTIFICATION || code == KeyEvent.KEYCODE_MENU;
    }

    private PowerManager.WakeLock awake;

    /**
     * A key this service takes never reaches Android's power manager, so on its own it doesn't
     * count as using the TV, and the screen could time out while you use only the panel. A brief
     * wake lock that restarts the screen timeout on release does what the press would have done.
     */
    @SuppressWarnings("deprecation")
    private void countAsUse() {
        try {
            if (awake == null) {
                PowerManager pm = getSystemService(PowerManager.class);
                if (pm == null) return;
                awake = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ON_AFTER_RELEASE,
                        "leios:key");
                awake.setReferenceCounted(false);
            }
            awake.acquire(1000);
        } catch (RuntimeException ignored) {
        }
    }

    private boolean takeKey(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (pinPad != null) {
            // The PIN screen takes every key but Home (which leaves it, back to Home).
            if (code == KeyEvent.KEYCODE_HOME) {
                removePin();
                return false;
            }
            pinPad.key(e);
            return true;
        }
        if (code == KeyEvent.KEYCODE_NOTIFICATION) {
            // The remote's gear button (scancode 256). Google TV's launcher used to take it; that's
            // disabled, so it opens Settings here instead.
            return shortcutKey(code, down, e.getRepeatCount());
        }
        if (code == KeyEvent.KEYCODE_MENU) {
            return shortcutKey(code, down, e.getRepeatCount());
        }
        if (code == KeyEvent.KEYCODE_HOME && !open) {
            watchHomeHold(down, e.getRepeatCount());
            // Never taken: Home works exactly as before, with no wait.
            return false;
        }
        if (!open) return false;
        // A different key interrupts a held one; the held key's own repeats are ignored below.
        if (down && e.getRepeatCount() == 0 && code != repeatKey) stopRepeat();
        switch (code) {
            case KeyEvent.KEYCODE_HOME:
                close();
                return false;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (!down) stopRepeat();
                else if (e.getRepeatCount() == 0) {
                    int dir = code == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1;
                    if (menu.moveFocus(dir)) startRepeat(code, dir, ViewConfiguration.getKeyRepeatTimeout());
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                // Repeats come from repeatStep, so any repeats the system does send are ignored.
                if (!down) stopRepeat();
                else if (e.getRepeatCount() == 0) sideways(code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (down && e.getRepeatCount() == 0) select();
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down && e.getRepeatCount() == 0) {
                    if (inSubmenu) showMain(mainFocus);
                    else close();
                }
                return true;
            default:
                // Volume, media and app buttons keep working normally under the panel.
                return false;
        }
    }

    private Item focusedItem() {
        int f = menu.focus();
        return f < 0 || f >= items.size() ? null : items.get(f);
    }

    private boolean adjustFocused(int dir) {
        Item it = focusedItem();
        if (it == null || it.adjust == null || !it.adjust.by(dir)) return false;
        if (it.refresh != null) it.refresh.run();
        menu.refresh(menu.focus());
        return true;
    }

    private void sideways(int dir) {
        Item it = focusedItem();
        if (it == null) return;
        if (it.adjust != null) {
            if (adjustFocused(dir)) {
                startRepeat(dir < 0 ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT, dir, tk.repeatDelay);
            }
            return;
        }
        if (dir > 0 && it.run != null && it.row.enabled()) it.run.run();
        else if (dir < 0 && inSubmenu) showMain(mainFocus);
    }

    private void select() {
        Item it = focusedItem();
        if (it == null) return;
        if (!it.row.enabled()) {
            if (it.row.disabledReason != null) menu.flash(it.row.disabledReason);
            return;
        }
        if (it.run != null) it.run.run();
        else if (it.adjust != null) adjustFocused(1);
    }

    private void stopRepeat() {
        repeatKey = 0;
        repeatDir = 0;
        repeat.removeCallbacks(repeatStep);
    }

    // ---- panel ------------------------------------------------------------------------------

    void toggle() {
        if (open) close();
        else openPanel();
    }

    private void openPanel() {
        if (open || wm == null) return;
        // Read again each time: the font, text size or look may have changed on Home.
        tk = Tokens.of(this);
        theme = Theme.of(this);
        overlay = new FrameLayout(this);
        menu = new MenuView(this, theme);
        menu.setHints(mainHints());
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(tk.sheetWidth,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        lp.setMargins(0, tk.safeV, tk.safeH, tk.safeV);
        menu.setMaxHeight(getResources().getDisplayMetrics().heightPixels - 2 * tk.safeV);
        overlay.addView(menu, lp);
        WindowManager.LayoutParams wlp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        try {
            wm.addView(overlay, wlp);
        } catch (RuntimeException ex) {
            return;
        }
        open = true;
        if (sounds == null && SafePrefs.focus(this).getBoolean("sound_panel", false)) sounds = new Sounds(this, true);
        if (sounds != null) {
            sounds.start();
            sounds.play(Sounds.OPEN);
        }
        showMain(0);
        menu.setTranslationX(tk.space6);
        menu.setAlpha(0f);
        menu.animate().translationX(0).alpha(1f).setDuration(tk.medium).setInterpolator(Tokens.EASE_OUT).start();
    }

    /** Only the faint open and close, if chosen; rows stay silent, there may be a film playing. */
    private Sounds sounds;

    void close() {
        if (!open) return;
        open = false;
        if (sounds != null) sounds.finish(Sounds.CLOSE);
        stopRepeat();
        FrameLayout gone = overlay;
        overlay = null;
        if (gone == null) return;
        menu.animate().cancel();
        menu.animate().translationX(tk.space5).alpha(0f).setDuration(tk.fast).setInterpolator(Tokens.EASE_IN_OUT)
                .withEndAction(() -> {
                    try {
                        wm.removeView(gone);
                    } catch (RuntimeException ignored) {
                    }
                }).start();
    }

    /** Close, then start something (it opens on top of whatever app was showing). */
    private void launch(Intent i) {
        close();
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException ex) {
            android.widget.Toast.makeText(this, R.string.toast_screen_unavailable,
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void setItems(List<Item> list, int focus) {
        items.clear();
        items.addAll(list);
        List<MenuView.Row> rows = new ArrayList<>();
        for (Item it : items) {
            if (it.refresh != null) it.refresh.run();
            rows.add(it.row);
        }
        menu.setRows(rows, focus);
    }

    /** "◀ ▶ Adjust  OK Select  ≡ Close" under the panel. ≡ is the remote's menu button. */
    private String[] mainHints() {
        return new String[] {"◀ ▶", getString(R.string.hint_adjust), getString(R.string.key_ok), getString(R.string.hint_select),
                "≡", getString(R.string.hint_close)};
    }

    private void showMain(int focus) {
        inSubmenu = false;
        Date now = new Date();
        boolean h24 = android.text.format.DateFormat.is24HourFormat(this);
        String time = new SimpleDateFormat(h24 ? "H:mm" : "h:mm a", Locale.getDefault()).format(now);
        String day = new SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(
                Locale.getDefault(), "EEEMMMd"), Locale.getDefault()).format(now);
        menu.setHeader(time + getString(R.string.dot_separator) + day, getString(R.string.panel_title));
        List<Item> list = new ArrayList<>();
        list.add(volumeItem());
        // Brightness: JMGO's own control as a slider, else its screen, else (other TVs) nothing.
        if (JmgoDlp.available() && JmgoDlp.brightnessStep() > 0) list.add(brightnessItem());
        else if (Device.has(this, Device.SETTINGS, Device.JMGO_LIGHT)) {
            list.add(new Item(MenuView.Row.nav(R.drawable.ic_brightness, getString(R.string.set_projector_brightness),
                    getString(R.string.panel_projector_screen)),
                    () -> launch(Device.open(Device.SETTINGS, Device.JMGO_LIGHT)), null, null));
        }
        if (Device.hasInputs(this)) {
            int at = list.size();
            list.add(new Item(MenuView.Row.nav(R.drawable.ic_input, getString(R.string.inputs), ""), () -> {
                mainFocus = at;
                showInputs();
            }, null, null));
        }
        list.add(new Item(MenuView.Row.nav(R.drawable.ic_wifi, getString(R.string.wifi), networkName()), () -> {
            Runnable go = () -> launch(new Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK));
            if (Lock.settingsLocked(this)) askPin(getString(R.string.pin_title_wifi_settings), go, null);
            else go.run();
        }, null, null));
        list.add(sleepItem());
        if (Device.has(this, Device.SETTINGS, Device.JMGO_PICTURE) || Device.has(this, Device.SETTINGS, Device.JMGO_KEYSTONE)) {
            int at = list.size();
            list.add(new Item(MenuView.Row.nav(R.drawable.ic_projector, getString(R.string.panel_projector),
                getString(R.string.panel_projector_summary)), () -> {
                mainFocus = at;
                showProjector();
            }, null, null));
        }
        list.add(new Item(MenuView.Row.nav(R.drawable.ic_settings, getString(R.string.settings), ""), this::openSettings, null, null));
        menu.setHints(mainHints());
        setItems(list, focus);
    }

    private void showInputs() {
        inSubmenu = true;
        menu.setHeader(getString(R.string.panel_title), getString(R.string.inputs));
        List<Item> list = new ArrayList<>();
        TvInputManager tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        boolean tuner = false;
        if (tim != null) {
            for (TvInputInfo info : tim.getTvInputList()) {
                if (info.getParentId() != null || info.isHidden(this)) continue;
                if (!info.isPassthroughInput()) {
                    tuner = true;
                    continue;
                }
                CharSequence label = info.loadCustomLabel(this);
                if (TextUtils.isEmpty(label)) label = info.loadLabel(this);
                Uri uri = TvContract.buildChannelUriForPassthroughInput(info.getId());
                list.add(new Item(MenuView.Row.action(R.drawable.ic_hdmi, String.valueOf(label)),
                        () -> launch(new Intent(Intent.ACTION_VIEW, uri)), null, null));
            }
        }
        list.sort((a, b) -> a.row.label.compareTo(b.row.label));
        if (tuner) list.add(new Item(MenuView.Row.action(R.drawable.ic_live, getString(R.string.inputs_live_tv)),
                () -> launch(new Intent(Intent.ACTION_VIEW, TvContract.Channels.CONTENT_URI)), null, null));
        if (list.isEmpty()) list.add(new Item(MenuView.Row.note(R.drawable.ic_info, getString(R.string.inputs_none)), null, null, null));
        menu.setHints(getString(R.string.key_ok), getString(R.string.hint_switch), getString(R.string.key_back),
                getString(R.string.hint_back));
        setItems(list, 0);
    }

    private void showProjector() {
        inSubmenu = true;
        menu.setHeader(getString(R.string.panel_title), getString(R.string.panel_projector));
        List<Item> list = new ArrayList<>();
        if (Device.has(this, Device.SETTINGS, Device.JMGO_PICTURE)) {
            list.add(new Item(MenuView.Row.nav(R.drawable.ic_picture, getString(R.string.set_projector_picture),
                getString(R.string.projector_picture_summary)),
                    () -> launch(Device.open(Device.SETTINGS, Device.JMGO_PICTURE)), null, null));
        }
        if (Device.has(this, Device.SETTINGS, Device.JMGO_KEYSTONE)) {
            list.add(new Item(MenuView.Row.nav(R.drawable.ic_keystone, getString(R.string.set_projector_keystone),
                getString(R.string.projector_keystone_summary)),
                    () -> launch(Device.open(Device.SETTINGS, Device.JMGO_KEYSTONE)), null, null));
        }
        menu.setHints(getString(R.string.key_ok), getString(R.string.hint_open), getString(R.string.key_back),
                getString(R.string.hint_back));
        setItems(list, 0);
    }

    // ---- rows -------------------------------------------------------------------------------

    private Item volumeItem() {
        MenuView.Row row = MenuView.Row.slider(R.drawable.ic_volume, getString(R.string.panel_volume), 0, "");
        Runnable refresh = () -> {
            int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int v = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            int pct = max == 0 ? 0 : Math.round(v * 100f / max);
            row.fraction = pct / 100f;
            row.value(NumberFormat.getIntegerInstance().format(pct));
        };
        return new Item(row, null, dir -> {
            int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int cur = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            int next = Math.max(0, Math.min(max, cur + dir * Math.max(1, max / 20)));
            if (next == cur) return false;
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0);
            return true;
        }, refresh);
    }

    /** Same 1..10 steps and values as the projector's Brightness screen. */
    private Item brightnessItem() {
        MenuView.Row row = MenuView.Row.slider(R.drawable.ic_brightness, getString(R.string.set_projector_brightness), 0, "");
        Runnable refresh = () -> {
            int step = Math.max(0, JmgoDlp.brightnessStep());
            row.fraction = step / 10f;
            row.value(NumberFormat.getIntegerInstance().format(step));
        };
        return new Item(row, null, dir -> {
            int step = JmgoDlp.brightnessStep();
            int next = Math.max(1, Math.min(10, step + dir));
            if (step <= 0 || next == step) return false;
            JmgoDlp.setBrightnessStep(next);
            return true;
        }, refresh);
    }

    private Item sleepItem() {
        MenuView.Row row = MenuView.Row.stepper(R.drawable.ic_sleep, getString(R.string.set_projector_sleep_timer),
                getString(R.string.off));
        boolean ready = SleepTimer.isReady(this);
        if (!ready) row.disabled(getString(R.string.panel_sleep_turn_on_once));
        Runnable refresh = () -> {
            int left = SleepTimer.minutesLeft(this);
            row.value(left > 0 ? getResources().getQuantityString(R.plurals.duration_min_short, left, left)
                    : getString(R.string.off));
        };
        return new Item(row, null, dir -> {
            if (!SleepTimer.isReady(this)) {
                menu.flash(row.disabledReason);
                return false;
            }
            int left = SleepTimer.minutesLeft(this);
            int cur = 0;
            for (int i = 1; i < SLEEP.length; i++) if (left > 0 && left <= SLEEP[i]) {
                cur = i;
                break;
            }
            int next = Math.max(0, Math.min(SLEEP.length - 1, cur + dir));
            if (next == cur) return false;
            if (next == 0) SleepTimer.cancel(this);
            else SleepTimer.set(this, SLEEP[next]);
            return true;
        }, refresh);
    }

    // ---- helpers ----------------------------------------------------------------------------


    @SuppressWarnings("deprecation")
    private String networkName() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        NetworkCapabilities caps = cm == null ? null : cm.getNetworkCapabilities(cm.getActiveNetwork());
        if (caps == null) return getString(R.string.net_offline);
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return getString(R.string.net_ethernet);
        WifiManager w = getApplicationContext().getSystemService(WifiManager.class);
        WifiInfo info = w == null ? null : w.getConnectionInfo();
        String s = info == null ? null : info.getSSID();
        if (s == null || s.contains("unknown ssid") || s.isEmpty()) return getString(R.string.wifi);
        return s.replaceAll("^\"|\"$", "");
    }
}
