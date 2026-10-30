package tv.leios.launcher;

import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

/** The photo and video lists on every storage volume, on Android 9 as well as 10 and newer. */
final class Media {

    private Media() {
    }

    /** Android 10 added named volumes (this TV, each USB drive) and folders in the media index. */
    static boolean volumes() {
        return Build.VERSION.SDK_INT >= 29;
    }

    static Uri images() {
        return volumes() ? MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
    }

    static Uri videos() {
        return volumes() ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                : MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
    }

    /** "external_primary" on Android 10+, "external" (the only one) before. */
    static String primary() {
        return volumes() ? MediaStore.VOLUME_EXTERNAL_PRIMARY : "external";
    }
}
