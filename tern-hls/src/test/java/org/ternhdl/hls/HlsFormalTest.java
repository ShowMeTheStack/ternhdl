package org.ternhdl.hls;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ternhdl.core.*;
import org.ternhdl.encode.Encoding;
import org.ternhdl.formal.*;

/**
 * Formal proof that HLS output computes the kernel function for all inputs: a combinational reference built from the
 * same TernHDL operators is added to the generated design; with inputs assumed stable, `done -> result == reference`.
 */
@Tag("slow")
class HlsFormalTest {
    @Test
    void straightLineKernelsAreProved() throws Exception {
        String src = HlsTest.source();
        for (String kn : System.getProperty("hls.formal", "tmix").split(",")) {
            Hls.Result res = Hls.compile(src, kn, Hls.Options.defaults());
            Design d = res.design();
            Sig a = d.inputSig(kn.equals("tmix") ? "a" : "x");
            Sig ref;
            if (kn.equals("tmix")) {
                Sig b = d.inputSig("b");
                Sig w27a = Hls.fit(d, a, 27), w27b = Hls.fit(d, b, 27);
                ref = w27a.min(w27b).sum(w27a.max(w27b).shl(2)).add(w27a.cons(w27b));
            } else {
                Sig x = Hls.fit(d, a, 27);
                Sig x2 = org.ternhdl.stdlib.Arith.mul(d, x, x).slice(26, 0), x3 = org.ternhdl.stdlib.Arith.mul(d, x2, x).slice(26, 0);
                ref = x3.add(x3).add(x3).add(x2.add(x2).add(x2).add(x2).add(x2).neg()).add(x.shl(2).add(x.shl(1).neg()).add(x)).add(Hls.k(d, -11, 27));
            }
            // inputs held stable after the first cycle
            Sig first = d.reg("f_first", Type.BIT, new Logic3[]{Logic3.P});
            first.next(d.bit(false));
            for (String in : d.inputs().keySet()) {
                if (in.equals("rst") || in.equals("start")) continue;
                Sig s = d.inputSig(in);
                Sig prev = d.reg("f_prev_" + in, s.type());
                prev.next(s);
                d.assume("stable_" + in, first.or(s.cmp(prev).isZero()));
            }
            d.assertAlways("result_correct", d.outputSig("done").not().or(d.outputSig("result").cmp(ref).isZero()));
            Sby.Result r = Equivalence.properties(d, Encoding.E1, true, 4, true, Equivalence.DEFAULT_ENGINE, 1800, "hls-prop-" + kn);
            System.out.printf("HLSFORMAL %s %s %.1fs%n", kn, r.status(), r.seconds());
            assertEquals(Sby.Status.PASS, r.status(), r.logTail());
        }
    }
}
