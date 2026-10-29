package app.dash;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import java.util.List;

/** One notification each at 3, 2 and 1 days before a subscription ends. */
final class SubReminder {
    private static final String CHANNEL = "alert"; // shared with the other warnings
    private static final int FIRST_ID = 100;

    private SubReminder() {}

    static void check(Context c, Prefs prefs) {
        long now = System.currentTimeMillis();
        List<String> urls = prefs.subUrls();
        for (int i = 0; i < urls.size(); i++) {
            String url = urls.get(i);
            if (!Subs.isHttp(url)) continue;
            SubInfo si = SubInfo.parse(prefs.subInfo(url));
            if (si == null) continue;
            int d = si.remindDay(now);
            if (d == 0) continue;
            String key = si.expire + ":" + d; // a renewed subscription gets a new end date, so it reminds again
            if (key.equals(prefs.subReminded(url))) continue;
            prefs.subReminded(url, key);
            notify(c, FIRST_ID + i, Subs.label(prefs, url) + " — осталось " + SubInfo.days(d), si.text(now));
        }
    }

    private static void notify(Context c, int id, String title, String text) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Предупреждения", NotificationManager.IMPORTANCE_HIGH));
        }
        Intent open = new Intent(c, SettingsActivity.class);
        nm.notify(id, new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(PendingIntent.getActivity(c, id, open, PendingIntent.FLAG_IMMUTABLE))
                .setAutoCancel(true)
                .build());
    }
}
