package org.ternhdl.stdlib;

import java.util.*;
import java.util.function.Supplier;
import org.ternhdl.core.*;

/** The fixed benchmark set listed in README.md (designs rebuilt from descriptions; names stable). */
public final class Benchmarks {
    private Benchmarks() { }

    public static Map<String, Supplier<Design>> all() {
        Map<String, Supplier<Design>> m = new LinkedHashMap<>();
        for (int n : new int[]{9, 27}) {
            m.put("ripple" + n, () -> ripple(n));
            m.put("csel" + n, () -> csel(n, 3));
            m.put("prefix" + n, () -> prefix(n));
        }
        m.put("satadd9", () -> satadd(9));
        m.put("cmp27", () -> cmp(27));
        m.put("alu9", () -> alu(9));
        m.put("mul9", () -> mul(9));
        m.put("dot4x9", () -> dot(4, 9));
        m.put("tritsum27", () -> tritsum(27));
        m.put("counter9", () -> counter(9));
        m.put("shiftreg4x9", () -> shiftreg(4, 9));
        m.put("fifo3x9", () -> fifo(9));
        m.put("regfile9x9", () -> regfile(9));
        m.put("mulfsm9", () -> mulfsm(9));
        m.put("irqctl9", () -> irqctl());
        m.put("traffic", () -> traffic());
        return m;
    }

    static Design ripple(int n) {
        Design d = new Design("ripple" + n);
        Sig a = d.input("a", Type.trits(n)), b = d.input("b", Type.trits(n)), c = d.input("cin", Type.TRIT);
        d.output("s", Arith.addWide(d, a, b, c));
        return d;
    }

    static Design csel(int n, int k) {
        Design d = new Design("csel" + n);
        Sig a = d.input("a", Type.trits(n)), b = d.input("b", Type.trits(n)), c = d.input("cin", Type.TRIT);
        d.output("s", Arith.carrySelect(d, a, b, c, k));
        return d;
    }

    static Design prefix(int n) {
        Design d = new Design("prefix" + n);
        Sig a = d.input("a", Type.trits(n)), b = d.input("b", Type.trits(n)), c = d.input("cin", Type.TRIT);
        d.output("s", Arith.prefixAdd(d, a, b, c));
        return d;
    }

    static Design satadd(int n) {
        Design d = new Design("satadd" + n);
        d.output("s", Arith.satAdd(d, d.input("a", Type.trits(n)), d.input("b", Type.trits(n))));
        return d;
    }

    static Design cmp(int n) {
        Design d = new Design("cmp" + n);
        d.output("k", d.input("a", Type.trits(n)).cmp(d.input("b", Type.trits(n))));
        return d;
    }

    static Design alu(int n) {
        Design d = new Design("alu" + n);
        Sig a = d.input("a", Type.trits(n)), b = d.input("b", Type.trits(n));
        Sig op1 = d.input("op1", Type.TRIT), op2 = d.input("op2", Type.TRIT);
        Sig z = Arith.zeros(d, n - 1);
        Sig g1 = op2.mux3(a.add(b), a.add(b.neg()), a.min(b));
        Sig g2 = op2.mux3(a.max(b), a.sum(b), a.cons(b));
        Sig g3 = op2.mux3(a.shl(1), a.shr(1), z.concat(a.cmp(b)));
        d.output("y", op1.mux3(g1, g2, g3));
        return d;
    }

    static Design mul(int n) {
        Design d = new Design("mul" + n);
        d.output("p", Arith.mul(d, d.input("a", Type.trits(n)), d.input("b", Type.trits(n))));
        return d;
    }

    static Design dot(int k, int n) {
        Design d = new Design("dot" + k + "x" + n);
        int ext = 2;
        Sig acc = Arith.zeros(d, n + ext);
        for (int i = 0; i < k; i++) {
            Sig x = d.input("x" + i, Type.trits(n)), w = d.input("w" + i, Type.TRIT);
            acc = acc.add(Arith.widen(d, Arith.mulDigit(d, x, w), ext));
        }
        d.output("y", acc);
        return d;
    }

