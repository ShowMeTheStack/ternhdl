package org.ternhdl.cpu;

import java.nio.file.*;
import java.util.*;
import org.ternhdl.core.*;
import org.ternhdl.encode.*;
import org.ternhdl.fpga.VivadoOoc;

/** Vivado OOC QoR of both cores (E1), all ports registered (not a test). */
public final class CoreQor {
    public static void main(String[] a) throws Exception {
        Path root = Path.of(System.getProperty("user.home"), "ternhdl-viv", "ooc");
        String[][] jobs = a.length > 0 && !a[0].equals("all") ? new String[][]{{a[0], "0-7"}} : new String[][]{{"t27sc", "0-7"}, {"t27pipe", "8-15"}, {"t27ooo", "16-23"}};
        SvEmitter.AdderStyle style = a.length > 1 ? SvEmitter.AdderStyle.valueOf(a[1]) : SvEmitter.AdderStyle.RIPPLE;
        List<Thread> th = new ArrayList<>();
        for (String[] j : jobs) {
            Thread t = new Thread(() -> {
                try {
                    Design d = j[0].equals("t27sc") ? T27Core.build(7) : j[0].equals("t27ooo") ? T27Ooo.build(7) : T27Pipe.build(7);
                    d.finish();
                    List<String[]> ports = new ArrayList<>();
                    for (var en : d.inputs().entrySet()) { Type ty = d.node(en.getValue()).type(); ports.add(new String[]{en.getKey(), "" + (ty.isBit() ? 1 : 2 * ty.width()), "in"}); }
                    for (var en : d.outputs().entrySet()) { Type ty = d.node(en.getValue()).type(); ports.add(new String[]{en.getKey(), "" + (ty.isBit() ? 1 : 2 * ty.width()), "out"}); }
                    VivadoOoc.Qor q = VivadoOoc.run("core_" + j[0] + "_" + style, SvEmitter.emit(d, new SvEmitter.Options(Encoding.E1, false, false, style)), d.name, ports, true, 20.0, root, j[1]);
                    System.out.printf("COREQOR %s " + style + " LUT=%d FF=%d WNS=%.3f@20ns Fmax=%.1fMHz %s%n", j[0], q.luts(), q.ffs(), q.wnsNs(), q.fmaxMhz(), q.error() == null ? "" : q.error());
                } catch (Exception e) { System.out.println("COREQOR " + j[0] + " ERROR " + e); }
            });
            t.start(); th.add(t);
        }
        for (Thread t : th) t.join();
    }
}
