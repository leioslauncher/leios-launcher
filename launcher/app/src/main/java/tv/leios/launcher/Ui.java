package tv.leios.launcher;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/** Small view builders shared by Home and the ≡ panel, all drawn from Theme and Tokens. */
final class Ui {

    private Ui() {
    }

    /** {@code color} with its alpha multiplied by {@code f}. */
    static int alpha(int color, float f) {
        return (Math.round((color >>> 24) * f) << 24) | (color & 0x00FFFFFF);
    }

    static GradientDrawable rounded(float radius, int fill, int stroke, int strokeWidth) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(radius);
        g.setColor(fill);
        if (strokeWidth > 0) g.setStroke(strokeWidth, stroke);
        return g;
    }

    /** A soft fade from {@code color} to transparent, for text over photos (no blur, no shadow). */
    static GradientDrawable fade(GradientDrawable.Orientation o, int color) {
        return new GradientDrawable(o, new int[] {color, color & 0x00FFFFFF});
    }

    static TextView text(Context c, CharSequence s, float sizePx, int color, Typeface face) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx);
        t.setTextColor(color);
        t.setTypeface(face);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
        return t;
    }

    static ImageView icon(Context c, int res, int size, int color) {
        ImageView v = new ImageView(c);
        v.setImageResource(res);
        v.setImageTintList(ColorStateList.valueOf(color));
        v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        v.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        return v;
    }

    static ProgressBar spinner(Context c, int size, int color) {
        ProgressBar p = new ProgressBar(c);
        p.setIndeterminate(true);
        p.setIndeterminateTintList(ColorStateList.valueOf(color));
        p.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        return p;
    }

    /**
     * A row of remote-button hints: each pair is a key ("OK", "◀", "Hold OK") drawn as a small
     * outlined key cap, then what it does.
     */
    static LinearLayout hints(Context c, Theme th, Tokens tk, String... keyThenLabel) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i + 1 < keyThenLabel.length; i += 2) {
            TextView key = text(c, keyThenLabel[i], tk.textCaption * 0.86f, th.textSecondary, tk.medium500);
            key.setPadding(tk.space2, tk.space1 / 2, tk.space2, tk.space1 / 2);
            key.setBackground(rounded(tk.radiusSm / 2f, 0, th.textTertiary, tk.stroke));
            LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) klp.setMarginStart(tk.space5);
            row.addView(key, klp);
            TextView label = text(c, keyThenLabel[i + 1], tk.textCaption, th.textTertiary, tk.regular);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            llp.setMarginStart(tk.space2);
            row.addView(label, llp);
        }
        return row;
    }

    /** A real slider: rounded track, filled part, no text glyphs. */
    static final class Slider extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private float value;

        Slider(Context c, int width, int height) {
            super(c);
            setLayoutParams(new LinearLayout.LayoutParams(width, height));
        }

        void setColors(int trackColor, int fillColor) {
            track.setColor(trackColor);
            fill.setColor(fillColor);
            invalidate();
        }

        void setValue(float v) {
            value = Math.max(0f, Math.min(1f, v));
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float h = getHeight(), rad = h / 2f;
            r.set(0, 0, getWidth(), h);
            canvas.drawRoundRect(r, rad, rad, track);
            if (value <= 0f) return;
            r.right = Math.max(h, getWidth() * value);
            canvas.drawRoundRect(r, rad, rad, fill);
        }
    }

    /**
     * A short message at the bottom of the screen in the app's own style (system toasts are grey
     * and can't be themed). Lives in {@code host}; a newer message replaces the one showing.
     */
    static final class Toaster {
        private final FrameLayout host;
        private final Theme th;
        private final Tokens tk;
        private LinearLayout showing;

        Toaster(FrameLayout host, Theme th, Tokens tk) {
            this.host = host;
            this.th = th;
            this.tk = tk;
        }

        /** True while a menu sheet is open on the right (the owner sets it before each message). */
        boolean besideSheet;

        void show(CharSequence message, int iconRes) {
            Context c = host.getContext();
            if (showing != null) host.removeView(showing);
            LinearLayout box = new LinearLayout(c);
            box.setOrientation(LinearLayout.HORIZONTAL);
            box.setGravity(Gravity.CENTER_VERTICAL);
            box.setPadding(tk.space5, tk.space3, tk.space5, tk.space3);
            box.setBackground(rounded(tk.radiusLg, th.surfaceRaised, th.stroke, tk.stroke));
            box.setElevation(tk.elevationSheet);
            if (iconRes != 0) {
                ImageView i = icon(c, iconRes, tk.iconMd, th.accent);
                ((LinearLayout.LayoutParams) i.getLayoutParams()).setMarginEnd(tk.space3);
                box.addView(i);
            }
            TextView t = text(c, message, tk.textLabel, th.text, tk.medium500);
            t.setSingleLine(false);
            t.setMaxLines(2);
            box.addView(t);
            // Beside a menu sheet (on the right) the message goes bottom left, so it never covers it.
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | (besideSheet ? Gravity.START : Gravity.CENTER_HORIZONTAL));
            lp.bottomMargin = tk.safeV + tk.space3;
            if (besideSheet) {
                lp.leftMargin = tk.safeH;
                t.setMaxWidth(host.getWidth() - tk.sheetWidth - tk.safeH * 3 - tk.space5 * 2 - tk.iconMd - tk.space3);
            }
            host.addView(box, lp);
            showing = box;
            box.setAlpha(0f);
            box.setTranslationY(tk.space4);
            box.animate().alpha(1f).translationY(0).setDuration(tk.medium).setInterpolator(Tokens.EASE_OUT).start();
            box.postDelayed(() -> {
                if (showing != box) return;
                box.animate().alpha(0f).translationY(tk.space2).setDuration(tk.medium)
                        .setInterpolator(Tokens.EASE_IN_OUT).withEndAction(() -> {
                            host.removeView(box);
                            if (showing == box) showing = null;
                        }).start();
            }, tk.toastMs);
        }
    }

    static Drawable tinted(Context c, int res, int color) {
        Drawable d = c.getDrawable(res);
        if (d == null) return null;
        d = d.mutate();
        d.setTint(color);
        return d;
    }
}
