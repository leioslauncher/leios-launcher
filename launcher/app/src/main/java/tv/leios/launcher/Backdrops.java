package tv.leios.launcher;

import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.SweepGradient;

/**
 * Shape backgrounds in the spirit of Apple's abstract wallpapers: Bloom (soft overlapping colour
 * shapes), Orbit (a glowing sphere with fine rings), Prism (overlapping translucent facets) and
 * Dunes (layered hills at dusk). Each is drawn once into a screen-sized bitmap off the main thread,
 * so on screen it costs no more than a photo. Palettes are in res/values/backdrops.xml.
 */
final class Backdrops {

    static final int BLOOM = 0, ORBIT = 1, PRISM = 2, DUNES = 3;
    /** Each shape's name (string resources), in the order of their numbers. */
    static final int[] NAMES = {R.string.shape_bloom, R.string.shape_orbit, R.string.shape_prism, R.string.shape_dunes};

    private Backdrops() {
    }

    /** Call off the main thread. */
    static Bitmap render(Resources r, int kind, boolean light, int w, int h) {
        int[] c = palette(r, kind, light);
        Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bm);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, h, c[0], c[1], Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, p);
        switch (kind) {
            case BLOOM: bloom(canvas, p, c, w, h, light); break;
            case ORBIT: orbit(canvas, p, c, w, h, light); break;
            case PRISM: prism(canvas, p, c, w, h, light); break;
            default: dunes(canvas, p, c, w, h); break;
        }
        return bm;
    }

    private static int[] palette(Resources r, int kind, boolean light) {
        int id;
        switch (kind) {
            case BLOOM: id = light ? R.array.bloom_light : R.array.bloom_dark; break;
            case ORBIT: id = light ? R.array.orbit_light : R.array.orbit_dark; break;
            case PRISM: id = light ? R.array.prism_light : R.array.prism_dark; break;
            default: id = light ? R.array.dunes_light : R.array.dunes_dark; break;
        }
        TypedArray a = r.obtainTypedArray(id);
        int[] out = new int[a.length()];
        for (int i = 0; i < out.length; i++) out[i] = a.getColor(i, 0);
        a.recycle();
        return out;
    }

    private static int alpha(int color, float f) {
        return Ui.alpha(color | 0xFF000000, f);
    }

    /** A soft disc: solid in the middle, fading out over its outer part. */
    private static void blob(Canvas cv, Paint p, float x, float y, float rad, int color, float strength) {
        p.setShader(new RadialGradient(x, y, rad,
                new int[] {alpha(color, strength), alpha(color, strength * 0.75f), alpha(color, 0f)},
                new float[] {0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        cv.drawCircle(x, y, rad, p);
    }

    private static void bloom(Canvas cv, Paint p, int[] c, int w, int h, boolean light) {
        float s = light ? 0.85f : 0.9f;
        blob(cv, p, w * 0.16f, h * 0.82f, h * 0.78f, c[2], s);
        blob(cv, p, w * 0.64f, h * 0.30f, h * 0.80f, c[3], s * 0.9f);
        blob(cv, p, w * 0.90f, h * 0.88f, h * 0.62f, c[4], s * 0.85f);
        blob(cv, p, w * 0.42f, h * 0.02f, h * 0.46f, c[5], s * 0.7f);
        // A quiet vignette keeps the corners (where the text sits) calm.
        if (!light) {
            p.setShader(new RadialGradient(w * 0.5f, h * 0.5f, w * 0.75f,
                    new int[] {0, 0, alpha(c[1], 0.65f)}, new float[] {0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
            cv.drawRect(0, 0, w, h, p);
        }
    }

    private static void orbit(Canvas cv, Paint p, int[] c, int w, int h, boolean light) {
        // Centre stage, between the clock and the menus, so the sphere is never hidden.
        float cx = w * 0.56f, cy = h * 0.40f, sphere = h * 0.25f;
        // Atmosphere, then the sphere.
        blob(cv, p, cx, cy, h * 0.78f, c[3], light ? 0.55f : 0.6f);
        p.setShader(new RadialGradient(cx - sphere * 0.3f, cy - sphere * 0.35f, sphere * 1.15f,
                new int[] {c[2], alpha(c[3], 1f), alpha(c[3], 0.95f)}, new float[] {0f, 0.7f, 1f},
                Shader.TileMode.CLAMP));
        cv.drawCircle(cx, cy, sphere, p);
        // Fine rings that fade around their circumference.
        Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(Math.max(1.5f, h / 540f * 1.2f));
        for (int i = 0; i < 9; i++) {
            float rad = sphere + h * (0.08f + i * 0.13f);
            float a = (light ? 0.5f : 0.34f) * (1f - i / 10f);
            ring.setShader(new SweepGradient(cx, cy,
                    new int[] {alpha(c[4], a), alpha(c[4], 0f), alpha(c[4], a * 0.5f), alpha(c[4], a)},
                    new float[] {0f, 0.35f, 0.7f, 1f}));
            cv.save();
            cv.rotate(i * 37f, cx, cy);
            cv.drawCircle(cx, cy, rad, ring);
            cv.restore();
        }
    }

    private static void prism(Canvas cv, Paint p, int[] c, int w, int h, boolean light) {
        float a = light ? 0.75f : 0.5f;
        float[][] facets = {
                {0.38f, -0.10f, 1.08f, 0.22f, 0.66f, 1.05f},
                {0.62f, -0.05f, 1.10f, 0.95f, 0.30f, 0.80f},
                {0.80f, 0.20f, 1.12f, 1.10f, 0.50f, 1.10f},
                {0.48f, 0.35f, 0.95f, 0.55f, 0.70f, 1.10f},
                {0.20f, 0.70f, 0.60f, 0.48f, 0.55f, 1.10f},
                {0.90f, -0.10f, 1.10f, 0.40f, 0.72f, 0.30f},
        };
        int[] colors = {c[2], c[3], c[4], c[5], c[3], c[2]};
        Path path = new Path();
        for (int i = 0; i < facets.length; i++) {
            float[] f = facets[i];
            path.reset();
            path.moveTo(f[0] * w, f[1] * h);
            path.lineTo(f[2] * w, f[3] * h);
            path.lineTo(f[4] * w, f[5] * h);
            path.close();
            int col = colors[i];
            p.setShader(new LinearGradient(f[0] * w, f[1] * h, f[4] * w, f[5] * h,
                    alpha(col, a), alpha(col, a * 0.25f), Shader.TileMode.CLAMP));
            cv.drawPath(path, p);
        }
        // Light from the top right.
        blob(cv, p, w * 0.85f, h * 0.05f, h * 0.6f, light ? 0xFFFFFFFF : c[4], light ? 0.5f : 0.22f);
    }

    private static void dunes(Canvas cv, Paint p, int[] c, int w, int h) {
        // A low sun on the horizon, then four ridges back to front, each a little darker.
        blob(cv, p, w * 0.66f, h * 0.50f, h * 0.70f, c[2], 0.55f);
        blob(cv, p, w * 0.66f, h * 0.50f, h * 0.16f, c[2], 0.95f);
        float[] tops = {0.46f, 0.58f, 0.70f, 0.82f};
        float[] phase = {0.1f, 0.55f, 0.3f, 0.8f};
        Path path = new Path();
        for (int i = 0; i < 4; i++) {
            float base = tops[i] * h, amp = h * (0.07f - i * 0.008f);
            path.reset();
            path.moveTo(0, h);
            path.lineTo(0, base);
            int steps = 48;
            for (int s = 0; s <= steps; s++) {
                float x = w * s / (float) steps;
                double t = s / (double) steps * Math.PI * 2 * (1.1 + i * 0.25) + phase[i] * Math.PI * 2;
                float y = base - (float) (Math.sin(t) * amp + Math.sin(t * 0.5 + i) * amp * 0.6);
                path.lineTo(x, y);
            }
            path.lineTo(w, h);
            path.close();
            int col = c[3 + i];
            p.setShader(new LinearGradient(0, base - amp * 2, 0, h, col, alpha(c[Math.min(6, 4 + i)], 1f),
                    Shader.TileMode.CLAMP));
            cv.drawPath(path, p);
        }
    }
}
