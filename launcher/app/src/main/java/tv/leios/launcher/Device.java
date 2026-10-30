package tv.leios.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;

/**
 * What this TV has. JMGO's picture, brightness and keystone screens and the TV input list are on
 * this projector but not on every Android TV; menus show only what's really there.
 */
final class Device {

    private Device() {
    }

    static final String SETTINGS = "com.android.tv.settings";
    static final String JMGO_PICTURE = "com.android.tv.settings.partnercustomizer.picture.PictureActivity";
    static final String JMGO_LIGHT = "com.android.tv.settings.partnercustomizer.picture.JmgoLightActivity";
    static final String JMGO_KEYSTONE = "com.jmgo.jmgosettinglib.module.keystone.ManualFittingActivity";

    /** True if that screen exists and can be opened from another app. */
    static boolean has(Context c, String pkg, String cls) {
        try {
            return c.getPackageManager().getActivityInfo(new ComponentName(pkg, cls), 0).exported;
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return false;
        }
    }

    static Intent open(String pkg, String cls) {
        return new Intent().setComponent(new ComponentName(pkg, cls));
    }

    /** HDMI and other inputs (or a TV tuner) that can be switched to. */
    static boolean hasInputs(Context c) {
        TvInputManager tim = (TvInputManager) c.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) return false;
        try {
            for (TvInputInfo info : tim.getTvInputList()) {
                if (info.getParentId() == null && !info.isHidden(c)) return true;
            }
        } catch (RuntimeException ignored) {
        }
        return false;
    }
}
