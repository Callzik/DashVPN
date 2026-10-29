package app.dash;

import java.util.Locale;

/**
 * Traffic and expiry from the panel's subscription-userinfo header
 * ("upload=1; download=2; total=3; expire=1700000000"). Zero total means unlimited, zero expire means forever.
 */
public final class SubInfo {
    public long used, total; // bytes
    public long expire; // unix seconds, 0 = no end date

    /** null when the header is missing or says nothing useful. */
    public static SubInfo parse(String h) {
        if (h == null || h.trim().isEmpty()) return null;
        SubInfo s = new SubInfo();
        boolean any = false;
        for (String part : h.split(";")) {
            int eq = part.indexOf('=');
            if (eq < 0) continue;
            String k = part.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            long v;
            try {
                v = (long) Double.parseDouble(part.substring(eq + 1).trim());
            } catch (NumberFormatException e) {
                continue;
            }
            if (v < 0) v = 0;
            if (k.equals("upload") || k.equals("download")) { s.used += v; any = true; }
            else if (k.equals("total")) { s.total = v; any = true; }
            else if (k.equals("expire")) { s.expire = v; any = true; }
        }
        if (!any || (s.total == 0 && s.expire == 0)) return null;
        return s;
    }

    /** Whole days left, rounded up; -1 when there is no end date. */
    public int daysLeft(long nowMs) {
        if (expire == 0) return -1;
        long left = expire * 1000 - nowMs;
        if (left <= 0) return 0;
        return (int) ((left + 86_399_999L) / 86_400_000L);
    }

    /** Share of the traffic spent, 0..1; 0 when unlimited. */
    public float usedPart() {
        if (total <= 0) return 0;
        return Math.min(1f, (float) used / total);
    }

    public boolean over(long nowMs) {
        return (expire != 0 && expire * 1000 <= nowMs) || (total > 0 && used >= total);
    }

    /** Less than 3 days or 10% of the traffic left. */
    public boolean low(long nowMs) {
        if (over(nowMs)) return false;
        int d = daysLeft(nowMs);
        return (d >= 0 && d < 3) || (total > 0 && total - used < total / 10);
    }

    /** "12 / 50 ГБ · 38 дн", "∞ · 120 дн", "закончилась". */
    public String text(long nowMs) {
        if (over(nowMs)) return "закончилась";
        String t = total > 0 ? gb(used) + " / " + gb(total) + " ГБ" : "∞";
        int d = daysLeft(nowMs);
        return d >= 0 ? t + " · " + d + " дн" : t;
    }

    private static String gb(long bytes) {
        double g = bytes / 1073741824.0;
        if (g >= 10) return String.valueOf(Math.round(g));
        String t = String.format(Locale.ROOT, "%.1f", g);
        return t.endsWith(".0") ? t.substring(0, t.length() - 2) : t;
    }
}
