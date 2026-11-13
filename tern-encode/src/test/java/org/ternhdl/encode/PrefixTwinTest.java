package org.ternhdl.encode;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.ternhdl.core.*;

/** ADD nodes emitted as parallel-prefix adders: twin vs native on random legal inputs (widths 4..54). */
class PrefixTwinTest {
    @Test
    void prefixAddersMatchNative() throws Exception {
        for (int n : new int[]{4, 5, 9, 27, 32}) {
            Design d = new Design("pfx" + n);
            Sig a = d.input("a", Type.trits(n)), b = d.input("b", Type.trits(n)), c = d.input("cin", Type.TRIT);
            d.output("s", a.add(b, c));
            d.output("t", a.add(b));
            for (Encoding enc : Encoding.values()) {
                TwinRunner tw = new TwinRunner(d, new SvEmitter.Options(enc, true, false, SvEmitter.AdderStyle.PREFIX), TwinRunner.workRoot().resolve("pfx-" + n + "-" + enc));
                Random r = new Random(n);
                List<Map<String, Logic3[]>> st = new ArrayList<>();
                for (int k = 0; k < 2000; k++) {
                    Map<String, Logic3[]> m = new LinkedHashMap<>();
                    for (var en : d.inputs().entrySet()) {
                        Logic3[] v = new Logic3[d.node(en.getValue()).type().width()];
                        for (int i = 0; i < v.length; i++) v[i] = Logic3.ofBal(r.nextInt(3) - 1);
                        m.put(en.getKey(), v);
                    }
                    st.add(m);
                }
                List<String> bad = TwinCheck.check(d, tw, st, r);
                assertTrue(bad.isEmpty(), n + " " + enc + ": " + bad.subList(0, Math.min(3, bad.size())));
            }
        }
    }
}
