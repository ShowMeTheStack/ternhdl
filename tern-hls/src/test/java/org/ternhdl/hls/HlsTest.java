package org.ternhdl.hls;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.tools.*;
import org.junit.jupiter.api.Test;
import org.ternhdl.core.*;
import org.ternhdl.sim.*;

class HlsTest {
    static String source() throws Exception {
        return new String(HlsTest.class.getResourceAsStream("/kernels/Kernels.java").readAllBytes(), StandardCharsets.UTF_8);
    }

    /** Compiles Kernels.java with javac and returns the class (the golden model). */
    static Class<?> golden() throws Exception {
        Path dir = Files.createTempDirectory("kernels");
        Path src = dir.resolve("Kernels.java");
        Files.writeString(src, source());
        JavaCompiler jc = ToolProvider.getSystemJavaCompiler();
        int rc = jc.run(null, null, null, "-cp", System.getProperty("java.class.path"), "-d", dir.toString(), src.toString());
        assertEquals(0, rc);
        return new URLClassLoader(new URL[]{dir.toUri().toURL()}, HlsTest.class.getClassLoader()).loadClass("Kernels");
    }

    static Logic3[] digits(long v, int n) {
        Logic3[] r = new Logic3[n];
        for (int i = 0; i < n; i++) { int d = (int) Math.floorMod(v, 3); if (d == 2) d = -1; r[i] = Logic3.ofBal(d); v = (v - d) / 3; }
        return r;
    }

    static long val(Logic3[] v) { return Arith.toBig(v).longValue(); }

    static long rnd(Random r, int w) { long h = (long) (Math.pow(3, w) - 1) / 2; return r.nextLong(2 * h + 1) - h; }

    /** Runs the HLS hardware on scalar args and arrays (modified in place); returns {result, cycles}. */
    static long[] runHw(Hls.Result res, Map<String, Long> scalars, Map<String, long[]> arrays) {
        Design d = res.design();
        Interpreter it = new Interpreter(d);
        for (String in : d.inputs().keySet()) {
            Type t = d.node(d.inputs().get(in)).type();
            Logic3[] z = new Logic3[t.width()];
            Arrays.fill(z, Logic3.O);
            it.poke(in, z);
        }
        it.poke("rst", "+"); it.step(); it.poke("rst", "0");
        for (var e : arrays.entrySet()) {
            Ir.Arr a = res.arrays().get(e.getKey());
            long h = (long) (Math.pow(3, a.depthTrits()) - 1) / 2;
            for (int i = 0; i < e.getValue().length; i++) {
                it.poke("ld_" + a.name() + "_we", "+");
                it.poke("ld_" + a.name() + "_addr", Logic3.str(digits(i - h, a.depthTrits())));
                it.poke("ld_" + a.name() + "_data", Logic3.str(digits(e.getValue()[i], a.width())));
                it.step();
            }
            it.poke("ld_" + a.name() + "_we", "0");
        }
        for (var e : scalars.entrySet()) {
            int w = d.node(d.inputs().get(e.getKey())).type().width();
            it.poke(e.getKey(), Logic3.str(digits(e.getValue(), w)));
        }
        it.poke("start", "+"); it.step(); it.poke("start", "0");
        long cycles = 1;
        while (it.peek("done").equals("0") && cycles < 2_000_000) { it.step(); cycles++; }
        assertEquals("+", it.peek("done"), "did not finish");
        for (var e : arrays.entrySet()) {
            Ir.Arr a = res.arrays().get(e.getKey());
            long h = (long) (Math.pow(3, a.depthTrits()) - 1) / 2;
            for (int i = 0; i < e.getValue().length; i++) e.getValue()[i] = val(it.memWord(res.design().mems().stream().filter(m -> m.name.equals("m_" + a.name())).findFirst().get().index(), i - h));
        }
        return new long[]{val(it.peekVec("result")), cycles};
    }

    @Test
    void kernelsMatchNativeJava() throws Exception {
        Class<?> g = golden();
        String src = source();
        Random r = new Random(2026);
        for (String kn : List.of("tmix", "poly", "gcd", "sumsq", "fir", "dot", "bubble", "matvec", "digitsum")) {
            Hls.Result res = Hls.compile(src, kn, Hls.Options.defaults());
            Method m = Arrays.stream(g.getMethods()).filter(x -> x.getName().equals(kn)).findFirst().get();
            int trials = kn.equals("bubble") || kn.equals("fir") || kn.equals("matvec") ? 20 : 60;
            long cyc = 0;
            for (int t = 0; t < trials; t++) {
                Map<String, Long> sc = new LinkedHashMap<>();
                Map<String, long[]> ar = new LinkedHashMap<>(), arG = new LinkedHashMap<>();
                Object[] jargs = new Object[m.getParameterCount()];
                int pi = 0;
                for (var p : m.getParameters()) {
                    String nm = p.getName();
                    String pname = res.params().size() > 0 || !res.arrays().isEmpty() ? null : null;
                    if (p.getType() == long[].class) {
                        Ir.Arr a = res.arrays().values().stream().skip(arrayIndex(res, m, pi)).findFirst().get();
                        long[] data = new long[(int) Math.pow(3, a.depthTrits())];
                        for (int i = 0; i < data.length; i++) data[i] = rnd(r, a.width());
                        ar.put(a.name(), data.clone()); arG.put(a.name(), data); jargs[pi] = data;
                    } else {
                        String name = res.params().get(scalarIndex(m, pi));
                        int w = res.design().node(res.design().inputs().get(name)).type().width();
                        long v = rnd(r, w);
                        if (kn.equals("gcd")) v = 1 + r.nextInt(3000);
                        if (kn.equals("sumsq")) v = r.nextInt(30);
                        if (kn.equals("dot")) v = r.nextInt(27);
                        sc.put(name, v); jargs[pi] = v;
                    }
                    pi++;
                }
                Object exp = m.invoke(null, jargs);
                long[] hw = runHw(res, sc, ar);
                cyc += hw[1];
                if (exp != null) assertEquals(((Number) exp).longValue(), hw[0], kn + " " + sc);
                for (var e : arG.entrySet()) assertArrayEquals(e.getValue(), ar.get(e.getKey()), kn + " array " + e.getKey());
            }
            System.out.printf("HLS %s: states=%d (state reg %d trits) temps=%d ops=%d nodes=%d avgCycles=%.1f trials=%d OK%n", kn, res.states(), res.stateTrits(),
                res.temps(), res.ops(), res.design().nodes().size(), (double) cyc / trials, trials);
        }
    }

    static int arrayIndex(Hls.Result res, Method m, int pi) { int k = 0; for (int i = 0; i < pi; i++) if (m.getParameterTypes()[i] == long[].class) k++; return k; }
    static int scalarIndex(Method m, int pi) { int k = 0; for (int i = 0; i < pi; i++) if (m.getParameterTypes()[i] != long[].class) k++; return k; }
}
