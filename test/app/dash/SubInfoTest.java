package app.dash;

/** subscription-userinfo parsing. */
public final class SubInfoTest {
    private static final long NOW = 1_800_000_000_000L;
    private static final long GB = 1073741824L;

    public static void main(String[] args) {
        long exp = NOW / 1000 + 38 * 86400 - 100;
        SubInfo a = SubInfo.parse("upload=" + 2 * GB + "; download=" + 10 * GB + "; total=" + 50 * GB + "; expire=" + exp);
        check(a != null && a.used == 12 * GB && a.total == 50 * GB, "parsed");
        check(a.daysLeft(NOW) == 38, "days rounded up");
        check("12 / 50 ГБ · 38 дн".equals(a.text(NOW)), "text: " + a.text(NOW));
        check(!a.low(NOW) && !a.over(NOW), "fine");

        SubInfo unl = SubInfo.parse("upload=0; download=123; total=0; expire=" + (NOW / 1000 + 120 * 86400));
        check("∞ · 120 дн".equals(unl.text(NOW)), "unlimited: " + unl.text(NOW));
        check(unl.usedPart() == 0, "unlimited part");

        SubInfo low = SubInfo.parse("upload=0;download=" + 93 * GB + ";total=" + 100 * GB + ";expire=0");
        check(low.low(NOW) && low.daysLeft(NOW) == -1, "low traffic, no end date");
        check("93 / 100 ГБ".equals(low.text(NOW)), "no days: " + low.text(NOW));
        check(SubInfo.parse("upload=0; download=0; total=0; expire=" + (NOW / 1000 + 2 * 86400)).low(NOW), "2 days is low");

        SubInfo old = SubInfo.parse("upload=1; download=1; total=0; expire=" + (NOW / 1000 - 10));
        check(old.over(NOW) && "закончилась".equals(old.text(NOW)), "expired");
        check(SubInfo.parse("download=" + 5 * GB + "; total=" + 5 * GB).over(NOW), "traffic spent");

        check(SubInfo.parse(null) == null && SubInfo.parse("") == null, "missing");
        check(SubInfo.parse("upload=1; download=2; total=0; expire=0") == null, "nothing useful");
        check(SubInfo.parse("garbage") == null, "garbage");
        check(SubInfo.parse("total=5.36870912E10; expire=abc") != null, "float total");
        check(SubInfo.parse("download=3; total=" + GB).text(NOW).equals("0 / 1 ГБ"), "tiny use");
        check("1.5".equals(SubInfo.parse("download=" + (3 * GB / 2) + "; total=" + 2 * GB).text(NOW).split(" ")[0]), "fraction GB");

        System.out.println("SubInfoTest: ok");
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }
}
