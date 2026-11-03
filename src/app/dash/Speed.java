package app.dash;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Speed check: the lowest ping is not always the fastest (an overloaded server answers quickly but downloads
 * slowly). A test file is downloaded through the best servers by ping via the core's speed-in proxy,
 * where the login (= server tag) sends the request to that server only. Two rounds: a short download through
 * up to 20 servers, a few at a time, then the leaders and the current one again one by one, so they do not
 * share the line and the comparison is fair.
 */
public final class Speed {
    static String url = "https://speed.cloudflare.com/__down?bytes=2000000";
    /** Bytes the speed and load checks downloaded, so the traffic shown to the user leaves them out. */
    private static final AtomicLong spent = new AtomicLong();

    static long spent() { return spent.get(); }

    /** Best servers by ping that get a test download. */
    static final int CANDIDATES = 20;
    /** Leaders of the quick round that are measured again one by one (plus the current server). */
    static final int FINALISTS = 3;
    /** Quick round downloads running at once. */
    static final int PARALLEL = 4;
    /** Spare servers the load check tries when the current one stalls. */
    static final int SPARES = 3;
    /** A download stops after this long even if unfinished. */
    static final long WINDOW_MS = 4000;
    /** The quick round: a smaller file and a shorter window. */
    static String quickUrl = "https://speed.cloudflare.com/__down?bytes=1000000";
    static final long QUICK_WINDOW_MS = 2000;

    /**
     * Load check: a server can answer the watchdog's tiny ping request and still stall every real download
     * (censors freeze such connections after the first 16–20 KB). A small file has to arrive in full.
     */
    static String loadUrl = "https://speed.cloudflare.com/__down?bytes=131072";
    static final long LOAD_MIN = 100 * 1024;
    static final long LOAD_WINDOW_MS = 10000;

    private Speed() {}

    /** Mbit/s through one server, 0 on failure. */
    public static double measure(int port, String tag, String secret) {
        return measure(url, WINDOW_MS, port, tag, secret);
    }

