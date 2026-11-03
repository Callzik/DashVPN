package app.dash;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Choice rules of the speed check. */
public final class SpeedTest {
    public static void main(String[] args) {
        Map<String, Integer> d = new HashMap<>();
        d.put("srv1", 300);
        d.put("srv2", 90);
        d.put("srv3", 120);
        d.put("srv5", 60);
        List<String> members = Arrays.asList("srv1", "srv2", "srv3", "srv4", "srv5");
        check(Speed.picks(members, d, "srv2").equals(Arrays.asList("srv5", "srv2", "srv3", "srv1")), "all answering by ping");
        check(Speed.picks(members, d, "srv4").equals(Arrays.asList("srv5", "srv2", "srv3", "srv1", "srv4")), "current added");
        Set<String> frozen = new HashSet<>(Arrays.asList("srv5"));
        check(Speed.picks(members, d, "srv2", frozen).equals(Arrays.asList("srv2", "srv3", "srv1")), "stalled server left out");
        check(Speed.spares(members, d, new HashSet<>(Arrays.asList("srv5", "srv2"))).equals(Arrays.asList("srv3", "srv1")),
                "spares skip the stalled and the current");
        check(Speed.spares(members, d, new HashSet<>(members)).isEmpty(), "no spares");

        Map<String, Integer> many = new HashMap<>();
        List<String> all = new java.util.ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            all.add("s" + i);
            many.put("s" + i, 1000 - i);
        }
        List<String> p = Speed.picks(all, many, "s1");
        check(p.size() == Speed.CANDIDATES + 1 && p.get(0).equals("s30") && p.contains("s1"), "20 best by ping plus current");
        check(Speed.spares(all, many, new HashSet<String>()).size() == Speed.SPARES, "load check spares stay few");

        check(Speed.finalists(speeds("a", 5, "b", 40, "c", 20, "d", 30, "e", 0), "a").equals(Arrays.asList("b", "d", "c", "a")),
                "three fastest plus current");
        check(Speed.finalists(speeds("a", 50, "b", 40, "c", 20, "d", 30), "a").equals(Arrays.asList("a", "b", "d")),
                "current among the fastest");
        check(Speed.finalists(speeds("a", 0, "b", 0), "a").equals(Arrays.asList("a")), "nothing downloaded");

        check("b".equals(Speed.faster(speeds("a", 3, "b", 40, "c", 20), "a")), "overloaded current");
        check(Speed.faster(speeds("a", 30, "b", 40), "a") == null, "not 1.5 times faster");
        check(Speed.faster(speeds("a", 1, "b", 2.5), "a") == null, "only 1.5 Mbit/s more");
        check("b".equals(Speed.faster(speeds("a", 0, "b", 5), "a")), "current did not download");
        check(Speed.faster(speeds("a", 50, "b", 10), "a") == null, "current is the fastest");
        check(Speed.faster(speeds("a", 0, "b", 0), "a") == null, "nothing downloaded");
        System.out.println("SpeedTest: ok");
    }

    private static Map<String, Double> speeds(Object... kv) {
        Map<String, Double> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], ((Number) kv[i + 1]).doubleValue());
        return m;
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }
}
