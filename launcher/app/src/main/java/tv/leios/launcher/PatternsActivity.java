package tv.leios.launcher;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

/**
 * Test patterns for setting up the projector, drawn here at one pixel per screen pixel:
 * focus, overscan, grey steps, colour bars, black and white levels, and fine detail.
 * Left/Right change pattern, OK shows or hides what to look for, Back closes.
 * Android TV draws apps at 1920×1080, so these are 1080p patterns (scaled to the 4K panel by the
 * projector, as all of Home is). Video plays at the panel's full resolution.
 */
public class PatternsActivity extends Activity {

    static final String[] NAMES = {"Focus", "Overscan and safe area", "Grey steps", "Colour bars",
            "Black level", "White level", "Fine detail"};
    static final String[] TIPS = {
            "Lines and text should be equally sharp in the centre and the corners.",
            "The outer frame is the picture edge. If any of the red edge is cut off, the projector crops (overscan).",
            "Every step should be a different shade, from black to white, with no colour tint.",
            "Colours should look clean, with no step bleeding into the next.",
            "Turn Brightness until bar 2 is just visible and bar 1 isn't (the background is pure black).",
            "Turn Contrast until bar 98 is just visible against pure white at 100.",
            "One-pixel lines should stay separate. Blur or shimmer means focus or sharpness is off."};

