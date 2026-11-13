package org.ternhdl.sim;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.ternhdl.core.*;

class SimTest {
    private static final Logic3[] VALS = {Logic3.N, Logic3.O, Logic3.P, Logic3.U, Logic3.X};

    private static Logic3[] combo(int c, int arity, Logic3[] alphabet) {
        Logic3[] in = new Logic3[arity];
        for (int k = 0; k < arity; k++) { in[k] = alphabet[c % alphabet.length]; c /= alphabet.length; }
        return in;
    }

    /** Runs a one-node design on every input combination through both simulators and compares with the reference. */
    private void checkOne(Design d, List<String> ins, Logic3[][] alphabets, java.util.function.Function<Logic3[], Logic3> ref) {
        Interpreter it = new Interpreter(d);
        int total = 1;
        for (Logic3[] al : alphabets) total *= al.length;
        for (int base = 0; base < total; base += SlicedSim.LANES) {
            SlicedSim ss = new SlicedSim(d);
            int lanes = Math.min(SlicedSim.LANES, total - base);
            for (int l = 0; l < lanes; l++) {
                int c = base + l;
                Logic3[] in = new Logic3[ins.size()];
                for (int k = 0; k < in.length; k++) { in[k] = alphabets[k][c % alphabets[k].length]; c /= alphabets[k].length; }
                for (int k = 0; k < in.length; k++) { ss.poke(ins.get(k), l, new Logic3[]{in[k]}); it.poke(ins.get(k), new Logic3[]{in[k]}); }
                Logic3 exp = ref.apply(in);
                assertEquals(exp, it.peekVec("y")[0], "interpreter " + Arrays.toString(in));
                assertEquals(exp, ss.peekVec("y", l)[0], "sliced " + Arrays.toString(in));
            }
        }
    }

    @Test
    void everyPrimitiveMatchesTheReferenceOnAllInputs() {
        for (Prim p : Prim.values()) {
            if (p.arity == 0 || p == Prim.MUX3) continue;
            Design d = new Design("one");
            Sig a = d.input("a", Type.TRIT);
            Sig y;
            List<String> ins = new ArrayList<>(List.of("a"));
            // BUF is not exposed in the builder API (it is a no-op); the other primitives are built through Sig
            Sig b = p.arity == 2 ? d.input("b", Type.TRIT) : null;
            if (b != null) ins.add("b");
            y = switch (p) {
                case BUF -> null;
                case STI -> a.neg(); case PTI -> a.pti(); case NTI -> a.nti(); case ICI -> a.ici(); case DCI -> a.dci();
                case ISZERO -> a.isZeroDigit();
                case MIN -> a.min(b); case MAX -> a.max(b); case ANY -> a.any(b); case CONS -> a.cons(b); case SUM -> a.sum(b);
                default -> throw new IllegalStateException();
            };
            if (y == null) continue;
            d.output("y", y);
            Logic3[][] al = new Logic3[ins.size()][];
            Arrays.fill(al, VALS);
            checkOne(d, ins, al, in -> p.eval(in));
        }
    }

    @Test
    void mux3MatchesTheReference() {
        Design d = new Design("mux");
        Sig s = d.input("s", Type.TRIT), a = d.input("a", Type.TRIT), b = d.input("b", Type.TRIT), c = d.input("c", Type.TRIT);
        d.output("y", s.mux3(a, b, c));
        Logic3[][] al = {VALS, VALS, VALS, VALS};
        checkOne(d, List.of("s", "a", "b", "c"), al, in -> Prim.MUX3.eval(in));
    }

    @Test
    void radixCrossingAndBitOpsMatch() {
        Logic3[] bitVals = {Logic3.O, Logic3.P, Logic3.U, Logic3.X};
        // isPos / isNeg / isZero
        for (int mode = 0; mode < 3; mode++) {
            final int mm = mode;
            Design d = new Design("c" + mode);
            Sig a = d.input("a", Type.TRIT);
            d.output("y", mm == 0 ? a.isPos() : mm == 1 ? a.isNeg() : a.isZero());
            checkOne(d, List.of("a"), new Logic3[][]{VALS}, in -> {
                Logic3 v = in[0];
                if (!v.isLevel()) return Logic3.X;
                boolean t = mm == 0 ? v == Logic3.P : mm == 1 ? v == Logic3.N : v == Logic3.O;
                return t ? Logic3.P : Logic3.O;
            });
        }
        // toTrit variants and bnot
        for (int mode = 0; mode < 2; mode++) {
            final int mm = mode;
            Design d = new Design("t" + mode);
            Sig b = d.input("a", Type.BIT);
            d.output("y", mm == 0 ? b.toTrit() : b.toTritSigned());
            checkOne(d, List.of("a"), new Logic3[][]{bitVals}, in -> {
                Logic3 v = in[0];
                if (!v.isLevel()) return Logic3.X;
                return v == Logic3.P ? Logic3.P : (mm == 0 ? Logic3.O : Logic3.N);
            });
        }
        Design d = new Design("bnot");
        d.output("y", d.input("a", Type.BIT).not());
        checkOne(d, List.of("a"), new Logic3[][]{bitVals}, in -> in[0].isLevel() ? (in[0] == Logic3.P ? Logic3.O : Logic3.P) : Logic3.X);
        // and / or are MIN / MAX on bits
        for (int k = 0; k < 2; k++) {
            final int kk = k;
            Design e = new Design("ao" + k);
            Sig x = e.input("a", Type.BIT), y = e.input("b", Type.BIT);
            e.output("y", kk == 0 ? x.and(y) : x.or(y));
            checkOne(e, List.of("a", "b"), new Logic3[][]{bitVals, bitVals}, in -> (kk == 0 ? Prim.MIN : Prim.MAX).evalBit(in));
        }
        // muxb
        Design m = new Design("muxb");
        Sig sel = m.input("s", Type.BIT), p = m.input("a", Type.TRIT), q = m.input("b", Type.TRIT);
        m.output("y", m.mux(sel, p, q));
        checkOne(m, List.of("s", "a", "b"), new Logic3[][]{bitVals, VALS, VALS}, in -> {
            Logic3 s = in[0], x = in[1], y = in[2];
            if (s == Logic3.P) return x.isLevel() ? x : Logic3.X;
            if (s == Logic3.O) return y.isLevel() ? y : Logic3.X;
            return x.isLevel() && x == y ? x : Logic3.X;
        });
    }

