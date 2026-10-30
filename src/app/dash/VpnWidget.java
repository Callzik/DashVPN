package app.dash;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.widget.RemoteViews;

import java.util.Random;

/** Home screen widgets (1-row and 4×4, see VpnWidgetBig) in the look of the main screen: night sky, power button, timer, server and ping. */
public class VpnWidget extends AppWidgetProvider {
    static final String ACTION_TOGGLE = "app.dash.WIDGET_TOGGLE";
    private static final int GOOD = 0xFF6BD68A;

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        VpnControl.init(c);
        updateAll(c, m);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context c, AppWidgetManager m, int id, Bundle options) {
        updateAll(c, m); // resized: the sky is redrawn to the new size
    }

    /** The tap goes to WidgetToggleReceiver: not exported, so other apps cannot switch the VPN. */
    static void toggle(Context c) {
        VpnControl.init(c);
        if (VpnControl.isActive()) {
            VpnControl.stop(c);
        } else if (VpnControl.needsUi(c)) {
            c.startActivity(VpnControl.openAppIntent(c, true));
        } else {
            try {
                VpnControl.start(c);
            } catch (Exception e) {
                // background start refused by the system: let the app do it
                c.startActivity(VpnControl.openAppIntent(c, true));
            }
        }
        updateAll(c, AppWidgetManager.getInstance(c));
    }

    static void updateAll(Context c, AppWidgetManager m) {
        update(c, m, false);
        update(c, m, true);
    }

    private static void update(Context c, AppWidgetManager m, boolean big) {
        int[] ids = m.getAppWidgetIds(new ComponentName(c, big ? VpnWidgetBig.class : VpnWidget.class));
        if (ids == null) return;
        for (int id : ids) m.updateAppWidget(id, views(c, m, id, big));
    }

    private static RemoteViews views(Context c, AppWidgetManager m, int id, boolean big) {
        RemoteViews v = new RemoteViews(c.getPackageName(), big ? R.layout.widget_big : R.layout.widget);
        int state = AppState.vpn;
        boolean paused = state == AppState.OFF && AppState.pausedUntil > 0;
        boolean manual = !AppState.pinned.isEmpty();
        String server = state == AppState.ON && !AppState.serverName.isEmpty() ? AppState.serverName : manual ? AppState.pinned : "";
        String[] fl = server.isEmpty() ? new String[]{"", ""} : ServersActivity.splitFlag(server);
        String place = server.isEmpty() ? "Авто" : (fl[0].isEmpty() ? "" : fl[0] + "  ") + fl[1];

        String title;
        CharSequence status = place;
        int titleColor = Ui.FG, btnBg, iconColor;
        switch (state) {
            case AppState.ON:
                title = null;
                btnBg = R.drawable.widget_btn_on;
                iconColor = Ui.BG;
                break;
            case AppState.CONNECTING:
                title = "Подключение…";
                if (AppState.switching || server.isEmpty()) status = "Проверка серверов";
                btnBg = R.drawable.widget_btn_wait;
                iconColor = Ui.ACCENT;
                break;
            case AppState.WAITING:
                title = "Нет ответа";
                btnBg = R.drawable.widget_btn_warn;
                iconColor = Ui.WARN;
                break;
            default:
                title = paused ? "Пауза" : "Отключено";
                titleColor = Ui.MUTED;
                if (!paused && !AppState.error.isEmpty()) status = red(AppState.error);
                btnBg = R.drawable.widget_btn_off;
                iconColor = Ui.MUTED;
        }
        if (title == null) {
            long since = AppState.since > 0 ? AppState.since : System.currentTimeMillis();
            long base = SystemClock.elapsedRealtime() - Math.max(0, System.currentTimeMillis() - since);
            v.setChronometer(R.id.widget_timer, base, null, true);
            v.setViewVisibility(R.id.widget_timer, View.VISIBLE);
            v.setViewVisibility(R.id.widget_title, View.GONE);
        } else {
            v.setChronometer(R.id.widget_timer, SystemClock.elapsedRealtime(), null, false);
            v.setViewVisibility(R.id.widget_timer, View.GONE);
            v.setViewVisibility(R.id.widget_title, View.VISIBLE);
            v.setTextViewText(R.id.widget_title, title);
            v.setTextColor(R.id.widget_title, titleColor);
        }
        if (big) {
            // server card: flag (⚡ for automatic), name, how it was chosen or what is happening now
            v.setTextViewText(R.id.widget_flag, server.isEmpty() ? "⚡" : !fl[0].isEmpty() ? fl[0] : fl[1].trim().isEmpty() ? "?" : fl[1].trim().substring(0, 1).toUpperCase());
            v.setTextViewText(R.id.widget_status, server.isEmpty() ? "Авто" : fl[1]);
            CharSequence sub = state == AppState.CONNECTING ? "Проверка серверов"
                    : state == AppState.OFF && !paused && !AppState.error.isEmpty() ? status
                    : server.isEmpty() ? "" : manual ? "Вручную" : "Авто";
            v.setTextViewText(R.id.widget_sub, sub);
            v.setViewVisibility(R.id.widget_sub, sub.length() > 0 ? View.VISIBLE : View.GONE);
            v.setInt(R.id.widget_ring, "setBackgroundResource", state == AppState.OFF ? R.drawable.widget_ring_off
                    : state == AppState.WAITING ? R.drawable.widget_ring_warn : R.drawable.widget_ring_on);
        } else {
            v.setTextViewText(R.id.widget_status, status);
        }

        int ping = state == AppState.ON && !AppState.pingingCurrent ? AppState.ping : 0;
        if (ping != 0) {
            v.setTextViewText(R.id.widget_ping, ping > 0 ? String.valueOf(ping) : "—");
            v.setTextColor(R.id.widget_ping, ping < 0 || ping >= 400 ? Ui.BAD : ping >= 300 ? Ui.WARN : GOOD);
            v.setViewVisibility(R.id.widget_ping, View.VISIBLE);
        } else {
            v.setViewVisibility(R.id.widget_ping, View.GONE);
        }

        v.setInt(R.id.widget_btn, "setBackgroundResource", btnBg);
        v.setInt(R.id.widget_btn, "setColorFilter", iconColor);
        Bitmap sky = sky(c, m, id, paused ? AppState.OFF : state, big);
        if (sky != null) v.setImageViewBitmap(R.id.widget_sky, sky);

        PendingIntent pi;
        if (state == AppState.OFF && VpnControl.needsUi(c)) {
            // no permission / no subscription yet: the button opens the app, which connects
            pi = PendingIntent.getActivity(c, 2, VpnControl.openAppIntent(c, true),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        } else {
            Intent toggle = new Intent(c, WidgetToggleReceiver.class).setAction(ACTION_TOGGLE);
            pi = PendingIntent.getBroadcast(c, 0, toggle,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        }
        v.setOnClickPendingIntent(R.id.widget_btn, pi);
        PendingIntent open = PendingIntent.getActivity(c, 1, VpnControl.openAppIntent(c, false),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        v.setOnClickPendingIntent(R.id.widget_root, open);
        return v;
    }

    private static CharSequence red(String s) {
        SpannableString sp = new SpannableString(s);
        sp.setSpan(new ForegroundColorSpan(Ui.BAD), 0, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sp;
    }

    private static final String[] skyKey = new String[2];
    private static final Bitmap[] skyBmp = new Bitmap[2];

    /**
     * The sky at the widget's size, drawn at about 1.5 px per dp to keep the bitmap small for the launcher.
     * Off: dim stars. Connecting: streaks flying from the button. On: brighter stars and a nebula around the button.
     */
    private static Bitmap sky(Context c, AppWidgetManager m, int id, int state, boolean big) {
        Bundle o = m.getAppWidgetOptions(id);
        int wDp = o == null ? 0 : o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH);
        int hDp = o == null ? 0 : o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT);
        if (wDp <= 0) wDp = big ? 300 : 250;
        if (hDp <= 0) hDp = big ? 300 : 64;
        float k = Math.min(1.5f, (big ? 450f : 600f) / wDp);
        int w = Math.max(1, Math.round(wDp * k)), h = Math.max(1, Math.round(hDp * k));
        String key = w + "x" + h + ":" + state;
        int slot = big ? 1 : 0;
        synchronized (VpnWidget.class) {
            if (key.equals(skyKey[slot]) && skyBmp[slot] != null) return skyBmp[slot];
        }
        Bitmap b;
        try {
            b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        } catch (Throwable e) {
            return null;
        }
        Canvas cv = new Canvas(b);
        cv.drawColor(Ui.BG);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        // centre of the power button: 10dp padding + 22dp radius from the left edge; big one: middle, a bit up
        float bx = big ? w / 2f : 32 * k, by = big ? h * 0.42f : h / 2f;
        float sc = big ? 1.4f : 1f;
        if (state == AppState.ON || state == AppState.WAITING) {
            int[] cols = state == AppState.ON ? new int[]{0x8B7CFF, 0x5A3CC8, 0xC878FF} : new int[]{0xF2B544, 0x8A5A1E, 0xF2B544};
            float[][] blobs = {{0, 0, 120, 0.34f}, {90, 20, 110, 0.18f}, {200, -30, 130, 0.10f}};
            for (int i = 0; i < blobs.length; i++) {
                float x = bx + blobs[i][0] * k * (big ? 0.5f : 1f), y = by + blobs[i][1] * k * sc, r = blobs[i][2] * k * sc;
                p.setShader(new RadialGradient(x, y, r, (Math.round(blobs[i][3] * 255) << 24) | cols[i], cols[i] & 0x00FFFFFF, Shader.TileMode.CLAMP));
                cv.drawCircle(x, y, r, p);
            }
            p.setShader(null);
        }
        Random rnd = new Random(7);
        int n = Math.max(24, (int) (wDp * hDp / (big ? 700f : 260f)));
        // streaks run under the text in the 1-row widget: dimmer there
        float bright = state == AppState.ON ? 1.4f : state == AppState.CONNECTING && !big ? 0.6f : 1f;
        p.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 0; i < n; i++) {
            float x = rnd.nextFloat() * w, y = rnd.nextFloat() * h, s = rnd.nextFloat();
            float r = k * (0.4f + s * s * 0.9f);
            float a = Math.min(1f, (0.15f + rnd.nextFloat() * 0.45f) * bright);
            int col = rnd.nextFloat() < 0.2f ? 0xC9C0FF : 0xFFFFFF;
            p.setColor((Math.round(a * 255) << 24) | col);
            if (state == AppState.CONNECTING) {
                // jump: each star stretched into a line pointing away from the button
                float dx = x - bx, dy = y - by, d = (float) Math.hypot(dx, dy);
                if (d < 1) continue;
                float len = Math.min(d * 0.5f, k * sc * (6 + 22 * s));
                p.setStrokeWidth(r * 1.4f);
                cv.drawLine(x - dx / d * len, y - dy / d * len, x, y, p);
            } else {
                cv.drawCircle(x, y, r, p);
            }
        }
        synchronized (VpnWidget.class) {
            skyKey[slot] = key;
            skyBmp[slot] = b;
        }
        return b;
    }
}
