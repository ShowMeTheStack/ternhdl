package org.ternhdl.hls;

import java.util.*;
import org.ternhdl.core.*;

/**
 * High-level synthesis of a Java static method into a TernHDL FSMD.
 * Scheduling: per basic block, as-soon-as-possible with chaining under a delay budget per state (a ternary cost model
 * in "levels"), at most 2 reads and 1 write per memory per state, a read after a write to the same memory goes to a
 * later state, a write is never placed before an earlier read of the same memory. Values used in a later state are
 * held in temporaries. The state register is a trit vector (3 states per trit).
 * Ports: start (Bit), one input per scalar parameter, done (Bit), result; per array parameter a loader port
 * (ld_<a>_we / ld_<a>_addr / ld_<a>_data) and a peek port (pk_<a>_addr -> pk_<a>_data).
 */
public final class Hls {
    public record Options(double budget, boolean prefixAdders, boolean strengthReduce) {
        public Options(double budget, boolean prefixAdders) { this(budget, prefixAdders, true); }
        public static Options defaults() { return new Options(40, false, true); }
    }

    public record Result(Design design, int states, int stateTrits, int temps, int ops, Map<String, Ir.Arr> arrays, List<String> params) { }

    private Hls() { }

    public static Result compile(String source, String method, Options opt) {
        Ir ir = Front.parse(source, method, opt.strengthReduce());
        for (Ir.Block b : ir.blocks) schedule(b, opt);
        return build(ir, method, opt);
    }

    // ---------------------------------------------------------------- cost model (levels)
    static double delay(Ir.Val v, Options o) {
        int w = v.width;
        double add = o.prefixAdders() ? 2 * Math.ceil(Math.log(Math.max(2, w)) / Math.log(2)) + 3 : 2.0 * w;
        return switch (v.k) {
            case CONST, VAR, WIDEN, SHL, SHR -> 0;
            case ADD, SUB -> add;
            case LT, LE, GT, GE, EQ, NE -> w;
            case MULD -> 1;
            case MEMRD -> 4;
            default -> 1;
        };
    }

    static boolean anywhere(Ir.Val v) { return v.k == Ir.K.CONST || v.k == Ir.K.VAR; }

    static void schedule(Ir.Block b, Options o) {
        Map<String, Integer> readsAt = new HashMap<>(), writesAt = new HashMap<>();
        int last = 0;
        for (Ir.Val v : b.vals) {
            if (anywhere(v)) { v.state = 0; v.arrive = 0; continue; }
            int s = 0;
            for (int a : v.args) { Ir.Val av = b.vals.get(a); if (!anywhere(av)) s = Math.max(s, av.state); }
            if (v.after >= 0) s = Math.max(s, b.wrState.get(b.writes.get(v.after)) + 1);
            double d = delay(v, o);
            double arr = 0;
            for (int a : v.args) { Ir.Val av = b.vals.get(a); if (!anywhere(av) && av.state == s) arr = Math.max(arr, av.arrive); }
            if (arr + d > o.budget() && arr > 0) { s++; arr = 0; }
            if (v.k == Ir.K.MEMRD) while (readsAt.getOrDefault(v.name + "@" + s, 0) >= 2) { s++; arr = 0; }
            v.state = s; v.arrive = arr + d;
            if (v.k == Ir.K.MEMRD) readsAt.merge(v.name + "@" + s, 1, Integer::sum);
            last = Math.max(last, s);
            // writes created right after this value can be placed now (keeps program order for WAR checks)
            placeWrites(b, v.id + 1, writesAt);
        }
        placeWrites(b, Integer.MAX_VALUE, writesAt);
        for (int st : b.wrState.values()) last = Math.max(last, st);
        if (b.cond >= 0) { Ir.Val c = b.vals.get(b.cond); if (!anywhere(c)) last = Math.max(last, c.state); }
        b.states = last + 1;
    }

    private static void placeWrites(Ir.Block b, int mark, Map<String, Integer> writesAt) {
        for (Ir.MemWr w : b.writes) {
            if (b.wrState.containsKey(w) || w.mark() > mark) continue;
            int s = 0;
            for (int a : new int[]{w.addr(), w.data()}) { Ir.Val av = b.vals.get(a); if (!anywhere(av)) s = Math.max(s, av.state); }
            for (Ir.Val r : b.vals) if (r.k == Ir.K.MEMRD && r.name.equals(w.mem()) && r.id < w.mark()) s = Math.max(s, r.state);   // WAR
            for (var e : b.wrState.entrySet()) if (e.getKey().mem().equals(w.mem())) s = Math.max(s, e.getValue() + 1);              // 1 write port, in order
            b.wrState.put(w, s);
        }
    }

    // ---------------------------------------------------------------- FSMD
    static Sig fit(Design d, Sig s, int w) {
        if (s.width() == w) return s;
        if (s.width() < w) return k(d, 0, w - s.width()).concat(s);
        return s.slice(w - 1, 0);
    }

