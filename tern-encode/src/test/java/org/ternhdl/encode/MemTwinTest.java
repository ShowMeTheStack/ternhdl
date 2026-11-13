package org.ternhdl.encode;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.ternhdl.core.*;

class MemTwinTest {
    @Test
    void memoryTwinMatchesNativeUnderEachEncoding() throws Exception {
        for (Encoding enc : Encoding.values()) {
            Design d = MemDesigns.twoPort();
            TwinRunner tw = new TwinRunner(d, new SvEmitter.Options(enc, true, true), TwinRunner.workRoot().resolve("mem-" + enc));
            Random r = new Random(9);
            List<Map<String, Logic3[]>> st = new ArrayList<>();
            for (int c = 0; c < 300; c++) {
                Map<String, Logic3[]> m = new LinkedHashMap<>();
                for (var en : d.inputs().entrySet()) {
                    Type t = d.node(en.getValue()).type();
                    Logic3[] v = new Logic3[t.width()];
                    for (int i = 0; i < v.length; i++) v[i] = t.isBit() ? (r.nextBoolean() ? Logic3.P : Logic3.O) : Logic3.ofBal(r.nextInt(3) - 1);
                    m.put(en.getKey(), v);
                }
                st.add(m);
            }
            List<String> bad = TwinCheck.check(d, tw, st, r);
            assertTrue(bad.isEmpty(), enc + ": " + bad.subList(0, Math.min(5, bad.size())));
        }
    }
}
