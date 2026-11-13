package org.ternhdl.map;

import java.util.*;
import org.ternhdl.core.*;

/** Direct per-digit lowering of an RTL design to a GT netlist (also the Park-style pattern-mapping baseline). */
public final class Lower {
    public record Result(Netlist netlist, List<int[]> regDigits) { }   // regDigits: {rtlRegIndex, digit, dffInstIndex}

    private final Design d;
    private final Netlist nl;
    private final int[][] nets;
    private final List<int[]> regDigits = new ArrayList<>();
    private final Map<Integer, Map<Integer, Integer>> unaryMemo = new HashMap<>();

    private Lower(Design d) {
        d.finish();
        this.d = d;
        this.nl = new Netlist(d.name + "_gt");
        this.nets = new int[d.nodes().size()][];
    }

    public static Result lower(Design d) { return new Lower(d).run(); }

    private int unary(int x, int code) { return UnaryDb.build(nl, x, code, unaryMemo.computeIfAbsent(x, k -> new HashMap<>())); }

    private Result run() {
        int[] dffOf = new int[0];
        for (Node nd : d.nodes()) {
            int id = nd.id(), w = nd.type().width();
            int[] a0 = nd.args().length > 0 ? nets[nd.args()[0]] : null;
            int[] r = new int[w];
            switch (nd.op()) {
                case INPUT -> r = nl.addInput(nd.name(), w, nd.type().isBit());
                case CONST -> { for (int i = 0; i < w; i++) r[i] = nl.tie(nd.constant()[i].isLevel() ? nd.constant()[i] : Logic3.O); }
                case REG -> {
                    for (int i = 0; i < w; i++) {
                        int idx = nl.dff(nd.name() + "[" + i + "]");
                        regDigits.add(new int[]{nd.aux(), i, idx});
                        r[i] = nl.dffOut(idx);
                    }
                }
                case PRIM -> {
                    Cell c = Cell.of(nd.prim());
                    for (int i = 0; i < w; i++) r[i] = c.arity == 1 ? nl.add(c, a0[i]) : nl.add(c, a0[i], nets[nd.args()[1]][i]);
                }
                case ADD -> {
                    int[] b = nets[nd.args()[1]];
                    int carry = nd.args().length == 3 ? nets[nd.args()[2]][0] : nl.tie(Logic3.O);
                    for (int i = 0; i < w; i++) {
                        int s1 = nl.add(Cell.SUM, a0[i], b[i]);
                        r[i] = nl.add(Cell.SUM, s1, carry);
                        if (i + 1 < w) carry = nl.add(Cell.ANY, nl.add(Cell.CONS, a0[i], b[i]), nl.add(Cell.CONS, s1, carry));
                    }
                }
                case SHL -> { for (int i = 0; i < w; i++) r[i] = i - nd.aux() >= 0 ? a0[i - nd.aux()] : nl.tie(Logic3.O); }
                case SHR -> { for (int i = 0; i < w; i++) r[i] = i + nd.aux() < w ? a0[i + nd.aux()] : nl.tie(Logic3.O); }
                case SLICE -> { for (int i = 0; i < w; i++) r[i] = a0[nd.aux2() + i]; }
                case CONCAT -> {
                    int pos = 0;
                    for (int k = nd.args().length - 1; k >= 0; k--) { for (int x : nets[nd.args()[k]]) r[pos++] = x; }
                }
                case CMP -> {
                    int[] b = nets[nd.args()[1]];
                    int acc = -1;
                    for (int i = 0; i < a0.length; i++) {
                        int di = nl.add(Cell.ANY, a0[i], nl.add(Cell.STI, b[i]));      // sign(a_i - b_i)
                        acc = acc < 0 ? di : nl.add(Cell.ANY, di, nl.add(Cell.ANY, di, acc));   // d != 0 ? d : prev
                    }
                    r[0] = acc;
                }
                case TOBIT -> {
                    int code = nd.aux() == 0 ? UnaryDb.code(0, 0, 1) : nd.aux() == 1 ? UnaryDb.code(1, 0, 0) : UnaryDb.code(0, 1, 0);
                    r[0] = unary(a0[0], code);
                }
                case TOTRIT -> r[0] = nd.aux() == 0 ? a0[0]
                    : unary(a0[0], UnaryDb.cheapest(UnaryDb.code(-1, -1, 1), UnaryDb.code(0, -1, 1), UnaryDb.code(1, -1, 1)));
                case BNOT -> r[0] = unary(a0[0], UnaryDb.cheapest(UnaryDb.code(-1, 1, 0), UnaryDb.code(0, 1, 0), UnaryDb.code(1, 1, 0)));
                case MUXT -> { for (int i = 0; i < w; i++) r[i] = nl.add(Cell.MUX3, a0[0], nets[nd.args()[1]][i], nets[nd.args()[2]][i], nets[nd.args()[3]][i]); }
                case MUXB -> { for (int i = 0; i < w; i++) { int bi = nets[nd.args()[2]][i]; r[i] = nl.add(Cell.MUX3, a0[0], bi, bi, nets[nd.args()[1]][i]); } }
                default -> throw new IllegalStateException("op " + nd.op());
            }
            nets[id] = r;
        }
        for (int[] rd : regDigits) nl.setD(rd[2], nets[d.regNext(rd[0])][rd[1]]);
        for (var en : d.outputs().entrySet()) nl.addOutput(en.getKey(), nets[en.getValue()], d.node(en.getValue()).type().isBit());
        return new Result(nl, regDigits);
    }
}
