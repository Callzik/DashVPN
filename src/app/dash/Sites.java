package app.dash;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Sites the user sends around the VPN: whatever is typed or pasted becomes a bare domain or an IPv4 (with /mask). */
public final class Sites {
    private static final Pattern IP4 = Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})(/\\d{1,2})?");
    private static final Pattern LABEL = Pattern.compile("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?|xn--[a-z0-9-]+");

    private Sites() {}

    /** "https://www.YouTube.com/watch?v=1" → "youtube.com"; null when it is not a site. */
    public static String normalize(String in) {
        if (in == null) return null;
        String s = in.trim().toLowerCase(java.util.Locale.ROOT);
        int i = s.indexOf("://");
        if (i >= 0) s = s.substring(i + 3);
        if (!IP4.matcher(cut(s, "?#")).matches()) s = cut(s, "/?#"); // keep a /mask of an IP, drop a page path
        else s = cut(s, "?#");
        i = s.lastIndexOf('@');
        if (i >= 0) s = s.substring(i + 1);
        i = s.indexOf(':');
        if (i >= 0) s = s.substring(0, i);
        while (s.startsWith("*.") || s.startsWith(".")) s = s.substring(s.charAt(0) == '*' ? 2 : 1);
        if (s.startsWith("www.")) s = s.substring(4);
        while (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        if (s.isEmpty()) return null;

        java.util.regex.Matcher m = IP4.matcher(s);
        if (m.matches()) {
            for (int g = 1; g <= 4; g++) if (Integer.parseInt(m.group(g)) > 255) return null;
            if (m.group(5) != null && Integer.parseInt(m.group(5).substring(1)) > 32) return null;
            return s;
        }
        try {
            s = java.net.IDN.toASCII(s, java.net.IDN.ALLOW_UNASSIGNED);
        } catch (Exception e) {
            return null;
        }
        String[] labels = s.split("\\.", -1);
        if (labels.length < 2 || s.length() > 253) return null;
        for (String l : labels) if (!LABEL.matcher(l).matches()) return null;
        if (labels[labels.length - 1].matches("\\d+")) return null;
        return s;
    }

    private static String cut(String s, String chars) {
        int end = s.length();
        for (char c : chars.toCharArray()) {
            int i = s.indexOf(c);
            if (i >= 0 && i < end) end = i;
        }
        return s.substring(0, end);
    }

    public static boolean isIp(String site) {
        return IP4.matcher(site).matches();
    }

    /** How the site is shown: Cyrillic domains in Cyrillic. */
    public static String label(String site) {
        try {
            return java.net.IDN.toUnicode(site);
        } catch (Exception e) {
            return site;
        }
    }

    /** Parses a stored or pasted list (one per line, spaces or commas also split), dropping junk and repeats. */
    public static List<String> parse(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text != null) {
            for (String part : text.split("[\\s,;]+")) {
                String n = normalize(part);
                if (n != null) out.add(n);
            }
        }
        return new ArrayList<>(out);
    }

    /** Domains for sing-box domain_suffix (the site and all its subdomains). */
    public static List<Object> domains(List<String> sites) {
        List<Object> out = new ArrayList<>();
        for (String s : sites) if (!isIp(s)) out.add(s);
        return out;
    }

    /** Addresses for sing-box ip_cidr. */
    public static List<Object> cidrs(List<String> sites) {
        List<Object> out = new ArrayList<>();
        for (String s : sites) if (isIp(s)) out.add(s.contains("/") ? s : s + "/32");
        return out;
    }
}