    static Design tritsum(int n) {
        Design d = new Design("tritsum" + n);
        Sig v = d.input("v", Type.trits(n));
        int w = 4;   // |sum| <= 27 < 40 = (3^4 - 1)/2
        Sig acc = Arith.zeros(d, w);
        for (int i = 0; i < n; i++) acc = acc.add(Arith.widen(d, v.digit(i), w - 1));
        d.output("s", acc);
        return d;
    }

    static Design counter(int n) {
        Design d = new Design("counter" + n);
        d.resetInput();
        Sig en = d.input("en", Type.BIT);
        Sig c = d.reg("c", Type.trits(n));
        c.next(d.mux(en, c.add(d.trits("0".repeat(n - 1) + "+")), c));
        d.output("q", c);
        return d;
    }

    static Design shiftreg(int stages, int n) {
        Design d = new Design("shiftreg" + stages + "x" + n);
        d.resetInput();
        Sig in = d.input("din", Type.trits(n)), en = d.input("en", Type.BIT);
        Sig prev = in;
        for (int s = 0; s < stages; s++) { Sig r = d.reg("s" + s, Type.trits(n)); r.next(d.mux(en, prev, r)); prev = r; }
        d.output("dout", prev);
        return d;
    }

    /** Depth-3 FIFO with one-trit pointers advanced by cyclic increment (ICI) and a 2-trit occupancy count. */
    static Design fifo(int n) {
        Design d = new Design("fifo3x" + n);
        d.resetInput();
        Sig din = d.input("din", Type.trits(n)), push = d.input("push", Type.BIT), pop = d.input("pop", Type.BIT);
        Sig wp = d.reg("wp", Type.TRIT), rp = d.reg("rp", Type.TRIT), cnt = d.reg("cnt", Type.trits(2));
        Sig full = cnt.cmp(d.trits("+0")).isZero();          // 3
        Sig empty = cnt.cmp(d.trits("00")).isZero();
        Sig doPush = push.and(full.not()), doPop = pop.and(empty.not());
        Sig[] mem = new Sig[3];
        String[] idx = {"-", "0", "+"};
        for (int i = 0; i < 3; i++) {
            mem[i] = d.reg("m" + i, Type.trits(n));
            Sig hit = wp.cmp(d.trits(idx[i])).isZero().and(doPush);
            mem[i].next(d.mux(hit, din, mem[i]));
        }
        wp.next(d.mux(doPush, wp.ici(), wp));
        rp.next(d.mux(doPop, rp.ici(), rp));
        Sig inc = d.mux(doPush, d.trits("0+"), d.trits("00")), dec = d.mux(doPop, d.trits("0-"), d.trits("00"));
        cnt.next(cnt.add(inc).add(dec));
        d.output("dout", rp.mux3(mem[0], mem[1], mem[2]));
        d.output("full", full);
        d.output("empty", empty);
        return d;
    }

    /** 9 registers x n trits, one write port, two read ports; 2-trit addresses (-4..4). */
    static Design regfile(int n) {
        Design d = new Design("regfile9x" + n);
        d.resetInput();
        Sig we = d.input("we", Type.BIT), wa = d.input("wa", Type.trits(2)), wd = d.input("wd", Type.trits(n));
        Sig ra = d.input("ra", Type.trits(2)), rb = d.input("rb", Type.trits(2));
        Sig[] r = new Sig[9];
        for (int i = 0; i < 9; i++) {
            r[i] = d.reg("r" + i, Type.trits(n));
            String addr = "" + "-0+".charAt(i / 3) + "-0+".charAt(i % 3);
            r[i].next(d.mux(we.and(wa.cmp(d.trits(addr)).isZero()), wd, r[i]));
        }
        for (String[] p : new String[][]{{"qa", "ra"}, {"qb", "rb"}}) {
            Sig a = p[1].equals("ra") ? ra : rb;
            Sig[] row = new Sig[3];
            for (int h = 0; h < 3; h++) row[h] = a.digit(0).mux3(r[3 * h], r[3 * h + 1], r[3 * h + 2]);
            d.output(p[0], a.digit(1).mux3(row[0], row[1], row[2]));
        }
        return d;
    }