    private int pattern;
    private boolean showInfo = true;
    private PatternView view;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        pattern = Math.max(0, Math.min(NAMES.length - 1, getIntent().getIntExtra("pattern", 0)));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        view = new PatternView(this);
        setContentView(view);
        // Full screen: the patterns must reach every edge (the overscan pattern measures them).
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) c.hide(WindowInsets.Type.systemBars());
        } else {
            //noinspection deprecation
            view.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_DOWN) {
            pattern = (pattern + 1) % NAMES.length;
        } else if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_UP) {
            pattern = (pattern + NAMES.length - 1) % NAMES.length;
        } else if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
            showInfo = !showInfo;
        } else return super.onKeyDown(code, e);
        view.invalidate();
        return true;
    }

    private final class PatternView extends View {
        private final Paint p = new Paint();
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Tokens tk;

        PatternView(Context c) {
            super(c);
            tk = Tokens.of(c);
            text.setTypeface(tk.medium500);
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            p.setAntiAlias(false);
            p.setStyle(Paint.Style.FILL);
            switch (pattern) {
                case 0: focus(c, w, h); break;
                case 1: overscan(c, w, h); break;
                case 2: greySteps(c, w, h); break;
                case 3: colourBars(c, w, h); break;
                case 4: blackLevel(c, w, h); break;
                case 5: whiteLevel(c, w, h); break;
                default: detail(c, w, h); break;
            }
            if (showInfo) info(c, w, h);
        }

        /**
         * What this pattern is and what to look for: a dark card (dark in light mode too, so it never
         * floods a black pattern with light) along the bottom of the safe area.
         */
        private void info(Canvas c, int w, int h) {
            float pad = tk.space5, x = tk.safeH * 2f;
            float cardH = tk.textHeadline + tk.textLabel * 3.2f + pad * 2;
            float y = h - tk.safeV - cardH + pad;
            text.setTextSize(tk.textHeadline);
            float titleW = text.measureText((pattern + 1) + " / " + NAMES.length + "   " + NAMES[pattern]);
            text.setTextSize(tk.textLabel);
            float tipW = Math.min(w - x * 2, text.measureText(TIPS[pattern]));
            RectF card = new RectF(x - pad, y - pad, x + Math.max(titleW, tipW) + pad, y - pad + cardH);
            p.setAntiAlias(true);
            p.setColor(getResources().getColor(R.color.dark_surface, null));
            c.drawRoundRect(card, tk.radiusLg, tk.radiusLg, p);
            text.setColor(getResources().getColor(R.color.dark_text, null));
            text.setTextSize(tk.textHeadline);
            c.drawText((pattern + 1) + " / " + NAMES.length + "   " + NAMES[pattern], x, y + tk.textHeadline, text);
            text.setColor(getResources().getColor(R.color.dark_text_secondary, null));
            text.setTextSize(tk.textLabel);
            c.drawText(TIPS[pattern], x, y + tk.textHeadline + tk.textLabel * 1.6f, text);
            c.drawText("◀ ▶  Next pattern     OK  Hide this     BACK  Close", x, y + tk.textHeadline + tk.textLabel * 3f, text);
            p.setAntiAlias(false);
        }

        private void focus(Canvas c, int w, int h) {
            c.drawColor(Color.BLACK);
            p.setColor(0xFF808080);
            for (int x = 0; x < w; x += 120) c.drawRect(x, 0, x + 1, h, p);
            for (int y = 0; y < h; y += 120) c.drawRect(0, y, w, y + 1, p);
            p.setColor(Color.WHITE);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2);
            p.setAntiAlias(true);
            float r = Math.min(w, h) * 0.42f;
            c.drawCircle(w / 2f, h / 2f, r, p);
            c.drawCircle(w / 2f, h / 2f, r / 2, p);
            c.drawLine(w / 2f - r, h / 2f, w / 2f + r, h / 2f, p);
            c.drawLine(w / 2f, h / 2f - r, w / 2f, h / 2f + r, p);
            p.setStyle(Paint.Style.FILL);
            text.setColor(Color.WHITE);
            text.setTextSize(h / 40f);
            int[][] spots = {{w / 2, h / 2}, {w / 8, h / 8}, {w * 7 / 8, h / 8}, {w / 8, h * 7 / 8}, {w * 7 / 8, h * 7 / 8}};
            for (int[] s : spots) {
                for (int i = 0; i < 6; i++) c.drawRect(s[0] - 60 + i * 20, s[1] - 70, s[0] - 59 + i * 20, s[1] - 30, p);
                c.drawText("Sharp text 123", s[0] - text.measureText("Sharp text 123") / 2, s[1] + 10, text);
            }
            p.setAntiAlias(false);
        }

        private void overscan(Canvas c, int w, int h) {
            c.drawColor(0xFF202020);
            int[] pct = {0, 25, 50};
            int[] colors = {Color.RED, 0xFFFFC000, 0xFF00C000};
            String[] labels = {"Edge", "2.5%", "5% (title safe)"};
            p.setStyle(Paint.Style.STROKE);
            text.setTextSize(h / 45f);
            for (int i = 0; i < pct.length; i++) {
                int dx = w * pct[i] / 1000, dy = h * pct[i] / 1000;
                p.setColor(colors[i]);
                p.setStrokeWidth(i == 0 ? 6 : 2);
                c.drawRect(dx + 1, dy + 1, w - dx - 1, h - dy - 1, p);
                text.setColor(colors[i]);
                c.drawText(labels[i], dx + 12, dy + text.getTextSize() + 10, text);
            }
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.WHITE);
            c.drawRect(w / 2f - 1, 0, w / 2f + 1, h, p);
            c.drawRect(0, h / 2f - 1, w, h / 2f + 1, p);
        }

        private void greySteps(Canvas c, int w, int h) {
            int steps = 11;
            for (int i = 0; i < steps; i++) {
                int v = Math.round(255f * i / (steps - 1));
                p.setColor(Color.rgb(v, v, v));
                c.drawRect(w * i / (float) steps, 0, w * (i + 1) / (float) steps, h, p);
                label(c, (i * 10) + "%", w * (i + 0.5f) / steps, h * 0.12f, v < 128);
            }
        }

        private void colourBars(Canvas c, int w, int h) {
            int[] bars = {Color.WHITE, 0xFFFFFF00, 0xFF00FFFF, 0xFF00FF00, 0xFFFF00FF, 0xFFFF0000, 0xFF0000FF, Color.BLACK};
            for (int i = 0; i < bars.length; i++) {
                p.setColor(bars[i]);
                c.drawRect(w * i / (float) bars.length, 0, w * (i + 1) / (float) bars.length, h * 0.7f, p);
            }
            // Below: the same hues at 50%, to show colour stays clean when it's darker.
            for (int i = 0; i < bars.length; i++) {
                int b = bars[i];
                p.setColor(Color.rgb(Color.red(b) / 2, Color.green(b) / 2, Color.blue(b) / 2));
                c.drawRect(w * i / (float) bars.length, h * 0.7f, w * (i + 1) / (float) bars.length, h, p);
            }
        }

        private void blackLevel(Canvas c, int w, int h) {
            c.drawColor(Color.BLACK);
            int[] levels = {0, 1, 2, 3, 4, 6, 8, 10};
            for (int i = 0; i < levels.length; i++) {
                int v = Math.round(255f * levels[i] / 100f);
                p.setColor(Color.rgb(v, v, v));
                float x0 = w * (0.1f + 0.8f * i / levels.length), x1 = w * (0.1f + 0.8f * (i + 1) / levels.length) - 8;
                c.drawRect(x0, h * 0.3f, x1, h * 0.6f, p);
                label(c, String.valueOf(levels[i]), (x0 + x1) / 2, h * 0.68f, true);
            }
        }

        private void whiteLevel(Canvas c, int w, int h) {
            c.drawColor(Color.WHITE);
            int[] levels = {90, 92, 94, 96, 97, 98, 99, 100};
            for (int i = 0; i < levels.length; i++) {
                int v = Math.round(255f * levels[i] / 100f);
                p.setColor(Color.rgb(v, v, v));
                float x0 = w * (0.1f + 0.8f * i / levels.length), x1 = w * (0.1f + 0.8f * (i + 1) / levels.length) - 8;
                c.drawRect(x0, h * 0.3f, x1, h * 0.6f, p);
                label(c, String.valueOf(levels[i]), (x0 + x1) / 2, h * 0.68f, false);
            }
        }

        /** One-pixel and two-pixel lines both ways, and a one-pixel checkerboard. */
        private void detail(Canvas c, int w, int h) {
            c.drawColor(Color.BLACK);
            p.setColor(Color.WHITE);
            int bw = w / 4, bh = h / 3;
            int[][] cells = {{1, 0}, {2, 0}, {1, 1}, {2, 1}};
            for (int k = 0; k < cells.length; k++) {
                int size = cells[k][0];
                boolean vertical = cells[k][1] == 0;
                int x0 = (k % 2) * bw * 2 + bw / 2, y0 = (k / 2) * bh + bh / 4;
                for (int i = 0; i < bw; i += size * 2) {
                    if (vertical) c.drawRect(x0 + i, y0, x0 + i + size, y0 + bh / 2, p);
                    else if (i < bh / 2) c.drawRect(x0, y0 + i, x0 + bw, y0 + i + size, p);
                }
                label(c, size + " px " + (vertical ? "vertical" : "horizontal"), x0 + bw / 2f, y0 + bh / 2f + h / 30f, true);
            }
            // A one-pixel checkerboard: a 2×2 tile repeated, not drawn pixel by pixel.
            int x0 = w / 2 - bw / 2, y0 = bh * 2 + bh / 6, size = bh / 2;
            android.graphics.Bitmap tile = android.graphics.Bitmap.createBitmap(new int[] {Color.WHITE, Color.BLACK,
                    Color.BLACK, Color.WHITE}, 2, 2, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.BitmapShader s = new android.graphics.BitmapShader(tile,
                    android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT);
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.setTranslate(x0, y0);
            s.setLocalMatrix(m);
            p.setShader(s);
            c.drawRect(x0, y0, x0 + bw, y0 + size, p);
            p.setShader(null);
            label(c, "1 px checkerboard", x0 + bw / 2f, y0 + size + h / 30f, true);
        }

        private void label(Canvas c, String s, float cx, float y, boolean light) {
            text.setColor(light ? Color.WHITE : Color.BLACK);
            text.setTextSize(getHeight() / 40f);
            c.drawText(s, cx - text.measureText(s) / 2, y, text);
        }
    }
}
