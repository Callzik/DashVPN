package app.dash;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class BoxVpnService extends VpnService {
    public static final String ACTION_START = "app.dash.START";
    public static final String ACTION_STOP = "app.dash.STOP";
    public static final String ACTION_PAUSE = "app.dash.PAUSE";
    public static final String ACTION_SWITCH = "app.dash.SWITCH";
    /** Re-read the locked apps: hold, update or drop the lock while the VPN is off. */
    public static final String ACTION_LOCK = "app.dash.LOCK";
    private static final long PAUSE_MS = 5 * 60 * 1000L;
    private static final String CHANNEL = "vpn";
    private static final String CHANNEL_LOCK = "lock";
    private static final String CHANNEL_ALERT = "alert";
    static final int NOTIFY_ID = 1;
    private static final int ALERT_ID = 3;

    private volatile boolean running;
    private volatile boolean stopping;
    private ScheduledExecutorService exec;
    private ScheduledFuture<?> pendingReeval;
    private ScheduledFuture<?> periodic;
    private ScheduledFuture<?> statusTask;

    private ParcelFileDescriptor tunPfd;
    /** Lock: an interface that takes only the locked apps and never passes a packet on. */
    private ParcelFileDescriptor lockPfd;
    private int lockedCount;
    private volatile boolean revoked, destroying;
    private volatile Process helper;
    private volatile Process xrayProc;
    private String xrayConfig;
    private OutputStream helperIn;
    private LocalSocket bindSocket;
    private LocalServerSocket server;
    private String sockPath;

    private ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback defaultCb;
    private ConnectivityManager.NetworkCallback allCb;
    private volatile Network defaultNet;
    private volatile Network underlying;
    private String lastIfaceLine = "";

    private Prefs prefs;
    private Clash clash;

    /** Clash API of the running core, null when stopped. */
    Clash clashApi() { return running ? clash : null; }
    private List<Server> servers = new ArrayList<>();
    private final Map<String, Server> byTag = new HashMap<>();
    private boolean hasRegular, hasLte;
    private volatile String activeGroup = "";
    static volatile BoxVpnService instance;

    /* ---------- lifecycle ---------- */

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (prefs == null) prefs = new Prefs(this);
        String action = intent == null ? null : intent.getAction();
        // restarted by the system after its process was killed: bring back what was there
        if (action == null) action = !running && prefs.lockIdle() ? ACTION_LOCK : ACTION_START;
        if (ACTION_LOCK.equals(action)) {
            refreshLock();
            return START_STICKY;
        }
        if (ACTION_PAUSE.equals(action)) {
            pause();
            return START_STICKY;
        }
        if (ACTION_SWITCH.equals(action)) {
            if (running) switchNext();
            else if (AppState.pausedUntil == 0 && lockPfd == null) stopSelf();
            return START_STICKY;
        }
        cancelPause();
        if (ACTION_STOP.equals(action)) {
            boolean wasRunning = running;
            stopVpn("Отключено");
            if (AppState.vpn != AppState.OFF) {
                AppState.vpn = AppState.OFF;
                AppState.changed();
            }
            if (!wasRunning) idle(); // stopVpn already did it otherwise
            return lockPfd == null ? START_NOT_STICKY : START_STICKY;
        }
        startForeground(NOTIFY_ID, notification("Подключение…"));
        if (!running) startVpn();
        return START_STICKY;
    }

    @Override
    public void onRevoke() {
        // another VPN took over or the user turned Dash off in system settings: the lock cannot stay
        revoked = true;
        boolean wasLocked = lockPfd != null || running && !prefs.lockedApps().isEmpty();
        unlock();
        cancelPause();
        if (running) stopVpn("VPN отключён системой");
        else idle();
        if (wasLocked) alertUnlocked();
    }

    @Override
    public void onDestroy() {
        instance = null;
        destroying = true;
        cancelPause();
        unlock();
        stopVpn(null);
        super.onDestroy();
    }

    private void startVpn() {
        running = true;
        stopping = false;
        revoked = false;
        prefs = new Prefs(this);
        prefs.lockIdle(false);
        // the tunnel takes a few seconds: until then the locked apps stay closed
        lock();
        instance = this;
        VpnControl.init(this);
        cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        exec = Executors.newSingleThreadScheduledExecutor();
        startTicker();
        AppState.vpn = AppState.CONNECTING;
        AppState.error = "";
        AppState.phase = "Загрузка подписки";
        AppState.serverName = "";
        AppState.ping = -1;
        AppState.changed();
        exec.execute(new Runnable() {
            @Override public void run() {
                try {
                    boot();
                } catch (Throwable t) {
                    AppState.log("Ошибка запуска: " + t);
                    fail(t.getMessage() == null ? t.toString() : t.getMessage());
                }
            }
        });
    }

    private void fail(String message) {
        if (!running) return; // stopped by the user: not an error
        AppState.error = message;
        stopVpn(null);
        AppState.error = message;
        AppState.changed();
    }

    /** State writes from background tasks: ignored once the VPN was stopped. */
    private synchronized boolean setVpn(int s) {
        if (!running) return false;
        AppState.vpn = s;
        return true;
    }

    private void stopVpn(String reason) {
        stopVpn(reason, false);
    }

    /** keepService: the pause, the service stays in the foreground with its notification. */
    private synchronized void stopVpn(String reason, boolean keepService) {
        if (!running) return;
        running = false;
        stopping = true;
        if (reason != null) AppState.log(reason);
        if (exec != null) exec.shutdownNow();
        if (ticker != null) ticker.shutdownNow();
        if (helperIn != null) {
            try {
                helperIn.write("stop\n".getBytes());
                helperIn.flush();
            } catch (Exception ignored) {
            }
        }
        final Process p = helper;
        if (p != null) {
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        Thread.sleep(2500);
                    } catch (InterruptedException ignored) {
                    }
                    p.destroy();
                }
            }).start();
        }
        helper = null;
        helperIn = null;
        final Process xp = xrayProc;
        xrayProc = null;
        if (xp != null) xp.destroy(); // no waiting here: this runs on the main thread
        closeServer();
        // the lock replaces the tunnel in place, so the locked apps see no gap between the two
        if (!destroying && !revoked) lock();
        if (tunPfd != null) {
            try {
                tunPfd.close();
            } catch (Exception ignored) {
            }
            tunPfd = null;
        }
        unregisterNetwork();
        AppState.vpn = AppState.OFF;
        AppState.regularBlocked = false;
        AppState.switching = false;
        AppState.serverName = "";
        AppState.ping = -1;
        AppState.changed();
        if (keepService) return;
        idle();
    }

    /* ---------- lock: apps that must never go online without the VPN ---------- */

    /**
     * Holds an interface for the locked apps only and never reads it: their packets go nowhere,
     * other apps keep the plain network. Replaces the previous interface of this service in place.
     * false when there is nothing to lock (no apps, no VPN permission) — any old lock is dropped then.
     */
    private synchronized boolean lock() {
        if (prefs == null) prefs = new Prefs(this);
        Set<String> apps = prefs.lockedApps();
        if (apps.isEmpty() || destroying || revoked || VpnService.prepare(this) != null) {
            unlock();
            return false;
        }
        Builder b = new Builder();
        b.setSession(getString(R.string.app_name));
        b.setMtu(1500);
        b.addAddress("172.19.0.1", 30);
        b.addAddress("fdfe:dcba:9876::1", 126);
        b.addRoute("0.0.0.0", 0);
        b.addRoute("::", 0);
        b.addDnsServer("172.19.0.2");
        int n = 0;
        for (String pkg : apps) {
            if (pkg.equals(getPackageName())) continue;
            try {
                b.addAllowedApplication(pkg);
                n++;
            } catch (Exception ignored) {
                // app was uninstalled
            }
        }
        // with no allowed app at all the interface would take every app
        if (n == 0) {
            unlock();
            return false;
        }
        if (Build.VERSION.SDK_INT >= 29) b.setMetered(false);
        Intent open = new Intent(this, MainActivity.class);
        b.setConfigureIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE));
        ParcelFileDescriptor fd = null;
        try {
            fd = b.establish();
        } catch (Exception e) {
            AppState.log("Замок: " + e.getMessage());
        }
        if (fd == null) {
            AppState.log("Замок: система не дала создать VPN");
            unlock();
            return false;
        }
        // no underlying network: the locked apps see a connection that is down, not a slow one
        setUnderlyingNetworks(new Network[0]);
        ParcelFileDescriptor old = lockPfd;
        lockPfd = fd;
        if (old != null) {
            try {
                old.close();
            } catch (Exception ignored) {
            }
        }
        if (n != lockedCount) AppState.log("Только через VPN: " + n + " прил.");
        lockedCount = n;
        return true;
    }

    private synchronized void unlock() {
        if (lockPfd == null) return;
        try {
            lockPfd.close();
        } catch (Exception ignored) {
        }
        lockPfd = null;
        lockedCount = 0;
    }

    /** VPN off and not paused: stay in the foreground with the lock, or go away. */
    private void idle() {
        if (lockPfd != null && !destroying && !revoked) {
            prefs.lockIdle(true);
            startForeground(NOTIFY_ID, lockNotification());
        } else {
            if (prefs != null && !destroying) prefs.lockIdle(false);
            stopForeground(true);
            stopSelf();
        }
    }

    private void refreshLock() {
        revoked = false;
        if (running) {
            // started with startForegroundService: the notification has to be posted again
            synchronized (this) {
                lastNotif = "";
                if (tunPfd == null) lock();
            }
            startForeground(NOTIFY_ID, notification("Подключение…"));
            return;
        }
        if (AppState.pausedUntil > 0) {
            startForeground(NOTIFY_ID, pauseNotification());
            lock();
            return;
        }
        startForeground(NOTIFY_ID, lockNotification());
        lock();
        idle();
    }

    private Notification lockNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CHANNEL_LOCK) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_LOCK, "Только через VPN", NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(this, MainActivity.class);
        return new Notification.Builder(this, CHANNEL_LOCK)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("VPN выключен")
                .setContentText(lockedCount > 0 ? "Закрыто приложений: " + lockedCount : "Приложения закрыты")
                .setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE))
                .setOngoing(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(action("Подключить", ACTION_START, 5))
                .build();
    }

    private void alertUnlocked() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CHANNEL_ALERT) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_ALERT, "Предупреждения", NotificationManager.IMPORTANCE_HIGH));
        }
        Intent open = new Intent(this, MainActivity.class);
        nm.notify(ALERT_ID, new Notification.Builder(this, CHANNEL_ALERT)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Замок снят")
                .setContentText("VPN занял другой — закрытые приложения видят сеть")
                .setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE))
                .setAutoCancel(true)
                .build());
    }

    /* ---------- boot ---------- */

    private static final long SUB_REFRESH_MS = 12 * 3600 * 1000L;
    private static final long SUB_CHECK_EVERY_MIN = 30;
    private ScheduledFuture<?> subTask;
    private File rulesDir;
    private String serversSig = "";

    private void boot() throws Exception {
        rulesDir = new File(getFilesDir(), "rules");
        copyRules(rulesDir);

        // Subscriptions
        List<String> urls = prefs.subUrls();
        if (urls.isEmpty()) throw new Exception("Добавьте ссылку на подписку");
        // White lists are checked while the subscription loads: both only wait for the network
        registerNetwork();
        Thread wl = new Thread(new Runnable() {
            @Override public void run() { probeWl(); }
        }, "wl-probe");
        wl.start();
        List<Subs.Entry> entries = savedSubs(urls);
        final boolean fromCache = entries != null;
        if (fromCache) {
            AppState.log("Подписка обновлялась " + (System.currentTimeMillis() - prefs.subUpdated()) / 60000
                    + " мин назад — используется сохранённая копия, обновление в фоне");
        } else {
            AppState.phase = urls.size() > 1 ? "Загрузка подписок (" + urls.size() + ")" : "Загрузка подписки";
            AppState.changed();
            entries = loadSubs(urls);
        }
        if (!running) return;
        List<String> warnings = new ArrayList<>();
        List<String> stubs = new ArrayList<>();
        List<Server> merged = Subs.arrange(Subs.merge(entries, warnings, stubs), prefs);
        for (String st : stubs) AppState.log("Вместо серверов заглушка — " + st);
        int usable = 0;
        for (Server s : merged) if (s.group != Server.EXCLUDED) usable++;
        if (!merged.isEmpty() && usable == 0) throw new Exception("Все серверы отключены. Включите хотя бы один в списке серверов");
        if (merged.isEmpty()) {
            if (!stubs.isEmpty()) throw new Exception("Сервис подписки вместо серверов прислал заглушку: " + stubs.get(0));
            throw new Exception("Не удалось скачать подписку. Проверьте ссылку и интернет");
        }
        if (!fromCache) prefs.markSubUpdated();
        applyServers(merged, warnings);
        if (!running) return;

        if (wl.isAlive()) {
            AppState.phase = "Проверка белых списков";
            AppState.changed();
            wl.join();
        }
        if (!running) return;

        startServer();
        startCore();
        boolean anyHttp = false;
        for (String u : urls) anyHttp |= Subs.isHttp(u);
        if (anyHttp) {
            subTask = exec.scheduleWithFixedDelay(new Runnable() {
                @Override public void run() { safeCheckSub(); }
            }, SUB_CHECK_EVERY_MIN, SUB_CHECK_EVERY_MIN, TimeUnit.MINUTES);
            if (fromCache && AppState.vpn == AppState.WAITING && AppState.wl != AppState.WL_NONET) {
                // the saved servers do not answer: maybe the subscription changed, download it now
                AppState.log("Серверы из сохранённой подписки не отвечают — загрузка свежей");
                prefs.resetSubUpdated();
                exec.execute(new Runnable() {
                    @Override public void run() { safeCheckSub(); }
                });
            }
        }
    }

    /**
     * Saved copies of all subscriptions when they were downloaded less than SUB_REFRESH_MS ago, else null.
     * Connecting then does not wait for the download; the periodic check refreshes them.
     * Adding, removing or manually refreshing a subscription resets the time, so those always download.
     */
    private List<Subs.Entry> savedSubs(List<String> urls) {
        long age = System.currentTimeMillis() - prefs.subUpdated();
        if (age < 0 || age >= SUB_REFRESH_MS) return null;
        List<Subs.Entry> out = new ArrayList<>();
        for (String url : urls) {
            Subs.Entry e = new Subs.Entry();
            e.url = url;
            e.name = Subs.label(prefs, url);
            e.body = Subs.isHttp(url) ? prefs.subCache(url) : url;
            if (e.body.isEmpty()) return null;
            if (Subs.isHttp(url) && !prefs.hasSubInfo(url)) return null; // saved before traffic info was kept
            out.add(e);
        }
        return out;
    }

    private static boolean isHttp(String url) {
        return url.startsWith("http://") || url.startsWith("https://");
    }

    /**
     * Downloads all subscriptions at once. A link that fails (or answers with a stub while a good copy is saved)
     * falls back to its saved copy, so one broken subscription never breaks the others.
     */
    private List<Subs.Entry> loadSubs(List<String> urls) {
        final List<Subs.Entry> out = java.util.Collections.synchronizedList(new ArrayList<Subs.Entry>());
        List<Thread> threads = new ArrayList<>();
        for (final String url : urls) {
            if (!Subs.isHttp(url)) {
                Subs.Entry e = new Subs.Entry();
                e.url = url;
                e.name = Subs.label(prefs, url);
                e.body = url; // a single key or a pasted list
                out.add(e);
                continue;
            }
            Thread t = new Thread(new Runnable() {
                @Override public void run() {
                    Subs.Entry e = new Subs.Entry();
                    e.url = url;
                    try {
                        String[] title = new String[2];
                        String body = fetch(url, title);
                        if (title[0] != null && !title[0].isEmpty()) prefs.subName(url, title[0]);
                        prefs.subInfo(url, title[1] == null ? "" : title[1]);
                        e.name = Subs.label(prefs, url);
                        boolean stub = stubReason(SubParser.parse(body, new ArrayList<String>())) != null;
                        String cache = prefs.subCache(url);
                        if (stub && !cache.isEmpty()) {
                            AppState.log(e.name + ": вместо серверов заглушка, используется сохранённая копия");
                            body = cache;
                        } else if (!stub) {
                            prefs.subCache(url, body);
                            AppState.log(e.name + ": подписка загружена");
                        }
                        e.body = body;
                        out.add(e);
                    } catch (Exception ex) {
                        if (!prefs.hasSubInfo(url)) prefs.subInfo(url, ""); // tried: do not hold every connect for it
                        e.name = Subs.label(prefs, url);
                        String cache = prefs.subCache(url);
                        AppState.log(e.name + ": не удалось скачать (" + ex.getMessage() + ")"
                                + (cache.isEmpty() ? "" : ", используется сохранённая копия"));
                        if (!cache.isEmpty()) {
                            e.body = cache;
                            out.add(e);
                        }
                    }
                }
            }, "sub-fetch");
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) {
            try {
                t.join(30000);
            } catch (InterruptedException ignored) {
                break;
            }
        }
        // keep the order the user added them in
        List<Subs.Entry> sorted = new ArrayList<>();
        synchronized (out) {
            for (String u : urls) for (Subs.Entry e : out) if (e.url.equals(u)) { sorted.add(e); break; }
        }
        SubReminder.check(this, prefs); // fresh end dates
        return sorted;
    }

    private static String signature(List<Server> list) {
        StringBuilder sb = new StringBuilder();
        for (Server s : list) {
            sb.append(s.group).append('|').append(s.name).append('|').append(Json.write(s.outbound));
            if (s.xray != null) sb.append('|').append(Json.write(s.xray));
            sb.append('\n');
        }
        return sb.toString();
    }

    private void applyServers(List<Server> parsed, List<String> warnings) throws Exception {
        for (String w : warnings) AppState.log(w);
        int reg = 0, lte = 0, ex = 0;
        for (Server s : parsed) {
            if (s.group == Server.REGULAR) { reg++; if (s.both) lte++; }
            else if (s.group == Server.LTE) lte++;
            else ex++;
        }
        if (reg == 0 && lte == 0) throw new Exception("В подписке нет серверов, которые умеет ядро");
        servers = parsed;
        AppState.servers = parsed;
        AppState.pings.clear();
        byTag.clear();
        for (Server s : servers) byTag.put(s.tag, s);
        hasRegular = reg > 0;
        hasLte = lte > 0;
        serversSig = signature(servers);
        java.util.Set<String> subNames = new java.util.LinkedHashSet<>();
        for (Server s : servers) subNames.add(s.sub);
        AppState.lastServers = "Серверов: " + servers.size() + " · обычных " + reg + ", LTE " + lte + ", не участвуют " + ex
                + (subNames.size() > 1 ? " · подписок " + subNames.size() : "");
        AppState.log(AppState.lastServers);
        for (Server s : servers) AppState.log(describe(s));
    }

    /** Builds the config, starts the core, picks a server and schedules the periodic checks. */
    private void startCore() throws Exception {
        String group = desiredGroup();
        activeGroup = group;

        String secret = randomHex(16);
        clash = new Clash(secret);
        autoTag = null;
        ConfigBuilder.Options opt = new ConfigBuilder.Options();
        opt.ruDirect = prefs.ruDirect();
        opt.blockedViaVpn = prefs.blockedVpn();
        opt.ruleDir = rulesDir.getAbsolutePath();
        opt.secret = secret;
        opt.initialGroup = group;
        if (prefs.shareProxy()) {
            opt.sharePort = Prefs.SHARE_PORT;
            opt.shareUser = prefs.shareUser();
            opt.sharePass = prefs.sharePass();
            AppState.log("Раздача: прокси на порту " + Prefs.SHARE_PORT + " (" + ShareInfo.addresses() + ")");
        }
        prepareXray();
        String config = ConfigBuilder.build(servers, opt);
        File cfg = new File(getFilesDir(), "config.json");
        FileOutputStream fo = new FileOutputStream(cfg);
        fo.write(config.getBytes("UTF-8"));
        fo.close();

        AppState.phase = "Запуск ядра";
        AppState.changed();
        if (!running) return;
        startXray();
        startHelper(cfg);
        long deadline = System.currentTimeMillis() + 25000;
        while (running && !clash.alive()) {
            if (helper == null) throw new Exception("Ядро не запустилось, подробности в журнале");
            try {
                helper.exitValue();
                throw new Exception("Ядро завершилось при запуске, подробности в журнале");
            } catch (IllegalThreadStateException stillRunning) {
                // keep waiting
            }
            if (System.currentTimeMillis() > deadline) throw new Exception("Ядро не отвечает");
            Thread.sleep(100);
        }
        if (!running) return;

        // Does the core itself reach the internet? Separates "core has no network" from "servers fail".
        new Thread(new Runnable() {
            @Override public void run() {
                AppState.log("Ядро, прямой доступ: ya.ru " + directCheck("https://ya.ru/") + ", gstatic.com " + directCheck(ConfigBuilder.TEST_URL));
            }
        }, "direct-check").start();

        AppState.phase = "Поиск самого быстрого сервера (" + groupLabel(group) + ")";
        AppState.changed();
        String working = ensureWorkingGroup(group, true);
        if (working == null) {
            if (!setVpn(AppState.WAITING)) return;
            AppState.phase = AppState.wl == AppState.WL_NONET
                    ? "Нет интернета. Подключение после появления сети"
                    : "Ни один сервер не отвечает. Повторная попытка через несколько секунд";
        } else {
            if (!setVpn(AppState.ON)) return;
            AppState.since = System.currentTimeMillis();
            String pn = prefs.pinned();
            if (!pn.isEmpty()) {
                if (!applyPin(pn)) prefs.pinned("");
            }
            laterSpeedCheck();
        }
        refreshStatus();
        AppState.changed();

        periodic = exec.scheduleWithFixedDelay(new Runnable() {
            @Override public void run() { safeReevaluate("periodic"); }
        }, 120, 120, TimeUnit.SECONDS);
        if (working == null) scheduleReevaluate("retry", 15000);
        statusTask = exec.scheduleWithFixedDelay(new Runnable() {
            @Override public void run() {
                try {
                    refreshStatus();
                } catch (Throwable ignored) {
                }
            }
        }, 5, 5, TimeUnit.SECONDS);
        deadCount = 0;
        watchdog = exec.scheduleWithFixedDelay(new Runnable() {
            @Override public void run() {
                try {
                    watch();
                } catch (Throwable ignored) {
                }
            }
        }, 20, 20, TimeUnit.SECONDS);
    }

    /* ---------- watchdog: notice a dead server in seconds, not at the next 3-minute check ---------- */

    private ScheduledFuture<?> watchdog;
    private long lastRegularTry;
    private int deadCount;

    private void watch() throws Exception {
        if (!running || clash == null || AppState.vpn != AppState.ON || AppState.switching) return;
        String pt = pinnedTag();
        if (pt != null) {
            watchPinned(pt);
            return;
        }
        String group = activeGroup;
        String auto = autoTag;
        if (auto != null) {
            watchAuto(group, auto);
            return;
        }
        String tag = clash.now(group);
        if (tag.isEmpty()) return;
        int d = clash.proxyDelay(tag, ConfigBuilder.TEST_URL, 5000);
        if (d > 0) {
            deadCount = 0;
            AppState.ping = d;
            AppState.changed();
            maybeLoadCheck(tag);
            if (d >= SLOW_MS && System.currentTimeMillis() - lastFasterCheck > FASTER_CHECK_EVERY_MS) {
                lastFasterCheck = System.currentTimeMillis();
                preferFaster(group, clash.testGroup(group, 5000));
            }
            return;
        }
        deadCount++;
        Server s = byTag.get(tag);
        AppState.log("Сервер " + (s != null ? s.name : tag) + " не ответил (" + deadCount + ")");
        if (deadCount < 2) return;
        deadCount = 0;
        // let the group re-test everything and move to the best live server
        Map<String, Integer> res = clash.groupCheck(group, 5000);
        String now = clash.now(group);
        if (res.isEmpty()) {
            Map<String, Integer> mine = clash.testGroup(group, 4000);
            if (mine.isEmpty()) {
                safeReevaluate("dead");
                return;
            }
            clash.groupCheck(group, 5000);
            now = clash.now(group);
        }
        refreshStatus();
        if (!now.equals(tag)) {
            try {
                clash.closeAll(); // connections stuck on the dead server: reopen through the new one
            } catch (Exception ignored) {
            }
            Server n = byTag.get(now);
            AppState.banner("Сервер перестал отвечать — переключено на " + (n != null ? n.name : now), 2);
        }
    }

    /* ---------- load check: a server can answer pings and still stall every real download ---------- */

    private static final long LOAD_EVERY_MS = 5 * 60 * 1000L;
    /** How long a server that failed the load check is left out of the automatic choice. */
    private static final long FROZEN_MS = 15 * 60 * 1000L;
    private final Map<String, Long> frozen = new ConcurrentHashMap<>();
    private volatile boolean loadBusy;
    private volatile int loadFails;
    private volatile long lastLoad;
    private String lastLoadTag = "";

    /** Servers that failed the load check recently. */
    private Set<String> frozenNow() {
        long now = System.currentTimeMillis();
        Set<String> out = new HashSet<>();
        for (Map.Entry<String, Long> e : frozen.entrySet()) {
            if (e.getValue() > now) out.add(e.getKey());
            else frozen.remove(e.getKey());
        }
        return out;
    }

    /**
     * Called by the watchdog after the server in use answered (a manual pin is left alone). Every few minutes,
     * and right after the server changes, a small file has to come through it in full. After two failures in a
     * row the server is left out for a while and traffic moves to the best server that passes the same check.
     */
    private void maybeLoadCheck(final String tag) {
        if (loadBusy || speedBusy) return;
        long now = System.currentTimeMillis();
        if (tag.equals(lastLoadTag) && loadFails == 0 && now - lastLoad < LOAD_EVERY_MS) return;
        lastLoad = now;
        lastLoadTag = tag;
        loadBusy = true;
        final Clash c = clash;
        final String group = activeGroup;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    loadCheck(c, group, tag);
                } catch (Throwable t) {
                    AppState.log("Проверка загрузки: " + t.getMessage());
                } finally {
                    loadBusy = false;
                }
            }
        }, "load-check").start();
    }

    private void loadCheck(final Clash c, final String group, final String tag) throws Exception {
        if (Speed.loads(ConfigBuilder.SPEED_PORT, tag, c.secret())) {
            loadFails = 0;
            return;
        }
        if (!running || c != clash) return;
        final String name = nameOf(tag);
        AppState.log("Сервер " + name + " отвечает, но не пропускает загрузку (" + (loadFails + 1) + ")");
        if (++loadFails < 2) return;
        loadFails = 0;
        frozen.put(tag, System.currentTimeMillis() + FROZEN_MS);
        String pick = null;
        for (String t : Speed.spares(c.members(group), c.delays(), frozenNow())) {
            if (!running || c != clash) return;
            if (Speed.loads(ConfigBuilder.SPEED_PORT, t, c.secret())) {
                pick = t;
                break;
            }
            frozen.put(t, System.currentTimeMillis() + FROZEN_MS);
            AppState.log("Сервер " + nameOf(t) + " тоже не пропускает загрузку");
        }
        final String best = pick;
        exec.execute(new Runnable() {
            @Override public void run() {
                try {
                    applyLoadPick(c, group, tag, name, best);
                } catch (Throwable t) {
                    AppState.log("Проверка загрузки: " + t.getMessage());
                }
            }
        });
    }

    private void applyLoadPick(Clash c, String group, String tag, String name, String best) throws Exception {
        if (!running || c != clash || AppState.vpn != AppState.ON || AppState.switching
                || !group.equals(activeGroup) || pinnedTag() != null) return;
        String inUse = autoTag != null ? autoTag : c.now(group);
        if (!tag.equals(inUse)) return;
        if (best == null) {
            AppState.banner("Сервер " + name + " почти не пропускает трафик, замены не нашлось", 2);
            return;
        }
        c.select(ConfigBuilder.SELECTOR, best);
        autoTag = best;
        deadCount = 0;
        try {
            c.closeAll(); // connections stuck on the stalled server: reopen through the new one
        } catch (Exception ignored) {
        }
        refreshStatus();
        AppState.banner("Сервер " + name + " перестал пропускать трафик — переключено на " + nameOf(best), 2);
    }

    private String nameOf(String tag) {
        Server s = byTag.get(tag);
        return s != null ? s.name : tag;
    }

    /* ---------- automatic choice: move to a clearly faster server ---------- */

    private static final int SLOW_MS = 250;
    private static final long FASTER_CHECK_EVERY_MS = 60000;
    private long lastFasterCheck;

    /**
     * The group keeps its server until its own check every few minutes, even when a much faster one answers.
     * When the fresh results show one at least 150 ms and 1.5 times faster, the group re-tests and re-selects now.
     * Open connections stay where they are; new ones go through the new server.
     */
    private void preferFaster(String group, Map<String, Integer> res) throws Exception {
        if (!running || clash == null || res.isEmpty() || pinnedTag() != null || !group.equals(activeGroup)) return;
        String cur = clash.now(group);
        Integer cd = res.get(cur);
        String bestTag = null;
        int best = Integer.MAX_VALUE;
        for (Map.Entry<String, Integer> e : res.entrySet()) {
            if (e.getValue() < best) {
                best = e.getValue();
                bestTag = e.getKey();
            }
        }
        if (bestTag == null || bestTag.equals(cur)) return;
        if (cd != null && (cd - best < 150 || cd * 2 < best * 3)) return;
        Server cs = byTag.get(cur), bs = byTag.get(bestTag);
        AppState.log("Есть сервер быстрее: " + (bs != null ? bs.name : bestTag) + " " + best + " мс, сейчас "
                + (cs != null ? cs.name : cur) + " " + (cd != null ? cd + " мс" : "без ответа") + " — перепроверка");
        clash.groupCheck(group, 5000);
        refreshStatus();
    }

    /* ---------- manual server choice ---------- */

    private String pinnedTag() {
        try {
            String sel = clash == null ? "" : clash.now(ConfigBuilder.SELECTOR);
            return byTag.containsKey(sel) && !sel.equals(autoTag) ? sel : null;
        } catch (Exception e) {
            return null;
        }
    }

    /* ---------- speed check: lowest ping is not always fastest ---------- */

    /** The server the speed check picked; selected directly like a manual pin, but it is still automatic choice. */
    private volatile String autoTag;
    private volatile boolean speedBusy;
    private long lastSpeed;
    private static final long SPEED_EVERY_MS = 3 * 60 * 1000L;

    /** Runs the speed check a few seconds from now, once the first pings have settled. */
    private void laterSpeedCheck() {
        if (!running || exec == null || exec.isShutdown()) return;
        try {
            exec.schedule(new Runnable() {
                @Override public void run() {
                    try {
                        speedCheck();
                    } catch (Throwable t) {
                        AppState.log("Замер скорости: " + t.getMessage());
                    }
                }
            }, 6, TimeUnit.SECONDS);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
        }
    }

    /**
     * Downloads a test file through the best few servers by ping, one after another, and moves to one that is
     * clearly faster than the current (an overloaded server answers pings quickly but downloads slowly).
     * Runs after connecting and after a network change. The downloads run on their own thread, so the
     * watchdog and network changes are not held up.
     */
    private void speedCheck() throws Exception {
        if (!running || clash == null || AppState.vpn != AppState.ON || AppState.switching || speedBusy
                || autoTag != null || System.currentTimeMillis() - lastSpeed < SPEED_EVERY_MS) return;
        if (pinnedTag() != null) return;
        final Clash c = clash;
        final String group = activeGroup;
        final String cur = c.now(group);
        final List<String> picks = Speed.picks(c.members(group), c.delays(), cur, frozenNow());
        if (picks.size() < 2) return;
        speedBusy = true;
        lastSpeed = System.currentTimeMillis();
        new Thread(new Runnable() {
            @Override public void run() {
                final Map<String, Double> speeds = new HashMap<>();
                try {
                    for (String t : picks) {
                        if (!running || c != clash) return;
                        double v = Speed.measure(ConfigBuilder.SPEED_PORT, t, c.secret());
                        speeds.put(t, v);
                        Server s = byTag.get(t);
                        AppState.log("Скорость: " + (s != null ? s.name : t) + " — " + Speed.mbit(v));
                    }
                    final String best = Speed.faster(speeds, cur);
                    if (best == null) return;
                    exec.execute(new Runnable() {
                        @Override public void run() {
                            try {
                                applySpeedPick(c, group, cur, best, speeds.get(best));
                            } catch (Throwable t) {
                                AppState.log("Замер скорости: " + t.getMessage());
                            }
                        }
                    });
                } catch (Throwable ignored) {
                } finally {
                    speedBusy = false;
                }
            }
        }, "speed-check").start();
    }

    private void applySpeedPick(Clash c, String group, String cur, String best, double mbit) throws Exception {
        if (!running || c != clash || AppState.vpn != AppState.ON || AppState.switching || autoTag != null
                || !group.equals(activeGroup) || pinnedTag() != null || !cur.equals(c.now(group))) return;
        c.select(ConfigBuilder.SELECTOR, best);
        autoTag = best;
        deadCount = 0;
        refreshStatus();
        Server s = byTag.get(best);
        AppState.banner("Выбран более быстрый сервер: " + (s != null ? s.name : best) + " · " + Speed.mbit(mbit), 1);
    }

    /** Drops the speed check's choice and hands traffic back to the group. */
    private void clearAuto(String group) throws Exception {
        if (autoTag == null) return;
        autoTag = null;
        clash.select(ConfigBuilder.SELECTOR, group);
    }

    /** The speed check's server stopped answering: back to the group's choice. */
    private void watchAuto(String group, String tag) throws Exception {
        int d = clash.proxyDelay(tag, ConfigBuilder.TEST_URL, 5000);
        if (d > 0) {
            deadCount = 0;
            AppState.ping = d;
            AppState.changed();
            maybeLoadCheck(tag);
            return;
        }
        deadCount++;
        Server s = byTag.get(tag);
        AppState.log("Сервер " + (s != null ? s.name : tag) + " не ответил (" + deadCount + ")");
        if (deadCount < 2) return;
        deadCount = 0;
        clearAuto(group);
        if (clash.groupCheck(group, 5000).isEmpty() && clash.testGroup(group, 4000).isEmpty()) {
            safeReevaluate("dead");
            return;
        }
        try {
            clash.closeAll();
        } catch (Exception ignored) {
        }
        refreshStatus();
        AppState.banner("Сервер перестал отвечать — переключено на " + AppState.serverName, 2);
    }

    private void watchPinned(String tag) throws Exception {
        int d = clash.proxyDelay(tag, ConfigBuilder.TEST_URL, 5000);
        if (d > 0) {
            deadCount = 0;
            AppState.ping = d;
            AppState.changed();
            return;
        }
        deadCount++;
        Server s = byTag.get(tag);
        AppState.log("Выбранный сервер " + (s != null ? s.name : tag) + " не ответил (" + deadCount + ")");
        if (deadCount < 2) return;
        deadCount = 0;
        prefs.pinned("");
        String g = desiredGroup();
        if (ensureWorkingGroup(g, false) == null) {
            clash.select(ConfigBuilder.SELECTOR, g);
            if (!setVpn(AppState.WAITING)) return;
            AppState.phase = "Ни один сервер не отвечает. Повторная попытка через несколько секунд";
            scheduleReevaluate("retry", 15000);
        }
        refreshStatus();
        AppState.banner("Выбранный сервер не отвечает — включён автовыбор", 2);
    }

    /** Selects a server by name; false when there is no such server. */
    private boolean applyPin(String name) throws Exception {
        for (Server s : servers) {
            if (s.group != Server.EXCLUDED && s.name.equals(name)) {
                clash.select(ConfigBuilder.SELECTOR, s.tag);
                deadCount = 0;
                AppState.log("Сервер выбран вручную: " + s.name);
                return true;
            }
        }
        return false;
    }

    /** name = "" returns to automatic choice. Called from the servers screen. */
    public void pin(final String name) {
        if (!running || exec == null || exec.isShutdown() || clash == null) return;
        prefs.pinned(name);
        exec.execute(new Runnable() {
            @Override public void run() {
                try {
                    if (AppState.vpn == AppState.OFF || AppState.vpn == AppState.CONNECTING) return;
                    AppState.switching = true;
                    autoTag = null;
                    AppState.changed();
                    if (name.isEmpty()) {
                        AppState.log("Возврат к автовыбору");
                        if (ensureWorkingGroup(desiredGroup(), false) != null) {
                            laterSpeedCheck();
                        } else {
                            clash.select(ConfigBuilder.SELECTOR, desiredGroup());
                            if (!setVpn(AppState.WAITING)) return;
                            AppState.phase = "Ни один сервер не отвечает. Повторная попытка через несколько секунд";
                            scheduleReevaluate("retry", 15000);
                        }
                    } else if (!applyPin(name)) {
                        prefs.pinned("");
                    }
                    AppState.switching = false;
                    refreshStatus();
                } catch (Throwable t) {
                    AppState.switching = false;
                    AppState.log("Не удалось выбрать сервер: " + t.getMessage());
                    AppState.changed();
                }
            }
        });
    }

    /**
     * The "Переключить" button in the notification: the next working server of the group, by ping.
     * A server chosen by hand stays chosen by hand; otherwise it is kept like the speed check's choice,
     * and the watchdog returns to the group if it stops answering.
     */
    private void switchNext() {
        if (!running || exec == null || exec.isShutdown() || clash == null) return;
        exec.execute(new Runnable() {
            @Override public void run() {
                try {
                    if (!running || AppState.vpn != AppState.ON || AppState.switching) return;
                    String pt = pinnedTag();
                    Server ps = pt == null ? null : byTag.get(pt);
                    String group = ps == null ? activeGroup
                            : ps.group == Server.LTE ? ConfigBuilder.GROUP_LTE : ConfigBuilder.GROUP_REGULAR;
                    String cur = pt != null ? pt : autoTag != null ? autoTag : clash.now(group);
                    final Map<String, Integer> d = clash.delays();
                    List<String> members = clash.members(group);
                    List<String> order = new ArrayList<>();
                    for (String t : members) if (d.containsKey(t) && d.get(t) > 0) order.add(t);
                    java.util.Collections.sort(order, new java.util.Comparator<String>() {
                        @Override public int compare(String a, String b) { return d.get(a) - d.get(b); }
                    });
                    if (order.size() < 2) order = members; // no fresh pings: the subscription's order
                    if (order.size() < 2) {
                        AppState.banner("Других серверов нет", 2);
                        return;
                    }
                    String next = order.get((order.indexOf(cur) + 1) % order.size());
                    if (next.equals(cur)) return;
                    Server s = byTag.get(next);
                    if (ps != null && s != null) {
                        prefs.pinned(s.name);
                        applyPin(s.name);
                    } else {
                        clash.select(ConfigBuilder.SELECTOR, next);
                        autoTag = next;
                        deadCount = 0;
                    }
                    try {
                        clash.closeAll(); // open connections move to the new server right away
                    } catch (Exception ignored) {
                    }
                    refreshStatus();
                    AppState.banner("Переключено на " + AppState.serverName, 1);
                } catch (Throwable t) {
                    AppState.log("Не удалось переключить сервер: " + t.getMessage());
                }
            }
        });
    }

    /** Measures the server in use right now (the "Пинг" button). */
    public void pingCurrent() {
        if (!running || clash == null || AppState.pingingCurrent) return;
        AppState.pingingCurrent = true;
        AppState.changed();
        final Clash c = clash;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String tag = pinnedTag();
                    if (tag == null) tag = autoTag;
                    if (tag == null) tag = c.now(activeGroup);
                    int d = tag.isEmpty() ? -1 : c.proxyDelay(tag, ConfigBuilder.TEST_URL, 5000);
                    AppState.ping = d > 0 ? d : -1;
                    if (!tag.isEmpty()) AppState.pings.put(tag, d > 0 ? d : -1);
                    AppState.log("Пинг текущего сервера: " + (d > 0 ? d + " мс" : "нет ответа"));
                } catch (Exception e) {
                    AppState.ping = -1;
                } finally {
                    AppState.pingingCurrent = false;
                    AppState.changed();
                }
            }
        }, "ping-current").start();
    }

    /** Measures every server through itself (the VPN must be on). */
    public void pingAll() {
        if (!running || clash == null || AppState.pinging) return;
        AppState.pinging = true;
        AppState.changed();
        final Clash c = clash;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    List<String> tags = new ArrayList<>();
                    for (Server s : servers) if (s.group != Server.EXCLUDED) tags.add(s.tag);
                    AppState.pings.clear();
                    c.testTags(tags, 5000, new Clash.Listener() {
                        @Override public void onResult(String tag, int delay) {
                            AppState.pings.put(tag, delay);
                            AppState.changed();
                        }
                    });
                } finally {
                    AppState.pinging = false;
                    AppState.changed();
                }
            }
        }, "ping-all").start();
    }

    /* ---------- subscription auto-update ---------- */

    private void safeCheckSub() {
        try {
            checkSub();
        } catch (Throwable t) {
            AppState.log("Автообновление подписки: " + t.getMessage());
        }
    }

    private void checkSub() throws Exception {
        if (!running) return;
        SubReminder.check(this, prefs);
        if (System.currentTimeMillis() - prefs.subUpdated() < SUB_REFRESH_MS) return;
        List<String> urls = prefs.subUrls();
        boolean anyHttp = false;
        for (String u : urls) anyHttp |= Subs.isHttp(u);
        if (!anyHttp) return;
        List<Subs.Entry> entries = loadSubs(urls);
        if (!running) return;
        List<String> warnings = new ArrayList<>();
        List<Server> fresh = Subs.arrange(Subs.merge(entries, warnings, new ArrayList<String>()), prefs);
        if (fresh.isEmpty()) {
            AppState.log("Автообновление: подписки не скачались, повтор позже");
            return;
        }
        prefs.markSubUpdated();
        if (signature(fresh).equals(serversSig)) {
            AppState.log("Автообновление: подписки не изменились");
            return;
        }
        AppState.log("Автообновление: серверы в подписке изменились, перезапуск ядра");
        if (!running) return;
        try {
            restartCore(fresh, warnings);
        } catch (Exception e) {
            AppState.log("Перезапуск ядра не удался: " + e);
            fail("Не удалось применить обновлённую подписку: " + e.getMessage());
            return;
        }
        AppState.banner("Подписка обновлена · " + AppState.lastServers.replace("Серверов: ", "серверов: "), 1);
    }

    /** Restarts only the core with new servers; the VPN interface and the notification stay. */
    private void restartCore(List<Server> fresh, List<String> warnings) throws Exception {
        if (!setVpn(AppState.CONNECTING)) return;
        AppState.phase = "Применение обновлённой подписки";
        AppState.changed();
        if (periodic != null) periodic.cancel(false);
        if (statusTask != null) statusTask.cancel(false);
        if (watchdog != null) watchdog.cancel(false);
        if (pendingReeval != null) pendingReeval.cancel(false);
        stopHelper();
        applyServers(fresh, warnings);
        startCore();
    }

    /** Stops the core process and waits for it, without treating that as a crash. */
    private void stopHelper() {
        stopXray();
        Process p = helper;
        helper = null;
        OutputStream in = helperIn;
        helperIn = null;
        if (p == null) return;
        try {
            if (in != null) {
                in.write("stop\n".getBytes());
                in.flush();
            }
        } catch (Exception ignored) {
        }
        try {
            if (!p.waitFor(4, TimeUnit.SECONDS)) p.destroy();
            p.waitFor(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
    }

    /* ---------- group logic ---------- */

    private String desiredGroup() {
        boolean wantLte;
        if (Prefs.MODE_NET.equals(prefs.mode())) wantLte = "cell".equals(AppState.net);
        else wantLte = AppState.wl == AppState.WL_ON;
        if (wantLte && hasLte) return ConfigBuilder.GROUP_LTE;
        if (!wantLte && hasRegular) return ConfigBuilder.GROUP_REGULAR;
        return hasRegular ? ConfigBuilder.GROUP_REGULAR : ConfigBuilder.GROUP_LTE;
    }

    private static String groupLabel(String g) {
        return ConfigBuilder.GROUP_LTE.equals(g) ? "LTE" : "обычные";
    }

    private String other(String g) {
        if (ConfigBuilder.GROUP_LTE.equals(g)) return hasRegular ? ConfigBuilder.GROUP_REGULAR : null;
        return hasLte ? ConfigBuilder.GROUP_LTE : null;
    }

    /** Tests the group, falls back to the other group. Returns the group in use or null. */
    private String ensureWorkingGroup(String group, boolean initial) throws Exception {
        Map<String, Integer> res = initial ? quickTest(group) : clash.testGroup(group, 5000);
        AppState.log("Пинг (" + groupLabel(group) + "): отвечают " + res.size() + " из " + countGroup(group));
        String use = group;
        if (res.isEmpty()) {
            String alt = other(group);
            if (alt != null) {
                Map<String, Integer> r2 = clash.testGroup(alt, 5000);
                AppState.log("Пинг (" + groupLabel(alt) + "): отвечают " + r2.size() + " из " + countGroup(alt));
                if (!r2.isEmpty()) {
                    use = alt;
                    res = r2;
                    boolean blockedNow = ConfigBuilder.GROUP_REGULAR.equals(group);
                    if (!initial && (!blockedNow || blockedNow != AppState.regularBlocked)) {
                        AppState.banner(groupLabel(group) + " не отвечают — переключено на " + groupLabel(alt), 2);
                    }
                }
            }
        }
        if (res.isEmpty()) return null;
        AppState.regularBlocked = ConfigBuilder.GROUP_LTE.equals(use) && ConfigBuilder.GROUP_REGULAR.equals(group);
        if (AppState.regularBlocked) AppState.log("Обычные серверы не отвечают, хотя белых списков нет — используются серверы для БС");
        clash.select(ConfigBuilder.SELECTOR, use);
        autoTag = null;
        activeGroup = use;
        AppState.group = use;
        AppState.alive = res.size();
        AppState.groupSize = countGroup(use);
        return use;
    }

    /** Waits only QUICK_GRACE_MS after the first answer; the full count comes later. */
    private Map<String, Integer> quickTest(final String group) throws Exception {
        return clash.testGroup(group, 5000, QUICK_GRACE_MS, new Clash.Done() {
            @Override public void onDone(Map<String, Integer> all) {
                if (!running || !group.equals(activeGroup)) return;
                AppState.log("Пинг (" + groupLabel(group) + ") полностью: отвечают " + all.size() + " из " + countGroup(group));
                AppState.alive = all.size();
                AppState.changed();
                final Map<String, Integer> res = all;
                try {
                    exec.execute(new Runnable() {
                        @Override public void run() {
                            try {
                                preferFaster(group, res);
                            } catch (Throwable ignored) {
                            }
                        }
                    });
                } catch (Exception ignored) {
                }
            }
        });
    }

    private static final long QUICK_GRACE_MS = 1500;

    private int countGroup(String g) {
        int want = ConfigBuilder.GROUP_LTE.equals(g) ? Server.LTE : Server.REGULAR;
        int n = 0;
        for (Server s : servers) if (s.group == want || want == Server.LTE && s.both && s.group == Server.REGULAR) n++;
        return n;
    }

    private synchronized void scheduleReevaluate(final String cause, long delayMs) {
        if (!running || exec == null || exec.isShutdown()) return;
        if (pendingReeval != null) pendingReeval.cancel(false);
        try {
            pendingReeval = exec.schedule(new Runnable() {
                @Override public void run() { safeReevaluate(cause); }
            }, delayMs, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
        }
    }

    private void safeReevaluate(String cause) {
        try {
            reevaluate(cause);
        } catch (Throwable t) {
            AppState.log("Ошибка проверки: " + t.getMessage());
            AppState.switching = false;
            AppState.changed();
            if (AppState.vpn == AppState.WAITING) scheduleReevaluate("retry", 15000);
        }
    }

    private void reevaluate(String cause) throws Exception {
        if (!running || clash == null) return;
        int prevWl = AppState.wl;
        probeWl();
        if (!running) return;
        if (AppState.wl == AppState.WL_NONET) {
            if (AppState.vpn != AppState.WAITING) {
                if (!setVpn(AppState.WAITING)) return;
                AppState.phase = "Нет интернета. Подключение после появления сети";
                AppState.log("Сеть пропала — ожидание");
                AppState.changed();
            }
            scheduleReevaluate("retry", 30000);
            return;
        }
        if (pinnedTag() != null) {
            if (AppState.vpn == AppState.WAITING) {
                if (!setVpn(AppState.ON)) return;
                AppState.since = System.currentTimeMillis();
            }
            refreshStatus();
            return;
        }
        String want = desiredGroup();
        String prevServer = AppState.serverName;
        boolean wasWaiting = AppState.vpn == AppState.WAITING;
        AppState.switching = true;
        AppState.changed();

        String used;
        boolean retryRegular = !AppState.regularBlocked || !"periodic".equals(cause)
                || System.currentTimeMillis() - lastRegularTry > 10 * 60 * 1000L;
        if (!want.equals(activeGroup) && !retryRegular && ConfigBuilder.GROUP_LTE.equals(activeGroup)) {
            want = activeGroup; // keep the white-list server for now
        }
        if (autoTag != null) {
            if ("periodic".equals(cause) && want.equals(activeGroup) && !wasWaiting) {
                AppState.switching = false;
                refreshStatus(); // the watchdog looks after the speed check's server
                return;
            }
            clearAuto(activeGroup);
        }
        if (ConfigBuilder.GROUP_REGULAR.equals(want) && !want.equals(activeGroup)) lastRegularTry = System.currentTimeMillis();
        if (!want.equals(activeGroup) || wasWaiting) {
            used = ensureWorkingGroup(want, false);
        } else {
            // same group: urltest keeps it fresh; test now only after a network change or a failure
            String cur = clash.now(activeGroup);
            int d = cur.isEmpty() ? -1 : clash.lastDelay(cur);
            if (d > 0 && !"net".equals(cause)) {
                used = activeGroup;
            } else {
                Map<String, Integer> res = clash.testGroup(activeGroup, 5000);
                AppState.alive = res.size();
                if (res.isEmpty()) used = ensureWorkingGroup(activeGroup, false);
                else used = activeGroup;
            }
        }
        AppState.switching = false;
        if (used == null) {
            if (!setVpn(AppState.WAITING)) return;
            AppState.phase = "Ни один сервер не отвечает. Повторная попытка через несколько секунд";
            AppState.changed();
            scheduleReevaluate("retry", 15000);
            return;
        }
        if (wasWaiting) {
            if (!setVpn(AppState.ON)) return;
            AppState.since = System.currentTimeMillis();
            String pn = prefs.pinned();
            if (!pn.isEmpty() && !applyPin(pn)) prefs.pinned("");
        }
        refreshStatus();
        String name = AppState.serverName;
        if (!name.equals(prevServer) && !prevServer.isEmpty()) {
            String msg;
            if ("wl".equals(cause) || prevWl != AppState.wl) {
                msg = (AppState.wl == AppState.WL_ON ? "Включились белые списки" : "Белые списки выключились") + " — переключено на " + name;
                AppState.banner(msg, AppState.wl == AppState.WL_ON ? 2 : 1);
            } else if ("net".equals(cause)) {
                AppState.banner(netName() + " — переключено на " + name, 2);
            } else {
                AppState.log("Сервер сменился: " + prevServer + " → " + name);
            }
        }
        AppState.changed();
        if (!"periodic".equals(cause)) laterSpeedCheck();
    }

    private void refreshStatus() throws Exception {
        if (clash == null) return;
        String group = clash.now(ConfigBuilder.SELECTOR);
        if (group.isEmpty()) return;
        String tag;
        Server picked = byTag.get(group);
        if (picked != null) {
            tag = group;
            group = picked.group == Server.LTE ? ConfigBuilder.GROUP_LTE : ConfigBuilder.GROUP_REGULAR;
            AppState.pinned = tag.equals(autoTag) ? "" : picked.name;
        } else {
            tag = clash.now(group);
            AppState.pinned = "";
        }
        Server s = byTag.get(tag);
        AppState.group = group;
        AppState.serverName = s != null ? s.name : tag;
        AppState.ping = tag.isEmpty() ? -1 : clash.lastDelay(tag);
        AppState.groupSize = countGroup(group);
        updateNotification();
        AppState.changed();
    }

    private void probeWl() {
        if ("wifi".equals(AppState.net)) {
            // operators' white lists exist only on mobile networks: no probing on Wi-Fi
            boolean was = AppState.wlSkipped;
            AppState.wl = AppState.WL_OFF;
            AppState.wlSkipped = true;
            AppState.wlChecking = false;
            AppState.wlDetail = "На Wi-Fi не проверяются";
            if (!was) AppState.log("Wi-Fi: белые списки не проверяются");
            AppState.changed();
            return;
        }
        AppState.wlSkipped = false;
        AppState.wlChecking = true;
        AppState.changed();
        WlProbe.Result r = WlProbe.run(underlying);
        AppState.wl = r.state;
        AppState.wlDetail = r.detail();
        AppState.wlChecking = false;
        AppState.log(r.logLine());
        AppState.changed();
    }

    /* ---------- core process ---------- */

    private static final int CRASH_KEEP = 60;

    private static boolean isCrashStart(String l) {
        return l.startsWith("panic: ") || l.startsWith("fatal error: ") || l.startsWith("runtime: ")
                || l.startsWith("SIG") || l.startsWith("unexpected fault");
    }

    private void startHelper(File cfg) throws Exception {
        String bin = getApplicationInfo().nativeLibraryDir + "/libsbhelper.so";
        File work = new File(getFilesDir(), "core");
        work.mkdirs();
        ProcessBuilder pb = new ProcessBuilder(bin, sockPath, cfg.getAbsolutePath(), work.getAbsolutePath());
        pb.redirectErrorStream(true);
        pb.directory(work);
        final Process proc = pb.start();
        helper = proc;
        helperIn = proc.getOutputStream();
        lastIfaceLine = "";
        final InputStream out = proc.getInputStream();
        new Thread(new Runnable() {
            @Override public void run() {
                int skipped = 0;
                try {
                    BufferedReader r = new BufferedReader(new InputStreamReader(out, "UTF-8"));
                    String line;
                    // A Go crash dumps every goroutine (thousands of lines) and would push its own
                    // first lines, the actual cause, out of the 400-line journal. Keep only the head.
                    int crashLines = -1;
                    while ((line = r.readLine()) != null) {
                        String l = stripAnsi(line);
                        if (crashLines < 0 && isCrashStart(l)) crashLines = 0;
                        if (crashLines >= 0) {
                            if (crashLines++ < CRASH_KEEP) AppState.log(l);
                            else skipped++;
                            continue;
                        }
                        // keep URL-test results and problems, drop per-connection noise
                        if (l.startsWith("DEBUG") && !l.contains("unavailable") && !l.contains("available:")) continue;
                        if (l.contains("dns: exchanged") || l.contains("inbound connection") || l.contains("outbound connection")
                                || l.contains("inbound packet connection") || l.contains("outbound packet connection")) continue;
                        AppState.log(l);
                    }
                } catch (Exception ignored) {
                }
                if (skipped > 0) AppState.log("… ещё " + skipped + " строк отчёта о сбое скрыто");
                if (running && !stopping && helper == proc) {
                    AppState.log("Ядро остановилось");
                    fail("Ядро остановилось, подробности в журнале");
                }
            }
        }, "core-log").start();
        pushIface();
    }

    /* ---------- bundled Xray for transports sing-box lacks (xhttp) ---------- */

    /** Gives every Xray-carried server a local port and fresh credentials; builds the Xray config. */
    private void prepareXray() {
        xrayConfig = null;
        int n = 0;
        for (Server s : servers) if (s.xray != null && s.group != Server.EXCLUDED) n++;
        if (n == 0) return;
        String user = randomHex(6), pass = randomHex(12);
        int port = 20000 + new SecureRandom().nextInt(20000);
        List<Server> used = new ArrayList<>();
        for (Server s : servers) {
            if (s.xray == null || s.group == Server.EXCLUDED) continue;
            s.outbound.put("server_port", port++);
            s.outbound.put("username", user);
            s.outbound.put("password", pass);
            used.add(s);
        }
        xrayConfig = XrayJson.buildCoreConfig(used, user, pass);
        AppState.log("xhttp-серверов: " + n + ", они пойдут через ядро Xray");
    }

    private void startXray() {
        if (xrayConfig == null) return;
        try {
            File work = new File(getFilesDir(), "xray");
            work.mkdirs();
            File cfg = new File(work, "config.json");
            FileOutputStream fo = new FileOutputStream(cfg);
            fo.write(xrayConfig.getBytes("UTF-8"));
            fo.close();
            String bin = getApplicationInfo().nativeLibraryDir + "/libxray.so";
            ProcessBuilder pb = new ProcessBuilder(bin, "run", "-c", cfg.getAbsolutePath());
            pb.environment().put("XRAY_LOCATION_ASSET", work.getAbsolutePath());
            pb.redirectErrorStream(true);
            pb.directory(work);
            final Process proc = pb.start();
            xrayProc = proc;
            final InputStream out = proc.getInputStream();
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        BufferedReader r = new BufferedReader(new InputStreamReader(out, "UTF-8"));
                        String line;
                        while ((line = r.readLine()) != null) {
                            String l = stripAnsi(line).trim();
                            if (l.isEmpty() || l.contains("[Info]") || l.contains("[Debug]") || l.contains(" accepted ")) continue;
                            AppState.log("Xray: " + l);
                        }
                    } catch (Exception ignored) {
                    }
                    if (running && !stopping && xrayProc == proc) {
                        AppState.log("Ядро Xray остановилось — xhttp-серверы недоступны, остальные работают");
                    }
                }
            }, "xray-log").start();
        } catch (Exception e) {
            AppState.log("Не удалось запустить Xray: " + e.getMessage() + " — xhttp-серверы недоступны");
        }
    }

    private void stopXray() {
        final Process p = xrayProc;
        xrayProc = null;
        if (p == null) return;
        p.destroy();
        try {
            p.waitFor(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
    }

    private static String stripAnsi(String s) {
        return s.replaceAll("\u001B\\[[;\\d]*m", "");
    }

    private synchronized void pushIface() {
        if (helperIn == null) return;
        String line;
        Network n = underlying;
        LinkProperties lp = n == null ? null : cm.getLinkProperties(n);
        if (lp == null || lp.getInterfaceName() == null) {
            line = "noiface\n";
        } else {
            int idx = ifIndex(lp.getInterfaceName());
            NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            boolean metered = nc != null && !nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
            line = "iface " + lp.getInterfaceName() + " " + idx + " " + (metered ? 1 : 0) + "\n";
        }
        if (line.equals(lastIfaceLine)) return;
        lastIfaceLine = line;
        try {
            helperIn.write(line.getBytes("UTF-8"));
            helperIn.flush();
        } catch (Exception e) {
            AppState.log("Не удалось передать сеть ядру: " + e.getMessage());
        }
    }

    private static int ifIndex(String name) {
        try {
            NetworkInterface ni = NetworkInterface.getByName(name);
            return ni == null ? -1 : ni.getIndex();
        } catch (Exception e) {
            return -1;
        }
    }

    /* ---------- socket for the core ---------- */

    private void startServer() throws Exception {
        File f = new File(getFilesDir(), "core.sock");
        f.delete();
        sockPath = f.getAbsolutePath();
        bindSocket = new LocalSocket();
        bindSocket.bind(new LocalSocketAddress(sockPath, LocalSocketAddress.Namespace.FILESYSTEM));
        server = new LocalServerSocket(bindSocket.getFileDescriptor());
        new Thread(new Runnable() {
            @Override public void run() {
                while (running) {
                    try {
                        final LocalSocket c = server.accept();
                        new Thread(new Runnable() {
                            @Override public void run() { handle(c); }
                        }).start();
                    } catch (Exception e) {
                        if (running) AppState.log("Сокет ядра: " + e.getMessage());
                        break;
                    }
                }
            }
        }, "core-sock").start();
    }

    private void closeServer() {
        try {
            if (server != null) server.close();
        } catch (Exception ignored) {
        }
        try {
            if (bindSocket != null) bindSocket.close();
        } catch (Exception ignored) {
        }
        server = null;
        bindSocket = null;
    }

    private void handle(LocalSocket c) {
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            String line = r.readLine();
            if (line == null) return;
            JSONObject req = new JSONObject(line);
            String m = req.optString("m");
            OutputStream os = c.getOutputStream();
            if ("openTun".equals(m)) {
                ParcelFileDescriptor pfd = openTun(req);
                if (pfd == null) {
                    os.write("{\"error\":\"VPN не разрешён\"}\n".getBytes("UTF-8"));
                } else {
                    c.setFileDescriptorsForSend(new FileDescriptor[]{pfd.getFileDescriptor()});
                    os.write("{\"ok\":true}\n".getBytes("UTF-8"));
                }
            } else if ("interfaces".equals(m)) {
                os.write((interfaces().toString() + "\n").getBytes("UTF-8"));
            } else {
                os.write("{\"error\":\"unknown\"}\n".getBytes("UTF-8"));
            }
            os.flush();
        } catch (Exception e) {
            AppState.log("Запрос ядра: " + e.getMessage());
        } finally {
            try {
                c.close();
            } catch (Exception ignored) {
            }
        }
    }

    private synchronized ParcelFileDescriptor openTun(JSONObject req) throws Exception {
        if (tunPfd != null) return tunPfd;
        Builder b = new Builder();
        b.setSession(getString(R.string.app_name));
        int mtu = req.optInt("mtu", 9000);
        b.setMtu(mtu > 0 ? mtu : 9000);
        boolean v6 = false;
        JSONArray a4 = req.optJSONArray("inet4");
        JSONArray a6 = req.optJSONArray("inet6");
        if (a4 != null) for (int i = 0; i < a4.length(); i++) addAddr(b, a4.getString(i));
        else b.addAddress("172.19.0.1", 30);
        if (a6 != null && a6.length() > 0) {
            for (int i = 0; i < a6.length(); i++) addAddr(b, a6.getString(i));
            v6 = true;
        }
        String dns = req.optString("dns", "");
        b.addDnsServer(dns.isEmpty() ? "172.19.0.2" : dns);
        JSONArray r4 = req.optJSONArray("routes4");
        if (r4 != null && r4.length() > 0) for (int i = 0; i < r4.length(); i++) addRoute(b, r4.getString(i));
        else b.addRoute("0.0.0.0", 0);
        if (v6) {
            JSONArray r6 = req.optJSONArray("routes6");
            if (r6 != null && r6.length() > 0) for (int i = 0; i < r6.length(); i++) addRoute(b, r6.getString(i));
            else b.addRoute("::", 0);
        }
        b.addDisallowedApplication(getPackageName());
        int skipped = 0;
        Set<String> locked = prefs.lockedApps();
        for (String pkg : prefs.excludedApps()) {
            if (pkg.equals(getPackageName()) || locked.contains(pkg)) continue;
            try {
                b.addDisallowedApplication(pkg);
                skipped++;
            } catch (Exception ignored) {
                // app was uninstalled
            }
        }
        if (skipped > 0) AppState.log("Приложений без VPN: " + skipped);
        if (Build.VERSION.SDK_INT >= 29) b.setMetered(false);
        Intent open = new Intent(this, MainActivity.class);
        b.setConfigureIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE));
        tunPfd = b.establish();
        if (tunPfd == null) AppState.log("Система не дала создать VPN-интерфейс");
        else {
            AppState.log("VPN-интерфейс создан");
            unlock(); // the tunnel replaced it
        }
        return tunPfd;
    }

    private static void addAddr(Builder b, String cidr) {
        int s = cidr.indexOf('/');
        b.addAddress(cidr.substring(0, s), Integer.parseInt(cidr.substring(s + 1)));
    }

    private static void addRoute(Builder b, String cidr) {
        int s = cidr.indexOf('/');
        b.addRoute(cidr.substring(0, s), Integer.parseInt(cidr.substring(s + 1)));
    }

    private JSONObject interfaces() throws Exception {
        JSONArray list = new JSONArray();
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            LinkProperties lp = cm.getLinkProperties(n);
            if (nc == null || lp == null || lp.getInterfaceName() == null) continue;
            if (nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
            String name = lp.getInterfaceName();
            JSONObject o = new JSONObject();
            o.put("name", name);
            o.put("index", ifIndex(name));
            int mtu = 0;
            if (Build.VERSION.SDK_INT >= 29) mtu = lp.getMtu();
            if (mtu <= 0) {
                try {
                    NetworkInterface ni = NetworkInterface.getByName(name);
                    if (ni != null) mtu = ni.getMTU();
                } catch (Exception ignored) {
                }
            }
            o.put("mtu", mtu > 0 ? mtu : 1500);
            JSONArray addrs = new JSONArray();
            for (LinkAddress la : lp.getLinkAddresses()) {
                addrs.put(la.getAddress().getHostAddress().replaceAll("%.*$", "") + "/" + la.getPrefixLength());
            }
            o.put("addrs", addrs);
            JSONArray dnsArr = new JSONArray();
            for (InetAddress d : lp.getDnsServers()) dnsArr.put(d.getHostAddress());
            o.put("dns", dnsArr);
            int type = 3;
            if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) type = 0;
            else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) type = 1;
            else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) type = 2;
            o.put("type", type);
            o.put("metered", !nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED));
            o.put("flags", 0x1 | 0x40 | 0x1000);
            list.put(o);
        }
        return new JSONObject().put("list", list);
    }

    /* ---------- network tracking ---------- */

    private void registerNetwork() {
        defaultCb = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) { defaultNet = network; onNetworkChanged(); }
            @Override public void onLost(Network network) { if (network.equals(defaultNet)) defaultNet = null; onNetworkChanged(); }
            @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities nc) { onNetworkChanged(); }
            @Override public void onLinkPropertiesChanged(Network network, LinkProperties lp) { onNetworkChanged(); }
        };
        allCb = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) { onNetworkChanged(); }
            @Override public void onLost(Network network) { onNetworkChanged(); }
        };
        try {
            cm.registerDefaultNetworkCallback(defaultCb);
            NetworkRequest req = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build();
            cm.registerNetworkCallback(req, allCb);
        } catch (Exception e) {
            AppState.log("Не удалось следить за сетью: " + e.getMessage());
        }
        computeUnderlying();
    }

    private void unregisterNetwork() {
        try {
            if (defaultCb != null) cm.unregisterNetworkCallback(defaultCb);
        } catch (Exception ignored) {
        }
        try {
            if (allCb != null) cm.unregisterNetworkCallback(allCb);
        } catch (Exception ignored) {
        }
        defaultCb = null;
        allCb = null;
    }

    private void onNetworkChanged() {
        if (!running) return;
        String before = AppState.net;
        Network beforeNet = underlying;
        computeUnderlying();
        pushIface();
        boolean changed = !AppState.net.equals(before) || (underlying == null) != (beforeNet == null)
                || (underlying != null && !underlying.equals(beforeNet));
        if (changed) {
            frozen.clear(); // another network may not stall the same servers
            loadFails = 0;
            lastLoad = 0;
            AppState.log("Сеть: " + netName());
            AppState.changed();
            if (AppState.vpn == AppState.ON || AppState.vpn == AppState.WAITING) scheduleReevaluate("net", 1500);
        }
    }

    /** The physical network the core should use: the default one, or any non-VPN network with internet. */
    private void computeUnderlying() {
        Network pick = null;
        Network d = defaultNet;
        if (d != null) {
            NetworkCapabilities nc = cm.getNetworkCapabilities(d);
            if (nc != null && !nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) pick = d;
        }
        if (pick == null) {
            Network cell = null;
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc == null || nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                        || !nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) { pick = n; break; }
                if (cell == null) cell = n;
            }
            if (pick == null) pick = cell;
        }
        underlying = pick;
        String net = "none";
        if (pick != null) {
            NetworkCapabilities nc = cm.getNetworkCapabilities(pick);
            if (nc != null) {
                if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) net = "wifi";
                else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) net = "cell";
                else net = "other";
            }
        }
        AppState.net = net;
    }

    private static String netName() {
        switch (AppState.net) {
            case "wifi": return "Wi-Fi";
            case "cell": return "Мобильная сеть";
            case "other": return "Сеть";
            default: return "Нет сети";
        }
    }

    /* ---------- diagnostics ---------- */

    private String directCheck(String url) {
        try {
            int d = clash.proxyDelay("direct", url, 5000);
            return d > 0 ? "ok " + d + " мс" : "нет ответа";
        } catch (Exception e) {
            return "ошибка (" + e.getMessage() + ")";
        }
    }

    /** One line per server without keys: protocol, transport, security, SNI, flow. */
    @SuppressWarnings("unchecked")
    private static String describe(Server s) {
        Map<String, Object> o = s.outbound;
        StringBuilder sb = new StringBuilder();
        sb.append(s.tag).append(" [").append(s.groupLabel()).append("] ").append(s.name).append(": ");
        sb.append(o.get("type"));
        Object tr = o.get("transport");
        sb.append(' ').append(tr instanceof Map ? ((Map<String, Object>) tr).get("type") : "tcp");
        Object tls = o.get("tls");
        if (tls instanceof Map) {
            Map<String, Object> t = (Map<String, Object>) tls;
            sb.append(t.containsKey("reality") ? " reality" : " tls");
            if (t.get("server_name") != null) sb.append(" sni=").append(t.get("server_name"));
            Object utls = t.get("utls");
            if (utls instanceof Map) sb.append(" fp=").append(((Map<String, Object>) utls).get("fingerprint"));
        } else {
            sb.append(" без tls");
        }
        if (o.get("flow") != null) sb.append(" flow=").append(o.get("flow"));
        sb.append(" порт ").append(o.get("server_port"));
        return sb.toString();
    }

    /* ---------- misc ---------- */

    private void copyRules(File dir) throws Exception {
        dir.mkdirs();
        String[] names = getAssets().list("rules");
        if (names == null) return;
        long stamp = getPackageManager().getPackageInfo(getPackageName(), 0).lastUpdateTime;
        File marker = new File(dir, ".stamp");
        if (marker.exists() && marker.lastModified() == stamp) return;
        for (String n : names) {
            InputStream in = getAssets().open("rules/" + n);
            FileOutputStream out = new FileOutputStream(new File(dir, n));
            byte[] buf = new byte[65536];
            int k;
            while ((k = in.read(buf)) > 0) out.write(buf, 0, k);
            in.close();
            out.close();
        }
        new FileOutputStream(marker).close();
        marker.setLastModified(stamp);
    }

    private String fetch(String url, String[] title) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", "v2rayNG/1.10.19");
            c.setRequestProperty("Accept", "*/*");
            // Device identification expected by panels with a device limit (Remnawave, Marzban forks, etc.)
            c.setRequestProperty("x-hwid", hwid());
            c.setRequestProperty("x-device-os", "Android");
            c.setRequestProperty("x-ver-os", Build.VERSION.RELEASE);
            c.setRequestProperty("x-device-model", (Build.MANUFACTURER + " " + Build.MODEL).trim());
            c.setRequestProperty("x-app-version", "Dash/1.7");
            int code = c.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);
            String body = Clash.read(c.getInputStream());
            if (body.trim().isEmpty()) throw new Exception("пустой ответ");
            title[0] = profileTitle(c.getHeaderField("profile-title"));
            if (title.length > 1) title[1] = c.getHeaderField("subscription-userinfo");
            return body;
        } finally {
            c.disconnect();
        }
    }

    /** profile-title header: plain text or "base64:…". */
    private static String profileTitle(String h) {
        if (h == null) return "";
        h = h.trim();
        if (h.startsWith("base64:")) {
            try {
                h = new String(android.util.Base64.decode(h.substring(7).trim(), android.util.Base64.DEFAULT), "UTF-8").trim();
            } catch (Exception e) {
                return "";
            }
        }
        return h.length() > 40 ? h.substring(0, 40) : h;
    }

    /** Stable per-device id (ANDROID_ID is fixed for this app on this device). */
    private String hwid() {
        String id = android.provider.Settings.Secure.getString(getContentResolver(),
                android.provider.Settings.Secure.ANDROID_ID);
        if (id == null || id.isEmpty()) {
            id = prefs.hwid();
            if (id.isEmpty()) { id = randomHex(8); prefs.hwid(id); }
        }
        return id;
    }

    /** Panels answer with fake entries (0.0.0.0, port 1) carrying a message. Returns that message or null. */
    static String stubReason(List<Server> list) {
        if (list.isEmpty()) return null;
        for (Server s : list) {
            Object host = s.xray != null ? s.host : s.outbound.get("server");
            Object port = s.xray != null ? Integer.valueOf(443) : s.outbound.get("server_port");
            String h = host == null ? "" : host.toString();
            int p = port instanceof Number ? ((Number) port).intValue() : 0;
            boolean fake = h.isEmpty() || h.equals("0.0.0.0") || h.startsWith("127.") || h.equals("::") || p <= 1;
            if (!fake) return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Server s : list) {
            if (sb.length() > 0) sb.append(" / ");
            sb.append(s.name);
        }
        return sb.toString();
    }

    private static String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }


    /* ---------- notification: server, time and speed; on Android 16 also a Live Update (Samsung Now Bar) ---------- */

    private ScheduledExecutorService ticker;
    private long tickDown = -1, tickUp, tickAt, downRate, upRate;
    private String lastNotif = "";

    /** Speed in the notification, every 2 seconds while connected. */
    private void startTicker() {
        tickDown = -1;
        downRate = upRate = 0;
        lastNotif = "";
        liveLogged = false;
        ticker = Executors.newSingleThreadScheduledExecutor();
        ticker.scheduleWithFixedDelay(new Runnable() {
            @Override public void run() {
                try {
                    Clash c = clash;
                    if (!running || c == null || AppState.vpn != AppState.ON) {
                        tickDown = -1;
                        return;
                    }
                    long[] t = c.totals();
                    long now = System.currentTimeMillis();
                    if (tickDown >= 0 && t[0] >= tickDown && now > tickAt) {
                        downRate = (t[0] - tickDown) * 1000 / (now - tickAt);
                        upRate = (t[1] - tickUp) * 1000 / (now - tickAt);
                    }
                    tickDown = t[0];
                    tickUp = t[1];
                    tickAt = now;
                    updateNotification();
                } catch (Throwable ignored) {
                }
            }
        }, 2, 2, TimeUnit.SECONDS);
    }

    private Notification notification(String text) {
        return notification(getString(R.string.app_name), text, 0, false);
    }

    private Notification notification(String title, String text, long when, boolean paused) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "VPN", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC);
        if (when > 0) {
            // the system counts by itself: time connected, or the pause's countdown; the status bar chip shows it
            b.setWhen(when).setShowWhen(true).setUsesChronometer(true);
            if (paused) b.setChronometerCountDown(true);
        } else {
            b.setShowWhen(false);
        }
        if (paused) {
            b.addAction(action("Продолжить", ACTION_START, 2));
        } else if (AppState.vpn == AppState.ON) {
            b.addAction(action("Пауза", ACTION_PAUSE, 3));
            b.addAction(action("Переключить", ACTION_SWITCH, 4));
        }
        b.addAction(action("Отключить", ACTION_STOP, 1));
        b.setCategory(Notification.CATEGORY_SERVICE);
        LiveUpdate.request(this, b);
        if (paused) LiveUpdate.chip(b, "Пауза");
        else if (when == 0) LiveUpdate.chip(b, AppState.vpn == AppState.WAITING ? "Ожид." : "Подкл.");
        return b.build();
    }

    private Notification.Action action(String label, String act, int code) {
        Intent i = new Intent(this, BoxVpnService.class).setAction(act);
        PendingIntent p = PendingIntent.getService(this, code, i, PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Action.Builder(null, label, p).build();
    }

    private void updateNotification() {
        String title = getString(R.string.app_name), text;
        long when = 0;
        if (AppState.vpn == AppState.ON) {
            String name = AppState.serverName.isEmpty() ? "Подключено" : AppState.serverName;
            title = (AppState.pinned.isEmpty() ? "⚡ " : "") + name;
            text = since(System.currentTimeMillis() - AppState.since);
            if (tickDown >= 0) text += " · ↑ " + Stats.rate(upRate) + "  ↓ " + Stats.rate(downRate);
            when = AppState.since;
        } else if (AppState.vpn == AppState.WAITING) {
            text = AppState.wl == AppState.WL_NONET ? "Ожидание сети" : "Нет ответа, ожидание";
        } else {
            text = "Подключение…";
        }
        String key = title + "|" + text + "|" + AppState.vpn;
        synchronized (this) {
            // after stopVpn the notification is gone; posting it again would leave a stray one
            if (key.equals(lastNotif) || !running) return;
            lastNotif = key;
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            Notification n = notification(title, text, when, false);
            nm.notify(NOTIFY_ID, n);
            if (AppState.vpn == AppState.ON && !liveLogged && LiveUpdate.supported()) {
                liveLogged = true;
                final Boolean ok = LiveUpdate.promotable(n);
                // the system decides a moment after notify, so look a little later
                try {
                    ticker.schedule(new Runnable() {
                        @Override public void run() {
                            AppState.log("Живое уведомление: подходит " + (ok == null ? "?" : ok ? "да" : "нет")
                                    + " · " + LiveUpdate.status(BoxVpnService.this, NOTIFY_ID));
                        }
                    }, 1, TimeUnit.SECONDS);
                } catch (java.util.concurrent.RejectedExecutionException ignored) {
                }
            }
        }
    }

    private boolean liveLogged;

    /** "<1 мин", "12 мин", "1 ч 5 мин". */
    private static String since(long ms) {
        long m = Math.max(0, ms) / 60000;
        if (m < 1) return "<1 мин";
        if (m < 60) return m + " мин";
        return m / 60 + " ч " + m % 60 + " мин";
    }

    /* ---------- pause from the notification: VPN off for 5 minutes, then back on by itself ---------- */

    private final AlarmManager.OnAlarmListener resume = new AlarmManager.OnAlarmListener() {
        @Override public void onAlarm() {
            if (AppState.pausedUntil == 0) return;
            AppState.pausedUntil = 0;
            AppState.log("Пауза закончилась");
            if (!running) {
                startForeground(NOTIFY_ID, notification("Подключение…"));
                startVpn();
            }
        }
    };

    private void pause() {
        if (!running) {
            if (AppState.pausedUntil == 0) stopSelf();
            return;
        }
        stopVpn("Пауза на " + PAUSE_MS / 60000 + " минут", true);
        AppState.pausedUntil = System.currentTimeMillis() + PAUSE_MS;
        AppState.changed();
        // the service stays in the foreground, so turning the VPN back on needs no start from the background
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, android.os.SystemClock.elapsedRealtime() + PAUSE_MS,
                "dash-resume", resume, null);
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(NOTIFY_ID, pauseNotification());
    }

    private Notification pauseNotification() {
        return notification("Пауза", "VPN включится сам через " + PAUSE_MS / 60000 + " минут", AppState.pausedUntil, true);
    }

    private void cancelPause() {
        if (AppState.pausedUntil == 0) return;
        AppState.pausedUntil = 0;
        ((AlarmManager) getSystemService(Context.ALARM_SERVICE)).cancel(resume);
        AppState.changed();
    }
}
