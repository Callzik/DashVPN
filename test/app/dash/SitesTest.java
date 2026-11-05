package app.dash;

import java.util.Arrays;

/** What the "sites without VPN" field accepts. */
public final class SitesTest {
    public static void main(String[] args) {
        eq(Sites.normalize("https://www.YouTube.com/watch?v=1"), "youtube.com");
        eq(Sites.normalize("  m.vk.com  "), "m.vk.com");
        eq(Sites.normalize("*.example.org"), "example.org");
        eq(Sites.normalize("http://user@site.io:8443/a"), "site.io");
        eq(Sites.normalize("пример.рф"), "xn--e1afmkfd.xn--p1ai");
        eq(Sites.label("xn--e1afmkfd.xn--p1ai"), "пример.рф");
        eq(Sites.normalize("8.8.8.8"), "8.8.8.8");
        eq(Sites.normalize("10.0.0.0/8"), "10.0.0.0/8");
        eq(Sites.normalize("https://1.2.3.4/path"), "1.2.3.4");
        eq(Sites.normalize("300.1.1.1"), null);
        eq(Sites.normalize("hello"), null);
        eq(Sites.normalize("bad_name.com"), null);
        eq(Sites.normalize(""), null);
        check(Sites.parse("a.com\nb.com, a.com junk").equals(Arrays.asList("a.com", "b.com")), "parse list");
        check(Sites.domains(Arrays.asList("a.com", "1.1.1.1")).equals(Arrays.<Object>asList("a.com")), "domains");
        check(Sites.cidrs(Arrays.asList("a.com", "1.1.1.1", "2.0.0.0/8")).equals(Arrays.<Object>asList("1.1.1.1/32", "2.0.0.0/8")), "cidrs");
        System.out.println("SitesTest: ok");
    }

    private static void eq(String got, String want) {
        check(want == null ? got == null : want.equals(got), "got " + got + ", want " + want);
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }
}
