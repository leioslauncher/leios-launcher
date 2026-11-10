package tv.leios.launcher;

import android.Manifest;
import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.Dialog;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.TransportInfo;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONException;
import org.json.JSONObject;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntConsumer;

/**
 * The home screen. Focus is not Android focus: the activity keeps one (area, index) pair and
 * moves the accent highlight itself, so Up/Down always move exactly one row.
 * All colours come from {@link Theme}, all sizes and timings from {@link Tokens}.
 */
public class MainActivity extends Activity {

    private static final int TOP = 0, DOCK = 1, GRID = 2, MENU = 3;
    // Top-bar buttons, left to right.
    private static final int PILL_NOTIF = 0, PILL_SEARCH = 1, PILL_INPUTS = 2, PILL_WIFI = 3, PILL_CUSTOMIZE = 4,
            PILL_SETTINGS = 5;
    private static final int REQ_PHOTOS = 1, REQ_ADMIN = 2, REQ_SLIDES = 3, REQ_VIDEOS = 4, REQ_MIC = 5;
    /** What to do once the mic is allowed (search's mic key asked for it). */
    private Runnable afterMic;
    private boolean askingMic;

    // Customize sections, in menu order (also used to return to the right row on Back).
    private static final int SEC_BACKGROUND = 0, SEC_HOME = 1, SEC_APPS = 2, SEC_LOOK = 3,
            SEC_PROJECTOR = 4, SEC_LOCK = 5, SEC_RESET = 6;

    private FrameLayout root, hero;
    private ScrollView scroller;
    private View dockBand, glow, edgeTop, edgeBottom, gridScrim, topBar, content;
    private LinearLayout moveHint, gridSection;
    private RecyclerView dockList;
    private TileAdapter dockAdapter;
    private TextView greeting, clock, date, weather, dockTitle, wifiLabel, notifLabel;
    private ImageView wifiIcon;
    private View[] pills;
    private HighlightView highlight;
    private ImageView wallpaper, wallpaperNext;
    private GradientDrawable glowDrawable;
    private MenuView menu;
    private Ui.Toaster toaster;
    private SearchOverlay search;
    /** When holding Home last opened search: the Home press that may follow is ignored. */
    private long searchHeldAt;
    private Theme theme;
    private Tokens tk;

    private final List<AppRepo.App> allApps = new ArrayList<>();
    private final List<AppRepo.App> dockApps = new ArrayList<>();

    /**
     * One row of apps under the dock: a category, or one of the optional rows that aren't
     * categories: "All apps" (every app, A–Z) and "Inputs" (a tile per HDMI input).
     */
    private static final class Section {
        static final String ALL = "all", INPUTS = "inputs";
        final Categories.Cat cat;
        final String special;
        /** Taken when built: the category's setting can change before the views are rebuilt. */
        final boolean single;
        final List<AppRepo.App> apps = new ArrayList<>();
        TextView header;
        RecyclerView list;
        TileAdapter adapter;
        /** Sideways rows: index of the first tile in view. */
        int first;

        Section(Categories.Cat cat) {
            this(cat, null);
        }

        Section(Categories.Cat cat, String special) {
            this.cat = cat;
            this.special = special;
            single = cat != null ? cat.singleRow : INPUTS.equals(special);
        }

        boolean single() {
            return single;
        }

        String id() {
            return cat != null ? cat.id : special;
        }
    }

    /** A row's name over its tiles: the category's, or "Inputs" / "All apps". */
    private String sectionName(Section s) {
        return s.cat != null ? catName(s.cat) : tr(Section.INPUTS.equals(s.special) ? R.string.row_inputs : R.string.row_all_apps);
    }

    private final List<Section> sections = new ArrayList<>();
    private Categories categories;
    private boolean appsLoaded;
    private int columns, cardW, cardH, colW;
    private int area = DOCK, topIndex, dockIndex, gridSec, gridIndex;
    private int scrollTarget;
    private View focused;
    private TextView focusedLabel;
    private String titlePkg;
    private int glowColor;
    private ObjectAnimator scrollAnim;
    private ValueAnimator glowAnim;
    private boolean focusPending = true;
    private String savedDockPkg, savedGridPkg, savedGridSec;
    private boolean moving, selectPending;
    private int labelMode;
    private boolean glowOn;

    // The one menu, reused for Inputs, Customize and the quick actions on a tile.
    private Sounds sounds;
    /** The key being handled is a repeat from holding it down. */
    private boolean navRepeat;
    /** Set by a menu row that makes its own sound (a toggle's on or off, a volume preview). */
    private int menuSound = -1;
    private boolean stepHandled;

    private static final class Entry {
        final Runnable action, preview;
        /** Sliders and steppers: Left (-1) / Right (+1) change the value in place. */
        IntConsumer adjust;

        Entry(Runnable action, Runnable preview) {
            this.action = action;
            this.preview = preview;
        }
    }

    private final List<MenuView.Row> pendingRows = new ArrayList<>();
    private final List<Entry> entries = new ArrayList<>();
    private String menuCrumb, menuTitle;
    private int menuReturnArea = TOP, menuAnchorPill = -1, lastSection;
    private Runnable menuBack, menuCancel;

    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs, usage;
    private ConnectivityManager.NetworkCallback netWatch;
    private boolean weatherLoading;
    private int pendingSleepMinutes;

