package tv.leios.launcher;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

/**
 * Spacing, type, radii, motion and fonts from res/values (dimens.xml, motion.xml), resolved once.
 * Sizes are in pixels; text sizes are in pixels too (use TypedValue.COMPLEX_UNIT_PX).
 */
final class Tokens {

    final float density;
    final int space1, space2, space3, space4, space5, space6, space7;
    final int safeH, safeV;
    final float textDisplay, textTitle, textHeadline, textBody, textLabel, textCaption;
    final int radiusSm, radiusMd, radiusLg;
    final int stroke, focusRing, focusGlow, elevationSheet;
    final int iconSm, iconMd, pillHeight, rowHeight, sheetWidth, popoverWidth, sliderWidth, sliderHeight;
    final int fast, focus, medium, slow, crossfade, toastMs, spinnerDelay, longPress, repeatDelay, repeatEvery;
    final float scaleTile, scalePill, scaleMoving;
    final Typeface regular, medium500, semibold;

    /** Settles quickly with no overshoot: focus moves, sheets, rows. */
    static final Interpolator EASE_OUT = new PathInterpolator(0.2f, 0f, 0f, 1f);
    /** Symmetric, for cross-fades and things leaving. */
    static final Interpolator EASE_IN_OUT = new PathInterpolator(0.4f, 0f, 0.2f, 1f);

    /** Bundled fonts (assets/fonts, all SIL OFL, licences beside them), in menu order. */
    static final String[] FONTS = {"Outfit", "Inter", "Lexend", "Atkinson", "Nunito"};
    static final String[] FONT_NAMES = {"Outfit", "Inter", "Lexend", "Atkinson Hyperlegible", "Nunito"};
    /** Text size: Customize › Look › Text size. */
    static final int[] TEXT_SCALES = {90, 100, 115};

    private static Tokens cached;

    static Tokens of(Context c) {
        if (cached == null) cached = new Tokens(c.getApplicationContext());
        return cached;
    }

    /** After the font or text size changes; the next of() reads them again (Home and the ≡ panel). */
    static void invalidate() {
        cached = null;
    }

    private Tokens(Context c) {
        Resources r = c.getResources();
        density = r.getDisplayMetrics().density;
        android.content.SharedPreferences p = SafePrefs.focus(c);
        String fontName = p.getString("font", FONTS[0]);
        if (!java.util.Arrays.asList(FONTS).contains(fontName)) fontName = FONTS[0];
        int scalePct = p.getInt("text_scale", 100);
        if (scalePct != 90 && scalePct != 115) scalePct = 100;
        float ts = scalePct / 100f;
        space1 = px(r, R.dimen.space_1);
        space2 = px(r, R.dimen.space_2);
        space3 = px(r, R.dimen.space_3);
        space4 = px(r, R.dimen.space_4);
        space5 = px(r, R.dimen.space_5);
        space6 = px(r, R.dimen.space_6);
        space7 = px(r, R.dimen.space_7);
        safeH = px(r, R.dimen.safe_h);
        safeV = px(r, R.dimen.safe_v);
        textDisplay = r.getDimension(R.dimen.text_display) * ts;
        textTitle = r.getDimension(R.dimen.text_title) * ts;
        textHeadline = r.getDimension(R.dimen.text_headline) * ts;
        textBody = r.getDimension(R.dimen.text_body) * ts;
        textLabel = r.getDimension(R.dimen.text_label) * ts;
        textCaption = r.getDimension(R.dimen.text_caption) * ts;
        radiusSm = px(r, R.dimen.radius_sm);
        radiusMd = px(r, R.dimen.radius_md);
        radiusLg = px(r, R.dimen.radius_lg);
        stroke = Math.max(1, px(r, R.dimen.stroke));
        focusRing = px(r, R.dimen.focus_ring);
        focusGlow = px(r, R.dimen.focus_glow);
        elevationSheet = px(r, R.dimen.elevation_sheet);
        iconSm = px(r, R.dimen.icon_sm);
        iconMd = px(r, R.dimen.icon_md);
        pillHeight = px(r, R.dimen.pill_height);
        rowHeight = px(r, R.dimen.row_height);
        sheetWidth = px(r, R.dimen.sheet_width);
        popoverWidth = px(r, R.dimen.popover_width);
        sliderWidth = px(r, R.dimen.slider_width);
        sliderHeight = px(r, R.dimen.slider_height);
        fast = r.getInteger(R.integer.motion_fast);
        focus = r.getInteger(R.integer.motion_focus);
        medium = r.getInteger(R.integer.motion_medium);
        slow = r.getInteger(R.integer.motion_slow);
        crossfade = r.getInteger(R.integer.motion_crossfade);
        toastMs = r.getInteger(R.integer.toast_ms);
        spinnerDelay = r.getInteger(R.integer.spinner_delay);
        longPress = r.getInteger(R.integer.long_press);
        repeatDelay = r.getInteger(R.integer.repeat_delay);
        repeatEvery = r.getInteger(R.integer.repeat_every);
        scaleTile = r.getInteger(R.integer.scale_tile) / 100f;
        scalePill = r.getInteger(R.integer.scale_pill) / 100f;
        scaleMoving = r.getInteger(R.integer.scale_moving) / 100f;
        regular = font(c, fontName, r.getInteger(R.integer.weight_regular));
        medium500 = font(c, fontName, r.getInteger(R.integer.weight_medium));
        semibold = font(c, fontName, r.getInteger(R.integer.weight_semibold));
    }

    int dp(float v) {
        return Math.round(v * density);
    }

    private static int px(Resources r, int id) {
        return r.getDimensionPixelSize(id);
    }

    /** All bundled fonts are variable, so one file gives every weight. */
    static Typeface font(Context c, String name, int weight) {
        try {
            Typeface t = new Typeface.Builder(c.getAssets(), "fonts/" + name + ".ttf")
                    .setFontVariationSettings("'wght' " + weight)
                    .build();
            if (t != null) return t;
        } catch (RuntimeException ignored) {
        }
        return Typeface.create(weight >= 500 ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL);
    }
}
