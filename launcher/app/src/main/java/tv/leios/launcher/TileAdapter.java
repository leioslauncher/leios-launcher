package tv.leios.launcher;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * Banner tiles for the dock and the grid. Nothing in here is focusable: MainActivity drives focus.
 * Until the apps are loaded it shows empty placeholder tiles, so the screen never looks blank.
 */
final class TileAdapter extends RecyclerView.Adapter<TileAdapter.Holder> {

    static final class Holder extends RecyclerView.ViewHolder {
        final View card;
        final ImageView banner;
        final TextView label, fallbackName;

        Holder(View v) {
            super(v);
            card = v.findViewById(R.id.card);
            banner = v.findViewById(R.id.banner);
            label = v.findViewById(R.id.label);
            fallbackName = v.findViewById(R.id.fallbackName);
        }
    }

    static final int LABELS_FOCUS = 0, LABELS_ALWAYS = 1, LABELS_NEVER = 2;

    private final List<AppRepo.App> items = new ArrayList<>();
    private final int cardW, cardH;
    private final Tokens tk;
    private Theme theme;
    private int labelMode = LABELS_FOCUS;
    private int placeholders;
    /** Width of each tile's cell in a sideways-scrolling row; 0 in a grid (the grid sets it). */
    private int cellWidth;

    TileAdapter(int cardW, int cardH, Tokens tk, Theme theme) {
        this.cardW = cardW;
        this.cardH = cardH;
        this.tk = tk;
        this.theme = theme;
    }

    void setCellWidth(int px) {
        cellWidth = px;
    }

    static final int ICON_BG_APP = 0, ICON_BG_GLASS = 1, ICON_BG_PLAIN = 2;
    private int radius = -1, iconBg = ICON_BG_APP;

    /** Corner radius and the background of icon tiles (Look › Tiles). */
    void setLook(int radiusPx, int iconBackground) {
        radius = radiusPx;
        iconBg = iconBackground;
    }

    private int radius() {
        return radius >= 0 ? radius : tk.radiusMd;
    }

    private int padTop = -1, padBottom = -1;

    /** Space above and below each tile (row spacing); -1 keeps item_tile.xml's. */
    void setRowPadding(int top, int bottom) {
        padTop = top;
        padBottom = bottom;
    }

    /** Set before the first submit (the label mode changes rebuild the lists). */
    void setLabelMode(int mode) {
        labelMode = mode;
    }

    /** Empty tiles shown before the apps have loaded. */
    @android.annotation.SuppressLint("NotifyDataSetChanged")
    void showPlaceholders(int count) {
        items.clear();
        shown.clear();
        placeholders = count;
        notifyDataSetChanged();
    }

    /** What each tile showed when last submitted: a change means it's drawn again. */
    private final List<Object[]> shown = new ArrayList<>();

    private static Object[] look(AppRepo.App a) {
        return new Object[] {a.pkg, a.label, a.iconTile, a.banner, a.icon, a.color};
    }

