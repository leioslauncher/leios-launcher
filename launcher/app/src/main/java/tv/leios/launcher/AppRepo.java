package tv.leios.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import java.text.Collator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Loads the TV apps (LEANBACK_LAUNCHER) with their banners and a sampled accent colour. */
final class AppRepo {

    static final class App {
        String pkg;
        /** What Home shows: your own name for the app, or {@link #original}. */
        String label;
        String original;
        ComponentName component;
        Drawable banner;
        Drawable icon;
        int color;
        /** Drawn as an icon tile even though it has a banner (your choice, globally or for this app). */
        boolean iconTile;
        /** A square icon from an icon pack or your own picture: always shown as an icon tile. */
        boolean customIcon;
        /** A line icon (an input tile): tinted to read on whatever the tile's background is. */
        boolean tintIcon;
    }

    /**
     * Icons from an icon pack (Customize › Apps › Icon pack) or chosen per app (hold OK › Tile).
     * A wide picture (16:9-ish, as TV icon packs ship) becomes the app's banner; a square one its
     * icon. Call off the main thread, right after {@link #loadAll}.
     */
    static void applyIcons(Context ctx, List<App> apps, String pack, org.json.JSONObject own, int w, int h, float radius) {
        for (App a : apps) {
            Drawable d = null;
            String spec = own.optString(a.pkg, "");
            if (spec.startsWith("pack:")) {
                String[] p = spec.split(":", 3);
                if (p.length == 3) d = IconPacks.load(ctx, p[1], p[2]);
            } else if (spec.startsWith("file:")) {
                java.io.File f = new java.io.File(ctx.getFilesDir(), "icons/" + a.pkg + ".png");
                android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeFile(f.getPath());
                if (bm != null) d = new BitmapDrawable(ctx.getResources(), bm);
            }
            if (d == null && !pack.isEmpty() && spec.isEmpty()) {
                d = IconPacks.iconFor(ctx, pack, a.pkg, a.component.getClassName());
            }
            if (d == null) continue;
            int iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
            if (iw > 0 && ih > 0 && iw >= ih * 1.5f) {
                a.banner = render(ctx, d, w, h, radius);
                a.color = sampleColor(d, a.color);
            } else {
                a.icon = d;
                a.customIcon = true;
            }
        }
    }

    /**
     * Call off the main thread. Banners are drawn once into a bitmap at tile size ({@code w}×{@code h},
     * a little larger for the focus scale-up), so the screen never scales full-size banners per frame.
     */
    static List<App> loadAll(Context ctx, int w, int h, float radius) {
        PackageManager pm = ctx.getPackageManager();
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        Map<String, App> byPkg = new LinkedHashMap<>();
        for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (ai.packageName.equals(ctx.getPackageName()) || byPkg.containsKey(ai.packageName)) continue;
            App a = new App();
            a.pkg = ai.packageName;
            a.label = String.valueOf(ri.loadLabel(pm));
            a.original = a.label;
            a.component = new ComponentName(ai.packageName, ai.name);
            Drawable banner = null;
            try {
                banner = ai.loadBanner(pm);
            } catch (RuntimeException ignored) {
                // A broken banner resource falls back to the icon tile.
            }
            // Always loaded: any app can be shown as an icon tile.
            try {
                a.icon = ri.loadIcon(pm);
            } catch (RuntimeException e) {
                a.icon = null;
            }
            a.color = sampleColor(banner != null ? banner : a.icon, ctx.getColor(R.color.neutral_glow));
            a.banner = banner == null ? null : render(ctx, banner, w, h, radius);
            byPkg.put(a.pkg, a);
        }
        List<App> apps = new ArrayList<>(byPkg.values());
        Collator collator = Collator.getInstance();
        apps.sort((x, y) -> collator.compare(x.label, y.label));
        return apps;
    }

    /** The banner at tile size with its corners already rounded, so tiles need no per-frame clip. */
    static Drawable render(Context ctx, Drawable d, int w, int h, float radius) {
        if (w <= 0 || h <= 0) return d;
        float scale = 1.12f;
        int bw = Math.round(w * scale), bh = Math.round(h * scale);
        try {
            Bitmap bm = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bm);
            android.graphics.Path clip = new android.graphics.Path();
            float r = radius * scale;
            clip.addRoundRect(0, 0, bw, bh, r, r, android.graphics.Path.Direction.CW);
            canvas.clipPath(clip);
            d.setBounds(0, 0, bw, bh);
            d.draw(canvas);
            return new BitmapDrawable(ctx.getResources(), bm);
        } catch (RuntimeException | OutOfMemoryError e) {
            return d;
        }
    }

    /** Saturation-weighted average of a tiny render of the drawable, lifted so it glows on black. */
    static int sampleColor(Drawable d, int neutral) {
        if (d == null) return neutral;
        int w = 32, h = 18;
        Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, w, h);
        d.draw(new Canvas(bm));
        int[] px = new int[w * h];
        bm.getPixels(px, 0, w, 0, 0, w, h);
        bm.recycle();
        float[] hsv = new float[3];
        double r = 0, g = 0, b = 0, total = 0;
        for (int p : px) {
            if (Color.alpha(p) < 128) continue;
            Color.colorToHSV(p, hsv);
            double weight = hsv[1] * hsv[2] + 0.02;
            r += Color.red(p) * weight;
            g += Color.green(p) * weight;
            b += Color.blue(p) * weight;
            total += weight;
        }
        if (total == 0) return neutral;
        Color.colorToHSV(Color.rgb((int) (r / total), (int) (g / total), (int) (b / total)), hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.15f);
        hsv[2] = Math.max(0.8f, hsv[2]);
        return Color.HSVToColor(hsv);
    }
}
