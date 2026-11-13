package org.ternhdl.hls;

import java.lang.reflect.Method;
import java.nio.file.*;
import java.util.*;
import org.ternhdl.core.*;
import org.ternhdl.encode.Encoding;
import org.ternhdl.fpga.*;

/**
 * An HLS kernel on the ZedBoard: arrays loaded through the loader ports, start, free run, done/result read back and
 * compared with the native Java method. args: kernel sim|board build|nobuild trials   (not a test)
 */
public final class HlsBoard {
    static final Encoding E = Encoding.E1;

    public static void main(String[] a) throws Exception {
        String kn = a[0], target = a[1];
        int trials = Integer.parseInt(a[3]);
        String src = HlsTest.source();
        Class<?> g = HlsTest.golden();
        Method m = Arrays.stream(g.getMethods()).filter(x -> x.getName().equals(kn)).findFirst().get();
        Hls.Result res = Hls.compile(src, kn, new Hls.Options(40, false, true));
        Design d = res.design();
        Path dir = Path.of(System.getProperty("user.home"), "ternhdl-viv", "board", "hls_" + kn + "-E1");
        System.out.println(BoardBuild.build(d, E, dir, 50, !a[2].equals("build")));
        BoardSoc.Layout l = BoardSoc.layout(d);
        Random r = new Random(99);
        int bad = 0;
        for (int t = 0; t < trials; t++) {
            Map<String, Logic3[]> in = new LinkedHashMap<>();
            for (var en : d.inputs().entrySet()) { Logic3[] z = new Logic3[d.node(en.getValue()).type().width()]; Arrays.fill(z, Logic3.O); in.put(en.getKey(), z); }
            List<String> cmds = new ArrayList<>();
            Runnable commit = () -> {
                long[] w = new long[l.inWords()];
                for (BoardSoc.Field f : l.in()) {
                    long enc = BoardRunner.encode(E, d.node(d.inputs().get(f.port())).type(), in.get(f.port()));
                    for (int b = 0; b < f.width(); b++) if ((enc >>> b & 1) != 0) { int gb = BoardSoc.offset(f) + b; w[gb / 32] |= 1L << (gb % 32); }
                }
                for (int k = 0; k < w.length; k++) cmds.add(String.format("W %x %x", 0x100 + 4 * k, w[k]));
            };
            in.put("rst", new Logic3[]{Logic3.P}); commit.run(); cmds.add("W 0 1");
            in.put("rst", new Logic3[]{Logic3.O});
            Object[] jargs = new Object[m.getParameterCount()];
            for (int pi = 0; pi < jargs.length; pi++) {
                if (m.getParameterTypes()[pi] == long[].class) {
                    Ir.Arr arr = res.arrays().values().stream().skip(HlsTest.arrayIndex(res, m, pi)).findFirst().get();
                    long[] data = new long[(int) Math.pow(3, arr.depthTrits())];
                    long h = (data.length - 1) / 2;
                    for (int i = 0; i < data.length; i++) {
                        data[i] = HlsTest.rnd(r, arr.width());
                        in.put("ld_" + arr.name() + "_we", new Logic3[]{Logic3.P});
                        in.put("ld_" + arr.name() + "_addr", HlsTest.digits(i - h, arr.depthTrits()));
                        in.put("ld_" + arr.name() + "_data", HlsTest.digits(data[i], arr.width()));
                        commit.run(); cmds.add("W 0 1");
                    }
                    in.put("ld_" + arr.name() + "_we", new Logic3[]{Logic3.O});
                    jargs[pi] = data;
                } else {
                    String name = res.params().get(HlsTest.scalarIndex(m, pi));
                    int w = d.node(d.inputs().get(name)).type().width();
                    long v = kn.equals("dot") ? r.nextInt(27) : kn.equals("gcd") ? 1 + r.nextInt(3000) : kn.equals("sumsq") ? r.nextInt(30) : HlsTest.rnd(r, w);
                    in.put(name, HlsTest.digits(v, w));
                    jargs[pi] = v;
                }
            }
            in.put("start", new Logic3[]{Logic3.P}); commit.run(); cmds.add("W 0 1");
            in.put("start", new Logic3[]{Logic3.O}); commit.run();
            cmds.add("W 8 2000"); cmds.add("D 2000");
            for (int k = 0; k < l.outWords(); k++) cmds.add(String.format("R %x", 0x200 + 4 * k));
            List<Long> reads = target.equals("sim") ? BoardRunner.simulate(dir, cmds) : BoardRunner.board(dir, cmds);
            long done = 0, result = 0;
            for (BoardSoc.Field f : l.out()) {
                long v = 0;
                for (int b = 0; b < f.width(); b++) { int gb = BoardSoc.offset(f) + b; if ((reads.get(gb / 32) >>> (gb % 32) & 1) != 0) v |= 1L << b; }
                if (f.port().equals("done")) done = v;
                if (f.port().equals("result")) { Logic3[] digs = new Logic3[f.width() / 2]; for (int k = 0; k < digs.length; k++) digs[k] = E.decode((int) (v >>> (2 * k) & 3)); result = HlsTest.val(digs); }
            }
            long exp = ((Number) m.invoke(null, jargs)).longValue();
            if (done != 1 || result != exp) { bad++; System.out.println("HLSBOARD mismatch trial " + t + ": done=" + done + " result=" + result + " expected=" + exp); }
        }
        System.out.printf("HLSBOARD %s %s trials=%d mismatches=%d%n", kn, target, trials, bad);
    }
}