    private final BroadcastReceiver clockReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            // Automatic appearance: flip at sunrise / sunset (checked once a minute).
            if (theme != null && Theme.isLight(prefs) != theme.light) {
                recreate();
                return;
            }
            updateClock();
            refreshWeather(false);
            // A new day: a new photo of the day (checked once a minute, fetched once a day).
            if ("online".equals(currentBg()) && !today().equals(prefs.getString("online_day", ""))) showOnline();
        }
    };

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            // An icon pack may have been installed, updated or removed.
            IconPacks.clear();
            reloadApps();
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (BuildConfig.DEBUG) {
            // Debug builds log any disk or network use on the main thread, and leaked objects.
            android.os.StrictMode.setThreadPolicy(new android.os.StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads().detectDiskWrites().detectNetwork().penaltyLog().build());
            android.os.StrictMode.setVmPolicy(new android.os.StrictMode.VmPolicy.Builder()
                    .detectActivityLeaks().detectLeakedClosableObjects().detectLeakedRegistrationObjects()
                    .penaltyLog().build());
        }
        setContentView(R.layout.activity_main);
        tk = Tokens.of(this);
        root = findViewById(R.id.root);
        hero = findViewById(R.id.hero);
        scroller = findViewById(R.id.scroller);
        topBar = findViewById(R.id.topBar);
        dockBand = findViewById(R.id.dockBand);
        gridSection = findViewById(R.id.gridSection);
        content = findViewById(R.id.content);
        glow = findViewById(R.id.glow);
        edgeTop = findViewById(R.id.edgeTop);
        edgeBottom = findViewById(R.id.edgeBottom);
        gridScrim = findViewById(R.id.gridScrim);
        dockList = findViewById(R.id.dockList);
        greeting = findViewById(R.id.greeting);
        clock = findViewById(R.id.clock);
        date = findViewById(R.id.date);
        weather = findViewById(R.id.weather);
        dockTitle = findViewById(R.id.dockTitle);
        wifiLabel = findViewById(R.id.wifiLabel);
        moveHint = findViewById(R.id.moveHint);
        highlight = findViewById(R.id.highlight);
        wallpaper = findViewById(R.id.wallpaper);
        // Second layer for the slideshow: the next photo fades in on top, then takes over.
        wallpaperNext = new ImageView(this);
        wallpaperNext.setScaleType(ImageView.ScaleType.CENTER_CROP);
        wallpaperNext.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        wallpaperNext.setVisibility(View.GONE);
        root.addView(wallpaperNext, root.indexOfChild(wallpaper) + 1, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        pills = new View[] {findViewById(R.id.pillNotif), findViewById(R.id.pillSearch), findViewById(R.id.pillInputs),
                findViewById(R.id.pillWifi), findViewById(R.id.pillCustomize), findViewById(R.id.pillSettings)};
        // A button opening (or a neighbour making room) moves its edges: the focus ring follows.
        for (View p : pills) {
            p.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                if (l != ol || r != or) followPill(v);
            });
        }
        notifLabel = findViewById(R.id.notifLabel);
        wifiIcon = findViewById(R.id.wifiIcon);
        prefs = SafePrefs.focus(this);
        usage = SafePrefs.usage(this);
        sounds = new Sounds(this, false);
        theme = Theme.of(this);
        categories = Categories.load(prefs);

        clock.setTypeface(tk.regular);
        greeting.setTypeface(tk.medium500);
        date.setTypeface(tk.regular);
        weather.setTypeface(tk.regular);
        dockTitle.setTypeface(tk.medium500);
        for (View pill : pills) {
            ViewGroup g = (ViewGroup) pill;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (g.getChildAt(i) instanceof TextView) ((TextView) g.getChildAt(i)).setTypeface(tk.medium500);
            }
        }

        labelMode = prefs.getInt("labels", TileAdapter.LABELS_FOCUS);
        computeLayout();
        // Placeholders until the apps arrive, so the dock is never a blank strip.
        dockAdapter.showPlaceholders(columns);

        glowColor = theme.accent;
        glowDrawable = new GradientDrawable();
        glowDrawable.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glowDrawable.setGradientRadius(getResources().getDimensionPixelSize(R.dimen.glow_radius));
        setGlow(glowColor);
        glow.setBackground(glowDrawable);
        glowOn = prefs.getBoolean("glow", true);

        menu = new MenuView(this, theme);
        menu.setVisibility(View.GONE);
        root.addView(menu, root.indexOfChild(highlight) + 1, new FrameLayout.LayoutParams(
                tk.sheetWidth, ViewGroup.LayoutParams.WRAP_CONTENT));
        search = new SearchOverlay(this, theme, tk, prefs, searchHost);
        root.addView(search, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        toaster = new Ui.Toaster(root, theme, tk);
        // A message saved before Home restarted itself (a theme, restore or reset).
        String pending = prefs.getString("toast", null);
        if (pending != null) {
            prefs.edit().remove("toast").apply();
            ui.postDelayed(() -> toast(pending, true), tk.slow);
        }
        // After each update of Home: a backup, in case the new version changes something you liked.
        try {
            long updated = getPackageManager().getPackageInfo(getPackageName(), 0).lastUpdateTime;
            long seen = prefs.getLong("seen_update", 0);
            if (seen != updated) {
                prefs.edit().putLong("seen_update", updated).apply();
                if (seen != 0) loader.execute(() -> autoBackup("After update"));
            }
        } catch (PackageManager.NameNotFoundException ignored) {
        }

        applyTheme();
        applyBackground(currentBg(), false);
        area = prefs.getInt("area", DOCK);
        if (area == MENU) area = TOP;
        topIndex = Math.min(pills.length - 1, prefs.getInt("top", PILL_INPUTS));
        hasInputs = Device.hasInputs(this);
        applyClockLook();
        applyBarLook();
        Notifs.onChange = notifWatch;
        savedDockPkg = prefs.getString("dock", null);
        savedGridPkg = prefs.getString("grid", null);
        savedGridSec = prefs.getString("grid_sec", null);

        root.getViewTreeObserver().addOnGlobalLayoutListener(this::onRootLayout);
        root.requestFocus();

        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addAction(Intent.ACTION_PACKAGE_REPLACED);
        pf.addDataScheme("package");
        register(packageReceiver, pf);

        updateClock();
        updateWeatherView();
        reloadApps();
        handleOpen(getIntent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter cf = new IntentFilter(Intent.ACTION_TIME_TICK);
        cf.addAction(Intent.ACTION_TIME_CHANGED);
        cf.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        register(clockReceiver, cf);
        updateClock();
        refreshWeather(false);
        startNetworkWatch();
        if (Theme.isLight(prefs) != theme.light) {
            // Automatic mode crossed sunrise or sunset while an app was open.
            recreate();
            return;
        }
        isResumedNow = true;
        sounds.start();
        resumed = new java.lang.ref.WeakReference<>(this);
        PanelService.homeShown();
        // Back on Home: carry on with a fresh photo, the animation or the video.
        if ("slideshow".equals(currentBg()) && !slideshowOn) startSlideshow();
        resumeAnimNow();
        if (player != null) {
            try {
                player.start();
            } catch (RuntimeException ignored) {
            }
        } else if ("video".equals(currentBg())) {
            if (videoView == null) applyBackground("video", false);
            else if (videoView.isAvailable()) startPlayer(videoView.getSurfaceTexture());
        }
        videoWarned = false;
        resetIdle();
        if (appsLoaded) maybeLaunchOnBoot();
    }

    // ---- light / dark -----------------------------------------------------------------------

    /** Colours the views laid out in XML; menus and tiles read {@link #theme} as they're built. */
    private void applyTheme() {
        Theme t = theme;
        root.setBackgroundColor(t.bg);
        getWindow().setBackgroundDrawable(new ColorDrawable(t.bg));
        clock.setTextColor(t.text);
        greeting.setTextColor(t.textSecondary);
        date.setTextColor(t.textSecondary);
        weather.setTextColor(t.textSecondary);
        dockTitle.setTextColor(t.text);
        // Corner fades behind the clock (top left) and the app name (bottom left) only, so the rest of
        // the photo stays exactly as it is. Three stops: solid behind the text, then an easy fall-off.
        int[] edge = {t.scrimEdge, Ui.alpha(t.scrimEdge, 0.55f), Ui.alpha(t.scrimEdge, 0f)};
        edgeTop.setBackground(cornerFade(edge, 0f));
        edgeBottom.setBackground(cornerFade(edge, 1f));
        gridScrim.setBackgroundColor(t.scrimGrid);
        dockBand.setBackground(Ui.rounded(tk.radiusLg, t.surfaceSoft, t.stroke, tk.stroke));
        moveHint.setBackground(Ui.rounded(tk.radiusLg, t.surface, t.stroke, tk.stroke));
        moveHint.setElevation(tk.elevationSheet);
        highlight.setTheme(t);
        paintPills();
    }

    /** A radial fade from the left corner at the top ({@code y} 0) or bottom ({@code y} 1) of its view. */
    private GradientDrawable cornerFade(int[] colors, float y) {
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setGradientCenter(0f, y);
        g.setGradientRadius(getResources().getDimensionPixelSize(R.dimen.edge_radius));
        g.setColors(colors);
        return g;
    }

    /** The focused pill fills with the accent; the rest are quiet frosted chips. */
    private int litPill = -2;

    private void paintPills() {
        int lit = area == TOP || (area == MENU && menuReturnArea == TOP) ? topIndex : -1;
        if (lit == litPill) return;
        boolean first = litPill == -2;
        litPill = lit;
        for (int p = 0; p < pills.length; p++) {
            boolean on = p == lit;
            // Circles with just the icon; the one you're on opens to show its name.
            shapePill(p, on, !first);
            pills[p].setBackground(Ui.rounded(pillHeight() / 2f, on ? theme.accentFill : theme.surfaceSoft,
                    theme.stroke, on ? 0 : tk.stroke));
            ViewGroup g = (ViewGroup) pills[p];
            int ink = on ? theme.onAccent : theme.text;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                if (c instanceof TextView) ((TextView) c).setTextColor(ink);
                else if (c instanceof ImageView) ((ImageView) c).setImageTintList(ColorStateList.valueOf(ink));
            }
        }
    }

    // ---- top-bar buttons: circles that open up when focused ------------------------------------

    private final android.animation.ValueAnimator[] pillAnims = new android.animation.ValueAnimator[6];

    /** Space before the icon: centres it in a circle as tall as the button. */
    private int pillPadStart() {
        return (pillHeight() - tk.iconSm) / 2;
    }

    /** A button's width when open: icon, then its text (if it shows one), as it always looked. */
    private int pillOpenWidth(View pill) {
        ViewGroup g = (ViewGroup) pill;
        int w = pillPadStart() + tk.iconSm, text = 0;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (!(c instanceof TextView) || c.getVisibility() == View.GONE) continue;
            TextView t = (TextView) c;
            int natural = (int) Math.ceil(t.getPaint().measureText(t.getText().toString()));
            if (t.getMaxWidth() > 0 && t.getMaxWidth() < Integer.MAX_VALUE) natural = Math.min(natural, t.getMaxWidth());
            // The text keeps its own width while the button opens, so it's revealed, not squeezed.
            ViewGroup.LayoutParams lp = t.getLayoutParams();
            if (lp.width != natural) {
                lp.width = natural;
                t.setLayoutParams(lp);
            }
            text = natural;
        }
        return text == 0 ? pillHeight() : w + tk.space2 + text + tk.space5 - tk.space1;
    }

    /** {@code open}: shows the button's text; otherwise a circle with the icon. */
    private void shapePill(int p, boolean open, boolean animate) {
        View pill = pills[p];
        int to = open ? pillOpenWidth(pill) : pillHeight();
        pill.setPaddingRelative(pillPadStart(), 0, 0, 0);
        ViewGroup g = (ViewGroup) pill;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c instanceof TextView) c.animate().alpha(open ? 1f : 0f).setDuration(open ? tk.focus : tk.fast).start();
        }
        ViewGroup.LayoutParams lp = pill.getLayoutParams();
        if (pillAnims[p] != null) pillAnims[p].cancel();
        int fromW = lp.width > 0 ? lp.width : pill.getWidth();
        if (!animate || fromW <= 0 || fromW == to || !isResumedNow) {
            lp.width = to;
            pill.setLayoutParams(lp);
            return;
        }
        android.animation.ValueAnimator a = android.animation.ValueAnimator.ofInt(fromW, to);
        a.setDuration(tk.focus);
        a.setInterpolator(Tokens.EASE_OUT);
        a.addUpdateListener(v -> {
            lp.width = (int) v.getAnimatedValue();
            pill.setLayoutParams(lp);
        });
        pillAnims[p] = a;
        a.start();
    }

    /** While a button opens or closes, the focus ring follows its edges (see HighlightView.retarget). */
    private void followPill(View pill) {
        if (pill != currentTarget()) return;
        int base = area == MENU ? menuReturnArea : area;
        if (base != TOP) return;
        float s = tk.scalePill;
        Rect r = rectOf(pill, true);
        RectF rf = new RectF(r);
        rf.inset(-r.width() * (s - 1f) / 2f, -r.height() * (s - 1f) / 2f);
        highlight.retarget(rf, rf.height() / 2f);
    }

    /** Each mode keeps its own background, so automatic mode looks right day and night. */
    private String bgKey() {
        return theme.light ? "bg_light" : "bg";
    }

    private String currentBg() {
        if (!theme.light) return prefs.getString("bg", "");
        String dark = prefs.getString("bg", "");
        // Until a light background is picked: your photos carry over, otherwise Pearl.
        String fallback = dark.equals("photo") || dark.equals("slideshow") ? dark : "preset:6";
        return prefs.getString("bg_light", fallback);
    }

    private int photoScrim() {
        // config.xml's darkening applies to dark mode; light mode leaves photos exactly as they are.
        return theme.light ? theme.scrimPhoto : getResources().getInteger(R.integer.wallpaper_scrim) << 24;
    }

    /**
     * Text sits on soft edge fades only when the background is a photo. A photo covers the whole
     * screen, so the solid colours under it are dropped too (each is a full-screen fill per frame).
     */
    private void setEdges(boolean photo) {
        // Dark mode dims the photo itself, which already keeps light text readable; the fades
        // (measured at ~2 ms a frame on this GPU) are only needed for dark text in light mode.
        boolean fades = photo && theme.light;
        edgeTop.setVisibility(fades ? View.VISIBLE : View.GONE);
        edgeBottom.setVisibility(fades ? View.VISIBLE : View.GONE);
        boolean covered = photo || (wallpaper.getVisibility() == View.VISIBLE && wallpaper.getDrawable() != null);
        root.setBackground(covered ? null : new ColorDrawable(theme.bg));
        getWindow().setBackgroundDrawable(covered ? null : new ColorDrawable(theme.bg));
    }

    /** Dark mode's dimming drawn into the photo once, instead of as a full-screen layer every frame. */
    private Bitmap dimmed(Bitmap bm) {
        int scrim = photoScrim();
        if (bm == null || (scrim >>> 24) == 0 || !bm.isMutable()) return bm;
        new android.graphics.Canvas(bm).drawColor(scrim);
        return bm;
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Hand the video decoder back: the app in front may need it. onResume starts it again.
        releasePlayer();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Nothing decodes, fades or plays while an app is on screen.
        isResumedNow = false;
        // Search never stays open behind an app; Home comes back without it.
        if (search.isOpen() && !askingMic) search.hideNow();
        sounds.stop();
        resumed = new java.lang.ref.WeakReference<>(null);
        ui.removeCallbacks(idleTick);
        if (idleOverlay != null) {
            root.removeView(idleOverlay);
            idleOverlay = null;
            idleClock = null;
            idleDate = null;
        }
        stopSlideshow();
        if (anim != null) anim.stop();
        if (player != null) {
            try {
                player.pause();
            } catch (RuntimeException ignored) {
            }
        }
        if (moving) endMove();
        unregisterReceiver(clockReceiver);
        stopNetworkWatch();
        prefs.edit()
                .putInt("area", area == MENU ? menuReturnArea : area)
                .putInt("top", topIndex)
                .putString("dock", currentDockPkg())
                .putString("grid", currentGridPkg())
                .putString("grid_sec", currentGridSec())
                .apply();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packageReceiver);
        loader.shutdownNow();
        search.destroy();
        // Nothing may keep this (old) screen alive after a theme or font change rebuilt it.
        ui.removeCallbacksAndMessages(null);
        if (Notifs.onChange == notifWatch) Notifs.onChange = null;
        sounds.destroy();
        stopVideo();
        stopAnim();
        super.onDestroy();
    }

    /** The ≡ helper's remote shortcuts start Home with "open": customize or next_bg. */
    private void handleOpen(Intent intent) {
        String open = intent == null ? null : intent.getStringExtra("open");
        if (open == null) return;
        intent.removeExtra("open");
        ui.postDelayed(() -> {
            if (isDestroyed()) return;
            if ("customize".equals(open)) {
                if (area == MENU) {
                    leaveMenu();
                    dismissMenu();
                }
                area = TOP;
                // The sheet belongs to the Customize button, so that's the one lit (not wherever focus was).
                if (pills[PILL_CUSTOMIZE].getVisibility() == View.VISIBLE) topIndex = PILL_CUSTOMIZE;
                requirePin(Lock.hasPin(this) && (Lock.guest(this) || customizeLocked()), tr(R.string.customize),
                        () -> openCustomize(0));
            } else if ("next_bg".equals(open)) {
                nextBackground();
            } else if ("search".equals(open)) {
                openSearch(false);
            }
        }, tk.medium);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.hasExtra("open")) {
            handleOpen(intent);
            return;
        }
        // The release of a Home held to open search can arrive as a Home press: search stays.
        if (android.os.SystemClock.uptimeMillis() - searchHeldAt < 1500) return;
        if (search.isOpen()) {
            search.hide(false);
            if (hasWindowFocus()) {
                goToStart();
                applyFocus(true);
            }
            return;
        }
        // Home pressed while already on Home: back to the dock. Coming back from an app keeps focus.
        if (hasWindowFocus()) {
            if (moving) endMove();
            if (area == MENU) {
                leaveMenu();
                dismissMenu();
            }
            goToStart();
            applyFocus(true);
        }
    }

    // ---- layout: tile size, spacing, margins (Customize › Home layout › Layout) ---------------

    private static final int[] GAPS = {R.dimen.tile_gap_tight, R.dimen.tile_gap, R.dimen.tile_gap_roomy};
    private static final int[] SPACING = {R.string.spacing_tight, R.string.spacing_normal, R.string.spacing_roomy};
    private static final int[] MARGINS = {R.string.margin_standard, R.string.margin_wider, R.string.margin_widest};
    /** The same, with what they're for, in the list to pick from. */
    private static final int[] MARGIN_OPTIONS = {R.string.margin_standard, R.string.margin_wider_long,
            R.string.margin_widest_long};
    /** After sorting a row, in the order of Categories.SORTS. */
    private static final int[] SORTED_TOASTS = {R.string.toast_sorted_custom, R.string.toast_sorted_az,
            R.string.toast_sorted_recent, R.string.toast_sorted_used};
    private int innerW, rowGap;

    private int pref(String key, int def, int max) {
        return Math.max(0, Math.min(max, prefs.getInt(key, def)));
    }

    /**
     * Tile sizes from the layout settings. Every option stays inside the overscan-safe area: margins
     * only ever add to it, and spacing and size come from fixed steps.
     */
    private void computeLayout() {
        // Slots chosen in Customize win over config.xml.
        columns = prefs.getInt("columns", 0);
        if (columns == 0) columns = getResources().getInteger(R.integer.columns);
        columns = Math.max(4, Math.min(7, columns));
        int margin = new int[] {0, tk.space4, tk.space6}[pref("margin", 0, 2)];
        scroller.setPadding(margin, 0, margin, 0);
        scroller.setClipToPadding(false);
        int screenW = getResources().getDisplayMetrics().widthPixels - 2 * margin;
        innerW = screenW - tk.safeH * 2 - tk.space4 * 2;
        colW = innerW / columns;
        cardW = colW - getResources().getDimensionPixelSize(GAPS[pref("gap", 1, 2)]);
        cardH = Math.round(cardW * 9f / 16f);
        rowGap = pref("row_gap", 1, 2);
        // The big app name starts exactly above the first dock tile.
        ((ViewGroup.MarginLayoutParams) dockTitle.getLayoutParams()).setMarginStart(
                tk.safeH + tk.space4 + (colW - cardW) / 2);
        dockAdapter = newAdapter();
        setupList(dockList, dockAdapter);
    }

    private TileAdapter newAdapter() {
        TileAdapter a = new TileAdapter(cardW, cardH, tk, theme);
        a.setLabelMode(labelMode);
        a.setLook(tileRadius(), pref("icon_bg", 0, 2));
        if (rowGap == 0) a.setRowPadding(tk.space1, 0);
        else if (rowGap == 2) a.setRowPadding(tk.space4, tk.space3);
        return a;
    }

    /** Applies the layout settings in place (no restart), keeping focus on the same app. */
    private void relayout() {
        computeLayout();
        sections.clear();
        gridSection.removeAllViews();
        clearFocused();
        if (appsLoaded) rebuildLists();
        else dockAdapter.showPlaceholders(columns);
        afterLayout(root, () -> applyFocus(false));
    }

    // ---- apps -------------------------------------------------------------------------------

    private void setupList(RecyclerView rv, TileAdapter adapter) {
        rv.setLayoutManager(new GridLayoutManager(this, columns));
        rv.setAdapter(adapter);
        rv.setItemAnimator(null);
        rv.setFocusable(false);
        rv.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        rv.setNestedScrollingEnabled(false);
    }

    private void reloadApps() {
        Context app = getApplicationContext();
        int w = cardW, h = cardH, radius = tileRadius();
        String pack = prefs.getString("icon_pack", "");
        JSONObject own = json("app_icons");
        loader.execute(() -> {
            List<AppRepo.App> all = AppRepo.loadAll(app, w, h, radius);
            AppRepo.applyIcons(app, all, pack, own, w, h, radius);
            ui.post(() -> applyApps(all));
        });
    }

    private void applyApps(List<AppRepo.App> all) {
        if (isDestroyed()) return;
        if (moving) endMove();
        boolean first = !appsLoaded;
        appsLoaded = true;
        allApps.clear();
        allApps.addAll(all);
        applyAppLooks();
        rebuildLists();
        if (first && isResumedNow) maybeLaunchOnBoot();
    }

    /** Your names and tile choices on top of what each app provides; A–Z follows your names. */
    private void applyAppLooks() {
        JSONObject names = json("app_names"), tiles = json("app_tiles");
        boolean icons = "icon".equals(prefs.getString("tile_style", "banner"));
        for (AppRepo.App a : allApps) {
            String n = names.optString(a.pkg, "");
            a.label = n.isEmpty() ? a.original : n;
            String t = tiles.optString(a.pkg, "");
            a.iconTile = a.customIcon || "icon".equals(t) || (icons && !"banner".equals(t));
        }
        java.text.Collator collator = java.text.Collator.getInstance();
        allApps.sort((x, y) -> collator.compare(x.label, y.label));
    }

    private JSONObject json(String key) {
        try {
            return new JSONObject(prefs.getString(key, "{}"));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    /** Sets (or with null, removes) one app's entry in a JSON map pref. */
    private void putJson(String key, String pkg, String value) {
        JSONObject o = json(key);
        try {
            if (value == null) o.remove(pkg);
            else o.put(pkg, value);
        } catch (JSONException ignored) {
        }
        prefs.edit().putString(key, o.toString()).apply();
    }

    /** After a name or tile change: redraw every list with the new looks, focus stays put. */
    private void refreshAppLooks() {
        applyAppLooks();
        titlePkg = null;
        rebuildLists();
    }

    /** Dock from the saved order, the rows below from the categories; hidden apps in neither. */
    private void rebuildLists() {
        String keepDock = currentDockPkg(), keepGrid = currentGridPkg(), keepSec = currentGridSec();
        Set<String> hidden = hiddenApps();
        Map<String, AppRepo.App> byPkg = new LinkedHashMap<>();
        for (AppRepo.App a : allApps) byPkg.put(a.pkg, a);
        dockApps.clear();
        for (String pkg : dockOrder()) {
            AppRepo.App a = byPkg.get(pkg);
            if (a != null && !hidden.contains(pkg) && !dockApps.contains(a) && dockApps.size() < columns) {
                dockApps.add(a);
            }
        }
        rebuildGrid();
        dockIndex = indexOf(dockApps, keepDock);
        focusGrid(keepSec, keepGrid);
        if (area == GRID && !hasGrid()) area = DOCK;
        if (area == DOCK && dockApps.isEmpty()) area = hasGrid() ? GRID : TOP;
        clearFocused();
        dockAdapter.submit(dockApps);
        focusPending = true;
        root.requestLayout();
    }

    /**
     * One section per category, in your order: a category's own apps, "Apps" everything else.
     * Docked and hidden apps are left out (a docked app keeps its category for when it leaves the dock).
     */
    private void rebuildGrid() {
        Set<String> hidden = hiddenApps();
        Map<String, AppRepo.App> byPkg = new LinkedHashMap<>();
        for (AppRepo.App a : allApps) byPkg.put(a.pkg, a);
        Set<String> claimed = new HashSet<>();
        for (Categories.Cat c : categories.list) if (!c.isApps()) claimed.addAll(c.apps);
        List<Section> next = new ArrayList<>();
        for (Categories.Cat c : categories.list) {
            Section s = new Section(c);
            if (c.isApps()) {
                for (AppRepo.App a : allApps) {
                    if (!claimed.contains(a.pkg) && !hidden.contains(a.pkg) && !dockApps.contains(a)) s.apps.add(a);
                }
            } else {
                for (String pkg : c.apps) {
                    AppRepo.App a = byPkg.get(pkg);
                    if (a != null && !hidden.contains(pkg) && !dockApps.contains(a)) s.apps.add(a);
                }
            }
            sortSection(s);
            if (!s.apps.isEmpty() || categories.showEmpty) next.add(s);
        }
        if (categories.inputsRow) {
            Section inputs = new Section(null, Section.INPUTS);
            for (AppRepo.App a : inputApps()) if (!hidden.contains(a.pkg)) inputs.apps.add(a);
            if (!inputs.apps.isEmpty()) next.add(inputs);
        }
        if (categories.allAppsRow) {
            Section all = new Section(null, Section.ALL);
            for (AppRepo.App a : allApps) if (!hidden.contains(a.pkg)) all.apps.add(a);
            next.add(all);
        }
        syncSections(next);
    }

    private List<AppRepo.App> inputTiles;

    /**
     * A tile per HDMI (and other pass-through) input the projector reports, named as in Android's
     * settings, renamable with hold OK. Kept between rebuilds; inputs don't change while Home runs.
     */
    private List<AppRepo.App> inputApps() {
        if (inputTiles != null) {
            JSONObject names = json("app_names");
            for (AppRepo.App a : inputTiles) {
                String n = names.optString(a.pkg, "");
                a.label = n.isEmpty() ? a.original : n;
            }
            return inputTiles;
        }
        inputTiles = new ArrayList<>();
        TvInputManager tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) return inputTiles;
        List<TvInputInfo> inputs = new ArrayList<>();
        for (TvInputInfo info : tim.getTvInputList()) {
            if (info.getParentId() == null && !info.isHidden(this) && info.isPassthroughInput()) inputs.add(info);
        }
        inputs.sort(Comparator.comparingInt(MainActivity::inputRank).thenComparing(this::inputLabel));
        for (TvInputInfo info : inputs) {
            AppRepo.App a = new AppRepo.App();
            a.pkg = "input:" + info.getId();
            a.original = a.label = inputLabel(info);
            Drawable icon = getDrawable(R.drawable.ic_hdmi);
            a.icon = icon == null ? null : icon.mutate();
            a.tintIcon = true;
            a.color = theme.accentFill;
            inputTiles.add(a);
        }
        return inputApps();
    }

    private static boolean isInput(AppRepo.App a) {
        return a.pkg.startsWith("input:");
    }

    /** Custom order, A–Z, last opened or most opened; ties stay A–Z (allApps is A–Z, sorts are stable). */
    private void sortSection(Section s) {
        // Input tiles aren't apps: they keep the projector's order.
        if (Section.INPUTS.equals(s.special)) return;
        Categories.sort(s.apps, s.cat, a -> a.pkg, allApps, a -> usage.getInt(a.pkg, 0),
                a -> usage.getLong("t:" + a.pkg, 0));
    }

    /**
     * Puts the new sections on screen. Same rows as before (the usual case: an app moved or was
     * hidden): the lists just get new contents. Otherwise the headers and lists are built again.
     */
    private void syncSections(List<Section> next) {
        // Names over the rows once there's more than plain "Apps" (Home then looks as it always did).
        boolean headers = next.size() > 1 || (next.size() == 1 && !(next.get(0).cat != null && next.get(0).cat.isApps()));
        boolean same = next.size() == sections.size();
        for (int i = 0; same && i < next.size(); i++) {
            Section a = sections.get(i), b = next.get(i);
            same = a.id().equals(b.id()) && a.single() == b.single() && (a.header != null) == headers;
        }
        if (same) {
            for (int i = 0; i < next.size(); i++) {
                Section old = sections.get(i), s = next.get(i);
                s.header = old.header;
                s.list = old.list;
                s.adapter = old.adapter;
                s.first = Math.min(old.first, Math.max(0, s.apps.size() - columns));
            }
        } else {
            gridSection.removeAllViews();
            for (int i = 0; i < next.size(); i++) buildSection(next.get(i), headers, i == 0);
        }
        sections.clear();
        sections.addAll(next);
        for (Section s : sections) {
            if (s.header != null) s.header.setText(s.apps.isEmpty() ? tr(R.string.row_empty, sectionName(s)) : sectionName(s));
            s.adapter.submit(s.apps);
            if (s.single()) ((LinearLayoutManager) s.list.getLayoutManager()).scrollToPositionWithOffset(s.first, 0);
        }
    }

    private void buildSection(Section s, boolean header, boolean firstSection) {
        if (header) {
            s.header = Ui.text(this, sectionName(s), tk.textLabel, theme.textSecondary, tk.medium500);
            s.header.setLetterSpacing(0.02f);
            LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            // Lined up with the left edge of the first tile, not the cell.
            hlp.setMarginStart((colW - cardW) / 2);
            hlp.topMargin = firstSection ? tk.space2 : new int[] {tk.space4, tk.space5, tk.space6}[rowGap];
            hlp.bottomMargin = tk.space1;
            gridSection.addView(s.header, hlp);
        }
        s.list = new RecyclerView(this);
        s.list.setClipChildren(false);
        s.list.setClipToPadding(false);
        s.list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        s.adapter = newAdapter();
        if (s.single()) {
            s.adapter.setCellWidth(colW);
            s.list.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false) {
                @Override
                protected void calculateExtraLayoutSpace(@NonNull RecyclerView.State state, @NonNull int[] extra) {
                    // Tiles just past the edge are laid out too, so focus can land on them at once.
                    extra[0] = colW * 2;
                    extra[1] = colW * 2;
                }
            });
            // The row is a few pixels wider than whole columns; padding the end by that much lets
            // the last scroll step land on a column instead of stopping at the end of the tiles.
            s.list.setPadding(0, 0, innerW - columns * colW, 0);
            s.list.setAdapter(s.adapter);
            s.list.setItemAnimator(null);
            s.list.setFocusable(false);
            s.list.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            s.list.setNestedScrollingEnabled(false);
        } else {
            setupList(s.list, s.adapter);
        }
        gridSection.addView(s.list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private boolean hasGrid() {
        for (Section s : sections) if (!s.apps.isEmpty()) return true;
        return false;
    }

    private Section sec() {
        return sections.isEmpty() ? null : sections.get(Math.min(gridSec, sections.size() - 1));
    }

    /** Focus on {@code pkg} in section {@code id}, or wherever that app is, or the first app. */
    private void focusGrid(String id, String pkg) {
        gridSec = -1;
        for (int i = 0; i < sections.size() && gridSec < 0; i++) {
            Section s = sections.get(i);
            if (s.id().equals(id) && indexOfOrNeg(s.apps, pkg) >= 0) gridSec = i;
        }
        for (int i = 0; i < sections.size() && gridSec < 0; i++) {
            if (sections.get(i).cat != null && indexOfOrNeg(sections.get(i).apps, pkg) >= 0) gridSec = i;
        }
        for (int i = 0; i < sections.size() && gridSec < 0; i++) if (!sections.get(i).apps.isEmpty()) gridSec = i;
        if (gridSec < 0) {
            gridSec = 0;
            gridIndex = 0;
            return;
        }
        gridIndex = Math.max(0, indexOfOrNeg(sections.get(gridSec).apps, pkg));
        keepInRow(sections.get(gridSec));
    }

    private static int indexOfOrNeg(List<AppRepo.App> list, String pkg) {
        for (int i = 0; i < list.size(); i++) if (list.get(i).pkg.equals(pkg)) return i;
        return -1;
    }

    /** Sideways rows: scrolls by whole tiles so the focused one is in view. */
    private void keepInRow(Section s) {
        if (!s.single()) return;
        if (gridIndex < s.first) s.first = gridIndex;
        else if (gridIndex >= s.first + columns) s.first = gridIndex - columns + 1;
    }

    /** Where the focused tile sits across the screen, 0 to columns - 1. */
    private int visualCol() {
        Section s = sec();
        return s == null ? 0 : s.single() ? gridIndex - s.first : gridIndex % columns;
    }

    /** The next section with apps in {@code dir}; -1 if none. {@code movable} skips "All apps". */
    private int nextSection(int from, int dir, boolean movable) {
        for (int i = from + dir; i >= 0 && i < sections.size(); i += dir) {
            Section s = sections.get(i);
            if (movable ? s.cat != null : !s.apps.isEmpty()) return i;
        }
        return -1;
    }

    /** Lands in section {@code to} at column {@code col}: its top row coming down, its last row going up. */
    private void enterSection(int to, int col, boolean fromAbove) {
        Section s = sections.get(to);
        gridSec = to;
        int n = s.apps.size();
        if (n == 0) {
            gridIndex = 0;
            return;
        }
        if (s.single()) gridIndex = Math.min(s.first + col, n - 1);
        else gridIndex = Math.min((fromAbove ? 0 : (n - 1) / columns * columns) + col, n - 1);
        keepInRow(s);
    }

    /** Where Home lands when you press Home: the dock, or the category picked in Customize. */
    private void goToStart() {
        if (!Categories.DOCK.equals(categories.startOn)) {
            for (int i = 0; i < sections.size(); i++) {
                if (sections.get(i).id().equals(categories.startOn) && !sections.get(i).apps.isEmpty()) {
                    area = GRID;
                    gridSec = i;
                    gridIndex = 0;
                    sections.get(i).first = 0;
                    if (sections.get(i).single()) ((LinearLayoutManager) sections.get(i).list.getLayoutManager())
                            .scrollToPositionWithOffset(0, 0);
                    return;
                }
            }
        }
        area = !dockApps.isEmpty() ? DOCK : hasGrid() ? GRID : TOP;
        if (area == GRID) enterSection(Math.max(0, nextSection(-1, 1, false)), 0, true);
    }

    /** Remembers a section as it is now as its own order, and switches it to that. */
    private void saveSectionOrder(Section s, boolean announce) {
        if (s.cat == null) return;
        List<String> pkgs = new ArrayList<>();
        for (AppRepo.App a : s.apps) pkgs.add(a.pkg);
        // Keep hidden and docked apps' places too, so they come back where they were.
        for (String old : s.cat.apps) if (!pkgs.contains(old)) pkgs.add(old);
        s.cat.apps.clear();
        s.cat.apps.addAll(pkgs);
        boolean switched = !Categories.SORT_CUSTOM.equals(s.cat.sort);
        s.cat.sort = Categories.SORT_CUSTOM;
        categories.save(prefs);
        if (switched && announce) toast(tr(R.string.toast_row_own_order, sectionName(s)), true);
    }

    private Set<String> hiddenApps() {
        String s = prefs.getString("hidden", "");
        return s.isEmpty() ? new HashSet<>() : new HashSet<>(Arrays.asList(s.split(",")));
    }

    private void setHidden(String pkg, boolean hide) {
        Set<String> hidden = hiddenApps();
        if (hide) hidden.add(pkg);
        else hidden.remove(pkg);
        prefs.edit().putString("hidden", TextUtils.join(",", hidden)).apply();
        rebuildLists();
    }

    private static int indexOf(List<AppRepo.App> list, String pkg) {
        for (int i = 0; i < list.size(); i++) if (list.get(i).pkg.equals(pkg)) return i;
        return 0;
    }

    private String currentDockPkg() {
        return dockApps.isEmpty() ? savedDockPkg : dockApps.get(Math.min(dockIndex, dockApps.size() - 1)).pkg;
    }

    private String currentGridPkg() {
        Section s = sec();
        return s == null || s.apps.isEmpty() ? savedGridPkg : s.apps.get(Math.min(gridIndex, s.apps.size() - 1)).pkg;
    }

    private String currentGridSec() {
        Section s = sec();
        return s == null ? savedGridSec : s.id();
    }

    /** The app under focus in the dock or a row, or null. */
    private AppRepo.App focusedApp() {
        if (area == DOCK || (area == MENU && menuReturnArea == DOCK)) {
            return dockApps.isEmpty() ? null : dockApps.get(Math.min(dockIndex, dockApps.size() - 1));
        }
        Section s = sec();
        return s == null || s.apps.isEmpty() ? null : s.apps.get(Math.min(gridIndex, s.apps.size() - 1));
    }

    private void launch(AppRepo.App app) {
        requirePin(Lock.appLocked(this, app.pkg, isInput(app) ? null : categories.of(app.pkg).id),
                tr(R.string.pin_title_open_app, app.label), () -> launchNow(app));
    }

    private void launchNow(AppRepo.App app) {
        usage.edit().putInt(app.pkg, usage.getInt(app.pkg, 0) + 1)
                .putLong("t:" + app.pkg, System.currentTimeMillis()).apply();
        if (isInput(app)) {
            startSafely(new Intent(Intent.ACTION_VIEW,
                    TvContract.buildChannelUriForPassthroughInput(app.pkg.substring(6))), app.label);
            return;
        }
        startSafely(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(app.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED), app.label);
    }

    private void startSafely(Intent i) {
        startSafely(i, null);
    }

    private void startSafely(Intent i, String what) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            // Opening something by name (an app, an input) is the big moment; settings screens aren't.
            sounds.play(what == null ? Sounds.SELECT : Sounds.LAUNCH);
        } catch (Exception e) {
            sounds.play(Sounds.ERROR);
            toast(what == null ? tr(R.string.toast_screen_unavailable) : tr(R.string.toast_couldnt_open, what), false);
        }
    }

    private void toast(String s, boolean done) {
        // Most confirmations come from a menu that reopens right after; keep them clear of it.
        // In Customization the left side is the section list, so messages stay centred.
        toaster.besideSheet = (area == MENU || menu.getVisibility() == View.VISIBLE) && custMode != CUST_FULL;
        toaster.show(s, done ? R.drawable.ic_check : R.drawable.ic_info);
    }

    // ---- text: every word on screen comes from res/values/strings*.xml -------------------------

    /** A string resource; with {@code args}, its %1$s… placeholders filled in. */
    private String tr(int id, Object... args) {
        return args.length == 0 ? getString(id) : getString(id, args);
    }

    /** A count in words ("1 app", "6 apps"), the right plural form for the language. */
    private String trn(int id, int n, Object... args) {
        return args.length == 0 ? getResources().getQuantityString(id, n, n)
                : getResources().getQuantityString(id, n, args);
    }

    /** The strings for a list of options, in the same order. */
    private String[] trAll(int... ids) {
        String[] out = new String[ids.length];
        for (int i = 0; i < ids.length; i++) out[i] = tr(ids[i]);
        return out;
    }

    /** "On" or "Off" at the end of a toggle's row. */
    private String onOff(boolean on) {
        return tr(on ? R.string.on : R.string.off);
    }

    private String[] sortNames() {
        return trAll(Categories.SORT_NAMES);
    }

    /** A count or number as the language writes it (1,234 / 1 234 / ١٬٢٣٤). */
    private static String number(long n) {
        return java.text.NumberFormat.getIntegerInstance().format(n);
    }

    /** A category's name: yours, or "Apps" in the TV's language. */
    private String catName(Categories.Cat c) {
        return c.name(tr(R.string.category_apps));
    }

    // ---- layout and focus -------------------------------------------------------------------

    /** Hero fills the screen above the dock, so the dock sits at the bottom until you press Down. */
    private void onRootLayout() {
        int h = root.getHeight();
        if (h == 0) return;
        int heroH = h - dockBand.getHeight() - ((ViewGroup.MarginLayoutParams) dockBand.getLayoutParams()).bottomMargin;
        if (heroH > 0 && hero.getLayoutParams().height != heroH) {
            hero.getLayoutParams().height = heroH;
            hero.requestLayout();
            return;
        }
        if (gridSection.getMinimumHeight() != h) {
            gridSection.setMinimumHeight(h);
            return;
        }
        if (focusPending && appsLoaded) {
            focusPending = false;
            applyFocus(false);
            // Apps are on screen: Android records this as "fully drawn" (start-up measurements).
            if (!reportedDrawn) {
                reportedDrawn = true;
                reportFullyDrawn();
                if (!prefs.getBoolean("tip_seen", false)) ui.postDelayed(this::showFirstRunTip, tk.crossfade);
            }
        }
    }

    // First run: three things worth knowing, once. Any button closes it.

    private View tipView;

    private void showFirstRunTip() {
        if (isDestroyed() || tipView != null || area == MENU || !isResumedNow) return;
        prefs.edit().putBoolean("tip_seen", true).apply();
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(tk.space6, tk.space5, tk.space6, tk.space5);
        card.setBackground(Ui.rounded(tk.radiusLg, theme.surface, theme.stroke, tk.stroke));
        card.setElevation(tk.elevationSheet);
        card.addView(Ui.text(this, tr(R.string.tip_title), tk.textHeadline, theme.text, tk.semibold));
        String[][] tips = {
                {tr(R.string.key_ok), tr(R.string.tip_hold_ok)},
                {"≡", tr(R.string.tip_menu_button)},
                {"▲", tr(R.string.tip_customize)}};
        for (String[] t : tips) {
            LinearLayout row = Ui.hints(this, theme, tk, t[0], t[1]);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = tk.space4;
            // The hint row's label is the tip itself, so it reads as body text, not a caption.
            TextView label = (TextView) row.getChildAt(1);
            label.setTextSize(TypedValue.COMPLEX_UNIT_PX, tk.textBody);
            label.setTextColor(theme.textSecondary);
            card.addView(row, lp);
        }
        TextView any = Ui.text(this, tr(R.string.tip_any_button), tk.textCaption, theme.textTertiary, tk.regular);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = tk.space5;
        card.addView(any, alp);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        root.addView(card, lp);
        tipView = card;
        card.setAlpha(0f);
        card.setTranslationY(tk.space4);
        card.animate().alpha(1f).translationY(0).setDuration(tk.slow).setInterpolator(Tokens.EASE_OUT).start();
    }

    /** True if the press only closed the tip. */
    private boolean dismissTip(KeyEvent e) {
        if (tipView == null) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            View v = tipView;
            tipView = null;
            tipUpTime = e.getDownTime();
            v.animate().alpha(0f).setDuration(tk.medium).withEndAction(() -> root.removeView(v)).start();
        }
        return true;
    }

    private long tipUpTime = -1;

    /**
     * Forget the focused tile before the lists change. Tiles that don't change keep their views
     * (TileAdapter only rebinds what changed), so the old one is scaled back down here.
     */
    private void clearFocused() {
        if (focused != null) {
            focused.animate().cancel();
            focused.setScaleX(1f);
            focused.setScaleY(1f);
        }
        if (focusedLabel != null && labelMode == TileAdapter.LABELS_FOCUS) {
            focusedLabel.animate().cancel();
            focusedLabel.setAlpha(0f);
        }
        focused = null;
        focusedLabel = null;
    }

    private boolean reportedDrawn;
    /** Does this TV have inputs to switch to (read once at start; inputs don't come and go). */
    private boolean hasInputs = true;
    private final Runnable notifWatch = () -> ui.post(() -> {
        if (!isDestroyed()) applyBarLook();
    });

    /**
     * An animated photo makes the whole screen redraw on every frame, which this GPU can't do while
     * focus moves too. So while you press buttons it's swapped for a still of the current frame,
     * and it plays again shortly after the last press.
     */
    private final Runnable resumeAnim = this::resumeAnimNow;

    private void holdAnim() {
        if (anim == null || wallpaper.getDrawable() != anim) return;
        anim.stop();
        int w = Math.max(1, anim.getIntrinsicWidth()), h = Math.max(1, anim.getIntrinsicHeight());
        try {
            Bitmap still = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            anim.draw(new android.graphics.Canvas(still));
            wallpaper.setImageBitmap(still);
        } catch (RuntimeException | OutOfMemoryError ignored) {
        }
    }

    private void resumeAnimNow() {
        if (anim == null || !isResumedNow) return;
        if (wallpaper.getDrawable() != anim) wallpaper.setImageDrawable(anim);
        anim.start();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        // The first-run tip: the press that closes it (and its release) does nothing else.
        if (dismissTip(e)) return true;
        if (e.getAction() == KeyEvent.ACTION_UP && e.getDownTime() == tipUpTime) {
            tipUpTime = -1;
            return true;
        }
        // Idle: the press that wakes Home only wakes it (its release too, so OK doesn't open an
        // app); every press restarts the idle wait.
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            boolean wasIdle = idleOverlay != null;
            if (wasIdle) wakeDownTime = e.getDownTime();
            resetIdle();
            if (wasIdle) return true;
            if (e.getRepeatCount() == 0 && !search.isOpen()) watchSecret(code);
        } else if (e.getAction() == KeyEvent.ACTION_UP && e.getDownTime() == wakeDownTime) {
            wakeDownTime = -1;
            return true;
        }
        if (anim != null && e.getAction() == KeyEvent.ACTION_DOWN) {
            holdAnim();
            ui.removeCallbacks(resumeAnim);
            ui.postDelayed(resumeAnim, tk.crossfade);
        }
        // Keys the stock launcher used to answer with Settings: MENU/SETTINGS, plus listed scancodes.
        if (code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_SETTINGS || isSettingsScancode(e.getScanCode())) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                if (area == MENU) {
                    leaveMenu();
                    dismissMenu();
                    applyFocus(true);
                }
                // The panel service normally takes ≡ before Home sees it; this is the fallback.
                if (PanelService.isRunning()) PanelService.toggleFromLauncher();
                else startSafely(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK));
            }
            return true;
        }
        // Search owns the keys while it shows (Back steps out of it one level at a time). The
        // release and repeats of an OK held to open it land here too, so they do nothing else.
        if (search.isOpen()) {
            if (search.onKey(e)) return true;
            return super.dispatchKeyEvent(e);
        }
        // OK acts on release, so holding it can open the quick actions instead.
        if (isSelect(code)) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (e.getRepeatCount() == 0) selectPending = true;
                else if (selectPending && (e.isLongPress() || e.getEventTime() - e.getDownTime() >= tk.longPress)) {
                    selectPending = false;
                    onLongSelect();
                }
            } else if (e.getAction() == KeyEvent.ACTION_UP && selectPending) {
                selectPending = false;
                navRepeat = false;
                if (!e.isCanceled()) onNav(code);
            }
            return true;
        }
        boolean handled = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_BACK;
        if (!handled) return super.dispatchKeyEvent(e);
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            navRepeat = e.getRepeatCount() > 0;
            onNav(code);
        }
        return true;
    }

    private boolean isSettingsScancode(int scan) {
        if (scan <= 0) return false;
        for (int s : getResources().getIntArray(R.array.settings_key_scancodes)) if (s == scan) return true;
        return false;
    }

    private static boolean isSelect(int code) {
        return code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BUTTON_A;
    }

    private void onNav(int code) {
        boolean up = code == KeyEvent.KEYCODE_DPAD_UP, down = code == KeyEvent.KEYCODE_DPAD_DOWN;
        boolean left = code == KeyEvent.KEYCODE_DPAD_LEFT, right = code == KeyEvent.KEYCODE_DPAD_RIGHT;
        boolean back = code == KeyEvent.KEYCODE_BACK, select = isSelect(code);
        int wasAt = focusSpot();
        if (moving) {
            moveNav(up, down, left, right, select || back);
            if (select || back) sounds.play(Sounds.SELECT);
            else if (focusSpot() != wasAt) sounds.tick(Sounds.FOCUS, navRepeat);
            return;
        }
        switch (area) {
            case MENU: {
                if (custMode == CUST_FULL && railActive) {
                    railNav(up, down, left, right, select, back);
                    return;
                }
                // Back from a setting opened from search goes straight back to search. Left keeps its
                // usual meaning (up a level, then across to the section list), so the rest of
                // Customization is still a press away; Back from the section list returns to search too.
                if (back && custMode == CUST_FULL && searchReturnMenu != null
                        && searchReturnMenu.equals(menuCrumb + "|" + menuTitle)) {
                    if (menuCancel != null) menuCancel.run();
                    sounds.play(Sounds.BACK);
                    backToSearch();
                    return;
                }
                MenuView.Row row = menu.focused();
                Entry entry = menu.focus() >= 0 && menu.focus() < entries.size() ? entries.get(menu.focus()) : null;
                if ((left || right) && entry != null && entry.adjust != null && row != null && row.enabled()) {
                    // Holding the button repeats (the system sends the repeats to Home).
                    entry.adjust.accept(left ? -1 : 1);
                    menu.refresh(menu.focus());
                    if (!stepHandled) sounds.tick(Sounds.STEP, navRepeat);
                    stepHandled = false;
                    return;
                }
                if (up || down) {
                    if (menu.moveFocus(up ? -1 : 1)) {
                        sounds.tick(Sounds.FOCUS, navRepeat);
                        Runnable preview = entries.get(menu.focus()).preview;
                        if (preview != null) preview.run();
                    }
                    return;
                }
                if (select || (right && row != null && row.kind == MenuView.NAV)) {
                    if (row == null) return;
                    if (!row.enabled()) {
                        if (row.disabledReason != null) menu.flash(row.disabledReason);
                        sounds.play(Sounds.ERROR);
                        return;
                    }
                    Runnable action = entries.get(menu.focus()).action;
                    if (action == null) return;
                    leaveMenu();
                    action.run();
                    sounds.play(menuSound >= 0 ? menuSound : Sounds.SELECT);
                    menuSound = -1;
                    if (area != MENU) dismissMenu();
                } else if (back || left) {
                    Runnable cancel = menuCancel, up1 = menuBack;
                    leaveMenu();
                    if (cancel != null) cancel.run();
                    if (up1 != null) up1.run();
                    sounds.play(area == MENU ? Sounds.BACK : Sounds.CLOSE);
                    if (area != MENU) dismissMenu();
                } else return;
                break;
            }
            case TOP:
                // Up at the very top pulls search down, like a swipe down on a phone's home screen.
                if (up && !navRepeat) {
                    openSearch(true);
                    return;
                }
                if (left) topIndex = nextPill(topIndex, -1);
                else if (right) topIndex = nextPill(topIndex, 1);
                else if (down) {
                    if (!dockApps.isEmpty()) area = DOCK;
                    else if (hasGrid()) {
                        area = GRID;
                        enterSection(nextSection(-1, 1, false), 0, true);
                    }
                } else if (select) {
                    pillAction(topIndex);
                    return;
                }
                break;
            case DOCK:
                if (left) dockIndex = Math.max(0, dockIndex - 1);
                else if (right) dockIndex = Math.min(dockApps.size() - 1, dockIndex + 1);
                else if (up && anyPill()) area = TOP;
                else if (down && hasGrid()) {
                    area = GRID;
                    enterSection(nextSection(-1, 1, false), dockIndex, true);
                } else if (select && !dockApps.isEmpty()) {
                    launch(dockApps.get(dockIndex));
                    return;
                }
                break;
            case GRID: {
                Section s = sec();
                int n = s == null ? 0 : s.apps.size();
                boolean single = s != null && s.single();
                int col = visualCol();
                boolean topRow = single || gridIndex < columns;
                boolean lastRow = single || (gridIndex / columns + 1) * columns >= n;
                if (left && (single ? gridIndex > 0 : col > 0)) gridIndex--;
                else if (right && gridIndex + 1 < n && (single || col < columns - 1)) gridIndex++;
                else if (up && !topRow) gridIndex -= columns;
                else if (up) {
                    int to = nextSection(gridSec, -1, false);
                    if (to >= 0) enterSection(to, col, false);
                    else if (!dockApps.isEmpty()) {
                        area = DOCK;
                        dockIndex = Math.min(col, dockApps.size() - 1);
                    } else area = TOP;
                } else if (down && !lastRow) {
                    gridIndex = Math.min(gridIndex + columns, n - 1);
                } else if (down) {
                    int to = nextSection(gridSec, 1, false);
                    if (to < 0) return;
                    enterSection(to, col, true);
                } else if (back) {
                    area = dockApps.isEmpty() ? TOP : DOCK;
                } else if (select && n > 0) {
                    launch(s.apps.get(gridIndex));
                    return;
                }
                if (area == GRID) keepInRow(sec());
                break;
            }
        }
        // An edge press, or Back with nowhere to go, moves nothing and says nothing.
        if (focusSpot() != wasAt) {
            if (back) sounds.play(Sounds.BACK);
            else sounds.tick(Sounds.FOCUS, navRepeat);
        }
        applyFocus(true);
    }

    /** Where focus is on Home: when this changes, a key press moved it (an edge press doesn't). */
    private int focusSpot() {
        return java.util.Objects.hash(area, topIndex, dockIndex, gridSec, gridIndex);
    }

    private View targetFor(int a) {
        switch (a) {
            case TOP: {
                // A hidden button (guest mode) can't hold focus: the first one showing does.
                if (pills[topIndex].getVisibility() != View.VISIBLE) topIndex = Math.max(0, nextPill(-1, 1));
                return pills[topIndex];
            }
            case DOCK: return cardAt(dockList, dockIndex);
            case GRID: {
                Section s = sec();
                return s == null || s.apps.isEmpty() ? null : cardAt(s.list, gridIndex);
            }
            default: return null;
        }
    }

    /** In a menu the highlight stays on what opened it (the pill or the tile), as a "you came from here". */
    private View currentTarget() {
        return targetFor(area == MENU ? menuReturnArea : area);
    }

    private static View cardAt(RecyclerView rv, int position) {
        RecyclerView.LayoutManager lm = rv.getLayoutManager();
        View item = lm == null ? null : lm.findViewByPosition(position);
        return item == null ? null : item.findViewById(R.id.card);
    }

    private float scaleFor(int a) {
        if (a == MENU) a = menuReturnArea;
        return a == TOP ? tk.scalePill : moving ? tk.scaleMoving : tk.scaleTile;
    }

    /** A view's rectangle on screen, as it will be once the content finishes scrolling. */
    private Rect rectOf(View v, boolean inScroller) {
        Rect r = new Rect(0, 0, v.getWidth(), v.getHeight());
        root.offsetDescendantRectToMyCoords(v, r);
        if (inScroller) r.offset(0, scroller.getScrollY() - scrollTarget);
        return r;
    }

    private void applyFocus(boolean animate) {
        View target = currentTarget();
        int base = area == MENU ? menuReturnArea : area;
        int wantScroll = base == GRID ? gridScrollFor(target) : 0;
        if (wantScroll != scrollTarget || !animate) scrollTo(wantScroll, animate);
        // A sideways row scrolls by whole tiles; the highlight goes where the tile will end up.
        int dx = base == GRID ? scrollRow(sec(), animate) : 0;
        paintPills();

        float s = scaleFor(area);
        boolean labelsOnFocus = labelMode == TileAdapter.LABELS_FOCUS;
        if (target != focused) {
            if (focused != null) focused.animate().scaleX(1f).scaleY(1f).setDuration(tk.focus)
                    .setInterpolator(Tokens.EASE_OUT).start();
            if (focusedLabel != null && labelsOnFocus) focusedLabel.animate().alpha(0f).setDuration(tk.fast).start();
            focused = target;
            focusedLabel = null;
            if (target != null) {
                target.animate().scaleX(s).scaleY(s).setDuration(tk.focus).setInterpolator(Tokens.EASE_OUT).start();
                if ((base == DOCK || base == GRID) && labelsOnFocus) {
                    focusedLabel = ((View) target.getParent()).findViewById(R.id.label);
                    focusedLabel.animate().alpha(1f).setDuration(tk.focus).start();
                }
            }
        }

        if (target == null) {
            highlight.hide();
            if (base == DOCK || base == GRID) focusPending = true;
        } else {
            Rect r = rectOf(target, true);
            r.offset(-dx, 0);
            RectF rf = new RectF(r);
            rf.inset(-r.width() * (s - 1f) / 2f, -r.height() * (s - 1f) / 2f);
            float radius = base == TOP ? rf.height() / 2f : tileRadius() * s;
            highlight.setColor(focusColor(base));
            highlight.moveTo(rf, radius, animate);
        }

        if (base == DOCK && !dockApps.isEmpty()) showDockTitle(dockApps.get(dockIndex), animate);
        float titleAlpha = base == DOCK && !dockApps.isEmpty() ? 1f : 0f;
        dockTitle.animate().alpha(titleAlpha).setDuration(tk.medium).start();
        glow.animate().alpha(glowOn ? titleAlpha : 0f).setDuration(tk.slow).start();
        // Invisible (not just transparent) when unused, so it costs nothing to draw.
        boolean scrim = base == GRID;
        if (scrim) gridScrim.setVisibility(View.VISIBLE);
        if (scrim != (gridScrim.getAlpha() > 0.5f) || scrim && gridScrim.getAlpha() < 1f) {
            gridScrim.animate().alpha(scrim ? 1f : 0f).setDuration(tk.slow).setInterpolator(Tokens.EASE_IN_OUT)
                    .withEndAction(() -> {
                        if (gridScrim.getAlpha() == 0f) gridScrim.setVisibility(View.INVISIBLE);
                    }).start();
        }
    }

    /**
     * Going into the rows lifts the dock to the top of the screen. After that the screen scrolls just
     * enough to show the focused row with its name and the row's labels, inside the safe area.
     */
    /** The focus ring: the accent on the top bar; on tiles, Look › Tiles › Advanced › Focus colour. */
    private int focusColor(int base) {
        if (base != DOCK && base != GRID) return theme.accent;
        int mode = pref("focus_color", 0, 2);
        AppRepo.App app = focusedApp();
        if (mode == 1 && app != null) return app.color;
        if (mode == 2) return theme.text;
        return theme.accent;
    }

    private int gridScrollFor(View card) {
        int base = dockBand.getTop() - tk.safeV;
        Section s = sec();
        if (card == null || s == null) return Math.max(base, scrollTarget);
        View item = (View) card.getParent();
        Rect r = new Rect(0, 0, item.getWidth(), item.getHeight());
        ((ViewGroup) content).offsetDescendantRectToMyCoords(item, r);
        int top = r.top;
        boolean firstRow = s.single() || gridIndex < columns;
        if (firstRow && s.header != null) top = gridSection.getTop() + s.header.getTop();
        int y = Math.max(base, scrollTarget), h = root.getHeight();
        if (r.bottom > y + h - tk.safeV) y = r.bottom - (h - tk.safeV);
        if (top - tk.space2 < y + tk.safeV) y = top - tk.space2 - tk.safeV;
        return Math.max(base, y);
    }

    /** Scrolls a sideways row to its first tile; returns how far it still has to go. */
    private int scrollRow(Section s, boolean animate) {
        if (s == null || !s.single() || s.list.getChildCount() == 0) return 0;
        LinearLayoutManager lm = (LinearLayoutManager) s.list.getLayoutManager();
        // Measured from where the new first tile really is (tiles just off screen are laid out too).
        View firstView = lm.findViewByPosition(s.first);
        int dx;
        if (firstView != null) dx = firstView.getLeft() - s.list.getPaddingLeft();
        else {
            View c = lm.getChildAt(0);
            dx = s.first * colW - (lm.getPosition(c) * colW - (c.getLeft() - s.list.getPaddingLeft()));
        }
        if (dx == 0) return 0;
        if (!animate) {
            s.list.scrollBy(dx, 0);
            return 0;
        }
        s.list.smoothScrollBy(dx, 0, Tokens.EASE_OUT, tk.slow);
        return dx;
    }

    private void scrollTo(int y, boolean animate) {
        scrollTarget = y;
        if (scrollAnim != null) scrollAnim.cancel();
        if (!animate) {
            scroller.setScrollY(y);
            return;
        }
        scrollAnim = ObjectAnimator.ofInt(scroller, "scrollY", scroller.getScrollY(), y);
        scrollAnim.setDuration(tk.slow);
        scrollAnim.setInterpolator(Tokens.EASE_OUT);
        scrollAnim.start();
    }

    private void showDockTitle(AppRepo.App app, boolean animate) {
        if (app.pkg.equals(titlePkg)) return;
        titlePkg = app.pkg;
        dockTitle.setText(app.label);
        if (glowAnim != null) glowAnim.cancel();
        if (!animate) {
            setGlow(app.color);
            return;
        }
        dockTitle.setTranslationX(tk.space2);
        dockTitle.animate().translationX(0).setDuration(tk.medium).setInterpolator(Tokens.EASE_OUT).start();
        glowAnim = ValueAnimator.ofObject(new ArgbEvaluator(), glowColor, app.color);
        glowAnim.setDuration(tk.slow);
        glowAnim.addUpdateListener(va -> setGlow((int) va.getAnimatedValue()));
        glowAnim.start();
    }

    private void setGlow(int color) {
        glowColor = color;
        int rgb = color & 0x00FFFFFF;
        glowDrawable.setColors(new int[] {rgb | 0x5C000000, rgb | 0x1A000000, rgb});
    }

    // ---- moving tiles -----------------------------------------------------------------------

    /** Saved dock order, unless config.xml changed since it was saved (a rebuild wins). */
    private List<String> dockOrder() {
        List<String> config = Arrays.asList(getResources().getStringArray(R.array.dock_apps));
        String saved = prefs.getString("dock_order", "");
        if (saved.isEmpty() || !TextUtils.join(",", config).equals(prefs.getString("dock_config", ""))) return config;
        return Arrays.asList(saved.split(","));
    }

    private void onLongSelect() {
        if ((area == DOCK || area == GRID) && focusedApp() != null) openQuickActions();
    }

    private void startMove() {
        if (area == GRID && sec() != null && sec().cat == null) {
            toast(tr(Section.INPUTS.equals(sec().special) ? R.string.toast_inputs_fixed_order
                    : R.string.toast_all_apps_fixed_order), false);
            return;
        }
        if ((area == DOCK || area == GRID) && focusedApp() != null) {
            moving = true;
            highlight.setMoving(true);
            showMoveHint();
            refocus();
        }
    }

    private void moveNav(boolean up, boolean down, boolean left, boolean right, boolean done) {
        if (done) {
            endMove();
            return;
        }
        if (area == DOCK) {
            if (left && dockIndex > 0) {
                Collections.swap(dockApps, dockIndex, dockIndex - 1);
                dockIndex--;
            } else if (right && dockIndex < dockApps.size() - 1) {
                Collections.swap(dockApps, dockIndex, dockIndex + 1);
                dockIndex++;
            } else if (down && dockApps.size() > 1) {
                // Back to its own category (or Apps), right below where it was if that row is in your order.
                int col = dockIndex;
                AppRepo.App a = dockApps.remove(dockIndex);
                dockIndex = Math.min(dockIndex, dockApps.size() - 1);
                rebuildGrid();
                focusGrid(categories.of(a.pkg).id, a.pkg);
                Section s = sec();
                if (s != null && s.cat != null && Categories.SORT_CUSTOM.equals(s.cat.sort)) {
                    s.apps.remove(a);
                    int at = s.single() ? s.first + col : col;
                    s.apps.add(Math.min(at, s.apps.size()), a);
                    saveSectionOrder(s, false);
                    gridIndex = s.apps.indexOf(a);
                    keepInRow(s);
                    s.adapter.submit(s.apps);
                }
                area = GRID;
            } else return;
        } else if (area == GRID) {
            Section s = sec();
            int n = s.apps.size(), col = visualCol();
            boolean single = s.single();
            boolean topRow = single || gridIndex < columns;
            boolean lastRow = single || (gridIndex / columns + 1) * columns >= n;
            int to = -1;
            if (left && (single ? gridIndex > 0 : col > 0)) to = gridIndex - 1;
            else if (right && gridIndex + 1 < n && (single || col < columns - 1)) to = gridIndex + 1;
            else if (up && !topRow) to = gridIndex - columns;
            else if (down && !lastRow) to = Math.min(gridIndex + columns, n - 1);
            if (to >= 0) {
                // Rearranging the row itself: swap with the neighbour in that direction.
                Collections.swap(s.apps, gridIndex, to);
                gridIndex = to;
                keepInRow(s);
                saveSectionOrder(s, true);
                s.adapter.submit(s.apps);
            } else if (up || down) {
                int target = nextSection(gridSec, up ? -1 : 1, true);
                if (target >= 0) moveToSection(s, sections.get(target), col, down);
                else if (up) {
                    // Up from the top row of the first row: into the dock.
                    AppRepo.App a = s.apps.get(gridIndex);
                    int at = Math.min(col, dockApps.size());
                    // A full dock swaps: the app in that slot goes back to its row.
                    if (dockApps.size() >= columns) dockApps.set(at, a);
                    else dockApps.add(at, a);
                    rebuildGrid();
                    area = DOCK;
                    dockIndex = at;
                    focusGrid(currentGridSec(), null);
                } else return;
            } else return;
        } else return;
        dockAdapter.submit(dockApps);
        showMoveHint();
        clearFocused();
        afterLayout(root, () -> applyFocus(true));
    }

    /**
     * Carries the app into the next row: it joins that category (or goes back to Apps) and lands at
     * the same column, on the near edge of the row. The row switches to your own order.
     */
    private void moveToSection(Section from, Section to, int col, boolean down) {
        AppRepo.App a = from.apps.get(gridIndex);
        int at;
        if (to.single()) at = to.first + col;
        else at = down ? col : (to.apps.size() - 1) / columns * columns + col
                + (to.apps.size() % columns == 0 && !to.apps.isEmpty() ? columns : 0);
        at = Math.max(0, Math.min(at, to.apps.size()));
        List<AppRepo.App> order = new ArrayList<>(to.apps);
        order.add(at, a);
        List<String> pkgs = new ArrayList<>();
        for (AppRepo.App x : order) pkgs.add(x.pkg);
        for (String old : to.cat.apps) if (!pkgs.contains(old)) pkgs.add(old);
        categories.moveApp(a.pkg, to.cat, -1);
        to.cat.apps.clear();
        to.cat.apps.addAll(pkgs);
        if (to.cat.isApps()) {
            // "Apps" keeps the old order list for everything; the app is simply no longer claimed.
            for (Categories.Cat c : categories.list) if (!c.isApps()) c.apps.remove(a.pkg);
        }
        to.cat.sort = Categories.SORT_CUSTOM;
        categories.save(prefs);
        rebuildGrid();
        focusGrid(to.id(), a.pkg);
    }

    private void endMove() {
        moving = false;
        highlight.setMoving(false);
        moveHint.animate().alpha(0f).setDuration(tk.fast).withEndAction(() -> moveHint.setVisibility(View.GONE)).start();
        List<String> pkgs = new ArrayList<>();
        for (AppRepo.App a : dockApps) pkgs.add(a.pkg);
        prefs.edit()
                .putString("dock_order", TextUtils.join(",", pkgs))
                .putString("dock_config", TextUtils.join(",", getResources().getStringArray(R.array.dock_apps)))
                .apply();
        refocus();
    }

    private void showMoveHint() {
        moveHint.removeAllViews();
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) moveHint.getLayoutParams();
        if (area == DOCK) {
            moveHint.addView(dockApps.size() > 1
                    ? Ui.hints(this, theme, tk, "◀ ▶", tr(R.string.hint_move), "▼", tr(R.string.move_remove_from_dock),
                            tr(R.string.key_ok), tr(R.string.hint_done))
                    : Ui.hints(this, theme, tk, "◀ ▶", tr(R.string.hint_move), tr(R.string.key_ok), tr(R.string.hint_done)));
            // Under the top bar, clear of the pills and the clock.
            lp.gravity = Gravity.TOP | Gravity.END;
            lp.topMargin = tk.safeV + tk.pillHeight + tk.space5;
            lp.rightMargin = tk.safeH;
            lp.bottomMargin = 0;
        } else {
            // Arrows move; at a row's edge, Up and Down say where the app goes next.
            Section s = sec();
            int n = s.apps.size();
            boolean topRow = s.single() || gridIndex < columns;
            boolean lastRow = s.single() || (gridIndex / columns + 1) * columns >= n;
            int prev = nextSection(gridSec, -1, true), next = nextSection(gridSec, 1, true);
            List<String> h = new ArrayList<>(Arrays.asList("◀ ▶ ▲ ▼", tr(R.string.hint_move)));
            if (topRow) {
                h.add("▲");
                h.add(prev >= 0 ? tr(R.string.move_to_row, sectionName(sections.get(prev)))
                        : tr(dockApps.size() >= columns ? R.string.move_swap_into_dock : R.string.move_add_to_dock));
            }
            if (lastRow && next >= 0) {
                h.add("▼");
                h.add(tr(R.string.move_to_row, sectionName(sections.get(next))));
            }
            h.add(tr(R.string.key_ok));
            h.add(tr(R.string.hint_done));
            moveHint.addView(Ui.hints(this, theme, tk, h.toArray(new String[0])));
            lp.topMargin = 0;
            lp.rightMargin = 0;
            lp.bottomMargin = tk.safeV;
            lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        }
        moveHint.setLayoutParams(lp);
        if (moveHint.getVisibility() != View.VISIBLE) {
            moveHint.setAlpha(0f);
            moveHint.setVisibility(View.VISIBLE);
            moveHint.animate().alpha(1f).setDuration(tk.medium).start();
        }
    }

    /** Re-run the focus animation on the same tile (scale changes between normal and moving). */
    private void refocus() {
        if (focused != null) focused.animate().scaleX(1f).scaleY(1f).setDuration(tk.fast).start();
        clearFocused();
        applyFocus(true);
    }

    // ---- the menu ---------------------------------------------------------------------------

    /** {@code crumb} says where this menu sits ("Customize › Look"), {@code title} what it is. */
    private void startMenu(String crumb, String title) {
        pendingRows.clear();
        entries.clear();
        menuCrumb = crumb;
        menuTitle = title;
    }

    private MenuView.Row add(MenuView.Row row, Runnable action) {
        return add(row, action, null);
    }

    private MenuView.Row add(MenuView.Row row, Runnable action, Runnable preview) {
        pendingRows.add(row);
        entries.add(new Entry(action, preview));
        return row;
    }

    /** A slider or stepper row: Left / Right call {@code adjust} with -1 / +1, then the row redraws. */
    private MenuView.Row addAdjust(MenuView.Row row, IntConsumer adjust) {
        add(row, null);
        entries.get(entries.size() - 1).adjust = adjust;
        return row;
    }

    /**
     * Shows the rows added since startMenu() as the sheet on the right, under the top bar. Back runs
     * {@code cancel}, then {@code back} (the parent menu), or closes.
     */
    private void showSheet(int focus, Runnable back, Runnable cancel) {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) menu.getLayoutParams();
        if (custMode == CUST_FULL) {
            // Customization: beside the section list, full height.
            lp.width = tk.sheetWidth;
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.topMargin = tk.safeV;
            lp.leftMargin = tk.safeH + railWidth() + tk.space5;
            lp.rightMargin = 0;
            lp.bottomMargin = 0;
            menu.setMaxHeight(root.getHeight() - 2 * tk.safeV);
            // No shadow over the backdrop: it barely shows there, and this GPU redraws it every frame.
            menu.setElevation(0);
            present(lp, focus, back, cancel, menuHints(false));
            return;
        }
        menu.setElevation(tk.elevationSheet);
        lp.width = tk.sheetWidth;
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.topMargin = tk.safeV + tk.pillHeight + tk.space3;
        lp.rightMargin = tk.safeH;
        lp.leftMargin = 0;
        lp.bottomMargin = 0;
        menu.setMaxHeight(root.getHeight() - lp.topMargin - tk.safeV);
        present(lp, focus, back, cancel, menuHints(back == null));
    }

    /** "OK Select  BACK Back" (or Close, at the top level) under a menu. */
    private String[] menuHints(boolean closes) {
        return new String[] {tr(R.string.key_ok), tr(R.string.hint_select), tr(R.string.key_back),
                tr(closes ? R.string.hint_close : R.string.hint_back)};
    }

    /** The quick actions: a small card beside the tile, above it when the tile is low on screen. */
    private void showPopover(Rect tile, int focus, Runnable back) {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) menu.getLayoutParams();
        menu.setElevation(tk.elevationSheet);
        lp.width = tk.popoverWidth;
        int w = root.getWidth(), h = root.getHeight();
        boolean right = tile.right + tk.space4 + lp.width <= w - tk.safeH;
        lp.leftMargin = right ? tile.right + tk.space4 : Math.max(tk.safeH, tile.left - tk.space4 - lp.width);
        lp.rightMargin = 0;
        boolean low = tile.centerY() > h / 2;
        lp.gravity = Gravity.START | (low ? Gravity.BOTTOM : Gravity.TOP);
        lp.topMargin = low ? 0 : Math.max(tk.safeV, tile.top);
        lp.bottomMargin = low ? Math.max(tk.safeV, h - tile.bottom) : 0;
        menu.setMaxHeight(h - 2 * tk.safeV);
        present(lp, focus, back, null, menuHints(back == null));
    }

    private void present(FrameLayout.LayoutParams lp, int focus, Runnable back, Runnable cancel, String[] hints) {
        if (area != MENU) menuReturnArea = area;
        menuBack = back;
        menuCancel = cancel;
        menu.setLayoutParams(lp);
        menu.setHeader(menuCrumb, menuTitle);
        menu.setHints(hints);
        boolean entering = menu.getVisibility() != View.VISIBLE;
        if (entering) {
            menu.animate().cancel();
            menu.setVisibility(View.VISIBLE);
            sounds.play(Sounds.OPEN);
        }
        // In Customization, while the section list has focus, this menu is only a preview: it swaps
        // without the level-change fade (a fade of the whole panel on every step is heavy on this GPU).
        boolean preview = custMode == CUST_FULL && railActive;
        menu.setRows(new ArrayList<>(pendingRows), focus, !preview);
        menu.setActive(!preview);
        area = MENU;
        if (entering) {
            menu.setAlpha(0f);
            menu.setTranslationX(tk.space5);
            menu.animate().alpha(1f).translationX(0).setDuration(tk.medium).setInterpolator(Tokens.EASE_OUT).start();
        }
        applyFocus(true);
    }

    /** Leaves menu mode (keys go back to Home); the menu view stays until {@link #dismissMenu}. */
    private void leaveMenu() {
        area = menuReturnArea;
        if (area == TOP && menuAnchorPill >= 0) topIndex = menuAnchorPill;
    }

    private void dismissMenu() {
        // Leaving Customize locks again (the PIN's grace is for an app you just unlocked).
        Lock.relock();
        hideFull(true);
        custMode = CUST_NONE;
        menu.animate().cancel();
        menu.animate().alpha(0f).translationX(tk.space4).setDuration(tk.fast).setInterpolator(Tokens.EASE_IN_OUT)
                .withEndAction(() -> {
                    if (area != MENU) menu.setVisibility(View.GONE);
                }).start();
        applyFocus(true);
    }

    /** A pick-one list with a tick on the current option; {@code preview} runs as you move. */
    private void openChoice(String crumb, String title, String[] options, int current, IntConsumer pick,
                            IntConsumer preview, Runnable back) {
        startMenu(crumb, title);
        for (int i = 0; i < options.length; i++) {
            int n = i;
            add(MenuView.Row.choice(options[i], i == current), () -> pick.accept(n),
                    preview == null ? null : () -> preview.accept(n));
        }
        showSheet(Math.max(0, current), back, preview == null ? null : () -> preview.accept(current));
    }

    // ---- top bar ----------------------------------------------------------------------------

    private void pillAction(int index) {
        menuAnchorPill = index;
        if (index == PILL_SEARCH) openSearch(false);
        else if (index == PILL_INPUTS) openInputsMenu();
        else if (index == PILL_CUSTOMIZE) requirePin(customizeLocked(), tr(R.string.customize), () -> openCustomize(0));
        else if (index == PILL_NOTIF) openNotifications();
        else requirePin(customizeLocked(), tr(index == PILL_WIFI ? R.string.pin_title_wifi_settings : R.string.settings), () -> startSafely(
                new Intent(index == PILL_WIFI ? Settings.ACTION_WIFI_SETTINGS : Settings.ACTION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)));
    }

    /** The notifications the bell counts: OK opens one, the last row clears them all. */
    private void openNotifications() {
        startMenu(null, tr(R.string.notifications));
        for (Notifs.Item n : Notifs.items()) {
            add(MenuView.Row.action(0, n.title).value(n.app), () -> {
                if (!Notifs.open(n)) toast(tr(R.string.toast_notification_cant_open), false);
            });
        }
        if (pendingRows.isEmpty()) add(MenuView.Row.note(R.drawable.ic_info, tr(R.string.notifications_none)), null);
        else add(MenuView.Row.action(R.drawable.ic_close, tr(R.string.notifications_clear_all)), () -> {
            Notifs.clearAll();
            toast(tr(R.string.toast_notifications_cleared), true);
        });
        showSheet(0, null, null);
    }

    private void openInputsMenu() {
        startMenu(null, tr(R.string.inputs));
        TvInputManager tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        List<TvInputInfo> inputs = new ArrayList<>();
        if (tim != null) {
            for (TvInputInfo info : tim.getTvInputList()) {
                if (info.getParentId() == null && !info.isHidden(this)) inputs.add(info);
            }
        }
        inputs.sort(Comparator.comparingInt(MainActivity::inputRank).thenComparing(this::inputLabel));
        boolean tuner = false;
        for (TvInputInfo info : inputs) {
            if (!info.isPassthroughInput()) {
                tuner = true;
                continue;
            }
            Uri uri = TvContract.buildChannelUriForPassthroughInput(info.getId());
            String label = inputLabel(info);
            add(MenuView.Row.action(R.drawable.ic_hdmi, label), () -> startSafely(new Intent(Intent.ACTION_VIEW, uri), label));
        }
        String live = tr(R.string.inputs_live_tv);
        if (tuner) add(MenuView.Row.action(R.drawable.ic_live, live),
                () -> startSafely(new Intent(Intent.ACTION_VIEW, TvContract.Channels.CONTENT_URI), live));
        if (pendingRows.isEmpty()) add(MenuView.Row.note(R.drawable.ic_info, tr(R.string.inputs_none)), null);
        showSheet(0, null, null);
    }

    private String inputLabel(TvInputInfo info) {
        CharSequence label = info.loadCustomLabel(this);
        if (TextUtils.isEmpty(label)) label = info.loadLabel(this);
        return String.valueOf(label);
    }

    private static int inputRank(TvInputInfo info) {
        switch (info.getType()) {
            case TvInputInfo.TYPE_HDMI: return 0;
            case TvInputInfo.TYPE_DISPLAY_PORT: return 1;
            case TvInputInfo.TYPE_COMPONENT: return 2;
            case TvInputInfo.TYPE_COMPOSITE: return 3;
            case TvInputInfo.TYPE_SVIDEO: return 4;
            case TvInputInfo.TYPE_SCART: return 5;
            case TvInputInfo.TYPE_VGA: return 6;
            case TvInputInfo.TYPE_DVI: return 7;
            case TvInputInfo.TYPE_TUNER: return 9;
            default: return 8;
        }
    }

    /**
     * The date's pattern for this language ("EEEE, MMMM d" in English). Looking it up loads the
     * locale's calendar data, a quarter of a second the first time, so it's done in the background
     * at start-up and the date follows a moment later (well before the first frame, in practice).
     */
    private static volatile String datePattern;
    private static volatile Locale datePatternLocale;
    private boolean datePatternPending;

    private String datePattern(Locale locale) {
        if (locale.equals(datePatternLocale)) return datePattern;
        if (datePatternPending) return null;
        datePatternPending = true;
        // Its own thread, so the apps (on the loader) don't wait behind it.
        new Thread(() -> {
            datePattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEEMMMMd");
            datePatternLocale = locale;
            ui.post(() -> {
                datePatternPending = false;
                if (!isDestroyed()) updateClock();
            });
        }, "date-pattern").start();
        return null;
    }

    private void updateClock() {
        Date now = new Date();
        Locale locale = Locale.getDefault();
        String fmt = prefs.getString("clock_fmt", "auto");
        boolean h24 = "auto".equals(fmt) ? android.text.format.DateFormat.is24HourFormat(this) : "24".equals(fmt);
        clock.setText(new SimpleDateFormat(h24 ? "H:mm" : "h:mm", locale).format(now));
        if (idleClock != null) idleClock.setText(clock.getText());
        String pattern = datePattern(locale);
        if (pattern != null) date.setText(new SimpleDateFormat(pattern, locale).format(now));
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        greeting.setText(hour < 5 ? R.string.greeting_night : hour < 12 ? R.string.greeting_morning
                : hour < 18 ? R.string.greeting_afternoon : R.string.greeting_evening);
    }

    // ---- quick actions: hold OK on a tile ---------------------------------------------------

    private Rect quickAnchor;

    private void openQuickActions() {
        AppRepo.App app = focusedApp();
        View card = currentTarget();
        if (card == null || app == null) return;
        Rect r = rectOf(card, true);
        float s = scaleFor(area);
        r.inset(-Math.round(r.width() * (s - 1f) / 2f), -Math.round(r.height() * (s - 1f) / 2f));
        quickAnchor = r;
        openQuickActions(app, 0);
    }

    // Rows of the quick actions, so a submenu's Back lands on the row that opened it.
    private static final int QA_CATEGORY = 2, QA_RENAME = 3, QA_TILE = 4, QA_MORE = 6;

    private void openQuickActions(AppRepo.App app, int focus) {
        menuAnchorPill = -1;
        startMenu(null, app.label);
        if (layoutLocked()) {
            // Guest mode, or Customize locked: Home can be used but not changed.
            add(MenuView.Row.action(R.drawable.ic_open, tr(R.string.qa_open)), () -> launch(app));
            add(MenuView.Row.note(R.drawable.ic_info, tr(Lock.guest(this) ? R.string.qa_guest_note
                    : R.string.qa_locked_note)), null);
            showPopover(quickAnchor, 0, null);
            return;
        }
        if (isInput(app)) {
            // An input: open it, give it your own name (HDMI 1 → Game console), or hide it.
            add(MenuView.Row.action(R.drawable.ic_open, tr(R.string.qa_open)), () -> launch(app));
            boolean renamed = !app.label.equals(app.original);
            add(renamed ? MenuView.Row.nav(R.drawable.ic_rename, tr(R.string.qa_name), "")
                            : MenuView.Row.action(R.drawable.ic_rename, tr(R.string.qa_rename)),
                    () -> {
                        if (renamed) openNameMenu(app);
                        else askAppName(app);
                    });
            add(MenuView.Row.action(R.drawable.ic_hide, tr(R.string.qa_hide)), () -> {
                setHidden(app.pkg, true);
                toast(tr(R.string.toast_app_hidden, app.label), true);
            });
            showPopover(quickAnchor, focus, null);
            return;
        }
        add(MenuView.Row.action(R.drawable.ic_open, tr(R.string.qa_open)), () -> launch(app));
        add(MenuView.Row.action(R.drawable.ic_move, tr(R.string.qa_move)), this::startMove);
        add(MenuView.Row.nav(R.drawable.ic_rows, tr(R.string.qa_category), catName(categories.of(app.pkg))),
                () -> openMoveToCategory(app));
        boolean renamed = !app.label.equals(app.original);
        add(renamed ? MenuView.Row.nav(R.drawable.ic_rename, tr(R.string.qa_name), "")
                        : MenuView.Row.action(R.drawable.ic_rename, tr(R.string.qa_rename)),
                () -> {
                    if (renamed) openNameMenu(app);
                    else askAppName(app);
                });
        add(MenuView.Row.nav(R.drawable.ic_apps, tr(R.string.qa_tile),
                        tr(app.iconTile || app.banner == null ? R.string.tile_icon : R.string.tile_banner)),
                () -> openTileMenu(app));
        add(MenuView.Row.action(R.drawable.ic_hide, tr(R.string.qa_hide)), () -> {
            setHidden(app.pkg, true);
            toast(tr(R.string.toast_app_hidden, app.label), true);
        });
        add(MenuView.Row.nav(R.drawable.ic_info, tr(R.string.qa_more), ""), () -> openMoreMenu(app));
        showPopover(quickAnchor, focus, null);
    }

    private Runnable backToQuick(AppRepo.App app, int row) {
        return () -> openQuickActions(app, row);
    }

    /** App info, close and uninstall: needed now and then, so one level down. */
    private void openMoreMenu(AppRepo.App app) {
        startMenu(app.label, tr(R.string.qa_more));
        add(MenuView.Row.action(R.drawable.ic_info, tr(R.string.qa_app_info)), () -> startSafely(new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.pkg, null))));
        add(MenuView.Row.action(R.drawable.ic_close, tr(R.string.qa_close_app)), () -> {
            // A launcher can't force-stop apps; this ends the app now that it is in the background.
            getSystemService(ActivityManager.class).killBackgroundProcesses(app.pkg);
            toast(tr(R.string.toast_app_closed, app.label), true);
        });
        if (isUserApp(app.pkg)) {
            // Android asks to confirm before anything is removed.
            add(MenuView.Row.action(R.drawable.ic_trash, tr(R.string.qa_uninstall)), () -> startSafely(new Intent(
                    Intent.ACTION_DELETE, Uri.fromParts("package", app.pkg, null))));
        }
        showPopover(quickAnchor, 0, backToQuick(app, QA_MORE));
    }

    private void askAppName(AppRepo.App app) {
        askText(app.original, tr(R.string.rename_title), tr(R.string.rename_app_hint), app.label,
                InputType.TYPE_TEXT_FLAG_CAP_WORDS, name -> {
            putJson("app_names", app.pkg, name.equals(app.original) ? null : name);
            refreshAppLooks();
            toast(tr(R.string.toast_renamed, name), true);
        }, null);
    }

    private void openNameMenu(AppRepo.App app) {
        startMenu(app.label, tr(R.string.qa_name));
        add(MenuView.Row.action(R.drawable.ic_rename, tr(R.string.qa_rename)), () -> askAppName(app));
        add(MenuView.Row.action(R.drawable.ic_reset, tr(R.string.qa_original_name)).value(app.original), () -> {
            putJson("app_names", app.pkg, null);
            refreshAppLooks();
            toast(tr(R.string.toast_name_back_to, app.original), true);
        });
        showPopover(quickAnchor, 0, backToQuick(app, QA_RENAME));
    }

    /** Banner (the app's 16:9 artwork) or icon tile, for this app only; "Default" follows Look › Tiles. */
    private void openTileMenu(AppRepo.App app) {
        String own = json("app_tiles").optString(app.pkg, "");
        boolean icons = "icon".equals(prefs.getString("tile_style", "banner"));
        startMenu(app.label, tr(R.string.qa_tile));
        String[] keys = {"", "banner", "icon"};
        String[] names = {tr(icons ? R.string.tile_default_icon : R.string.tile_default_banner),
                tr(R.string.tile_banner), tr(R.string.tile_icon)};
        int[] toasts = {R.string.toast_tile_default, R.string.toast_tile_banner, R.string.toast_tile_icon};
        int cur = Math.max(0, Arrays.asList(keys).indexOf(own));
        for (int i = 0; i < keys.length; i++) {
            String k = keys[i];
            int done = toasts[i];
            MenuView.Row row = add(MenuView.Row.choice(names[i], i == cur), () -> {
                putJson("app_tiles", app.pkg, k.isEmpty() ? null : k);
                refreshAppLooks();
                toast(tr(done, app.label), true);
            });
            if ("banner".equals(k) && app.banner == null) row.disabled(tr(R.string.tile_no_banner));
        }
        add(MenuView.Row.nav(R.drawable.ic_background, tr(R.string.tile_picture), ""), () -> openIconPicture(app));
        List<IconPacks.Pack> packs = IconPacks.installed(this);
        MenuView.Row fromPack = add(MenuView.Row.nav(R.drawable.ic_apps, tr(R.string.tile_from_pack), ""),
                () -> openIconPackFor(app, packs));
        if (packs.isEmpty()) fromPack.disabled(tr(R.string.tile_no_packs));
        if (json("app_icons").has(app.pkg)) {
            add(MenuView.Row.action(R.drawable.ic_reset, tr(R.string.tile_remove_icon)), () -> {
                putJson("app_icons", app.pkg, null);
                //noinspection ResultOfMethodCallIgnored
                new File(getFilesDir(), "icons/" + app.pkg + ".png").delete();
                reloadApps();
                toast(tr(R.string.toast_icon_removed, app.label), true);
            });
        }
        showPopover(quickAnchor, cur, backToQuick(app, QA_TILE));
    }

    // Custom icons: a picture from the TV or USB, or any icon from an installed icon pack.

    private void openIconPicture(AppRepo.App app) {
        if (checkSelfPermission(imagePermission()) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {imagePermission()}, REQ_PHOTOS);
            return;
        }
        startMenu(app.label, tr(R.string.picture_title));
        add(MenuView.Row.loading(tr(R.string.picture_looking)), null);
        showPopover(quickAnchor, 0, () -> openTileMenu(app));
        loader.execute(() -> {
            Uri collection = Media.images();
            List<Uri> uris = new ArrayList<>();
            List<String> names = new ArrayList<>();
            try (Cursor c = getContentResolver().query(collection, new String[] {MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME}, null, null, MediaStore.Images.Media.DISPLAY_NAME + " COLLATE NOCASE ASC")) {
                while (c != null && c.moveToNext() && uris.size() < MAX_PHOTOS) {
                    uris.add(ContentUris.withAppendedId(collection, c.getLong(0)));
                    names.add(c.getString(1));
                }
            } catch (RuntimeException ignored) {
            }
            ui.post(() -> {
                if (isDestroyed() || area != MENU) return;
                startMenu(app.label, tr(R.string.picture_title));
                for (int i = 0; i < uris.size(); i++) {
                    Uri uri = uris.get(i);
                    add(MenuView.Row.action(0, names.get(i) == null ? tr(R.string.picture_title) : names.get(i)),
                            () -> useIconPicture(app, uri));
                }
                if (uris.isEmpty()) add(MenuView.Row.note(R.drawable.ic_info, tr(R.string.picture_none_for_icon)), null);
                showPopover(quickAnchor, 0, () -> openTileMenu(app));
            });
        });
    }

    /** Copied (at most 512 px on its long side) so it stays when the USB drive is unplugged. */
    private void useIconPicture(AppRepo.App app, Uri uri) {
        loader.execute(() -> {
            boolean ok = false;
            try {
                Bitmap bm = ImageDecoder.decodeBitmap(ImageDecoder.createSource(getContentResolver(), uri), (d, info, s) -> {
                    int max = Math.max(info.getSize().getWidth(), info.getSize().getHeight());
                    if (max > 512) d.setTargetSize(info.getSize().getWidth() * 512 / max, info.getSize().getHeight() * 512 / max);
                    d.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                });
                File dir = new File(getFilesDir(), "icons");
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                try (FileOutputStream out = new FileOutputStream(new File(dir, app.pkg + ".png"))) {
                    ok = bm.compress(Bitmap.CompressFormat.PNG, 100, out);
                }
            } catch (IOException | RuntimeException ignored) {
            }
            boolean saved = ok;
            ui.post(() -> {
                if (!saved) {
                    toast(tr(R.string.toast_picture_unusable), false);
                    return;
                }
                putJson("app_icons", app.pkg, "file:");
                reloadApps();
                toast(tr(R.string.toast_new_icon, app.label), true);
            });
        });
    }

    private void openIconPackFor(AppRepo.App app, List<IconPacks.Pack> packs) {
        if (packs.size() == 1) {
            openPackIcons(app, packs.get(0), null);
            return;
        }
        startMenu(app.label, tr(R.string.icon_pack));
        for (IconPacks.Pack p : packs) add(MenuView.Row.nav(0, p.label, ""), () -> openPackIcons(app, p, null));
        showPopover(quickAnchor, 0, () -> openTileMenu(app));
    }

    /**
     * The pack's own match for this app first, then search by name (packs hold thousands of icons,
     * too many to scroll through with a remote). Each icon shows on the tile as you move to it.
     */
    private void openPackIcons(AppRepo.App app, IconPacks.Pack pack, String query) {
        startMenu(app.label, pack.label);
        add(MenuView.Row.loading(tr(R.string.icon_pack_reading)), null);
        showPopover(quickAnchor, 0, () -> openTileMenu(app));
        loader.execute(() -> {
            List<String> names = new ArrayList<>();
            String match = IconPacks.appfilter(this, pack.pkg).get(app.pkg + "/" + app.component.getClassName());
            if (match == null) match = IconPacks.appfilter(this, pack.pkg).get(app.pkg);
            if (query == null) {
                if (match != null) names.add(match);
            } else {
                String q = query.toLowerCase(Locale.ROOT).replace(' ', '_');
                for (String n : IconPacks.allIcons(this, pack.pkg)) {
                    if (n.toLowerCase(Locale.ROOT).contains(q)) names.add(n);
                    if (names.size() >= 200) break;
                }
            }
            String suggested = match;
            ui.post(() -> {
                if (isDestroyed() || area != MENU) return;
                startMenu(app.label, pack.label);
                for (String n : names) {
                    String label = n.replace('_', ' ');
                    add(MenuView.Row.action(0, n.equals(suggested) && query == null
                            ? tr(R.string.icon_pack_made_for_app, label) : label), () -> {
                        putJson("app_icons", app.pkg, "pack:" + pack.pkg + ":" + n);
                        reloadApps();
                        toast(tr(R.string.toast_new_icon, app.label), true);
                    }, () -> previewIcon(app, pack.pkg, n));
                }
                if (names.isEmpty()) add(MenuView.Row.note(R.drawable.ic_info, query == null
                        ? tr(R.string.icon_pack_no_match, app.label)
                        : tr(R.string.icon_pack_none_called, query)), null);
                add(MenuView.Row.action(R.drawable.ic_rename, tr(R.string.icon_pack_search)), () -> askText(pack.label,
                        tr(R.string.icon_pack_search_title), tr(R.string.icon_pack_search_hint), app.original, 0, IME_SEARCH,
                        q -> openPackIcons(app, pack, q.trim()), null));
                showPopover(quickAnchor, 0, () -> openTileMenu(app));
                // Back from here, or picking nothing, puts the app's own look back.
                menuCancel = this::reloadApps;
                if (!names.isEmpty()) previewIcon(app, pack.pkg, names.get(0));
            });
        });
    }

    private void previewIcon(AppRepo.App app, String pack, String name) {
        loader.execute(() -> {
            Drawable d = IconPacks.load(this, pack, name);
            ui.post(() -> {
                if (d == null || isDestroyed()) return;
                app.icon = d;
                app.customIcon = true;
                refreshAppLooks();
            });
        });
    }

    /** Which category an app is in: pick one, or make a new one for it. */
    private void openMoveToCategory(AppRepo.App app) {
        Categories.Cat current = categories.of(app.pkg);
        startMenu(app.label, tr(R.string.qa_category));
        int focus = 0;
        for (Categories.Cat c : categories.list) {
            if (c == current) focus = pendingRows.size();
            add(MenuView.Row.choice(c.isApps() ? tr(R.string.category_apps_none, catName(c)) : catName(c), c == current), () -> {
                if (c != current) {
                    categories.moveApp(app.pkg, c, -1);
                    categories.save(prefs);
                    rebuildLists();
                    toast(tr(R.string.toast_app_moved_to, app.label, catName(c)), true);
                }
            });
        }
        add(MenuView.Row.action(R.drawable.ic_add, tr(R.string.category_new)), () -> askText(crumb(tr(R.string.menu_categories)),
                tr(R.string.category_new_title), tr(R.string.category_new_hint), "", InputType.TYPE_TEXT_FLAG_CAP_WORDS, name -> {
                    Categories.Cat c = categories.create(name);
                    categories.moveApp(app.pkg, c, -1);
                    categories.save(prefs);
                    rebuildLists();
                    toast(tr(R.string.toast_app_moved_to, app.label, catName(c)), true);
                }, null));
        showPopover(quickAnchor, focus, backToQuick(app, QA_CATEGORY));
    }

    private boolean isUserApp(String pkg) {
        try {
            return (getPackageManager().getApplicationInfo(pkg, 0).flags & ApplicationInfo.FLAG_SYSTEM) == 0;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    // ---- Search -----------------------------------------------------------------------------

    /**
     * Opens search over Home. {@code pulled}: Up at the top of Home pulled it down. Only on Home:
     * other apps are never touched.
     */
    private void openSearch(boolean pulled) {
        if (moving || search.isOpen() || isDestroyed()) return;
        if (area == MENU) {
            leaveMenu();
            dismissMenu();
        }
        search.show(pulled);
    }

    /**
     * Holding Home while Home shows (the ≡ helper sees the hold; Home never waits for it, so a
     * normal press is as quick as ever). False if Home isn't in a state to open search.
     */
    boolean openSearchFromHold() {
        if (!isResumedNow || !hasWindowFocus() || moving || search.isOpen() || idleOverlay != null) return false;
        searchHeldAt = android.os.SystemClock.uptimeMillis();
        openSearch(false);
        return true;
    }

    private final SearchOverlay.Host searchHost = new SearchOverlay.Host() {
        @Override
        public List<AppRepo.App> searchableApps() {
            List<AppRepo.App> out = new ArrayList<>();
            Set<String> hidden = hiddenApps();
            for (AppRepo.App a : allApps) if (SearchPrefs.appVisible(a.pkg, hidden)) out.add(a);
            return out;
        }

        @Override
        public Set<String> lockedPackages() {
            Set<String> out = new HashSet<>();
            boolean active = Lock.active(MainActivity.this);
            if (!active) return out;
            Set<String> lockedApps = Lock.lockedApps(MainActivity.this), lockedCats = Lock.lockedCats(MainActivity.this);
            for (AppRepo.App a : allApps) {
                String cat = isInput(a) ? null : categories.of(a.pkg).id;
                if (SearchPrefs.appLocked(a.pkg, cat, lockedApps, lockedCats, true)) out.add(a.pkg);
            }
            return out;
        }

        @Override
        public void openApp(AppRepo.App app) {
            // The PIN first (for a locked app): cancelling it leaves search as it was.
            requirePin(Lock.appLocked(MainActivity.this, app.pkg, isInput(app) ? null : categories.of(app.pkg).id),
                    tr(R.string.pin_title_open_app, app.label), () -> {
                        search.hideNow();
                        launchNow(app);
                    });
        }

        @Override
        public boolean settingsAllowed() {
            return SearchPrefs.settingsVisible(Lock.guest(MainActivity.this));
        }

        @Override
        public boolean settingAvailable(SettingsIndex.Entry e) {
            if ("projector".equals(e.menu)) {
                for (int i = 0; i < PROJECTOR.length; i++) {
                    if (PROJECTOR_LABELS[i] == e.label) return Device.has(MainActivity.this, PROJECTOR[i][0], PROJECTOR[i][1]);
                }
            }
            if (e.label == R.string.set_look_bar_inputs_button || e.label == R.string.set_home_categories_inputs_row_hdmi) {
                return hasInputs;
            }
            return true;
        }

        @Override
        public void openSetting(SettingsIndex.Entry e) {
            openSettingFromSearch(e);
        }

        @Override
        public void openSearchSettings() {
            search.hideNow();
            requirePin(customizeLocked(), tr(R.string.customize), () -> {
                fullBack = MainActivity.this::backToSearch;
                showFullViews();
                rail.setFocus(SEC_RESET, false);
                railActive = false;
                rail.setActive(false);
                MainActivity.this.openSearchSettings(0);
                searchReturnMenu = menuCrumb + "|" + menuTitle;
            });
        }

        @Override
        public Set<String> openablePackages() {
            Set<String> out = new HashSet<>();
            for (AppRepo.App a : searchableApps()) out.add(a.pkg);
            return out;
        }

        @Override
        public Map<String, Integer> uses() {
            Map<String, Integer> out = new java.util.HashMap<>();
            for (AppRepo.App a : allApps) out.put(a.pkg, usage.getInt(a.pkg, 0));
            return out;
        }

        @Override
        public void openTitle(AppLinks.App app, Titles.Title title) {
            AppRepo.App installed = null;
            for (AppRepo.App a : allApps) if (a.pkg.equals(app.pkg)) installed = a;
            if (installed == null) return;
            AppRepo.App target = installed;
            requirePin(Lock.appLocked(MainActivity.this, target.pkg, categories.of(target.pkg).id),
                    tr(R.string.pin_title_open_app, target.label), () -> findTitleLink(app, target, title));
        }

        @Override
        public void withMic(Runnable then) {
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                then.run();
                return;
            }
            afterMic = then;
            // Android's question pauses Home; search stays open under it (see onPause).
            askingMic = true;
            requestPermissions(new String[] {android.Manifest.permission.RECORD_AUDIO}, REQ_MIC);
        }

        @Override
        public void sound(int id, boolean repeat) {
            if (repeat) sounds.tick(id, true);
            else sounds.play(id);
        }

        @Override
        public void toast(String message) {
            MainActivity.this.toast(message, false);
        }

        @Override
        public void closed() {
            applyFocus(true);
        }
    };

    /**
     * For apps that open a title's own page (Netflix, Apple TV, Max, Prime Video): the app's ID for
     * it, from Wikidata (a second or so, cached for a month). Search stays up meanwhile.
     */
    private void findTitleLink(AppLinks.App app, AppRepo.App target, Titles.Title title) {
        if (app.showLink == null && app.movieLink == null) {
            openTitleNow(app, target, title, null);
            return;
        }
        toast(tr(R.string.toast_opening_title, title.name, app.name), false);
        loader.execute(() -> {
            String link = null;
            try {
                link = AppLinks.titleLink(app, title.tv, Titles.appIds(this, title));
            } catch (IOException ignored) {
                // No ID (offline, or Wikidata doesn't have it): the app's search, or the app, instead.
            }
            String found = link;
            ui.post(() -> {
                if (!isDestroyed()) openTitleNow(app, target, title, found);
            });
        });
    }

    /**
     * Sends a title to an app: to its own page when there's a {@code link}, else into the app's own
     * search when it takes one, else the app opens and a note says what to look for.
     */
    private void openTitleNow(AppLinks.App app, AppRepo.App target, Titles.Title title, String link) {
        search.hideNow();
        usage.edit().putInt(target.pkg, usage.getInt(target.pkg, 0) + 1)
                .putLong("t:" + target.pkg, System.currentTimeMillis()).apply();
        if (link != null) {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(app.pkg)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    // Netflix's TV app reads this to treat the link as coming from a launcher.
                    .putExtra("source", "30");
            // An app already running in the background (Netflix on this projector) brings back its
            // last screen and ignores the link. Closing it first (as "Close app" does; never a force
            // stop) makes it start fresh, on the title.
            ActivityManager am = getSystemService(ActivityManager.class);
            if (am != null) am.killBackgroundProcesses(app.pkg);
            ui.postDelayed(() -> {
                try {
                    startActivity(i);
                    sounds.play(Sounds.LAUNCH);
                } catch (RuntimeException e) {
                    openTitleFallback(app, target, title.name);
                }
            }, 400);
            return;
        }
        openTitleFallback(app, target, title.name);
    }

    private void openTitleFallback(AppLinks.App app, AppRepo.App target, String title) {
        Intent i = AppLinks.searchIntent(app, title);
        if (i != null) {
            try {
                startActivity(i);
                sounds.play(Sounds.LAUNCH);
                return;
            } catch (RuntimeException ignored) {
                // Not taken after all: fall back to opening the app.
            }
        }
        launchNow(target);
        // Home's own toasts can't show over another app; Android's can.
        android.widget.Toast.makeText(this, tr(R.string.toast_search_title_in_app, title, app.name),
                android.widget.Toast.LENGTH_LONG).show();
    }

    // ---- Customize --------------------------------------------------------------------------

    /**
     * Quick Customize, the sheet on Home: the three things people change most, and the way into
     * everything else. The same settings as Customization, so both stay in step.
     */
    private void openCustomize(int focus) {
        hideFull(false);
        custMode = CUST_QUICK;
        // In guest mode the Customize button is hidden; the menu then belongs to no button.
        menuAnchorPill = pills[PILL_CUSTOMIZE].getVisibility() == View.VISIBLE ? PILL_CUSTOMIZE : -1;
        startMenu(null, tr(R.string.customize));
        add(MenuView.Row.nav(R.drawable.ic_background, tr(R.string.menu_background), backgroundName()), () -> openBackgroundMenu());
        add(MenuView.Row.nav(R.drawable.ic_look, tr(R.string.set_look_appearance), appearanceName()), () -> openAppearanceMenu(0));
        add(MenuView.Row.nav(R.drawable.ic_dock, tr(R.string.cust_dock_arrange), ""), () -> openDockArrangeMenu(0));
        add(MenuView.Row.nav(R.drawable.ic_palette, tr(R.string.cust_all), ""), () -> openFull(lastSection, () -> openCustomize(3)));
        showSheet(focus, null, null);
    }

    /** Quick Customize › Dock & arrange apps. */
    private void openDockArrangeMenu(int focus) {
        startMenu(tr(R.string.customize), tr(R.string.cust_dock_arrange));
        MenuView.Row rearrange = add(MenuView.Row.action(R.drawable.ic_dock, tr(R.string.cust_rearrange_dock)), this::rearrangeDock);
        if (dockApps.isEmpty()) rearrange.disabled(tr(R.string.dock_empty));
        MenuView.Row arrange = add(MenuView.Row.action(R.drawable.ic_move, tr(R.string.set_apps_arrange_apps)), this::arrangeApps);
        if (!hasGrid()) arrange.disabled(tr(R.string.cust_nothing_to_arrange));
        add(MenuView.Row.nav(R.drawable.ic_hide, tr(R.string.set_apps_show_or_hide_apps), trn(R.plurals.apps_hidden, hiddenCount())),
                () -> openHideMenu(0));
        add(MenuView.Row.action(R.drawable.ic_reset, tr(R.string.set_home_dock_reset_dock_to_default)), this::resetDock);
        showSheet(focus, () -> openCustomize(2), null);
    }

    private void rearrangeDock() {
        area = DOCK;
        dockIndex = Math.min(dockIndex, dockApps.size() - 1);
        startMove();
    }

    private void arrangeApps() {
        area = GRID;
        enterSection(nextSection(-1, 1, false), 0, true);
        startMove();
    }

    private void resetDock() {
        prefs.edit().remove("dock_order").remove("dock_config").apply();
        rebuildLists();
        toast(tr(R.string.toast_dock_reset), true);
    }

    /** Back from a section's own menu: the section list in Customization, or the quick sheet. */
    private Runnable backTo(int section) {
        return () -> {
            if (custMode == CUST_FULL) showFullRoot(section);
            else openCustomize(0);
        };
    }

    // ---- Customization: the full screen ------------------------------------------------------

    private static final int CUST_NONE = 0, CUST_QUICK = 1, CUST_FULL = 2;
    private static final int[] SECTION_NAMES = {R.string.menu_background, R.string.menu_home, R.string.menu_apps,
            R.string.menu_look, R.string.menu_projector, R.string.menu_lock, R.string.menu_system};
    private static final int[] SECTION_ICONS = {R.drawable.ic_background, R.drawable.ic_dock, R.drawable.ic_apps,
            R.drawable.ic_look, R.drawable.ic_projector, R.drawable.ic_lock, R.drawable.ic_settings};
    /** Which Customize is showing, so Back from a section knows where "up" is. */
    private int custMode = CUST_NONE;
    private MenuView rail;
    private View fullScrim;
    /** The section list has focus (the section's rows show beside it as a preview). */
    private boolean railActive;
    /** Where Back from the section list goes: the quick sheet, search, or nowhere (closes). */
    private Runnable fullBack;
    /** A setting opened from search: Back from this menu goes back to search. */
    private String searchReturnMenu;

    private int railWidth() {
        // Room for the longest section name ("Projector & tools") and the hints under it.
        return tk.dp(272);
    }

    /**
     * Customization: every setting, as a full screen. The seven sections on the left, the chosen
     * section's rows beside them, and Home still showing on the right, so background and look
     * changes preview live. Same menu look, same keys: Right or OK goes in, Left or Back comes out.
     */
    private void openFull(int section, Runnable back) {
        fullBack = back;
        searchReturnMenu = null;
        showFullViews();
        showFullRoot(Math.max(0, Math.min(SECTION_NAMES.length - 1, section)));
    }

    private void showFullViews() {
        custMode = CUST_FULL;
        if (rail == null) {
            fullScrim = new View(this);
            int a = Ui.alpha(theme.bg | 0xFF000000, 0.93f);
            fullScrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[] {a, a, a, Ui.alpha(a, 0.55f), a & 0x00FFFFFF}));
            fullScrim.setVisibility(View.GONE);
            // Only as wide as the panels and a short fade after them: the rest of Home shows as it
            // is, and the GPU fills a quarter less of the screen every frame.
            int scrimW = tk.safeH + railWidth() + tk.space5 + tk.sheetWidth + tk.space7 * 2;
            root.addView(fullScrim, root.indexOfChild(menu), new FrameLayout.LayoutParams(
                    scrimW, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START));
            rail = new MenuView(this, theme);
            rail.setVisibility(View.GONE);
            // No shadow over the backdrop (see showSheet).
            rail.setElevation(0);
            FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(railWidth(), ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.START);
            rlp.leftMargin = tk.safeH;
            rlp.topMargin = tk.safeV;
            root.addView(rail, root.indexOfChild(menu), rlp);
        }
        rail.setMaxHeight(root.getHeight() - 2 * tk.safeV);
        List<MenuView.Row> rows = new ArrayList<>();
        for (int i = 0; i < SECTION_NAMES.length; i++) rows.add(MenuView.Row.nav(SECTION_ICONS[i], tr(SECTION_NAMES[i]), ""));
        rail.setHeader(null, tr(R.string.cust_title));
        rail.setRows(rows, lastSection, false);
        if (rail.getVisibility() != View.VISIBLE) {
            for (View v : new View[] {fullScrim, rail}) {
                v.animate().cancel();
                v.setVisibility(View.VISIBLE);
                v.setAlpha(0f);
            }
            fullScrim.animate().alpha(1f).setDuration(tk.medium).setInterpolator(Tokens.EASE_OUT).start();
            rail.setTranslationX(-tk.space5);
            rail.animate().alpha(1f).translationX(0).setDuration(tk.medium).setInterpolator(Tokens.EASE_OUT).start();
        }
    }

    /** The section list has focus; the section's own menu shows beside it, as a preview. */
    private void showFullRoot(int section) {
        if (custMode != CUST_FULL) showFullViews();
        railActive = true;
        rail.setActive(true);
        rail.setFocus(section, false);
        rail.setHints(railHints(fullBack == null));
        openSection(section);
    }

    /** "OK Open  BACK Back" (or Close) under Customization's section list. */
    private String[] railHints(boolean closes) {
        return new String[] {tr(R.string.key_ok), tr(R.string.hint_open), tr(R.string.key_back),
                tr(closes ? R.string.hint_close : R.string.hint_back)};
    }

    private void openSection(int s) {
        switch (s) {
            case SEC_BACKGROUND: openBackgroundMenu(); break;
            case SEC_HOME: openHomeMenu(0); break;
            case SEC_APPS: openAppsMenu(0); break;
            case SEC_LOOK: openLookMenu(0); break;
            case SEC_PROJECTOR: openProjectorMenu(0); break;
            case SEC_LOCK: openLockMenu(0); break;
            default: openBackupMenu(0); break;
        }
    }

    private static final long PEEK_DELAY_MS = 150;
    private boolean peekPending;
    private final Runnable peekSection = () -> {
        peekPending = false;
        if (custMode == CUST_FULL && railActive && rail != null) openSection(rail.focus());
    };

    /** Keys while the section list has focus. */
    private void railNav(boolean up, boolean down, boolean left, boolean right, boolean select, boolean back) {
        if (up || down) {
            if (rail.moveFocus(up ? -1 : 1)) {
                sounds.tick(Sounds.FOCUS, navRepeat);
                // The section's menu is built once you stop on it: moving through the list only
                // moves the highlight (building a whole menu per step was the slow part).
                ui.removeCallbacks(peekSection);
                peekPending = true;
                ui.postDelayed(peekSection, PEEK_DELAY_MS);
            }
        } else if (right || select) {
            // Going in before the preview caught up: build it now.
            if (peekPending) {
                ui.removeCallbacks(peekSection);
                peekPending = false;
                openSection(rail.focus());
            }
            railActive = false;
            rail.setActive(false);
            menu.setActive(true);
            sounds.tick(Sounds.FOCUS, false);
        } else if (back || left) {
            ui.removeCallbacks(peekSection);
            peekPending = false;
            Runnable b = fullBack;
            hideFull(true);
            sounds.play(b == null ? Sounds.CLOSE : Sounds.BACK);
            if (b != null) b.run();
            else {
                leaveMenu();
                dismissMenu();
            }
        }
    }

    /** Closes Customization's section list and backdrop (the menu itself is the caller's). */
    private void hideFull(boolean animate) {
        if (custMode == CUST_FULL) custMode = CUST_NONE;
        railActive = false;
        searchReturnMenu = null;
        if (rail == null || rail.getVisibility() != View.VISIBLE) return;
        for (View v : new View[] {fullScrim, rail}) {
            v.animate().cancel();
            if (!animate) {
                v.setVisibility(View.GONE);
                continue;
            }
            v.animate().alpha(0f).setDuration(tk.fast).setInterpolator(Tokens.EASE_IN_OUT)
                    .withEndAction(() -> {
                        if (custMode != CUST_FULL) v.setVisibility(View.GONE);
                    }).start();
        }
    }

    /** Opens one of SettingsIndex's menus by its id (search). */
    private void openSettingMenu(String id) {
        switch (id) {
            case "background": openBackgroundMenu(); break;
            case "background.slideshow": openSlideshowMenu(); break;
            case "background.adjust": openAdjustMenu(0); break;
            case "home": openHomeMenu(0); break;
            case "home.layout": openLayoutMenu(0); break;
            case "home.categories": openCategoriesMenu(0); break;
            case "home.dock": openDockMenu(0); break;
            case "home.weather": openWeatherMenu(0); break;
            case "apps": openAppsMenu(0); break;
            case "look": openLookMenu(0); break;
            case "look.appearance": openAppearanceMenu(0); break;
            case "look.font": openFontMenu(0); break;
            case "look.clock": openClockMenu(0); break;
            case "look.bar": openBarMenu(0); break;
            case "look.bar.notif": openNotifSetting(); break;
            case "look.tiles": openTilesMenu(0); break;
            case "look.tiles.advanced": openTilesAdvanced(0); break;
            case "look.themes": openThemesMenu(0); break;
            case "look.sounds": openSoundsMenu(0); break;
            case "look.sounds.output": openSoundsOutputMenu(0); break;
            case "look.sounds.quiet": openSoundsQuietMenu(0); break;
            case "projector": openProjectorMenu(0); break;
            case "projector.startup": openStartupMenu(0); break;
            case "projector.idle": openIdleMenu(); break;
            case "lock": openLockMenu(0); break;
            case "lock.shortcuts": openShortcutsMenu(0); break;
            case "system": openBackupMenu(0); break;
            case "system.search": openSearchSettings(0); break;
            case "system.about": openAboutMenu(0); break;
            default: openSection(SettingsIndex.section(id)); break;
        }
    }

    /** A setting picked in search: its menu in Customization, on its row, which pulses once. */
    private void openSettingFromSearch(SettingsIndex.Entry e) {
        requirePin(customizeLocked(), tr(R.string.customize), () -> {
            search.hideNow();
            int section = SettingsIndex.section(e.menu);
            lastSection = section;
            fullBack = this::backToSearch;
            showFullViews();
            rail.setFocus(section, false);
            railActive = false;
            rail.setActive(false);
            rail.setHints(railHints(false));
            openSettingMenu(e.menu);
            menu.setActive(true);
            // The row shows the same string resource as the entry, so this holds in every language.
            String label = tr(e.label);
            for (int i = 0; i < menu.size(); i++) {
                if (label.equals(menu.row(i).label)) {
                    menu.setFocus(i, false);
                    break;
                }
            }
            menu.pulse();
            searchReturnMenu = menuCrumb + "|" + menuTitle;
        });
    }

    /** Back from Customization to search, as you left it. */
    private void backToSearch() {
        hideFull(false);
        leaveMenu();
        menu.animate().cancel();
        menu.setVisibility(View.GONE);
        Lock.relock();
        search.reshow();
    }

    /** Where a menu sits: "Customize  ›  Look & sound", then any further parts. */
    private String crumb(String... parts) {
        String[] all = new String[parts.length + 1];
        all[0] = tr(R.string.customize);
        System.arraycopy(parts, 0, all, 1, parts.length);
        return crumbOf(all);
    }

    /** Parts of a menu's place joined with " › " (the separator is translatable). */
    private String crumbOf(String... parts) {
        return TextUtils.join(tr(R.string.crumb_separator), parts);
    }

    private String categoriesCrumb() {
        return crumb(tr(R.string.menu_home));
    }

    // Background

    // 0–5 are dark backgrounds, 6–8 light ones; each mode lists only its own.
    private static final int[] PRESETS = {R.string.bg_black, R.string.bg_midnight, R.string.bg_graphite,
            R.string.bg_aurora, R.string.bg_ember, R.string.bg_dusk, R.string.bg_pearl, R.string.bg_mist, R.string.bg_sand};
    private static final int FIRST_LIGHT_PRESET = 6;
    // The menu scrolls, so list everything a normal drive holds.
    private static final int MAX_PHOTOS = 1000;
    private Runnable pendingPreview;

    private String backgroundName() {
        String bg = currentBg();
        if (bg.startsWith("preset:")) return tr(PRESETS[Integer.parseInt(bg.substring(7))]);
        if (bg.startsWith("shape:")) return tr(Backdrops.NAMES[Integer.parseInt(bg.substring(6))]);
        if (bg.equals("photo")) return tr(R.string.set_background_photo);
        if (bg.equals("anim")) return tr(R.string.bg_animated_photo);
        if (bg.equals("slideshow")) return tr(R.string.set_background_slideshow);
        if (bg.equals("video")) return tr(R.string.set_background_video);
        if (bg.equals("online")) return tr(R.string.set_background_photo_of_the_day);
        return tr(getResources().getIdentifier("wallpaper", "drawable", getPackageName()) != 0 ? R.string.bg_custom : R.string.bg_black);
    }

    private void openBackgroundMenu() {
        lastSection = SEC_BACKGROUND;
        String current = currentBg();
        boolean resWallpaper = getResources().getIdentifier("wallpaper", "drawable", getPackageName()) != 0;
        boolean colour = current.startsWith("preset:") || (current.isEmpty() && !resWallpaper);
        boolean shape = current.startsWith("shape:");
        boolean photo = current.equals("photo") || current.equals("anim"), slides = current.equals("slideshow");
        boolean video = current.equals("video");
        startMenu(tr(R.string.customize), tr(R.string.menu_background));
        add(MenuView.Row.nav(R.drawable.ic_look, tr(R.string.set_background_colours), colour ? backgroundName() : ""), this::openColoursMenu);
        add(MenuView.Row.nav(R.drawable.ic_shapes, tr(R.string.set_background_shapes), shape ? backgroundName() : ""), this::openShapesMenu);
        add(MenuView.Row.nav(R.drawable.ic_background, tr(R.string.set_background_photo), photo ? backgroundName() : ""), this::openPhotoMenu);
        add(MenuView.Row.nav(R.drawable.ic_picture, tr(R.string.set_background_slideshow),
                slides ? tr(R.string.slide_every_short, slideShort(slideIndex())) : ""), this::openSlideshowMenu);
        add(MenuView.Row.nav(R.drawable.ic_video, tr(R.string.set_background_video), video ? tr(R.string.bg_in_use) : ""), this::openVideoMenu);
        boolean online = current.equals("online");
        add(MenuView.Row.nav(R.drawable.ic_weather, tr(R.string.set_background_photo_of_the_day), tr(online ? R.string.bg_in_use : R.string.bg_uses_internet)),
                this::openOnlineMenu);
        MenuView.Row adjust = add(MenuView.Row.nav(R.drawable.ic_brightness, tr(R.string.set_background_adjust),
                tr(Wallpaper.Adjust.from(prefs).none() ? R.string.off : R.string.on)), () -> openAdjustMenu(0));
        if (!(photo || slides || video || online)) adjust.disabled(tr(R.string.adjust_for_what));
        showSheet(shape ? 1 : photo ? 2 : slides ? 3 : video ? 4 : online ? 5 : 0, backTo(SEC_BACKGROUND), null);
    }

    // Photo of the day: the one background that uses the internet, and only when you choose it.

    private final OnlineSource online = new OnlineSource.WikimediaToday();
    private TextView creditView;
    private boolean onlineLoading, onlineWarned;

    private File onlineFile() {
        return new File(getFilesDir(), "online.jpg");
    }

    private static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
    }

    private void openOnlineMenu() {
        String crumb = crumb(tr(R.string.menu_background));
        startMenu(crumb, tr(R.string.set_background_photo_of_the_day));
        boolean on = "online".equals(currentBg());
        add(MenuView.Row.note(R.drawable.ic_info, tr(R.string.online_note, tr(online.about()))), null);
        add(MenuView.Row.action(R.drawable.ic_check, tr(on ? R.string.online_again : R.string.bg_use_as_background)), () -> {
            if (on) prefs.edit().remove("online_day").apply();
            applyBackground("online", true);
            openBackgroundMenu();
        });
        String credit = onlineCredit();
        if (on && !credit.isEmpty()) add(MenuView.Row.note(R.drawable.ic_picture, credit), null);
        showSheet(1, this::openBackgroundMenu, null);
    }

    /** Today's picture: from the cache if it's today's, else fetched once (the cache covers no network). */
    private void showOnline() {
        File f = onlineFile();
        boolean fresh = today().equals(prefs.getString("online_day", "")) && f.exists();
        if (f.exists()) loader.execute(() -> {
            wallRaw = decodeScaled(() -> new java.io.FileInputStream(f), Uri.fromFile(f));
            Bitmap bm = prepared(wallRaw);
            ui.post(() -> {
                if ("online".equals(currentBg())) {
                    showPhoto(bm);
                    showCredit(true);
                }
            });
        });
        if (fresh || onlineLoading) return;
        onlineLoading = true;
        if (!f.exists()) toast(tr(R.string.toast_online_getting), false);
        loader.execute(() -> {
            OnlineSource.Picture p;
            try {
                p = online.fetch();
                try (FileOutputStream out = new FileOutputStream(f)) {
                    out.write(p.bytes);
                }
            } catch (IOException e) {
                p = null;
            }
            OnlineSource.Picture got = p;
            if (got != null) {
                wallRaw = decodeScaled(() -> new java.io.FileInputStream(f), Uri.fromFile(f));
            }
            Bitmap bm = got == null ? null : prepared(wallRaw);
            ui.post(() -> {
                onlineLoading = false;
                if (got == null) {
                    // No network: keep yesterday's (or the plain background) and try again later.
                    if (!onlineWarned) toast(tr(R.string.toast_online_unreachable), false);
                    onlineWarned = true;
                    if (!f.exists()) setEdges(false);
                    return;
                }
                onlineWarned = false;
                // Who and which licence, not a sentence: the credit is worded in the language shown.
                prefs.edit().putString("online_day", today()).remove("online_credit")
                        .putString("online_artist", got.artist).putString("online_license", got.license).apply();
                if (!"online".equals(currentBg()) || bm == null) return;
                crossfadeTo(bm, tk.slow);
                showCredit(true);
            });
        });
    }

    /** The photographer and licence, small, in the corner above the dock (only for the photo of the day). */
    private void showCredit(boolean show) {
        if (creditView == null) {
            creditView = Ui.text(this, "", tk.textCaption, theme.textSecondary, tk.regular);
            // Readable on any part of any photo.
            creditView.setShadowLayer(tk.space2, 0, 0, theme.light ? theme.bg : theme.shadow);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
            lp.rightMargin = tk.safeH;
            lp.bottomMargin = tk.space4;
            hero.addView(creditView, lp);
        }
        String credit = onlineCredit();
        creditView.setText(credit);
        creditView.setVisibility(show && !credit.isEmpty() ? View.VISIBLE : View.GONE);
    }

    /**
     * "Photo: Jane Doe, Wikimedia Commons (CC BY-SA 4.0)" for today's picture. A credit saved as a
     * sentence by 1.3 or earlier shows as it was until the next picture arrives.
     */
    private String onlineCredit() {
        if (!prefs.contains("online_artist")) return prefs.getString("online_credit", "");
        String artist = prefs.getString("online_artist", ""), license = prefs.getString("online_license", "");
        String source = artist.isEmpty() ? tr(R.string.online_source) : tr(R.string.online_source_artist, artist.trim());
        String line = tr(R.string.online_credit, source);
        return license.isEmpty() ? line : tr(R.string.online_credit_license, line, license);
    }

    // Adjust: brightness, contrast, saturation, blur. Drawn into each picture once, previewed live.

    private static final String[] ADJ_KEYS = {"adj_b", "adj_c", "adj_s"};
    private static final int[] ADJ_NAMES = {R.string.set_background_adjust_brightness,
            R.string.set_background_adjust_contrast, R.string.set_background_adjust_saturation};

    private void openAdjustMenu(int focus) {
        String crumb = crumb(tr(R.string.menu_background));
        startMenu(crumb, tr(R.string.menu_background_adjust));
        boolean video = "video".equals(currentBg()), anim = "anim".equals(currentBg());
        Wallpaper.Adjust before = Wallpaper.Adjust.from(prefs);
        for (int i = 0; i < ADJ_KEYS.length; i++) {
            String key = ADJ_KEYS[i];
            int v = prefs.getInt(key, 0);
            MenuView.Row row = addAdjust(MenuView.Row.slider(0, tr(ADJ_NAMES[i]), (v + 50) / 100f, signed(v)), dir -> {
                int nv = Math.max(-50, Math.min(50, prefs.getInt(key, 0) + dir * 5));
                prefs.edit().putInt(key, nv).apply();
                MenuView.Row r = menu.focused();
                r.fraction = (nv + 50) / 100f;
                r.value(signed(nv));
                previewAdjust();
            });
            // A video can only be dimmed or brightened (a layer over it); the rest needs the pixels.
            if (video && i > 0) row.disabled(tr(R.string.adjust_video_only_brightness));
        }
        int blur = prefs.getInt("adj_blur", 0);
        MenuView.Row blurRow = addAdjust(MenuView.Row.stepper(0, tr(R.string.set_background_adjust_blur), tr(Wallpaper.BLUR_NAMES[blur])), dir -> {
            int nb = Math.max(0, Math.min(3, prefs.getInt("adj_blur", 0) + dir));
            prefs.edit().putInt("adj_blur", nb).apply();
            menu.focused().value(tr(Wallpaper.BLUR_NAMES[nb]));
            previewAdjust();
        });
        if (video || anim) blurRow.disabled(tr(video ? R.string.adjust_video_no_blur : R.string.adjust_anim_no_blur));
        add(MenuView.Row.action(R.drawable.ic_reset, tr(R.string.set_background_adjust_reset_adjustments)), () -> {
            prefs.edit().remove("adj_b").remove("adj_c").remove("adj_s").remove("adj_blur").apply();
            previewAdjust();
            toast(tr(R.string.toast_adjust_reset), true);
            openAdjustMenu(4);
        });
        showSheet(focus, () -> {
            if (!before.equals(Wallpaper.Adjust.from(prefs))) toast(tr(R.string.toast_adjust_saved), true);
            openBackgroundMenu();
        }, null);
        menu.setHints("◀ ▶", tr(R.string.hint_adjust), tr(R.string.key_back), tr(R.string.hint_done));
    }

    /** -5, 0, +5 as numbers are written in the language shown. */
    private static String signed(int v) {
        java.text.NumberFormat f = java.text.NumberFormat.getIntegerInstance();
        return v > 0 ? "+" + f.format(v) : f.format(v);
    }

    private final Runnable adjustTick = this::applyAdjustNow;

    /** Shortly after the last change (holding a button changes it many times a second). */
    private void previewAdjust() {
        ui.removeCallbacks(adjustTick);
        ui.postDelayed(adjustTick, tk.fast);
    }

    private void applyAdjustNow() {
        String bg = currentBg();
        if ("video".equals(bg)) {
            applyVideoLook();
            return;
        }
        if ("anim".equals(bg)) {
            applyAnimLook();
            return;
        }
        loader.execute(() -> {
            Bitmap raw = wallRaw;
            if (raw == null) return;
            Bitmap bm = prepared(raw);
            ui.post(() -> {
                if (isDestroyed()) return;
                wallpaper.setImageBitmap(bm);
                updateAccent(bm);
            });
        });
    }

    /** The current photo or slide as decoded (no adjustments, no dimming); touched on the loader thread only. */
    private Bitmap wallRaw;

    /** A decoded picture with your adjustments and dark mode's dimming drawn in; {@code raw} stays as it is. */
    private Bitmap prepared(Bitmap raw) {
        if (raw == null) return null;
        return dimmed(Wallpaper.apply(raw, Wallpaper.Adjust.from(prefs)));
    }

    /** Plain colours and gradients; each previews as you move, Back puts the old one back. */
    private void openColoursMenu() {
        String current = currentBg();
        boolean resWallpaper = getResources().getIdentifier("wallpaper", "drawable", getPackageName()) != 0;
        startMenu(crumb(tr(R.string.menu_background)), tr(R.string.set_background_colours));
        int focus = 0;
        int from = theme.light ? FIRST_LIGHT_PRESET : 0, to = theme.light ? PRESETS.length : FIRST_LIGHT_PRESET;
        for (int i = from; i < to; i++) {
            String spec = "preset:" + i;
            String name = tr(PRESETS[i]);
            boolean isCurrent = spec.equals(current) || (i == 0 && current.isEmpty() && !resWallpaper);
            if (isCurrent) focus = pendingRows.size();
            add(MenuView.Row.choice(name, isCurrent), () -> {
                applyBackground(spec, true);
                toast(tr(R.string.toast_bg_set_to, name), true);
                openBackgroundMenu();
            }, () -> applyBackground(spec, false));
        }
        showSheet(focus, this::openBackgroundMenu, () -> applyBackground(current, false));
    }

    /** Shape backgrounds (Backdrops); drawn for the current light or dark look. */
    private void openShapesMenu() {
        String current = currentBg();
        startMenu(crumb(tr(R.string.menu_background)), tr(R.string.set_background_shapes));
        int focus = 0;
        for (int i = 0; i < Backdrops.NAMES.length; i++) {
            String spec = "shape:" + i;
            String name = tr(Backdrops.NAMES[i]);
            if (spec.equals(current)) focus = i;
            add(MenuView.Row.choice(name, spec.equals(current)), () -> {
                cancelPhotoPreview();
                applyBackground(spec, true);
                toast(tr(R.string.toast_bg_set_to, name), true);
                openBackgroundMenu();
            }, () -> {
                // Drawing one takes a moment, so wait until you pause on it.
                cancelPhotoPreview();
                pendingPreview = () -> applyBackground(spec, false);
                ui.postDelayed(pendingPreview, tk.spinnerDelay);
            });
        }
        showSheet(focus, this::openBackgroundMenu, () -> {
            cancelPhotoPreview();
            applyBackground(current, false);
        });
        // Show the first one straight away if nothing from here is on screen yet.
        if (!current.startsWith("shape:")) {
            pendingPreview = () -> applyBackground("shape:0", false);
            ui.postDelayed(pendingPreview, tk.spinnerDelay);
        }
    }

    // Rendered shape backgrounds, newest last; two at most (each is a full-screen bitmap).
    private final LinkedHashMap<String, Bitmap> shapeCache = new LinkedHashMap<>();
    private String wantedShape;

    private void showShape(String spec) {
        String key = spec + (theme.light ? ":light" : ":dark");
        wantedShape = key;
        Bitmap cached = shapeCache.get(key);
        if (cached != null) {
            crossfadeTo(cached, tk.medium, false);
            return;
        }
        int kind = Integer.parseInt(spec.substring(6));
        boolean light = theme.light;
        int w = getResources().getDisplayMetrics().widthPixels, h = getResources().getDisplayMetrics().heightPixels;
        loader.execute(() -> {
            Bitmap bm = Backdrops.render(getResources(), kind, light, w, h);
            ui.post(() -> {
                if (isDestroyed()) return;
                shapeCache.put(key, bm);
                while (shapeCache.size() > 2) shapeCache.remove(shapeCache.keySet().iterator().next());
                // Only if it's still the one asked for (you may have moved on meanwhile).
                if (key.equals(wantedShape)) crossfadeTo(bm, tk.medium, false);
            });
        });
    }

    /** Built-in backgrounds are gradients drawn at runtime: no image files, almost no memory. */
    private Drawable presetDrawable(int i) {
        switch (i) {
            case 0: return new ColorDrawable(theme.bg);
            case 1: return linear(GradientDrawable.Orientation.TOP_BOTTOM, c(R.color.preset_midnight), theme.bg);
            case 2: return linear(GradientDrawable.Orientation.TOP_BOTTOM, c(R.color.preset_graphite), theme.bg);
            case 3: return glowLayers(c(R.color.preset_aurora_a), 0.12f, 0.95f, c(R.color.preset_aurora_b), 0.88f, 0.12f);
            case 4: return glowLayers(c(R.color.preset_ember_a), 0.18f, 0.92f, c(R.color.preset_ember_b), 0.9f, 0.15f);
            case 5: return linear(GradientDrawable.Orientation.TL_BR, c(R.color.preset_dusk_a), c(R.color.preset_dusk_b), theme.bg);
            // Light: soft, low-contrast so dark text reads cleanly on them.
            case 6: return linear(GradientDrawable.Orientation.TOP_BOTTOM, c(R.color.preset_pearl_a), c(R.color.preset_pearl_b));
            case 7: return linear(GradientDrawable.Orientation.TL_BR, c(R.color.preset_mist_a), c(R.color.preset_mist_b),
                    c(R.color.preset_mist_c));
            case 8: return linear(GradientDrawable.Orientation.TL_BR, c(R.color.preset_sand_a), c(R.color.preset_sand_b),
                    c(R.color.preset_sand_c));
            default: return null;
        }
    }

    private static GradientDrawable linear(GradientDrawable.Orientation o, int... colors) {
        return new GradientDrawable(o, colors);
    }

    private int c(int colorRes) {
        return getResources().getColor(colorRes, null);
    }

    private Drawable glowLayers(int c1, float x1, float y1, int c2, float x2, float y2) {
        return new LayerDrawable(new Drawable[] {new ColorDrawable(theme.bg), radial(c1, x1, y1), radial(c2, x2, y2)});
    }

    private Drawable radial(int color, float cx, float cy) {
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setGradientCenter(cx, cy);
        g.setGradientRadius(getResources().getDisplayMetrics().widthPixels * 0.62f);
        g.setColors(new int[] {color, color & 0x00FFFFFF});
        return g;
    }

    private File photoFile() {
        return new File(getFilesDir(), "background.jpg");
    }

    /** "preset:N", "shape:N", "photo" (a copy in app storage), or "" for config.xml's wallpaper or black. */
    private void applyBackground(String spec, boolean save) {
        if (save) prefs.edit().putString(bgKey(), spec).apply();
        wantedShape = null;
        if (!spec.equals("video")) stopVideo();
        if (!spec.equals("anim")) stopAnim();
        if (!spec.equals("online") && creditView != null) showCredit(false);
        if (spec.equals("online")) {
            stopSlideshow();
            showOnline();
            return;
        }
        if (spec.equals("slideshow")) {
            // Already running (e.g. a menu preview put it back): don't jump to a new photo.
            if (!slideshowOn) startSlideshow();
            setEdges(true);
            return;
        }
        stopSlideshow();
        if (spec.equals("video")) {
            String v = prefs.getString("bg_video", "");
            if (!v.isEmpty()) showVideo(Uri.parse(v));
            return;
        }
        if (spec.equals("anim") && animFile().exists()) {
            showAnim(animFile());
            return;
        }
        if (spec.startsWith("shape:")) {
            // Fades over whatever is showing, so no clearing first.
            showShape(spec);
            return;
        }
        wallpaperNext.animate().cancel();
        wallpaperNext.setVisibility(View.GONE);
        wallpaperNext.setImageDrawable(null);
        wallpaper.setForeground(null);
        if (spec.startsWith("preset:")) {
            Drawable d = presetDrawable(Integer.parseInt(spec.substring(7)));
            wallpaper.setScaleType(ImageView.ScaleType.FIT_XY);
            wallpaper.setImageDrawable(d);
            wallpaper.setVisibility(d == null ? View.GONE : View.VISIBLE);
            setEdges(false);
            updateAccent(d);
        } else if (spec.equals("photo") && photoFile().exists()) {
            File f = photoFile();
            loader.execute(() -> {
                wallRaw = decodeScaled(() -> new FileInputStream(f), Uri.fromFile(f));
                Bitmap bm = prepared(wallRaw);
                ui.post(() -> showPhoto(bm));
            });
        } else {
            int id = getResources().getIdentifier("wallpaper", "drawable", getPackageName());
            if (id == 0) {
                wallpaper.setImageDrawable(null);
                wallpaper.setVisibility(View.GONE);
                setEdges(false);
            } else {
                wallpaper.setScaleType(ImageView.ScaleType.CENTER_CROP);
                wallpaper.setImageResource(id);
                // A resource wallpaper isn't decoded here, so its dimming stays a layer.
                wallpaper.setForeground(new ColorDrawable(photoScrim()));
                wallpaper.setVisibility(View.VISIBLE);
                setEdges(true);
            }
        }
    }

    private void showPhoto(Bitmap bm) {
        showPhoto(bm, true);
    }

    /** {@code photo} false for a shape background: it's calm enough that text needs no fades. */
    private void showPhoto(Bitmap bm, boolean photo) {
        if (bm == null || isDestroyed()) return;
        boolean wasHidden = wallpaper.getVisibility() != View.VISIBLE;
        wallpaper.setScaleType(ImageView.ScaleType.CENTER_CROP);
        // Already dimmed (dark mode) by dimmed(), so no overlay layer here.
        wallpaper.setImageBitmap(bm);
        wallpaper.setForeground(null);
        wallpaper.setColorFilter(null);
        wallpaper.setVisibility(View.VISIBLE);
        setEdges(photo);
        updateAccent(bm);
        if (wasHidden) {
            // First photo after start-up fades in instead of popping.
            wallpaper.setAlpha(0f);
            wallpaper.animate().alpha(1f).setDuration(tk.slow).setInterpolator(Tokens.EASE_IN_OUT).start();
        }
    }

    private String imagePermission() {
        return Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_IMAGES
                : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    /** The photos on the TV and any USB drive, by name. Listed off the main thread. */
    private void openPhotoMenu() {
        if (checkSelfPermission(imagePermission()) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {imagePermission()}, REQ_PHOTOS);
            return;
        }
        String crumb = crumb(tr(R.string.menu_background));
        startMenu(crumb, tr(R.string.set_background_photo));
        add(MenuView.Row.loading(tr(R.string.photo_looking)), null);
        String current = currentBg();
        Runnable restore = () -> {
            cancelPhotoPreview();
            applyBackground(current, false);
        };
        showSheet(0, this::openBackgroundMenu, restore);
        loader.execute(() -> {
            Uri collection = Media.images();
            String[] cols = {MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME};
            List<Uri> uris = new ArrayList<>();
            List<String> names = new ArrayList<>();
            try (Cursor c = getContentResolver().query(collection, cols, null, null,
                    MediaStore.Images.Media.DISPLAY_NAME + " COLLATE NOCASE ASC")) {
                while (c != null && c.moveToNext() && uris.size() < MAX_PHOTOS) {
                    uris.add(ContentUris.withAppendedId(collection, c.getLong(0)));
                    names.add(c.getString(1));
                }
            } catch (RuntimeException ignored) {
            }
            ui.post(() -> {
                // Still on the loading sheet? (Back may have been pressed meanwhile.)
                if (isDestroyed() || area != MENU || !crumb.equals(menuCrumb)) return;
                startMenu(crumb, tr(R.string.set_background_photo));
                for (int i = 0; i < uris.size(); i++) {
                    Uri uri = uris.get(i);
                    String name = names.get(i);
                    add(MenuView.Row.action(0, name == null ? tr(R.string.set_background_photo) : name), () -> {
                        cancelPhotoPreview();
                        usePhoto(uri);
                    }, () -> schedulePhotoPreview(uri));
                }
                if (uris.isEmpty()) {
                    add(MenuView.Row.note(R.drawable.ic_info, tr(R.string.photo_none)), null);
                }
                showSheet(0, this::openBackgroundMenu, restore);
                if (!uris.isEmpty()) schedulePhotoPreview(uris.get(0));
            });
        });
    }

    /** Preview a highlighted photo once you pause on it (decoding on every press would lag). */
    private void schedulePhotoPreview(Uri uri) {
        cancelPhotoPreview();
        pendingPreview = () -> loader.execute(() -> {
            Bitmap bm = prepared(decodeScaled(() -> getContentResolver().openInputStream(uri), uri));
            ui.post(() -> {
                if (bm == null || area != MENU || isDestroyed()) return;
                stopSlideshow();
                stopVideo();
                stopAnim();
                crossfadeTo(bm, tk.slow);
            });
        });
        ui.postDelayed(pendingPreview, tk.spinnerDelay);
    }

    private void cancelPhotoPreview() {
        if (pendingPreview != null) ui.removeCallbacks(pendingPreview);
        pendingPreview = null;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        boolean ok = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        if (requestCode == REQ_PHOTOS && ok) openPhotoMenu();
        if (requestCode == REQ_SLIDES && ok) openSlideshowMenu();
        if (requestCode == REQ_VIDEOS && ok) openVideoMenu();
        if (!ok && (requestCode == REQ_PHOTOS || requestCode == REQ_SLIDES)) {
            toast(tr(R.string.toast_photos_permission), false);
        }
        if (!ok && requestCode == REQ_VIDEOS) toast(tr(R.string.toast_videos_permission), false);
        if (requestCode == REQ_MIC) {
            askingMic = false;
            Runnable then = afterMic;
            afterMic = null;
            if (ok && then != null && search.isOpen()) then.run();
            if (!ok) toast(tr(R.string.toast_mic_permission), false);
        }
    }

    // Slideshow: random order through the USB drive's photos, one decoded bitmap at a time.

    private static final int[] SLIDE_SECONDS = {15, 30, 60, 300, 900};
    private static final int[] SLIDE_LABELS = {R.string.slide_every_15s, R.string.slide_every_30s, R.string.slide_every_1m,
            R.string.slide_every_5m, R.string.slide_every_15m};
    private final List<Uri> slideQueue = new ArrayList<>();
    private boolean slideshowOn, slideLoading, slideWarned;
    private final Runnable slideTick = this::nextSlide;

    /** "15 sec", "1 min": how often the slideshow changes, short. */
    private String slideShort(int i) {
        int s = SLIDE_SECONDS[i];
        return s < 60 ? trn(R.plurals.duration_sec_short, s) : trn(R.plurals.duration_min_short, s / 60);
    }

    private int slideIndex() {
        int s = prefs.getInt("slide_seconds", 60);
        for (int i = 0; i < SLIDE_SECONDS.length; i++) if (SLIDE_SECONDS[i] == s) return i;
        return 2;
    }

    private void openSlideshowMenu() {
        if (checkSelfPermission(imagePermission()) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {imagePermission()}, REQ_SLIDES);
            return;
        }
        boolean on = "slideshow".equals(currentBg());
        String crumb = crumb(tr(R.string.menu_background));
        startMenu(crumb, tr(R.string.menu_background_slideshow));
        add(MenuView.Row.nav(R.drawable.ic_background, tr(R.string.set_background_slideshow_photos_from), slideFolderName()), this::openSlideFolderMenu);
        add(MenuView.Row.nav(R.drawable.ic_sleep, tr(R.string.set_background_slideshow_change_every), slideShort(slideIndex())), () -> openChoice(
                crumbOf(crumb, tr(R.string.menu_background_slideshow)), tr(R.string.set_background_slideshow_change_every),
                trAll(SLIDE_LABELS), slideIndex(), i -> {
                    prefs.edit().putInt("slide_seconds", SLIDE_SECONDS[i]).apply();
                    if ("slideshow".equals(currentBg())) restartSlideshow();
                    openSlideshowMenu();
                }, null, this::openSlideshowMenu));
        add(MenuView.Row.action(R.drawable.ic_check, tr(on ? R.string.slide_next_now : R.string.bg_use_as_background)), () -> {
            restartSlideshow();
            toast(on ? tr(R.string.toast_slide_next) : tr(R.string.toast_slide_on, slideShort(slideIndex())), true);
            openBackgroundMenu();
        });
        showSheet(on ? 0 : 2, this::openBackgroundMenu, null);
    }

    private void restartSlideshow() {
        stopSlideshow();
        synchronized (slideQueue) {
            slideQueue.clear();
        }
        slideWarned = false;
        applyBackground("slideshow", true);
    }

    /** "" for every photo on USB drives, or "volume|relative/path/" for one folder and its subfolders. */
    private String slideFolder() {
        return prefs.getString("slide_folder", "");
    }

    private String slideFolderName() {
        String f = slideFolder();
        if (f.isEmpty()) return tr(R.string.slide_usb_drives);
        return folderLabel(f.substring(0, f.indexOf('|')), f.substring(f.indexOf('|') + 1));
    }

    /** "USB › Photos › 2024": a drive, then the folders on it (their own names). */
    private String folderLabel(String volume, String path) {
        String where = tr(Media.primary().equals(volume) ? R.string.slide_this_tv : R.string.slide_usb);
        String p = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        String sep = tr(R.string.folder_separator);
        return p.isEmpty() ? where : where + sep + p.replace("/", sep);
    }

    /**
     * Every folder that holds photos, directly or in a subfolder, with how many. Picking one shows
     * the photos in it and in every folder below it.
     */
    private void openSlideFolderMenu() {
        String crumb = crumb(tr(R.string.menu_background), tr(R.string.menu_background_slideshow));
        startMenu(crumb, tr(R.string.set_background_slideshow_photos_from));
        add(MenuView.Row.loading(tr(R.string.slide_looking_folders)), null);
        showSheet(0, this::openSlideshowMenu, null);
        loader.execute(() -> {
            Map<String, Integer> counts = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            if (Build.VERSION.SDK_INT >= 29) {
                String[] cols = {MediaStore.Images.Media.VOLUME_NAME, MediaStore.Images.Media.RELATIVE_PATH};
                try (Cursor c = getContentResolver().query(Media.images(),
                        cols, null, null, null)) {
                    while (c != null && c.moveToNext()) {
                        String vol = c.getString(0), rel = c.getString(1);
                        if (vol == null || rel == null) continue;
                        // Count the photo in its own folder and every folder above it.
                        String[] parts = rel.split("/");
                        StringBuilder sb = new StringBuilder();
                        counts.merge(vol + "|", 1, Integer::sum);
                        for (String part : parts) {
                            if (part.isEmpty()) continue;
                            sb.append(part).append('/');
                            counts.merge(vol + "|" + sb, 1, Integer::sum);
                        }
                    }
                } catch (RuntimeException ignored) {
                }
            }
            ui.post(() -> {
                if (isDestroyed() || area != MENU || !crumb.equals(menuCrumb)) return;
                startMenu(crumb, tr(R.string.set_background_slideshow_photos_from));
                String cur = slideFolder();
                add(MenuView.Row.choice(tr(R.string.slide_every_usb_photo), cur.isEmpty()), () -> pickSlideFolder(""));
                int focus = 0;
                for (Map.Entry<String, Integer> e : counts.entrySet()) {
                    String key = e.getKey();
                    if (key.equals(cur)) focus = pendingRows.size();
                    String vol = key.substring(0, key.indexOf('|')), rel = key.substring(key.indexOf('|') + 1);
                    add(MenuView.Row.choice(folderLabel(vol, rel), key.equals(cur)).value(number(e.getValue())),
                            () -> pickSlideFolder(key));
                }
                if (counts.isEmpty()) add(MenuView.Row.note(R.drawable.ic_info, tr(R.string.photo_none)), null);
                showSheet(focus, this::openSlideshowMenu, null);
            });
        });
    }

    private void pickSlideFolder(String key) {
        prefs.edit().putString("slide_folder", key).apply();
        restartSlideshow();
        toast(tr(R.string.toast_slide_folder, slideFolderName()), true);
        openSlideshowMenu();
    }

    // Video

    private String videoPermission() {
        return Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_VIDEO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    /** Videos on the TV and USB drives, by name. Previewed on Home once you pause on one. */
    private void openVideoMenu() {
        if (checkSelfPermission(videoPermission()) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {videoPermission()}, REQ_VIDEOS);
            return;
        }
        String crumb = crumb(tr(R.string.menu_background));
        startMenu(crumb, tr(R.string.set_background_video));
        add(MenuView.Row.loading(tr(R.string.video_looking)), null);
        String current = currentBg();
        Runnable restore = () -> {
            cancelPhotoPreview();
            applyBackground(current, false);
        };
        showSheet(0, this::openBackgroundMenu, restore);
        loader.execute(() -> {
            Uri collection = Media.videos();
            String[] cols = {MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DURATION};
            List<Uri> uris = new ArrayList<>();
            List<String> names = new ArrayList<>(), lengths = new ArrayList<>();
            try (Cursor c = getContentResolver().query(collection, cols, null, null,
                    MediaStore.Video.Media.DISPLAY_NAME + " COLLATE NOCASE ASC")) {
                while (c != null && c.moveToNext() && uris.size() < MAX_PHOTOS) {
                    uris.add(ContentUris.withAppendedId(collection, c.getLong(0)));
                    names.add(c.getString(1));
                    long s = c.getLong(2) / 1000;
                    // Some TVs don't index the length; better nothing than "0 s".
                    lengths.add(s <= 0 ? "" : s >= 60 ? trn(R.plurals.video_length_min, (int) (s / 60))
                            : trn(R.plurals.video_length_sec, (int) s));
                }
            } catch (RuntimeException ignored) {
            }
            ui.post(() -> {
                if (isDestroyed() || area != MENU || !crumb.equals(menuCrumb)) return;
                startMenu(crumb, tr(R.string.set_background_video));
                String cur = prefs.getString("bg_video", "");
                int focus = 0;
                for (int i = 0; i < uris.size(); i++) {
                    Uri uri = uris.get(i);
                    if (uri.toString().equals(cur)) focus = i;
                    add(MenuView.Row.choice(names.get(i) == null ? tr(R.string.set_background_video) : names.get(i),
                            "video".equals(current) && uri.toString().equals(cur)).value(lengths.get(i)), () -> {
                        cancelPhotoPreview();
                        prefs.edit().putString("bg_video", uri.toString()).apply();
                        applyBackground("video", true);
                        toast(tr(R.string.toast_video_set), true);
                        backTo(SEC_BACKGROUND).run();
                    }, () -> {
                        cancelPhotoPreview();
                        pendingPreview = () -> {
                            stopSlideshow();
                            stopAnim();
                            showVideo(uri);
                        };
                        ui.postDelayed(pendingPreview, tk.spinnerDelay * 2);
                    });
                }
                if (uris.isEmpty()) add(MenuView.Row.note(R.drawable.ic_info, tr(R.string.video_none)), null);
                showSheet(focus, this::openBackgroundMenu, restore);
            });
        });
    }

    private void startSlideshow() {
        slideshowOn = true;
        ui.removeCallbacks(slideTick);
        nextSlide();
    }

    private void stopSlideshow() {
        slideshowOn = false;
        ui.removeCallbacks(slideTick);
    }

    private void nextSlide() {
        if (!slideshowOn || slideLoading || isDestroyed()) return;
        slideLoading = true;
        loader.execute(() -> {
            Uri pick = null;
            synchronized (slideQueue) {
                if (slideQueue.isEmpty()) {
                    slideQueue.addAll(usbPhotos());
                    Collections.shuffle(slideQueue);
                }
                if (!slideQueue.isEmpty()) pick = slideQueue.remove(0);
            }
            Uri uri = pick;
            Bitmap raw = uri == null ? null : decodeScaled(() -> getContentResolver().openInputStream(uri), uri);
            if (raw != null) wallRaw = raw;
            Bitmap bm = prepared(raw);
            ui.post(() -> {
                slideLoading = false;
                if (!slideshowOn || isDestroyed()) return;
                long delay;
                if (bm != null) {
                    crossfadeTo(bm, tk.crossfade);
                    slideWarned = false;
                    delay = prefs.getInt("slide_seconds", 60) * 1000L;
                } else if (uri != null) {
                    // Unreadable file, or the drive was unplugged: re-scan and try again soon.
                    synchronized (slideQueue) {
                        slideQueue.clear();
                    }
                    delay = 2000;
                } else {
                    if (!slideWarned) toast(slideFolder().isEmpty() ? tr(R.string.toast_slide_no_usb)
                            : tr(R.string.toast_slide_folder_missing, slideFolderName()), false);
                    slideWarned = true;
                    delay = 30_000;
                }
                ui.postDelayed(slideTick, delay);
            });
        });
    }

    /**
     * Photos on removable drives only (every volume but the projector's own storage), skipping
     * thumbnails and other small images. Falls back to all photos if no drive is indexed.
     */
    private List<Uri> usbPhotos() {
        List<Uri> out = new ArrayList<>();
        if (checkSelfPermission(imagePermission()) != PackageManager.PERMISSION_GRANTED) return out;
        String folder = slideFolder();
        if (!folder.isEmpty() && Build.VERSION.SDK_INT >= 29) {
            // One folder and everything below it. If its drive is unplugged this finds nothing,
            // and the slideshow waits for it to come back.
            String vol = folder.substring(0, folder.indexOf('|')), rel = folder.substring(folder.indexOf('|') + 1);
            Uri col;
            try {
                col = MediaStore.Images.Media.getContentUri(vol);
            } catch (RuntimeException e) {
                return out;
            }
            String where = "(" + MediaStore.Images.Media.WIDTH + " IS NULL OR " + MediaStore.Images.Media.WIDTH
                    + " = 0 OR " + MediaStore.Images.Media.WIDTH + " >= 800) AND "
                    + MediaStore.Images.Media.RELATIVE_PATH + " LIKE ?";
            try (Cursor c = getContentResolver().query(col, new String[] {MediaStore.Images.Media._ID}, where,
                    new String[] {rel.replace("%", "\\%") + "%"}, null)) {
                while (c != null && c.moveToNext()) out.add(ContentUris.withAppendedId(col, c.getLong(0)));
            } catch (RuntimeException ignored) {
            }
            return out;
        }
        List<Uri> collections = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 29) {
            for (String v : MediaStore.getExternalVolumeNames(this)) {
                if (!MediaStore.VOLUME_EXTERNAL_PRIMARY.equals(v)) collections.add(MediaStore.Images.Media.getContentUri(v));
            }
        }
        if (collections.isEmpty()) {
            collections.add(Media.images());
        }
        String big = MediaStore.Images.Media.WIDTH + " IS NULL OR " + MediaStore.Images.Media.WIDTH
                + " = 0 OR " + MediaStore.Images.Media.WIDTH + " >= 800";
        for (Uri col : collections) {
            try (Cursor c = getContentResolver().query(col, new String[] {MediaStore.Images.Media._ID}, big, null, null)) {
                while (c != null && c.moveToNext()) out.add(ContentUris.withAppendedId(col, c.getLong(0)));
            } catch (RuntimeException ignored) {
            }
        }
        return out;
    }

    /** The next photo fades in over the current background, then replaces it (old one freed). */
    private void crossfadeTo(Bitmap bm, int duration) {
        crossfadeTo(bm, duration, true);
    }

    private void crossfadeTo(Bitmap bm, int duration, boolean photo) {
        if (wallpaper.getVisibility() != View.VISIBLE || wallpaper.getDrawable() == null) {
            showPhoto(bm, photo);
            return;
        }
        setEdges(photo);
        wallpaperNext.animate().cancel();
        wallpaperNext.setScaleType(ImageView.ScaleType.CENTER_CROP);
        wallpaperNext.setImageBitmap(bm);
        wallpaperNext.setAlpha(0f);
        wallpaperNext.setVisibility(View.VISIBLE);
        wallpaperNext.animate().alpha(1f).setDuration(duration).setInterpolator(Tokens.EASE_IN_OUT).withEndAction(() -> {
            showPhoto(bm, photo);
            wallpaperNext.setVisibility(View.GONE);
            wallpaperNext.setImageDrawable(null);
        }).start();
    }

    /**
     * Copies the photo, scaled to the screen, so it stays after the USB drive is unplugged. An
     * animated GIF or WebP is copied as it is (up to 40 MB) and plays on Home.
     */
    private void usePhoto(Uri uri) {
        loader.execute(() -> {
            if (copyIfAnimated(uri)) {
                ui.post(() -> {
                    applyBackground("anim", true);
                    toast(tr(R.string.toast_anim_set), true);
                });
                return;
            }
            Bitmap raw = decodeScaled(() -> getContentResolver().openInputStream(uri), uri);
            boolean ok = false;
            if (raw != null) {
                try (FileOutputStream out = new FileOutputStream(photoFile())) {
                    ok = raw.compress(Bitmap.CompressFormat.JPEG, 90, out);
                } catch (IOException ignored) {
                }
            }
            boolean saved = ok;
            // The saved copy stays as it was (light mode shows it as is); only the screen copy is changed.
            if (saved) wallRaw = raw;
            Bitmap bm = saved ? prepared(raw) : null;
            ui.post(() -> {
                if (!saved) {
                    toast(tr(R.string.toast_photo_unusable), false);
                    return;
                }
                prefs.edit().putString(bgKey(), "photo").apply();
                stopSlideshow();
                stopVideo();
                stopAnim();
                crossfadeTo(bm, tk.slow);
                toast(tr(R.string.toast_bg_set), true);
            });
        });
        backTo(SEC_BACKGROUND).run();
    }

    // Animated photos (GIF, animated WebP): Android's own decoder, scaled to the screen as it plays.

    private static final long MAX_ANIM_BYTES = 40L << 20;
    private android.graphics.drawable.AnimatedImageDrawable anim;

    private File animFile() {
        return new File(getFilesDir(), "background.anim");
    }

    /** Loader thread: true if {@code uri} is animated and was copied to app storage. */
    private boolean copyIfAnimated(Uri uri) {
        String type = getContentResolver().getType(uri);
        if (type == null || !(type.equals("image/gif") || type.equals("image/webp"))) return false;
        try {
            Drawable d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(getContentResolver(), uri),
                    (decoder, info, src) -> decoder.setTargetSampleSize(4));
            if (!(d instanceof android.graphics.drawable.AnimatedImageDrawable)) return false;
        } catch (IOException | RuntimeException e) {
            return false;
        }
        File tmp = new File(getFilesDir(), "background.anim.tmp");
        long total = 0;
        try (InputStream in = getContentResolver().openInputStream(uri); FileOutputStream out = new FileOutputStream(tmp)) {
            if (in == null) return false;
            byte[] buf = new byte[64 << 10];
            for (int n; (n = in.read(buf)) > 0; ) {
                total += n;
                if (total > MAX_ANIM_BYTES) return false;
                out.write(buf, 0, n);
            }
        } catch (IOException e) {
            return false;
        } finally {
            if (total > MAX_ANIM_BYTES) //noinspection ResultOfMethodCallIgnored
                tmp.delete();
        }
        return tmp.renameTo(animFile());
    }

    private void showAnim(File f) {
        int w = getResources().getDisplayMetrics().widthPixels, h = getResources().getDisplayMetrics().heightPixels;
        loader.execute(() -> {
            Drawable d;
            try {
                d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(f), (decoder, info, src) -> {
                    // Decoded at no more than screen size, so each frame is cheap to draw.
                    float s = Math.min(1f, Math.max(w / (float) info.getSize().getWidth(),
                            h / (float) info.getSize().getHeight()));
                    decoder.setTargetSize(Math.round(info.getSize().getWidth() * s),
                            Math.round(info.getSize().getHeight() * s));
                });
            } catch (IOException | RuntimeException e) {
                d = null;
            }
            Drawable result = d;
            Bitmap still = null;
            try {
                still = ImageDecoder.decodeBitmap(ImageDecoder.createSource(f), (decoder, info, src) -> {
                    decoder.setTargetSampleSize(8);
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                });
            } catch (IOException | RuntimeException ignored) {
            }
            Bitmap first = still;
            ui.post(() -> {
                if (isDestroyed() || !"anim".equals(currentBg())) return;
                if (!(result instanceof android.graphics.drawable.AnimatedImageDrawable)) {
                    toast(tr(R.string.toast_anim_unplayable), false);
                    return;
                }
                stopAnim();
                anim = (android.graphics.drawable.AnimatedImageDrawable) result;
                anim.setRepeatCount(android.graphics.drawable.AnimatedImageDrawable.REPEAT_INFINITE);
                // Smooth, not blocky, when a small GIF is stretched to the screen.
                anim.setFilterBitmap(true);
                wallpaperNext.setVisibility(View.GONE);
                wallpaper.setScaleType(ImageView.ScaleType.CENTER_CROP);
                wallpaper.setImageDrawable(anim);
                wallpaper.setVisibility(View.VISIBLE);
                wallpaper.setAlpha(1f);
                setEdges(true);
                applyAnimLook();
                updateAccent(first);
                if (isResumedNow) anim.start();
            });
        });
    }

    /** Animated photos can't be redrawn per picture, so adjustments and dimming are filters on the view. */
    private void applyAnimLook() {
        if (anim == null) return;
        Wallpaper.Adjust a = Wallpaper.Adjust.from(prefs);
        wallpaper.setColorFilter(a.none() ? null : new android.graphics.ColorMatrixColorFilter(Wallpaper.matrix(a)));
        wallpaper.setForeground((photoScrim() >>> 24) == 0 ? null : new ColorDrawable(photoScrim()));
    }

    private void stopAnim() {
        if (anim == null) return;
        ui.removeCallbacks(resumeAnim);
        anim.stop();
        // The animation itself, or the still standing in for it while you press buttons.
        wallpaper.setImageDrawable(null);
        wallpaper.setColorFilter(null);
        wallpaper.setForeground(null);
        anim = null;
    }

    // Video: Android's own player on a TextureView behind Home, muted and looping, paused whenever
    // Home isn't on screen. Not a SurfaceView: that needs the display's video overlay, and once any
    // streaming app has run this projector's decoder hands it frames the overlay refuses, so the
    // whole 4K screen falls to the GPU (about 80 ms a frame) until the next reboot.

    private TextureView videoView;
    private View videoScrim;
    private android.media.MediaPlayer player;
    private Surface videoSurface;
    private Uri videoUri;
    private boolean isResumedNow, videoWarned;

    private void showVideo(Uri uri) {
        boolean same = uri.equals(videoUri) && videoView != null;
        videoUri = uri;
        wallpaper.setVisibility(View.GONE);
        wallpaperNext.setVisibility(View.GONE);
        // The window shows through where the video is, so nothing opaque may sit behind Home's views.
        root.setBackground(null);
        getWindow().setBackgroundDrawable(null);
        edgeTop.setVisibility(theme.light ? View.VISIBLE : View.GONE);
        edgeBottom.setVisibility(theme.light ? View.VISIBLE : View.GONE);
        if (videoView == null) {
            videoView = new TextureView(this);
            root.addView(videoView, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
            videoScrim = new View(this);
            videoScrim.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            root.addView(videoScrim, 1, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            videoView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(@NonNull SurfaceTexture texture, int width, int height) {
                    startPlayer(texture);
                }

                @Override
                public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture texture, int width, int height) {
                }

                @Override
                public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture texture) {
                    releasePlayer();
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(@NonNull SurfaceTexture texture) {
                }
            });
        } else if (!same) {
            releasePlayer();
            if (videoView.isAvailable()) startPlayer(videoView.getSurfaceTexture());
        }
        videoView.setVisibility(View.VISIBLE);
        applyVideoLook();
        // Accent and a still for previews: one frame from the start of the video.
        loader.execute(() -> {
            Bitmap frame = null;
            try (android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever()) {
                r.setDataSource(this, uri);
                frame = r.getScaledFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 192, 108);
            } catch (Exception ignored) {
            }
            Bitmap f = frame;
            ui.post(() -> {
                if (f != null && uri.equals(videoUri)) updateAccent(f);
            });
        });
    }

    private void startPlayer(SurfaceTexture texture) {
        if (videoUri == null || player != null) return;
        android.media.MediaPlayer p = new android.media.MediaPlayer();
        player = p;
        try {
            p.setDataSource(this, videoUri);
            videoSurface = new Surface(texture);
            p.setSurface(videoSurface);
            p.setLooping(true);
            p.setVolume(0f, 0f);
            p.setOnPreparedListener(mp -> {
                if (player != mp) return;
                fitVideo(mp.getVideoWidth(), mp.getVideoHeight());
                if (isResumedNow) mp.start();
            });
            p.setOnErrorListener((mp, what, extra) -> {
                // The drive was unplugged or the file can't be played: show the plain background
                // and try again next time Home comes back.
                releasePlayer();
                videoFailed();
                return true;
            });
            p.prepareAsync();
        } catch (IOException | RuntimeException e) {
            releasePlayer();
            videoFailed();
        }
    }

    private void videoFailed() {
        if (videoView != null) videoView.setVisibility(View.GONE);
        root.setBackground(new ColorDrawable(theme.bg));
        if (!videoWarned) toast("Can't play this video. The file may be damaged or in an unsupported format (MP4 works best), or its USB drive is unplugged.", false);
        videoWarned = true;
    }

    /** Fills the screen like a photo: scaled up until no edge shows, centred. */
    private void fitVideo(int vw, int vh) {
        if (vw <= 0 || vh <= 0 || videoView == null) return;
        int w = root.getWidth(), h = root.getHeight();
        float s = Math.max(w / (float) vw, h / (float) vh);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) videoView.getLayoutParams();
        lp.width = Math.round(vw * s);
        lp.height = Math.round(vh * s);
        videoView.setLayoutParams(lp);
    }

    // Accent colour from the background (Customize › Look › Accent colour)

    private boolean accentAuto() {
        return prefs.getBoolean("accent_auto", false);
    }

    private void updateAccent(Bitmap bm) {
        if (accentAuto() && bm != null) setAccentSeed(Wallpaper.seedFrom(bm));
    }

    private void updateAccent(Drawable d) {
        if (accentAuto() && d != null) setAccentSeed(Wallpaper.seedFrom(d));
    }

    /** Applied live: the focus ring, the lit button and menus from now on. Kept for the ≡ panel. */
    private void setAccentSeed(int seed) {
        prefs.edit().putInt("accent_seed", seed).apply();
        if (!theme.setSeed(seed)) return;
        highlight.setTheme(theme);
        litPill = -2;
        paintPills();
        applyFocus(true);
    }

    /** Dark mode's dimming plus your brightness, as one layer over the video. */
    private void applyVideoLook() {
        if (videoScrim == null) return;
        int b = prefs.getInt("adj_b", 0);
        int dim = (photoScrim() >>> 24) - Math.round(b * 2.55f);
        if (dim >= 0) videoScrim.setBackgroundColor(Math.min(255, dim) << 24);
        else videoScrim.setBackgroundColor((Math.min(255, -dim) << 24) | 0x00FFFFFF);
        videoScrim.setVisibility(dim == 0 ? View.GONE : View.VISIBLE);
    }

    private void releasePlayer() {
        if (player == null) return;
        try {
            player.release();
        } catch (RuntimeException ignored) {
        }
        player = null;
        if (videoSurface != null) videoSurface.release();
        videoSurface = null;
    }

    private void stopVideo() {
        releasePlayer();
        videoUri = null;
        if (videoView != null) {
            root.removeView(videoView);
            root.removeView(videoScrim);
            videoView = null;
            videoScrim = null;
        }
    }

    private interface StreamSource {
        InputStream open() throws IOException;
    }

    /** Decodes at no more than screen size; ImageDecoder also applies the photo's EXIF rotation. */
    private Bitmap decodeScaled(StreamSource src, Uri uri) {
        int w = getResources().getDisplayMetrics().widthPixels, h = getResources().getDisplayMetrics().heightPixels;
        try {
            ImageDecoder.Source s = "file".equals(uri.getScheme())
                    ? ImageDecoder.createSource(new File(uri.getPath()))
                    : ImageDecoder.createSource(getContentResolver(), uri);
            return ImageDecoder.decodeBitmap(s, (decoder, info, source) -> {
                int sample = Math.max(1, Math.min(info.getSize().getWidth() / w, info.getSize().getHeight() / h));
                decoder.setTargetSampleSize(sample);
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                // Mutable, so dark mode's dimming can be drawn into it once.
                decoder.setMutableRequired(true);
            });
        } catch (IOException | RuntimeException e) {
            // A few files ImageDecoder rejects still decode the older way (without EXIF rotation).
        }
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = src.open()) {
                BitmapFactory.decodeStream(in, null, o);
            }
            o.inSampleSize = Math.max(1, Math.min(o.outWidth / w, o.outHeight / h));
            o.inJustDecodeBounds = false;
            o.inMutable = true;
            try (InputStream in = src.open()) {
                return BitmapFactory.decodeStream(in, null, o);
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    // Home layout: categories, dock, where Home starts

    private String homeSummary() {
        int n = categories.list.size();
        return n == 1 ? trn(R.plurals.home_slots, columns) : trn(R.plurals.home_categories, n);
    }

    private String startOnName() {
        Categories.Cat c = categories.find(categories.startOn);
        return c == null ? tr(R.string.set_home_dock) : catName(c);
    }

    private void openHomeMenu(int focus) {
        lastSection = SEC_HOME;
        startMenu(tr(R.string.customize), tr(R.string.menu_home));
        add(MenuView.Row.nav(R.drawable.ic_rows, tr(R.string.set_home_categories), number(categories.list.size())),
                () -> openCategoriesMenu(0));
        add(MenuView.Row.nav(R.drawable.ic_dock, tr(R.string.set_home_dock), trn(R.plurals.apps_count, dockApps.size())),
                () -> openDockMenu(0));
        add(MenuView.Row.nav(R.drawable.ic_apps, tr(R.string.set_home_layout), trn(R.plurals.layout_per_row, columns)),
                () -> openLayoutMenu(0));
        add(MenuView.Row.nav(R.drawable.ic_open, tr(R.string.set_home_home_button_goes_to), startOnName()), this::openStartOnMenu);
        add(MenuView.Row.nav(R.drawable.ic_weather, tr(R.string.set_home_weather), weatherSummary()), () -> openWeatherMenu(0));
        showSheet(focus, backTo(SEC_HOME), null);
    }

    private void openLayoutMenu(int focus) {
        startMenu(crumb(tr(R.string.menu_home)), tr(R.string.menu_home_layout));
        add(MenuView.Row.nav(0, tr(R.string.set_home_layout_tiles_per_row), number(columns)), () -> layoutChoice(0,
                tr(R.string.set_home_layout_tiles_per_row), new String[] {tr(R.string.layout_columns_largest, number(4)),
                        number(5), number(6), tr(R.string.layout_columns_smallest, number(7))}, "columns", columns - 4, 4));
        add(MenuView.Row.nav(0, tr(R.string.set_home_layout_tile_spacing), tr(SPACING[pref("gap", 1, 2)])), () -> layoutChoice(1,
                tr(R.string.set_home_layout_tile_spacing), trAll(SPACING), "gap", pref("gap", 1, 2), 0));
        add(MenuView.Row.nav(0, tr(R.string.set_home_layout_row_spacing), tr(SPACING[rowGap])), () -> layoutChoice(2,
                tr(R.string.set_home_layout_row_spacing), trAll(SPACING), "row_gap", rowGap, 0));
        add(MenuView.Row.nav(0, tr(R.string.set_home_layout_side_margins), tr(MARGINS[pref("margin", 0, 2)])), () -> layoutChoice(3,
                tr(R.string.set_home_layout_side_margins), trAll(MARGIN_OPTIONS), "margin", pref("margin", 0, 2), 0));
        add(MenuView.Row.action(R.drawable.ic_reset, tr(R.string.set_home_layout_reset_layout)), () -> {
            prefs.edit().remove("columns").remove("gap").remove("row_gap").remove("margin").apply();
            relayout();
            reloadApps();
            toast(tr(R.string.toast_layout_reset), true);
            openLayoutMenu(4);
        });
        showSheet(focus, () -> openHomeMenu(2), null);
    }

    /** A layout choice that shows on screen as you pause on it; Back puts the old one back. */
    private void layoutChoice(int row, String title, String[] options, String key, int current, int offset) {
        openChoice(crumb(tr(R.string.menu_home), tr(R.string.menu_home_layout)), title, options, current, i -> {
            cancelPhotoPreview();
            prefs.edit().putInt(key, i + offset).apply();
            relayout();
            // Banners are drawn at tile size, so draw them again at the new size.
            reloadApps();
            toast(tr(R.string.toast_setting_value, title, options[i]), true);
            openLayoutMenu(row);
        }, i -> {
            cancelPhotoPreview();
            pendingPreview = () -> {
                if (prefs.getInt(key, -1) == i + offset) return;
                prefs.edit().putInt(key, i + offset).apply();
                relayout();
            };
            // Back (and returning to the current option) puts it back at once; others wait for a pause.
            if (i == current) pendingPreview.run();
            else ui.postDelayed(pendingPreview, tk.spinnerDelay);
        }, () -> openLayoutMenu(row));
    }

    private void openStartOnMenu() {
        List<String> names = new ArrayList<>(Collections.singletonList(tr(R.string.set_home_dock)));
        List<String> ids = new ArrayList<>(Collections.singletonList(Categories.DOCK));
        for (Categories.Cat c : categories.list) {
            names.add(catName(c));
            ids.add(c.id);
        }
        openChoice(crumb(tr(R.string.menu_home)), tr(R.string.set_home_home_button_goes_to), names.toArray(new String[0]),
                ids.indexOf(categories.startOn), i -> {
                    categories.startOn = ids.get(i);
                    categories.save(prefs);
                    toast(tr(R.string.toast_home_goes_to, names.get(i)), true);
                    openHomeMenu(2);
                }, null, () -> openHomeMenu(2));
    }


    private void openCategoriesMenu(int focus) {
        startMenu(categoriesCrumb(), tr(R.string.menu_home_categories));
        for (Categories.Cat c : categories.list) {
            int n = 0;
            for (Section s : sections) if (s.cat == c) n = s.apps.size();
            add(MenuView.Row.nav(0, catName(c), trn(R.plurals.apps_count, n)), () -> openCategoryMenu(c, 0));
        }
        add(MenuView.Row.action(R.drawable.ic_add, tr(R.string.set_home_categories_new_category)), () -> askText(categoriesCrumb(),
                tr(R.string.category_new_title), tr(R.string.category_new_hint), "", InputType.TYPE_TEXT_FLAG_CAP_WORDS, name -> {
                    Categories.Cat c = categories.create(name);
                    categories.save(prefs);
                    rebuildLists();
                    toast(tr(R.string.toast_category_added, name), true);
                    openCategoryApps(c, 0);
                }, () -> openCategoriesMenu(categories.list.size())));
        int base = categories.list.size() + 1;
        add(MenuView.Row.action(0, tr(R.string.set_home_categories_all_apps_row)).value(onOff(categories.allAppsRow)), () -> {
            categories.allAppsRow = !categories.allAppsRow;
            categories.save(prefs);
            rebuildLists();
            openCategoriesMenu(base);
        });
        MenuView.Row inputsRow = add(MenuView.Row.action(0, tr(R.string.set_home_categories_inputs_row_hdmi)).value(onOff(categories.inputsRow)), () -> {
            categories.inputsRow = !categories.inputsRow;
            categories.save(prefs);
            rebuildLists();
            openCategoriesMenu(base + 1);
        });
        if (!hasInputs) inputsRow.disabled(tr(R.string.categories_no_inputs));
        add(MenuView.Row.action(0, tr(R.string.set_home_categories_empty_categories))
                .value(tr(categories.showEmpty ? R.string.shown : R.string.hidden)), () -> {
            categories.showEmpty = !categories.showEmpty;
            categories.save(prefs);
            rebuildLists();
            openCategoriesMenu(base + 2);
        });
        MenuView.Row reset = add(MenuView.Row.nav(R.drawable.ic_reset, tr(R.string.set_home_categories_reset_categories), ""), () -> {
            startMenu(crumbOf(categoriesCrumb(), tr(R.string.menu_home_categories)), tr(R.string.categories_reset_title));
            add(MenuView.Row.note(R.drawable.ic_info, tr(R.string.categories_reset_note)), null);
            add(MenuView.Row.action(R.drawable.ic_close, tr(R.string.action_cancel)), () -> openCategoriesMenu(base + 3));
            add(MenuView.Row.action(R.drawable.ic_reset, tr(R.string.set_home_categories_reset_categories)), () -> {
                autoBackup("Before category reset");
                prefs.edit().remove("categories").remove("sort").remove("grid_order").apply();
                categories = Categories.load(prefs);
                rebuildLists();
                toast(tr(R.string.toast_categories_reset), true);
                openCategoriesMenu(0);
            });
            // Cancel is preselected.
            showSheet(1, () -> openCategoriesMenu(base + 3), null);
        });
        if (categories.list.size() == 1 && !categories.allAppsRow && !categories.inputsRow
                && Categories.SORT_AZ.equals(categories.list.get(0).sort)) {
            reset.disabled(tr(R.string.already_default));
        }
        showSheet(focus, () -> openHomeMenu(0), null);
    }

    private void openCategoryMenu(Categories.Cat c, int focus) {
        String crumb = crumbOf(categoriesCrumb(), tr(R.string.menu_home_categories));
        startMenu(crumb, catName(c));
        Section shown = null;
        for (Section s : sections) if (s.cat == c) shown = s;
        int count = shown == null ? 0 : shown.apps.size();
        MenuView.Row apps = add(MenuView.Row.nav(R.drawable.ic_apps, tr(R.string.category_apps_in_it), number(count)),
                () -> openCategoryApps(c, 0));
        if (c.isApps()) apps.disabled(tr(R.string.category_apps_note));
        add(MenuView.Row.nav(R.drawable.ic_check, tr(R.string.category_sort), tr(c.sortName())), () -> {
            int cur = Arrays.asList(Categories.SORTS).indexOf(c.sort);
            openChoice(crumb, tr(R.string.category_sort_title, catName(c)), sortNames(), cur, i -> {
                c.sort = Categories.SORTS[i];
                categories.save(prefs);
                rebuildLists();
                toast(tr(SORTED_TOASTS[i], catName(c)), true);
                openCategoryMenu(c, 1);
            }, null, () -> openCategoryMenu(c, 1));
        });
        add(MenuView.Row.nav(R.drawable.ic_rows, tr(R.string.category_layout), tr(c.singleRow ? R.string.category_one_row
                        : R.string.category_grid)), () ->
                openChoice(crumb, tr(R.string.category_layout_title, catName(c)),
                        trAll(R.string.category_grid_long, R.string.category_one_row_long),
                        c.singleRow ? 1 : 0, i -> {
                            c.singleRow = i == 1;
                            categories.save(prefs);
                            rebuildLists();
                            openCategoryMenu(c, 2);
                        }, null, () -> openCategoryMenu(c, 2)));
        Section target = shown;
        MenuView.Row arrange = add(MenuView.Row.action(R.drawable.ic_move, tr(R.string.category_rearrange)), () -> {
            area = GRID;
            gridSec = sections.indexOf(target);
            gridIndex = 0;
            target.first = 0;
            startMove();
        });
        if (count == 0) arrange.disabled(tr(R.string.category_no_apps_yet));
        add(MenuView.Row.nav(R.drawable.ic_rename, tr(R.string.rename_title), ""), () -> askText(crumb,
                tr(R.string.category_rename_title, catName(c)), tr(R.string.qa_name), catName(c),
                InputType.TYPE_TEXT_FLAG_CAP_WORDS, name -> {
                    c.name = name;
                    categories.save(prefs);
                    rebuildLists();
                    toast(tr(R.string.toast_renamed, name), true);
                    openCategoryMenu(c, 4);
                }, () -> openCategoryMenu(c, 4)));
        int pos = categories.list.indexOf(c);
        add(MenuView.Row.nav(R.drawable.ic_order, tr(R.string.category_position),
                tr(R.string.position_of, number(pos + 1), number(categories.list.size()))), () -> {
            String[] options = new String[categories.list.size()];
            for (int i = 0; i < options.length; i++) options[i] = i == 0 ? tr(R.string.position_top, number(1)) : number(i + 1);
            openChoice(crumb, tr(R.string.category_position_title, catName(c)), options, pos, i -> {
                categories.list.remove(c);
                categories.list.add(i, c);
                categories.save(prefs);
                rebuildLists();
                openCategoryMenu(c, 5);
            }, null, () -> openCategoryMenu(c, 5));
        });
        if (!c.isApps()) {
            add(MenuView.Row.nav(R.drawable.ic_trash, tr(R.string.category_delete), ""), () -> {
                startMenu(crumb, tr(R.string.category_delete_title, catName(c)));
                add(MenuView.Row.note(R.drawable.ic_info, tr(R.string.category_delete_note)), null);
                add(MenuView.Row.action(R.drawable.ic_close, tr(R.string.action_cancel)), () -> openCategoryMenu(c, 6));
                add(MenuView.Row.action(R.drawable.ic_trash, tr(R.string.category_delete_action, catName(c))), () -> {
                    categories.delete(c);
                    categories.save(prefs);
                    rebuildLists();
                    toast(tr(R.string.toast_category_deleted, catName(c)), true);
                    openCategoriesMenu(0);
                });
                // Cancel is preselected.
                showSheet(1, () -> openCategoryMenu(c, 6), null);
            });
        }
        showSheet(focus, () -> openCategoriesMenu(Math.max(0, categories.list.indexOf(c))), null);
    }

    /** Ticked apps are in the category; OK adds or takes one out (back to Apps). */
    private void openCategoryApps(Categories.Cat c, int focus) {
        startMenu(crumbOf(categoriesCrumb(), catName(c)), tr(R.string.category_apps_in_title, catName(c)));
        Set<String> hidden = hiddenApps();
        for (int i = 0; i < allApps.size(); i++) {
            AppRepo.App a = allApps.get(i);
            Categories.Cat in = categories.of(a.pkg);
            boolean mine = in == c;
            int n = i;
            // Docked apps can be added too; they show in the category once they leave the dock.
            String where = hidden.contains(a.pkg) ? tr(R.string.hidden) : dockApps.contains(a) ? tr(R.string.in_dock)
                    : !mine && !in.isApps() ? catName(in) : "";
            add(MenuView.Row.choice(a.label, mine).value(where), () -> {
                categories.moveApp(a.pkg, mine ? categories.find(Categories.APPS) : c, -1);
                categories.save(prefs);
                rebuildLists();
                openCategoryApps(c, n);
            });
        }
        if (allApps.isEmpty()) add(MenuView.Row.loading(tr(R.string.loading_apps)), null);
        showSheet(focus, () -> openCategoryMenu(c, 0), null);
        menu.setHints(tr(R.string.key_ok), tr(R.string.hint_add_remove), tr(R.string.key_back), tr(R.string.hint_back));
    }

    // Dock

    private void openDockMenu(int focus) {
        lastSection = SEC_HOME;
        startMenu(crumb(tr(R.string.menu_home)), tr(R.string.menu_home_dock));
        add(MenuView.Row.note(R.drawable.ic_info, tr(R.string.dock_note)), null);
        MenuView.Row rearrange = add(MenuView.Row.action(0, tr(R.string.set_home_dock_rearrange)), () -> {
            area = DOCK;
            dockIndex = Math.min(dockIndex, dockApps.size() - 1);
            startMove();
        });
        if (dockApps.isEmpty()) rearrange.disabled(tr(R.string.dock_empty));
        add(MenuView.Row.action(0, tr(R.string.set_home_dock_reset_dock_to_default)), () -> {
            prefs.edit().remove("dock_order").remove("dock_config").apply();
            rebuildLists();
            toast(tr(R.string.toast_dock_reset), true);
        });
        showSheet(focus, () -> openHomeMenu(1), null);
    }

    // Apps

    private int hiddenCount() {
        int hidden = 0;
        Set<String> h = hiddenApps();
        for (AppRepo.App a : allApps) if (h.contains(a.pkg)) hidden++;
        return hidden;
    }

    private String appsSummary() {
        int hidden = hiddenCount();
        return hidden > 0 ? trn(R.plurals.apps_hidden, hidden) : tr(R.string.apps_all_shown);
    }

    private void openAppsMenu(int focus) {
        lastSection = SEC_APPS;
        startMenu(tr(R.string.customize), tr(R.string.menu_apps));
        add(MenuView.Row.nav(0, tr(R.string.set_apps_show_or_hide_apps), trn(R.plurals.apps_hidden, hiddenCount())), () -> openHideMenu(0));
        MenuView.Row arrange = add(MenuView.Row.action(0, tr(R.string.set_apps_arrange_apps)), () -> {
            area = GRID;
            enterSection(nextSection(-1, 1, false), 0, true);
            startMove();
        });
        if (!hasGrid()) arrange.disabled(tr(R.string.cust_nothing_to_arrange));
        add(MenuView.Row.nav(0, tr(R.string.set_apps_sort_and_categories), ""), () -> openCategoriesMenu(0));
        List<IconPacks.Pack> packs = IconPacks.installed(this);
        String packPkg = prefs.getString("icon_pack", "");
        String packName = tr(R.string.icon_pack_none);
        for (IconPacks.Pack p : packs) if (p.pkg.equals(packPkg)) packName = p.label;
        add(MenuView.Row.nav(0, tr(R.string.set_apps_icon_pack), packName), () -> openIconPackMenu(packs));
        int changed = json("app_names").length() + json("app_tiles").length() + json("app_icons").length();
        MenuView.Row reset = add(MenuView.Row.action(R.drawable.ic_reset, tr(R.string.set_apps_reset_names_tiles_and_icons)), () -> {
            prefs.edit().remove("app_names").remove("app_tiles").remove("app_icons").apply();
            deleteRecursively(new File(getFilesDir(), "icons"));
            reloadApps();
            toast(tr(R.string.toast_apps_reset), true);
            openAppsMenu(4);
        });
        if (changed == 0) reset.disabled(tr(R.string.apps_nothing_to_reset));
        showSheet(focus, backTo(SEC_APPS), null);
    }

    /** One pack for every app it has an icon for; apps it doesn't cover keep their own. */
    private void openIconPackMenu(List<IconPacks.Pack> packs) {
        String crumb = crumb(tr(R.string.menu_apps));
        startMenu(crumb, tr(R.string.icon_pack));
        String cur = prefs.getString("icon_pack", "");
        add(MenuView.Row.choice(tr(R.string.icon_pack_none_long), cur.isEmpty()), () -> setIconPack("", ""));
        int focus = 0;
        for (IconPacks.Pack p : packs) {
            if (p.pkg.equals(cur)) focus = pendingRows.size();
            add(MenuView.Row.choice(p.label, p.pkg.equals(cur)), () -> setIconPack(p.pkg, p.label));
        }
        if (packs.isEmpty()) add(MenuView.Row.note(R.drawable.ic_info, tr(R.string.icon_pack_none_installed)), null);
        showSheet(focus, () -> openAppsMenu(3), null);
    }

    private void setIconPack(String pkg, String name) {
        prefs.edit().putString("icon_pack", pkg).apply();
        IconPacks.clear();
        reloadApps();
        toast(pkg.isEmpty() ? tr(R.string.toast_icon_pack_off) : tr(R.string.toast_icon_pack_on, name), true);
        openAppsMenu(3);
    }

    private static void deleteRecursively(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursively(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    /** Ticked apps show on Home; OK toggles one. */
    private void openHideMenu(int focus) {
        startMenu(crumb(tr(R.string.menu_apps)), tr(R.string.hide_title));
        Set<String> hidden = hiddenApps();
        for (int i = 0; i < allApps.size(); i++) {
            AppRepo.App a = allApps.get(i);
            boolean isHidden = hidden.contains(a.pkg);
            int n = i;
            add(MenuView.Row.choice(a.label, !isHidden).value(isHidden ? tr(R.string.hidden) : ""), () -> {
                setHidden(a.pkg, !isHidden);
                openHideMenu(n);
            });
        }
        if (allApps.isEmpty()) add(MenuView.Row.loading(tr(R.string.loading_apps)), null);
        showSheet(focus, () -> {
            if (custMode == CUST_QUICK) openDockArrangeMenu(2);
            else openAppsMenu(0);
        }, null);
        menu.setHints(tr(R.string.key_ok), tr(R.string.hint_show_hide), tr(R.string.key_back), tr(R.string.hint_back));
    }

    // Weather

    private boolean fahrenheit() {
        String def = Arrays.asList("US", "LR", "MM").contains(Locale.getDefault().getCountry()) ? "f" : "c";
        return "f".equals(prefs.getString("weather_units", def));
    }

    private String weatherSummary() {
        if (!prefs.getBoolean("weather_on", true)) return tr(R.string.off);
        return prefs.getString("weather_city", tr(R.string.weather_set_city));
    }

    private void openWeatherMenu(int focus) {
        lastSection = SEC_HOME;
        startMenu(crumb(tr(R.string.menu_home)), tr(R.string.menu_home_weather));
        boolean on = prefs.getBoolean("weather_on", true);
        add(MenuView.Row.action(0, tr(R.string.set_home_weather_show_weather)).value(onOff(on)), () -> {
            prefs.edit().putBoolean("weather_on", !on).apply();
            refreshWeather(true);
            openWeatherMenu(0);
        });
        add(MenuView.Row.nav(0, tr(R.string.set_home_weather_city), prefs.getString("weather_city", tr(R.string.not_set))), this::askCity);
        add(MenuView.Row.action(0, tr(R.string.set_home_weather_units)).value(tr(fahrenheit() ? R.string.unit_f : R.string.unit_c)), () -> {
            prefs.edit().putString("weather_units", fahrenheit() ? "c" : "f").apply();
            refreshWeather(true);
            openWeatherMenu(2);
        });
        add(MenuView.Row.action(R.drawable.ic_reset, tr(R.string.set_home_weather_reset_weather)), () -> {
            prefs.edit().remove("weather_on").remove("weather_city").remove("weather_lat").remove("weather_lon")
                    .remove("weather_units").remove("w_code").remove("weather_time").remove("weather_try").apply();
            refreshWeather(true);
            toast(tr(R.string.toast_weather_reset), true);
            openWeatherMenu(3);
        });
        showSheet(focus, () -> openHomeMenu(4), null);
    }

    // Lock & remote: the PIN, what it locks and when, guest mode, and hold shortcuts on the remote.

    private String lockSummary() {
        if (Lock.guest(this)) return tr(R.string.set_lock_guest_mode);
        return Lock.hasPin(this) ? tr(R.string.lock_pin_on) : "";
    }

    private void openLockMenu(int focus) {
        lastSection = SEC_LOCK;
        String crumb = crumb(tr(R.string.menu_lock));
        startMenu(tr(R.string.customize), tr(R.string.menu_lock));
        boolean pin = Lock.hasPin(this);
        add(MenuView.Row.nav(R.drawable.ic_lock, tr(R.string.set_lock_pin_lock), onOff(pin)), this::openPinMenu);
        MenuView.Row what = add(MenuView.Row.nav(0, tr(R.string.set_lock_what_it_locks), lockWhatSummary()), () -> openLockWhat(0));
        MenuView.Row when = add(MenuView.Row.nav(0, tr(R.string.set_lock_when), scheduleName()), this::openLockWhen);
        if (!pin) {
            what.disabled(tr(R.string.lock_set_pin_first));
            when.disabled(tr(R.string.lock_set_pin_first));
        }
        boolean guest = Lock.guest(this);
        add(MenuView.Row.action(0, tr(R.string.set_lock_guest_mode)).value(onOff(guest)), () -> {
            prefs.edit().putBoolean("guest", !guest).apply();
            menuSound = guest ? Sounds.OFF : Sounds.ON;
            applyBarLook();
            toast(tr(!guest ? R.string.toast_guest_on : R.string.toast_guest_off), true);
            if (!guest) dismissMenuNow();
            else openLockMenu(3);
        });
        add(MenuView.Row.nav(R.drawable.ic_settings, tr(R.string.set_lock_remote_shortcuts), shortcutsSummary()), () -> openShortcutsMenu(0));
        if (!PanelService.isRunning()) {
            // Locked apps, the shortcuts and the PIN over Settings all live in the ≡ helper.
            add(MenuView.Row.note(R.drawable.ic_info, tr(R.string.lock_helper_off)), null);
        }
        add(MenuView.Row.note(R.drawable.ic_info, tr(R.string.lock_forgot_pin)), null);
        showSheet(focus, backTo(SEC_LOCK), null);
    }

    private void dismissMenuNow() {
        leaveMenu();
        area = DOCK;
        dismissMenu();
    }

    private void openPinMenu() {
        String crumb = crumb(tr(R.string.menu_lock));
        startMenu(crumb, tr(R.string.set_lock_pin_lock));
        boolean pin = Lock.hasPin(this);
        add(MenuView.Row.action(R.drawable.ic_lock, tr(pin ? R.string.lock_change_pin : R.string.lock_set_pin)), () -> setNewPin(null));
        if (pin) {
            add(MenuView.Row.action(R.drawable.ic_trash, tr(R.string.lock_turn_off)), () -> {
                Lock.clear(this);
                applyBarLook();
                PanelService.locksChanged();
                toast(tr(R.string.toast_pin_off), true);
                openLockMenu(0);
            });
        }
        add(MenuView.Row.note(R.drawable.ic_info, tr(R.string.lock_pin_note)), null);
        showSheet(0, () -> openLockMenu(0), null);
    }

    /** Asks for the new PIN twice; {@code first} is the first entry once given. */
    private void setNewPin(String first) {
        Dialog dialog = new Dialog(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
        PinPad[] pad = new PinPad[1];
        pad[0] = new PinPad(this, theme, crumb(tr(R.string.menu_lock)),
                tr(first == null ? R.string.pin_new : R.string.pin_again),
                tr(first == null ? R.string.pin_new_hint : R.string.pin_again_hint), new PinPad.Listener() {
            @Override
            public void done(String pin) {
                dialog.dismiss();
                if (first == null) {
                    setNewPin(pin);
                } else if (first.equals(pin)) {
                    Lock.setPin(MainActivity.this, pin);
                    PanelService.locksChanged();
                    toast(tr(R.string.toast_pin_set), true);
                    openLockMenu(0);
                } else {
                    toast(tr(R.string.toast_pin_mismatch), false);
                    openPinMenu();
                }
            }

            @Override
            public void cancel() {
                dialog.dismiss();
                openPinMenu();
            }
        });
        dialog.setContentView(pad[0]);
        dialog.setOnKeyListener((d, code, e) -> pad[0].key(e));
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0));
            w.setDimAmount(0.6f);
        }
        dialog.show();
    }

    private String lockWhatSummary() {
        int apps = 0;
        for (String s : Lock.lockedApps(this)) if (!s.isEmpty()) apps++;
        for (String s : Lock.lockedCats(this)) if (!s.isEmpty()) apps++;
        boolean settings = prefs.getBoolean("lock_settings", true);
        String more = apps > 0 ? trn(R.plurals.lock_more, apps) : "";
        if (settings && apps > 0) return tr(R.string.lock_what_and, tr(R.string.settings), more);
        return settings ? tr(R.string.settings) : more;
    }

    private void openLockWhat(int focus) {
        String crumb = crumb(tr(R.string.menu_lock));
        startMenu(crumb, tr(R.string.set_lock_what_it_locks));
        boolean settings = prefs.getBoolean("lock_settings", true);
        add(MenuView.Row.choice(tr(R.string.lock_customize_and_settings), settings), () -> {
            prefs.edit().putBoolean("lock_settings", !settings).apply();
            openLockWhat(0);
        });
        Set<String> cats = Lock.lockedCats(this);
        for (Categories.Cat c : categories.list) {
            add(MenuView.Row.choice(catName(c), cats.contains(c.id)).value(tr(R.string.qa_category)), () -> {
                Lock.toggle(this, "locked_cats", c.id);
                PanelService.locksChanged();
                openLockWhat(menu.focus());
            });
        }
        Set<String> apps = Lock.lockedApps(this);
        for (AppRepo.App a : allApps) {
            add(MenuView.Row.choice(a.label, apps.contains(a.pkg)), () -> {
                Lock.toggle(this, "locked_apps", a.pkg);
                PanelService.locksChanged();
                openLockWhat(menu.focus());
            });
        }
        showSheet(focus, () -> openLockMenu(1), null);
        menu.setHints(tr(R.string.key_ok), tr(R.string.hint_lock_unlock), tr(R.string.key_back), tr(R.string.hint_back));
    }

    // Minutes after midnight, from-to (it may wrap past midnight).
    private static final String[] SCHEDULES = {"", "1260-420", "1200-480"};

    /** "21:00 to 7:00" for a schedule, in the language's digits (always 24-hour, as the lock works). */
    private String scheduleName(int i) {
        if (i <= 0 || i >= SCHEDULES.length) return tr(R.string.lock_always);
        String[] range = SCHEDULES[i].split("-");
        return tr(R.string.lock_time_range, minutesText(Integer.parseInt(range[0])), minutesText(Integer.parseInt(range[1])));
    }

    /** Minutes after midnight as "7:00" or "21:00". */
    private static String minutesText(int minutes) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, minutes / 60);
        c.set(Calendar.MINUTE, minutes % 60);
        return new SimpleDateFormat("H:mm", Locale.getDefault()).format(c.getTime());
    }

    private String scheduleName() {
        return scheduleName(Arrays.asList(SCHEDULES).indexOf(prefs.getString("lock_sched", "")));
    }

    private void openLockWhen() {
        int cur = Math.max(0, Arrays.asList(SCHEDULES).indexOf(prefs.getString("lock_sched", "")));
        openChoice(crumb(tr(R.string.menu_lock)), tr(R.string.lock_when_title), new String[] {tr(R.string.lock_always),
                tr(R.string.lock_bedtime, scheduleName(1)), tr(R.string.lock_bedtime, scheduleName(2))}, Math.min(cur, 2), i -> {
                    prefs.edit().putString("lock_sched", SCHEDULES[i]).apply();
                    PanelService.locksChanged();
                    toast(i == 0 ? tr(R.string.toast_lock_always) : tr(R.string.toast_lock_from, scheduleName(i)), true);
                    openLockMenu(2);
                }, null, () -> openLockMenu(2));
    }

    // Remote shortcuts: what holding ≡ or the Settings (gear) button does. Off by default.

    static final String[] HOLD_ACTIONS = {"", "home", "customize", "search", "next_bg", "sleep30"};
    private static final int[] HOLD_NAMES = {R.string.hold_nothing, R.string.hold_home, R.string.hold_customize,
            R.string.hold_search, R.string.hold_next_bg, R.string.hold_sleep30};
    /** After picking one, in the same order: "Hold ≡: go to home". */
    private static final int[] HOLD_TOASTS = {R.string.toast_hold_nothing, R.string.toast_hold_home,
            R.string.toast_hold_customize, R.string.toast_hold_search, R.string.toast_hold_next_bg,
            R.string.toast_hold_sleep30};

    private String holdName(String key) {
        int i = Arrays.asList(HOLD_ACTIONS).indexOf(prefs.getString(key, ""));
        return tr(HOLD_NAMES[Math.max(0, i)]);
    }

    private String shortcutsSummary() {
        return onOff(!(prefs.getString("hold_menu", "").isEmpty() && prefs.getString("hold_gear", "").isEmpty()));
    }

    private void openShortcutsMenu(int focus) {
        String crumb = crumb(tr(R.string.menu_lock));
        startMenu(crumb, tr(R.string.menu_lock_shortcuts));
        add(MenuView.Row.nav(0, tr(R.string.set_lock_shortcuts_hold), holdName("hold_menu")),
                () -> openHoldChoice("hold_menu", tr(R.string.set_lock_shortcuts_hold), 0));
        add(MenuView.Row.nav(0, tr(R.string.set_lock_shortcuts_hold_the_settings_button), holdName("hold_gear")),
                () -> openHoldChoice("hold_gear", tr(R.string.set_lock_shortcuts_hold_the_settings_button), 1));
        boolean homeHold = prefs.getBoolean("hold_home_search", true);
        MenuView.Row home = add(MenuView.Row.action(0, tr(R.string.set_lock_shortcuts_hold_home_for_search)).value(onOff(homeHold)), () -> {
            prefs.edit().putBoolean("hold_home_search", !homeHold).apply();
            menuSound = homeHold ? Sounds.OFF : Sounds.ON;
            openShortcutsMenu(2);
        });
        if (!PanelService.isRunning()) home.disabled(tr(R.string.shortcuts_needs_helper));
        add(MenuView.Row.note(R.drawable.ic_info, tr(R.string.shortcuts_note)), null);
        showSheet(focus, () -> openLockMenu(4), null);
    }

    private void openHoldChoice(String key, String title, int row) {
        int cur = Math.max(0, Arrays.asList(HOLD_ACTIONS).indexOf(prefs.getString(key, "")));
        openChoice(crumb(tr(R.string.menu_lock), tr(R.string.menu_lock_shortcuts)), title, trAll(HOLD_NAMES), cur, i -> {
            prefs.edit().putString(key, HOLD_ACTIONS[i]).apply();
            toast(tr(HOLD_TOASTS[i], title), true);
            openShortcutsMenu(row);
        }, null, () -> openShortcutsMenu(row));
    }

    /** Next background, for a remote shortcut: the next slide, or the next shape or colour. */
    void nextBackground() {
        String bg = currentBg();
        if ("slideshow".equals(bg)) {
            ui.removeCallbacks(slideTick);
            nextSlide();
        } else if (bg.startsWith("shape:")) {
            applyBackground("shape:" + (Integer.parseInt(bg.substring(6)) + 1) % Backdrops.NAMES.length, true);
        } else if (bg.startsWith("preset:") || bg.isEmpty()) {
            int from = theme.light ? FIRST_LIGHT_PRESET : 0, to = theme.light ? PRESETS.length : FIRST_LIGHT_PRESET;
            int cur = bg.isEmpty() ? from : Integer.parseInt(bg.substring(7));
            applyBackground("preset:" + (cur + 1 >= to ? from : cur + 1), true);
        } else {
            toast(tr(R.string.toast_next_bg_unsupported), false);
        }
    }

    // ---- PIN ------------------------------------------------------------------------------

    private int wrongPins;
    private long pinWaitUntil;

    /** Runs {@code then} straight away, or after the right PIN if a lock applies now. */
    private void requirePin(boolean locked, String title, Runnable then) {
        if (!locked) {
            then.run();
            return;
        }
        Dialog dialog = new Dialog(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
        PinPad[] pad = new PinPad[1];
        pad[0] = new PinPad(this, theme, null, title, tr(R.string.pin_enter), new PinPad.Listener() {
            @Override
            public void done(String pin) {
                if (System.currentTimeMillis() < pinWaitUntil) {
                    pad[0].error(tr(R.string.pin_too_many));
                    return;
                }
                if (Lock.check(MainActivity.this, pin)) {
                    wrongPins = 0;
                    dialog.dismiss();
                    then.run();
                } else {
                    // Five wrong in a row: a short wait, so guessing all 10,000 takes days.
                    if (++wrongPins >= 5) {
                        wrongPins = 0;
                        pinWaitUntil = System.currentTimeMillis() + 30_000;
                    }
                    pad[0].error(tr(R.string.pin_wrong));
                }
            }

            @Override
            public void cancel() {
                dialog.dismiss();
            }
        });
        dialog.setContentView(pad[0]);
        dialog.setOnKeyListener((d, code, e) -> pad[0].key(e));
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0));
            w.setDimAmount(0.6f);
        }
        dialog.show();
    }

    private boolean customizeLocked() {
        return Lock.settingsLocked(this);
    }

    /** Hold OK in guest mode, or with Customize locked: only Open. */
    private boolean layoutLocked() {
        return Lock.guest(this) || Lock.settingsLocked(this);
    }

    /** City by name with the on-screen keyboard (this projector has no location of its own). */
    private void askCity() {
        askText(crumb(tr(R.string.menu_home_weather)), tr(R.string.weather_your_city), tr(R.string.weather_city_hint),
                prefs.getString("weather_city", ""),
                InputType.TYPE_TEXT_FLAG_CAP_WORDS, IME_SEARCH, this::searchCity, () -> openWeatherMenu(1));
    }

    private static final int IME_DONE = 0, IME_SEARCH = 1;

    private void askText(String crumb, String titleText, String hint, String initial, int flags,
                         java.util.function.Consumer<String> done, Runnable cancelled) {
        askText(crumb, titleText, hint, initial, flags, IME_DONE, done, cancelled);
    }

    /**
     * A line of text with the on-screen keyboard, in a card drawn like the menus instead of a stock
     * dialog. Empty text counts as cancelled (except for a search, which handles it itself).
     */
    private void askText(String crumb, String titleText, String hint, String initial, int flags, int ime,
                         java.util.function.Consumer<String> done, Runnable cancelled) {
        Dialog dialog = new Dialog(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(tk.space5, tk.space5, tk.space5, tk.space4);
        card.setBackground(Ui.rounded(tk.radiusLg, theme.surface, theme.stroke, tk.stroke));
        TextView crumbView = Ui.text(this, crumb.toUpperCase(Locale.getDefault()), tk.textCaption,
                theme.textTertiary, tk.medium500);
        crumbView.setLetterSpacing(0.06f);
        card.addView(crumbView);
        TextView title = Ui.text(this, titleText, tk.textHeadline, theme.text, tk.semibold);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = tk.space1;
        tlp.bottomMargin = tk.space4;
        card.addView(title, tlp);
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(hint);
        input.setText(initial);
        input.setSelectAllOnFocus(true);
        input.setTextSize(TypedValue.COMPLEX_UNIT_PX, tk.textBody);
        input.setTypeface(tk.regular);
        input.setTextColor(theme.text);
        input.setHintTextColor(theme.textTertiary);
        input.setPadding(tk.space4, tk.space3, tk.space4, tk.space3);
        input.setBackground(Ui.rounded(tk.radiusMd, theme.surfaceRaised, theme.accent, tk.focusRing / 2 + tk.stroke));
        input.setInputType(InputType.TYPE_CLASS_TEXT | flags);
        input.setImeOptions(ime == IME_SEARCH ? EditorInfo.IME_ACTION_SEARCH : EditorInfo.IME_ACTION_DONE);
        card.addView(input, new LinearLayout.LayoutParams(tk.sheetWidth, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = tk.space4;
        card.addView(ime == IME_SEARCH
                ? Ui.hints(this, theme, tk, tr(R.string.key_search), tr(R.string.hint_look_up), tr(R.string.key_back), tr(R.string.hint_cancel))
                : Ui.hints(this, theme, tk, tr(R.string.key_done), tr(R.string.hint_save), tr(R.string.key_back), tr(R.string.hint_cancel)), hlp);
        dialog.setContentView(card);
        boolean[] finished = {false};
        input.setOnEditorActionListener((v, actionId, ev) -> {
            String text = input.getText().toString().trim();
            if (text.isEmpty() && ime != IME_SEARCH) return true;
            finished[0] = true;
            dialog.dismiss();
            done.accept(text);
            return true;
        });
        dialog.setOnDismissListener(d -> {
            if (!finished[0] && cancelled != null) cancelled.run();
        });
        dialog.setOnShowListener(d -> input.requestFocus());
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0));
            w.setDimAmount(0.5f);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
            w.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            WindowManager.LayoutParams a = w.getAttributes();
            a.y = tk.safeV * 2;
            w.setAttributes(a);
        }
        dialog.show();
    }

    private void searchCity(String query) {
        String q = query.trim();
        if (q.isEmpty()) {
            openWeatherMenu(1);
            return;
        }
        String crumb = crumb(tr(R.string.menu_home_weather));
        startMenu(crumb, tr(R.string.weather_choose_city));
        add(MenuView.Row.loading(tr(R.string.weather_looking_up, q)), null);
        showSheet(0, () -> openWeatherMenu(1), null);
        String language = Locale.getDefault().getLanguage(), separator = tr(R.string.list_separator);
        loader.execute(() -> {
            List<Weather.Place> found;
            try {
                found = Weather.search(q, language, separator);
            } catch (Exception e) {
                found = null;
            }
            List<Weather.Place> places = found;
            ui.post(() -> {
                if (isDestroyed() || area != MENU || !crumb.equals(menuCrumb)) return;
                startMenu(crumb, tr(R.string.weather_choose_city));
                if (places == null) {
                    add(MenuView.Row.note(R.drawable.ic_info, tr(R.string.weather_unreachable)), null);
                    add(MenuView.Row.action(R.drawable.ic_reset, tr(R.string.try_again)), () -> searchCity(q));
                } else if (places.isEmpty()) {
                    add(MenuView.Row.note(R.drawable.ic_info, tr(R.string.weather_no_city, q)), null);
                    add(MenuView.Row.action(0, tr(R.string.weather_type_another)), this::askCity);
                } else {
                    for (Weather.Place p : places) add(MenuView.Row.action(0, p.name).value(p.detail), () -> saveCity(p));
                }
                showSheet(places == null || places.isEmpty() ? 1 : 0, () -> openWeatherMenu(1), null);
            });
        });
    }

    private void saveCity(Weather.Place p) {
        prefs.edit()
                .putString("weather_city", p.name)
                .putString("weather_lat", String.valueOf(p.lat))
                .putString("weather_lon", String.valueOf(p.lon))
                .putBoolean("weather_on", true)
                .remove("w_code")
                .apply();
        refreshWeather(true);
        toast(tr(R.string.toast_weather_for, p.name), true);
        openWeatherMenu(1);
    }

    /** Fetches at most every 30 minutes (5 after a failure); TIME_TICK only checks the age. */
    private void refreshWeather(boolean force) {
        updateWeatherView();
        if (!prefs.getBoolean("weather_on", true) || !prefs.contains("weather_lat") || weatherLoading) return;
        long now = System.currentTimeMillis();
        boolean stale = now - prefs.getLong("weather_time", 0) > 30 * 60_000L
                && now - prefs.getLong("weather_try", 0) > 5 * 60_000L;
        if (!force && !stale) return;
        weatherLoading = true;
        prefs.edit().putLong("weather_try", now).apply();
        double lat = Double.parseDouble(prefs.getString("weather_lat", "0"));
        double lon = Double.parseDouble(prefs.getString("weather_lon", "0"));
        boolean f = fahrenheit();
        loader.execute(() -> {
            Weather.Now n;
            try {
                n = Weather.fetch(lat, lon, f);
            } catch (Exception e) {
                n = null;
            }
            Weather.Now result = n;
            ui.post(() -> {
                weatherLoading = false;
                if (result == null || isDestroyed()) return;
                prefs.edit()
                        .putFloat("w_temp", (float) result.temp)
                        .putInt("w_code", result.code)
                        .putBoolean("w_day", result.day)
                        .putBoolean("w_f", f)
                        .putLong("w_sunrise", result.sunrise)
                        .putLong("w_sunset", result.sunset)
                        .putLong("weather_time", System.currentTimeMillis())
                        .apply();
                if (Theme.isLight(prefs) != theme.light) recreate();
                updateWeatherView();
            });
        });
    }

    private void updateWeatherView() {
        if (!prefs.getBoolean("weather_on", true)) {
            weather.setVisibility(View.GONE);
            return;
        }
        weather.setVisibility(View.VISIBLE);
        if (!prefs.contains("weather_lat")) {
            weather.setTextColor(theme.textTertiary);
            weather.setText(R.string.weather_set_city_hint);
            return;
        }
        weather.setTextColor(theme.textSecondary);
        if (!prefs.contains("w_code") || prefs.getBoolean("w_f", false) != fahrenheit()) {
            // Nothing to show until the first reading arrives.
            weather.setVisibility(View.GONE);
            return;
        }
        // No city name: the projector stays at home, so it's always the same place.
        int code = prefs.getInt("w_code", 0), sky = Weather.describe(code);
        weather.setText(tr(R.string.weather_line, Weather.emoji(code, prefs.getBoolean("w_day", true)),
                number(Math.round(prefs.getFloat("w_temp", 0))), sky == 0 ? "" : tr(sky)));
    }

    // Look

    private static final String[] LABELS = {"On focus", "Always", "Never"};
    private static final String[] APPEARANCE = {"Dark", "Light", "Automatic"};
    private static final String[] APPEARANCE_KEYS = {Theme.DARK, Theme.LIGHT, Theme.AUTO};

    private int appearanceIndex() {
        return Math.max(0, Arrays.asList(APPEARANCE_KEYS).indexOf(Theme.mode(prefs)));
    }

    private String appearanceName() {
        return APPEARANCE[appearanceIndex()];
    }

    private void openLookMenu(int focus) {
        lastSection = SEC_LOOK;
        startMenu(tr(R.string.customize), tr(R.string.menu_look));
        add(MenuView.Row.nav(0, tr(R.string.set_look_appearance), APPEARANCE[appearanceIndex()]), () -> openAppearanceMenu(0));
        add(MenuView.Row.nav(0, tr(R.string.set_look_font_and_text_size), Tokens.FONT_NAMES[fontIndex()]), () -> openFontMenu(0));
        add(MenuView.Row.nav(0, tr(R.string.set_look_clock), clockSummary()), () -> openClockMenu(0));
        add(MenuView.Row.nav(0, tr(R.string.set_look_top_bar), barSummary()), () -> openBarMenu(0));
        add(MenuView.Row.nav(0, tr(R.string.set_look_tiles), TILE_STYLES[tileStyleIndex()]), () -> openTilesMenu(0));
        add(MenuView.Row.nav(0, tr(R.string.set_look_themes), ""), () -> openThemesMenu(0));
        add(MenuView.Row.nav(0, tr(R.string.set_look_sounds), soundVolumeText(Sounds.volume(prefs))), () -> openSoundsMenu(0));
        showSheet(focus, backTo(SEC_LOOK), null);
    }

    private void openAppearanceMenu(int focus) {
        String crumb = crumb(tr(R.string.menu_look));
        startMenu(crumb, tr(R.string.menu_look_appearance));
        int mode = appearanceIndex();
        add(MenuView.Row.nav(0, tr(R.string.set_look_appearance_mode), APPEARANCE[mode]), () -> openChoice(crumbOf(crumb, tr(R.string.menu_look_appearance)), "Mode",
                new String[] {"Dark", "Light", "Automatic (light from sunrise to sunset)"}, mode, i -> {
                    // Colours are applied as views are built, so rebuild Home in the new look.
                    prefs.edit().putString("appearance", APPEARANCE_KEYS[i]).putInt("area", TOP)
                            .putInt("top", PILL_CUSTOMIZE).apply();
                    recreate();
                }, null, () -> openAppearanceMenu(0)));
        boolean auto = accentAuto();
        add(MenuView.Row.nav(0, tr(R.string.set_look_appearance_accent_colour), auto ? "From background" : "Azure"), () -> openChoice(crumbOf(crumb, tr(R.string.menu_look_appearance)),
                "Accent colour", new String[] {"Azure (default)", "From background"}, auto ? 1 : 0, i -> {
                    prefs.edit().putBoolean("accent_auto", i == 1).apply();
                    if (i == 1) {
                        // Showing the background again reads its colour, whatever kind it is.
                        if ("slideshow".equals(currentBg())) {
                            Drawable d = wallpaper.getDrawable();
                            if (d instanceof android.graphics.drawable.BitmapDrawable) {
                                updateAccent(((android.graphics.drawable.BitmapDrawable) d).getBitmap());
                            }
                        } else applyBackground(currentBg(), false);
                        toast("Accent follows the background. It stays azure when a colour wouldn't be readable.", true);
                    } else {
                        setAccentSeed(0);
                        toast("Accent back to azure", true);
                    }
                    openAppearanceMenu(1);
                }, null, () -> openAppearanceMenu(1)));
        showSheet(focus, () -> {
            if (custMode == CUST_QUICK) openCustomize(1);
            else openLookMenu(0);
        }, null);
    }

    // Sounds: all off until chosen here. Sounds.java decides when one may play.

    private static String soundVolumeText(int v) {
        return v == 0 ? "Off" : v + " of " + Sounds.VOLUME_MAX;
    }

    private void openSoundsMenu(int focus) {
        String crumb = crumb(tr(R.string.menu_look));
        startMenu(crumb, tr(R.string.menu_look_sounds));
        int volume = Sounds.volume(prefs);
        addAdjust(MenuView.Row.slider(0, tr(R.string.set_look_sounds_volume), volume / (float) Sounds.VOLUME_MAX, soundVolumeText(volume))
                .widest(soundVolumeText(Sounds.VOLUME_MAX)), dir -> {
            int old = Sounds.volume(prefs);
            int nv = Math.max(0, Math.min(Sounds.VOLUME_MAX, old + dir));
            // At either end nothing changes, so nothing is heard: no endless repeat while the button is held.
            stepHandled = true;
            if (nv == old) return;
            prefs.edit().putInt("sound_volume", nv).apply();
            if ((old == 0) != (nv == 0)) {
                // Turned on or off: the other rows come or go.
                sounds.refresh();
                // The first thing heard once the samples have loaded.
                if (nv > 0) sounds.playWhenReady(Sounds.SELECT);
                ui.post(() -> openSoundsMenu(0));
                return;
            }
            MenuView.Row r = menu.focused();
            r.fraction = nv / (float) Sounds.VOLUME_MAX;
            r.value(soundVolumeText(nv));
            // A real sound to judge the level by, not the quieter step tick; held, it thins out and fades.
            sounds.tick(Sounds.SELECT, navRepeat);
        });
        if (volume == 0) {
            add(MenuView.Row.note(R.drawable.ic_info, "Soft, quiet sounds for moving and choosing, while Home is on "
                    + "screen. Press right to turn them on. Everything still shows on screen with sounds off."), null);
            showSheet(0, () -> openLookMenu(6), null);
            return;
        }
        add(MenuView.Row.nav(0, tr(R.string.set_look_sounds_output), SOUND_OUTPUTS[pref("sound_output", 0, 2)]), () -> openSoundsOutputMenu(0));
        add(MenuView.Row.nav(0, tr(R.string.set_look_sounds_quiet_times), ""), () -> openSoundsQuietMenu(0));
        addToggle(tr(R.string.set_look_sounds_start_up_sound), "sound_startup", false, () -> openSoundsMenu(3));
        boolean panelOn = prefs.getBoolean("sound_panel", false);
        add(MenuView.Row.action(0, tr(R.string.set_look_sounds_panel_open_and_close)).value(panelOn ? "Faint" : "Off"), () -> {
            prefs.edit().putBoolean("sound_panel", !panelOn).apply();
            menuSound = panelOn ? Sounds.OFF : Sounds.ON;
            openSoundsMenu(4);
        });
        add(MenuView.Row.action(R.drawable.ic_reset, tr(R.string.set_look_sounds_reset_sounds)), () -> {
            prefs.edit().remove("sound_volume").remove("sound_mode").remove("sound_level").remove("sound_startup")
                    .remove("sound_panel").remove("sound_quiet_slow").remove("sound_mute_bed").remove("sound_mute_guest")
                    .remove("sound_output").apply();
            sounds.refresh();
            toast("Sounds back to the default: off", true);
            openSoundsMenu(0);
        });
        if (!sounds.systemEffectsOn()) add(MenuView.Row.note(R.drawable.ic_info, "Android's own sound effects are off, "
                + "so Home stays silent too."), null);
        else if (!sounds.audibleNow()) add(MenuView.Row.note(R.drawable.ic_info, "You won't hear these right now. "
                + "Output explains why."), null);
        showSheet(focus, () -> openLookMenu(6), null);
    }

    private static final String[] SOUND_OUTPUTS = {"Automatic", "System sounds", "Media volume"};

    /** Which of Android's channels the sounds use, and why you might not hear them. */
    private void openSoundsOutputMenu(int focus) {
        String crumb = crumb(tr(R.string.menu_look), tr(R.string.menu_look_sounds));
        startMenu(crumb, tr(R.string.menu_look_sounds_output));
        int output = pref("sound_output", 0, 2);
        add(MenuView.Row.nav(0, tr(R.string.set_look_sounds_output_sound_output), SOUND_OUTPUTS[output]), () -> openChoice(crumbOf(crumb, tr(R.string.menu_look_sounds_output)),
                "Sound output", SOUND_OUTPUTS, output, i -> {
                    prefs.edit().putInt("sound_output", i).apply();
                    sounds.refresh();
                    // The samples reload for the new channel; this is the first thing heard from it.
                    sounds.playWhenReady(Sounds.SELECT);
                    openSoundsOutputMenu(0);
                }, null, () -> openSoundsOutputMenu(0)));
        addToggle(tr(R.string.set_look_sounds_output_silent_on_bluetooth_or_arc), "sound_quiet_slow", true, () -> openSoundsOutputMenu(1));
        add(MenuView.Row.note(R.drawable.ic_info, outputNote(output)), null);
        showSheet(focus, () -> openSoundsMenu(1), null);
    }

    private String outputNote(int output) {
        boolean heard = sounds.audibleNow();
        if (output == Sounds.OUTPUT_SYSTEM) {
            return heard ? "Sounds use your TV's system sounds."
                    : "Your TV's system sounds are muted, so you won't hear these. Choose Automatic or Media volume.";
        }
        if (!heard) return "Your TV's volume is muted or at 0, so you won't hear these.";
        if (output == Sounds.OUTPUT_MEDIA) return "Sounds follow your TV's volume. They never pause or lower other apps.";
        return sounds.currentRoute() == Sounds.ROUTE_MEDIA
                ? "Your TV's system sounds are muted, so sounds use its media volume. They never pause or lower other apps."
                : "Sounds use your TV's system sounds, and switch to its media volume if those are muted.";
    }

    private void openSoundsQuietMenu(int focus) {
        startMenu(crumb(tr(R.string.menu_look), tr(R.string.menu_look_sounds)), tr(R.string.menu_look_sounds_quiet));
        addToggle(tr(R.string.set_look_sounds_quiet_silent_at_bedtime), "sound_mute_bed", false, () -> openSoundsQuietMenu(0));
        if (prefs.getString("lock_sched", "").isEmpty()) {
            pendingRows.get(pendingRows.size() - 1).disabled("Choose a time in Lock & remote › When first.");
        }
        addToggle(tr(R.string.set_look_sounds_quiet_silent_in_guest_mode), "sound_mute_guest", false, () -> openSoundsQuietMenu(1));
        showSheet(focus, () -> openSoundsMenu(2), null);
    }

    private static final String[] TEXT_SIZES = {"Smaller", "Normal", "Larger"};

    private void openFontMenu(int focus) {
        String crumb = crumb(tr(R.string.menu_look));
        startMenu(crumb, tr(R.string.menu_look_font));
        int font = fontIndex();
        add(MenuView.Row.nav(0, tr(R.string.set_look_font_font), Tokens.FONT_NAMES[font]), () -> openChoice(crumbOf(crumb, tr(R.string.menu_look_font)),
                "Font", Tokens.FONT_NAMES, font, i -> {
                    prefs.edit().putString("font", Tokens.FONTS[i]).putInt("area", TOP).putInt("top", PILL_CUSTOMIZE).apply();
                    // Every screen (and the ≡ panel) picks up the new font.
                    Tokens.invalidate();
                    recreate();
                }, i -> previewFont(Tokens.FONTS[i]), () -> openFontMenu(0)));
        int scale = Math.max(0, Arrays.stream(Tokens.TEXT_SCALES).boxed().collect(java.util.stream.Collectors.toList())
                .indexOf(prefs.getInt("text_scale", 100)));
        add(MenuView.Row.nav(0, tr(R.string.set_look_font_text_size), TEXT_SIZES[scale]), () -> openChoice(crumbOf(crumb, tr(R.string.menu_look_font)),
                "Text size", TEXT_SIZES, scale, i -> {
                    prefs.edit().putInt("text_scale", Tokens.TEXT_SCALES[i]).putInt("area", TOP)
                            .putInt("top", PILL_CUSTOMIZE).apply();
                    Tokens.invalidate();
                    recreate();
                }, null, () -> openFontMenu(1)));
        add(MenuView.Row.action(R.drawable.ic_reset, tr(R.string.set_look_font_reset_font_and_size)), () -> {
            prefs.edit().remove("font").remove("text_scale").putInt("area", TOP).putInt("top", PILL_CUSTOMIZE).apply();
            Tokens.invalidate();
            recreate();
        });
        showSheet(focus, () -> openLookMenu(1), null);
    }

    // Themes: the whole look, saved, shared as a picture, or one of three built in.

    private void openThemesMenu(int focus) {
        String crumb = crumb(tr(R.string.menu_look));
        startMenu(crumb, tr(R.string.menu_look_themes));
        for (int i = 0; i < Themes.PRESETS.length; i++) {
            int n = i;
            add(MenuView.Row.action(0, Themes.PRESETS[i][0]).value("Built in"), () -> {
                try {
                    applyTheme(Themes.preset(n), Themes.PRESETS[n][0]);
                } catch (JSONException ignored) {
                }
            });
        }
        for (File f : Themes.saved(this)) {
            String name = Themes.name(f);
            add(MenuView.Row.nav(0, name, "Yours"), () -> openSavedTheme(f, name));
        }
        add(MenuView.Row.action(R.drawable.ic_add, tr(R.string.set_look_themes_save_this_look)), () -> askText(crumbOf(crumb, tr(R.string.menu_look_themes)),
                "Save this look", "Name, e.g. Movie night", "", InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, name -> {
                    try {
                        Themes.save(this, name);
                        toast("Saved as " + name, true);
                    } catch (IOException | JSONException e) {
                        toast("Couldn't save the theme", false);
                    }
                    openThemesMenu(0);
                }, () -> openThemesMenu(0)));
        add(MenuView.Row.nav(R.drawable.ic_background, tr(R.string.set_look_themes_import_from_a_picture), ""), this::openThemeImport);
        if (Themes.undo(this).exists()) {
            add(MenuView.Row.action(R.drawable.ic_reset, tr(R.string.set_look_themes_undo_last_theme)), () -> {
                try {
                    applyTheme(Themes.load(Themes.undo(this)), null);
                } catch (IOException | JSONException e) {
                    toast("Nothing to undo", false);
                }
            });
        }
        showSheet(focus, () -> openLookMenu(5), null);
    }

    /** Remembers the look for Undo, applies the theme and rebuilds Home in it. */
    private void applyTheme(JSONObject theme, String name) {
        loader.execute(() -> {
            boolean ok;
            try {
                autoBackup("Before theme");
                Themes.apply(this, theme);
                ok = true;
            } catch (IOException | JSONException e) {
                ok = false;
            }
            boolean done = ok;
            ui.post(() -> {
                if (!done) {
                    toast("That theme can't be read. Nothing was changed.", false);
                    return;
                }
                prefs.edit().putInt("area", TOP).putInt("top", PILL_CUSTOMIZE)
                        .putString("toast", name == null ? "Theme undone" : name + " applied. Look › Themes › Undo puts it back.")
                        .apply();
                Tokens.invalidate();
                recreate();
            });
        });
    }

    private void openSavedTheme(File f, String name) {
        String crumb = crumb(tr(R.string.menu_look), tr(R.string.menu_look_themes));
        startMenu(crumb, name);
        add(MenuView.Row.action(R.drawable.ic_check, "Apply"), () -> {
            try {
                applyTheme(Themes.load(f), name);
            } catch (IOException | JSONException e) {
                toast("That theme can't be read. Nothing was changed.", false);
            }
        });
        for (String vol : Backups.volumes(this)) {
            boolean tv = Media.primary().equals(vol);
            add(MenuView.Row.action(R.drawable.ic_open, tv ? "Share as a picture on this TV" : "Share as a picture on USB"), () -> {
                loader.execute(() -> {
                    Uri uri = null;
                    try {
                        uri = Backups.export(this, Themes.load(f), name + " theme", vol, previewCard(name, "Leios theme"));
                    } catch (IOException | JSONException ignored) {
                    }
                    Uri saved = uri;
                    ui.post(() -> toast(saved == null ? "Couldn't save the picture" : "Saved in Pictures › Leios", saved != null));
                });
            });
        }
        add(MenuView.Row.action(R.drawable.ic_trash, "Delete"), () -> {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            toast(name + " deleted", true);
            openThemesMenu(0);
        });
        showSheet(0, () -> openThemesMenu(0), null);
    }

    private void openThemeImport() {
        if (checkSelfPermission(imagePermission()) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {imagePermission()}, REQ_PHOTOS);
            return;
        }
        String crumb = crumb(tr(R.string.menu_look), tr(R.string.menu_look_themes));
        startMenu(crumb, "Import a theme");
        add(MenuView.Row.loading("Looking for theme pictures…"), null);
        showSheet(0, () -> openThemesMenu(0), null);
        loader.execute(() -> {
            List<Backups.Entry> found = Backups.exported(this, "theme");
            ui.post(() -> {
                if (isDestroyed() || area != MENU || !crumb.equals(menuCrumb)) return;
                startMenu(crumb, "Import a theme");
                for (Backups.Entry e : found) {
                    add(MenuView.Row.action(0, e.label).value(Backups.when(e.time)), () -> loader.execute(() -> {
                        JSONObject theme;
                        try {
                            theme = Backups.read(this, e);
                            // Kept as one of yours too, so it can be applied again later.
                            if (!theme.has("name")) theme.put("name", e.label.replace(" theme", ""));
                            Themes.write(new File(new File(getFilesDir(), "themes"),
                                    "theme-" + System.currentTimeMillis() + ".json"), theme);
                        } catch (IOException | JSONException ex) {
                            theme = null;
                        }
                        JSONObject t = theme;
                        ui.post(() -> {
                            if (t == null) toast("That picture has no theme in it, or it's damaged. Nothing was changed.", false);
                            else applyTheme(t, t.optString("name", "Theme"));
                        });
                    }));
                }
                if (found.isEmpty()) add(MenuView.Row.note(R.drawable.ic_info, "No theme pictures found. Themes "
                        + "shared from Leios or Clean Home are in Pictures, on this TV or a USB drive."), null);
                showSheet(0, () -> openThemesMenu(0), null);
            });
        });
    }

    /** The picture a shared theme or backup shows in any photo viewer: the background and a label. */
    private Bitmap previewCard(String title, String kind) {
        int w = 960, h = 540;
        Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(bm);
        c.drawColor(theme.bg);
        Drawable d = wallpaper.getDrawable();
        if (d != null && d.getConstantState() != null && wallpaper.getVisibility() == View.VISIBLE) {
            Drawable copy = d.getConstantState().newDrawable().mutate();
            copy.setBounds(0, 0, w, h);
            copy.draw(c);
        }
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setColor(theme.scrimEdge);
        c.drawRect(0, h * 0.62f, w, h, p);
        p.setColor(theme.text);
        p.setTypeface(tk.semibold);
        p.setTextSize(h * 0.09f);
        c.drawText(title, w * 0.06f, h * 0.8f, p);
        p.setTypeface(tk.regular);
        p.setColor(theme.textSecondary);
        p.setTextSize(h * 0.05f);
        c.drawText(kind, w * 0.06f, h * 0.9f, p);
        return bm;
    }

    private int fontIndex() {
        return Math.max(0, Arrays.asList(Tokens.FONTS).indexOf(prefs.getString("font", Tokens.FONTS[0])));
    }

    /** Shows a font on the clock, date and app name while you move through the list. */
    private void previewFont(String name) {
        Typeface reg = Tokens.font(this, name, getResources().getInteger(R.integer.weight_regular));
        Typeface med = Tokens.font(this, name, getResources().getInteger(R.integer.weight_medium));
        clock.setTypeface(reg);
        date.setTypeface(reg);
        weather.setTypeface(reg);
        greeting.setTypeface(med);
        dockTitle.setTypeface(med);
    }

    // Clock

    private static final String[] CLOCK_SIZE_NAMES = {"Small", "Medium", "Large"};
    private static final float[] CLOCK_SIZES = {0.8f, 1f, 1.2f};
    private static final String[] CLOCK_FORMATS = {"auto", "12", "24"};
    private static final String[] CLOCK_FORMAT_NAMES = {"Like the projector", "12-hour", "24-hour"};

    private String clockSummary() {
        return CLOCK_SIZE_NAMES[pref("clock_size", 1, 2)] + ("centre".equals(prefs.getString("clock_pos", "left"))
                ? " · centre" : "");
    }

    private void openClockMenu(int focus) {
        String crumb = crumb(tr(R.string.menu_look));
        startMenu(crumb, tr(R.string.menu_look_clock));
        int fmt = Math.max(0, Arrays.asList(CLOCK_FORMATS).indexOf(prefs.getString("clock_fmt", "auto")));
        add(MenuView.Row.nav(0, tr(R.string.set_look_clock_format), CLOCK_FORMAT_NAMES[fmt]), () -> openChoice(crumbOf(crumb, tr(R.string.menu_look_clock)), "Format",
                CLOCK_FORMAT_NAMES, fmt, i -> {
                    prefs.edit().putString("clock_fmt", CLOCK_FORMATS[i]).apply();
                    updateClock();
                    openClockMenu(0);
                }, null, () -> openClockMenu(0)));
        int size = pref("clock_size", 1, 2);
        add(MenuView.Row.nav(0, tr(R.string.set_look_clock_size), CLOCK_SIZE_NAMES[size]), () -> openChoice(crumbOf(crumb, tr(R.string.menu_look_clock)), "Size",
                CLOCK_SIZE_NAMES, size, i -> {
                    prefs.edit().putInt("clock_size", i).apply();
                    applyClockLook();
                    openClockMenu(1);
                }, i -> {
                    prefs.edit().putInt("clock_size", i).apply();
                    applyClockLook();
                }, () -> openClockMenu(1)));
        boolean centre = "centre".equals(prefs.getString("clock_pos", "left"));
        add(MenuView.Row.nav(0, tr(R.string.set_look_clock_position), centre ? "Centre" : "Top left"), () -> openChoice(crumbOf(crumb, tr(R.string.menu_look_clock)),
                "Position", new String[] {"Top left", "Centre"}, centre ? 1 : 0, i -> {
                    prefs.edit().putString("clock_pos", i == 1 ? "centre" : "left").apply();
                    applyClockLook();
                    openClockMenu(2);
                }, i -> {
                    prefs.edit().putString("clock_pos", i == 1 ? "centre" : "left").apply();
                    applyClockLook();
                }, () -> openClockMenu(2)));
        addToggle(tr(R.string.set_look_clock_date), "clock_date", true, () -> openClockMenu(3));
        addToggle(tr(R.string.set_look_clock_greeting), "greeting", true, () -> openClockMenu(4));
        addToggle(tr(R.string.set_look_clock_shadow), "clock_shadow", false, () -> openClockMenu(5));
        add(MenuView.Row.action(R.drawable.ic_reset, tr(R.string.set_look_clock_reset_clock)), () -> {
            prefs.edit().remove("clock_fmt").remove("clock_size").remove("clock_pos").remove("clock_date")
                    .remove("greeting").remove("clock_shadow").apply();
            applyClockLook();
            updateClock();
            toast("Clock back to the default", true);
            openClockMenu(6);
        });
        showSheet(focus, () -> openLookMenu(2), null);
    }

    /** An On / Off row that flips a pref and applies it straight away. */
    private void addToggle(String label, String key, boolean def, Runnable reopen) {
        boolean on = prefs.getBoolean(key, def);
        add(MenuView.Row.action(0, label).value(on ? "On" : "Off"), () -> {
            prefs.edit().putBoolean(key, !on).apply();
            menuSound = on ? Sounds.OFF : Sounds.ON;
            applyClockLook();
            applyBarLook();
            reopen.run();
        });
    }

    private ViewGroup clockCol;

    /** Text sizes follow Look › Text size; the clock also has its own size, place and shadow. */
    private void applyClockLook() {
        if (clockCol == null) clockCol = (ViewGroup) clock.getParent();
        clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, tk.textDisplay * CLOCK_SIZES[pref("clock_size", 1, 2)]);
        greeting.setTextSize(TypedValue.COMPLEX_UNIT_PX, tk.textLabel);
        date.setTextSize(TypedValue.COMPLEX_UNIT_PX, tk.textBody);
        weather.setTextSize(TypedValue.COMPLEX_UNIT_PX, tk.textBody);
        dockTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, tk.textTitle);
        greeting.setVisibility(prefs.getBoolean("greeting", true) ? View.VISIBLE : View.GONE);
        date.setVisibility(prefs.getBoolean("clock_date", true) ? View.VISIBLE : View.GONE);
        boolean shadow = prefs.getBoolean("clock_shadow", false);
        for (TextView t : new TextView[] {greeting, clock, date, weather}) {
            if (shadow) t.setShadowLayer(tk.space2, 0, tk.space1 / 2f, theme.shadow);
            else t.setShadowLayer(0, 0, 0, 0);
        }
        // Centre sits in the middle of the space above the dock, clear of the top bar and app name.
        boolean centre = "centre".equals(prefs.getString("clock_pos", "left"));
        ViewGroup parent = (ViewGroup) clockCol.getParent();
        if (centre && parent != hero) {
            parent.removeView(clockCol);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
            // FrameLayout centres and then shifts by (top - bottom) margin, so half of each band
            // (the top bar above, the app name below) centres the clock between them.
            lp.topMargin = (tk.safeV + pillHeight() + tk.space4) / 2;
            lp.bottomMargin = Math.round(tk.textTitle * 1.3f + tk.space4 + tk.space5) / 2;
            hero.addView(clockCol, 1, lp);
        } else if (!centre && parent != topBar) {
            parent.removeView(clockCol);
            ((ViewGroup) topBar).addView(clockCol, 0, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        ((LinearLayout) clockCol).setGravity(centre ? Gravity.CENTER_HORIZONTAL : Gravity.START);
        // The soft fade that keeps text readable on photos follows the clock (light mode only uses it).
        FrameLayout.LayoutParams elp = (FrameLayout.LayoutParams) edgeTop.getLayoutParams();
        elp.gravity = centre ? Gravity.TOP | Gravity.CENTER_HORIZONTAL : Gravity.TOP | Gravity.START;
        edgeTop.setLayoutParams(elp);
        int[] edge = {theme.scrimEdge, Ui.alpha(theme.scrimEdge, 0.55f), Ui.alpha(theme.scrimEdge, 0f)};
        GradientDrawable fade = cornerFade(edge, centre ? 0.36f : 0f);
        if (centre) {
            // Centred in its square view: the fade must reach clear before the view's own edges.
            fade.setGradientCenter(0.5f, 0.36f);
            fade.setGradientRadius(getResources().getDimensionPixelSize(R.dimen.edge_radius) / 2f);
        }
        edgeTop.setBackground(fade);
    }

    // Top bar

    private static final String[] WIFI_SHOW = {"Name", "Icon only", "Hidden"};

    private String barSummary() {
        int n = 0;
        for (View p : pills) if (p.getVisibility() == View.VISIBLE) n++;
        return n + " buttons";
    }

    private void openBarMenu(int focus) {
        String crumb = crumb(tr(R.string.menu_look));
        startMenu(crumb, tr(R.string.menu_look_bar));
        addToggle(tr(R.string.set_look_bar_search_button), "bar_search", true, () -> openBarMenu(0));
        addToggle(tr(R.string.set_look_bar_inputs_button), "bar_inputs", true, () -> openBarMenu(1));
        if (!hasInputs) pendingRows.get(pendingRows.size() - 1).disabled("This TV has no HDMI inputs.");
        int wifi = pref("bar_wifi", 0, 2);
        add(MenuView.Row.nav(0, tr(R.string.set_look_bar_wi_fi), WIFI_SHOW[wifi]), () -> openChoice(crumbOf(crumb, tr(R.string.menu_look_bar)), "Wi-Fi",
                new String[] {"Name and signal", "Signal only", "Hidden"}, wifi, i -> {
                    prefs.edit().putInt("bar_wifi", i).apply();
                    applyBarLook();
                    openBarMenu(2);
                }, null, () -> openBarMenu(2)));
        boolean notif = prefs.getBoolean("bar_notif", false);
        add(MenuView.Row.nav(0, tr(R.string.set_look_bar_notifications), !notif ? "Off" : Notifs.connected() ? "On" : "Needs access"),
                this::openNotifSetting);
        addToggle(tr(R.string.set_look_bar_settings_button), "bar_settings", true, () -> openBarMenu(4));
        boolean compact = pref("bar_size", 0, 1) == 1;
        add(MenuView.Row.nav(0, tr(R.string.set_look_bar_size), compact ? "Compact" : "Normal"), () -> openChoice(crumbOf(crumb, tr(R.string.menu_look_bar)), "Size",
                new String[] {"Normal", "Compact"}, compact ? 1 : 0, i -> {
                    prefs.edit().putInt("bar_size", i).apply();
                    applyBarLook();
                    openBarMenu(5);
                }, i -> {
                    prefs.edit().putInt("bar_size", i).apply();
                    applyBarLook();
                }, () -> openBarMenu(5)));
        add(MenuView.Row.action(R.drawable.ic_reset, tr(R.string.set_look_bar_reset_top_bar)), () -> {
            prefs.edit().remove("bar_search").remove("bar_inputs").remove("bar_wifi").remove("bar_notif")
                    .remove("bar_settings").remove("bar_size").apply();
            applyBarLook();
            toast("Top bar back to the default", true);
            openBarMenu(6);
        });
        showSheet(focus, () -> openLookMenu(3), null);
    }

    /**
     * The bell needs Android's notification access, which only you can give (Settings › Privacy ›
     * Notification access); this explains that and opens the page.
     */
    private void openNotifSetting() {
        boolean on = prefs.getBoolean("bar_notif", false);
        String crumb = crumb(tr(R.string.menu_look), tr(R.string.menu_look_bar));
        startMenu(crumb, tr(R.string.menu_look_bar_notif));
        add(MenuView.Row.note(R.drawable.ic_info, "Shows a bell in the top bar when an app has a notification. "
                + "OK on it lists them. Needs notification access, which you turn on in Android's settings."), null);
        add(MenuView.Row.action(0, tr(R.string.set_look_bar_notif_show_the_bell)).value(on ? "On" : "Off"), () -> {
            prefs.edit().putBoolean("bar_notif", !on).apply();
            applyBarLook();
            if (!on && !Notifs.connected()) toast("Turn on “Home” on the next screen, then come back", false);
            if (!on && !Notifs.connected()) startSafely(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
            else openNotifSetting();
        });
        MenuView.Row access = add(MenuView.Row.action(R.drawable.ic_settings, tr(R.string.set_look_bar_notif_notification_access))
                .value(Notifs.connected() ? "Allowed" : "Not allowed"),
                () -> startSafely(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")));
        if (!on) access.disabled("Turn on the bell first.");
        showSheet(1, () -> openBarMenu(3), null);
    }

    private int pillHeight() {
        return pref("bar_size", 0, 1) == 1 ? getResources().getDimensionPixelSize(R.dimen.pill_height_compact)
                : tk.pillHeight;
    }

    /** Which top-bar buttons show, and their size. Customize always stays, so settings stay reachable. */
    private void applyBarLook() {
        boolean compact = pref("bar_size", 0, 1) == 1;
        int h = pillHeight();
        for (View pill : pills) {
            pill.getLayoutParams().height = h;
            ViewGroup g = (ViewGroup) pill;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                if (c instanceof TextView) ((TextView) c).setTextSize(TypedValue.COMPLEX_UNIT_PX,
                        compact ? tk.textCaption : tk.textLabel);
            }
            pill.requestLayout();
        }
        int wifi = pref("bar_wifi", 0, 2);
        // A TV without inputs (or a tuner) has nothing to switch to: no Inputs button.
        pills[PILL_INPUTS].setVisibility(hasInputs && prefs.getBoolean("bar_inputs", true) ? View.VISIBLE : View.GONE);
        pills[PILL_SEARCH].setVisibility(prefs.getBoolean("bar_search", true) ? View.VISIBLE : View.GONE);
        pills[PILL_WIFI].setVisibility(wifi == 2 ? View.GONE : View.VISIBLE);
        // "Signal only": the Wi-Fi button stays a circle even when focused (shapePill sees no text).
        wifiLabel.setVisibility(wifi == 1 ? View.GONE : View.VISIBLE);
        // Guest mode hides both ways into settings; ▲ ▲ ▼ ▼ ◀ ▶ ◀ ▶ on Home still opens Customize.
        boolean guest = Lock.guest(this);
        pills[PILL_CUSTOMIZE].setVisibility(guest ? View.GONE : View.VISIBLE);
        pills[PILL_SETTINGS].setVisibility(!guest && prefs.getBoolean("bar_settings", true) ? View.VISIBLE : View.GONE);
        pills[PILL_NOTIF].setVisibility(prefs.getBoolean("bar_notif", false) && Notifs.count() > 0
                ? View.VISIBLE : View.GONE);
        notifLabel.setText(String.valueOf(Notifs.count()));
        if (pills[topIndex].getVisibility() != View.VISIBLE) {
            int first = nextPill(-1, 1);
            topIndex = first < 0 ? PILL_CUSTOMIZE : first;
        }
        litPill = -2;
        paintPills();
        if (area == TOP || (area == MENU && menuReturnArea == TOP)) afterLayout(topBar, () -> applyFocus(true));
    }

    /** The next shown button in {@code dir}, or where you are if there's none. */
    private int nextPill(int from, int dir) {
        for (int i = from + dir; i >= 0 && i < pills.length; i += dir) {
            if (pills[i].getVisibility() == View.VISIBLE) return i;
        }
        return from;
    }

    private boolean anyPill() {
        for (View p : pills) if (p.getVisibility() == View.VISIBLE) return true;
        return false;
    }

    // ▲ ▲ ▼ ▼ ◀ ▶ ◀ ▶ on Home opens Customize, even when guest mode hides it (with the PIN if one is set).
    private static final int[] SECRET = {KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT};
    private final int[] lastKeys = new int[SECRET.length];
    private int keyCount;

    private void watchSecret(int code) {
        System.arraycopy(lastKeys, 1, lastKeys, 0, lastKeys.length - 1);
        lastKeys[lastKeys.length - 1] = code;
        keyCount++;
        if (keyCount >= SECRET.length && Arrays.equals(lastKeys, SECRET)) {
            keyCount = 0;
            Arrays.fill(lastKeys, 0);
            if (area == MENU) {
                leaveMenu();
                dismissMenu();
            }
            area = TOP;
            menuAnchorPill = PILL_CUSTOMIZE;
            requirePin(Lock.hasPin(this) && (Lock.guest(this) || customizeLocked()), tr(R.string.customize),
                    () -> openFull(SEC_LOCK, null));
        }
    }

    /** Home in front, for the ≡ helper's remote shortcuts. */
    private static java.lang.ref.WeakReference<MainActivity> resumed = new java.lang.ref.WeakReference<>(null);

    static MainActivity resumedHome() {
        return resumed.get();
    }

    // Tiles

    private static final String[] TILE_STYLES = {"Clean", "Soft", "Crisp", "Custom"};
    // Per style: corners (0 small, 1 medium, 2 large), focus colour (0 accent, 1 app, 2 plain), icon tile background.
    private static final int[][] TILE_PRESETS = {{1, 0, 0}, {2, 1, 1}, {0, 2, 2}};
    private static final String[] CORNERS = {"Small", "Medium", "Large"};
    private static final String[] FOCUS_COLOURS = {"Accent", "App's colour", "Plain"};
    private static final String[] ICON_BACKS = {"App's colour", "Glass", "Plain"};

    private int tileStyleIndex() {
        int[] cur = {pref("tile_corners", 1, 2), pref("focus_color", 0, 2), pref("icon_bg", 0, 2)};
        for (int i = 0; i < TILE_PRESETS.length; i++) if (Arrays.equals(cur, TILE_PRESETS[i])) return i;
        return 3;
    }

    private int tileRadius() {
        return new int[] {tk.radiusSm, tk.radiusMd, tk.radiusLg}[pref("tile_corners", 1, 2)];
    }

    private void openTilesMenu(int focus) {
        String crumb = crumb(tr(R.string.menu_look));
        startMenu(crumb, tr(R.string.menu_look_tiles));
        int style = tileStyleIndex();
        add(MenuView.Row.nav(0, tr(R.string.set_look_tiles_style), TILE_STYLES[style]), () -> {
            int[][] before = {{pref("tile_corners", 1, 2), pref("focus_color", 0, 2), pref("icon_bg", 0, 2)}};
            // "Custom" (your own Advanced settings) has no tick here; Back puts them back.
            openChoice(crumbOf(crumb, tr(R.string.menu_look_tiles)), "Style", Arrays.copyOf(TILE_STYLES, 3), style == 3 ? -1 : style,
                    i -> {
                        setTilePreset(TILE_PRESETS[i]);
                        reloadApps();
                        toast("Tiles: " + TILE_STYLES[i], true);
                        openTilesMenu(0);
                    }, i -> {
                        if (i >= 0) setTilePreset(TILE_PRESETS[i]);
                    }, () -> {
                        setTilePreset(before[0]);
                        openTilesMenu(0);
                    });
        });
        boolean icons = "icon".equals(prefs.getString("tile_style", "banner"));
        add(MenuView.Row.nav(0, tr(R.string.set_look_tiles_show_apps_as), icons ? "Icons" : "Banners"), () -> openChoice(crumbOf(crumb, tr(R.string.menu_look_tiles)),
                "Show apps as", new String[] {"Banners (16:9 artwork)", "Icons"}, icons ? 1 : 0, i -> {
                    prefs.edit().putString("tile_style", i == 1 ? "icon" : "banner").apply();
                    refreshAppLooks();
                    openTilesMenu(1);
                }, i -> {
                    prefs.edit().putString("tile_style", i == 1 ? "icon" : "banner").apply();
                    refreshAppLooks();
                }, () -> openTilesMenu(1)));
        add(MenuView.Row.nav(0, tr(R.string.set_look_tiles_app_names), LABELS[labelMode]), () -> openChoice(crumbOf(crumb, tr(R.string.menu_look_tiles)),
                "App names under tiles", LABELS, labelMode, i -> {
                    labelMode = i;
                    prefs.edit().putInt("labels", i).apply();
                    relayout();
                    openTilesMenu(2);
                }, null, () -> openTilesMenu(2)));
        add(MenuView.Row.nav(0, tr(R.string.set_look_tiles_advanced), ""), () -> openTilesAdvanced(0));
        add(MenuView.Row.action(R.drawable.ic_reset, tr(R.string.set_look_tiles_reset_tiles)), () -> {
            prefs.edit().remove("tile_corners").remove("focus_color").remove("icon_bg").remove("tile_style")
                    .remove("labels").remove("glow").apply();
            labelMode = TileAdapter.LABELS_FOCUS;
            glowOn = true;
            applyAppLooks();
            relayout();
            reloadApps();
            toast("Tiles back to the default", true);
            openTilesMenu(4);
        });
        showSheet(focus, () -> openLookMenu(4), null);
    }

    private void setTilePreset(int[] p) {
        prefs.edit().putInt("tile_corners", p[0]).putInt("focus_color", p[1]).putInt("icon_bg", p[2]).apply();
        relayout();
    }

    private void openTilesAdvanced(int focus) {
        String crumb = crumb(tr(R.string.menu_look), tr(R.string.menu_look_tiles));
        startMenu(crumb, tr(R.string.menu_look_tiles_advanced));
        tileOption(0, tr(R.string.set_look_tiles_advanced_corners), CORNERS, "tile_corners", 1, true);
        tileOption(1, tr(R.string.set_look_tiles_advanced_focus_colour), FOCUS_COLOURS, "focus_color", 0, false);
        tileOption(2, tr(R.string.set_look_tiles_advanced_icon_tile_background), ICON_BACKS, "icon_bg", 0, false);
        add(MenuView.Row.action(0, tr(R.string.set_look_tiles_advanced_glow_behind_the_dock)).value(glowOn ? "On" : "Off"), () -> {
            glowOn = !glowOn;
            prefs.edit().putBoolean("glow", glowOn).apply();
            applyFocus(true);
            openTilesAdvanced(3);
        });
        showSheet(focus, () -> openTilesMenu(3), null);
    }

    /** One tile setting that previews on the tiles as you move; {@code banners} redraws banner corners. */
    private void tileOption(int row, String title, String[] options, String key, int def, boolean banners) {
        int cur = pref(key, def, options.length - 1);
        add(MenuView.Row.nav(0, title, options[cur]), () -> openChoice(crumb(tr(R.string.menu_look), tr(R.string.menu_look_tiles), tr(R.string.menu_look_tiles_advanced)),
                title, options, cur, i -> {
                    prefs.edit().putInt(key, i).apply();
                    relayout();
                    if (banners) reloadApps();
                    openTilesAdvanced(row);
                }, i -> {
                    prefs.edit().putInt(key, i).apply();
                    relayout();
                }, () -> openTilesAdvanced(row)));
    }

    // Projector & sleep: JMGO's own user screens (checked by hand; calibration and test screens left out).

    /** Package and activity of each screen; its name and summary are in PROJECTOR_LABELS / _SUMMARIES. */
    private static final String[][] PROJECTOR = {
            {"com.android.tv.settings", "com.android.tv.settings.partnercustomizer.picture.PictureActivity"},
            {"com.android.tv.settings", "com.android.tv.settings.partnercustomizer.picture.JmgoLightActivity"},
            {"com.android.tv.settings", "com.jmgo.jmgosettinglib.module.keystone.ManualFittingActivity"},
    };
    private static final int[] PROJECTOR_LABELS = {R.string.set_projector_picture, R.string.set_projector_brightness,
            R.string.set_projector_keystone};
    private static final int[] PROJECTOR_SUMMARIES = {R.string.projector_picture_summary,
            R.string.projector_brightness_summary, R.string.projector_keystone_summary};
    private static final int[] PROJECTOR_ICONS = {R.drawable.ic_picture, R.drawable.ic_brightness, R.drawable.ic_keystone};

    /** How many of JMGO's screens the Projector menu lists (the rows below it follow). */
    private int projectorBase = PROJECTOR.length;

    private void openProjectorMenu(int focus) {
        lastSection = SEC_PROJECTOR;
        startMenu(tr(R.string.customize), tr(R.string.menu_projector));
        // JMGO's own screens, only those this TV has (other TVs show just the tools below).
        for (int i = 0; i < PROJECTOR.length; i++) {
            String[] p = PROJECTOR[i];
            if (!Device.has(this, p[0], p[1])) continue;
            String label = tr(PROJECTOR_LABELS[i]);
            add(MenuView.Row.nav(PROJECTOR_ICONS[i], label, tr(PROJECTOR_SUMMARIES[i])),
                    () -> startSafely(Device.open(p[0], p[1]), label));
        }
        projectorBase = pendingRows.size();
        int left = SleepTimer.minutesLeft(this);
        add(MenuView.Row.nav(R.drawable.ic_sleep, tr(R.string.set_projector_sleep_timer), left > 0 ? left + " min left" : "Off"), this::openSleepMenu);
        add(MenuView.Row.nav(R.drawable.ic_grid, tr(R.string.set_projector_test_patterns), "Focus, levels…"), this::openPatternsMenu);
        add(MenuView.Row.nav(R.drawable.ic_open, tr(R.string.set_projector_start_up_and_wake), startupSummary()), () -> openStartupMenu(0));
        add(MenuView.Row.nav(R.drawable.ic_look, tr(R.string.set_projector_when_home_is_idle), idleSummary()), this::openIdleMenu);
        showSheet(focus, backTo(SEC_PROJECTOR), null);
    }

    private void openPatternsMenu() {
        startMenu(crumb(tr(R.string.menu_projector)), "Test patterns");
        for (int i = 0; i < PatternsActivity.NAMES.length; i++) {
            int n = i;
            add(MenuView.Row.action(0, PatternsActivity.NAMES[i]), () -> startSafely(
                    new Intent(this, PatternsActivity.class).putExtra("pattern", n), "the test patterns"));
        }
        showSheet(0, () -> openProjectorMenu(projectorBase + 1), null);
        menu.setHints("OK", "Show", "BACK", "Back");
    }

    // Start-up and wake: open an app (or an input) when the projector starts or wakes, or stay on Home.

    /** Targets: "" Home, a package, or "input:<id>". On wake, "stay" leaves whatever was on screen. */
    private String targetName(String t) {
        if (t.isEmpty()) return "Home";
        if ("stay".equals(t)) return "Where it was";
        for (AppRepo.App a : allApps) if (a.pkg.equals(t)) return a.label;
        for (AppRepo.App a : inputApps()) if (a.pkg.equals(t)) return a.label;
        return "Home";
    }

    private String startupSummary() {
        String boot = prefs.getString("boot_app", "");
        return boot.isEmpty() ? "" : targetName(boot);
    }

    private void openStartupMenu(int focus) {
        String crumb = crumb(tr(R.string.menu_projector));
        startMenu(crumb, tr(R.string.menu_projector_startup));
        add(MenuView.Row.nav(0, tr(R.string.set_projector_startup_when_it_starts), targetName(prefs.getString("boot_app", ""))),
                () -> openTargetChoice("When the projector starts", "boot_app", false, 0));
        add(MenuView.Row.nav(0, tr(R.string.set_projector_startup_when_it_wakes), targetName(prefs.getString("wake_app", "stay"))),
                () -> openTargetChoice("When it wakes from standby", "wake_app", true, 1));
        add(MenuView.Row.note(R.drawable.ic_info, "Start-up opens it once, right after the projector starts. "
                + "Waking needs the ≡ helper on (Settings › System › Accessibility)."), null);
        showSheet(focus, () -> openProjectorMenu(projectorBase + 2), null);
    }

    private void openTargetChoice(String title, String key, boolean wake, int row) {
        List<String> names = new ArrayList<>(), ids = new ArrayList<>();
        if (wake) {
            names.add("Stay where it was");
            ids.add("stay");
        }
        names.add("Home");
        ids.add("");
        for (AppRepo.App a : inputApps()) {
            names.add(a.label);
            ids.add(a.pkg);
        }
        for (AppRepo.App a : allApps) {
            names.add(a.label);
            ids.add(a.pkg);
        }
        String cur = prefs.getString(key, wake ? "stay" : "");
        openChoice(crumb(tr(R.string.menu_projector), tr(R.string.menu_projector_startup)), title, names.toArray(new String[0]),
                Math.max(0, ids.indexOf(cur)), i -> {
                    prefs.edit().putString(key, ids.get(i)).apply();
                    toast(title + ": " + names.get(i), true);
                    openStartupMenu(row);
                }, null, () -> openStartupMenu(row));
    }

    /**
     * On the first Home after the projector starts (Android counts boots), opens the start-up
     * app or input once. Holding Back skips it.
     */
    private void maybeLaunchOnBoot() {
        int boots = Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        if (boots < 0 || boots == prefs.getInt("last_boot", -1)) return;
        prefs.edit().putInt("last_boot", boots).apply();
        if (android.os.SystemClock.elapsedRealtime() <= 5 * 60_000L) sounds.startup();
        String target = prefs.getString("boot_app", "");
        // Only right after start-up (not when Home restarts later the same day).
        if (target.isEmpty() || android.os.SystemClock.elapsedRealtime() > 5 * 60_000L) return;
        ui.postDelayed(() -> {
            if (isDestroyed() || !isResumedNow) return;
            for (AppRepo.App a : inputApps()) if (a.pkg.equals(target)) launch(a);
            for (AppRepo.App a : allApps) if (a.pkg.equals(target)) launch(a);
        }, tk.crossfade);
    }

    // Idle: after a while with no button pressed on Home, dim it or show a calm clock.

    private static final int[] IDLE_MINUTES = {2, 5, 10, 30};
    private static final String[] IDLE_MODES = {"Off", "Dim Home", "Show a clock"};
    private final Runnable idleTick = this::enterIdle;
    private FrameLayout idleOverlay;
    private TextView idleClock, idleDate;

    private String idleSummary() {
        int mode = pref("idle_mode", 0, 2);
        return mode == 0 ? "Off" : IDLE_MODES[mode] + " after " + prefs.getInt("idle_min", 5) + " min";
    }

    private void openIdleMenu() {
        String crumb = crumb(tr(R.string.menu_projector));
        startMenu(crumb, tr(R.string.menu_projector_idle));
        int mode = pref("idle_mode", 0, 2);
        add(MenuView.Row.nav(0, tr(R.string.set_projector_idle_do_this), IDLE_MODES[mode]), () -> openChoice(crumbOf(crumb, tr(R.string.crumb_idle)), "When Home is idle",
                IDLE_MODES, mode, i -> {
                    prefs.edit().putInt("idle_mode", i).apply();
                    resetIdle();
                    openIdleMenu();
                }, null, this::openIdleMenu));
        int min = prefs.getInt("idle_min", 5);
        String[] mins = new String[IDLE_MINUTES.length];
        for (int i = 0; i < mins.length; i++) mins[i] = "After " + IDLE_MINUTES[i] + " minutes";
        int cur = Math.max(0, Arrays.stream(IDLE_MINUTES).boxed().collect(java.util.stream.Collectors.toList()).indexOf(min));
        MenuView.Row after = add(MenuView.Row.nav(0, tr(R.string.set_projector_idle_after), min + " minutes"), () -> openChoice(crumbOf(crumb, tr(R.string.crumb_idle)),
                "After", mins, cur, i -> {
                    prefs.edit().putInt("idle_min", IDLE_MINUTES[i]).apply();
                    resetIdle();
                    openIdleMenu();
                }, null, this::openIdleMenu));
        if (mode == 0) after.disabled("Choose what to do first.");
        add(MenuView.Row.note(R.drawable.ic_info, "Only while Home is on screen. Android's screensaver keeps its own "
                + "timer; Leios's clock can be that screensaver too (see the readme)."), null);
        showSheet(0, () -> openProjectorMenu(projectorBase + 3), null);
    }

    /** The press that woke Home from idle. */
    private long wakeDownTime = -1;

    private void resetIdle() {
        ui.removeCallbacks(idleTick);
        if (idleOverlay != null) exitIdle();
        int mode = pref("idle_mode", 0, 2);
        if (mode > 0 && isResumedNow) ui.postDelayed(idleTick, prefs.getInt("idle_min", 5) * 60_000L);
    }

    private void enterIdle() {
        if (!isResumedNow || isDestroyed()) return;
        // Not over a menu or while moving an app: try again later.
        if (area == MENU || moving) {
            resetIdle();
            return;
        }
        boolean clockMode = pref("idle_mode", 0, 2) == 2;
        idleOverlay = new FrameLayout(this);
        idleOverlay.setBackgroundColor(c(clockMode ? R.color.idle_bg : R.color.idle_dim));
        if (clockMode) {
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            idleClock = Ui.text(this, clock.getText(), tk.textDisplay * 1.6f, c(R.color.dark_text), tk.regular);
            idleClock.setAlpha(0.8f);
            idleDate = Ui.text(this, date.getText(), tk.textHeadline, c(R.color.dark_text), tk.regular);
            idleDate.setAlpha(0.55f);
            for (TextView t : new TextView[] {idleClock, idleDate}) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.gravity = Gravity.CENTER_HORIZONTAL;
                col.addView(t, lp);
            }
            idleOverlay.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
            // Nothing to draw behind it while it shows.
            if (player != null) {
                try {
                    player.pause();
                } catch (RuntimeException ignored) {
                }
            }
            if (anim != null) anim.stop();
            stopSlideshow();
        }
        root.addView(idleOverlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        idleOverlay.setAlpha(0f);
        idleOverlay.animate().alpha(1f).setDuration(tk.crossfade * 2L).setInterpolator(Tokens.EASE_IN_OUT).start();
    }

    private void exitIdle() {
        View o = idleOverlay;
        idleOverlay = null;
        idleClock = null;
        idleDate = null;
        if (o == null) return;
        o.animate().alpha(0f).setDuration(tk.medium).withEndAction(() -> root.removeView(o)).start();
        if (player != null && isResumedNow) {
            try {
                player.start();
            } catch (RuntimeException ignored) {
            }
        }
        resumeAnimNow();
        if ("slideshow".equals(currentBg()) && !slideshowOn && isResumedNow) startSlideshow();
    }

    private static final int[] SLEEP_MINUTES = {0, 15, 30, 60, 90, 120};

    private void openSleepMenu() {
        int left = SleepTimer.minutesLeft(this);
        startMenu(crumb(tr(R.string.menu_projector)), "Sleep timer");
        if (left > 0) add(MenuView.Row.note(R.drawable.ic_sleep, "Going to sleep in " + left + " min"), null);
        String[] options = {"Off", "15 minutes", "30 minutes", "1 hour", "1½ hours", "2 hours"};
        for (int i = 0; i < options.length; i++) {
            int n = i;
            add(MenuView.Row.choice(options[i], i == 0 && left == 0), () -> {
                if (n == 0) {
                    SleepTimer.cancel(this);
                    toast("Sleep timer off", true);
                } else setSleep(SLEEP_MINUTES[n]);
                openProjectorMenu(projectorBase);
            });
        }
        showSheet(left > 0 ? 1 : 0, () -> openProjectorMenu(projectorBase), null);
    }

    private void setSleep(int minutes) {
        if (SleepTimer.isReady(this)) {
            SleepTimer.set(this, minutes);
            toast("Going to sleep in " + sleepLabel(minutes), true);
            return;
        }
        // First use: Android asks you to allow the screen-off permission.
        pendingSleepMinutes = minutes;
        try {
            startActivityForResult(new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, SleepTimer.admin(this))
                    .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, getString(R.string.sleep_admin)), REQ_ADMIN);
        } catch (Exception e) {
            toast("This projector doesn't allow a sleep timer", false);
        }
    }

    private static String sleepLabel(int minutes) {
        return minutes < 60 ? minutes + " minutes" : minutes == 60 ? "1 hour" : minutes == 90 ? "1½ hours" : minutes / 60 + " hours";
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_ADMIN) {
            if (SleepTimer.isReady(this) && pendingSleepMinutes > 0) {
                SleepTimer.set(this, pendingSleepMinutes);
                toast("Going to sleep in " + sleepLabel(pendingSleepMinutes), true);
            } else if (pendingSleepMinutes > 0) {
                toast("Sleep timer not set: it needs that permission to turn the projector off", false);
            }
        }
        pendingSleepMinutes = 0;
    }

    // System: search and privacy, backup and reset, about

    private void openBackupMenu(int focus) {
        lastSection = SEC_RESET;
        startMenu(tr(R.string.customize), tr(R.string.menu_system));
        add(MenuView.Row.nav(R.drawable.ic_search, tr(R.string.set_system_search_and_privacy), searchSummary()), () -> openSearchSettings(0));
        add(MenuView.Row.nav(R.drawable.ic_open, tr(R.string.set_system_save_a_backup), ""), this::openBackupSave);
        add(MenuView.Row.nav(R.drawable.ic_reset, tr(R.string.set_system_restore_a_backup), ""), this::openRestoreList);
        boolean auto = prefs.getBoolean("auto_backup", true);
        add(MenuView.Row.action(0, tr(R.string.set_system_automatic_backups)).value(auto ? "On" : "Off"), () -> {
            prefs.edit().putBoolean("auto_backup", !auto).apply();
            openBackupMenu(3);
        });
        add(MenuView.Row.nav(R.drawable.ic_trash, tr(R.string.set_system_reset_everything), ""), this::openResetMenu);
        add(MenuView.Row.nav(R.drawable.ic_info, tr(R.string.set_system_about), BuildConfig.VERSION_NAME), () -> openAboutMenu(0));
        showSheet(focus, backTo(SEC_RESET), null);
    }

    private String searchSummary() {
        switch (SearchPrefs.online(prefs, Titles.hasKey())) {
            case SearchPrefs.ON: return "Online on";
            case SearchPrefs.UNAVAILABLE: return "On this TV";
            default: return "Online off";
        }
    }

    /** Customization › System › Search & privacy. */
    private void openSearchSettings(int focus) {
        String crumb = crumb(tr(R.string.menu_system));
        startMenu(crumb, tr(R.string.menu_system_search));
        int state = SearchPrefs.online(prefs, Titles.hasKey());
        MenuView.Row online = add(MenuView.Row.action(0, tr(R.string.set_system_search_online_title_search))
                .value(state == SearchPrefs.ON ? "On" : "Off"), () -> {
            boolean on = state != SearchPrefs.ON;
            SearchPrefs.setOnline(prefs, on);
            menuSound = on ? Sounds.ON : Sounds.OFF;
            if (!on) loader.execute(() -> Titles.clearCache(this));
            openSearchSettings(0);
        });
        if (state == SearchPrefs.UNAVAILABLE) online.disabled("This copy of Leios was built without a TMDB key, "
                + "so search finds apps and settings only.");
        else online.detail("Sends only what you type and your region to TMDB");
        String region = SearchPrefs.region(prefs, Locale.getDefault());
        boolean autoRegion = prefs.getString("search_region", "").isEmpty();
        MenuView.Row regionRow = add(MenuView.Row.nav(0, tr(R.string.set_system_search_region), autoRegion ? "Automatic (" + region + ")" : region),
                () -> openRegionChoice());
        MenuView.Row elsewhere = add(MenuView.Row.action(0, tr(R.string.set_system_search_titles_not_on_your_apps))
                .value(SearchPrefs.showElsewhere(prefs) ? "Show" : "Hide"), () -> {
            prefs.edit().putBoolean("search_elsewhere", !SearchPrefs.showElsewhere(prefs)).apply();
            openSearchSettings(2);
        });
        if (state != SearchPrefs.ON) {
            regionRow.disabled("For online title search.");
            elsewhere.disabled("For online title search.");
        }
        boolean recent = SearchPrefs.remembersRecent(prefs);
        add(MenuView.Row.action(0, tr(R.string.set_system_search_recent_searches)).value(recent ? "On" : "Off"), () -> {
            prefs.edit().putBoolean("search_recent_on", !recent).apply();
            if (recent) SearchPrefs.clearRecent(prefs);
            menuSound = recent ? Sounds.OFF : Sounds.ON;
            openSearchSettings(3);
        });
        MenuView.Row clear = add(MenuView.Row.action(R.drawable.ic_trash, tr(R.string.set_system_search_clear_recent_searches)), () -> {
            SearchPrefs.clearRecent(prefs);
            toast("Recent searches cleared", true);
            openSearchSettings(4);
        });
        if (SearchPrefs.recent(prefs).isEmpty()) clear.disabled("Nothing to clear.");
        add(MenuView.Row.note(R.drawable.ic_info, "Apps and settings are always found on this TV. Recent searches stay "
                + "on this TV too. Movie and show details come from TMDB, where to watch from JustWatch, and apps' own "
                + "IDs for titles from Wikidata (only the picked title's number is sent there)."), null);
        showSheet(focus, () -> openBackupMenu(0), null);
    }

    private static final String[] REGIONS = {"US", "GB", "CA", "AU", "IE", "NZ", "DE", "FR", "ES", "IT", "NL", "SE",
            "NO", "DK", "FI", "PT", "BR", "MX", "AR", "IN", "JP", "KR"};

    private void openRegionChoice() {
        String[] names = new String[REGIONS.length + 1];
        String device = Locale.getDefault().getCountry();
        names[0] = "Automatic (" + new Locale("", device.isEmpty() ? "US" : device).getDisplayCountry() + ")";
        for (int i = 0; i < REGIONS.length; i++) names[i + 1] = new Locale("", REGIONS[i]).getDisplayCountry();
        String cur = prefs.getString("search_region", "");
        // A region picked before that isn't listed any more counts as Automatic.
        int current = cur.isEmpty() ? 0 : Math.max(0, Arrays.asList(REGIONS).indexOf(cur) + 1);
        openChoice(crumb(tr(R.string.menu_system), tr(R.string.menu_system_search)), "Region", names, current, i -> {
            prefs.edit().putString("search_region", i == 0 ? "" : REGIONS[i - 1]).apply();
            openSearchSettings(1);
        }, null, () -> openSearchSettings(1));
    }

    /** Customization › System › About: version, credits, and the attribution TMDB and JustWatch ask for. */
    private void openAboutMenu(int focus) {
        startMenu(crumb(tr(R.string.menu_system)), tr(R.string.menu_system_about));
        add(MenuView.Row.note(R.drawable.ic_info, "Leios " + BuildConfig.VERSION_NAME + ", free and open source. "
                + "github.com/leioslauncher/leios-launcher"), null);
        // TMDB's logo with its required notice (smaller than Leios's own name above), then the other
        // sources, each in a note of its own so none is cut short.
        MenuView.Row tmdb = MenuView.Row.note(0, "This application uses TMDB and the TMDB APIs but is not endorsed, "
                + "certified, or otherwise approved by TMDB.").image(tk.dp(62), tk.dp(8));
        tmdb.image = getDrawable(R.drawable.tmdb_logo);
        add(tmdb, null);
        add(MenuView.Row.note(R.drawable.ic_search, "Where-to-watch information is provided by JustWatch. Streaming "
                + "apps' own title IDs come from Wikidata (CC0)."), null);
        add(MenuView.Row.note(R.drawable.ic_info, "Licences for fonts, icons and other parts: THIRD_PARTY_NOTICES.md "
                + "in the project."), null);
        showSheet(focus, () -> openBackupMenu(5), null);
        menu.setHints("BACK", "Back");
    }

    /** Before anything big: your current settings, in the restore list (unless you turned this off). */
    private void autoBackup(String reason) {
        if (prefs.getBoolean("auto_backup", true)) Backups.auto(this, reason);
    }

    private void openBackupSave() {
        String crumb = crumb(tr(R.string.menu_system));
        startMenu(crumb, "Save a backup");
        add(MenuView.Row.note(R.drawable.ic_info, "Everything you've set on Home, with your photo and icons, as a "
                + "picture in Pictures › Leios. Restore it here, or on another projector."), null);
        for (String vol : Backups.volumes(this)) {
            boolean tv = Media.primary().equals(vol);
            add(MenuView.Row.action(R.drawable.ic_open, tv ? "On this TV" : "On the USB drive"), () -> {
                toast("Saving the backup…", false);
                Bitmap card = previewCard("Leios backup", Backups.when(System.currentTimeMillis()));
                loader.execute(() -> {
                    Uri uri = null;
                    try {
                        uri = Backups.export(this, Backups.snapshot(this, true), "Leios backup " + Backups.stamp(), vol, card);
                    } catch (JSONException ignored) {
                    }
                    Uri saved = uri;
                    ui.post(() -> toast(saved == null ? "Couldn't save the backup" : "Backup saved in Pictures › Leios", saved != null));
                });
                openBackupMenu(1);
            });
        }
        if (Backups.volumes(this).isEmpty()) add(MenuView.Row.note(R.drawable.ic_info,
                "Saving a backup file needs Android 10 or newer. Automatic backups still work."), null);
        showSheet(1, () -> openBackupMenu(1), null);
    }

    private void openRestoreList() {
        String crumb = crumb(tr(R.string.menu_system));
        startMenu(crumb, "Restore a backup");
        add(MenuView.Row.loading("Looking for backups…"), null);
        showSheet(0, () -> openBackupMenu(2), null);
        boolean pictures = checkSelfPermission(imagePermission()) == PackageManager.PERMISSION_GRANTED;
        loader.execute(() -> {
            List<Backups.Entry> list = Backups.list(this, pictures);
            ui.post(() -> {
                if (isDestroyed() || area != MENU || !crumb.equals(menuCrumb)) return;
                startMenu(crumb, "Restore a backup");
                for (Backups.Entry e : list) {
                    add(MenuView.Row.nav(0, e.label, Backups.when(e.time)), () -> confirmRestore(e));
                }
                if (list.isEmpty()) add(MenuView.Row.note(R.drawable.ic_info, "No backups yet. One is made "
                        + "automatically after each update and before big changes."), null);
                showSheet(0, () -> openBackupMenu(2), null);
            });
        });
    }

    private void confirmRestore(Backups.Entry e) {
        String crumb = crumb(tr(R.string.menu_system), tr(R.string.crumb_restore));
        startMenu(crumb, "Restore " + e.label + "?");
        add(MenuView.Row.note(R.drawable.ic_info, "Home goes back to how it was on " + Backups.when(e.time)
                + ". What you have now is backed up first, so you can come back to it."), null);
        add(MenuView.Row.action(R.drawable.ic_close, "Cancel"), this::openRestoreList);
        add(MenuView.Row.action(R.drawable.ic_reset, "Restore"), () -> loader.execute(() -> {
            JSONObject backup;
            try {
                backup = Backups.read(this, e);
                if (!"backup".equals(backup.optString("kind"))) throw new IOException("not a backup");
            } catch (IOException ex) {
                ui.post(() -> toast("That backup can't be read. Nothing was changed.", false));
                return;
            }
            Backups.auto(this, "Before restore");
            boolean ok;
            try {
                Backups.apply(this, backup, null);
                ok = true;
            } catch (JSONException ex) {
                ok = false;
            }
            boolean done = ok;
            ui.post(() -> {
                if (!done) {
                    toast("That backup can't be read. Nothing was changed.", false);
                    return;
                }
                prefs.edit().putString("toast", "Backup restored").putInt("area", TOP).apply();
                Tokens.invalidate();
                recreate();
            });
        }));
        // Cancel is preselected.
        showSheet(1, this::openRestoreList, null);
    }

    // commit, not apply: the settings must be on disk before Home restarts itself.
    @android.annotation.SuppressLint("ApplySharedPref")
    private void openResetMenu() {
        lastSection = SEC_RESET;
        startMenu(crumb(tr(R.string.menu_system)), "Reset everything?");
        add(MenuView.Row.note(R.drawable.ic_info, "Background, dock, categories, hidden apps, weather and look go "
                + "back to how they started. Your apps and the projector's settings aren't touched. A backup is "
                + "made first."), null);
        add(MenuView.Row.action(R.drawable.ic_close, "Cancel"), () -> openBackupMenu(4));
        add(MenuView.Row.action(R.drawable.ic_reset, "Reset everything"), () -> {
            Backups.auto(this, "Before reset");
            stopSlideshow();
            stopVideo();
            stopAnim();
            SleepTimer.cancel(this);
            prefs.edit().clear().putString("toast", "Home is back to how it started. System › Restore a backup undoes this.")
                    .commit();
            usage.edit().clear().commit();
            //noinspection ResultOfMethodCallIgnored
            photoFile().delete();
            //noinspection ResultOfMethodCallIgnored
            animFile().delete();
            deleteRecursively(new File(getFilesDir(), "icons"));
            Tokens.invalidate();
            recreate();
        });
        // Cancel is preselected.
        showSheet(1, () -> openBackupMenu(4), null);
    }

    // ---- network pill: event driven, no polling ---------------------------------------------

    private final class NetWatch extends ConnectivityManager.NetworkCallback {
        NetWatch() {
            super();
        }

        @androidx.annotation.RequiresApi(31)
        NetWatch(int flags) {
            super(flags);
        }

        @Override
        public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
            ui.post(() -> showNetwork(caps));
        }

        @Override
        public void onLost(Network network) {
            ui.post(() -> showNetwork(null));
        }
    }

    private void startNetworkWatch() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm == null || netWatch != null) return;
        netWatch = Build.VERSION.SDK_INT >= 31
                ? new NetWatch(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO)
                : new NetWatch();
        try {
            cm.registerDefaultNetworkCallback(netWatch);
        } catch (RuntimeException e) {
            netWatch = null;
        }
        // No callback fires when there's no network at all, so say so up front.
        if (cm.getActiveNetwork() == null) showNetwork(null);
    }

    private void stopNetworkWatch() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm != null && netWatch != null) {
            try {
                cm.unregisterNetworkCallback(netWatch);
            } catch (RuntimeException ignored) {
            }
        }
        netWatch = null;
    }

    private int wifiLevelIcon = R.drawable.ic_wifi;

    private void showNetwork(NetworkCapabilities caps) {
        String label;
        if (caps == null) label = "Offline";
        else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) label = "Ethernet";
        else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) label = ssid(caps);
        else label = "Online";
        // Signal strength as one, two or three arcs.
        int icon = R.drawable.ic_wifi;
        if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && Build.VERSION.SDK_INT >= 30) {
            WifiManager wm = getApplicationContext().getSystemService(WifiManager.class);
            int rssi = caps.getSignalStrength();
            if (wm != null && rssi != NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED) {
                float f = wm.calculateSignalLevel(rssi) / (float) Math.max(1, wm.getMaxSignalLevel());
                icon = f < 0.34f ? R.drawable.ic_wifi_1 : f < 0.67f ? R.drawable.ic_wifi_2 : R.drawable.ic_wifi;
            }
        }
        if (icon != wifiLevelIcon) {
            wifiLevelIcon = icon;
            wifiIcon.setImageResource(icon);
        }
        if (label.contentEquals(wifiLabel.getText())) return;
        wifiLabel.setText(label);
        if (area == TOP) afterLayout(pills[PILL_WIFI], () -> applyFocus(true));
    }

    @SuppressWarnings("deprecation")
    private String ssid(NetworkCapabilities caps) {
        String s = null;
        if (Build.VERSION.SDK_INT >= 29) {
            TransportInfo ti = caps.getTransportInfo();
            if (ti instanceof WifiInfo) s = ((WifiInfo) ti).getSSID();
        }
        if (s == null || s.contains("unknown ssid")) {
            WifiManager wm = getApplicationContext().getSystemService(WifiManager.class);
            WifiInfo info = wm == null ? null : wm.getConnectionInfo();
            if (info != null) s = info.getSSID();
        }
        if (s == null || s.contains("unknown ssid") || s.isEmpty()) return "Wi-Fi";
        if (s.length() > 1 && s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length() - 1);
        return s;
    }

    // ---- helpers ----------------------------------------------------------------------------

    /** Package and clock broadcasts come from the system; before Android 13 there were no flags to give. */
    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void register(BroadcastReceiver r, IntentFilter f) {
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(r, f);
    }

    private static void afterLayout(View v, Runnable r) {
        v.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                v.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                r.run();
            }
        });
        v.requestLayout();
    }
}
