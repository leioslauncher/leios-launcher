package tv.leios.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.service.dreams.DreamService;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Random;

/**
 * Android's screensaver in Home's own idle look: the clock and date, dim on near-black, moving a
 * little each minute. Chosen once as the screensaver; any key leaves it.
 */
public class Screensaver extends DreamService {

    private TextView clock, date;
    private LinearLayout col;
    private final Random random = new Random();
    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            update();
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(false);
        Tokens tk = Tokens.of(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(getColor(R.color.idle_bg));
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        clock = Ui.text(this, "", tk.textDisplay * 1.6f, getColor(R.color.dark_text), tk.regular);
        clock.setAlpha(0.8f);
        date = Ui.text(this, "", tk.textHeadline, getColor(R.color.dark_text), tk.regular);
        date.setAlpha(0.55f);
        for (TextView t : new TextView[] {clock, date}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.CENTER_HORIZONTAL;
            col.addView(t, lp);
        }
        root.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        setContentView(root);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        registerReceiver(tick, new IntentFilter(Intent.ACTION_TIME_TICK));
        update();
    }

    @Override
    public void onDreamingStopped() {
        try {
            unregisterReceiver(tick);
        } catch (RuntimeException ignored) {
        }
        super.onDreamingStopped();
    }

    private void update() {
        if (clock == null) return;
        SharedPreferences p = SafePrefs.focus(this);
        String fmt = p.getString("clock_fmt", "auto");
        boolean h24 = "auto".equals(fmt) ? android.text.format.DateFormat.is24HourFormat(this) : "24".equals(fmt);
        Locale locale = Locale.getDefault();
        Date now = new Date();
        clock.setText(new SimpleDateFormat(h24 ? "H:mm" : "h:mm", locale).format(now));
        date.setText(new SimpleDateFormat(
                android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEEMMMMd"), locale).format(now));
        // A small drift each minute, so nothing sits in one place all night.
        View parent = (View) col.getParent();
        if (parent != null && parent.getWidth() > 0) {
            float dx = (parent.getWidth() - col.getWidth()) / 2f * 0.6f, dy = (parent.getHeight() - col.getHeight()) / 2f * 0.6f;
            col.animate().translationX((random.nextFloat() * 2 - 1) * dx).translationY((random.nextFloat() * 2 - 1) * dy)
                    .alpha(1f).setDuration(2000).start();
        }
    }
}
