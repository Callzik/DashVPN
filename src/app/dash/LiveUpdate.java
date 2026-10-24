package app.dash;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.service.notification.StatusBarNotification;

/**
 * Android 16 Live Updates: the VPN notification as a chip in the status bar and in Samsung's Now Bar.
 * The app is built against SDK 34, so every call goes by name and fails quietly on older systems.
 */
final class LiveUpdate {
    private LiveUpdate() {}

    static final String EXTRA_REQUEST = "android.requestPromotedOngoing";
    /** Notification.FLAG_PROMOTED_ONGOING: set by the system once it accepted the request. */
    private static final int FLAG_PROMOTED = 0x00040000;

    static boolean supported() {
        return Build.VERSION.SDK_INT >= 36;
    }

    static void request(Notification.Builder b) {
        if (!supported()) return;
        b.getExtras().putBoolean(EXTRA_REQUEST, true);
        try {
            Notification.Builder.class.getMethod("setRequestPromotedOngoing", boolean.class).invoke(b, true);
        } catch (Throwable ignored) {
        }
    }

    /** null = unknown (old system or no such call). */
    static Boolean allowed(Context c) {
        if (!supported()) return null;
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            return (Boolean) NotificationManager.class.getMethod("canPostPromotedNotifications").invoke(nm);
        } catch (Throwable t) {
            return null;
        }
    }

    static Boolean promotable(Notification n) {
        if (!supported()) return null;
        try {
            return (Boolean) Notification.class.getMethod("hasPromotableCharacteristics").invoke(n);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Whether the notification with this id is shown as a Live Update right now; null = not shown at all. */
    static Boolean shown(Context c, int id) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            for (StatusBarNotification s : nm.getActiveNotifications()) {
                if (s.getId() == id) return (s.getNotification().flags & FLAG_PROMOTED) != 0;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** One line for the log and the settings page. */
    static String status(Context c, int id) {
        if (!supported()) return "Android " + Build.VERSION.RELEASE + ": живые уведомления появились только в Android 16";
        Boolean a = allowed(c), s = shown(c, id);
        return "Android " + Build.VERSION.RELEASE + " · разрешены: " + yesNo(a)
                + " · сейчас в строке состояния: " + (s == null ? "VPN выключен" : yesNo(s));
    }

    private static String yesNo(Boolean b) {
        return b == null ? "неизвестно" : b ? "да" : "нет";
    }

    /** The system page where the user allows Live Updates for Dash; falls back to its notification settings. */
    static void openSettings(Context c) {
        String[] actions = {"android.settings.APP_NOTIFICATION_PROMOTION_SETTINGS",
                "android.settings.MANAGE_APP_PROMOTED_NOTIFICATIONS",
                "android.settings.APP_NOTIFICATION_SETTINGS"};
        for (String a : actions) {
            for (int withData = 1; withData >= 0; withData--) {
                try {
                    Intent i = new Intent(a)
                            .putExtra("android.provider.extra.APP_PACKAGE", c.getPackageName())
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    if (withData == 1) i.setData(Uri.fromParts("package", c.getPackageName(), null));
                    c.startActivity(i);
                    return;
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
