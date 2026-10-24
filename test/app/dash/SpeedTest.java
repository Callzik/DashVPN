package app.dash;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Choice rules of the speed check. */
public final class SpeedTest {
    public static void main(String[] args) {
        Map<String, Integer> d = new HashMap<>();
        d.put("srv1", 300);
        d.put("srv2", 90);
        d.put("srv3", 120);
        d.put("srv5", 60);
        List<String> members = Arrays.asList("srv1", "srv2", "srv3", "srv4", "srv5");
        check(Speed.picks(members, d, "srv2").equals(Arrays.asList("srv5", "srv2", "srv3")), "current among the best");
        check(Speed.picks(members, d, "srv1").equals(Arrays.asList("srv5", "srv2", "srv3", "srv1")), "current added");

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
