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

/** Home screen widgets (1-row, 4×4 VpnWidgetBig, 2×2 VpnWidgetSquare) in the look of the main screen: night sky, power button, timer, server and ping. */
public class VpnWidget extends AppWidgetProvider {
    static final String ACTION_TOGGLE = "app.dash.WIDGET_TOGGLE";
    private static final int GOOD = 0xFF6BD68A;
    private static final int ROW = 0, BIG = 1, SQUARE = 2;

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
        update(c, m, ROW);
        update(c, m, BIG);
        update(c, m, SQUARE);
    }

    private static void update(Context c, AppWidgetManager m, int kind) {
        int[] ids = m.getAppWidgetIds(new ComponentName(c, kind == BIG ? VpnWidgetBig.class : kind == SQUARE ? VpnWidgetSquare.class : VpnWidget.class));
        if (ids == null) return;
        for (int id : ids) m.updateAppWidget(id, views(c, m, id, kind));
    }

    private static RemoteViews views(Context c, AppWidgetManager m, int id, int kind) {
        boolean big = kind == BIG;
        RemoteViews v = new RemoteViews(c.getPackageName(), big ? R.layout.widget_big : kind == SQUARE ? R.layout.widget_square : R.layout.widget);
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
        Bitmap sky = sky(c, m, id, state == AppState.ON ? AppState.ON : AppState.OFF, kind);
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

    private static final String[] skyKey = new String[3];
    private static final Bitmap[] skyBmp = new Bitmap[3];

    /**
     * The sky at the widget's size, drawn at about 1.5 px per dp to keep the bitmap small for the launcher.
     * Connected: stars and a violet glow around the button; otherwise dim stars only.
     */
    private static Bitmap sky(Context c, AppWidgetManager m, int id, int state, int kind) {
        Bundle o = m.getAppWidgetOptions(id);
        int wDp = o == null ? 0 : o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH);
        int hDp = o == null ? 0 : o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT);
        boolean big = kind != ROW;
        if (wDp <= 0) wDp = kind == BIG ? 300 : kind == SQUARE ? 150 : 250;
        if (hDp <= 0) hDp = kind == BIG ? 300 : kind == SQUARE ? 150 : 64;
        float k = Math.min(1.5f, (big ? 450f : 600f) / wDp);
        int w = Math.max(1, Math.round(wDp * k)), h = Math.max(1, Math.round(hDp * k));
        String key = w + "x" + h + ":" + state;
        int slot = kind;
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
        // centre of the power button: 10dp padding + 22dp radius from the left edge; square ones: middle, a bit up
        float bx = big ? w / 2f : 32 * k, by = big ? h * (kind == BIG ? 0.42f : 0.38f) : h / 2f;
        boolean on = state == AppState.ON;
        Random rnd = new Random(7);
        int n = Math.max(24, (int) (wDp * hDp / (big ? 700f : 260f)));
        for (int i = 0; i < n; i++) {
            float x = rnd.nextFloat() * w, y = rnd.nextFloat() * h, s = rnd.nextFloat();
            float r = k * (0.4f + s * s * 0.9f);
            // off: dim stars; on: a bit brighter
            float a = Math.min(1f, (0.15f + rnd.nextFloat() * 0.45f) * (on ? 1.15f : 0.55f));
            int col = rnd.nextFloat() < 0.2f ? 0xC9C0FF : 0xFFFFFF;
            p.setColor((Math.round(a * 255) << 24) | col);
            cv.drawCircle(x, y, r, p);
        }
        if (on) { // violet glow around the button
            float rad = (kind == BIG ? 110 : kind == SQUARE ? 70 : 60) * k;
            float[][] glow = {{rad, 0.55f}, {rad * 1.8f, 0.18f}};
            int[] cols = {0x8B7CFF, 0x6E50E6};
            for (int i = 0; i < glow.length; i++) {
                int c0 = (Math.round(glow[i][1] * 255) << 24) | cols[i], c1 = (Math.round(glow[i][1] * 0.4f * 255) << 24) | cols[i];
                p.setShader(new RadialGradient(bx, by, glow[i][0], new int[]{c0, c1, cols[i]}, new float[]{0, 0.45f, 1}, Shader.TileMode.CLAMP));
                cv.drawCircle(bx, by, glow[i][0], p);
            }
            p.setShader(null);
        }
        synchronized (VpnWidget.class) {
            skyKey[slot] = key;
            skyBmp[slot] = b;
        }
        return b;
    }
}
