package tv.leios.launcher;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The one menu look, used by every Home menu and by the ≡ panel: a header (where you are), rows
 * with an accent focus bar that slides between them, and a footer with remote hints.
 * It draws only; the owner decides what keys do and calls {@link #setFocus}.
 */
final class MenuView extends LinearLayout {

    /** What a row shows on its right: a chevron, a tick, a slider, ◀ value ▶, a spinner, or nothing. */
    static final int NAV = 0, ACTION = 1, CHOICE = 2, SLIDER = 3, STEPPER = 4, LOADING = 5, NOTE = 6, HEADER = 7;

    static final class Row {
        final int kind;
        final int icon;
        final String label;
        String value = "";
        /** Greyed out, with this reason shown under the label. */
        String disabledReason;
        boolean selected;
        float fraction;
        /** The longest value this row will show, so the value's space never changes as it does. */
        String widestValue;
        /** A second line under the label (search: where a setting is, a title's year). */
        String detail;
        /** A picture in place of the icon (search: an app's icon, a poster); see {@link #image(int, int)}. */
        android.graphics.drawable.Drawable image;
        int imageW, imageH;
        /** Quieter text: search results that are less likely to be what you want. */
        boolean quiet;

        private Row(int kind, int icon, String label) {
            this.kind = kind;
            this.icon = icon;
            this.label = label;
        }

        static Row nav(int icon, String label, String value) {
            return new Row(NAV, icon, label).value(value);
        }

        static Row action(int icon, String label) {
            return new Row(ACTION, icon, label);
        }

        static Row choice(String label, boolean selected) {
            Row r = new Row(CHOICE, 0, label);
            r.selected = selected;
            return r;
        }

        static Row slider(int icon, String label, float fraction, String value) {
            Row r = new Row(SLIDER, icon, label).value(value);
            r.fraction = fraction;
            return r;
        }

        static Row stepper(int icon, String label, String value) {
            return new Row(STEPPER, icon, label).value(value);
        }

        static Row loading(String label) {
            return new Row(LOADING, 0, label);
        }

        static Row note(int icon, String label) {
            return new Row(NOTE, icon, label);
        }

        /** A small heading over a group of rows ("Apps", "Settings"); focus steps over it. */
        static Row header(String label) {
            return new Row(HEADER, 0, label);
        }

        Row detail(String d) {
            detail = d == null || d.isEmpty() ? null : d;
            return this;
        }

        /** Room for a picture of this size at the start of the row (it can arrive later). */
        Row image(int w, int h) {
            imageW = w;
            imageH = h;
            return this;
        }

        Row quiet() {
            quiet = true;
            return this;
        }

        Row value(String v) {
            value = v == null ? "" : v;
            return this;
        }

        Row widest(String v) {
            widestValue = v;
            return this;
        }

        Row disabled(String reason) {
            disabledReason = reason;
            return this;
        }

        boolean enabled() {
            return disabledReason == null && kind != NOTE && kind != LOADING && kind != HEADER;
        }

        /** Focus never stops here. */
        boolean skipped() {
            return kind == NOTE || kind == HEADER;
        }
    }

    private final Theme th;
    private final Tokens tk;
    private final TextView crumb, title;
    private final LinearLayout header, footer, rowsBox;
    private final FrameLayout content;
    private final View indicator, fadeTop, fadeBottom;
    private final CappedScroll scroll;
    private final List<Row> rows = new ArrayList<>();
    private final List<View> rowViews = new ArrayList<>();
    private int focus = -1, maxHeight;
    /** When focus last moved, to tell a held button's quick steps from single presses. */
    private long lastMove;
    private String[] hints = new String[0];
    private Runnable restoreFooter;

    MenuView(Context c, Theme th) {
        super(c);
        this.th = th;
        this.tk = Tokens.of(c);
        setOrientation(VERTICAL);
        setBackground(Ui.rounded(tk.radiusLg, th.surface, th.stroke, tk.stroke));
        setElevation(tk.elevationSheet);
        // No outline clip: the padding keeps every row inside the rounded corners, and a rounded
        // clip costs extra GPU work on every frame.
        setPadding(tk.space2, tk.space2, tk.space2, tk.space2);

        header = new LinearLayout(c);
        header.setOrientation(VERTICAL);
        header.setPadding(tk.space4, tk.space3, tk.space4, tk.space2);
        crumb = Ui.text(c, "", tk.textCaption, th.textTertiary, tk.medium500);
        crumb.setLetterSpacing(0.06f);
        crumb.setAllCaps(true);
        title = Ui.text(c, "", tk.textHeadline, th.text, tk.semibold);
        LayoutParams tlp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = tk.space1;
        header.addView(crumb);
        header.addView(title, tlp);
        addView(header);

        FrameLayout body = new FrameLayout(c);
        scroll = new CappedScroll(c);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setFocusable(false);
        scroll.setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        content = new FrameLayout(c);
        indicator = new View(c);
        indicator.setVisibility(INVISIBLE);
        content.addView(indicator, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, tk.rowHeight));
        rowsBox = new LinearLayout(c);
        rowsBox.setOrientation(VERTICAL);
        content.addView(rowsBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        scroll.addView(content);
        body.addView(scroll);
        // Cheap scroll cues: gradients over the list edges (fading edges cost a full offscreen layer).
        fadeTop = new View(c);
        fadeTop.setBackground(Ui.fade(GradientDrawable.Orientation.TOP_BOTTOM, opaque(th.surface)));
        fadeBottom = new View(c);
        fadeBottom.setBackground(Ui.fade(GradientDrawable.Orientation.BOTTOM_TOP, opaque(th.surface)));
        body.addView(fadeTop, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, tk.space6, Gravity.TOP));
        body.addView(fadeBottom, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, tk.space6, Gravity.BOTTOM));
        scroll.setOnScrollChangeListener((v, x, y, ox, oy) -> updateFades());
        addView(body, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        footer = new LinearLayout(c);
        footer.setOrientation(HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setPadding(tk.space4, tk.space2, tk.space4, tk.space1);
        addView(footer);
    }

    private static int opaque(int c) {
        return c | 0xFF000000;
    }

    // ---- content ----------------------------------------------------------------------------

    /** {@code crumbText} is where this menu sits ("Customize › Look"); null hides the header. */
    void setHeader(String crumbText, String titleText) {
        header.setVisibility(titleText == null ? GONE : VISIBLE);
        crumb.setVisibility(crumbText == null || crumbText.isEmpty() ? GONE : VISIBLE);
        crumb.setText(crumbText);
        title.setText(titleText);
    }

    /** Key / label pairs, e.g. "OK", "Select", "BACK", "Back". */
    void setHints(String... keyThenLabel) {
        hints = keyThenLabel;
        if (restoreFooter == null) showHints();
    }

    private void showHints() {
        footer.removeAllViews();
        footer.setVisibility(hints.length == 0 ? GONE : VISIBLE);
        if (hints.length > 0) footer.addView(Ui.hints(getContext(), th, tk, hints));
    }

    /** A short confirmation or reason in the footer, then the hints come back. */
    void flash(String message) {
        footer.removeAllViews();
        footer.setVisibility(VISIBLE);
        ImageView i = Ui.icon(getContext(), R.drawable.ic_info, tk.iconSm, th.accent);
        ((LayoutParams) i.getLayoutParams()).setMarginEnd(tk.space2);
        footer.addView(i);
        TextView t = Ui.text(getContext(), message, tk.textCaption, th.text, tk.medium500);
        t.setSingleLine(false);
        t.setMaxLines(2);
        footer.addView(t, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (restoreFooter != null) removeCallbacks(restoreFooter);
        restoreFooter = () -> {
            restoreFooter = null;
            showHints();
        };
        postDelayed(restoreFooter, tk.toastMs);
    }

    void setMaxHeight(int px) {
        maxHeight = px;
        requestLayout();
    }

    void setRows(List<Row> list, int focusIndex) {
        setRows(list, focusIndex, true);
    }

    /** {@code fade}: a new level (a quick fade); false for the same list refreshing (search results). */
    void setRows(List<Row> list, int focusIndex, boolean fade) {
        rows.clear();
        rows.addAll(list);
        rowsBox.removeAllViews();
        rowViews.clear();
        for (Row r : rows) {
            View v = build(r);
            rowsBox.addView(v);
            rowViews.add(v);
        }
        focus = -1;
        scroll.scrollTo(0, 0);
        int f = Math.max(0, Math.min(focusIndex, rows.size() - 1));
        while (f + 1 < rows.size() && rows.get(f).skipped()) f++;
        // Only notes (About): stay at the top, so the first one is read, not scrolled past.
        if (!rows.isEmpty() && rows.get(f).skipped()) f = Math.max(0, Math.min(focusIndex, rows.size() - 1));
        setFocus(f, false);
        post(this::updateFades);
        // Going into or out of a submenu: a quick fade, so the swap reads as a change of level.
        if (fade && isShown()) {
            content.setAlpha(0f);
            content.animate().alpha(1f).setDuration(tk.fast).setInterpolator(Tokens.EASE_OUT).start();
        }
    }

    int size() {
        return rows.size();
    }

    int focus() {
        return focus;
    }

    Row row(int i) {
        return rows.get(i);
    }

    Row focused() {
        return focus < 0 || focus >= rows.size() ? null : rows.get(focus);
    }

    /** Re-reads a row's value, fraction and tick after the owner changed them. */
    void refresh(int i) {
        if (i < 0 || i >= rowViews.size()) return;
        bind(rows.get(i), rowViews.get(i), i == focus);
    }

    /** Moves to the next row in {@code dir}, stepping over notes (they're text, not choices). */
    boolean moveFocus(int dir) {
        int next = focus + dir;
        while (next >= 0 && next < rows.size() && rows.get(next).skipped()) next += dir;
        if (next < 0 || next >= rows.size()) {
            // Only notes that way: still scroll so the note is read, but keep focus where it is.
            if (dir < 0) scroll.smoothScrollTo(0, 0);
            return false;
        }
        setFocus(next, true);
        return true;
    }

    /** Is there a row focus could stop on? */
    boolean hasChoices() {
        for (Row r : rows) if (!r.skipped() && r.kind != LOADING) return true;
        return false;
    }

    private boolean active = true;

    /**
     * Inactive: another part of the screen has focus (search's keyboard, Customization's section
     * list), so the focus bar steps back to a quiet outline and the row reads as normal.
     */
    void setActive(boolean on) {
        if (active == on) return;
        active = on;
        if (focus >= 0 && focus < rows.size()) {
            bind(rows.get(focus), rowViews.get(focus), true);
            setFocus(focus, false);
        }
    }

    boolean isActive() {
        return active;
    }

    /** A short pulse on the focused row: "this is the setting you searched for". */
    void pulse() {
        indicator.animate().cancel();
        ValueAnimatorHolder.pulse(indicator, tk.slow);
    }

    /** A picture for a row that was waiting for one (a poster that just arrived). */
    void setRowImage(int i, android.graphics.drawable.Drawable d) {
        if (i < 0 || i >= rows.size()) return;
        rows.get(i).image = d;
        bind(rows.get(i), rowViews.get(i), i == focus);
    }

    private static final class ValueAnimatorHolder {
        static void pulse(View v, int duration) {
            android.animation.ObjectAnimator a = android.animation.ObjectAnimator.ofFloat(v, View.ALPHA,
                    1f, 0.35f, 1f, 0.35f, 1f);
            a.setDuration(duration * 3L);
            a.setInterpolator(Tokens.EASE_IN_OUT);
            a.start();
        }
    }

    void setFocus(int i, boolean animate) {
        if (rows.isEmpty() || rows.get(i).kind == HEADER) {
            indicator.setVisibility(INVISIBLE);
            if (!rows.isEmpty()) focus = i;
            return;
        }
        int old = focus;
        focus = i;
        if (old >= 0 && old < rowViews.size() && old != i) bind(rows.get(old), rowViews.get(old), false);
        bind(rows.get(i), rowViews.get(i), true);
        View target = rowViews.get(i);
        // A held button steps every ~50 ms, faster than the focus slide: each slide then lasts only
        // until the next step (or snaps, when there's no time to see it), so the bar keeps pace with
        // the row instead of trailing behind it.
        long now = android.os.SystemClock.uptimeMillis();
        long gap = now - lastMove;
        lastMove = now;
        int slide = gap >= tk.focus ? tk.focus : (int) (gap * 0.8f);
        boolean quick = animate && slide < tk.focus;
        if (slide < 24) animate = false;
        boolean glide = animate;
        Runnable place = () -> {
            Row r = rows.get(focus);
            if (!active) indicator.setBackground(Ui.rounded(tk.radiusMd, 0, th.textTertiary, tk.stroke));
            else indicator.setBackground(Ui.rounded(tk.radiusMd, r.enabled() ? th.accentFill : th.surfaceRaised,
                    th.accent, r.enabled() ? 0 : tk.stroke));
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) indicator.getLayoutParams();
            if (lp.height != target.getHeight()) {
                lp.height = target.getHeight();
                indicator.setLayoutParams(lp);
            }
            boolean wasHidden = indicator.getVisibility() != VISIBLE;
            indicator.setVisibility(VISIBLE);
            indicator.animate().cancel();
            if (!glide || wasHidden) indicator.setTranslationY(target.getTop());
            else indicator.animate().translationY(target.getTop()).setDuration(slide)
                    .setInterpolator(Tokens.EASE_OUT).start();
            keepVisible(target, glide, quick ? slide : 0);
        };
        if (target.getHeight() == 0) target.post(place);
        else place.run();
    }

    /** Keep the focused row and one row beyond it in view, so you can see where the list goes. */
    private android.animation.ObjectAnimator scrolling;

    /**
     * {@code pace}: a held button's step time. The list then scrolls over exactly that long, in step
     * with the focus bar (a normal smooth scroll takes 250 ms and would trail behind).
     */
    private void keepVisible(View it, boolean animate, int pace) {
        int ahead = it.getHeight();
        int top = it.getTop() - ahead, bottom = it.getBottom() + ahead;
        if (scrolling != null) scrolling.cancel();
        int sy = scroll.getScrollY(), h = scroll.getHeight();
        int to = sy;
        if (top < sy) to = Math.max(0, top);
        else if (bottom > sy + h) to = bottom - h;
        if (to == sy) return;
        if (!animate) {
            scroll.scrollTo(0, to);
        } else if (pace > 0) {
            scrolling = android.animation.ObjectAnimator.ofInt(scroll, "scrollY", sy, to);
            scrolling.setDuration(pace);
            scrolling.setInterpolator(Tokens.EASE_OUT);
            scrolling.start();
        } else {
            scroll.smoothScrollTo(0, to);
        }
    }

    private void updateFades() {
        fadeTop.setVisibility(scroll.canScrollVertically(-1) ? VISIBLE : GONE);
        fadeBottom.setVisibility(scroll.canScrollVertically(1) ? VISIBLE : GONE);
    }

    // ---- rows -------------------------------------------------------------------------------

    private static final int TAG_ICON = 1, TAG_LABEL = 2, TAG_SUB = 3, TAG_VALUE = 4, TAG_TRAIL = 5,
            TAG_SLIDER = 6, TAG_LEFT = 7, TAG_IMAGE = 8;

    private View build(Row r) {
        Context c = getContext();
        LinearLayout v = new LinearLayout(c);
        v.setOrientation(HORIZONTAL);
        v.setGravity(Gravity.CENTER_VERTICAL);
        if (r.kind == HEADER) {
            // A group heading: small caps, like the menu's own breadcrumb.
            v.setPadding(tk.space4, tk.space3, tk.space4, tk.space1);
            TextView h = Ui.text(c, r.label, tk.textCaption, th.textTertiary, tk.medium500);
            h.setLetterSpacing(0.06f);
            h.setAllCaps(true);
            h.setTag(TAG_LABEL);
            v.addView(h);
            return v;
        }
        v.setMinimumHeight(tk.rowHeight);
        v.setPadding(tk.space4, tk.space2 - tk.space1 / 2, tk.space4, tk.space2 - tk.space1 / 2);

        if (r.imageW > 0) {
            ImageView img = new ImageView(c);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            img.setClipToOutline(true);
            img.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline o) {
                    o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), tk.radiusSm / 2f);
                }
            });
            LayoutParams ilp = new LayoutParams(r.imageW, r.imageH);
            ilp.setMarginEnd(tk.space3);
            img.setTag(TAG_IMAGE);
            v.addView(img, ilp);
        } else if (r.icon != 0) {
            ImageView icon = Ui.icon(c, r.icon, tk.iconMd, th.textSecondary);
            ((LayoutParams) icon.getLayoutParams()).setMarginEnd(tk.space3);
            icon.setTag(TAG_ICON);
            v.addView(icon);
        } else if (r.kind == CHOICE) {
            // Choices keep a tick slot on the left, so labels line up whether ticked or not.
            ImageView tick = Ui.icon(c, R.drawable.ic_check, tk.iconMd, th.accent);
            ((LayoutParams) tick.getLayoutParams()).setMarginEnd(tk.space3);
            tick.setTag(TAG_ICON);
            v.addView(tick);
        }

        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(VERTICAL);
        TextView label = Ui.text(c, r.label, r.kind == NOTE ? tk.textLabel : tk.textBody, th.text,
                r.kind == NOTE ? tk.regular : tk.medium500);
        label.setTag(TAG_LABEL);
        if (r.kind == NOTE) {
            // Notes explain something (what Reset clears, why a list is empty), so they wrap.
            label.setSingleLine(false);
            label.setMaxLines(5);
            label.setLineSpacing(0, 1.15f);
        }
        texts.addView(label);
        TextView sub = Ui.text(c, "", tk.textCaption, th.textTertiary, tk.regular);
        sub.setTag(TAG_SUB);
        sub.setSingleLine(false);
        sub.setMaxLines(2);
        LayoutParams slp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = tk.space1 / 2;
        texts.addView(sub, slp);
        v.addView(texts, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (r.kind == STEPPER) {
            ImageView left = Ui.icon(c, R.drawable.ic_chevron, tk.iconSm, th.textSecondary);
            left.setRotation(180);
            left.setTag(TAG_LEFT);
            v.addView(left);
        }
        if (r.kind == SLIDER) {
            Ui.Slider s = new Ui.Slider(c, tk.sliderWidth, tk.sliderHeight);
            ((LayoutParams) s.getLayoutParams()).setMarginStart(tk.space3);
            s.setTag(TAG_SLIDER);
            v.addView(s);
        }
        TextView value = Ui.text(c, "", tk.textLabel, th.textSecondary, tk.medium500);
        value.setTag(TAG_VALUE);
        // Same-width digits, so a changing number doesn't shift anything.
        value.setFontFeatureSettings("tnum");
        value.setGravity(Gravity.END);
        LayoutParams vlp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vlp.setMarginStart(tk.space3);
        int minWidth = r.kind == SLIDER ? Math.round(tk.textLabel * 1.9f) : 0;
        // Room for the longest value up front, so the slider beside it never moves ("9 of 10" to "10 of 10").
        if (r.widestValue != null) minWidth = Math.max(minWidth, (int) Math.ceil(value.getPaint().measureText(r.widestValue)));
        if (minWidth > 0) value.setMinWidth(minWidth);
        v.addView(value, vlp);

        if (r.kind == NAV || r.kind == STEPPER) {
            ImageView chev = Ui.icon(c, R.drawable.ic_chevron, tk.iconSm, th.textSecondary);
            ((LayoutParams) chev.getLayoutParams()).setMarginStart(tk.space2);
            chev.setTag(TAG_TRAIL);
            v.addView(chev);
        } else if (r.kind == LOADING) {
            ProgressBar p = Ui.spinner(c, tk.iconMd, th.accent);
            ((LayoutParams) p.getLayoutParams()).setMarginStart(tk.space3);
            p.setTag(TAG_TRAIL);
            v.addView(p);
        }
        bind(r, v, false);
        return v;
    }

    private void bind(Row r, View v, boolean focused) {
        if (r.kind == HEADER) return;
        boolean on = focused && r.enabled() && active;
        int main = on ? th.onAccent : r.quiet ? th.textSecondary : r.enabled() || r.kind == LOADING ? th.text : th.disabled;
        int soft = on ? (th.onAccent & 0x00FFFFFF) | 0xE0000000 : th.textSecondary;
        TextView label = v.findViewWithTag(TAG_LABEL);
        label.setTextColor(r.kind == NOTE ? th.textSecondary : main);
        ImageView img = v.findViewWithTag(TAG_IMAGE);
        if (img != null) {
            img.setImageDrawable(r.image);
            img.setBackground(r.image == null ? Ui.rounded(tk.radiusSm / 2f, th.surfaceRaised, 0, 0) : null);
            img.setAlpha(r.quiet && !on ? 0.6f : 1f);
        }
        TextView sub = v.findViewWithTag(TAG_SUB);
        String subText = r.disabledReason != null ? r.disabledReason : r.detail;
        sub.setVisibility(subText == null ? GONE : VISIBLE);
        sub.setText(subText);
        sub.setTextColor(on ? soft : th.textTertiary);
        TextView value = v.findViewWithTag(TAG_VALUE);
        value.setText(r.value);
        value.setVisibility(r.value.isEmpty() ? GONE : VISIBLE);
        value.setTextColor(soft);
        ImageView icon = v.findViewWithTag(TAG_ICON);
        if (icon != null) {
            if (r.kind == CHOICE) {
                icon.setVisibility(r.selected ? VISIBLE : INVISIBLE);
                icon.setImageTintList(android.content.res.ColorStateList.valueOf(on ? th.onAccent : th.accent));
            } else {
                icon.setImageTintList(android.content.res.ColorStateList.valueOf(
                        r.enabled() || r.kind == NOTE ? soft : th.disabled));
            }
        }
        for (int tag : new int[] {TAG_TRAIL, TAG_LEFT}) {
            View t = v.findViewWithTag(tag);
            if (t instanceof ImageView) {
                ((ImageView) t).setImageTintList(android.content.res.ColorStateList.valueOf(soft));
                // The left arrow of a stepper only shows while you're on it, as a "◀ ▶ changes this" cue.
                if (tag == TAG_LEFT) t.setVisibility(focused ? VISIBLE : INVISIBLE);
            }
        }
        Ui.Slider s = v.findViewWithTag(TAG_SLIDER);
        if (s != null) {
            s.setColors(on ? (th.onAccent & 0x00FFFFFF) | 0x4D000000 : th.track, on ? th.onAccent : th.accent);
            s.setValue(r.fraction);
        }
    }

    // ---- sizing -----------------------------------------------------------------------------

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        if (maxHeight > 0) {
            int w = MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight(),
                    MeasureSpec.EXACTLY);
            int unbounded = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
            header.measure(w, unbounded);
            footer.measure(w, unbounded);
            int used = getPaddingTop() + getPaddingBottom()
                    + (header.getVisibility() == GONE ? 0 : header.getMeasuredHeight())
                    + (footer.getVisibility() == GONE ? 0 : footer.getMeasuredHeight());
            scroll.cap = Math.max(tk.rowHeight * 2, maxHeight - used);
            heightSpec = MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST);
        }
        super.onMeasure(widthSpec, heightSpec);
    }

    private static final class CappedScroll extends ScrollView {
        int cap;

        CappedScroll(Context c) {
            super(c);
        }

        @Override
        protected void onMeasure(int w, int h) {
            if (cap > 0) h = MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST);
            super.onMeasure(w, h);
        }
    }
}
