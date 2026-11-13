package org.ternhdl.sim;

import java.util.*;
import org.ternhdl.core.*;

/** Generates random well-typed designs for differential testing. */
public final class RandomDesign {
    private RandomDesign() { }

    public static Design random(Random r, boolean withRegs, int steps) { return random(r, withRegs, steps, 1000); }

    /** maxWidth bounds concatenations so every signal stays at most maxWidth trits. */
    public static Design random(Random r, boolean withRegs, int steps, int maxWidth) {
        Design d = new Design("rnd");
        List<Sig> trits = new ArrayList<>(), bits = new ArrayList<>();
        int ni = 2 + r.nextInt(3);
        for (int i = 0; i < ni; i++) trits.add(d.input("i" + i, Type.trits(1 + r.nextInt(8))));
        trits.add(d.input("t1", Type.TRIT));
        bits.add(d.input("b0", Type.BIT));
        bits.add(d.input("b1", Type.BIT));
        List<Sig> regs = new ArrayList<>();
        if (withRegs) {
            bits.add(d.resetInput());
            int nr = 1 + r.nextInt(3);
            for (int i = 0; i < nr; i++) {
                Sig reg = d.reg("r" + i, Type.trits(1 + r.nextInt(8)));
                regs.add(reg);
                trits.add(reg);
            }
            Sig bReg = d.reg("rb", Type.BIT);
            regs.add(bReg);
            bits.add(bReg);
        }
        for (int s = 0; s < steps; s++) step(d, r, trits, bits, maxWidth);
        if (withRegs) for (Sig reg : regs) {
            List<Sig> pool = reg.type().isBit() ? bits : trits;
            List<Sig> ok = new ArrayList<>();
            for (Sig c : pool) if (c.type().equals(reg.type()) && c.id() != reg.id()) ok.add(c);
            reg.next(ok.isEmpty() ? reg : ok.get(r.nextInt(ok.size())));
        }
        for (int i = 0; i < 3; i++) {
            Sig o = trits.get(trits.size() - 1 - i % trits.size());
            if (!d.outputs().containsKey("o" + i)) d.output("o" + i, o);
        }
        return d;
    }

    private static Sig pick(Random r, List<Sig> l) { return l.get(r.nextInt(l.size())); }

    private static Sig sameWidth(Random r, List<Sig> l, Sig s) {
        List<Sig> ok = new ArrayList<>();
        for (Sig c : l) if (c.type().equals(s.type())) ok.add(c);
        return pick(r, ok);
    }

    private static Sig one(Random r, List<Sig> trits) {
        List<Sig> ok = new ArrayList<>();
        for (Sig c : trits) if (c.width() == 1) ok.add(c);
        return pick(r, ok);
    }

    private static void step(Design d, Random r, List<Sig> trits, List<Sig> bits, int maxWidth) {
        Sig a = pick(r, trits);
        switch (r.nextInt(18)) {
            case 0 -> trits.add(switch (r.nextInt(7)) { case 0 -> a.not(); case 1 -> a.neg(); case 2 -> a.pti(); case 3 -> a.nti(); case 4 -> a.ici(); case 5 -> a.dci(); default -> a.isZeroDigit(); });
            case 1, 2 -> { Sig b = sameWidth(r, trits, a); trits.add(switch (r.nextInt(5)) { case 0 -> a.min(b); case 1 -> a.max(b); case 2 -> a.any(b); case 3 -> a.cons(b); default -> a.sum(b); }); }
            case 3, 4 -> { Sig b = sameWidth(r, trits, a); trits.add(a.add(b)); }
            case 5 -> { Sig b = sameWidth(r, trits, a); trits.add(a.add(b, one(r, trits))); }
            case 6 -> trits.add(a.shl(r.nextInt(a.width() + 1)));
            case 7 -> trits.add(a.shr(r.nextInt(a.width() + 1)));
            case 8 -> { Sig b = sameWidth(r, trits, a); trits.add(a.cmp(b)); }
            case 9 -> { int lo = r.nextInt(a.width()); int hi = lo + r.nextInt(a.width() - lo); trits.add(a.slice(hi, lo)); }
            case 10 -> { Sig b = pick(r, trits); if (a.width() + b.width() <= maxWidth) trits.add(a.concat(b)); }
            case 11 -> bits.add(switch (r.nextInt(3)) { case 0 -> one(r, trits).isPos(); case 1 -> one(r, trits).isNeg(); default -> one(r, trits).isZero(); });
            case 12 -> trits.add(r.nextBoolean() ? pick(r, bits).toTrit() : pick(r, bits).toTritSigned());
            case 13 -> { Sig b = pick(r, bits); bits.add(switch (r.nextInt(3)) { case 0 -> b.not(); case 1 -> b.and(pick(r, bits)); default -> b.or(pick(r, bits)); }); }
            case 14 -> { Sig b = sameWidth(r, trits, a); Sig c = sameWidth(r, trits, a); trits.add(one(r, trits).mux3(a, b, c)); }
            case 15 -> { Sig b = sameWidth(r, trits, a); trits.add(d.mux(pick(r, bits), a, b)); }
            case 16 -> {
                int w = 1 + r.nextInt(6);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < w; i++) sb.append(r.nextInt(10) == 0 ? "X" : "-0+".charAt(r.nextInt(3)));
                trits.add(d.trits(sb.toString()));
            }
            default -> trits.add(d.bit(r.nextBoolean()).toTrit());
        }
    }

    public static Logic3 randValue(Random r, boolean bit) {
        int k = r.nextInt(20);
        if (k < 2) return Logic3.X;
        if (k < 3) return Logic3.U;
        return bit ? (r.nextBoolean() ? Logic3.P : Logic3.O) : Logic3.ofBal(r.nextInt(3) - 1);
    }
}
