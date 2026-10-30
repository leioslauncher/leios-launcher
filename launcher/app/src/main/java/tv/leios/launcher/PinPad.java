package tv.leios.launcher;

import android.content.Context;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Four digits picked with the remote: ▲▼ change the digit, ▶ or OK goes to the next one, ◀ back,
 * number keys type directly, Back cancels. Entered digits show as dots. Used by Home (in a
 * dialog) and by the ≡ helper (over an app), which both pass their key presses to {@link #key}.
 */
final class PinPad extends LinearLayout {

    interface Listener {
        void done(String pin);

        void cancel();
    }

    private final Theme th;
    private final Tokens tk;
    private final Listener listener;
    private final TextView[] boxes = new TextView[Lock.DIGITS];
    private final TextView message;
    private final int[] digits = new int[Lock.DIGITS];
    private int at;
    private final String hint;

    PinPad(Context c, Theme th, String crumb, String title, String hint, Listener listener) {
        super(c);
        this.th = th;
        this.tk = Tokens.of(c);
        this.listener = listener;
        this.hint = hint;
        setOrientation(VERTICAL);
        setPadding(tk.space6, tk.space5, tk.space6, tk.space4);
        setBackground(Ui.rounded(tk.radiusLg, th.surface, th.stroke, tk.stroke));
        setElevation(tk.elevationSheet);
        if (crumb != null) {
            TextView cr = Ui.text(c, crumb.toUpperCase(java.util.Locale.getDefault()), tk.textCaption, th.textTertiary, tk.medium500);
            cr.setLetterSpacing(0.06f);
            addView(cr);
        }
        TextView t = Ui.text(c, title, tk.textHeadline, th.text, tk.semibold);
        LayoutParams tlp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = tk.space1;
        tlp.bottomMargin = tk.space5;
        addView(t, tlp);
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(HORIZONTAL);
        for (int i = 0; i < boxes.length; i++) {
            TextView b = Ui.text(c, "", tk.textTitle, th.text, tk.medium500);
            b.setGravity(Gravity.CENTER);
            b.setFontFeatureSettings("tnum");
            LayoutParams blp = new LayoutParams(tk.pillHeight * 3 / 2, tk.pillHeight * 2);
            if (i > 0) blp.setMarginStart(tk.space3);
            row.addView(b, blp);
            boxes[i] = b;
        }
        addView(row);
        message = Ui.text(c, hint, tk.textLabel, th.textSecondary, tk.regular);
        message.setSingleLine(false);
        message.setMaxLines(2);
        LayoutParams mlp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = tk.space4;
        addView(message, mlp);
        LayoutParams hlp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = tk.space4;
        addView(Ui.hints(c, th, tk, "▲ ▼", "Digit", "▶ OK", "Next", "BACK", "Cancel"), hlp);
        refresh();
    }

    /** Handles a key press; true if it was the pad's. */
    boolean key(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return isPadKey(e.getKeyCode());
        int code = e.getKeyCode();
        if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) {
            digits[at] = code - KeyEvent.KEYCODE_0;
            next();
        } else if (code == KeyEvent.KEYCODE_DPAD_UP) {
            digits[at] = (digits[at] + 1) % 10;
        } else if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
            digits[at] = (digits[at] + 9) % 10;
        } else if (code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_CENTER
                || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (e.getRepeatCount() == 0) next();
        } else if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DEL) {
            if (at > 0) at--;
        } else if (code == KeyEvent.KEYCODE_BACK) {
            if (e.getRepeatCount() == 0) listener.cancel();
            return true;
        } else return false;
        refresh();
        return true;
    }

    private static boolean isPadKey(int code) {
        return (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) || code == KeyEvent.KEYCODE_DPAD_UP
                || code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_LEFT
                || code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_CENTER
                || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_DEL;
    }

    private void next() {
        if (at < digits.length - 1) {
            at++;
            digits[at] = 0;
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int d : digits) sb.append(d);
        at = 0;
        java.util.Arrays.fill(digits, 0);
        listener.done(sb.toString());
    }

    /** Wrong PIN (or a mismatch while setting one): start over, saying why. */
    void error(String why) {
        at = 0;
        java.util.Arrays.fill(digits, 0);
        message.setText(why);
        message.setTextColor(th.text);
        animate().translationX(tk.space2).setDuration(tk.fast / 2).withEndAction(() ->
                animate().translationX(-tk.space2).setDuration(tk.fast / 2).withEndAction(() ->
                        animate().translationX(0).setDuration(tk.fast / 2).start()).start()).start();
        refresh();
    }

    void setMessage(String text) {
        message.setText(text == null ? hint : text);
        message.setTextColor(th.textSecondary);
    }

    private void refresh() {
        for (int i = 0; i < boxes.length; i++) {
            boolean cur = i == at;
            boxes[i].setText(i < at ? "•" : cur ? String.valueOf(digits[i]) : "");
            boxes[i].setTextColor(cur ? th.onAccent : th.text);
            boxes[i].setBackground(Ui.rounded(tk.radiusMd, cur ? th.accentFill : th.surfaceRaised, th.stroke,
                    cur ? 0 : tk.stroke));
        }
    }
}