    static Sig k(Design d, long v, int n) {
        StringBuilder sb = new StringBuilder();
        long x = v;
        char[] cs = new char[n];
        for (int i = 0; i < n; i++) { int r = (int) Math.floorMod(x, 3); if (r == 2) r = -1; cs[n - 1 - i] = r < 0 ? '-' : r == 0 ? '0' : '+'; x = (x - r) / 3; }
        return d.trits(new String(cs));
    }

    static Sig isState(Design d, Sig st, int s) { return st.cmp(k(d, s, st.width())).isZero(); }

    private static Result build(Ir ir, String method, Options o) {
        Design d = new Design("hls_" + method);
        d.resetInput();
        Sig start = d.input("start", Type.BIT);
        Map<String, Sig> pin = new LinkedHashMap<>();
        for (String p : ir.params) pin.put(p, d.input(p, Type.trits(ir.vars.get(p).width())));
        int total = 2;
        int[] base = new int[ir.blocks.size()];
        for (Ir.Block b : ir.blocks) { base[b.id] = total; total += b.states; }
        int stTrits = 1;
        while ((Math.pow(3, stTrits) - 1) / 2 < total) stTrits++;
        Sig st = d.reg("state", Type.trits(stTrits));
        Map<String, Sig> vreg = new LinkedHashMap<>();
        for (Ir.Var v : ir.vars.values()) vreg.put(v.name(), d.reg("v_" + v.name(), Type.trits(v.width())));
        Sig result = d.reg("result", Type.trits(ir.retWidth));

        // memories with one write port each (loader has priority)
        Map<String, Design.Mem> mem = new LinkedHashMap<>();
        Map<String, Sig[]> ld = new HashMap<>();
        for (Ir.Arr a : ir.arrays.values()) {
            mem.put(a.name(), d.memory("m_" + a.name(), Type.trits(a.width()), a.depthTrits()));
            ld.put(a.name(), new Sig[]{d.input("ld_" + a.name() + "_we", Type.BIT), d.input("ld_" + a.name() + "_addr", Type.trits(a.depthTrits())), d.input("ld_" + a.name() + "_data", Type.trits(a.width()))});
        }
        Map<String, List<Sig[]>> wports = new HashMap<>();   // mem -> {stateActive, addr, data}

        int temps = 0, ops = 0;
        List<Sig[]> next = new ArrayList<>();   // {condition Bit, next state Sig}
        Map<String, List<Sig[]>> vnext = new HashMap<>();
        List<Sig[]> rnext = new ArrayList<>();
        for (Ir.Block b : ir.blocks) {
            Sig[] comb = new Sig[b.vals.size()];
            Sig[] tmp = new Sig[b.vals.size()];
            // which values need temporaries: used in a later state or at block end
            boolean[] needTmp = new boolean[b.vals.size()];
            int L = b.states - 1;
            for (Ir.Val v : b.vals) for (int a : v.args) { Ir.Val av = b.vals.get(a); if (!anywhere(av) && av.state < v.state) needTmp[a] = true; }
            for (Ir.MemWr w : b.writes) for (int a : new int[]{w.addr(), w.data()}) { Ir.Val av = b.vals.get(a); if (!anywhere(av) && av.state < b.wrState.get(w)) needTmp[a] = true; }
            List<Integer> endUses = new ArrayList<>(b.varOut.values());
            if (b.cond >= 0) endUses.add(b.cond);
            if (b.ret >= 0) endUses.add(b.ret);
            for (int a : endUses) { Ir.Val av = b.vals.get(a); if (!anywhere(av) && av.state < L) needTmp[a] = true; }
            for (Ir.Val v : b.vals) if (needTmp[v.id]) { tmp[v.id] = d.reg("t_b" + b.id + "_v" + v.id, v.bit ? Type.BIT : Type.trits(v.width)); temps++; }
            final Ir.Block blk = b;
            java.util.function.BiFunction<Integer, Integer, Sig> use = (a, atState) -> {
                Ir.Val av = blk.vals.get(a);
                if (anywhere(av) || av.state == atState) return comb[a];
                return tmp[a];
            };
            for (Ir.Val v : b.vals) {
                int s = v.state;
                Sig[] in = new Sig[v.args.length];
                for (int i = 0; i < in.length; i++) in[i] = use.apply(v.args[i], s);
                int w = v.width;
                comb[v.id] = switch (v.k) {
                    case CONST -> v.bit ? d.bit(v.c != 0) : k(d, v.c, w);
                    case VAR -> vreg.get(v.name);
                    case ADD -> fit(d, in[0], w).add(fit(d, in[1], w));
                    case SUB -> fit(d, in[0], w).add(fit(d, in[1], w).neg());
                    case NEG -> fit(d, in[0], w).neg();
                    case LT, LE, GT, GE, EQ, NE -> {
                        int cw = Math.max(in[0].width(), in[1].width());
                        Sig c = fit(d, in[0], cw).cmp(fit(d, in[1], cw));
                        yield switch (v.k) {
                            case LT -> c.isNeg(); case GT -> c.isPos(); case EQ -> c.isZero();
                            case LE -> c.isPos().not(); case GE -> c.isNeg().not(); default -> c.isZero().not();
                        };
                    }
                    case NOT -> in[0].not();
                    case AND -> in[0].and(in[1]);
                    case OR -> in[0].or(in[1]);
                    case SEL -> d.mux(in[0], fit(d, in[1], w), fit(d, in[2], w));
                    case MEMRD -> mem.get(v.name).read(address(d, in[0], ir.arrays.get(v.name).depthTrits()));
                    case MIN -> fit(d, in[0], w).min(fit(d, in[1], w));
                    case MAX -> fit(d, in[0], w).max(fit(d, in[1], w));
                    case ANY -> fit(d, in[0], w).any(fit(d, in[1], w));
                    case CONS -> fit(d, in[0], w).cons(fit(d, in[1], w));
                    case TSUM -> fit(d, in[0], w).sum(fit(d, in[1], w));
                    case SHL -> fit(d, in[0], w).shl((int) v.c);
                    case SHR -> in[0].shr((int) v.c);
                    case MULD -> {
                        Sig x = fit(d, in[0], w);
                        Sig digit = in[1].digit((int) v.c);
                        yield digit.mux3(x.neg(), k(d, 0, w), x).shl((int) v.c);
                    }
                    case WIDEN -> fit(d, in[0], (int) v.c);
                };
                if (!anywhere(v)) ops++;
            }
            for (Ir.Val v : b.vals) if (tmp[v.id] != null) tmp[v.id].next(d.mux(isState(d, st, base[b.id] + v.state), comb[v.id], tmp[v.id]));
            for (Ir.MemWr w : b.writes) {
                int ws = b.wrState.get(w);
                wports.computeIfAbsent(w.mem(), x -> new ArrayList<>()).add(new Sig[]{isState(d, st, base[b.id] + ws),
                    address(d, use.apply(w.addr(), ws), ir.arrays.get(w.mem()).depthTrits()), fit(d, use.apply(w.data(), ws), ir.arrays.get(w.mem()).width())});
            }
            Sig lastState = isState(d, st, base[b.id] + L);
            for (var e : b.varOut.entrySet())
                vnext.computeIfAbsent(e.getKey(), x -> new ArrayList<>()).add(new Sig[]{lastState, fit(d, use.apply(e.getValue(), L), ir.vars.get(e.getKey()).width())});
            for (int s = 0; s < L; s++) next.add(new Sig[]{isState(d, st, base[b.id] + s), k(d, base[b.id] + s + 1, stTrits)});
            switch (b.term) {
                case "GOTO" -> next.add(new Sig[]{lastState, k(d, base[b.tgtTrue], stTrits)});
                case "BR" -> next.add(new Sig[]{lastState, d.mux(use.apply(b.cond, L), k(d, base[b.tgtTrue], stTrits), k(d, base[b.tgtFalse], stTrits))});
                default -> {
                    next.add(new Sig[]{lastState, k(d, 1, stTrits)});
                    if (b.ret >= 0) rnext.add(new Sig[]{lastState, fit(d, use.apply(b.ret, L), ir.retWidth)});
                }
            }
        }
        // idle (0) and done (1): start loads the parameters and enters block 0
        Sig idleOrDone = isState(d, st, 0).or(isState(d, st, 1));
        Sig go = idleOrDone.and(start);
        Sig ns = d.mux(go, k(d, base[0], stTrits), st);
        for (Sig[] n : next) ns = d.mux(n[0], n[1], ns);
        st.next(ns);
        for (var e : vreg.entrySet()) {
            Sig cur = e.getValue(), nx = cur;
            for (Sig[] u : vnext.getOrDefault(e.getKey(), List.of())) nx = d.mux(u[0], u[1], nx);
            if (pin.containsKey(e.getKey())) nx = d.mux(go, pin.get(e.getKey()), nx);
            cur.next(nx);
        }
        Sig rn = result;
        for (Sig[] u : rnext) rn = d.mux(u[0], u[1], rn);
        result.next(rn);
        for (Ir.Arr a : ir.arrays.values()) {
            Sig[] l = ld.get(a.name());
            Sig we = d.bit(false), addr = k(d, 0, a.depthTrits()), data = k(d, 0, a.width());
            for (Sig[] p : wports.getOrDefault(a.name(), List.of())) { we = we.or(p[0]); addr = d.mux(p[0], p[1], addr); data = d.mux(p[0], p[2], data); }
            mem.get(a.name()).write(l[0].or(we), d.mux(l[0], l[1], addr), d.mux(l[0], l[2], data));
            Sig pk = d.input("pk_" + a.name() + "_addr", Type.trits(a.depthTrits()));
            d.output("pk_" + a.name() + "_data", mem.get(a.name()).read(pk));
        }
        d.output("done", isState(d, st, 1));
        d.output("result", result);
        return new Result(d, total, stTrits, temps, ops, ir.arrays, ir.params);
    }

    /** Java index i (0-based) -> balanced memory address i - (3^k - 1)/2, k trits. */
    static Sig address(Design d, Sig index, int k) {
        long h = (long) (Math.pow(3, k) - 1) / 2;
        int w = Math.max(index.width(), k + 1);
        return fit(d, index, w).add(k(d, -h, w)).slice(k - 1, 0);
    }
}