    /** Sequential shift-and-add multiplier: one multiplier digit per cycle. */
    static Design mulfsm(int n) {
        Design d = new Design("mulfsm" + n);
        d.resetInput();
        Sig start = d.input("start", Type.BIT), a = d.input("a", Type.trits(n)), b = d.input("b", Type.trits(n));
        Sig mc = d.reg("mc", Type.trits(2 * n)), mp = d.reg("mp", Type.trits(n)), acc = d.reg("acc", Type.trits(2 * n));
        Sig steps = d.reg("steps", Type.trits(3));
        Sig busy = steps.cmp(Arith.zeros(d, 3)).isZero().not();
        Sig pp = mp.digit(0).mux3(mc.neg(), Arith.zeros(d, 2 * n), mc);
        mc.next(d.mux(start, Arith.widen(d, a, n), d.mux(busy, mc.shl(1), mc)));
        mp.next(d.mux(start, b, d.mux(busy, mp.shr(1), mp)));
        acc.next(d.mux(start, Arith.zeros(d, 2 * n), d.mux(busy, acc.add(pp), acc)));
        Sig nTr = d.trits("+00");   // 9
        steps.next(d.mux(start, nTr, d.mux(busy, steps.add(d.trits("00-")), steps)));
        d.output("p", acc);
        d.output("done", busy.not());
        return d;
    }

    /** 9 interrupt lines, mask register, pending register, fixed priority (line 0 highest); 2-trit id output. */
    static Design irqctl() {
        Design d = new Design("irqctl9");
        d.resetInput();
        Sig[] req = new Sig[9], mask = new Sig[9], pend = new Sig[9];
        Sig wm = d.input("wmask", Type.BIT), ack = d.input("ack", Type.BIT);
        Sig maskIn = d.input("maskin", Type.trits(9));   // digit i: P enables line i
        for (int i = 0; i < 9; i++) {
            req[i] = d.input("req" + i, Type.BIT);
            mask[i] = d.reg("mask" + i, Type.BIT);
            mask[i].next(d.mux(wm, maskIn.digit(i).isPos(), mask[i]));
            pend[i] = d.reg("pend" + i, Type.BIT);
        }
        Sig found = d.bit(false);
        Sig id = d.trits("00");
        Sig[] grant = new Sig[9];
        for (int i = 0; i < 9; i++) {
            Sig act = pend[i].and(mask[i]);
            grant[i] = act.and(found.not());
            String addr = "" + "-0+".charAt(i / 3) + "-0+".charAt(i % 3);
            id = d.mux(grant[i], d.trits(addr), id);
            found = found.or(act);
        }
        for (int i = 0; i < 9; i++) pend[i].next(req[i].or(pend[i].and(ack.and(grant[i]).not())));
        d.output("valid", found);
        d.output("id", id);
        return d;
    }

    /** Traffic light: state trit (N red, O yellow, P green), 2-trit timer, car sensor. */
    static Design traffic() {
        Design d = new Design("traffic");
        d.resetInput();
        Sig car = d.input("car", Type.BIT);
        Sig st = d.reg("st", Type.TRIT), tm = d.reg("tm", Type.trits(2));
        Sig expired = tm.cmp(d.trits("00")).isZero();
        Sig nextSt = st.mux3(d.mux(car, d.trits("+"), d.trits("-")), d.trits("-"), d.trits("0"));   // red->green on car, yellow->red, green->yellow
        Sig load = st.mux3(d.trits("++"), d.trits("0+"), d.trits("+0"));                           // durations 4, 1, 3
        st.next(d.mux(expired, nextSt, st));
        tm.next(d.mux(expired, nextSt.mux3(d.trits("++"), d.trits("0+"), d.trits("+0")), tm.add(d.trits("0-"))));
        d.output("light", st);
        d.output("timer", tm);
        return d;
    }
}
