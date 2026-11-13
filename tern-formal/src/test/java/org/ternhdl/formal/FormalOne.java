package org.ternhdl.formal;

import java.util.Random;
import org.ternhdl.core.*;
import org.ternhdl.encode.*;
import org.ternhdl.sim.RandomDesign;

/** Re-runs one fuzz design: args seed solverOrEngine timeoutSec (not a test). */
public final class FormalOne {
    public static void main(String[] a) throws Exception {
        int sd = Integer.parseInt(a[0]);
        Design d = RandomDesign.random(new Random(70_000 + sd), sd % 3 != 0, 30, 24);
        Encoding enc = Encoding.values()[sd % 3];
        Sby.Result r = Equivalence.twinRefinesReference(d, enc, a[1], Integer.parseInt(a[2]), "one-" + sd + "-" + a[1].replace(' ', '_'));
        int adds = 0, cmps = 0, regs = d.regs().size();
        for (Node n : d.nodes()) { if (n.op() == Op.ADD) adds++; if (n.op() == Op.CMP) cmps++; }
        System.out.printf("ONE seed=%d engine=%s status=%s sec=%.1f nodes=%d adds=%d cmps=%d regs=%d%n", sd, a[1], r.status(), r.seconds(), d.nodes().size(), adds, cmps, regs);
    }
}
