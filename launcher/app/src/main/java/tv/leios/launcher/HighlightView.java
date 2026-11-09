package tv.leios.launcher;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * The one focus mark on Home: an accent ring with a soft glow that glides from one tile or pill to
 * the next. The glow is a few faint rings rather than a blur, which this GPU draws badly.
 * The view is only as big as the area the ring moves across (not the whole screen), so with the
 * GPU's partial updates each animation frame repaints just that patch.
 */
public class HighlightView extends View {

    private final RectF cur = new RectF(), from = new RectF(), to = new RectF();
    private float radius, fromRadius, toRadius;
    private boolean shown, moving;
    private ValueAnimator anim;

    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Tokens tk;
    private int accent;
    private static final int[] GLOW_ALPHA = {0x52, 0x2E, 0x14};

    /** Where this view sits in its parent; the parent's own layout is overridden by these. */
    private int bl, bt, br, bb;

    public HighlightView(Context c, AttributeSet a) {
        super(c, a);
        tk = Tokens.of(c);
        ring.setStyle(Paint.Style.STROKE);
        glow.setStyle(Paint.Style.STROKE);
        setWillNotDraw(false);
    }

    void setTheme(Theme t) {
        accent = t.accent;
        invalidate();
    }

    /** The ring's colour: the accent, or the focused app's own colour (Look › Tiles). */
    void setColor(int color) {
        if (color == accent) return;
        accent = color;
        invalidate();
    }

    /** A picked-up tile gets a thicker ring and a stronger glow. */
    void setMoving(boolean moving) {
        this.moving = moving;
        fit(cur, cur);
        invalidate();
    }

    void moveTo(RectF target, float targetRadius, boolean animate) {
        if (anim != null) anim.cancel();
        if (!shown || !animate) {
            cur.set(target);
            radius = targetRadius;
            shown = true;
            fit(cur, cur);
            invalidate();
            return;
        }
        from.set(cur);
        fromRadius = radius;
        to.set(target);
        toRadius = targetRadius;
        fit(from, to);
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(tk.focus);
        anim.setInterpolator(Tokens.EASE_OUT);
        anim.addUpdateListener(va -> {
            float t = (float) va.getAnimatedValue();
            cur.set(lerp(from.left, to.left, t), lerp(from.top, to.top, t),
                    lerp(from.right, to.right, t), lerp(from.bottom, to.bottom, t));
            radius = lerp(fromRadius, toRadius, t);
            invalidate();
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                fit(cur, cur);
            }
        });
        anim.start();
    }

    /**
     * The element it's heading for changed size or place mid-way (a top-bar button opening up):
     * the ring keeps gliding, now towards the new rectangle, or follows at once if it had arrived.
     */
    void retarget(RectF target, float targetRadius) {
        if (!shown) return;
        if (anim != null && anim.isRunning()) {
            to.set(target);
            toRadius = targetRadius;
            fit(from, to);
            return;
        }
        cur.set(target);
        radius = targetRadius;
        fit(cur, cur);
        invalidate();
    }

    void hide() {
        if (anim != null) anim.cancel();
        shown = false;
        invalidate();
    }

    /** Sizes the view to cover both rectangles plus the ring and glow around them. */
    private void fit(RectF a, RectF b) {
        int m = (int) Math.ceil(outset() + tk.focusGlow) + 2;
        bl = (int) Math.floor(Math.min(a.left, b.left)) - m;
        bt = (int) Math.floor(Math.min(a.top, b.top)) - m;
        br = (int) Math.ceil(Math.max(a.right, b.right)) + m;
        bb = (int) Math.ceil(Math.max(a.bottom, b.bottom)) + m;
        super.layout(bl, bt, br, bb);
    }

    @Override
    public void layout(int l, int t, int r, int b) {
        // The parent lays this out full-screen; keep the bounds fit() chose once it has run.
        if (br > bl) super.layout(bl, bt, br, bb);
        else super.layout(l, t, r, b);
    }

    private float outset() {
        float ringW = moving ? tk.focusRing * 1.5f : tk.focusRing;
        return ringW + tk.focusGlow;
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!shown) return;
        canvas.translate(-getLeft(), -getTop());
        float ringW = moving ? tk.focusRing * 1.5f : tk.focusRing;
        float step = tk.focusGlow / (float) GLOW_ALPHA.length;
        int rgb = accent & 0x00FFFFFF;
        glow.setStrokeWidth(step);
        // Outermost first, faintest; the ring sits just outside the element so it never covers it.
        for (int i = GLOW_ALPHA.length - 1; i >= 0; i--) {
            float o = ringW + step * (i + 0.5f);
            int alpha = moving ? Math.min(0xFF, GLOW_ALPHA[i] * 3 / 2) : GLOW_ALPHA[i];
            glow.setColor((alpha << 24) | rgb);
            canvas.drawRoundRect(cur.left - o, cur.top - o, cur.right + o, cur.bottom + o, radius + o, radius + o, glow);
        }
        ring.setStrokeWidth(ringW);
        ring.setColor(accent);
        float o = ringW / 2f;
        canvas.drawRoundRect(cur.left - o, cur.top - o, cur.right + o, cur.bottom + o, radius + o, radius + o, ring);
    }
}
