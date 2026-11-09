package tv.leios.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Search on Home: a field, a D-pad keyboard, and results grouped as Apps, Settings, then Movies &
 * TV (only with online title search on). It sits over Home and owns the keys while it shows; Back
 * steps out one level (results → keyboard → closed). Apps and settings are found on the TV
 * instantly; titles come from TMDB a moment after typing stops, and nothing typed leaves the TV
 * unless online search is on.
 */
final class SearchOverlay extends FrameLayout {

    /** What search needs from Home. */
    interface Host {
        /** Apps search may show: not ones hidden from Home (see SearchPrefs.appVisible). */
        List<AppRepo.App> searchableApps();

        /** Apps that are locked right now: they show with "Locked", and opening one asks for the PIN. */
        Set<String> lockedPackages();

        void openApp(AppRepo.App app);

        boolean settingsAllowed();

        boolean settingAvailable(SettingsIndex.Entry e);

        void openSetting(SettingsIndex.Entry e);

        void openSearchSettings();

        /** Installed apps that search may open titles in (same rules as searchableApps). */
        Set<String> openablePackages();

        Map<String, Integer> uses();

        /** Opens a title in one of your apps: its own page where the app allows it, else its search, else the app. */
        void openTitle(AppLinks.App app, Titles.Title title);

        void sound(int id, boolean repeat);

        void toast(String message);

        /** Search finished closing; Home takes the keys back. */
        void closed();
    }

    private static final int KEYS = 0, RESULTS = 1;
    private static final int MAX_APPS = 5, MAX_SETTINGS = 4, MAX_TITLES = 6, MAX_ELSEWHERE = 3;
    private static final long DEBOUNCE_MS = 450, ONLINE_TIMEOUT_MS = 8000;

    private final Host host;
    private final Theme th;
    private final Tokens tk;
    private final SharedPreferences prefs;
    private final View scrim;
    private final LinearLayout content;
    private final TextView field, ghost;
    private final View attribution;
    private final View caret;
    private final SearchKeyboard keyboard;
    private final MenuView results;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    /** A few service lookups at once (TMDB allows far more; this keeps the TV's CPU calm). */
    private final ExecutorService services = Executors.newFixedThreadPool(3);

    private final StringBuilder query = new StringBuilder();
    private int zone = KEYS;
    private boolean open;
    /** What each results row does (null for headings and notes). */
    private final List<Runnable> actions = new ArrayList<>();
    /** A stable name for each row, so focus stays put when results refresh. */
    private final List<String> rowKeys = new ArrayList<>();

    /** Online results for {@link #onlineQuery}; null while loading. */
    private String onlineQuery = "";
    private List<Found> online;
    private String onlineProblem;
    private int seq;
    /** Picking a title on more than one app: the apps to choose from. */
    private Found choosing;
    private int focusBeforeChooser;

    /** A title and the apps of yours that have it (to watch first, then to rent or buy). */
    private static final class Found {
        final Titles.Title title;
        final List<AppLinks.Offer> offers;
        Drawable poster;

        Found(Titles.Title title, List<AppLinks.Offer> offers) {
            this.title = title;
            this.offers = offers;
        }
    }

