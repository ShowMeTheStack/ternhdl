package org.ternhdl.sim;

import org.ternhdl.core.*;

/** Ad-hoc throughput check (not a test): a 27-trit accumulator pipeline of 32 adders. Run with java -cp. */
public final class Bench {
    public static void main(String[] a) {
        Design d = new Design("bench");
        d.resetInput();
        Sig in = d.input("in", Type.trits(27));
        Sig acc = d.reg("acc", Type.trits(27));
        Sig s = acc;
        for (int i = 0; i < 32; i++) s = s.add(in.shl(i % 5)).min(s.max(in));
        acc.next(s);
        d.output("q", acc);
        int nodes = d.nodes().size();

        SlicedSim ss = new SlicedSim(d);
        ss.poke("rst", "+"); ss.poke("in", "000000000000000000000000+-0");
        ss.step(); ss.poke("rst", "0");
        for (int i = 0; i < 2000; i++) ss.step();      // warm up
        int cycles = 200_000;
        long t0 = System.nanoTime();
        for (int i = 0; i < cycles; i++) ss.step();
        double sec = (System.nanoTime() - t0) / 1e9;
        System.out.printf("sliced: %d nodes, %.0f cycles/s x 64 lanes = %.2f M lane-cycles/s%n", nodes, cycles / sec, cycles / sec * 64 / 1e6);

        Interpreter it = new Interpreter(d);
        it.poke("rst", "+"); it.poke("in", "000000000000000000000000+-0");
        it.step(); it.poke("rst", "0");
        for (int i = 0; i < 200; i++) it.step();
        int c2 = 5000;
        t0 = System.nanoTime();
        for (int i = 0; i < c2; i++) it.step();
        sec = (System.nanoTime() - t0) / 1e9;
        System.out.printf("interpreter: %.0f cycles/s%n", c2 / sec);
    }
}
