package org.ternhdl.hls;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.ternhdl.core.*;
import org.ternhdl.encode.*;
import org.ternhdl.fpga.VivadoOoc;

/** Vivado OOC QoR of HLS kernels: ripple cost model + ripple adders vs prefix cost model + prefix adders (not a test). */
public final class HlsQor {
    public static void main(String[] a) throws Exception {
        String src = HlsTest.source();
        Path root = Path.of(System.getProperty("user.home"), "ternhdl-viv", "ooc");
        String[] cpus = {"0-3", "4-7", "8-11", "12-15", "16-19", "20-23"};
        BlockingQueue<String> q = new LinkedBlockingQueue<>(List.of(cpus));
        ExecutorService ex = Executors.newFixedThreadPool(cpus.length);
        Map<String, Future<String>> fs = new LinkedHashMap<>();
        for (String kn : a.length > 0 ? List.of(a[0].split(",")) : List.of("tmix", "poly", "gcd", "sumsq", "fir", "dot", "bubble", "matvec", "digitsum"))
            for (boolean pfx : new boolean[]{false, true}) {
                fs.put(kn + pfx, ex.submit(() -> {
                    Hls.Result res = Hls.compile(src, kn, new Hls.Options(40, pfx, true));
                    Design d = res.design();
                    d.finish();
                    List<String[]> ports = new ArrayList<>();
                    for (var en : d.inputs().entrySet()) { Type t = d.node(en.getValue()).type(); ports.add(new String[]{en.getKey(), "" + (t.isBit() ? 1 : 2 * t.width()), "in"}); }
                    for (var en : d.outputs().entrySet()) { Type t = d.node(en.getValue()).type(); ports.add(new String[]{en.getKey(), "" + (t.isBit() ? 1 : 2 * t.width()), "out"}); }
                    String sv = SvEmitter.emit(d, new SvEmitter.Options(Encoding.E1, false, false, pfx ? SvEmitter.AdderStyle.PREFIX : SvEmitter.AdderStyle.RIPPLE));
                    String c = q.take();
                    try {
                        VivadoOoc.Qor r = VivadoOoc.run("hls_" + kn + (pfx ? "_prefix" : "_ripple"), sv, d.name, ports, true, 10.0, root, c);
                        return String.format(Locale.ROOT, "| %s | %s | %d | %d | %d | %.1f | %s |", kn, pfx ? "prefix model + prefix adders" : "ripple model + ripple adders",
                            res.states(), r.luts(), r.ffs(), r.fmaxMhz(), r.error() == null ? "" : r.error().substring(0, Math.min(80, r.error().length())));
                    } finally { q.put(c); }
                }));
            }
        System.out.println("| Kernel | Variant | States | LUT | FF | Fmax (MHz) | note |\n|---|---|---|---|---|---|---|");
        try { for (var f : fs.values()) System.out.println(f.get()); } finally { ex.shutdownNow(); }
    }
}
