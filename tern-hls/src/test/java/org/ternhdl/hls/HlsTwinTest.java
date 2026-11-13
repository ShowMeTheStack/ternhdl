package org.ternhdl.hls;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.ternhdl.core.*;
import org.ternhdl.encode.*;

/** Encoded twins of HLS designs (ripple and prefix emission) refine the native designs under random stimulus. */
class HlsTwinTest {
    @Test
    void hlsTwinsRefineNative() throws Exception {
        String src = HlsTest.source();
        for (String kn : List.of("tmix", "poly", "gcd", "fir", "dot", "bubble", "sumsq", "matvec", "digitsum"))
            for (SvEmitter.AdderStyle st : SvEmitter.AdderStyle.values()) {
                Hls.Result res = Hls.compile(src, kn, new Hls.Options(40, st == SvEmitter.AdderStyle.PREFIX, true));
                Design d = res.design();
                TwinRunner tw = new TwinRunner(d, new SvEmitter.Options(Encoding.E1, true, false, st), TwinRunner.workRoot().resolve("hls-" + kn + "-" + st));
                Random r = new Random(kn.hashCode());
                List<Map<String, Logic3[]>> stim = new ArrayList<>();
                for (int c = 0; c < 400; c++) {
                    Map<String, Logic3[]> m = new LinkedHashMap<>();
                    for (var en : d.inputs().entrySet()) {
                        Type t = d.node(en.getValue()).type();
                        Logic3[] v = new Logic3[t.width()];
                        for (int i = 0; i < v.length; i++) v[i] = t.isBit() ? (r.nextInt(5) == 0 ? Logic3.P : Logic3.O) : Logic3.ofBal(r.nextInt(3) - 1);
                        if (en.getKey().equals("rst")) v[0] = c == 0 ? Logic3.P : Logic3.O;
                        m.put(en.getKey(), v);
                    }
                    stim.add(m);
                }
                List<String> bad = TwinCheck.check(d, tw, stim, r);
                System.out.printf("HLSTWIN %s %s: %s%n", kn, st, bad.isEmpty() ? "OK" : bad.size() + " mismatches");
                assertTrue(bad.isEmpty(), kn + " " + st + ": " + bad.subList(0, Math.min(3, bad.size())));
            }
    }
}
