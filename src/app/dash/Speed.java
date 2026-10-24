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

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Speed check: the lowest ping is not always the fastest (an overloaded server answers quickly but downloads
 * slowly). A test file is downloaded through the best few servers by ping via the core's speed-in proxy,
 * where the login (= server tag) sends the request to that server only.
 */
public final class Speed {
    static String url = "https://speed.cloudflare.com/__down?bytes=2000000";
    /** Best servers by ping that get a test download. */
    static final int CANDIDATES = 3;
    /** A download stops after this long even if unfinished. */
    static final long WINDOW_MS = 4000;

    private Speed() {}

    /** Mbit/s through one server, 0 on failure. */
    public static double measure(int port, String tag, String secret) {
        try {
            return download(port, tag, secret);
        } catch (Exception e) {
            return 0;
        }
    }

    private static double download(int port, String tag, String secret) throws Exception {
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
                if (!head(so.getInputStream()).contains(" 200")) return 0;
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
            if (!h.startsWith("HTTP/1.1 200") && !h.startsWith("HTTP/1.0 200")) return 0;
            // the clock starts at the first data, so the server's ping does not count against its speed
            byte[] buf = new byte[32 * 1024];
            long start = 0, n = 0;
            int k;
            while ((k = in.read(buf)) > 0) {
                if (start == 0) start = System.nanoTime();
                else n += k;
                if ((System.nanoTime() - start) / 1000000 > WINDOW_MS) break;
            }
            double sec = (System.nanoTime() - start) / 1e9;
            if (start == 0 || n < 64 * 1024 || sec <= 0) return 0;
            return n * 8 / sec / 1e6;
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

    /** The best few members by ping, plus the current one. */
    static List<String> picks(List<String> members, final Map<String, Integer> delays, String cur) {
        List<String> alive = new ArrayList<>();
        for (String t : members) if (delays.containsKey(t) && delays.get(t) > 0) alive.add(t);
        Collections.sort(alive, new Comparator<String>() {
            @Override public int compare(String a, String b) { return delays.get(a) - delays.get(b); }
        });
        if (alive.size() > CANDIDATES) alive = new ArrayList<>(alive.subList(0, CANDIDATES));
        if (cur != null && !cur.isEmpty() && !alive.contains(cur)) alive.add(cur);
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
