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
    static final int NAV = 0, ACTION = 1, CHOICE = 2, SLIDER = 3, STEPPER = 4, LOADING = 5, NOTE = 6;

    static final class Row {
        final int kind;
        final int icon;
        final String label;
        String value = "";
        /** Greyed out, with this reason shown under the label. */
        String disabledReason;
        boolean selected;
        float fraction;

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

        Row value(String v) {
            value = v == null ? "" : v;
            return this;
        }

        Row disabled(String reason) {
            disabledReason = reason;
            return this;
        }

        boolean enabled() {
            return disabledReason == null && kind != NOTE && kind != LOADING;
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
        while (f + 1 < rows.size() && rows.get(f).kind == NOTE) f++;
        setFocus(f, false);
        post(this::updateFades);
        // Going into or out of a submenu: a quick fade, so the swap reads as a change of level.
        if (isShown()) {
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
        while (next >= 0 && next < rows.size() && rows.get(next).kind == NOTE) next += dir;
        if (next < 0 || next >= rows.size()) {
            // Only notes that way: still scroll so the note is read, but keep focus where it is.
            if (dir < 0) scroll.smoothScrollTo(0, 0);
            return false;
        }
        setFocus(next, true);
        return true;
    }

    void setFocus(int i, boolean animate) {
        if (rows.isEmpty()) {
            indicator.setVisibility(INVISIBLE);
            return;
        }
        int old = focus;
        focus = i;
        if (old >= 0 && old < rowViews.size() && old != i) bind(rows.get(old), rowViews.get(old), false);
        bind(rows.get(i), rowViews.get(i), true);
        View target = rowViews.get(i);
        Runnable place = () -> {
            Row r = rows.get(focus);
            indicator.setBackground(Ui.rounded(tk.radiusMd, r.enabled() ? th.accentFill : th.surfaceRaised,
                    th.accent, r.enabled() ? 0 : tk.stroke));
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) indicator.getLayoutParams();
            if (lp.height != target.getHeight()) {
                lp.height = target.getHeight();
                indicator.setLayoutParams(lp);
            }
            boolean wasHidden = indicator.getVisibility() != VISIBLE;
            indicator.setVisibility(VISIBLE);
            indicator.animate().cancel();
            if (!animate || wasHidden) indicator.setTranslationY(target.getTop());
            else indicator.animate().translationY(target.getTop()).setDuration(tk.focus)
                    .setInterpolator(Tokens.EASE_OUT).start();
            keepVisible(target, animate);
        };
        if (target.getHeight() == 0) target.post(place);
        else place.run();
    }

    /** Keep the focused row and one row beyond it in view, so you can see where the list goes. */
    private void keepVisible(View it, boolean animate) {
        int ahead = it.getHeight();
        int top = it.getTop() - ahead, bottom = it.getBottom() + ahead;
        int sy = scroll.getScrollY(), h = scroll.getHeight();
        int to = sy;
        if (top < sy) to = Math.max(0, top);
        else if (bottom > sy + h) to = bottom - h;
        if (to == sy) return;
        if (animate) scroll.smoothScrollTo(0, to);
        else scroll.scrollTo(0, to);
    }

    private void updateFades() {
        fadeTop.setVisibility(scroll.canScrollVertically(-1) ? VISIBLE : GONE);
        fadeBottom.setVisibility(scroll.canScrollVertically(1) ? VISIBLE : GONE);
    }

    // ---- rows -------------------------------------------------------------------------------

    private static final int TAG_ICON = 1, TAG_LABEL = 2, TAG_SUB = 3, TAG_VALUE = 4, TAG_TRAIL = 5,
            TAG_SLIDER = 6, TAG_LEFT = 7;

    private View build(Row r) {
        Context c = getContext();
        LinearLayout v = new LinearLayout(c);
        v.setOrientation(HORIZONTAL);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setMinimumHeight(tk.rowHeight);
        v.setPadding(tk.space4, tk.space2 - tk.space1 / 2, tk.space4, tk.space2 - tk.space1 / 2);

        if (r.icon != 0) {
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
        if (r.kind == SLIDER) value.setMinWidth(Math.round(tk.textLabel * 1.9f));
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
        boolean on = focused && r.enabled();
        int main = on ? th.onAccent : r.enabled() || r.kind == LOADING ? th.text : th.disabled;
        int soft = on ? (th.onAccent & 0x00FFFFFF) | 0xE0000000 : th.textSecondary;
        TextView label = v.findViewWithTag(TAG_LABEL);
        label.setTextColor(r.kind == NOTE ? th.textSecondary : main);
        TextView sub = v.findViewWithTag(TAG_SUB);
        String subText = r.disabledReason != null ? r.disabledReason : null;
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
