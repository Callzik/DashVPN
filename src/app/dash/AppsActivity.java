package app.dash;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Pick apps that bypass the VPN (banks, Gosuslugi, games …) or, with EXTRA_LOCK, apps that must never go online without it. */
public class AppsActivity extends Activity {
    static final String EXTRA_LOCK = "lock";
    private static final int REQ_VPN = 1;

    private static final class App {
        String pkg, label;
        Drawable icon;
    }

    private Prefs prefs;
    private boolean lockMode;
    private Set<String> selected;
    private Set<String> initial;
    /** The other list: an app can bypass the VPN or be locked to it, not both. */
    private Set<String> other;
    private Set<String> otherInitial;
    private String pendingPkg;
    private final List<App> all = new ArrayList<>();
    private final List<App> shown = new ArrayList<>();
    private Adapter adapter;
    private TextView countText, loading;
    private String filter = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        lockMode = getIntent().getBooleanExtra(EXTRA_LOCK, false);
        selected = lockMode ? prefs.lockedApps() : prefs.excludedApps();
        initial = new java.util.HashSet<>(selected);
        other = lockMode ? prefs.excludedApps() : prefs.lockedApps();
        otherInitial = new java.util.HashSet<>(other);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        Context c = this;

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(Ui.BG);
        col.setPadding(Ui.dp(c, 20), Ui.dp(c, 16), Ui.dp(c, 20), 0);
        setContentView(col);

        LinearLayout top = Ui.row(c);
        TextView back = Ui.text(c, "←", 24, Ui.FG, false);
        back.setPadding(Ui.dp(c, 4), Ui.dp(c, 4), Ui.dp(c, 16), Ui.dp(c, 4));
        back.setContentDescription("Назад");
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        top.addView(back);
        top.addView(Ui.text(c, lockMode ? "Только через VPN" : "Приложения без VPN", 22, Ui.FG, true));
        col.addView(top);

