package app.dash;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Receives the package installer's answer for a Dash update. Not exported: only the system,
 * through the PendingIntent Dash itself created, can send to it, so the confirm intent inside
 * (EXTRA_INTENT) cannot be planted by another app.
 */
public class InstallStatusReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (Updater.ACTION_STATUS.equals(i.getAction())) Updater.onStatus(c, i);
    }
}
