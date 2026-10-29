package app.dash;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    static final String EXTRA_EDIT_HOME = "edit_home";
    private static final int REQ_VPN = 1;

    private Prefs prefs;
    private FrameLayout root;
    private View mainView, onboardingView;

    private TextView netChip, title, subtitle, banner, wlBadge, srvName, srvSub, srvBadge, errorText;
    private TextView speedVal, trafficVal, ipFlag, ipText, ipCountry;
    private TileGrid grid;
    private LinearLayout editBar;
    private TextView editAdd, editRemove;
    private String tilesSig;
    private PowerButton power;
    private StarField stars;
    private FrameLayout srvFlag;
    private LinearLayout srvPing;
    private String srvKey = "";
    private LinearLayout updCard;
    private TextView updText, updBtn;
    private EditText subInput;
    private TextView subError;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            render();
            handler.postDelayed(this, 1000);
        }
    };
    private final Runnable listener = new Runnable() {
        @Override public void run() { render(); }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        Window w = getWindow();
        w.setStatusBarColor(Ui.BG);
        w.setNavigationBarColor(Ui.BG);
        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        setContentView(root);
        stars = new StarField(this);
        root.addView(stars);
        mainView = buildMain();
        onboardingView = buildOnboarding();
        root.addView(mainView);
        root.addView(onboardingView);
        showScreen();
        askNotifications();
        showChangelog();
        VpnControl.init(this);
        handleIntent(getIntent());
    }

    /** "Что нового" after an update — skipped on the very first install. */
    private void showChangelog() {
        int code;
        boolean fresh;
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            code = pi.versionCode;
            fresh = pi.firstInstallTime == pi.lastUpdateTime;
        } catch (Exception e) {
            return;
        }
        int seen = prefs.lastSeenVersion();
        if (seen == 0) {
            if (fresh) { // first install: nothing to tell
                prefs.lastSeenVersion(code);
                return;
            }
            seen = code - 1; // updated from a build that did not remember versions yet
        }
        if (seen >= code) return;
        java.util.List<String> lines = Changelog.since(seen, code);
        prefs.lastSeenVersion(code);
        if (lines.isEmpty()) return;
        WhatsNew.show(this, versionName(), lines);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    /** Tile / widget asked to connect but needed the screen (permission or subscription). */
    private void handleIntent(Intent intent) {
        if (intent != null && intent.getBooleanExtra(EXTRA_EDIT_HOME, false)) { // Settings → Главный экран
            intent.removeExtra(EXTRA_EDIT_HOME);
            if (grid != null) grid.setEdit(true, null);
        }
        if (intent == null || !intent.getBooleanExtra(VpnControl.EXTRA_CONNECT, false)) return;
        intent.removeExtra(VpnControl.EXTRA_CONNECT);
        if (AppState.vpn == AppState.OFF && !prefs.subUrl().trim().isEmpty()) toggle();
    }

    @Override protected void onResume() {
        super.onResume();
        AppState.addListener(listener);
        handler.post(ticker);
        if (grid != null) rebuildTiles();
        showScreen();
        Updater.check(this, false);
    }

    @Override protected void onPause() {
        AppState.removeListener(listener);
        handler.removeCallbacks(ticker);
        super.onPause();
    }

    private void showScreen() {
        boolean hasSub = !prefs.subUrl().trim().isEmpty();
        mainView.setVisibility(hasSub ? View.VISIBLE : View.GONE);
        onboardingView.setVisibility(hasSub ? View.GONE : View.VISIBLE);
        render();
    }

    /* ---------- main screen ---------- */

    private View buildMain() {
        Context c = this;
        ScrollView sv = new ScrollView(c);
        sv.setFillViewport(true);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(c, 22), Ui.dp(c, 22), Ui.dp(c, 22), Ui.dp(c, 22));
        sv.addView(col, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // header: comet logo + wordmark + settings
        LinearLayout top = Ui.row(c);
        ImageView logo = new ImageView(c);
        logo.setImageResource(R.drawable.ic_logo);
        top.addView(logo, new LinearLayout.LayoutParams(Ui.dp(c, 30), Ui.dp(c, 30)));
        TextView word = Ui.text(c, "dash", 24, Ui.FG, true);
        word.setLetterSpacing(-0.02f);
        word.setPadding(Ui.dp(c, 10), 0, Ui.dp(c, 8), 0);
        top.addView(word);
        netChip = Ui.text(c, "", 12, Ui.MUTED, false); // version lives in Settings only
        top.addView(netChip, Ui.weight());
        FrameLayout settings = new FrameLayout(c);
        settings.setBackground(Ui.round(c, Ui.SURFACE, 14, Ui.LINE));
        settings.addView(new SlidersIcon(c), new FrameLayout.LayoutParams(Ui.dp(c, 20), Ui.dp(c, 20), Gravity.CENTER));
        settings.setContentDescription("Настройки");
        settings.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startActivity(new Intent(MainActivity.this, SettingsActivity.class)); }
        });
        top.addView(settings, new LinearLayout.LayoutParams(Ui.dp(c, 44), Ui.dp(c, 44)));
        col.addView(top);

        // hero: orbit button + status + timer
        LinearLayout hero = new LinearLayout(c);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(Gravity.CENTER);
        power = new PowerButton(c);
        power.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggle(); }
        });
        hero.addView(power, new LinearLayout.LayoutParams(Ui.dp(c, 270), Ui.dp(c, 270)));
        title = Ui.text(c, "Отключено", 32, Ui.FG, true);
        title.setLetterSpacing(-0.02f);
        title.setGravity(Gravity.CENTER);
        hero.addView(title, Ui.lp(c, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 14));
        subtitle = Ui.text(c, "", 15, Ui.MUTED, false);
        subtitle.setGravity(Gravity.CENTER);
        hero.addView(subtitle, Ui.lp(c, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 6));
        errorText = Ui.text(c, "", 14, Ui.BAD, false);
        errorText.setGravity(Gravity.CENTER);
        hero.addView(errorText, Ui.lp(c, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 8));
        LinearLayout.LayoutParams heroLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        hero.setMinimumHeight(Ui.dp(c, 420));
        hero.setPadding(0, Ui.dp(c, 8), 0, Ui.dp(c, 12));
        col.addView(hero, heroLp);

        banner = Ui.text(c, "", 13, Ui.WARN_FG, false);
        banner.setPadding(Ui.dp(c, 14), Ui.dp(c, 10), Ui.dp(c, 14), Ui.dp(c, 10));
        banner.setVisibility(View.GONE);
        col.addView(banner, Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0));

        // update available
        updCard = Ui.row(c);
        updCard.setBackground(Ui.round(c, Ui.ACCENT_TINT, 16, 0));
        updCard.setPadding(Ui.dp(c, 16), Ui.dp(c, 10), Ui.dp(c, 10), Ui.dp(c, 10));
        updText = Ui.text(c, "", 14, 0xFFD9D3FF, false);
        updCard.addView(updText, Ui.weight());
        updBtn = Ui.text(c, "Обновить", 14, Ui.ACCENT_INK, true);
        updBtn.setBackground(Ui.round(c, Ui.ACCENT, 12, 0));
        updBtn.setPadding(Ui.dp(c, 14), Ui.dp(c, 8), Ui.dp(c, 14), Ui.dp(c, 8));
        updBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { Updater.start(MainActivity.this); }
        });
        updCard.addView(updBtn);
        updCard.setVisibility(View.GONE);
        col.addView(updCard, Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 10));

        // tiles: built from the user's layout (Settings → Главный экран)
        // edit bar: shown while tiles are being arranged
        editBar = Ui.row(c);
        editBar.setVisibility(View.GONE);
        editAdd = Ui.button(c, "Добавить", false);
        editAdd.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { addTileMenu(v); }
        });
        editBar.addView(editAdd, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        editRemove = Ui.button(c, "Убрать", false);
        editRemove.setTextColor(Ui.BAD);
        editRemove.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { grid.removeSelected(); }
        });
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        rp.leftMargin = Ui.dp(c, 10);
        editBar.addView(editRemove, rp);
        TextView done = Ui.button(c, "Готово", true);
        done.setTextSize(15);
        done.setMinHeight(Ui.dp(c, 48));
        done.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { grid.setEdit(false, null); }
        });
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        dp.leftMargin = Ui.dp(c, 10);
        editBar.addView(done, dp);
        col.addView(editBar, Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0));

        // tiles on a grid: long press to move, resize, add or remove them
        grid = new TileGrid(c, new TileGrid.Host() {
            @Override public View make(Tiles.Item it) {
                View v = makeTile(it);
                handler.post(listener); // fill the new view right away, not on the next tick
                final String id = it.id;
                v.setOnLongClickListener(new View.OnLongClickListener() {
                    @Override public boolean onLongClick(View v) {
                        v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                        grid.setEdit(true, id);
                        return true;
                    }
                });
                return v;
            }
            @Override public void changed(java.util.List<Tiles.Item> items) {
                Tiles.save(prefs, items);
                tilesSig = Tiles.signature(prefs);
                render();
            }
            @Override public void editState(boolean edit, String selected) {
                if (edit && editBar.getVisibility() != View.VISIBLE) { // slide in
                    editBar.setVisibility(View.VISIBLE);
                    editBar.setAlpha(0f);
                    editBar.setTranslationY(Ui.dp(MainActivity.this, 10));
                    editBar.animate().alpha(1f).translationY(0).setDuration(220)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator(1.8f)).start();
                } else if (!edit) {
                    editBar.setVisibility(View.GONE);
                }
                editRemove.setEnabled(selected != null);
                editRemove.setAlpha(selected != null ? 1f : 0.4f);
                boolean hidden = false;
                for (Tiles.Item t : grid.items()) if (!t.on) hidden = true;
                editAdd.setEnabled(hidden);
                editAdd.setAlpha(hidden ? 1f : 0.4f);
            }
        });
        LinearLayout.LayoutParams gp = Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 10);
        int pad = TileGrid.pad(c); // the grid's own padding holds the edit frame: pull it out so tiles line up
        gp.leftMargin = gp.rightMargin = -pad;
        gp.topMargin -= pad;
        gp.bottomMargin = -pad;
        col.addView(grid, gp);
        rebuildTiles();

        return sv;
    }

    /** Re-creates the tiles when the layout changed elsewhere; references of hidden tiles become null. */
    private void rebuildTiles() {
        if (grid.editing()) return;
        String sig = Tiles.signature(prefs);
        if (sig.equals(tilesSig)) return;
        tilesSig = sig;
        wlBadge = speedVal = trafficVal = ipFlag = ipText = ipCountry = srvName = srvSub = null;
        srvFlag = null;
        srvPing = null;
        srvKey = "";
        grid.setItems(Tiles.load(prefs));
    }

    private void addTileMenu(View anchor) {
        android.widget.PopupMenu m = new android.widget.PopupMenu(this, anchor);
        final java.util.List<String> ids = new java.util.ArrayList<>();
        for (Tiles.Item t : grid.items()) {
            if (t.on) continue;
            ids.add(t.id);
            m.getMenu().add(0, ids.size() - 1, 0, Tiles.title(t.id));
        }
        m.setOnMenuItemClickListener(new android.widget.PopupMenu.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(android.view.MenuItem item) {
                grid.add(ids.get(item.getItemId()));
                return true;
            }
        });
        m.show();
    }

    @Override public void onBackPressed() {
        if (grid != null && grid.editing()) { grid.setEdit(false, null); return; }
        super.onBackPressed();
    }

    /** A tile laid out for its size: 1–4 columns wide, 1–2 rows high. */
    private View makeTile(Tiles.Item it) {
        Context c = this;
        int w = it.w, h = it.h;
        switch (it.id) {
            case Tiles.WL: {
                LinearLayout t = statTile(w == 1 ? "Списки" : "Белые списки", h);
                wlBadge = statValue(t, false, h);
                return t;
            }
            case Tiles.SPEED: {
                LinearLayout t = statTile(w == 1 ? "Мбит/с" : "Скорость, Мбит/с", h);
                speedVal = statValue(t, true, h);
                return t;
            }
            case Tiles.TRAFFIC: {
                LinearLayout t = statTile("Трафик", h);
                trafficVal = statValue(t, true, h);
                return t;
            }
            case Tiles.IP: {
                if (w <= 2) { // compact: flag over the address
                    LinearLayout t = new LinearLayout(c);
                    t.setOrientation(LinearLayout.VERTICAL);
                    t.setGravity(h == 1 && w == 1 ? Gravity.CENTER : Gravity.CENTER_VERTICAL);
                    t.setBackground(Ui.round(c, Ui.SURFACE, 16, Ui.LINE));
                    t.setPadding(Ui.dp(c, 12), Ui.dp(c, 8), Ui.dp(c, 12), Ui.dp(c, 8));
                    ipFlag = Ui.text(c, "🌐", h == 2 ? 30 : w == 1 ? 26 : 20, Ui.FG, false);
                    t.addView(ipFlag);
                    ipText = Ui.text(c, "—", 15, Ui.FG, true);
                    ipText.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
                    ipText.setSingleLine(true);
                    ipText.setAutoSizeTextTypeUniformWithConfiguration(8, h == 2 ? 18 : 15, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
                    ipText.setVisibility(w == 1 && h == 1 ? View.GONE : View.VISIBLE);
                    t.addView(ipText, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 22)));
                    ipCountry = Ui.text(c, "", 12, Ui.MUTED, false);
                    ipCountry.setSingleLine(true);
                    ipCountry.setVisibility(h == 2 ? View.VISIBLE : View.GONE);
                    t.addView(ipCountry);
                    t.setOnClickListener(copyIp());
                    return t;
                }
                LinearLayout t = Ui.row(c);
                t.setBackground(Ui.round(c, Ui.SURFACE, 16, Ui.LINE));
                t.setPadding(Ui.dp(c, 14), Ui.dp(c, 12), Ui.dp(c, 14), Ui.dp(c, 12));
                ipFlag = Ui.text(c, "🌐", 26, Ui.FG, false);
                t.addView(ipFlag);
                LinearLayout txt = new LinearLayout(c);
                txt.setOrientation(LinearLayout.VERTICAL);
                txt.setPadding(Ui.dp(c, 12), 0, 0, 0);
                txt.addView(Ui.text(c, "Внешний IP", 12, Ui.MUTED, false));
                ipText = Ui.text(c, "—", 18, Ui.FG, true);
                ipText.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
                ipText.setSingleLine(true);
                txt.addView(ipText, Ui.lp(c, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 2));
                t.addView(txt, Ui.weight());
                ipCountry = Ui.text(c, "", 13, Ui.MUTED, false);
                ipCountry.setGravity(Gravity.END);
                ipCountry.setMaxWidth(Ui.dp(c, 130));
                t.addView(ipCountry);
                t.setOnClickListener(copyIp());
                return t;
            }
            default: { // server card: flag; name from 2 columns; ping and "Авто" from 3
                srvKey = "";
                LinearLayout srv = new LinearLayout(c);
                srv.setOrientation(w == 1 || (w == 2 && h == 2) ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
                srv.setGravity(w == 1 ? Gravity.CENTER : Gravity.CENTER_VERTICAL);
                srv.setBackground(Ui.round(c, 0xD113121A, 18, Ui.LINE));
                srv.setPadding(Ui.dp(c, w == 1 ? 6 : 13), Ui.dp(c, 6), Ui.dp(c, w == 1 ? 6 : 16), Ui.dp(c, 6));
                srvFlag = new FrameLayout(c);
                srvFlag.setBackground(Ui.round(c, Ui.SURFACE2, 12, 0));
                srv.addView(srvFlag, new LinearLayout.LayoutParams(Ui.dp(c, 40), Ui.dp(c, 40)));
                LinearLayout srvText = new LinearLayout(c);
                srvText.setOrientation(LinearLayout.VERTICAL);
                srvText.setPadding(srv.getOrientation() == LinearLayout.HORIZONTAL ? Ui.dp(c, 12) : 0, srv.getOrientation() == LinearLayout.VERTICAL ? Ui.dp(c, 8) : 0, Ui.dp(c, 8), 0);
                srvText.setVisibility(w == 1 ? View.GONE : View.VISIBLE);
                srvName = Ui.text(c, "Авто", 16, Ui.FG, true);
                srvName.setSingleLine(true);
                srvName.setEllipsize(android.text.TextUtils.TruncateAt.END);
                srvSub = Ui.text(c, "", 13, Ui.MUTED, false);
                srvSub.setSingleLine(true);
                srvSub.setEllipsize(android.text.TextUtils.TruncateAt.END);
                srvText.addView(srvName);
                srvText.addView(srvSub, Ui.lp(c, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 2));
                srvSub.setVisibility(w >= 3 ? View.VISIBLE : View.GONE);
                srv.addView(srvText, srv.getOrientation() == LinearLayout.HORIZONTAL ? Ui.weight()
                        : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                srvPing = Ui.row(c);
                srvPing.setVisibility(w >= 3 || h == 2 ? View.VISIBLE : View.GONE);
                if (srv.getOrientation() == LinearLayout.VERTICAL) {
                    LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    pp.topMargin = Ui.dp(c, 6);
                    srv.addView(srvPing, pp);
                } else {
                    srv.addView(srvPing);
                }
                srvPing.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        BoxVpnService s = BoxVpnService.instance;
                        if (s != null && AppState.vpn == AppState.ON) s.pingCurrent();
                    }
                });
                srv.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { startActivity(new Intent(MainActivity.this, ServersActivity.class)); }
                });
                return srv;
            }
        }
    }

    private View.OnClickListener copyIp() {
        return new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (Stats.ip.isEmpty()) { Stats.refreshIp(); return; }
                android.content.ClipboardManager cb = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cb.setPrimaryClip(ClipData.newPlainText("ip", Stats.ip));
                Toast.makeText(MainActivity.this, "IP скопирован", Toast.LENGTH_SHORT).show();
            }
        };
    }

    /** Label on top; a two-row tile puts its value at the bottom, larger. */
    private LinearLayout statTile(String label, int h) {
        Context c = this;
        LinearLayout t = new LinearLayout(c);
        t.setOrientation(LinearLayout.VERTICAL);
        if (h == 1) t.setGravity(Gravity.CENTER_VERTICAL);
        t.setBackground(Ui.round(c, Ui.SURFACE, 16, Ui.LINE));
        t.setPadding(Ui.dp(c, 14), Ui.dp(c, 12), Ui.dp(c, 10), Ui.dp(c, 12));
        TextView l = Ui.text(c, label, 12, Ui.MUTED, false);
        l.setSingleLine(true);
        l.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.addView(l);
        return t;
    }

    private TextView statValue(LinearLayout t, boolean mono, int h) {
        Context c = this;
        int max = h == 2 ? (mono ? 30 : 28) : (mono ? 20 : 18);
        TextView v = Ui.text(c, "—", max, Ui.FG, true);
        if (mono) v.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        v.setSingleLine(true);
        v.setGravity(Gravity.BOTTOM);
        v.setAutoSizeTextTypeUniformWithConfiguration(9, max, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        if (h == 2) { // label at the top, value pinned to the bottom
            View spacer = new View(c);
            t.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));
        }
        t.addView(v, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, h == 2 ? 42 : 28)));
        return v;
    }

    /** Settings glyph: two slider lines. */
    static class SlidersIcon extends View {
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        SlidersIcon(Context c) {
            super(c);
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            p.setStrokeWidth(Ui.dp(c, 2));
            p.setColor(Ui.FG);
        }
        @Override protected void onDraw(android.graphics.Canvas cv) {
            float u = getWidth() / 24f;
            cv.drawLine(4 * u, 7 * u, 14 * u, 7 * u, p);
            cv.drawLine(18 * u, 7 * u, 20 * u, 7 * u, p);
            cv.drawLine(4 * u, 17 * u, 8 * u, 17 * u, p);
            cv.drawLine(12 * u, 17 * u, 20 * u, 17 * u, p);
            cv.drawCircle(16 * u, 7 * u, 2 * u, p);
            cv.drawCircle(10 * u, 17 * u, 2 * u, p);
        }
    }

    private void render() {
        if (title == null) return;
        int s = AppState.vpn;
        power.setState(s);

        stars.setMode(s);
        int[] pl = new int[2], sl = new int[2];
        power.getLocationInWindow(pl);
        stars.getLocationInWindow(sl);
        if (power.getWidth() > 0) stars.setCenter(pl[0] - sl[0] + power.getWidth() / 2f, pl[1] - sl[1] + power.getHeight() / 2f);

        title.setTextColor(s == AppState.WAITING ? Ui.WARN : Ui.FG);
        title.setTextSize(s == AppState.ON ? 36 : 32);
        title.setFontFeatureSettings(s == AppState.ON ? "tnum" : null);
        subtitle.setTypeface(s == AppState.ON ? Typeface.MONOSPACE : Typeface.DEFAULT);
        switch (s) {
            case AppState.CONNECTING:
                title.setText("Подключение…");
                subtitle.setText(AppState.phase);
                break;
            case AppState.ON:
                // the glowing planet already says "connected": the timer is the title, speed below it
                title.setText(duration(System.currentTimeMillis() - AppState.since));
                subtitle.setText(Stats.hasTraffic ? "↓ " + rate(Stats.downRate) + "    ↑ " + rate(Stats.upRate) : "");
                break;
            case AppState.WAITING:
                title.setText(AppState.wl == AppState.WL_NONET ? "Ожидание сети" : "Нет ответа");
                subtitle.setText(AppState.phase);
                break;
            default:
                String net = currentNetGuess();
                title.setText(AppState.pausedUntil > 0 ? "Пауза" : "Отключено");
                if (AppState.pausedUntil > 0) {
                    subtitle.setText("Включится сам в " + new java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT)
                            .format(new java.util.Date(AppState.pausedUntil)));
                } else {
                    subtitle.setText("none".equals(net) ? "Нет сети" : "");
                }
        }
        errorText.setText(s == AppState.OFF ? AppState.error : "");
        errorText.setVisibility(s == AppState.OFF && !AppState.error.isEmpty() ? View.VISIBLE : View.GONE);

        // banner (7 s)
        boolean showBanner = !AppState.banner.isEmpty() && System.currentTimeMillis() - AppState.bannerAt < 7000 && s != AppState.OFF;
        banner.setVisibility(showBanner ? View.VISIBLE : View.GONE);
        if (showBanner) {
            banner.setText(AppState.banner);
            boolean ok = AppState.bannerTone == 1;
            banner.setBackground(Ui.round(this, ok ? Ui.ACCENT_TINT : Ui.WARN_TINT, 14, 0));
            banner.setTextColor(ok ? 0xFFD9D3FF : Ui.WARN_FG);
        }

        // update card
        boolean upd = !Updater.newVersion.isEmpty();
        updCard.setVisibility(upd ? View.VISIBLE : View.GONE);
        if (upd) {
            int pr = Updater.progress;
            updText.setText(pr == 101 ? "Установка " + Updater.newVersion + "…"
                    : pr >= 0 ? "Загрузка " + Updater.newVersion + " · " + pr + "%"
                    : !Updater.error.isEmpty() ? Updater.error : "Доступна версия " + Updater.newVersion);
            updBtn.setVisibility(pr >= 0 ? View.GONE : View.VISIBLE);
            updBtn.setText(Updater.error.isEmpty() ? "Обновить" : "Ещё раз");
        }

        boolean on = s == AppState.ON;
        Stats.tick();
        renderStats(on);

        // tile: white lists
        if (wlBadge != null) {
        int wlColor = Ui.FG;
        if (AppState.wlChecking) wlBadge.setText("…");
        else if (AppState.wl == AppState.WL_ON) { wlBadge.setText("вкл"); wlColor = Ui.WARN; }
        else if (AppState.wl == AppState.WL_OFF && AppState.regularBlocked && s != AppState.OFF) { wlBadge.setText("блок"); wlColor = Ui.WARN; }
        else if (AppState.wl == AppState.WL_OFF) wlBadge.setText("выкл");
        else if (AppState.wl == AppState.WL_NONET) { wlBadge.setText("нет сети"); wlColor = Ui.BAD; }
        else wlBadge.setText("—");
        wlBadge.setTextColor(wlColor);
        }

        // server card: the server in use (or the pinned one), else "Авто"
        if (srvName == null) return;
        boolean manual = !AppState.pinned.isEmpty();
        String name = on && !AppState.serverName.isEmpty() ? AppState.serverName : manual ? AppState.pinned : "";
        String[] fl = name.isEmpty() ? new String[]{"", ""} : ServersActivity.splitFlag(name);
        srvName.setText(name.isEmpty() ? "Авто" : fl[1]);
        srvSub.setText(on && AppState.switching ? "Проверка серверов…" : name.isEmpty() ? "" : manual ? "Вручную" : "Авто");
        srvSub.setVisibility(srvSub.getText().length() > 0 ? View.VISIBLE : View.GONE);
        int ping = on && !AppState.pingingCurrent ? AppState.ping : 0;
        String key = name + "|" + ping + "|" + (on && AppState.pingingCurrent);
        if (!key.equals(srvKey)) { // rebuild the flag and ping only when they change
            srvKey = key;
            Context c = this;
            srvFlag.removeAllViews();
            if (name.isEmpty()) {
                srvFlag.addView(new IconView(c, IconView.AUTO, Ui.ACCENT), new FrameLayout.LayoutParams(Ui.dp(c, 22), Ui.dp(c, 22), Gravity.CENTER));
            } else {
                boolean hasFlag = !fl[0].isEmpty();
                String letter = fl[1].trim().isEmpty() ? "?" : fl[1].trim().substring(0, 1).toUpperCase();
                TextView f = Ui.text(c, hasFlag ? fl[0] : letter, hasFlag ? 21 : 16, Ui.MUTED, !hasFlag);
                f.setGravity(Gravity.CENTER);
                srvFlag.addView(f, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            srvPing.removeAllViews();
            if (on && AppState.pingingCurrent) srvPing.addView(Ui.text(c, "…", 14, Ui.MUTED, true));
            else if (ping != 0) srvPing.addView(ServersActivity.pingView(c, ping));
        }
    }

    /** Bytes/s for the line under the timer: "310 КБ/с", "4.2 МБ/с". */
    private static String rate(long b) {
        if (b < 1024) return b + " Б/с";
        if (b < 1024 * 1024) return Math.round(b / 1024.0) + " КБ/с";
        double m = b / (1024.0 * 1024);
        return String.format(java.util.Locale.ROOT, m < 10 ? "%.1f МБ/с" : "%.0f МБ/с", m);
    }

    private void renderStats(boolean on) {
        if (speedVal != null) {
            speedVal.setText(on && Stats.hasTraffic ? "↓" + Stats.mbit(Stats.downRate) + " ↑" + Stats.mbit(Stats.upRate) : "—");
        }
        if (trafficVal != null) {
            trafficVal.setText(on && Stats.hasTraffic ? "↓" + Stats.bytes(Stats.down) + " ↑" + Stats.bytes(Stats.up) : "—");
        }
        if (ipText != null) {
            if (!on) {
                ipFlag.setText("🌐");
                ipText.setText("—");
                ipCountry.setText("");
            } else if (!Stats.ip.isEmpty()) {
                ipFlag.setText(Stats.flag(Stats.cc));
                ipText.setText(Stats.ip);
                ipCountry.setText(Stats.country);
            } else {
                ipFlag.setText("🌐");
                ipText.setText(Stats.ipBusy ? "…" : "—");
                ipCountry.setText(Stats.ipFailed && !Stats.ipBusy ? "не удалось, нажмите" : "");
            }
        }
    }

    private String currentNetGuess() {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            android.net.Network n = cm.getActiveNetwork();
            if (n == null) return "none";
            android.net.NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            if (nc == null) return "none";
            if (nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) return "wifi";
            if (nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)) return "cell";
            return "other";
        } catch (Exception e) {
            return "other";
        }
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private static String duration(long ms) {
        long t = Math.max(0, ms / 1000);
        return String.format("%02d:%02d:%02d", t / 3600, (t / 60) % 60, t % 60);
    }

    private void toggle() {
        if (AppState.vpn == AppState.OFF) {
            AppState.error = "";
            Intent i = VpnService.prepare(this);
            if (i != null) startActivityForResult(i, REQ_VPN);
            else startVpn();
        } else {
            startService(new Intent(this, BoxVpnService.class).setAction(BoxVpnService.ACTION_STOP));
        }
    }

    private void startVpn() {
        Intent i = new Intent(this, BoxVpnService.class).setAction(BoxVpnService.ACTION_START);
        startForegroundService(i);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK) startVpn();
            else Toast.makeText(this, "Без разрешения VPN не включится", Toast.LENGTH_LONG).show();
        }
    }

    private void askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && !prefs.askedNotifications()
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            prefs.askedNotifications(true);
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
        }
    }

    /* ---------- onboarding ---------- */

    private View buildOnboarding() {
        Context c = this;
        ScrollView sv = new ScrollView(c);
        sv.setFillViewport(true);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(c, 20), Ui.dp(c, 56), Ui.dp(c, 20), Ui.dp(c, 24));
        sv.addView(col, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        col.addView(Ui.text(c, "Добавьте подписку", 28, Ui.FG, true));
        TextView lead = Ui.text(c, "Вставьте ссылку от VPN-провайдера. Подойдут ссылки на подписку и ключи vless, vmess, trojan, ss, hy2.", 15, Ui.MUTED, false);
        lead.setLineSpacing(0, 1.2f);
        col.addView(lead, Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 12));

        col.addView(Ui.text(c, "Ссылка", 14, Ui.FG, true), Ui.lp(c, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 28));
        subInput = new EditText(c);
        subInput.setHint("https://… или vless://…");
        subInput.setHintTextColor(0xFF8E8AA3);
        subInput.setTextColor(Ui.FG);
        subInput.setTypeface(Typeface.MONOSPACE);
        subInput.setTextSize(14);
        subInput.setSingleLine(false);
        subInput.setMaxLines(4);
        subInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        subInput.setBackground(Ui.round(c, Ui.SURFACE, 14, Ui.LINE2));
        subInput.setPadding(Ui.dp(c, 16), Ui.dp(c, 14), Ui.dp(c, 16), Ui.dp(c, 14));
        col.addView(subInput, Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 10));
        subError = Ui.text(c, "", 13, Ui.BAD, false);
        subError.setVisibility(View.GONE);
        col.addView(subError, Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 8));

        TextView paste = Ui.button(c, "Вставить из буфера", false);
        paste.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                ClipboardManager cb = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                ClipData d = cb.getPrimaryClip();
                if (d != null && d.getItemCount() > 0 && d.getItemAt(0).coerceToText(MainActivity.this) != null) {
                    subInput.setText(d.getItemAt(0).coerceToText(MainActivity.this).toString().trim());
                } else {
                    Toast.makeText(MainActivity.this, "Буфер обмена пуст", Toast.LENGTH_SHORT).show();
                }
            }
        });
        col.addView(paste, Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 12));

        View spacer = new View(c);
        col.addView(spacer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout setup = Ui.card(c);
        setup.setPadding(Ui.dp(c, 16), Ui.dp(c, 16), Ui.dp(c, 16), Ui.dp(c, 16));
        setup.addView(Ui.text(c, "Настроится само", 14, Ui.MUTED, true));
        String[] items = {"Российские сайты — без VPN", "Самый быстрый сервер — автоматически", "Белые списки — переход на LTE"};
        for (String it : items) {
            TextView t = Ui.text(c, "✓  " + it, 15, Ui.FG, false);
            setup.addView(t, Ui.lp(c, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 10));
        }
        col.addView(setup, Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 24));

        TextView go = Ui.button(c, "Продолжить", true);
        go.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { saveSubscription(); }
        });
        col.addView(go, Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 16));
        return sv;
    }

    static boolean looksLikeLink(String v) {
        return v.matches("(?is)^(https?|vless|vmess|trojan|ss|hy2|hysteria2)://\\S+.*");
    }

    private void saveSubscription() {
        String v = subInput.getText().toString().trim();
        if (!looksLikeLink(v)) {
            subError.setText(v.isEmpty() ? "Вставьте ссылку на подписку"
                    : "Это не похоже на ссылку. Нужна ссылка на подписку (https://…) или ключ vless://, vmess://, trojan://, ss://, hy2://");
            subError.setVisibility(View.VISIBLE);
            return;
        }
        subError.setVisibility(View.GONE);
        prefs.addSub(v);
        showScreen();
    }
}
