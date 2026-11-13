package org.ternhdl.cpu;

import org.ternhdl.core.*;
import org.ternhdl.encode.Encoding;
import org.ternhdl.formal.*;

/** Formal proof of the cores' invariants on small-memory variants (not a test). */
public final class CoreProps {
    public static void main(String[] a) throws Exception {
        String[] cores = a.length > 0 ? a[0].split(",") : new String[]{"t27sc", "t27pipe"};
        String[] engines = a.length > 1 ? a[1].split(",") : new String[]{"abc pdr", "z3"};
        for (String c : cores) for (String eng : engines) {
            Design d = c.equals("t27sc") ? T27Core.build(2) : c.equals("t27ooo") ? T27Ooo.build(2) : T27Pipe.build(2);
            Sby.Result r = Equivalence.properties(d, Encoding.E1, true, 4, true, eng, 3600, "props-" + c + "-" + eng.replace(' ', '_'));
            System.out.printf("COREPROPS %s %s %s %.1fs%n", c, eng, r.status(), r.seconds());
            if (r.status() != Sby.Status.PASS) System.out.println(r.logTail().substring(Math.max(0, r.logTail().length() - 1200)));
        }
    }
}