        TextView hint = Ui.text(c, lockMode
                ? "Пока VPN выключен, у отмеченных приложений нет интернета."
                : "Отмеченные приложения работают напрямую, мимо VPN. Пригодится для банков, Госуслуг и игр.", 13, Ui.MUTED, false);
        col.addView(hint, Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 10));
        if (lockMode) {
            // after a reboot the lock comes back by itself a few seconds later; always-on VPN closes even that gap
            TextView always = Ui.text(c, "Постоянная VPN ›", 13, Ui.ACCENT, true);
            always.setPadding(0, Ui.dp(c, 6), 0, Ui.dp(c, 2));
            always.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    try {
                        startActivity(new Intent(android.provider.Settings.ACTION_VPN_SETTINGS));
                    } catch (Exception e) {
                        Toast.makeText(AppsActivity.this, "Настройки → Подключения → VPN", Toast.LENGTH_LONG).show();
                    }
                }
            });
            col.addView(always, Ui.lp(c, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 4));
        }

        EditText search = new EditText(c);
        search.setHint("Поиск");
        search.setSingleLine(true);
        search.setTextColor(Ui.FG);
        search.setHintTextColor(0xFF8E8AA3);
        search.setTextSize(15);
        search.setBackground(Ui.round(c, Ui.SURFACE2, 12, 0));
        search.setPadding(Ui.dp(c, 12), Ui.dp(c, 10), Ui.dp(c, 12), Ui.dp(c, 10));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b2, int c2) {}
            @Override public void onTextChanged(CharSequence s, int a, int b2, int c2) {}
            @Override public void afterTextChanged(Editable s) {
                filter = s.toString().trim().toLowerCase(Locale.ROOT);
                applyFilter();
            }
        });
        col.addView(search, Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 12));

        countText = Ui.text(c, "", 13, Ui.MUTED, false);
        col.addView(countText, Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 10));

        loading = Ui.text(c, "Загрузка списка приложений…", 14, Ui.MUTED, false);
        loading.setGravity(Gravity.CENTER);
        col.addView(loading, Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 24));

        ListView list = new ListView(c);
        list.setDivider(null);
        list.setSelector(new android.graphics.drawable.ColorDrawable(0));
        adapter = new Adapter();
        list.setAdapter(adapter);
        col.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        updateCount();
        load();
    }

    private void load() {
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                PackageManager pm = app.getPackageManager();
                Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
                List<ResolveInfo> ris = pm.queryIntentActivities(launcher, 0);
                Map<String, App> byPkg = new HashMap<>();
                for (ResolveInfo ri : ris) {
                    String pkg = ri.activityInfo.packageName;
                    if (pkg.equals(app.getPackageName()) || byPkg.containsKey(pkg)) continue;
                    App a = new App();
                    a.pkg = pkg;
                    a.label = String.valueOf(ri.loadLabel(pm));
                    try {
                        a.icon = ri.loadIcon(pm);
                    } catch (Exception ignored) {
                    }
                    byPkg.put(pkg, a);
                }
                final List<App> res = new ArrayList<>(byPkg.values());
                final Collator coll = Collator.getInstance(new Locale("ru"));
                Collections.sort(res, new Comparator<App>() {
                    @Override public int compare(App x, App y) {
                        boolean sx = selected.contains(x.pkg), sy = selected.contains(y.pkg);
                        if (sx != sy) return sx ? -1 : 1;
                        return coll.compare(x.label, y.label);
                    }
                });
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override public void run() {
                        all.clear();
                        all.addAll(res);
                        loading.setVisibility(View.GONE);
                        applyFilter();
                    }
                });
            }
        }, "apps").start();
    }

    private void applyFilter() {
        shown.clear();
        for (App a : all) {
            if (filter.isEmpty() || a.label.toLowerCase(Locale.ROOT).contains(filter)
                    || a.pkg.toLowerCase(Locale.ROOT).contains(filter)) shown.add(a);
        }
        adapter.notifyDataSetChanged();
    }

    private void updateCount() {
        if (lockMode) countText.setText(selected.isEmpty() ? "Ничего не выбрано" : "Закрыто без VPN: " + selected.size());
        else countText.setText(selected.isEmpty() ? "Все приложения идут через VPN" : "Без VPN: " + selected.size());
    }

    private void pick(App a, boolean on) {
        if (on) {
            selected.add(a.pkg);
            other.remove(a.pkg);
            // the lock is a VPN of its own: it needs the permission even if Dash was never connected
            if (lockMode && pendingPkg == null) {
                Intent ask = android.net.VpnService.prepare(this);
                if (ask != null) {
                    pendingPkg = a.pkg;
                    try {
                        startActivityForResult(ask, REQ_VPN);
                    } catch (Exception e) {
                        onActivityResult(REQ_VPN, RESULT_CANCELED, null);
                    }
                }
            }
        } else {
            selected.remove(a.pkg);
        }
        updateCount();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req != REQ_VPN) return;
        if (res != RESULT_OK && pendingPkg != null) {
            selected.remove(pendingPkg);
            Toast.makeText(this, "Без разрешения на VPN закрыть приложение нельзя", Toast.LENGTH_LONG).show();
            adapter.notifyDataSetChanged();
            updateCount();
        }
        pendingPkg = null;
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (pendingPkg != null) return; // the permission dialog is on top; saved when the user comes back and leaves
        if (lockMode) {
            prefs.lockedApps(selected);
            prefs.excludedApps(other);
        } else {
            prefs.excludedApps(selected);
            prefs.lockedApps(other);
        }
        if (!isFinishing()) return;
        Set<String> locked = lockMode ? selected : other;
        boolean lockChanged = !(lockMode ? initial : otherInitial).equals(locked);
        boolean bypassChanged = !(lockMode ? otherInitial : initial).equals(lockMode ? other : selected);
        // while the VPN is on everything already goes through it; the lock matters once it is off
        if (lockChanged && AppState.vpn == AppState.OFF) {
            try {
                startForegroundService(new Intent(this, BoxVpnService.class).setAction(BoxVpnService.ACTION_LOCK));
            } catch (Exception e) {
                AppState.log("Замок: " + e.getMessage());
            }
        }
        if (bypassChanged) {
            if (AppState.vpn != AppState.OFF) {
                final Context app = getApplicationContext();
                VpnControl.stop(app);
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override public void run() {
                        try {
                            VpnControl.start(app);
                        } catch (Exception e) {
                            AppState.log("Не удалось переподключиться: " + e.getMessage());
                        }
                    }
                }, 3000);
                Toast.makeText(this, "Переподключение для применения", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int i) { return shown.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            Context c = parent.getContext();
            Row r;
            if (convert == null) {
                r = new Row(c);
                convert = r.root;
                convert.setTag(r);
            } else {
                r = (Row) convert.getTag();
            }
            final App a = shown.get(pos);
            r.icon.setImageDrawable(a.icon);
            r.label.setText(a.label);
            r.pkg.setText(a.pkg);
            r.check.setOnCheckedChangeListener(null);
            r.check.setChecked(selected.contains(a.pkg));
            final CheckBox cb = r.check;
            cb.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean v) {
                    pick(a, v);
                }
            });
            r.root.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { cb.toggle(); }
            });
            return convert;
        }
    }

    private static final class Row {
        final LinearLayout root;
        final ImageView icon;
        final TextView label, pkg;
        final CheckBox check;

        Row(Context c) {
            root = Ui.row(c);
            root.setPadding(0, Ui.dp(c, 8), 0, Ui.dp(c, 8));
            root.setMinimumHeight(Ui.dp(c, 56));
            icon = new ImageView(c);
            root.addView(icon, new LinearLayout.LayoutParams(Ui.dp(c, 36), Ui.dp(c, 36)));
            LinearLayout text = new LinearLayout(c);
            text.setOrientation(LinearLayout.VERTICAL);
            text.setPadding(Ui.dp(c, 14), 0, Ui.dp(c, 8), 0);
            label = Ui.text(c, "", 15, Ui.FG, true);
            label.setSingleLine(true);
            pkg = Ui.text(c, "", 12, Ui.MUTED, false);
            pkg.setSingleLine(true);
            text.addView(label);
            text.addView(pkg);
            root.addView(text, Ui.weight());
            check = new CheckBox(c);
            check.setFocusable(false);
            root.addView(check);
        }
    }
}