    /**
     * Only tiles that were added, removed, moved or look different are redrawn, so hiding one app or
     * renaming another doesn't rebuild every banner in the row.
     */
    void submit(List<AppRepo.App> apps) {
        List<Object[]> next = new ArrayList<>();
        for (AppRepo.App a : apps) next.add(look(a));
        List<Object[]> old = new ArrayList<>(shown);
        int oldCount = Math.max(old.size(), placeholders);
        boolean hadPlaceholders = placeholders > 0;
        placeholders = 0;
        items.clear();
        items.addAll(apps);
        shown.clear();
        shown.addAll(next);
        if (hadPlaceholders) {
            // Placeholders to apps: everything is new.
            notifyItemRangeChanged(0, Math.min(oldCount, next.size()));
            if (next.size() > oldCount) notifyItemRangeInserted(oldCount, next.size() - oldCount);
            else if (oldCount > next.size()) notifyItemRangeRemoved(next.size(), oldCount - next.size());
            return;
        }
        androidx.recyclerview.widget.DiffUtil.calculateDiff(new androidx.recyclerview.widget.DiffUtil.Callback() {
            @Override
            public int getOldListSize() {
                return old.size();
            }

            @Override
            public int getNewListSize() {
                return next.size();
            }

            @Override
            public boolean areItemsTheSame(int o, int n) {
                return old.get(o)[0].equals(next.get(n)[0]);
            }

            @Override
            public boolean areContentsTheSame(int o, int n) {
                Object[] a = old.get(o), b = next.get(n);
                for (int i = 1; i < a.length; i++) if (!java.util.Objects.equals(a[i], b[i])) return false;
                return true;
            }
        }).dispatchUpdatesTo(this);
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_tile, parent, false);
        Holder h = new Holder(v);
        if (cellWidth > 0) v.getLayoutParams().width = cellWidth;
        if (padTop >= 0) v.setPadding(0, padTop, 0, padBottom);
        h.card.getLayoutParams().width = cardW;
        h.card.getLayoutParams().height = cardH;
        h.label.getLayoutParams().width = cardW;
        // No outline clip: banners come with rounded corners (AppRepo), backgrounds are rounded shapes.
        h.label.setTypeface(tk.medium500);
        h.fallbackName.setTypeface(tk.medium500);
        return h;
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        h.card.setScaleX(1f);
        h.card.setScaleY(1f);
        if (position >= items.size()) {
            h.banner.setImageDrawable(null);
            h.fallbackName.setVisibility(View.GONE);
            h.label.setText("");
            h.card.setBackground(Ui.rounded(radius(), theme.surfaceSoft, theme.stroke, tk.stroke));
            return;
        }
        AppRepo.App app = items.get(position);
        h.label.setText(app.label);
        h.label.setAlpha(labelMode == LABELS_ALWAYS ? 1f : 0f);
        h.label.setTextColor(theme.text);
        if (app.banner != null && !app.iconTile) {
            h.banner.setImageTintList(null);
            h.banner.setScaleType(ImageView.ScaleType.CENTER_CROP);
            h.banner.setPadding(0, 0, 0, 0);
            h.banner.setImageDrawable(app.banner);
            h.fallbackName.setVisibility(View.GONE);
            // The banner covers the whole card, so nothing is drawn under it.
            h.card.setBackground(null);
        } else {
            // No banner: the icon above the app's name, on a tile tinted with the icon's own colour.
            int pad = cardH / 6;
            // The icon keeps clear of the name below it, however small the tiles are.
            int nameH = Math.round(tk.textCaption * 1.25f) + tk.space2 * 2;
            h.banner.setScaleType(ImageView.ScaleType.FIT_CENTER);
            h.banner.setPadding(pad, pad / 2, pad, Math.max(cardH / 3, nameH));
            h.banner.setImageDrawable(app.icon);
            h.banner.setImageTintList(app.tintIcon ? android.content.res.ColorStateList.valueOf(
                    iconBg == ICON_BG_APP ? theme.onAccent : theme.text) : null);
            h.fallbackName.setText(app.label);
            // The name is on the tile already; don't repeat it underneath.
            h.label.setText("");
            h.fallbackName.setVisibility(View.VISIBLE);
            if (iconBg == ICON_BG_APP) {
                // Tinted with the icon's own colour over a dark base, so its name is always light.
                h.fallbackName.setTextColor(theme.onAccent);
                GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] {
                        blend(app.color, theme.tileFallback, 0.42f), blend(app.color, theme.tileFallback, 0.18f)});
                bg.setCornerRadius(radius());
                h.card.setBackground(bg);
            } else {
                // Glass lets the background through; plain is a solid surface. Both carry theme text.
                h.fallbackName.setTextColor(theme.text);
                h.card.setBackground(iconBg == ICON_BG_GLASS
                        ? Ui.rounded(radius(), theme.surfaceSoft, theme.stroke, tk.stroke)
                        : Ui.rounded(radius(), theme.surfaceRaised, theme.stroke, tk.stroke));
            }
        }
    }

    /** {@code amount} of {@code color} over {@code base}. */
    private static int blend(int color, int base, float amount) {
        return Color.rgb(
                Math.round(Color.red(color) * amount + Color.red(base) * (1 - amount)),
                Math.round(Color.green(color) * amount + Color.green(base) * (1 - amount)),
                Math.round(Color.blue(color) * amount + Color.blue(base) * (1 - amount)));
    }

    @Override
    public int getItemCount() {
        return Math.max(items.size(), placeholders);
    }
}
