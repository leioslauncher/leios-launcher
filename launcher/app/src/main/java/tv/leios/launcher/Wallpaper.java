package tv.leios.launcher;

import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;

/**
 * Picture maths for backgrounds, all done once per picture (never per frame): brightness,
 * contrast, saturation and blur (Customize › Background › Adjust), and the accent colour taken
 * from a background (Customize › Look › Accent colour), kept readable at WCAG AA.
 */
final class Wallpaper {

    private Wallpaper() {
    }

    static final int[] BLUR_NAMES = {R.string.blur_off, R.string.blur_light, R.string.blur_medium, R.string.blur_strong};

    /** Each from -50 to 50 (percent), blur 0 to 3. All zero leaves the picture exactly as it is. */
    static final class Adjust {
        final int brightness, contrast, saturation, blur;

        Adjust(int brightness, int contrast, int saturation, int blur) {
            this.brightness = brightness;
            this.contrast = contrast;
            this.saturation = saturation;
            this.blur = blur;
        }

        static Adjust from(SharedPreferences p) {
            return new Adjust(clamp(p.getInt("adj_b", 0)), clamp(p.getInt("adj_c", 0)), clamp(p.getInt("adj_s", 0)),
                    Math.max(0, Math.min(3, p.getInt("adj_blur", 0))));
        }

