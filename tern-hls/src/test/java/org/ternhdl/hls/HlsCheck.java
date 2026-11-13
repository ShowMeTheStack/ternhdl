package org.ternhdl.hls;

import java.lang.reflect.Method;
import java.util.*;

/** Random-input check of one HLS kernel against the native Java method: returns {total cycles, failures}. */
final class HlsCheck {
    private HlsCheck() { }

    static long[] check(Class<?> g, Hls.Result res, String kn, int trials, Random r) throws Exception {
        Method m = Arrays.stream(g.getMethods()).filter(x -> x.getName().equals(kn)).findFirst().get();
        long cyc = 0, fail = 0;
        for (int t = 0; t < trials; t++) {
            Map<String, Long> sc = new LinkedHashMap<>();
            Map<String, long[]> ar = new LinkedHashMap<>(), arG = new LinkedHashMap<>();
            Object[] jargs = new Object[m.getParameterCount()];
            for (int pi = 0; pi < jargs.length; pi++) {
                if (m.getParameterTypes()[pi] == long[].class) {
                    Ir.Arr a = res.arrays().values().stream().skip(HlsTest.arrayIndex(res, m, pi)).findFirst().get();
                    long[] data = new long[(int) Math.pow(3, a.depthTrits())];
                    for (int i = 0; i < data.length; i++) data[i] = HlsTest.rnd(r, a.width());
                    ar.put(a.name(), data.clone()); arG.put(a.name(), data); jargs[pi] = data;
                } else {
                    String name = res.params().get(HlsTest.scalarIndex(m, pi));
                    int w = res.design().node(res.design().inputs().get(name)).type().width();
                    long v = HlsTest.rnd(r, w);
                    if (kn.equals("gcd")) v = 1 + r.nextInt(3000);
                    if (kn.equals("sumsq")) v = r.nextInt(30);
                    if (kn.equals("dot")) v = r.nextInt(27);
                    sc.put(name, v); jargs[pi] = v;
                }
            }
            Object exp = m.invoke(null, jargs);
            long[] hw = HlsTest.runHw(res, sc, ar);
            cyc += hw[1];
            if (exp != null && ((Number) exp).longValue() != hw[0]) fail++;
            for (var e : arG.entrySet()) if (!Arrays.equals(e.getValue(), ar.get(e.getKey()))) fail++;
        }
        return new long[]{cyc, fail};
    }
}
