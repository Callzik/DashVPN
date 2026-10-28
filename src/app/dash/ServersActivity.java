package app.dash;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * All servers with their ping: "Авто" on its own card, then main, LTE and turned-off servers.
 * A check marks the chosen one, a dot the one auto uses now. Tap to use, long press to turn off.
 */
public class ServersActivity extends Activity {
    private static final int GOOD = 0xFF6BD68A;

    private Prefs prefs;
    private final List<Server> items = new ArrayList<>();
    private View test;
    private LinearLayout autoBox, body;
    private View searchBox;
    private String query = "";
    private final Runnable refresh = new Runnable() {
        @Override public void run() { update(); }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        off = prefs.offServers();
        offAtStart = new java.util.HashSet<>(off);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        Context c = this;

        ScrollView scroll = new ScrollView(c);
        scroll.setBackgroundColor(Ui.BG);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(c, 16), Ui.dp(c, 12), Ui.dp(c, 16), Ui.dp(c, 32));
        scroll.addView(col);
        setContentView(scroll);

        LinearLayout top = Ui.row(c);
        FrameLayout back = new FrameLayout(c);
        back.setBackground(Ui.round(c, Ui.SURFACE, 14, Ui.LINE));
        back.setForeground(ripple());
        back.setClipToOutline(true);
        back.setContentDescription("Назад");
        back.addView(new IconView(c, IconView.BACK, Ui.FG), new FrameLayout.LayoutParams(Ui.dp(c, 22), Ui.dp(c, 22), Gravity.CENTER));
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        top.addView(back, new LinearLayout.LayoutParams(Ui.dp(c, 44), Ui.dp(c, 44)));
        TextView title = Ui.text(c, "Серверы", 22, Ui.FG, true);
        title.setPadding(Ui.dp(c, 14), 0, Ui.dp(c, 8), 0);
        top.addView(title, Ui.weight());
        FrameLayout refreshBtn = new FrameLayout(c);
        refreshBtn.setBackground(Ui.round(c, Ui.SURFACE, 14, Ui.LINE));
        refreshBtn.setForeground(ripple());
        refreshBtn.setClipToOutline(true);
        refreshBtn.setContentDescription("Проверить пинг");
        refreshBtn.addView(new IconView(c, IconView.REFRESH, Ui.FG), new FrameLayout.LayoutParams(Ui.dp(c, 20), Ui.dp(c, 20), Gravity.CENTER));
        test = refreshBtn;
        test.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                BoxVpnService s = BoxVpnService.instance;
                if (s == null || AppState.vpn != AppState.ON) {
                    Toast.makeText(ServersActivity.this, "Сначала подключитесь", Toast.LENGTH_SHORT).show();
                    return;
                }
                s.pingAll();
            }
        });
        top.addView(test, new LinearLayout.LayoutParams(Ui.dp(c, 44), Ui.dp(c, 44)));
        top.setPadding(0, 0, 0, Ui.dp(c, 8));
        col.addView(top);

        autoBox = new LinearLayout(c);
        autoBox.setOrientation(LinearLayout.VERTICAL);
        autoBox.setBackground(Ui.round(c, Ui.SURFACE, 22, Ui.LINE));
        autoBox.setClipToOutline(true);
        col.addView(autoBox, Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 12));

        searchBox = searchBox(c);
        col.addView(searchBox, Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 12));

        body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        col.addView(body);

    }

    @Override
    protected void onResume() {
        super.onResume();
        AppState.addListener(refresh);
        update();
        BoxVpnService s = BoxVpnService.instance;
        if (s != null && AppState.vpn == AppState.ON && AppState.pings.isEmpty()) s.pingAll();
    }

    @Override
    protected void onPause() {
        super.onPause();
        AppState.removeListener(refresh);
    }

    private java.util.Set<String> off;
    private java.util.Set<String> offAtStart;

    private boolean isOff(Server s) {
        return off.contains(s.name);
    }

    private void toggleOff(final Server s) {
        final boolean nowOff = isOff(s);
        new android.app.AlertDialog.Builder(this)
                .setTitle(s.name)
                .setMessage(nowOff ? "Включить сервер обратно?" : "Отключить сервер? Он не будет участвовать в автовыборе, пока вы его не включите.")
                .setPositiveButton(nowOff ? "Включить" : "Отключить", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        if (nowOff) off.remove(s.name); else off.add(s.name);
                        prefs.offServers(off);
                        if (!nowOff && s.name.equals(prefs.pinned())) {
                            prefs.pinned("");
                            AppState.pinned = "";
                        }
                        update();
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // turning servers on/off changes the core config: reconnect once when leaving the screen
        if (isFinishing() && off != null && !off.equals(offAtStart) && AppState.vpn != AppState.OFF) {
            offAtStart = new java.util.HashSet<>(off);
            AppState.servers = new ArrayList<>();
            final Context app = getApplicationContext();
            VpnControl.stop(app);
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override public void run() {
                    try {
                        VpnControl.start(app);
                    } catch (Exception e) {
                        AppState.log("Не удалось переподключиться: " + e.getMessage());
                    }
                }
            }, 3000);
            Toast.makeText(app, "Переподключение для применения", Toast.LENGTH_SHORT).show();
        }
    }

    private int ping(Server s) {
        Integer p = AppState.pings.get(s.tag);
        return p == null ? 0 : p; // 0 unknown, -1 no answer
    }

    /** 3 good, 2 so-so, 1 slow, 0 unknown: the same 300/400 ms thresholds as the main screen. */
    private static int quality(int p) {
        return p <= 0 ? 0 : p < 300 ? 3 : p < 400 ? 2 : 1;
    }

    private void update() {
        List<Server> src = AppState.servers;
        if (src == null || src.isEmpty()) {
            try {
                src = Subs.applyOff(Subs.merge(Subs.cached(prefs), new ArrayList<String>(), new ArrayList<String>()), prefs.offServers());
            } catch (Exception e) {
                src = new ArrayList<>();
            }
        }
        items.clear();
        for (Server s : src) {
            if (s.group != Server.EXCLUDED || s.off) items.add(s);
        }
        Collections.sort(items, new Comparator<Server>() {
            @Override public int compare(Server a, Server b) {
                int pa = ping(a), pb = ping(b);
                int ka = pa > 0 ? pa : pa == 0 ? 100000 : 200000;
                int kb = pb > 0 ? pb : pb == 0 ? 100000 : 200000;
                return ka - kb;
            }
        });
        boolean on = AppState.vpn == AppState.ON;
        test.setAlpha(on && !AppState.pinging ? 1f : 0.4f);
        render();
    }

    private String pinnedName() {
        String p = prefs.pinned();
        return p.isEmpty() ? AppState.pinned : p;
    }

    private void render() {
        Context c = this;
        String pinned = pinnedName();
        boolean on = AppState.vpn == AppState.ON;

        autoBox.removeAllViews();
        autoBox.addView(row(c, null, pinned.isEmpty(), false));

        searchBox.setVisibility(items.size() > 8 ? View.VISIBLE : View.GONE);
        String q = query.trim().toLowerCase();
        List<Server> main = new ArrayList<>(), lte = new ArrayList<>(), offList = new ArrayList<>();
        for (Server s : items) {
            if (!matches(s, q)) continue;
            if (isOff(s)) offList.add(s);
            else if (s.origGroup == Server.LTE) lte.add(s);
            else main.add(s);
        }
        body.removeAllViews();
        String now = pinned.isEmpty() && on ? AppState.serverName : "";
        group(c, null, main, pinned, now);
        group(c, "LTE", lte, pinned, now);
        group(c, "Отключены", offList, pinned, now);
        if (body.getChildCount() == 0) {
            TextView none = Ui.text(c, q.isEmpty() ? "Серверов пока нет" : "Ничего не нашлось", 14, Ui.MUTED, false);
            none.setGravity(Gravity.CENTER);
            none.setPadding(0, Ui.dp(c, 32), 0, 0);
            body.addView(none, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    private static boolean matches(Server s, String q) {
        if (q.isEmpty()) return true;
        String hay = (s.name + " " + s.sub + (s.xray != null ? " xhttp" : "") + (s.origGroup == Server.LTE ? " lte" : "")).toLowerCase();
        for (String w : q.split("\\s+")) if (!hay.contains(w)) return false;
        return true;
    }

    private void group(Context c, String name, List<Server> list, String pinned, String now) {
        if (list.isEmpty()) return;
        if (name != null) {
            TextView label = Ui.text(c, name, 13, Ui.MUTED, true);
            label.setPadding(Ui.dp(c, 6), Ui.dp(c, 22), 0, 0);
            body.addView(label);
        }
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.round(c, Ui.SURFACE, 22, Ui.LINE));
        card.setClipToOutline(true);
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                View d = new View(c);
                d.setBackgroundColor(Ui.LINE);
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(c, 0.7f)));
                p.leftMargin = Ui.dp(c, 64);
                card.addView(d, p);
            }
            Server s = list.get(i);
            card.addView(row(c, s, s.name.equals(pinned), s.name.equals(now)));
        }
        body.addView(card, Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, name == null ? 12 : 8));
    }

    /** One line: flag (or lightning for "Авто"), name, dot if in use, ping, check if chosen. */
    private View row(Context c, final Server s, boolean sel, boolean now) {
        LinearLayout row = Ui.row(c);
        row.setPadding(Ui.dp(c, 14), Ui.dp(c, 10), Ui.dp(c, 14), Ui.dp(c, 10));
        row.setMinimumHeight(Ui.dp(c, 58));
        row.setBackground(ripple());
        row.setClickable(true);
        row.setFocusable(true);

        FrameLayout tile = new FrameLayout(c);
        String name;
        if (s == null) {
            name = "Авто";
            tile.setBackground(Ui.round(c, 0x268B7CFF, 11, 0));
            tile.addView(new IconView(c, IconView.AUTO, Ui.ACCENT), new FrameLayout.LayoutParams(Ui.dp(c, 20), Ui.dp(c, 20), Gravity.CENTER));
        } else {
            String[] fl = splitFlag(s.name);
            name = fl[1];
            tile.setBackground(Ui.round(c, Ui.SURFACE2, 11, 0));
            boolean hasFlag = !fl[0].isEmpty();
            String letter = name.trim().isEmpty() ? "?" : name.trim().substring(0, 1).toUpperCase();
            TextView f = Ui.text(c, hasFlag ? fl[0] : letter, hasFlag ? 19 : 15, Ui.MUTED, !hasFlag);
            f.setGravity(Gravity.CENTER);
            tile.addView(f, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        row.addView(tile, new LinearLayout.LayoutParams(Ui.dp(c, 36), Ui.dp(c, 36)));

        TextView t = Ui.text(c, name, 16, Ui.FG, sel);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setPadding(Ui.dp(c, 14), 0, Ui.dp(c, 8), 0);
        row.addView(t, Ui.weight());

        if (now) {
            View dot = new View(c);
            GradientDrawable dg = new GradientDrawable();
            dg.setShape(GradientDrawable.OVAL);
            dg.setColor(Ui.ACCENT);
            dot.setBackground(dg);
            LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(Ui.dp(c, 7), Ui.dp(c, 7));
            dp.rightMargin = Ui.dp(c, 10);
            row.addView(dot, dp);
        }
        int p = s == null ? 0 : ping(s);
        if (s != null && !isOff(s) && p != 0) row.addView(pingView(c, p));
        if (sel) {
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(Ui.dp(c, 20), Ui.dp(c, 20));
            cp.leftMargin = Ui.dp(c, 12);
            row.addView(new IconView(c, IconView.CHECK, Ui.ACCENT), cp);
        }
        if (s != null && isOff(s)) row.setAlpha(0.4f);

        String desc = s == null ? name : s.name + (p > 0 ? ", " + p + " мс" : p < 0 ? ", не отвечает" : "");
        row.setContentDescription(desc + (now ? ", используется сейчас" : "") + (sel ? ", выбран" : ""));
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (s == null) choose("");
                else if (isOff(s)) Toast.makeText(ServersActivity.this, "Сервер отключён. Долгое нажатие — включить", Toast.LENGTH_SHORT).show();
                else choose(s.name);
            }
        });
        if (s != null) {
            row.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    toggleOff(s);
                    return true;
                }
            });
        }
        return row;
    }

    /** Signal bars and ms in the ping colour, or a red dash when the server does not answer. */
    private static View pingView(Context c, int p) {
        int q = quality(p);
        int fg = p < 0 || q == 1 ? Ui.BAD : q == 2 ? Ui.WARN : GOOD;
        LinearLayout l = Ui.row(c);
        if (p > 0) {
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(Ui.dp(c, 13), Ui.dp(c, 12));
            bp.rightMargin = Ui.dp(c, 6);
            l.addView(new Bars(c, p < 150 ? 3 : p < 400 ? 2 : 1, fg), bp);
        }
        l.addView(Ui.text(c, p > 0 ? String.valueOf(p) : "—", 14, fg, true));
        return l;
    }

    /** Three signal bars, the first {@code level} of them lit. */
    private static final class Bars extends View {
        private final int level;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        Bars(Context c, int level, int color) {
            super(c);
            this.level = level;
            paint.setColor(color);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight(), bw = w / 4.3f, gap = (w - 3 * bw) / 2f;
            float[] hs = {h * 5 / 12f, h * 8 / 12f, h};
            for (int i = 0; i < 3; i++) {
                paint.setAlpha(i < level ? 255 : 64);
                float x = i * (bw + gap);
                r.set(x, h - hs[i], x + bw, h);
                cv.drawRoundRect(r, bw / 2, bw / 2, paint);
            }
        }
    }

    /** The first flag emoji (two regional indicators) anywhere in the name, and the name without it. */
    static String[] splitFlag(String name) {
        for (int i = 0; i + 4 <= name.length(); i += Character.charCount(name.codePointAt(i))) {
            int a = name.codePointAt(i);
            if (!isRegional(a)) continue;
            int b = name.codePointAt(i + 2);
            if (!isRegional(b)) continue;
            String rest = (name.substring(0, i) + " " + name.substring(i + 4)).trim().replaceAll("\\s{2,}", " ");
            return new String[]{name.substring(i, i + 4), rest.isEmpty() ? name : rest};
        }
        return new String[]{"", name};
    }

    private static boolean isRegional(int cp) {
        return cp >= 0x1F1E6 && cp <= 0x1F1FF;
    }

    private View searchBox(Context c) {
        LinearLayout box = Ui.row(c);
        box.setBackground(Ui.round(c, Ui.SURFACE, 18, Ui.LINE));
        box.setPadding(Ui.dp(c, 14), 0, Ui.dp(c, 6), 0);
        box.setMinimumHeight(Ui.dp(c, 52));
        box.addView(new IconView(c, IconView.SEARCH, Ui.MUTED), new LinearLayout.LayoutParams(Ui.dp(c, 20), Ui.dp(c, 20)));
        final EditText in = new EditText(c);
        in.setBackground(null);
        in.setHint("Поиск");
        in.setHintTextColor(0xFF6F6B82);
        in.setTextColor(Ui.FG);
        in.setTextSize(16);
        in.setSingleLine(true);
        in.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        in.setPadding(Ui.dp(c, 12), Ui.dp(c, 12), Ui.dp(c, 8), Ui.dp(c, 12));
        box.addView(in, Ui.weight());
        final FrameLayout clear = new FrameLayout(c);
        clear.setContentDescription("Очистить поиск");
        clear.addView(new IconView(c, IconView.CLEAR, Ui.MUTED), new FrameLayout.LayoutParams(Ui.dp(c, 18), Ui.dp(c, 18), Gravity.CENTER));
        clear.setVisibility(View.GONE);
        clear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { in.setText(""); }
        });
        box.addView(clear, new LinearLayout.LayoutParams(Ui.dp(c, 40), Ui.dp(c, 40)));
        in.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int d) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int d) { }
            @Override public void afterTextChanged(android.text.Editable s) {
                query = s.toString();
                clear.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE);
                render();
            }
        });
        return box;
    }

    private static android.graphics.drawable.RippleDrawable ripple() {
        return new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x1FFFFFFF),
                null, new android.graphics.drawable.ColorDrawable(0xFFFFFFFF));
    }

    private void choose(String name) {
        prefs.pinned(name);
        AppState.pinned = name;
        BoxVpnService s = BoxVpnService.instance;
        if (s != null && AppState.vpn != AppState.OFF) s.pin(name);
        else Toast.makeText(this, name.isEmpty() ? "Авто" : "Применится при подключении", Toast.LENGTH_SHORT).show();
        update();
    }
}
