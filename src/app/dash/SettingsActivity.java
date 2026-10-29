package app.dash;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class SettingsActivity extends Activity {
    private Prefs prefs;
    private TextView modeWl, modeNet, modeHint, logView;
    private EditText subInput;
    private LinearLayout subList;
    private TextView updStatus, updBtn;
    private final android.os.Handler updHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable updTicker = new Runnable() {
        @Override public void run() {
            renderUpdate();
            updHandler.postDelayed(this, 1000);
        }
    };

    private static final int ROOT = -1, P_SUBS = 0, P_AUTO = 1, P_ROUTE = 2, P_APPS = 3, P_SHARE = 4,
            P_HOME = 5, P_SERVERS = 6, P_UPDATE = 7, P_LOG = 8, P_ISLAND = 9, P_LOCK = 10;
    private static final String[] TITLES = {"Подписки", "Автовыбор серверов", "Маршрутизация",
            "Приложения без VPN", "Раздача VPN", "Главный экран", "Серверы из подписки", "Обновление", "Журнал", "Остров и Now Bar", "Только через VPN"};
    private static final int[] ICONS = {IconView.SUBS, IconView.AUTO, IconView.ROUTE, IconView.APPS,
            IconView.SHARE, IconView.HOME, IconView.SERVERS, IconView.UPDATE, IconView.LOG, IconView.ISLAND, IconView.LOCK};
    private static final int[] COLORS = {Ui.ACCENT, 0xFFF2B544, 0xFF5AA9FF, 0xFFFF9F6B,
            0xFF4FD1C5, 0xFFF27BB0, 0xFFB48CFF, 0xFF6BD68A, 0xFFF0716B, 0xFF5AA9FF, 0xFFF0716B};
    /** Extra words the search finds a page by: what is inside it, not only its title. */
    private static final String[] KEYS = {
            "ссылка подписка ключ vless добавить удалить вставить обновить переподключить",
            "белые списки тип сети lte wi-fi вайфай мобильная группа режим выбор",
            "российские сайты ру рф su напрямую заблокированные обход маршруты",
            "исключения приложения напрямую мимо vpn split",
            "точка доступа хотспот hotspot прокси socks http пароль логин раздать",
            "плашки порядок оформление вид главный",
            "список серверов обычные lte не участвуют заглушка",
            "версия обновить установить загрузка apk",
            "лог журнал ошибки диагностика скопировать поддержка",
            "уведомление остров живые уведомления live update строка состояния samsung самсунг",
            "замок kill switch блокировать запретить закрыть приложения без vpn бан аккаунт утечка"};
    private static final int[][] GROUPS = {{P_AUTO, P_ROUTE, P_APPS, P_LOCK, P_SHARE}, {P_HOME, P_ISLAND}, {P_UPDATE, P_LOG}};
    private static final String[] GROUP_NAMES = {"Подключение", "Внешний вид", "О приложении"};

    private ScrollView scroll;
    private LinearLayout col, menuBody;
    private int page = ROOT, rootScroll;
    private String query = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 32));
        scroll.addView(col);
        setContentView(scroll);
        showRoot();
    }

    @Override public void onBackPressed() {
        if (page != ROOT) {
            showRoot();
            enter(-1);
            scroll.post(new Runnable() {
                @Override public void run() { scroll.scrollTo(0, rootScroll); }
            });
        } else {
            super.onBackPressed();
        }
    }

    private void showRoot() {
        Context c = this;
        page = ROOT;
        col.removeAllViews();
        col.addView(header(c, "Настройки", "версия " + verName()));

        // subscriptions are the one thing every user touches, so they get the big card on top
        LinearLayout hero = new LinearLayout(c);
        hero.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR, new int[]{Ui.ACCENT_TINT, Ui.SURFACE});
        g.setCornerRadius(Ui.dp(c, 22));
        g.setStroke(Ui.dp(c, 1), Ui.LINE2);
        hero.setBackground(g);
        hero.setClipToOutline(true);
        View hr = menuRow(c, P_SUBS, true);
        hero.addView(hr);
        col.addView(hero, Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 16));

        col.addView(searchBox(c), Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 12));

        menuBody = new LinearLayout(c);
        menuBody.setOrientation(LinearLayout.VERTICAL);
        col.addView(menuBody);
        renderMenu();
    }

    /** The grouped menu, or a flat list of matches while something is typed in search. */
    private void renderMenu() {
        Context c = this;
        menuBody.removeAllViews();
        String q = query.trim().toLowerCase();
        if (q.isEmpty()) {
            for (int gi = 0; gi < GROUPS.length; gi++) {
                menuBody.addView(groupLabel(c, GROUP_NAMES[gi]));
                menuBody.addView(group(c, GROUPS[gi]), Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 8));
            }
            return;
        }
        List<Integer> hits = new ArrayList<>();
        for (int i = 0; i < TITLES.length; i++) {
            if (i == P_SERVERS) continue; // groups are set on the servers screen now
            String hay = (TITLES[i] + " " + subtitle(i) + " " + KEYS[i]).toLowerCase();
            boolean all = true;
            for (String w : q.split("\\s+")) if (!hay.contains(w)) { all = false; break; }
            if (all) hits.add(i);
        }
        if (hits.isEmpty()) {
            TextView none = Ui.text(c, "Ничего не нашлось", 14, Ui.MUTED, false);
            none.setGravity(android.view.Gravity.CENTER);
            none.setPadding(0, Ui.dp(c, 32), 0, 0);
            menuBody.addView(none, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return;
        }
        int[] ids = new int[hits.size()];
        for (int i = 0; i < ids.length; i++) ids[i] = hits.get(i);
        menuBody.addView(group(c, ids), Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 16));
    }

    private void open(int id) {
        if (id == P_APPS || id == P_LOCK) {
            startActivity(new Intent(this, AppsActivity.class).putExtra(AppsActivity.EXTRA_LOCK, id == P_LOCK));
            return;
        }
        if (id == P_HOME) { // arranged right on the main screen
            startActivity(new Intent(this, MainActivity.class).putExtra(MainActivity.EXTRA_EDIT_HOME, true));
            return;
        }
        rootScroll = scroll.getScrollY();
        hideKeyboard();
        page = id;
        Context c = this;
        col.removeAllViews();
        col.addView(header(c, TITLES[id], null));
        switch (id) {
            case P_SUBS: pageSubs(c, col); break;
            case P_AUTO: pageAuto(c, col); break;
            case P_ROUTE: pageRouting(c, col); break;
            case P_SHARE: pageShare(c, col); break;
            case P_SERVERS: pageServers(c, col); break;
            case P_UPDATE: pageUpdate(c, col); renderUpdate(); break;
            case P_LOG: pageLog(c, col); break;
            case P_ISLAND: pageIsland(c, col); break;
        }
        scroll.scrollTo(0, 0);
        enter(1);
    }

    /** Short slide in from the side the user is going to. */
    private void enter(int dir) {
        col.setAlpha(0f);
        col.setTranslationX(Ui.dp(this, 24) * dir);
        col.animate().alpha(1f).translationX(0).setDuration(180)
                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
    }

    private void hideKeyboard() {
        View f = getCurrentFocus();
        if (f == null) return;
        ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(f.getWindowToken(), 0);
        f.clearFocus();
    }

    /** What is set inside a page right now, shown under its name in the menu. */
    private String subtitle(int id) {
        switch (id) {
            case P_SUBS: {
                int n = prefs.subUrls().size();
                long upd = prefs.subUpdated();
                String s = n + " " + plural(n, "подписка", "подписки", "подписок");
                String srv = AppState.lastServers;
                int dot = srv.indexOf(" ·");
                if (!srv.isEmpty()) s += " · " + (dot > 0 ? srv.substring(0, dot) : srv).toLowerCase();
                else s += upd == 0 ? " · ещё не загружались" : " · " + android.text.format.DateFormat.format("dd.MM HH:mm", upd);
                return s;
            }
            case P_AUTO: return Prefs.MODE_WL.equals(prefs.mode()) ? "По белым спискам" : "По типу сети";
            case P_ROUTE: return prefs.ruDirect() ? "Российские сайты напрямую" : "Весь трафик через VPN";
            case P_APPS: {
                int n = prefs.excludedApps().size();
                return n == 0 ? "Все приложения через VPN" : "Напрямую: " + n;
            }
            case P_LOCK: {
                int n = prefs.lockedApps().size();
                return n == 0 ? "Выключено" : "Закрыто без VPN: " + n;
            }
            case P_SHARE: return prefs.shareProxy() ? "Включена · порт " + Prefs.SHARE_PORT : "Выключена";
            case P_HOME: return "Размер и место плиток";
            case P_SERVERS: return "Какие серверы в какой группе";
            case P_ISLAND: {
                Boolean a = LiveUpdate.allowed(this);
                if (!LiveUpdate.supported()) return "Нужен Android 16";
                return a == null ? "Уведомление VPN в строке состояния" : a ? "Разрешены" : "Выключены в системе";
            }
            case P_UPDATE: return !Updater.newVersion.isEmpty() ? "Доступна версия " + Updater.newVersion : "Версия " + verName();
            default: return "Что происходило с подключением";
        }
    }

    private static String plural(int n, String one, String few, String many) {
        int m10 = n % 10, m100 = n % 100;
        if (m10 == 1 && m100 != 11) return one;
        if (m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14)) return few;
        return many;
    }

    private LinearLayout header(Context c, String title, String right) {
        LinearLayout top = Ui.row(c);
        android.widget.FrameLayout back = new android.widget.FrameLayout(c);
        back.setBackground(Ui.round(c, Ui.SURFACE, 14, Ui.LINE));
        back.setForeground(ripple(c));
        back.setClipToOutline(true);
        back.setContentDescription("Назад");
        back.addView(new IconView(c, IconView.BACK, Ui.FG), new android.widget.FrameLayout.LayoutParams(Ui.dp(c, 22), Ui.dp(c, 22), android.view.Gravity.CENTER));
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onBackPressed(); }
        });
        top.addView(back, new LinearLayout.LayoutParams(Ui.dp(c, 44), Ui.dp(c, 44)));
        TextView t = Ui.text(c, title, 22, Ui.FG, true);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setPadding(Ui.dp(c, 14), 0, Ui.dp(c, 8), 0);
        top.addView(t, Ui.weight());
        if (right != null) top.addView(Ui.text(c, right, 13, Ui.MUTED, false));
        top.setPadding(0, 0, 0, Ui.dp(c, 8));
        return top;
    }

    private View searchBox(Context c) {
        LinearLayout box = Ui.row(c);
        box.setBackground(Ui.round(c, Ui.SURFACE, 18, Ui.LINE));
        box.setPadding(Ui.dp(c, 14), 0, Ui.dp(c, 6), 0);
        box.setMinimumHeight(Ui.dp(c, 52));
        box.addView(new IconView(c, IconView.SEARCH, Ui.MUTED), new LinearLayout.LayoutParams(Ui.dp(c, 20), Ui.dp(c, 20)));
        final EditText in = new EditText(c);
        in.setBackground(null);
        in.setHint("Поиск настроек");
        in.setHintTextColor(0xFF6F6B82);
        in.setTextColor(Ui.FG);
        in.setTextSize(16);
        in.setSingleLine(true);
        in.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        in.setPadding(Ui.dp(c, 12), Ui.dp(c, 12), Ui.dp(c, 8), Ui.dp(c, 12));
        in.setText(query);
        box.addView(in, Ui.weight());
        final android.widget.FrameLayout clear = new android.widget.FrameLayout(c);
        clear.setContentDescription("Очистить поиск");
        clear.addView(new IconView(c, IconView.CLEAR, Ui.MUTED), new android.widget.FrameLayout.LayoutParams(Ui.dp(c, 18), Ui.dp(c, 18), android.view.Gravity.CENTER));
        clear.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE);
        clear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { in.setText(""); hideKeyboard(); }
        });
        box.addView(clear, new LinearLayout.LayoutParams(Ui.dp(c, 40), Ui.dp(c, 40)));
        in.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int d) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int d) { }
            @Override public void afterTextChanged(android.text.Editable s) {
                query = s.toString();
                clear.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE);
                renderMenu();
            }
        });
        return box;
    }

    private TextView groupLabel(Context c, String s) {
        TextView t = Ui.text(c, s, 13, Ui.MUTED, true);
        t.setPadding(Ui.dp(c, 6), Ui.dp(c, 22), 0, 0);
        return t;
    }

    private LinearLayout group(Context c, int[] ids) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.round(c, Ui.SURFACE, 22, Ui.LINE));
        card.setClipToOutline(true);
        for (int i = 0; i < ids.length; i++) {
            if (i > 0) {
                View d = new View(c);
                d.setBackgroundColor(Ui.LINE);
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(c, 0.7f)));
                p.leftMargin = Ui.dp(c, 70);
                card.addView(d, p);
            }
            card.addView(menuRow(c, ids[i], false));
        }
        return card;
    }

    private View menuRow(Context c, final int id, boolean big) {
        LinearLayout row = Ui.row(c);
        row.setPadding(Ui.dp(c, 14), Ui.dp(c, big ? 14 : 10), Ui.dp(c, 12), Ui.dp(c, big ? 14 : 10));
        row.setMinimumHeight(Ui.dp(c, big ? 68 : 58));
        row.setBackground(ripple(c));
        row.setClickable(true);
        row.setFocusable(true);

        int color = COLORS[id];
        android.widget.FrameLayout tile = new android.widget.FrameLayout(c);
        tile.setBackground(Ui.round(c, (color & 0x00FFFFFF) | 0x26000000, big ? 15 : 13, 0));
        int ic = big ? 26 : 22;
        tile.addView(new IconView(c, ICONS[id], color), new android.widget.FrameLayout.LayoutParams(Ui.dp(c, ic), Ui.dp(c, ic), android.view.Gravity.CENTER));
        int ts = big ? 46 : 42;
        row.addView(tile, new LinearLayout.LayoutParams(Ui.dp(c, ts), Ui.dp(c, ts)));

        LinearLayout text = new LinearLayout(c);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(Ui.dp(c, 14), 0, Ui.dp(c, 8), 0);
        TextView title = Ui.text(c, TITLES[id], big ? 18 : 16, Ui.FG, true);
        title.setSingleLine(true);
        text.addView(title);
        String subText = subtitle(id);
        // only news gets a second line: the menu is names and icons
        if (id == P_UPDATE && !Updater.newVersion.isEmpty()) {
            TextView sub = Ui.text(c, subText, 13, Ui.ACCENT, false);
            sub.setSingleLine(true);
            text.addView(sub, Ui.lp(c, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 2));
        }
        row.addView(text, Ui.weight());

        row.addView(new IconView(c, IconView.CHEVRON, 0xFF6F6B82), new LinearLayout.LayoutParams(Ui.dp(c, 18), Ui.dp(c, 18)));
        row.setContentDescription(TITLES[id] + ". " + subText);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { open(id); }
        });
        return row;
    }

    private static android.graphics.drawable.RippleDrawable ripple(Context c) {
        return new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x1FFFFFFF),
                null, new android.graphics.drawable.ColorDrawable(0xFFFFFFFF));
    }

    private void pageSubs(Context c, LinearLayout col) {
        LinearLayout sub = Ui.card(c);
        subList = new LinearLayout(c);
        subList.setOrientation(LinearLayout.VERTICAL);
        sub.addView(subList);
        renderSubs();

        subInput = new EditText(c);
        subInput.setTextColor(Ui.FG);
        subInput.setHintTextColor(0xFF8E8AA3);
        subInput.setHint("Ссылка на подписку");
        subInput.setTypeface(Typeface.MONOSPACE);
        subInput.setTextSize(13);
        subInput.setMaxLines(3);
        subInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        subInput.setBackground(Ui.round(c, Ui.SURFACE2, 12, 0));
        subInput.setPadding(Ui.dp(c, 12), Ui.dp(c, 12), Ui.dp(c, 12), Ui.dp(c, 12));
        sub.addView(subInput, Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 12));

        LinearLayout addRow = Ui.row(c);
        TextView paste = Ui.button(c, "Вставить", false);
        paste.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                ClipboardManager cb = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                ClipData d = cb.getPrimaryClip();
                CharSequence t = d != null && d.getItemCount() > 0 ? d.getItemAt(0).coerceToText(SettingsActivity.this) : null;
                if (t != null) subInput.setText(t.toString().trim());
                else Toast.makeText(SettingsActivity.this, "Буфер обмена пуст", Toast.LENGTH_SHORT).show();
            }
        });
        addRow.addView(paste, Ui.weight());
        View gap = new View(c);
        addRow.addView(gap, new LinearLayout.LayoutParams(Ui.dp(c, 8), 1));
        TextView add = Ui.button(c, "Добавить", false);
        add.setTextColor(Ui.ACCENT);
        add.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String val = subInput.getText().toString().trim();
                if (!MainActivity.looksLikeLink(val)) {
                    Toast.makeText(SettingsActivity.this, "Это не похоже на ссылку на подписку", Toast.LENGTH_LONG).show();
                    return;
                }
                if (!prefs.addSub(val)) {
                    Toast.makeText(SettingsActivity.this, "Эта подписка уже добавлена", Toast.LENGTH_SHORT).show();
                    return;
                }
                subInput.setText("");
                subsChanged();
            }
        });
        addRow.addView(add, Ui.weight());
        sub.addView(addRow, Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 10));

        long upd = prefs.subUpdated();
        String info = upd == 0 ? "Ещё не загружались" : "Загружены " + android.text.format.DateFormat.format("dd.MM HH:mm", upd);
        TextView subInfo = Ui.text(c, info, 13, Ui.MUTED, false);
        sub.addView(subInfo, Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 12));
        TextView save = Ui.button(c, "Обновить", false);
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                prefs.resetSubUpdated();
                reconnect();
            }
        });
        sub.addView(save, Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 12));
        col.addView(sub, Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 8));
    }

    private void pageAuto(Context c, LinearLayout col) {
        LinearLayout auto = Ui.card(c);
        LinearLayout seg = Ui.row(c);
        seg.setBackground(Ui.round(c, Ui.SURFACE2, 12, 0));
        seg.setPadding(Ui.dp(c, 4), Ui.dp(c, 4), Ui.dp(c, 4), Ui.dp(c, 4));
        modeWl = segButton(c, "По белым спискам");
        modeNet = segButton(c, "По типу сети");
        seg.addView(modeWl, Ui.weight());
        seg.addView(modeNet, Ui.weight());
        modeWl.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setMode(Prefs.MODE_WL); }
        });
        modeNet.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setMode(Prefs.MODE_NET); }
        });
        auto.addView(seg);
        modeHint = Ui.text(c, "", 13, Ui.MUTED, false);
        auto.addView(modeHint, Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 8));
        col.addView(auto, Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 8));
        renderMode();
    }

    private void pageRouting(Context c, LinearLayout col) {
        LinearLayout rt = Ui.card(c);
        rt.addView(switchRow(c, "Российские сайты без VPN", null, prefs.ruDirect(), new Toggle() {
            @Override public void set(boolean v) { prefs.ruDirect(v); }
        }));
        rt.addView(Ui.divider(c));
        rt.addView(switchRow(c, "Заблокированные в РФ — через VPN", null, prefs.blockedVpn(), new Toggle() {
            @Override public void set(boolean v) { prefs.blockedVpn(v); }
        }));
        col.addView(rt, Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 8));
    }

    private void pageShare(Context c, LinearLayout col) {
        LinearLayout sh = Ui.card(c);
        sh.addView(switchRow(c, "VPN для точки доступа", null, prefs.shareProxy(), new Toggle() {
            @Override public void set(boolean v) {
                prefs.shareProxy(v);
                renderShare();
                reconnect();
            }
        }));
        shareInfo = Ui.text(c, "", 13, Ui.MUTED, false);
        shareInfo.setTextIsSelectable(true);
        sh.addView(shareInfo, Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 8));
        shareButtons = Ui.row(c);
        TextView shCopy = Ui.button(c, "Скопировать", false);
        shCopy.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                ClipboardManager cb = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cb.setPrimaryClip(ClipData.newPlainText("proxy", shareText()));
                Toast.makeText(SettingsActivity.this, "Скопировано", Toast.LENGTH_SHORT).show();
            }
        });
        TextView shReset = Ui.button(c, "Новый пароль", false);
        shReset.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                prefs.resetShareCreds();
                renderShare();
                reconnect();
            }
        });
        shareButtons.addView(shCopy, Ui.weight());
        View shGap = new View(c);
        shareButtons.addView(shGap, new LinearLayout.LayoutParams(Ui.dp(c, 8), 1));
        shareButtons.addView(shReset, Ui.weight());
        sh.addView(shareButtons, Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 12));
        col.addView(sh, Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 8));
        renderShare();
    }

    private void pageServers(Context c, LinearLayout col) {
        LinearLayout srv = Ui.card(c);
        srv.addView(Ui.text(c, serversText(), 13, Ui.FG, false));
        col.addView(srv, Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 8));
    }

    private void pageUpdate(Context c, LinearLayout col) {
        LinearLayout updCard = Ui.card(c);
        updStatus = Ui.text(c, "", 13, Ui.MUTED, false);
        updCard.addView(updStatus);
        updBtn = Ui.button(c, "Проверить обновление", false);
        updBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (Updater.progress >= 0) return;
                if (!Updater.newVersion.isEmpty()) {
                    Updater.start(SettingsActivity.this);
                } else {
                    Toast.makeText(SettingsActivity.this, "Проверка обновлений…", Toast.LENGTH_SHORT).show();
                    Updater.check(SettingsActivity.this, true);
                }
            }
        });
        updCard.addView(updBtn, Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 12));
        col.addView(updCard, Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 8));
    }

    private TextView islandStatus;

    private void pageIsland(Context c, LinearLayout col) {
        LinearLayout card = Ui.card(c);
        islandStatus = Ui.text(c, LiveUpdate.status(c, BoxVpnService.NOTIFY_ID), 13, Ui.MUTED, false);
        card.addView(islandStatus);
        col.addView(card, Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 8));
        TextView open = Ui.button(c, "Настройки уведомлений", false);
        open.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { LiveUpdate.openSettings(SettingsActivity.this); }
        });
        col.addView(open, Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 12));
    }

    private void pageLog(Context c, LinearLayout col) {
        LinearLayout lg = Ui.card(c);
        logView = Ui.text(c, "", 11, Ui.MUTED, false);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        // the button goes first: the log is up to 200 lines long
        TextView copy = Ui.button(c, "Скопировать журнал", false);
        copy.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                ClipboardManager cb = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cb.setPrimaryClip(ClipData.newPlainText("log", logText()));
                Toast.makeText(SettingsActivity.this, "Журнал скопирован", Toast.LENGTH_SHORT).show();
            }
        });
        col.addView(copy, Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 8));
        lg.addView(logView);
        col.addView(lg, Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 12));
        logView.setText(logText());
    }

    @Override protected void onResume() {
        super.onResume();
        // values on the menu (apps count, share on/off) may have changed in another screen
        if (page == ROOT) showRoot();
        // back from the system's notification settings
        if (page == P_ISLAND && islandStatus != null) islandStatus.setText(LiveUpdate.status(this, BoxVpnService.NOTIFY_ID));
        renderShare();
        updHandler.post(updTicker);
    }

    @Override protected void onPause() {
        updHandler.removeCallbacks(updTicker);
        super.onPause();
    }

    private void renderUpdate() {
        if (updStatus == null) return;
        int pr = Updater.progress;
        if (pr == 101) updStatus.setText("Установка " + Updater.newVersion + "…");
        else if (pr >= 0) updStatus.setText("Загрузка " + Updater.newVersion + " · " + pr + "%");
        else if (!Updater.error.isEmpty()) updStatus.setText(Updater.error);
        else if (!Updater.newVersion.isEmpty()) updStatus.setText("Доступна версия " + Updater.newVersion);
        else if (Updater.checking) updStatus.setText("Проверка обновлений…");
        else if (!Updater.checkError.isEmpty()) updStatus.setText(Updater.checkError + ". Версия " + verName());
        else updStatus.setText("У вас последняя версия " + verName());
        updBtn.setEnabled(pr < 0 && !Updater.checking);
        updBtn.setText(pr >= 0 ? "Проверить обновление"
                : !Updater.newVersion.isEmpty() ? "Установить " + Updater.newVersion : "Проверить обновление");
    }

    private String verName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return ""; }
    }

    private String logText() {
        List<String> lines = AppState.logSnapshot();
        StringBuilder sb = new StringBuilder();
        int from = Math.max(0, lines.size() - 200);
        for (int i = lines.size() - 1; i >= from; i--) sb.append(lines.get(i)).append('\n');
        return sb.length() == 0 ? "Пока пусто" : sb.toString();
    }

    private String serversText() {
        List<Subs.Entry> entries = Subs.cached(prefs);
        if (entries.isEmpty()) return "Подписка ещё не загружалась. Подключитесь один раз.";
        List<String> warnings = new ArrayList<>();
        List<String> stubs = new ArrayList<>();
        List<Server> list = Subs.arrange(Subs.merge(entries, warnings, stubs), prefs);
        for (String st : stubs) warnings.add("заглушка вместо серверов — " + st);
        StringBuilder sb = new StringBuilder();
        String[] titles = {"Обычные", "LTE (для белых списков)", "Не участвуют"};
        for (int g = 0; g < 3; g++) {
            int n = 0;
            StringBuilder part = new StringBuilder();
            for (Server s : list) {
                if (s.group != g) continue;
                n++;
                part.append("  ").append(s.name);
                if (entries.size() > 1) part.append("  [").append(s.sub).append(']');
                if (s.excludeReason != null) part.append(" — ").append(s.excludeReason);
                part.append('\n');
            }
            sb.append(titles[g]).append(": ").append(n).append('\n').append(part);
            if (g < 2) sb.append('\n');
        }
        for (String w : warnings) sb.append("\n⚠ ").append(w);
        return sb.toString().trim();
    }

    /** One row per subscription with a delete cross. */
    private void renderSubs() {
        Context c = this;
        subList.removeAllViews();
        List<String> urls = prefs.subUrls();
        for (int i = 0; i < urls.size(); i++) {
            final String url = urls.get(i);
            if (i > 0) subList.addView(Ui.divider(c));
            LinearLayout row = Ui.row(c);
            LinearLayout text = new LinearLayout(c);
            text.setOrientation(LinearLayout.VERTICAL);
            TextView name = Ui.text(c, Subs.label(prefs, url), 15, Ui.FG, true);
            name.setSingleLine(true);
            text.addView(name);
            TextView link = Ui.text(c, url, 12, Ui.MUTED, false);
            link.setTypeface(Typeface.MONOSPACE);
            link.setSingleLine(true);
            link.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            text.addView(link);
            row.addView(text, Ui.weight());
            TextView del = Ui.text(c, "✕", 18, Ui.MUTED, false);
            del.setPadding(Ui.dp(c, 14), Ui.dp(c, 6), Ui.dp(c, 4), Ui.dp(c, 6));
            del.setContentDescription("Удалить подписку");
            del.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (prefs.subUrls().size() <= 1) {
                        Toast.makeText(SettingsActivity.this, "Должна остаться хотя бы одна подписка", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    new android.app.AlertDialog.Builder(SettingsActivity.this)
                            .setTitle("Удалить подписку?")
                            .setMessage(Subs.label(prefs, url))
                            .setPositiveButton("Удалить", new android.content.DialogInterface.OnClickListener() {
                                @Override public void onClick(android.content.DialogInterface d, int w) {
                                    prefs.removeSub(url);
                                    subsChanged();
                                }
                            })
                            .setNegativeButton("Отмена", null)
                            .show();
                }
            });
            row.addView(del);
            subList.addView(row);
        }
    }

    private void subsChanged() {
        AppState.servers = new ArrayList<>();
        AppState.pings.clear();
        prefs.resetSubUpdated();
        renderSubs();
        reconnect();
    }

    private TextView shareInfo;
    private LinearLayout shareButtons;

    private String shareText() {
        java.util.List<String> ips = ShareInfo.list();
        String host = ips.isEmpty() ? "<адрес телефона>" : ips.get(0);
        return "Прокси (SOCKS5 или HTTP): " + host + ":" + Prefs.SHARE_PORT
                + "\nЛогин: " + prefs.shareUser() + "\nПароль: " + prefs.sharePass();
    }

    private void renderShare() {
        if (shareInfo == null) return;
        boolean on = prefs.shareProxy();
        shareButtons.setVisibility(on ? View.VISIBLE : View.GONE);
        if (!on) {
            shareInfo.setText("Android не пускает трафик точки доступа в VPN без root. "
                    + "Поэтому Dash поднимает прокси: на подключённом устройстве его нужно указать в настройках Wi-Fi.");
            return;
        }
        String where = ShareInfo.list().isEmpty()
                ? "Включите точку доступа — здесь появится адрес.\n\n" : "";
        shareInfo.setText(where + shareText()
                + "\n\nНа устройстве: Wi-Fi → эта сеть → Прокси → Вручную, адрес и порт выше. "
                + "Логин и пароль спросит браузер. UDP (игры, звонки) работает только через SOCKS5.");
    }

    private interface Toggle { void set(boolean v); }

    private View switchRow(Context c, String title, String sub, boolean value, final Toggle t) {
        LinearLayout row = Ui.row(c);
        LinearLayout text = new LinearLayout(c);
        text.setOrientation(LinearLayout.VERTICAL);
        text.addView(Ui.text(c, title, 15, Ui.FG, true));
        if (sub != null) text.addView(Ui.text(c, sub, 13, Ui.MUTED, false));
        row.setMinimumHeight(Ui.dp(c, 44));
        row.addView(text, Ui.weight());
        Switch sw = new Switch(c);
        sw.setChecked(value);
        sw.setContentDescription(title);
        sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean v) { t.set(v); }
        });
        row.addView(sw);
        return row;
    }

    private TextView segButton(Context c, String label) {
        TextView t = Ui.text(c, label, 13, Ui.MUTED, true);
        t.setGravity(android.view.Gravity.CENTER);
        t.setMinHeight(Ui.dp(c, 40));
        t.setPadding(Ui.dp(c, 6), 0, Ui.dp(c, 6), 0);
        return t;
    }

    private void setMode(String m) {
        if (m.equals(prefs.mode())) return;
        prefs.mode(m);
        renderMode();
        if (AppState.vpn != AppState.OFF) {
            Toast.makeText(this, "Применится при следующей проверке", Toast.LENGTH_SHORT).show();
        }
    }

    private void renderMode() {
        boolean wl = Prefs.MODE_WL.equals(prefs.mode());
        modeWl.setBackground(wl ? Ui.round(this, Ui.SURFACE, 9, Ui.LINE2) : null);
        modeNet.setBackground(!wl ? Ui.round(this, Ui.SURFACE, 9, Ui.LINE2) : null);
        modeWl.setTextColor(wl ? Ui.FG : Ui.MUTED);
        modeNet.setTextColor(!wl ? Ui.FG : Ui.MUTED);
        modeHint.setText(wl ? "Белые списки → LTE" : "Мобильная сеть → LTE, Wi-Fi → обычные");
    }

    private void reconnect() {
        if (AppState.vpn != AppState.OFF) {
            startService(new Intent(this, BoxVpnService.class).setAction(BoxVpnService.ACTION_STOP));
            final Context app = getApplicationContext();
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override public void run() {
                    app.startForegroundService(new Intent(app, BoxVpnService.class).setAction(BoxVpnService.ACTION_START));
                }
            }, 3000);
            Toast.makeText(this, "Переподключение…", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show();
        }
        finish();
    }
}
