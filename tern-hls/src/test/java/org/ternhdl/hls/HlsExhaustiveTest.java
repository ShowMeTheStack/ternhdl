package org.ternhdl.hls;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.util.*;
import org.junit.jupiter.api.Test;

/** poly has a 5-trit input: all 243 inputs are checked against native Java (a proof by exhaustion). */
class HlsExhaustiveTest {
    @Test
    void polyOnEveryInput() throws Exception {
        Class<?> g = HlsTest.golden();
        Method m = Arrays.stream(g.getMethods()).filter(x -> x.getName().equals("poly")).findFirst().get();
        Hls.Result res = Hls.compile(HlsTest.source(), "poly", Hls.Options.defaults());
        int n = 0;
        for (long x = -121; x <= 121; x++) {
            long exp = ((Number) m.invoke(null, x)).longValue();
            assertEquals(exp, HlsTest.runHw(res, Map.of("x", x), Map.of())[0], "x=" + x);
            n++;
        }
        System.out.println("HLSEXHAUSTIVE poly: " + n + " inputs, all equal");
    }
}