    private static double measure(String url, long windowMs, int port, String tag, String secret) {
        try {
            double[] r = download(url, windowMs, port, tag, secret);
            if (r[1] < 64 * 1024 || r[2] <= 0) return 0;
            return r[1] * 8 / r[2] / 1e6;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * The quick round: a short download through every tag, PARALLEL at a time. Stops early when alive turns
     * false (the VPN went off). Returns tag → Mbit/s for the tags that were measured.
     */
    public static Map<String, Double> quick(List<String> tags, final int port, final String secret, final Alive alive) {
        final Map<String, Double> out = new ConcurrentHashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Math.min(PARALLEL, tags.size())));
        try {
            for (final String t : tags) {
                pool.execute(new Runnable() {
                    @Override public void run() {
                        if (alive.ok()) out.put(t, measure(quickUrl, QUICK_WINDOW_MS, port, t, secret));
                    }
                });
            }
            pool.shutdown();
            pool.awaitTermination(5, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdownNow();
        }
        return out;
    }

    /** Whether a long check should go on. */
    public interface Alive {
        boolean ok();
    }

    /** The FINALISTS fastest of the quick round that downloaded anything, plus the current server. */
    static List<String> finalists(final Map<String, Double> quick, String cur) {
        List<String> top = new ArrayList<>();
        for (Map.Entry<String, Double> e : quick.entrySet()) if (e.getValue() > 0) top.add(e.getKey());
        Collections.sort(top, new Comparator<String>() {
            @Override public int compare(String a, String b) {
                int c = Double.compare(quick.get(b), quick.get(a));
                return c != 0 ? c : a.compareTo(b);
            }
        });
        if (top.size() > FINALISTS) top = new ArrayList<>(top.subList(0, FINALISTS));
        if (cur != null && !cur.isEmpty() && !top.contains(cur)) top.add(cur);
        return top;
    }

    /** True when the load check file comes through one server in full. */
    public static boolean loads(int port, String tag, String secret) {
        try {
            return download(loadUrl, LOAD_WINDOW_MS, port, tag, secret)[0] >= LOAD_MIN;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Downloads through one server for up to windowMs after the first data.
     * Returns {all bytes, bytes after the first chunk, seconds since the first chunk}.
     */
    private static double[] download(String url, long windowMs, int port, String tag, String secret) throws Exception {
        URL u = new URL(url);
        boolean tls = "https".equals(u.getProtocol());
        String host = u.getHost();
        int hp = u.getPort() > 0 ? u.getPort() : u.getDefaultPort();
        String file = u.getFile().isEmpty() ? "/" : u.getFile();
        String auth = java.util.Base64.getEncoder().encodeToString((tag + ":" + secret).getBytes("UTF-8"));
        Socket so = new Socket();
        try {
            so.connect(new InetSocketAddress("127.0.0.1", port), 3000);
            so.setSoTimeout(6000);
            Socket s = so;
            String target;
            if (tls) {
                OutputStream po = so.getOutputStream();
                po.write(("CONNECT " + host + ":" + hp + " HTTP/1.1\r\nHost: " + host + ":" + hp
                        + "\r\nProxy-Authorization: Basic " + auth + "\r\n\r\n").getBytes("UTF-8"));
                po.flush();
                if (!head(so.getInputStream()).contains(" 200")) return new double[3];
                SSLSocket ss = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(so, host, hp, true);
                SSLParameters sp = ss.getSSLParameters();
                sp.setEndpointIdentificationAlgorithm("HTTPS"); // check the certificate is for this host
                ss.setSSLParameters(sp);
                ss.startHandshake();
                s = ss;
                target = file;
            } else {
                target = url;
            }
            OutputStream os = s.getOutputStream();
            os.write(("GET " + target + " HTTP/1.1\r\nHost: " + host
                    + (tls ? "" : "\r\nProxy-Authorization: Basic " + auth)
                    + "\r\nUser-Agent: Dash\r\nAccept: */*\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
            os.flush();
            InputStream in = s.getInputStream();
            String h = head(in);
            if (!h.startsWith("HTTP/1.1 200") && !h.startsWith("HTTP/1.0 200")) return new double[3];
            // the clock starts at the first data, so the server's ping does not count against its speed
            byte[] buf = new byte[32 * 1024];
            long start = 0, n = 0, all = 0;
            int k;
            while ((k = in.read(buf)) > 0) {
                all += k;
                spent.addAndGet(k);
                if (start == 0) start = System.nanoTime();
                else n += k;
                if ((System.nanoTime() - start) / 1000000 > windowMs) break;
            }
            if (start == 0) return new double[3];
            return new double[] {all, n, (System.nanoTime() - start) / 1e9};
        } finally {
            try {
                so.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** Reads an HTTP response head up to the blank line, byte by byte so no body is consumed. */
    private static String head(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            sb.append((char) c);
            if (sb.length() > 16384) break;
            int l = sb.length();
            if (l >= 4 && sb.charAt(l - 4) == '\r' && sb.charAt(l - 3) == '\n' && sb.charAt(l - 2) == '\r' && sb.charAt(l - 1) == '\n') break;
        }
        return sb.toString();
    }

    /** The best members by ping, plus the current one. */
    static List<String> picks(List<String> members, Map<String, Integer> delays, String cur) {
        return picks(members, delays, cur, Collections.<String>emptySet());
    }

    /** The CANDIDATES best members by ping that are not in skip, plus the current one. */
    static List<String> picks(List<String> members, Map<String, Integer> delays, String cur, Set<String> skip) {
        List<String> alive = best(members, delays, skip, CANDIDATES);
        if (cur != null && !cur.isEmpty() && !alive.contains(cur)) alive.add(cur);
        return alive;
    }

    /** The SPARES best members by ping that answer and are not in skip. */
    static List<String> spares(List<String> members, Map<String, Integer> delays, Set<String> skip) {
        return best(members, delays, skip, SPARES);
    }

    /** The n best members by ping that answer and are not in skip. */
    static List<String> best(List<String> members, final Map<String, Integer> delays, Set<String> skip, int n) {
        List<String> alive = new ArrayList<>();
        for (String t : members) if (delays.containsKey(t) && delays.get(t) > 0 && !skip.contains(t)) alive.add(t);
        Collections.sort(alive, new Comparator<String>() {
            @Override public int compare(String a, String b) { return delays.get(a) - delays.get(b); }
        });
        if (alive.size() > n) alive = new ArrayList<>(alive.subList(0, n));
        return alive;
    }

    /** The fastest result when it clearly beats the current server (1.5 times and 3 Mbit/s), else null. */
    static String faster(Map<String, Double> speeds, String cur) {
        String best = null;
        double bs = 0;
        for (Map.Entry<String, Double> e : speeds.entrySet()) {
            double v = e.getValue();
            if (v > bs || v == bs && best != null && e.getKey().compareTo(best) < 0) {
                best = e.getKey();
                bs = v;
            }
        }
        Double c = speeds.get(cur);
        double cs = c == null ? 0 : c;
        if (best == null || best.equals(cur) || bs < cs * 1.5 || bs - cs < 3) return null;
        return best;
    }

    static String mbit(double v) {
        if (v <= 0) return "не скачалось";
        return String.format(Locale.ROOT, v < 10 ? "%.1f Мбит/с" : "%.0f Мбит/с", v);
    }
}
