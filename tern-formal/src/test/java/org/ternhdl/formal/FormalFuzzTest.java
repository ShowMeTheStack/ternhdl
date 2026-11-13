package org.ternhdl.formal;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ternhdl.core.*;
import org.ternhdl.encode.*;
import org.ternhdl.sim.RandomDesign;

/** Formal T2 on random designs: every node of the twin equals the reference model for all legal inputs and states. */
@Tag("slow")
class FormalFuzzTest {
    @Test
    void randomDesignTwinsEqualTheirReferences() throws Exception {
        int designs = Integer.getInteger("tern.ffuzz.designs", 120);
        int par = Integer.getInteger("tern.ffuzz.parallel", 24);
        String solver = System.getProperty("tern.ffuzz.solver", "z3");
        ExecutorService ex = Executors.newFixedThreadPool(par);
        List<Future<String[]>> fs = new ArrayList<>();
        long t0 = System.nanoTime();
        for (int seed = 0; seed < designs; seed++) {
            final int sd = seed;
            fs.add(ex.submit(() -> {
                Design d = RandomDesign.random(new Random(70_000 + sd), sd % 3 != 0, 30, 24);
                Encoding enc = Encoding.values()[sd % 3];
                Sby.Result r = Equivalence.twinRefinesReference(d, enc, solver, 600, "ffuzz-" + solver + "-" + sd);
                return new String[]{String.valueOf(sd), enc.name(), r.status().name(), String.format("%.1f", r.seconds()), String.valueOf(d.nodes().size()), String.valueOf(d.regs().size())};
            }));
        }
        Map<String, Integer> counts = new TreeMap<>();
        List<String> bad = new ArrayList<>();
        List<Double> times = new ArrayList<>();
        for (Future<String[]> f : fs) {
            String[] r = f.get();
            counts.merge(r[2], 1, Integer::sum);
            times.add(Double.parseDouble(r[3]));
            System.out.println("FFUZZ seed=" + r[0] + " enc=" + r[1] + " status=" + r[2] + " sec=" + r[3] + " nodes=" + r[4] + " regs=" + r[5]);
            if (!r[2].equals("PASS")) bad.add(String.join(" ", r));
        }
        ex.shutdown();
        Collections.sort(times);
        System.out.printf("FFUZZ-SUMMARY solver=%s designs=%d %s median=%.1fs max=%.1fs wall=%.1fs%n", solver, designs, counts,
            times.get(times.size() / 2), times.get(times.size() - 1), (System.nanoTime() - t0) / 1e9);
        assertTrue(bad.isEmpty(), bad.toString());
    }
}
