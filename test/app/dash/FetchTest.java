package app.dash;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** Response parsing of the updater's proxied requests. */
public final class FetchTest {
    public static void main(String[] args) throws Exception {
        String raw = "HTTP/1.1 302 Found\r\nlocation: https://x/y\r\nContent-Length: 0\r\n\r\nrest";
        InputStream in = new ByteArrayInputStream(raw.getBytes("UTF-8"));
        String h = Fetch.head(in);
        check(Fetch.status(h).equals("302"), "status");
        check("https://x/y".equals(Fetch.header(h, "Location")), "header name ignores case");
        check(Fetch.header(h, "etag") == null, "missing header");
        check(read(in).equals("rest"), "head leaves the body");

        String chunked = "5\r\nhello\r\n7;ext=1\r\n, world\r\n0\r\n\r\n";
        check(read(new Fetch.Chunked(new ByteArrayInputStream(chunked.getBytes("UTF-8")))).equals("hello, world"), "chunked");
        System.out.println("FetchTest: ok");
    }

    private static String read(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[3];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toString("UTF-8");
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }
}
