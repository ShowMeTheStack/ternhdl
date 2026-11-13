package org.ternhdl.cpu;

import java.nio.file.*;
import java.util.*;
import org.ternhdl.core.*;
import org.ternhdl.encode.Encoding;
import org.ternhdl.fpga.*;
import org.ternhdl.isa.*;

/**
 * Runs T27 programs on the single-cycle core through the board harness (target sim = Verilator AXI model of the whole
 * harness, board = ZedBoard via xsct). Per program: load through the loader port with reset held, then (a) per-cycle
 * lockstep against the ISS trace if requested, and (b) free run + final data-memory check against the ISS.
 * args: sim|board build|nobuild lockstepPrograms(comma) runPrograms(comma)   (not a test)
 */
public final class CoreBoard {
    static final Encoding E = Encoding.E1;

    static final class Session {
        final Design d; final BoardSoc.Layout l; final Map<String, Logic3[]> in = new LinkedHashMap<>();
        final List<String> cmds = new ArrayList<>();
        Session(Design d) {
            this.d = d; this.l = BoardSoc.layout(d);
            for (var en : d.inputs().entrySet()) { Logic3[] z = new Logic3[d.node(en.getValue()).type().width()]; Arrays.fill(z, Logic3.O); in.put(en.getKey(), z); }
        }
        void set(String port, Logic3[] v) { in.put(port, v); }
        void commit() {
            long[] w = new long[l.inWords()];
            for (BoardSoc.Field f : l.in()) {
                long enc = BoardRunner.encode(E, d.node(d.inputs().get(f.port())).type(), in.get(f.port()));
                for (int b = 0; b < f.width(); b++) if ((enc >>> b & 1) != 0) { int g = BoardSoc.offset(f) + b; w[g / 32] |= 1L << (g % 32); }
            }
            for (int k = 0; k < w.length; k++) cmds.add(String.format("W %x %x", 0x100 + 4 * k, w[k]));
        }
        void step() { cmds.add("W 0 1"); }
        void readAll() { for (int k = 0; k < l.outWords(); k++) cmds.add(String.format("R %x", 0x200 + 4 * k)); }
        void run(long n) { cmds.add(String.format("W 8 %x", n)); cmds.add(String.format("D %x", n)); }
        /** Decodes one readAll group starting at reads[i]. */
        Map<String, Logic3[]> decode(List<Long> reads, int i) {
            Map<String, Logic3[]> m = new HashMap<>();
            for (BoardSoc.Field f : l.out()) {
                long v = 0;
                for (int b = 0; b < f.width(); b++) { int g = BoardSoc.offset(f) + b; if ((reads.get(i + g / 32) >>> (g % 32) & 1) != 0) v |= 1L << b; }
                Type t = d.node(d.outputs().get(f.port())).type();
                Logic3[] r = new Logic3[t.width()];
                if (t.isBit()) r[0] = (v & 1) != 0 ? Logic3.P : Logic3.O;
                else for (int k = 0; k < r.length; k++) { Logic3 x = E.decode((int) (v >>> (2 * k) & 3)); r[k] = x == null ? Logic3.X : x; }
                m.put(f.port(), r);
            }
            return m;
        }
    }

    static Logic3[] digits(long v, int n) { int[] t = T27.trits(v, n); Logic3[] r = new Logic3[n]; for (int i = 0; i < n; i++) r[i] = Logic3.ofBal(t[i]); return r; }

    static void load(Session s, Asm.Program p) {
        s.set("rst", new Logic3[]{Logic3.P});
        for (var e : p.words().entrySet()) {
            s.set("ld_we", new Logic3[]{Logic3.P}); s.set("ld_row", digits(e.getKey() / 3, 7)); s.set("ld_data", digits(e.getValue(), 27));
            s.commit(); s.step();
        }
        s.set("ld_we", new Logic3[]{Logic3.O}); s.commit(); s.step();
        s.set("rst", new Logic3[]{Logic3.O}); s.commit();
    }

    static final Map<String, long[]> RESULTS = Map.of("fib", new long[]{3000}, "fibrec", new long[]{3000}, "gcd", new long[]{3000}, "sieve", new long[]{3000},
        "dot", new long[]{3000, 3027}, "sort", range(3000, 12), "alu", range(3000, 20));
    static long[] range(long a, int n) { long[] r = new long[n]; for (int i = 0; i < n; i++) r[i] = a + 3L * i; return r; }

    public static void main(String[] a) throws Exception {
        String target = a[0];
        String kind = a.length > 4 ? a[4] : "sc";
        java.util.function.Supplier<Design> mk = () -> kind.equals("ooo") ? T27Ooo.build(7) : kind.equals("pipe") ? T27Pipe.build(7) : T27Core.build(7);
        Design core = mk.get();
        Path dir = Path.of(System.getProperty("user.home"), "ternhdl-viv", "board", "t27" + kind + "-E1");
        if (a[1].equals("build")) System.out.println(BoardBuild.build(core, E, dir, 10, false));
        else BoardBuild.build(core, E, dir, 10, true);
        for (String name : a[2].split(",")) {
            if (name.isBlank()) continue;
            Asm.Program p = CoreTest.prog(name);
            List<Lockstep.Event> tr = Lockstep.issTrace(p, 1_000_000);
            Session s = new Session(mk.get());
            load(s, p);
            int cycles = tr.size() * 5 + 50;
            for (int c = 0; c < cycles; c++) { s.readAll(); s.step(); }
            List<Long> reads = target.equals("sim") ? BoardRunner.simulate(dir, s.cmds) : BoardRunner.board(dir, s.cmds);
            List<Map<String, Logic3[]>> rec = new ArrayList<>();
            rec.add(Map.of());
            for (int i = 0; i + s.l.outWords() <= reads.size(); i += s.l.outWords()) rec.add(s.decode(reads, i));
            Lockstep.Result r = Lockstep.check(rec, tr);
            System.out.printf("COREBOARD %s lockstep %s: %d instructions, %d cycles read, errors=%s%n", target, name, r.instructions(), rec.size() - 1, r.errors());
        }
        for (String name : a[3].split(",")) {
            if (name.isBlank()) continue;
            Asm.Program p = CoreTest.prog(name);
            Iss iss = new Iss(8); iss.load(p); iss.run(1_000_000);
            Session s = new Session(mk.get());
            load(s, p);
            long n = 40L * iss.steps + 100;
            s.run(n);
            s.readAll();
            long[] addrs = RESULTS.get(name);
            for (long ad : addrs) { s.set("peek_row", digits(ad / 3, 7)); s.commit(); s.readAll(); }
            List<Long> reads = target.equals("sim") ? BoardRunner.simulate(dir, s.cmds) : BoardRunner.board(dir, s.cmds);
            int ow = s.l.outWords();
            List<String> err = new ArrayList<>();
            if (reads.size() != ow * (1 + addrs.length)) err.add("got " + reads.size() + " reads");
            else {
                Map<String, Logic3[]> fin = s.decode(reads, 0);
                if (fin.get("halted")[0] != Logic3.P) err.add("not halted");
                for (int i = 0; i < addrs.length; i++) {
                    long got = Lockstep.val(s.decode(reads, ow * (1 + i)).get("peek_word")), exp = iss.word(addrs[i]);
                    if (got != exp) err.add("word " + addrs[i] + " = " + got + " vs ISS " + exp);
                }
            }
            System.out.printf("COREBOARD %s run %s: %d ISS instructions, %d cycles budget, %d result words, errors=%s%n", target, name, iss.steps, n, addrs.length, err);
        }
    }
}