        boolean none() {
            return brightness == 0 && contrast == 0 && saturation == 0 && blur == 0;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Adjust)) return false;
            Adjust a = (Adjust) o;
            return a.brightness == brightness && a.contrast == contrast && a.saturation == saturation && a.blur == blur;
        }

        @Override
        public int hashCode() {
            return ((brightness * 101 + contrast) * 101 + saturation) * 4 + blur;
        }

        private static int clamp(int v) {
            return Math.max(-50, Math.min(50, v));
        }
    }

    static ColorMatrix matrix(Adjust a) {
        ColorMatrix m = new ColorMatrix();
        m.setSaturation(1f + a.saturation / 100f);
        float c = 1f + a.contrast / 100f;
        float shift = 128f * (1f - c) + a.brightness * 2.55f;
        m.postConcat(new ColorMatrix(new float[] {
                c, 0, 0, 0, shift,
                0, c, 0, 0, shift,
                0, 0, c, 0, shift,
                0, 0, 0, 1, 0}));
        return m;
    }

    /** A new mutable bitmap with the adjustments drawn in; {@code src} is left untouched. */
    static Bitmap apply(Bitmap src, Adjust a) {
        if (src == null) return null;
        Bitmap out;
        try {
            out = a.blur > 0 ? blur(src, a.blur) : src.copy(Bitmap.Config.ARGB_8888, true);
            if (a.brightness != 0 || a.contrast != 0 || a.saturation != 0) {
                Bitmap colour = Bitmap.createBitmap(out.getWidth(), out.getHeight(), Bitmap.Config.ARGB_8888);
                Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
                p.setColorFilter(new ColorMatrixColorFilter(matrix(a)));
                new Canvas(colour).drawBitmap(out, 0, 0, p);
                out.recycle();
                out = colour;
            }
        } catch (OutOfMemoryError e) {
            return src.copy(Bitmap.Config.ARGB_8888, true);
        }
        return out;
    }

    /**
     * A soft blur cheaply: shrink, smooth the small picture with a few box-blur passes, then enlarge
     * in two steps (one big bilinear stretch leaves visible blocks).
     */
    private static Bitmap blur(Bitmap src, int level) {
        int w = src.getWidth(), h = src.getHeight();
        int div = new int[] {1, 8, 16, 28}[level];
        int sw = Math.max(2, w / div), sh = Math.max(2, h / div);
        Bitmap small = Bitmap.createScaledBitmap(src, sw, sh, true);
        int[] px = new int[sw * sh];
        small.getPixels(px, 0, sw, 0, 0, sw, sh);
        small.recycle();
        for (int pass = 0; pass < 3; pass++) {
            boxBlur(px, sw, sh, 2, true);
            boxBlur(px, sw, sh, 2, false);
        }
        Bitmap smooth = Bitmap.createBitmap(px, sw, sh, Bitmap.Config.ARGB_8888);
        Bitmap mid = Bitmap.createScaledBitmap(smooth, Math.min(w, sw * 4), Math.min(h, sh * 4), true);
        smooth.recycle();
        Bitmap out = Bitmap.createScaledBitmap(mid, w, h, true);
        if (mid != out) mid.recycle();
        return out.isMutable() ? out : out.copy(Bitmap.Config.ARGB_8888, true);
    }

    /** One box-blur pass of radius {@code r}, along rows or columns, in place. */
    private static void boxBlur(int[] px, int w, int h, int r, boolean rows) {
        int len = rows ? w : h, lines = rows ? h : w;
        int[] line = new int[len];
        for (int l = 0; l < lines; l++) {
            for (int i = 0; i < len; i++) line[i] = px[rows ? l * w + i : i * w + l];
            for (int i = 0; i < len; i++) {
                int a = 0, rr = 0, g = 0, b = 0, n = 0;
                for (int k = Math.max(0, i - r); k <= Math.min(len - 1, i + r); k++) {
                    int c = line[k];
                    a += c >>> 24;
                    rr += (c >> 16) & 0xFF;
                    g += (c >> 8) & 0xFF;
                    b += c & 0xFF;
                    n++;
                }
                px[rows ? l * w + i : i * w + l] = ((a / n) << 24) | ((rr / n) << 16) | ((g / n) << 8) | (b / n);
            }
        }
    }

    // ---- accent colour from the background ---------------------------------------------------

    /**
     * The background's most present vivid colour (by hue), or 0 if it has none to speak of (greys,
     * black and white): then Home keeps its own azure.
     */
    static int seedFrom(Drawable d) {
        if (d == null) return 0;
        // A copy, so the bounds of the one on screen aren't touched.
        if (d.getConstantState() != null) d = d.getConstantState().newDrawable().mutate();
        int w = 48, h = 27;
        Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, w, h);
        d.draw(new Canvas(bm));
        int seed = seedFrom(bm);
        bm.recycle();
        return seed;
    }

    static int seedFrom(Bitmap bm) {
        if (bm == null) return 0;
        int[] px = new int[48 * 27];
        try {
            // Pixels can't be read from a hardware bitmap; a small software copy is read instead.
            Bitmap soft = bm.getConfig() == Bitmap.Config.HARDWARE ? bm.copy(Bitmap.Config.ARGB_8888, false) : bm;
            Bitmap s = Bitmap.createScaledBitmap(soft, 48, 27, true);
            s.getPixels(px, 0, 48, 0, 0, 48, 27);
            if (s != bm) s.recycle();
            if (soft != bm && soft != s) soft.recycle();
        } catch (RuntimeException e) {
            return 0;
        }
        double[] weight = new double[12], sumH = new double[12], sumS = new double[12];
        float[] hsv = new float[3];
        double total = 0;
        for (int p : px) {
            Color.colorToHSV(p, hsv);
            total += 1;
            if (hsv[1] < 0.25f || hsv[2] < 0.2f) continue;
            int b = Math.min(11, (int) (hsv[0] / 30f));
            double wgt = hsv[1] * hsv[2];
            weight[b] += wgt;
            sumH[b] += hsv[0] * wgt;
            sumS[b] += hsv[1] * wgt;
        }
        int best = 0;
        for (int i = 1; i < 12; i++) if (weight[i] > weight[best]) best = i;
        // Less than ~4% of the picture is colourful: not enough to call it the picture's colour.
        if (weight[best] < total * 0.04 * 0.4) return 0;
        float hue = (float) (sumH[best] / weight[best]);
        float sat = (float) Math.max(0.55, Math.min(0.85, sumS[best] / weight[best]));
        return Color.HSVToColor(new float[] {hue, sat, 0.9f});
    }

    /**
     * {accent, accentFill} built on the seed's hue: the fill is darkened until white text on it
     * reaches 4.5:1 (WCAG AA), the ring/tick colour stays at least 3:1 against the background.
     * Null when the hue can't make both (Home then keeps the default accent).
     */
    static int[] accentPair(int seed, boolean light, int bg) {
        if (seed == 0) return null;
        float[] hsv = new float[3];
        Color.colorToHSV(seed, hsv);
        int fill = 0;
        for (float v = 0.8f; v >= 0.3f; v -= 0.02f) {
            int c = Color.HSVToColor(new float[] {hsv[0], hsv[1], v});
            if (contrast(Color.WHITE, c) >= 4.5) {
                fill = c;
                break;
            }
        }
        if (fill == 0) return null;
        int ring = light ? fill : Color.HSVToColor(new float[] {hsv[0], hsv[1] * 0.8f, 1f});
        if (contrast(ring, bg) < 3.0) return null;
        return new int[] {ring, fill};
    }

    static double contrast(int a, int b) {
        double la = luminance(a), lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(int c) {
        return 0.2126 * channel(Color.red(c)) + 0.7152 * channel(Color.green(c)) + 0.0722 * channel(Color.blue(c));
    }

    private static double channel(int v) {
        double s = v / 255.0;
        return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }
}
