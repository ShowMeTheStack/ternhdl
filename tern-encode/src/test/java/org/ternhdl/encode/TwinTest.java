package org.ternhdl.encode;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.ternhdl.core.*;
import org.ternhdl.sim.*;

class TwinTest {
    @Test
    void everyOperationOnEveryLegalInputMatchesUnderEachEncoding() throws Exception {
        for (Encoding enc : Encoding.values()) {
            Design d = SampleDesigns.allOps();
            TwinRunner tw = new TwinRunner(d, new SvEmitter.Options(enc, true, false), TwinRunner.workRoot().resolve("allops-" + enc));
            // enumerate all legal combinations: 5 single trits (3^5), two 2-trit vectors (9*9), two bits (4)
            List<Map<String, Logic3[]>> stim = new ArrayList<>();
            String[] trit1 = {"a", "b", "c", "s", "cin"};
            int total = 243 * 81 * 4;
            for (int k = 0; k < total; k++) {
                Map<String, Logic3[]> m = new LinkedHashMap<>();
                int v = k;
                for (String t : trit1) { m.put(t, new Logic3[]{Logic3.ofBal(v % 3 - 1)}); v /= 3; }
                for (String t : new String[]{"x", "y"}) { m.put(t, new Logic3[]{Logic3.ofBal(v % 3 - 1), Logic3.ofBal(v / 3 % 3 - 1)}); v /= 9; }
                for (String t : new String[]{"bt", "bu"}) { m.put(t, new Logic3[]{v % 2 == 1 ? Logic3.P : Logic3.O}); v /= 2; }
                stim.add(m);
            }
            List<String> bad = TwinCheck.check(d, tw, stim, new Random(1));
            assertTrue(bad.isEmpty(), enc + ": " + bad.size() + " mismatches, first: " + bad.subList(0, Math.min(5, bad.size())));
        }
    }

    @Test
    void illegalCodeTripsTheCheckedModeAssertion() throws Exception {
        Design d = new Design("illegal");
        Sig a = d.input("a", Type.TRIT);
        d.output("y", a.neg());
        TwinRunner tw = new TwinRunner(d, new SvEmitter.Options(Encoding.E1, true, false), TwinRunner.workRoot().resolve("illegal-E1"));
        TwinRunner.Result ok = tw.runRaw(List.of("1", "2", "0"));
        assertEquals(0, ok.exit(), ok.stderr());
        assertEquals(List.of("2", "1", "0"), ok.lines());          // E1: STI swaps the two bits
        TwinRunner.Result bad = tw.runRaw(List.of("3"));
        assertTrue(bad.exit() != 0, "exit " + bad.exit() + " stderr " + bad.stderr());   // Verilator aborts (exit 134) on the failed assertion
    }

    @Test
    void fewRandomDesignsRefineTheirTwins() throws Exception {
        for (int seed = 0; seed < 4; seed++) {
            Random r = new Random(1000 + seed);
            Design d = RandomDesign.random(r, seed % 2 == 0, 25, 24);
            Encoding enc = Encoding.values()[seed % 3];
            TwinRunner tw = new TwinRunner(d, new SvEmitter.Options(enc, true, true), TwinRunner.workRoot().resolve("quick-" + seed));
            List<String> bad = TwinCheck.check(d, tw, TwinCheck.randomStimulus(d, r, 10), r);
            assertTrue(bad.isEmpty(), "seed " + seed + " " + enc + ": " + bad.subList(0, Math.min(5, bad.size())));
        }
    }
}
