package org.ternhdl.encode;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ternhdl.core.*;
import org.ternhdl.sim.*;

/** Large differential fuzz: random designs, native Interpreter vs Verilator twin. Run with :tern-encode:slowTest. */
@Tag("slow")
class TwinFuzzTest {
    @Test
    void randomDesignsRefineTheirTwins() throws Exception {
        int designs = Integer.getInteger("tern.fuzz.designs", 150);
        int par = Integer.getInteger("tern.fuzz.parallel", 6);
        ExecutorService ex = Executors.newFixedThreadPool(par);
        List<Future<String>> fs = new ArrayList<>();
        long[] nodes = new long[1];
        long t0 = System.nanoTime();
        for (int seed = 0; seed < designs; seed++) {
            final int sd = seed;
            Random r0 = new Random(50_000 + sd);
            Design d = RandomDesign.random(r0, sd % 3 != 0, 30, 24);
            synchronized (nodes) { nodes[0] += d.nodes().size(); }
            Encoding enc = Encoding.values()[sd % 3];
            fs.add(ex.submit(() -> {
                Random r = new Random(90_000 + sd);
                TwinRunner tw = new TwinRunner(d, new SvEmitter.Options(enc, true, true), TwinRunner.workRoot().resolve("fuzz-" + sd));
                List<String> bad = TwinCheck.check(d, tw, TwinCheck.randomStimulus(d, r, 20), r);
                return bad.isEmpty() ? null : "seed " + sd + " " + enc + ": " + bad.size() + " mismatches, first " + bad.subList(0, Math.min(3, bad.size()));
            }));
        }
        List<String> failures = new ArrayList<>();
        for (Future<String> f : fs) { String s = f.get(); if (s != null) failures.add(s); }
        ex.shutdown();
        double sec = (System.nanoTime() - t0) / 1e9;
        System.out.printf("TWINFUZZ designs=%d totalNodes=%d digitComparisons=%d failures=%d seconds=%.1f%n",
            designs, nodes[0], TwinCheck.COMPARED.get(), failures.size(), sec);
        assertTrue(failures.isEmpty(), failures.toString());
    }
}
