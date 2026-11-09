package tv.leios.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * The search keyboard, built for the D-pad: letters A–Z in order (no hunting for QWERTY), digits,
 * then Space, Delete and Clear. One view that draws itself, so moving between keys redraws only
 * the keyboard, and the focus slides from key to key like the menus' focus bar.
 */
final class SearchKeyboard extends View {

    static final String SPACE = " ", DELETE = "\b", CLEAR = "\u0018", MIC = "\u0007";

    private static final class Key {
        final String label, value;
        final int span;
        final RectF rect = new RectF();
        int row;

        Key(String label, String value, int span) {
            this.label = label;
            this.value = value;
            this.span = span;
        }
    }

    private static final int COLS = 7;
    private final List<List<Key>> rows = new ArrayList<>();
    private final List<Key> keys = new ArrayList<>();
    private final Theme th;
    private final Tokens tk;
    private final Paint keyPaint = new Paint(Paint.ANTI_ALIAS_FLAG), focusPaint = new Paint(Paint.ANTI_ALIAS_FLAG),
            textPaint = new Paint(Paint.ANTI_ALIAS_FLAG), smallPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF focusRect = new RectF(), from = new RectF();
    private final int keyH, gap;
    private int focus;
    private long lastMove;
    private boolean active = true;
    private ValueAnimator slide;
    private final Drawable micIcon;

    SearchKeyboard(Context c, Theme th, Tokens tk, boolean mic) {
        super(c);
        this.th = th;
        this.tk = tk;
        keyH = tk.dp(40);
        gap = tk.space1 + tk.space1 / 2;
        String[] letters = {"abcdefg", "hijklmn", "opqrstu", "vwxyz12", "3456789", "0'&-.:!"};
        for (String r : letters) {
            List<Key> row = new ArrayList<>();
            for (char ch : r.toCharArray()) row.add(new Key(String.valueOf(ch), String.valueOf(ch), 1));
            rows.add(row);
        }
        List<Key> last = new ArrayList<>();
        last.add(new Key("Space", SPACE, mic ? 2 : 3));
        last.add(new Key("Delete", DELETE, 2));
        last.add(new Key("Clear", CLEAR, 2));
        if (mic) last.add(new Key("", MIC, 1));
        rows.add(last);
        for (int i = 0; i < rows.size(); i++) for (Key k : rows.get(i)) {
            k.row = i;
            keys.add(k);
        }
        micIcon = mic ? Ui.tinted(c, R.drawable.ic_mic, th.text) : null;
        keyPaint.setColor(th.surfaceSoft);
        focusPaint.setColor(th.accentFill);
        textPaint.setTypeface(tk.medium500);
        textPaint.setTextSize(tk.textBody);
        textPaint.setTextAlign(Paint.Align.CENTER);
        smallPaint.setTypeface(tk.medium500);
        smallPaint.setTextSize(tk.textCaption);
        smallPaint.setTextAlign(Paint.Align.CENTER);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** The keyboard's natural size for a given width. */
    int heightFor() {
        return rows.size() * keyH + (rows.size() - 1) * gap;
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), heightFor());
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        float cell = (w - gap * (COLS - 1f)) / COLS;
        for (int r = 0; r < rows.size(); r++) {
            float x = 0, y = r * (keyH + gap);
            for (Key k : rows.get(r)) {
                float kw = cell * k.span + gap * (k.span - 1);
                k.rect.set(x, y, x + kw, y + keyH);
                x += kw + gap;
            }
        }
        focusRect.set(keys.get(focus).rect);
    }

    /** Moves focus; false at an edge (the owner may move on, e.g. right into the results). */
    boolean move(int dx, int dy) {
        Key cur = keys.get(focus);
        int target = -1;
        if (dx != 0) {
            List<Key> row = rows.get(cur.row);
            int i = row.indexOf(cur) + dx;
            if (i >= 0 && i < row.size()) target = keys.indexOf(row.get(i));
        } else {
            int r = cur.row + dy;
            if (r >= 0 && r < rows.size()) {
                float cx = cur.rect.centerX();
                float best = Float.MAX_VALUE;
                for (Key k : rows.get(r)) {
                    float d = Math.abs(k.rect.centerX() - cx);
                    if (d < best) {
                        best = d;
                        target = keys.indexOf(k);
                    }
                }
            }
        }
        if (target < 0) return false;
        setFocus(target, true);
        return true;
    }

    /** Back to the first letter, as when search opens. */
    void reset() {
        setFocus(0, false);
    }

    /** Whether focus is in the last column (Right from here goes to the results). */
    boolean atRightEdge() {
        Key cur = keys.get(focus);
        List<Key> row = rows.get(cur.row);
        return row.indexOf(cur) == row.size() - 1;
    }

    String focusedValue() {
        return keys.get(focus).value;
    }

    /** Dimmed focus while the results have it. */
    void setActive(boolean on) {
        active = on;
        invalidate();
    }

    private void setFocus(int i, boolean animate) {
        if (slide != null) slide.cancel();
        focus = i;
        RectF to = keys.get(i).rect;
        // Held arrows step faster than the slide: shorten it to the gap between steps (or snap), so
        // the highlight stays on the key instead of trailing it.
        long now = android.os.SystemClock.uptimeMillis();
        long gap = now - lastMove;
        lastMove = now;
        int duration = gap >= tk.focus ? tk.focus : (int) (gap * 0.8f);
        if (!animate || getWidth() == 0 || duration < 24) {
            focusRect.set(to);
            invalidate();
            return;
        }
        from.set(focusRect);
        RectF end = new RectF(to);
        slide = ValueAnimator.ofFloat(0f, 1f);
        slide.setDuration(duration);
        slide.setInterpolator(Tokens.EASE_OUT);
        slide.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            focusRect.set(from.left + (end.left - from.left) * f, from.top + (end.top - from.top) * f,
                    from.right + (end.right - from.right) * f, from.bottom + (end.bottom - from.bottom) * f);
            invalidate();
        });
        slide.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float rad = tk.radiusSm;
        for (Key k : keys) canvas.drawRoundRect(k.rect, rad, rad, keyPaint);
        focusPaint.setColor(active ? th.accentFill : th.surfaceRaised);
        canvas.drawRoundRect(focusRect, rad, rad, focusPaint);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        for (Key k : keys) {
            boolean on = active && keys.indexOf(k) == focus;
            int ink = on ? th.onAccent : th.text;
            if (MIC.equals(k.value) && micIcon != null) {
                int s = tk.iconMd;
                int cx = (int) k.rect.centerX(), cy = (int) k.rect.centerY();
                micIcon.setTint(ink);
                micIcon.setBounds(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2);
                micIcon.draw(canvas);
                continue;
            }
            Paint p = k.span > 1 ? smallPaint : textPaint;
            p.setColor(ink);
            Paint.FontMetrics m = k.span > 1 ? smallPaint.getFontMetrics() : fm;
            float y = k.rect.centerY() - (m.ascent + m.descent) / 2f;
            canvas.drawText(k.span > 1 ? k.label : k.label.toUpperCase(java.util.Locale.ROOT), k.rect.centerX(), y, p);
        }
    }
}
