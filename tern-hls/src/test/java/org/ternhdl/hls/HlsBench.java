package org.ternhdl.hls;

import java.util.*;
import org.ternhdl.core.*;

/** HLS ablations (not a test): states, temps, IR nodes, cycles for option variants; correctness re-checked. */
public final class HlsBench {
    public static void main(String[] a) throws Exception {
        String src = HlsTest.source();
        Class<?> g = HlsTest.golden();
        System.out.println("| Kernel | Variant | States | Temps | Ops | IR nodes | Avg cycles | Check |");
        System.out.println("|---|---|---|---|---|---|---|---|");
        Map<String, Hls.Options> variants = new LinkedHashMap<>();
        variants.put("budget 40, ripple, strength-reduce", new Hls.Options(40, false, true));
        variants.put("budget 40, ripple, no strength-reduce", new Hls.Options(40, false, false));
        variants.put("budget 40, prefix cost model", new Hls.Options(40, true, true));
        variants.put("budget 15 (deeper pipelining of the FSM)", new Hls.Options(15, false, true));
        variants.put("budget 120 (more chaining)", new Hls.Options(120, false, true));
        for (String kn : List.of("poly", "fir", "dot", "sumsq", "gcd", "bubble", "tmix", "matvec", "digitsum")) for (var v : variants.entrySet()) {
            Hls.Result res = Hls.compile(src, kn, v.getValue());
            long[] st = HlsCheck.check(g, res, kn, 15, new Random(7));
            System.out.printf("| %s | %s | %d | %d | %d | %d | %.1f | %s |%n", kn, v.getKey(), res.states(), res.temps(), res.ops(), res.design().nodes().size(), st[0] / 15.0, st[1] == 0 ? "OK" : "FAIL " + st[1]);
        }
    }
}
