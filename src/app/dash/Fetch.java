package app.dash;

import java.io.Closeable;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.Locale;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * HTTPS GET for the updater, either directly or through the core's local proxy (probe-in), so that
 * GitHub is reached via the VPN server when the provider blocks it. Dash itself is excluded from the tunnel.
 */
final class Fetch implements Closeable {
    final InputStream in;
    final long length; // -1 if unknown
    private final Closeable conn;
    private String location; // redirect target, proxied requests only

    private Fetch(InputStream in, long length, Closeable conn) {
        this.in = in;
        this.length = length;
        this.conn = conn;
    }

    @Override public void close() {
        try {
            conn.close();
        } catch (Exception ignored) {
        }
    }

    /** Follows redirects. login ("user:pass" of the local proxy) == null means a direct request. */
    static Fetch open(String url, String accept, int proxyPort, String login, int readTimeout) throws Exception {
        if (login == null) return direct(url, accept, readTimeout);
        for (int hop = 0; hop < 5; hop++) {
            Fetch f = proxied(url, accept, proxyPort, login, readTimeout);
            if (f.location == null) return f;
            f.close();
            url = new URL(new URL(url), f.location).toString();
        }
        throw new IOException("слишком много перенаправлений");
    }

    /** Small text body (version.json). */
    static String text(String url, String accept, int proxyPort, String login) throws Exception {
        try (Fetch f = open(url, accept, proxyPort, login, 15000)) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = f.in.read(buf)) > 0) {
                b.write(buf, 0, n);
                if (b.size() > 256 * 1024) throw new IOException("слишком большой ответ");
            }
            return b.toString("UTF-8");
        }
    }

    private static Fetch direct(String url, String accept, int readTimeout) throws Exception {
        final HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(10000);
        h.setReadTimeout(readTimeout);
        h.setUseCaches(false);
        h.setInstanceFollowRedirects(true);
        h.setRequestProperty("User-Agent", "Dash");
        h.setRequestProperty("Accept", accept);
        int code = h.getResponseCode();
        if (code != 200) {
            h.disconnect();
            throw new IOException("HTTP " + code);
        }
        return new Fetch(h.getInputStream(), h.getContentLengthLong(), new Closeable() {
            @Override public void close() { h.disconnect(); }
        });
    }

    private static Fetch proxied(String url, String accept, int port, String login, int readTimeout) throws Exception {
        URL u = new URL(url);
        if (!"https".equals(u.getProtocol())) throw new IOException("только https");
        String host = u.getHost();
        int hp = u.getPort() > 0 ? u.getPort() : 443;
        String file = u.getFile().isEmpty() ? "/" : u.getFile();
        String auth = java.util.Base64.getEncoder().encodeToString(login.getBytes("UTF-8"));
        Socket so = new Socket();
        try {
            so.connect(new InetSocketAddress("127.0.0.1", port), 3000);
            so.setSoTimeout(readTimeout);
            OutputStream po = so.getOutputStream();
            po.write(("CONNECT " + host + ":" + hp + " HTTP/1.1\r\nHost: " + host + ":" + hp
                    + "\r\nProxy-Authorization: Basic " + auth + "\r\n\r\n").getBytes("UTF-8"));
            po.flush();
            String ph = head(so.getInputStream());
            if (!status(ph).equals("200")) throw new IOException("прокси: " + firstLine(ph));
            SSLSocket ss = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(so, host, hp, true);
            SSLParameters sp = ss.getSSLParameters();
            sp.setEndpointIdentificationAlgorithm("HTTPS"); // check the certificate is for this host
            ss.setSSLParameters(sp);
            ss.startHandshake();
            OutputStream os = ss.getOutputStream();
            os.write(("GET " + file + " HTTP/1.1\r\nHost: " + host
                    + "\r\nUser-Agent: Dash\r\nAccept: " + accept + "\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
            os.flush();
            InputStream in = ss.getInputStream();
            String h = head(in);
            String st = status(h);
            if (st.startsWith("3") && header(h, "location") != null) {
                Fetch f = new Fetch(in, -1, so);
                f.location = header(h, "location");
                return f;
            }
            if (!st.equals("200")) throw new IOException(firstLine(h));
            String te = header(h, "transfer-encoding"), cl = header(h, "content-length");
            if (te != null && te.toLowerCase(Locale.ROOT).contains("chunked")) return new Fetch(new Chunked(in), -1, so);
            long len = cl == null ? -1 : Long.parseLong(cl.trim());
            return new Fetch(len < 0 ? in : new Limited(in, len), len, so);
        } catch (Exception e) {
            so.close();
            throw e;
        }
    }

    /** Reads an HTTP response head up to the blank line, byte by byte so no body is consumed. */
    static String head(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            sb.append((char) c);
            if (sb.length() > 32768) throw new IOException("слишком длинный заголовок");
            int l = sb.length();
            if (l >= 4 && sb.charAt(l - 4) == '\r' && sb.charAt(l - 3) == '\n' && sb.charAt(l - 2) == '\r' && sb.charAt(l - 1) == '\n') break;
        }
        return sb.toString();
    }

    private static String firstLine(String h) {
        int i = h.indexOf('\r');
        return i < 0 ? h : h.substring(0, i);
    }

    static String status(String h) {
        String[] p = firstLine(h).split(" ");
        return p.length > 1 ? p[1] : "";
    }

    static String header(String h, String name) {
        for (String line : h.split("\r\n")) {
            int i = line.indexOf(':');
            if (i > 0 && line.substring(0, i).trim().equalsIgnoreCase(name)) return line.substring(i + 1).trim();
        }
        return null;
    }

    /** Stops at Content-Length, so a kept-open connection does not hang the reader. */
    private static final class Limited extends FilterInputStream {
        private long left;

        Limited(InputStream in, long len) {
            super(in);
            left = len;
        }

        @Override public int read() throws IOException {
            if (left <= 0) return -1;
            int c = in.read();
            if (c >= 0) left--;
            return c;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) return -1;
            int n = in.read(b, off, (int) Math.min(len, left));
            if (n > 0) left -= n;
            return n;
        }
    }

    /** Decodes Transfer-Encoding: chunked. */
    static final class Chunked extends InputStream {
        private final InputStream in;
        private long left; // bytes left in the current chunk
        private boolean done;

        Chunked(InputStream in) { this.in = in; }

        private String line() throws IOException {
            StringBuilder sb = new StringBuilder();
            int c;
            while ((c = in.read()) >= 0 && c != '\n') if (c != '\r') sb.append((char) c);
            if (c < 0 && sb.length() == 0) throw new IOException("обрыв ответа");
            return sb.toString();
        }

        private boolean next() throws IOException {
            if (done) return false;
            if (left == 0) {
                String s = line();
                if (s.isEmpty()) s = line(); // CRLF after the previous chunk
                int semi = s.indexOf(';');
                left = Long.parseLong((semi < 0 ? s : s.substring(0, semi)).trim(), 16);
                if (left == 0) {
                    done = true;
                    return false;
                }
            }
            return true;
        }

        @Override public int read() throws IOException {
            byte[] b = new byte[1];
            return read(b, 0, 1) < 0 ? -1 : b[0] & 0xff;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (!next()) return -1;
            int n = in.read(b, off, (int) Math.min(len, left));
            if (n < 0) throw new IOException("обрыв ответа");
            left -= n;
            return n;
        }
    }
}