    SearchOverlay(Context c, Theme th, Tokens tk, SharedPreferences prefs, Host host) {
        super(c);
        this.th = th;
        this.tk = tk;
        this.prefs = prefs;
        this.host = host;
        setVisibility(GONE);
        setClickable(true);

        // One flat fill over Home: cheap on this GPU (no blur), and it keeps text readable on any photo.
        scrim = new View(c);
        // Nearly solid: at 94 % the clock and tiles still showed through in light mode, as clutter.
        scrim.setBackgroundColor(Ui.alpha(th.bg | 0xFF000000, 0.98f));
        addView(scrim, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        content = new LinearLayout(c);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(tk.safeH, tk.safeV, tk.safeH, tk.safeV);
        addView(content, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // The field: what you've typed, with a caret, in the same frosted chip as the top bar.
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(tk.space5, 0, tk.space5, 0);
        box.setBackground(Ui.rounded(tk.radiusLg, th.surface, th.stroke, tk.stroke));
        ImageView icon = Ui.icon(c, R.drawable.ic_search, tk.iconMd, th.textSecondary);
        ((LinearLayout.LayoutParams) icon.getLayoutParams()).setMarginEnd(tk.space3);
        box.addView(icon);
        field = Ui.text(c, "", tk.textHeadline, th.text, tk.medium500);
        box.addView(field);
        caret = new View(c);
        caret.setBackgroundColor(th.accent);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(tk.dp(2), Math.round(tk.textHeadline * 1.1f));
        clp.setMarginStart(tk.dp(2));
        box.addView(caret, clp);
        // The rest of a popular title that starts with what you've typed, in grey; Up takes it.
        ghost = Ui.text(c, "", tk.textHeadline, th.textTertiary, tk.medium500);
        box.addView(ghost);
        content.addView(box, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                tk.pillHeight + tk.space3));

        LinearLayout body = new LinearLayout(c);
        body.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        blp.topMargin = tk.space5;
        content.addView(body, blp);

        keyboard = new SearchKeyboard(c, th, tk, false);
        body.addView(keyboard, new LinearLayout.LayoutParams(tk.dp(336), ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout right = new LinearLayout(c);
        right.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        rlp.setMarginStart(tk.space6);
        body.addView(right, rlp);
        results = new MenuView(c, th);
        results.setHeader(null, null);
        right.addView(results, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        // Where online results come from (TMDB, and JustWatch for where to watch): only while they show.
        // TMDB's terms ask for its logo with the credit, smaller than Leios's own name.
        LinearLayout credit = new LinearLayout(c);
        credit.setOrientation(LinearLayout.HORIZONTAL);
        credit.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(c);
        logo.setImageResource(R.drawable.tmdb_logo);
        logo.setContentDescription("TMDB");
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(tk.dp(77), tk.dp(10));
        llp.setMarginEnd(tk.space3);
        credit.addView(logo, llp);
        TextView creditText = Ui.text(c, "Details from TMDB  ·  Where to watch from JustWatch",
                tk.textCaption, th.textTertiary, tk.regular);
        creditText.setSingleLine(false);
        creditText.setMaxLines(2);
        credit.addView(creditText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        attribution = credit;
        attribution.setVisibility(GONE);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = tk.space3;
        alp.setMarginStart(tk.space4);
        right.addView(attribution, alp);
        parts = new View[] {box, keyboard, right};
        scrim.setPivotX(0f);
        scrim.setPivotY(0f);
    }

    /** Field, keyboard, results: they come in in this order, a moment apart. */
    private final View[] parts;

    boolean isOpen() {
        return open;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        int top = tk.safeV + tk.pillHeight + tk.space3 + tk.space5;
        int footer = attribution.getVisibility() == VISIBLE ? tk.space3 + Math.round(tk.textCaption * 1.4f) : 0;
        results.setMaxHeight(Math.max(tk.rowHeight * 3, h - top - tk.safeV - footer));
    }

    // ---- open and close -----------------------------------------------------------------------

    /**
     * Shows search. Keys work at once, during the animation. {@code pulled} (Up at the top of
     * Home) and the other ways in look the same: search always drops from the top edge.
     */
    void show(boolean pulled) {
        open = true;
        zone = KEYS;
        choosing = null;
        query.setLength(0);
        keyboard.reset();
        keyboard.setActive(true);
        results.setActive(false);
        refresh(true);
        enter();
        loadPopular();
        host.sound(Sounds.OPEN, false);
    }

    /** The reverse of {@link #show}; Home gets the keys back straight away. */
    void hide(boolean pulled) {
        if (!open) return;
        open = false;
        seq++;
        ui.removeCallbacks(lookUp);
        exit();
        host.closed();
    }

    /** Coming back from a setting opened from search: as it was, with the same text. */
    void reshow() {
        open = true;
        refresh(false);
        enter();
    }

    // Motion, adapted from Subframe's "Curtain index" (MIT; see THIRD_PARTY_NOTICES.md): a backdrop
    // drops from the top edge on an ease-in-out, then the field, keyboard and results fade in and
    // settle 12 dp downwards, 35 ms apart, on a strong ease-out. Leaving is quicker, with no stagger.
    // Shortened for a TV remote (about 300 ms in all, 220 ms out), and only scale, translation and
    // opacity move, so this GPU draws the backdrop once and moves layers.
    private static final android.view.animation.Interpolator CURTAIN =
            new android.view.animation.PathInterpolator(0.76f, 0f, 0.24f, 1f);
    private static final android.view.animation.Interpolator SETTLE =
            new android.view.animation.PathInterpolator(0.2f, 0.8f, 0.2f, 1f);
    private static final int CURTAIN_IN = 260, CURTAIN_OUT = 200, PART_DELAY = 70, PART_STAGGER = 35,
            PART_MOVE = 220, PART_FADE = 150, PART_OUT = 130;

    /** Android's "Remove animations" (or animator scale 0): no movement, just appear. */
    private static boolean motionOff() {
        return !android.animation.ValueAnimator.areAnimatorsEnabled();
    }

    private void enter() {
        setVisibility(VISIBLE);
        setAlpha(1f);
        scrim.animate().cancel();
        int travel = tk.space3;
        boolean still = motionOff();
        scrim.setScaleY(still ? 1f : 0f);
        if (!still) scrim.animate().scaleY(1f).setStartDelay(0).setDuration(CURTAIN_IN).setInterpolator(CURTAIN)
                .withLayer().start();
        cancelFades();
        for (int i = 0; i < parts.length; i++) {
            View p = parts[i];
            p.animate().cancel();
            if (still) {
                p.setAlpha(1f);
                p.setTranslationY(0);
                continue;
            }
            p.setAlpha(0f);
            p.setTranslationY(-travel);
            long delay = PART_DELAY + (long) i * PART_STAGGER;
            p.animate().translationY(0).setStartDelay(delay).setDuration(PART_MOVE).setInterpolator(SETTLE).start();
            // The fade is shorter than the move, so the text is readable before it finishes settling.
            android.animation.ObjectAnimator fade = android.animation.ObjectAnimator.ofFloat(p, View.ALPHA, 0f, 1f);
            fade.setStartDelay(delay);
            fade.setDuration(PART_FADE);
            fade.setInterpolator(Tokens.EASE_OUT);
            fade.start();
            fades.add(fade);
        }
    }

    private final List<android.animation.Animator> fades = new ArrayList<>();

    private void cancelFades() {
        for (android.animation.Animator a : fades) a.cancel();
        fades.clear();
    }

    private void exit() {
        cancelFades();
        if (motionOff()) {
            setVisibility(GONE);
            return;
        }
        int travel = tk.space3;
        for (View p : parts) {
            p.animate().cancel();
            p.animate().alpha(0f).translationY(-travel).setStartDelay(0).setDuration(PART_OUT)
                    .setInterpolator(Tokens.EASE_IN_OUT).start();
        }
        scrim.animate().cancel();
        scrim.animate().scaleY(0f).setStartDelay(PART_OUT / 2).setDuration(CURTAIN_OUT).setInterpolator(CURTAIN)
                .withLayer().withEndAction(() -> {
                    if (!open) setVisibility(GONE);
                }).start();
    }

    /** Hidden at once (a setting from search opens in its place; Home was pressed). */
    void hideNow() {
        open = false;
        seq++;
        ui.removeCallbacks(lookUp);
        cancelFades();
        scrim.animate().cancel();
        for (View p : parts) p.animate().cancel();
        setVisibility(GONE);
    }

    void destroy() {
        net.shutdownNow();
        services.shutdownNow();
        ui.removeCallbacksAndMessages(null);
    }

    // ---- keys ---------------------------------------------------------------------------------

    /** All keys while search shows; false only for keys Home never handles (volume, media). */
    boolean onKey(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        boolean repeat = e.getRepeatCount() > 0;
        boolean select = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BUTTON_A;
        if (passesThrough(code)) return false;
        if (!down) return true;
        switch (code) {
            case KeyEvent.KEYCODE_BACK:
                // On the keyboard with text typed, Back deletes a letter; held, it keeps deleting
                // and stops at an empty field. Only a fresh press on an empty field closes search.
                if (zone == KEYS && choosing == null && query.length() > 0) type(SearchKeyboard.DELETE, repeat);
                else if (!repeat) back();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                move(code, repeat);
                return true;
            case KeyEvent.KEYCODE_DEL:
                type(SearchKeyboard.DELETE, repeat);
                return true;
            default:
                break;
        }
        if (select) {
            if (zone == KEYS) {
                String v = keyboard.focusedValue();
                // Holding OK on Delete keeps deleting; other keys type once per press.
                if (!repeat || SearchKeyboard.DELETE.equals(v)) type(v, repeat);
            } else if (!repeat) pick();
            return true;
        }
        // A real keyboard, or text typed with adb: letters, digits and punctuation go in as typed.
        int ch = e.getUnicodeChar();
        if (ch > 0 && !Character.isISOControl(ch)) {
            type(String.valueOf((char) ch).toLowerCase(Locale.ROOT), repeat);
            return true;
        }
        return true;
    }

    /** Volume and media buttons keep doing what they always do. */
    private static boolean passesThrough(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
            case KeyEvent.KEYCODE_MUTE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_STOP:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_HEADSETHOOK:
            case KeyEvent.KEYCODE_POWER:
            case KeyEvent.KEYCODE_TV_INPUT:
                return true;
            default:
                return false;
        }
    }

    private void move(int code, boolean repeat) {
        boolean up = code == KeyEvent.KEYCODE_DPAD_UP, down = code == KeyEvent.KEYCODE_DPAD_DOWN;
        boolean left = code == KeyEvent.KEYCODE_DPAD_LEFT, right = code == KeyEvent.KEYCODE_DPAD_RIGHT;
        if (zone == KEYS) {
            // Up from the keyboard's top row takes the grey completion, if there is one.
            if (up && !repeat && keyboard.atTopRow() && suggestion != null) {
                query.setLength(0);
                query.append(suggestion);
                host.sound(Sounds.SELECT, false);
                refresh(true);
                return;
            }
            if (right && keyboard.atRightEdge()) {
                if (results.hasChoices()) {
                    setZone(RESULTS);
                    host.sound(Sounds.FOCUS, repeat);
                }
                return;
            }
            if (keyboard.move(left ? -1 : right ? 1 : 0, up ? -1 : down ? 1 : 0)) host.sound(Sounds.FOCUS, repeat);
            return;
        }
        if (left) {
            setZone(KEYS);
            host.sound(Sounds.FOCUS, repeat);
        } else if (up || down) {
            if (results.moveFocus(up ? -1 : 1)) host.sound(Sounds.FOCUS, repeat);
        }
    }

    private void back() {
        if (choosing != null) {
            choosing = null;
            refresh(false);
            results.setFocus(Math.min(focusBeforeChooser, results.size() - 1), false);
            host.sound(Sounds.BACK, false);
        } else if (zone == RESULTS) {
            setZone(KEYS);
            host.sound(Sounds.BACK, false);
        } else {
            host.sound(Sounds.CLOSE, false);
            hide(false);
        }
    }

    private void setZone(int z) {
        zone = z;
        keyboard.setActive(z == KEYS);
        results.setActive(z == RESULTS);
        if (z == RESULTS) {
            int f = results.focus();
            if (f < 0 || f >= results.size() || !results.row(f).enabled()) {
                results.setFocus(0, false);
                if (results.size() > 0 && results.row(0).skipped()) results.moveFocus(1);
            }
        }
        hints();
    }

    private void type(String v, boolean repeat) {
        int before = query.length();
        if (SearchKeyboard.DELETE.equals(v)) {
            if (before > 0) query.setLength(before - 1);
        } else if (SearchKeyboard.CLEAR.equals(v)) {
            query.setLength(0);
        } else if (SearchKeyboard.SPACE.equals(v)) {
            if (before > 0 && query.charAt(before - 1) != ' ') query.append(' ');
        } else if (query.length() < 60) {
            query.append(v);
        }
        boolean changed = query.length() != before || SearchKeyboard.CLEAR.equals(v);
        if (!changed) return;
        host.sound(Sounds.STEP, repeat);
        choosing = null;
        refresh(true);
    }

    private void pick() {
        int f = results.focus();
        if (f < 0 || f >= actions.size()) return;
        MenuView.Row row = results.row(f);
        if (!row.enabled()) {
            if (row.disabledReason != null) results.flash(row.disabledReason);
            host.sound(Sounds.ERROR, false);
            return;
        }
        Runnable a = actions.get(f);
        if (a == null) return;
        host.sound(Sounds.SELECT, false);
        a.run();
    }

    // ---- results ------------------------------------------------------------------------------

    private String q() {
        return query.toString().trim();
    }

    /** Rebuilds the results for what's typed. {@code typed}: the text changed, so look online again. */
    private void refresh(boolean typed) {
        String q = q();
        field.setText(query.length() == 0 ? placeholder() : query);
        field.setTextColor(query.length() == 0 ? th.textTertiary : th.text);
        caret.setVisibility(open ? VISIBLE : GONE);
        showSuggestion();
        if (typed) {
            ui.removeCallbacks(lookUp);
            if (!q.equalsIgnoreCase(onlineQuery)) {
                seq++;
                online = null;
                onlineProblem = null;
                onlineQuery = q;
                if (onlineState() == SearchPrefs.ON && q.length() >= 2) ui.postDelayed(lookUp, DEBOUNCE_MS);
            }
            // The letter shows at once; results follow a moment later, once for a quick run of
            // letters (a held Delete, fast typing) instead of once per letter.
            ui.removeCallbacks(renderSoon);
            ui.postDelayed(renderSoon, RENDER_DELAY_MS);
            return;
        }
        render();
    }

    private static final long RENDER_DELAY_MS = 70;
    private final Runnable renderSoon = this::render;

    private String placeholder() {
        return onlineState() == SearchPrefs.ON ? "Apps, settings, movies and shows" : "Apps and settings";
    }

    private int onlineState() {
        return SearchPrefs.online(prefs, Titles.hasKey());
    }

    private void render() {
        String keep = results.focus() >= 0 && results.focus() < rowKeys.size() ? rowKeys.get(results.focus()) : null;
        List<MenuView.Row> rows = new ArrayList<>();
        actions.clear();
        rowKeys.clear();
        if (choosing != null) renderChooser(rows);
        else if (q().isEmpty()) renderEmpty(rows);
        else renderResults(rows);
        int focus = keep == null ? 0 : Math.max(0, rowKeys.indexOf(keep));
        results.setRows(rows, focus, false);
        if (zone == RESULTS && !results.hasChoices()) setZone(KEYS);
        else hints();
        boolean credit = choosing == null && onlineState() == SearchPrefs.ON && !q().isEmpty();
        if ((attribution.getVisibility() == VISIBLE) != credit) {
            attribution.setVisibility(credit ? VISIBLE : GONE);
            requestLayout();
            onSizeChanged(getWidth(), getHeight(), getWidth(), getHeight());
        }
    }

    private void row(List<MenuView.Row> rows, MenuView.Row r, String key, Runnable action) {
        rows.add(r);
        rowKeys.add(key);
        actions.add(action);
    }

    private void renderEmpty(List<MenuView.Row> rows) {
        List<String> recent = SearchPrefs.remembersRecent(prefs) ? SearchPrefs.recent(prefs) : new ArrayList<>();
        if (recent.isEmpty()) {
            row(rows, MenuView.Row.note(R.drawable.ic_info, onlineState() == SearchPrefs.ON
                    ? "Type to find apps, Leios settings, movies and shows."
                    : "Type to find apps and Leios settings."), "hint", null);
            return;
        }
        row(rows, MenuView.Row.header("Recent searches"), "h:recent", null);
        for (String s : recent) {
            row(rows, MenuView.Row.action(R.drawable.ic_search, s), "r:" + s, () -> {
                query.setLength(0);
                query.append(s);
                setZone(KEYS);
                refresh(true);
            });
        }
        row(rows, MenuView.Row.action(R.drawable.ic_trash, "Clear recent searches"), "r:clear", () -> {
            SearchPrefs.clearRecent(prefs);
            setZone(KEYS);
            render();
            host.toast("Recent searches cleared");
        });
    }

    /** App names made ready for matching, once each (apps rarely change names). */
    private final Map<String, SearchRank.Prepared> preparedNames = new HashMap<>();

    private void renderResults(List<MenuView.Row> rows) {
        String q = q();
        boolean any = false;

        // Apps: on this TV, instant.
        List<AppRepo.App> apps = new ArrayList<>(host.searchableApps());
        List<Integer> scores = new ArrayList<>();
        List<AppRepo.App> found = new ArrayList<>();
        SearchRank.Prepared pq = SearchRank.prepare(q);
        for (AppRepo.App a : apps) {
            SearchRank.Prepared name = preparedNames.get(a.label);
            if (name == null) {
                name = SearchRank.prepare(a.label);
                preparedNames.put(a.label, name);
            }
            int s = SearchRank.score(pq, name);
            if (s <= 0) continue;
            int at = 0;
            while (at < scores.size() && scores.get(at) >= s) at++;
            scores.add(at, s);
            found.add(at, a);
        }
        if (!found.isEmpty()) {
            any = true;
            row(rows, MenuView.Row.header("Apps"), "h:apps", null);
            int iconSize = tk.dp(34);
            Set<String> locked = host.lockedPackages();
            for (int i = 0; i < Math.min(MAX_APPS, found.size()); i++) {
                AppRepo.App a = found.get(i);
                boolean banner = a.icon == null;
                MenuView.Row r = MenuView.Row.action(0, a.label)
                        .image(banner ? tk.dp(60) : iconSize, banner ? tk.dp(34) : iconSize)
                        .value(locked.contains(a.pkg) ? "Locked" : "");
                r.image = banner ? a.banner : a.icon;
                row(rows, r, "a:" + a.pkg, () -> remember(() -> host.openApp(a)));
            }
        }

        // Movies and shows come before settings (most searches are for something to watch).
        any |= renderTitles(rows);

        // Leios's own settings (none in guest mode).
        if (host.settingsAllowed()) {
            List<SettingsIndex.Entry> settings = new ArrayList<>();
            for (SettingsIndex.Entry e : SettingsIndex.search(q, 20)) {
                if (host.settingAvailable(e)) settings.add(e);
                if (settings.size() >= MAX_SETTINGS) break;
            }
            if (!settings.isEmpty()) {
                any = true;
                row(rows, MenuView.Row.header("Settings"), "h:settings", null);
                for (SettingsIndex.Entry e : settings) {
                    row(rows, MenuView.Row.nav(R.drawable.ic_settings, e.label, "").detail(e.crumb()),
                            "s:" + e.menu + "/" + e.label, () -> remember(() -> host.openSetting(e)));
                }
            }
        }

        if (!any) {
            row(rows, MenuView.Row.note(R.drawable.ic_info, "Nothing matches “" + q + "”."
                    + (onlineState() == SearchPrefs.OFF ? " Online title search is off (Customization › System › Search & privacy)." : "")),
                    "none", null);
        }
    }

    /** Movies & TV: the one-time question, a spinner, a calm message, or the titles. True if it showed titles. */
    private boolean renderTitles(List<MenuView.Row> rows) {
        String q = q();
        int state = onlineState();
        if (state == SearchPrefs.UNAVAILABLE || state == SearchPrefs.OFF) return false;
        if (state == SearchPrefs.UNDECIDED) {
            if (q.length() < 2) return false;
            row(rows, MenuView.Row.header("Movies & TV"), "h:titles", null);
            row(rows, MenuView.Row.note(R.drawable.ic_info, "Find movies and shows too? Leios would send only what you type and your region ("
                    + region() + ") to TMDB, a film database, and when you pick a title, its ID number to Wikidata to open it "
                    + "in your app. Nothing about you or this TV. You can change this later in "
                    + "Customization › System › Search & privacy."), "ask", null);
            row(rows, MenuView.Row.action(R.drawable.ic_check, "Turn on online search"), "ask:on", () -> {
                SearchPrefs.setOnline(prefs, true);
                onlineQuery = "";
                refresh(true);
                setZone(KEYS);
                loadPopular();
            });
            row(rows, MenuView.Row.action(R.drawable.ic_close, "Not now"), "ask:off", () -> {
                SearchPrefs.setOnline(prefs, false);
                setZone(KEYS);
                refresh(false);
                host.toast("Online search is off. Customization › System › Search & privacy turns it on.");
            });
            return true;
        }
        if (q.length() < 2) return false;
        if (onlineProblem != null) {
            row(rows, MenuView.Row.header("Movies & TV"), "h:titles", null);
            row(rows, MenuView.Row.note(R.drawable.ic_info, onlineProblem), "problem", null);
            return true;
        }
        if (online == null) {
            row(rows, MenuView.Row.header("Movies & TV"), "h:titles", null);
            row(rows, MenuView.Row.loading("Looking up movies and shows…"), "loading", null);
            return true;
        }
        List<Found> yours = new ArrayList<>(), elsewhere = new ArrayList<>();
        for (Found f : online) (f.offers.isEmpty() ? elsewhere : yours).add(f);
        boolean showElsewhere = SearchPrefs.showElsewhere(prefs);
        if (yours.isEmpty() && (elsewhere.isEmpty() || !showElsewhere)) {
            row(rows, MenuView.Row.header("Movies & TV"), "h:titles", null);
            row(rows, MenuView.Row.note(R.drawable.ic_info, online.isEmpty() ? "No movies or shows match."
                    : "None of your apps has a matching movie or show."), "notitles", null);
            return true;
        }
        int posterW = tk.dp(34), posterH = tk.dp(50);
        if (!yours.isEmpty()) {
            row(rows, MenuView.Row.header("Movies & TV on your apps"), "h:titles", null);
            for (int i = 0; i < Math.min(MAX_TITLES, yours.size()); i++) {
                Found f = yours.get(i);
                MenuView.Row r = MenuView.Row.action(0, f.title.name).image(posterW, posterH)
                        .detail(describe(f.title)).value(appNames(f.offers));
                r.image = f.poster;
                row(rows, r, "t:" + f.title.tv + f.title.id, () -> remember(() -> openTitle(f)));
            }
        }
        if (showElsewhere && !elsewhere.isEmpty()) {
            row(rows, MenuView.Row.header("Not on your apps"), "h:elsewhere", null);
            for (int i = 0; i < Math.min(MAX_ELSEWHERE, elsewhere.size()); i++) {
                Found f = elsewhere.get(i);
                MenuView.Row r = MenuView.Row.action(0, f.title.name).image(posterW, posterH)
                        .detail(describe(f.title)).quiet().disabled(null);
                r.image = f.poster;
                row(rows, r, "e:" + f.title.tv + f.title.id, () -> {
                    results.flash("None of your apps has " + f.title.name + " in " + region() + ".");
                    host.sound(Sounds.ERROR, false);
                });
            }
        }
        return true;
    }

    private static String describe(Titles.Title t) {
        String kind = t.tv ? "TV show" : "Movie";
        return t.year.isEmpty() ? kind : t.year + "  ·  " + kind;
    }

    /** "Netflix  ·  Hulu", "YouTube (rent)": where you can watch it, at most two names. */
    private static String appNames(List<AppLinks.Offer> offers) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < offers.size() && i < 2; i++) {
            AppLinks.Offer o = offers.get(i);
            sb.append(i == 0 ? "" : "  ·  ").append(o.app.name).append(o.rent ? " (rent)" : "");
        }
        if (offers.size() > 2) sb.append("  +").append(offers.size() - 2);
        return sb.toString();
    }

    private void renderChooser(List<MenuView.Row> rows) {
        row(rows, MenuView.Row.header("Open " + choosing.title.name + " in"), "h:choose", null);
        for (AppLinks.Offer o : choosing.offers) {
            Titles.Title t = choosing.title;
            row(rows, MenuView.Row.action(0, o.app.name).value(o.rent ? "Rent or buy" : ""),
                    "c:" + o.app.pkg, () -> host.openTitle(o.app, t));
        }
    }

    private void openTitle(Found f) {
        if (f.offers.size() == 1) {
            host.openTitle(f.offers.get(0).app, f.title);
            return;
        }
        focusBeforeChooser = results.focus();
        choosing = f;
        render();
        results.setFocus(1, false);
    }

    /** What you search for is remembered (if Recent searches is on) once you open something. */
    private void remember(Runnable then) {
        SearchPrefs.addRecent(prefs, q());
        then.run();
    }

    // ---- completing what you type -------------------------------------------------------------

    /** Popular titles this week (TMDB, cached a day), and titles from recent results, newest first. */
    private final List<String> popularNames = new ArrayList<>(), learnedNames = new ArrayList<>();
    private String suggestion;
    private boolean popularAsked;

    /** Once per opening, with online search on: this week's popular titles (one request a day at most). */
    private void loadPopular() {
        if (popularAsked || onlineState() != SearchPrefs.ON) return;
        popularAsked = true;
        Context c = getContext().getApplicationContext();
        String language = Locale.getDefault().toLanguageTag();
        net.execute(() -> {
            List<String> names;
            List<Titles.Title> known;
            try {
                names = Titles.popular(c, language);
                known = Titles.catalog(c, language);
            } catch (java.io.IOException e) {
                ui.post(() -> popularAsked = false);
                return;
            }
            // Best known first, so completion offers the famous one ("the bo" → The Boys).
            List<Titles.Title> byFame = new ArrayList<>(known);
            byFame.sort((a, b) -> Double.compare(Titles.fame(b), Titles.fame(a)));
            catalog = byFame;
            ui.post(() -> {
                popularNames.clear();
                popularNames.addAll(names);
                catalogNames.clear();
                for (Titles.Title t : byFame) catalogNames.add(t.name);
                showSuggestion();
            });
        });
    }

    /** Well-known titles (Titles.catalog), best known first; read by the lookup thread too. */
    private volatile List<Titles.Title> catalog;
    private final List<String> catalogNames = new ArrayList<>();

    /** Titles from the latest results become completions too (they matched what was typed). */
    private void learnNames(List<Titles.Title> titles) {
        for (int i = titles.size() - 1; i >= 0; i--) {
            String n = titles.get(i).name;
            learnedNames.remove(n);
            learnedNames.add(0, n);
        }
        while (learnedNames.size() > 60) learnedNames.remove(learnedNames.size() - 1);
        showSuggestion();
    }

    /**
     * What's typed, completed in grey after the caret (two letters or more). It's the first title in
     * the results as they're shown, so the grey text and the list agree; while results are loading,
     * a recent result or a popular title stands in.
     */
    private void showSuggestion() {
        suggestion = null;
        String typed = query.toString();
        if (open && choosing == null && typed.trim().length() >= 2 && onlineState() == SearchPrefs.ON) {
            List<String> shown = new ArrayList<>();
            if (online != null && typed.trim().equalsIgnoreCase(onlineQuery)) {
                // Same order as the list: titles on your apps, then the rest.
                for (Found f : online) if (!f.offers.isEmpty()) shown.add(f.title.name);
                for (Found f : online) if (f.offers.isEmpty()) shown.add(f.title.name);
            }
            // Already a whole title ("the boys"): nothing to complete, even if a longer one starts the same.
            boolean whole = false;
            for (String n : shown) whole |= n.equalsIgnoreCase(typed.trim());
            if (whole) {
                ghost.setText("");
                hints();
                return;
            }
            for (List<String> names : java.util.Arrays.asList(shown, catalogNames, learnedNames, popularNames)) {
                for (String n : names) {
                    if (n.length() > typed.length() && n.regionMatches(true, 0, typed, 0, typed.length())) {
                        suggestion = n;
                        break;
                    }
                }
                if (suggestion != null) break;
            }
        }
        ghost.setText(suggestion == null ? "" : suggestion.substring(typed.length()));
        hints();
    }

    private void hints() {
        if (zone == KEYS && suggestion != null && choosing == null) {
            results.setHints("OK", "Type", "▲", "Complete", "▶", "Results", "BACK", "Delete");
        } else if (zone == KEYS) results.setHints("OK", "Type", "▶", "Results", "BACK", query.length() > 0 ? "Delete" : "Close");
        else if (choosing != null) results.setHints("OK", "Open", "BACK", "Back");
        else results.setHints("OK", "Open", "◀", "Keyboard", "BACK", "Keyboard");
    }

    private String region() {
        return SearchPrefs.region(prefs, Locale.getDefault());
    }

    // ---- online -------------------------------------------------------------------------------

    private boolean offline() {
        ConnectivityManager cm = getContext().getSystemService(ConnectivityManager.class);
        if (cm == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
        return caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private final Runnable lookUp = this::lookUpNow;

    private void lookUpNow() {
        if (!open || onlineState() != SearchPrefs.ON) return;
        String q = onlineQuery;
        int mine = ++seq;
        if (offline()) {
            onlineProblem = "You're offline, so only apps and settings show.";
            render();
            return;
        }
        String region = region(), language = Locale.getDefault().toLanguageTag();
        Set<String> openable = host.openablePackages();
        Map<String, Integer> uses = new HashMap<>(host.uses());
        Context c = getContext().getApplicationContext();
        // Slow network: a calm message instead of a spinner forever (the request itself times out too).
        ui.postDelayed(() -> {
            if (seq == mine && online == null && onlineProblem == null) {
                onlineProblem = "Movies and shows are taking a while. Try again in a moment.";
                render();
            }
        }, ONLINE_TIMEOUT_MS);
        net.execute(() -> {
            List<Found> list = new ArrayList<>();
            String problem = null;
            try {
                // TMDB's results plus well-known titles with a word starting with what's typed.
                List<Titles.Title> titles = Titles.merge(q, Titles.search(c, q, language, region, 20), catalog,
                        MAX_TITLES + MAX_ELSEWHERE + 2);
                if (seq != mine) return;
                // Where each one streams: asked side by side (a few at a time), not one after another.
                List<java.util.concurrent.Future<List<Titles.Offer>>> asks = new ArrayList<>();
                for (Titles.Title t : titles) asks.add(services.submit(() -> Titles.services(c, t, region)));
                for (int i = 0; i < titles.size(); i++) {
                    List<Titles.Offer> offers;
                    try {
                        offers = asks.get(i).get(ONLINE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
                    } catch (java.util.concurrent.ExecutionException e) {
                        if (e.getCause() instanceof Titles.Unavailable && ((Titles.Unavailable) e.getCause()).busy) {
                            throw (Titles.Unavailable) e.getCause();
                        }
                        offers = new ArrayList<>();
                    } catch (java.util.concurrent.TimeoutException | InterruptedException e) {
                        offers = new ArrayList<>();
                    }
                    list.add(new Found(titles.get(i), AppLinks.yourOffers(offers, openable, uses)));
                }
                if (seq != mine) return;
                ui.post(() -> {
                    if (seq == mine) learnNames(titles);
                });
            } catch (Titles.Unavailable u) {
                problem = u.busy ? "TMDB is busy right now. Try again in a moment."
                        : "Movies and shows couldn't load. Apps and settings still work.";
            } catch (java.io.IOException e) {
                problem = "Movies and shows couldn't load. Apps and settings still work.";
            }
            String p = problem;
            ui.post(() -> {
                if (seq != mine) return;
                if (p != null) onlineProblem = p;
                else {
                    online = list;
                    onlineProblem = null;
                }
                render();
                showSuggestion();
            });
            if (p == null) loadPosters(c, list, mine);
        });
    }

    /** Posters one at a time after the titles show; each fills its row as it arrives. */
    private void loadPosters(Context c, List<Found> list, int mine) {
        for (Found f : list) {
            if (seq != mine) return;
            byte[] b = Titles.poster(c, f.title.poster);
            if (b == null) continue;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = android.graphics.Bitmap.Config.RGB_565;
            android.graphics.Bitmap bm = BitmapFactory.decodeByteArray(b, 0, b.length, o);
            if (bm == null) continue;
            Drawable d = new BitmapDrawable(c.getResources(), bm);
            ui.post(() -> {
                if (seq != mine) return;
                f.poster = d;
                String key = (f.offers.isEmpty() ? "e:" : "t:") + f.title.tv + f.title.id;
                int i = rowKeys.indexOf(key);
                if (i >= 0) results.setRowImage(i, d);
            });
        }
    }
}
