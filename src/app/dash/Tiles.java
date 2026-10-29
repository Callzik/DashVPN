package app.dash;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Main screen tiles on a 4-column grid: each shown tile has a cell, a width (1–4 columns) and a height (1–2 rows).
 * Stored as "server:0,0,4,1;wl:0,1,2,1;!ip" ("!" = hidden). Tiles never overlap and float up to the first free row.
 */
final class Tiles {
    private Tiles() {}

    static final String WL = "wl", SPEED = "speed", TRAFFIC = "traffic", IP = "ip", SERVER = "server";
    // the ping tile is gone: ping sits in the server card; a saved "ping" is skipped as unknown
    static final String[] ALL = {WL, SPEED, TRAFFIC, IP, SERVER};
    static final int COLS = 4, MAX_H = 2;

    static final class Item {
        final String id;
        boolean on;
        int c, r, w, h;
        Item(String id, boolean on, int c, int r, int w, int h) {
            this.id = id; this.on = on; this.c = c; this.r = r; this.w = w; this.h = h;
        }
        Item copy() { return new Item(id, on, c, r, w, h); }
        boolean overlaps(Item o) {
            return c < o.c + o.w && o.c < c + w && r < o.r + o.h && o.r < r + h;
        }
    }

    static String title(String id) {
        switch (id) {
            case WL: return "Белые списки";
            case SPEED: return "Скорость";
            case TRAFFIC: return "Трафик";
            case IP: return "IP и страна";
            case SERVER: return "Сервер";
        }
        return id;
    }

    /** Width a tile gets when it is added or migrated from the old list. */
    static int defaultW(String id) {
        return IP.equals(id) || SERVER.equals(id) ? 4 : 2;
    }

    static List<Item> load(Prefs p) {
        List<Item> out = new ArrayList<>();
        String grid = p.homeGrid();
        if (grid != null) {
            for (String part : grid.split(";")) {
                part = part.trim();
                boolean on = !part.startsWith("!");
                if (!on) part = part.substring(1);
                String[] kv = part.split(":");
                if (!known(kv[0]) || find(out, kv[0]) != null) continue;
                Item it = new Item(kv[0], on, 0, 999, defaultW(kv[0]), 1);
                if (kv.length > 1) {
                    String[] n = kv[1].split(",");
                    try {
                        it.c = Integer.parseInt(n[0]); it.r = Integer.parseInt(n[1]);
                        it.w = Integer.parseInt(n[2]); it.h = Integer.parseInt(n[3]);
                    } catch (Exception ignored) {}
                }
                out.add(it);
            }
        } else {
            migrate(p.homeTiles(), out);
        }
        // tiles added in newer versions show up at the bottom
        for (String id : ALL) if (find(out, id) == null) out.add(new Item(id, true, 0, 999, defaultW(id), 1));
        for (Item it : out) clamp(it);
        settle(out, null);
        return out;
    }

    /** The old "wl,!speed,ip" list: narrow tiles two per row, wide ones a whole row, in that order. */
    private static void migrate(String raw, List<Item> out) {
        List<String> order = new ArrayList<>();
        List<Boolean> on = new ArrayList<>();
        if (raw != null) {
            for (String part : raw.split(",")) {
                part = part.trim();
                boolean o = !part.startsWith("!");
                String id = o ? part : part.substring(1);
                if (known(id) && !order.contains(id)) { order.add(id); on.add(o); }
            }
        } else {
            for (String id : new String[]{WL, SPEED, TRAFFIC, IP, SERVER}) { order.add(id); on.add(true); }
        }
        int row = 0, col = 0;
        for (int i = 0; i < order.size(); i++) {
            String id = order.get(i);
            int w = defaultW(id);
            if (!on.get(i)) { out.add(new Item(id, false, 0, 0, w, 1)); continue; }
            if (col + w > COLS) { row++; col = 0; }
            out.add(new Item(id, true, col, row, w, 1));
            col += w;
            if (col >= COLS) { row++; col = 0; }
        }
    }

    static void save(Prefs p, List<Item> items) {
        StringBuilder sb = new StringBuilder();
        for (Item it : items) {
            if (sb.length() > 0) sb.append(';');
            if (!it.on) sb.append('!');
            sb.append(it.id).append(':').append(it.c).append(',').append(it.r).append(',').append(it.w).append(',').append(it.h);
        }
        p.homeGrid(sb.toString());
    }

    static String signature(Prefs p) {
        StringBuilder sb = new StringBuilder();
        for (Item it : load(p)) sb.append(it.on ? "" : "!").append(it.id).append(',');
        return sb.toString();
    }

    static Item find(List<Item> items, String id) {
        for (Item it : items) if (it.id.equals(id)) return it;
        return null;
    }

    static void clamp(Item it) {
        it.w = Math.max(1, Math.min(COLS, it.w));
        it.h = Math.max(1, Math.min(MAX_H, it.h));
        it.c = Math.max(0, Math.min(COLS - it.w, it.c));
        it.r = Math.max(0, it.r);
    }

    /**
     * Makes the grid valid again after {@code fixed} moved or changed size: tiles it covers are pushed down below it
     * (and whatever they then cover, too), then rows nobody uses are dropped. Gaps inside a row stay, like on a phone
     * home screen.
     */
    static void settle(List<Item> items, Item fixed) {
        settle(items, fixed, true);
    }

    /** {@code dropEmpty} false while a tile is being dragged: rows must not shift under the finger. */
    static void settle(List<Item> items, Item fixed, boolean dropEmpty) {
        List<Item> shown = new ArrayList<>();
        for (Item it : items) if (it.on) shown.add(it);
        // top tiles keep their place, lower ones move; the fixed tile never moves
        Collections.sort(shown, new Comparator<Item>() {
            @Override public int compare(Item x, Item y) {
                if (x.r != y.r) return x.r - y.r;
                return x.c - y.c;
            }
        });
        if (fixed != null && shown.remove(fixed)) shown.add(0, fixed);
        List<Item> placed = new ArrayList<>();
        for (Item it : shown) {
            while (collides(it, placed)) it.r++;
            placed.add(it);
        }
        if (!dropEmpty) return;
        // drop empty rows
        int rows = rows(items);
        for (int r = rows - 1; r >= 0; r--) {
            boolean used = false;
            for (Item it : shown) if (it.r <= r && r < it.r + it.h) { used = true; break; }
            if (!used) for (Item it : shown) if (it.r > r) it.r--;
        }
    }

    private static boolean collides(Item it, List<Item> others) {
        for (Item o : others) if (o != it && it.overlaps(o)) return true;
        return false;
    }

    /** Rows used by shown tiles. */
    static int rows(List<Item> items) {
        int n = 0;
        for (Item it : items) if (it.on) n = Math.max(n, it.r + it.h);
        return n;
    }

    /** First free spot for a tile of this size, scanning rows top to bottom. */
    static void place(List<Item> items, Item it) {
        for (int r = 0; ; r++) {
            for (int c = 0; c + it.w <= COLS; c++) {
                it.c = c;
                it.r = r;
                boolean free = true;
                for (Item o : items) if (o != it && o.on && it.overlaps(o)) { free = false; break; }
                if (free) return;
            }
        }
    }

    private static boolean known(String id) {
        for (String a : ALL) if (a.equals(id)) return true;
        return false;
    }
}
