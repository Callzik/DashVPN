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
 * All servers with their ping, grouped like the settings menu: "Авто" on a big card on top,
 * then main, LTE and turned-off servers. Tap one to use it, long press to turn it off.
 */
public class ServersActivity extends Activity {
    private static final int GOOD = 0xFF6BD68A, GOOD_TINT = 0xFF14271B;

    private Prefs prefs;
    private final List<Server> items = new ArrayList<>();
    private TextView test, hint;
    private LinearLayout autoBox, body;
    private View searchBox;
    private boolean manySubs;
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
        test = Ui.button(c, "Проверить", false);
        test.setMinHeight(Ui.dp(c, 44));
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
        top.addView(test);
        top.setPadding(0, 0, 0, Ui.dp(c, 8));
        col.addView(top);

        autoBox = new LinearLayout(c);
        autoBox.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{Ui.ACCENT_TINT, Ui.SURFACE});
        g.setCornerRadius(Ui.dp(c, 22));
        g.setStroke(Ui.dp(c, 1), Ui.LINE2);
        autoBox.setBackground(g);
        autoBox.setClipToOutline(true);
        col.addView(autoBox, Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 16));

        searchBox = searchBox(c);
        col.addView(searchBox, Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 12));

        body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        col.addView(body);

        hint = Ui.text(c, "", 13, Ui.MUTED, false);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(Ui.dp(c, 8), 0, Ui.dp(c, 8), 0);
        col.addView(hint, Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 18));
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
        java.util.Set<String> subs = new java.util.HashSet<>();
        for (Server s : src) {
            if (s.group != Server.EXCLUDED || s.off) items.add(s);
            subs.add(s.sub);
        }
        manySubs = subs.size() > 1;
        Collections.sort(items, new Comparator<Server>() {
            @Override public int compare(Server a, Server b) {
                int pa = ping(a), pb = ping(b);
                int ka = pa > 0 ? pa : pa == 0 ? 100000 : 200000;
                int kb = pb > 0 ? pb : pb == 0 ? 100000 : 200000;
                return ka - kb;
            }
        });
        boolean on = AppState.vpn == AppState.ON;
        test.setText(AppState.pinging ? "Проверка…" : "Проверить");
        test.setAlpha(on && !AppState.pinging ? 1f : 0.5f);
        hint.setText((on ? "" : "Подключитесь, чтобы увидеть пинг. ")
                + "Долгое нажатие на сервер — отключить или включить." + (off.isEmpty() ? "" : " Отключено: " + off.size()));
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
        String autoSub = pinned.isEmpty() && on && !AppState.serverName.isEmpty()
                ? "Сейчас: " + AppState.serverName : "Лучший сервер выбирается сам";
        autoBox.addView(row(c, null, "Авто", autoSub, false, null, pinned.isEmpty(), true));

        searchBox.setVisibility(items.size() > 5 ? View.VISIBLE : View.GONE);
        String q = query.trim().toLowerCase();
        List<Server> main = new ArrayList<>(), lte = new ArrayList<>(), offList = new ArrayList<>();
        for (Server s : items) {
            if (!matches(s, q)) continue;
            if (isOff(s)) offList.add(s);
            else if (s.origGroup == Server.LTE) lte.add(s);
            else main.add(s);
        }
        body.removeAllViews();
        group(c, "Основные", main, pinned, on);
        group(c, "Резервные (LTE)", lte, pinned, on);
        group(c, "Отключённые", offList, pinned, on);
        if (body.getChildCount() == 0) {
            TextView none = Ui.text(c, q.isEmpty() ? "Серверов пока нет" : "Ничего не нашлось", 14, Ui.MUTED, false);
            none.setGravity(Gravity.CENTER);
            none.setPadding(0, Ui.dp(c, 32), 0, 0);
            body.addView(none, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    private static boolean matches(Server s, String q) {
        if (q.isEmpty()) return true;
        String hay = (s.name + " " + s.sub + (s.xray != null ? " xhttp" : "")
                + (s.origGroup == Server.LTE ? " резервный lte" : " основной")).toLowerCase();
        for (String w : q.split("\\s+")) if (!hay.contains(w)) return false;
        return true;
    }

    private void group(Context c, String name, List<Server> list, String pinned, boolean on) {
        if (list.isEmpty()) return;
        LinearLayout label = Ui.row(c);
        label.addView(Ui.text(c, name, 13, Ui.MUTED, true));
        TextView n = Ui.text(c, "  " + list.size(), 13, 0xFF6F6B82, true);
        label.addView(n);
        label.setPadding(Ui.dp(c, 6), Ui.dp(c, 22), 0, 0);
        body.addView(label);

        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.round(c, Ui.SURFACE, 22, Ui.LINE));
        card.setClipToOutline(true);
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                View d = new View(c);
                d.setBackgroundColor(Ui.LINE);
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(c, 0.7f)));
                p.leftMargin = Ui.dp(c, 70);
                card.addView(d, p);
            }
            final Server s = list.get(i);
            boolean sel = s.name.equals(pinned);
            boolean now = on && s.name.equals(AppState.serverName);
            int p = ping(s);
            List<String> bits = new ArrayList<>();
            if (s.xray != null) bits.add("xhttp");
            if (manySubs && !s.sub.isEmpty()) bits.add(s.sub);
            String sub;
            if (now && !sel) {
                sub = "Сейчас используется" + (bits.isEmpty() ? "" : " · " + TextUtils.join(" · ", bits));
            } else if (!bits.isEmpty()) {
                sub = TextUtils.join(" · ", bits);
            } else if (isOff(s)) {
                sub = "Не участвует в автовыборе";
            } else {
                sub = p > 0 ? new String[]{"", "медленный", "средний", "быстрый"}[quality(p)] : p < 0 ? "Нет ответа" : "Пинг ещё не проверен";
            }
            View r = row(c, s, null, sub, now && !sel, isOff(s) ? null : pill(c, p), sel, false);
            if (isOff(s)) r.setAlpha(0.45f);
            card.addView(r);
        }
        body.addView(card, Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 8));
    }

    /** One row: flag tile (or lightning for "Авто"), name and subtitle, ping pill, radio. */
    private View row(Context c, final Server s, String title, String sub, boolean subAccent, View right, boolean sel, boolean big) {
        LinearLayout row = Ui.row(c);
        row.setPadding(Ui.dp(c, 14), Ui.dp(c, big ? 18 : 12), Ui.dp(c, 14), Ui.dp(c, big ? 18 : 12));
        row.setMinimumHeight(Ui.dp(c, big ? 76 : 64));
        if (sel && !big) {
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0x1A8B7CFF, 0x008B7CFF});
            row.setBackground(g);
            row.setForeground(ripple());
        } else {
            row.setBackground(ripple());
        }
        row.setClickable(true);
        row.setFocusable(true);

        int ts = big ? 46 : 42;
        FrameLayout tile = new FrameLayout(c);
        String name = title;
        if (s == null) {
            tile.setBackground(Ui.round(c, 0x268B7CFF, big ? 15 : 13, 0));
            tile.addView(new IconView(c, IconView.AUTO, Ui.ACCENT), new FrameLayout.LayoutParams(Ui.dp(c, 26), Ui.dp(c, 26), Gravity.CENTER));
        } else {
            String[] fl = splitFlag(s.name);
            name = fl[1];
            GradientDrawable bg = Ui.round(c, Ui.SURFACE2, 13, 0);
            if (sel) bg.setStroke(Ui.dp(c, 2), Ui.ACCENT);
            tile.setBackground(bg);
            boolean hasFlag = !fl[0].isEmpty();
            String letter = name.trim().isEmpty() ? "?" : name.trim().substring(0, 1).toUpperCase();
            TextView f = Ui.text(c, hasFlag ? fl[0] : letter, hasFlag ? 22 : 16, Ui.MUTED, !hasFlag);
            f.setGravity(Gravity.CENTER);
            tile.addView(f, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        row.addView(tile, new LinearLayout.LayoutParams(Ui.dp(c, ts), Ui.dp(c, ts)));

        LinearLayout text = new LinearLayout(c);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(Ui.dp(c, 14), 0, Ui.dp(c, 8), 0);
        TextView t = Ui.text(c, name, big ? 18 : 16, Ui.FG, true);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(t);
        TextView st = Ui.text(c, sub, 13, subAccent ? Ui.ACCENT : Ui.MUTED, false);
        st.setSingleLine(true);
        st.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(st, Ui.lp(c, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 2));
        row.addView(text, Ui.weight());

        if (right != null) row.addView(right);
        if (s != null && isOff(s)) {
            TextView o = Ui.badge(c);
            o.setText("отключён");
            row.addView(o);
        }
        View radio = new View(c);
        GradientDrawable rg = new GradientDrawable();
        rg.setShape(GradientDrawable.OVAL);
        if (sel) {
            rg.setColor(Ui.ACCENT);
        } else {
            rg.setColor(0);
            rg.setStroke(Ui.dp(c, 2), Ui.LINE2);
        }
        radio.setBackground(sel ? new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{rg, dot(c)}) : rg);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(Ui.dp(c, 22), Ui.dp(c, 22));
        rp.leftMargin = Ui.dp(c, 12);
        row.addView(radio, rp);

        row.setContentDescription(s == null ? name + ". " + sub : s.name + ". " + sub + (sel ? ". Выбран" : ""));
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

    private static android.graphics.drawable.Drawable dot(Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(Ui.ACCENT_INK);
        android.graphics.drawable.InsetDrawable in = new android.graphics.drawable.InsetDrawable(d, Ui.dp(c, 7));
        return in;
    }

    /** Coloured ping pill with signal bars, or null while the ping is unknown. */
    private static View pill(Context c, int p) {
        if (p == 0) return null;
        int q = quality(p);
        int fg = p < 0 || q == 1 ? Ui.BAD : q == 2 ? Ui.WARN : GOOD;
        int bg = p < 0 || q == 1 ? Ui.BAD_TINT : q == 2 ? Ui.WARN_TINT : GOOD_TINT;
        LinearLayout l = Ui.row(c);
        l.setBackground(Ui.round(c, bg, 12, 0));
        l.setPadding(Ui.dp(c, 10), Ui.dp(c, 5), Ui.dp(c, 10), Ui.dp(c, 5));
        if (p > 0) {
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(Ui.dp(c, 13), Ui.dp(c, 12));
            bp.rightMargin = Ui.dp(c, 7);
            l.addView(new Bars(c, p < 150 ? 3 : p < 400 ? 2 : 1, fg), bp);
        }
        l.addView(Ui.text(c, p > 0 ? p + " мс" : "не отвечает", 13, fg, true));
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

    /** Leading flag emoji (two regional indicators) and the rest of the name. */
    static String[] splitFlag(String name) {
        if (name.length() >= 4) {
            int a = name.codePointAt(0);
            int b = name.codePointAt(Character.charCount(a));
            if (isRegional(a) && isRegional(b)) {
                String rest = name.substring(4).trim();
                return new String[]{name.substring(0, 4), rest.isEmpty() ? name : rest};
            }
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
        in.setHint("Поиск сервера");
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