    @Test
    void addMatchesBigIntegerOnWidthsUpTo40() {
        Random r = new Random(7);
        for (int n : new int[]{1, 2, 3, 5, 9, 17, 27, 33, 40}) {
            Design d = new Design("add");
            Sig a = d.input("a", Type.trits(n)), b = d.input("b", Type.trits(n)), c = d.input("c", Type.TRIT);
            d.output("y", a.add(b, c));
            Interpreter it = new Interpreter(d);
            SlicedSim ss = new SlicedSim(d);
            Logic3[][] av = new Logic3[64][], bv = new Logic3[64][], cv = new Logic3[64][];
            for (int l = 0; l < 64; l++) {
                av[l] = randLevels(r, n); bv[l] = randLevels(r, n); cv[l] = randLevels(r, 1);
                ss.poke("a", l, av[l]); ss.poke("b", l, bv[l]); ss.poke("c", l, cv[l]);
            }
            for (int l = 0; l < 64; l++) {
                it.poke("a", av[l]); it.poke("b", bv[l]); it.poke("c", cv[l]);
                BigInteger exp = Arith.toBig(av[l]).add(Arith.toBig(bv[l])).add(Arith.toBig(cv[l]));
                assertEquals(Arith.toBig(Arith.fromBig(exp, n)), Arith.toBig(it.peekVec("y")), "interp n=" + n);
                assertEquals(Arith.toBig(Arith.fromBig(exp, n)), Arith.toBig(ss.peekVec("y", l)), "sliced n=" + n);
            }
        }
    }

    private static Logic3[] randLevels(Random r, int n) {
        Logic3[] v = new Logic3[n];
        for (int i = 0; i < n; i++) v[i] = Logic3.ofBal(r.nextInt(3) - 1);
        return v;
    }

    @Test
    void randomDesignsAgreeBetweenInterpreterAndSlicedSimulatorLaneByLane() {
        for (int seed = 0; seed < 400; seed++) {
            Random r = new Random(seed);
            boolean regs = seed % 2 == 0;
            Design d = RandomDesign.random(r, regs, 30);
            SlicedSim ss = new SlicedSim(d);
            Interpreter[] its = new Interpreter[SlicedSim.LANES];
            for (int l = 0; l < its.length; l++) its[l] = new Interpreter(d);
            int cycles = regs ? 8 : 1;
            for (int cyc = 0; cyc < cycles; cyc++) {
                for (var e : d.inputs().entrySet()) {
                    Node nd = d.node(e.getValue());
                    for (int l = 0; l < its.length; l++) {
                        Logic3[] v = new Logic3[nd.type().width()];
                        boolean isRst = e.getKey().equals("rst");
                        for (int i = 0; i < v.length; i++) v[i] = isRst && cyc > 0 ? (r.nextInt(6) == 0 ? Logic3.P : Logic3.O) : RandomDesign.randValue(r, nd.type().isBit());
                        its[l].poke(e.getKey(), v);
                        ss.poke(e.getKey(), l, v);
                    }
                }
                for (Node nd : d.nodes()) for (int l = 0; l < its.length; l++)
                    assertArrayEquals(its[l].nodeValue(nd.id()), ss.nodeValue(nd.id(), l),
                        "seed " + seed + " cycle " + cyc + " node " + nd.id() + " " + nd.op() + " lane " + l);
                for (Interpreter it : its) it.step();
                ss.step();
            }
        }
    }

    @Test
    void registerIsUninitializedUntilReset() {
        Design d = new Design("cnt");
        d.resetInput();
        Sig r = d.reg("c", Type.trits(4));
        r.next(r.add(d.trits("000+")));
        d.output("q", r);
        Interpreter it = new Interpreter(d);
        assertEquals("UUUU", it.peek("q"));
        it.poke("rst", "0");
        it.step();
        assertEquals("XXXX", it.peek("q"));           // U + 1 = X
        it.poke("rst", "+");
        it.step();
        assertEquals("0000", it.peek("q"));           // reset value
        it.poke("rst", "0");
        for (int i = 0; i < 5; i++) it.step();
        assertEquals("0+--", it.peek("q"));           // 5 = 1*9 + (-1)*3 + (-1)
    }
}
