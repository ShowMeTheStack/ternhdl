package org.ternhdl.formal;

import java.util.*;
import java.util.concurrent.*;
import org.ternhdl.core.*;
import org.ternhdl.encode.*;

/** Engine comparison on the standard designs (not a test). Prints one BENCH line per (design, engine). */
public final class FormalBench {
    public static void main(String[] a) throws Exception {
        String[] engines = {"z3", "cvc5", "abc pdr"};
        Map<String, java.util.function.Supplier<Design>> ds = new LinkedHashMap<>();
        for (Encoding e : Encoding.values()) ds.put("allops-" + e, SampleDesigns::allOps);
        for (int n : new int[]{1, 3, 9, 27}) { final int nn = n; ds.put("adder" + n, () -> SampleDesigns.adder(nn)); }
        ds.put("counter", () -> {
            Design d = new Design("cnt");
            d.resetInput();
            Sig en = d.input("en", Type.BIT);
            Sig c = d.reg("c", Type.trits(4));
            c.next(d.mux(en, c.add(d.trits("000+")), c));
            d.output("q", c);
            return d;
        });
        ExecutorService ex = Executors.newFixedThreadPool(24);
        List<Future<String>> fs = new ArrayList<>();
        for (var en : ds.entrySet()) for (String eng : engines) {
            fs.add(ex.submit(() -> {
                Encoding enc = en.getKey().startsWith("allops-") ? Encoding.valueOf(en.getKey().substring(7)) : Encoding.E1;
                Sby.Result r = Equivalence.twinRefinesReference(en.getValue().get(), enc, eng, 900, "bench-" + en.getKey() + "-" + eng.replace(' ', '_'));
                return String.format("BENCH design=%s engine=%s status=%s sec=%.1f", en.getKey(), eng, r.status(), r.seconds());
            }));
        }
        for (Future<String> f : fs) System.out.println(f.get());
        ex.shutdown();
    }
}
