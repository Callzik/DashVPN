package app.dash;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;

/** After a reboot or an update of Dash: brings back the lock on the apps that must not go online without the VPN. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        String a = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) return;
        if (new Prefs(c).lockedApps().isEmpty() || AppState.vpn != AppState.OFF) return;
        try {
            if (VpnService.prepare(c) != null) return; // no VPN permission: nothing can be locked
            c.startForegroundService(new Intent(c, BoxVpnService.class).setAction(BoxVpnService.ACTION_LOCK));
        } catch (Exception e) {
            AppState.log("Замок после перезагрузки: " + e.getMessage());
        }
    }
}
