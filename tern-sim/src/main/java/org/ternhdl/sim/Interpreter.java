package org.ternhdl.sim;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import org.ternhdl.core.*;

/** Reference simulator: scalar, Logic3-valued, evaluates the IR node by node using the golden-model operators. */
public final class Interpreter implements Simulator {
    private final Design m;
    private final Logic3[][] val;
    private final Logic3[][] state;
    private final Logic3[][][] memState;
    private boolean dirty = true;
    private long cycle;
    private VcdWriter vcd;
    private final List<String> violations = new ArrayList<>();
    private List<String> traced;

    public Interpreter(Design m) {
        m.finish();
        this.m = m;
        val = new Logic3[m.nodes().size()][];
        state = new Logic3[m.regs().size()][];
        for (int i = 0; i < state.length; i++) { state[i] = new Logic3[m.regs().get(i).type().width()]; Arrays.fill(state[i], Logic3.U); }
        for (Node n : m.nodes()) if (n.op() == Op.INPUT) { val[n.id()] = new Logic3[n.type().width()]; Arrays.fill(val[n.id()], Logic3.U); }
        memState = new Logic3[m.mems().size()][][];
        for (Design.Mem mm : m.mems()) {
            Logic3[][] a = new Logic3[mm.depth()][mm.type.width()];
            for (Logic3[] e : a) Arrays.fill(e, Logic3.O);
            for (var en : mm.init().entrySet()) a[en.getKey()] = en.getValue().clone();
            memState[mm.index()] = a;
        }
    }

    public Design module() { return m; }
    public long cycle() { return cycle; }

    public void poke(String input, Logic3[] le) {
        Integer id = m.inputs().get(input);
        if (id == null) throw new IllegalArgumentException("no input " + input);
        Node n = m.node(id);
        if (le.length != n.type().width()) throw new IllegalArgumentException("width of " + input);
        if (n.type().isBit()) for (Logic3 v : le) if (v == Logic3.N) throw new IllegalArgumentException("Bit cannot be N");
        val[id] = le.clone();
        dirty = true;
    }

    @Override public void poke(String input, String msbFirst) { poke(input, Logic3.parseVec(msbFirst)); }

    /** Value of node id (after eval). */
    public Logic3[] nodeValue(int id) { eval(); return val[id].clone(); }

    public Logic3[] peekVec(String name) {
        eval();
        Integer id = m.inputs().get(name);
        if (id == null) id = m.outputs().get(name);
        if (id == null) id = m.named().get(name);
        if (id == null) throw new IllegalArgumentException("no signal " + name);
        return val[id].clone();
    }

    @Override public String peek(String name) { return Logic3.str(peekVec(name)); }

    @Override public void eval() {
        if (!dirty) return;
        for (Node nd : m.nodes()) if (nd.op() != Op.INPUT) val[nd.id()] = evalNode(nd);
        dirty = false;
    }

    /** Property violations seen so far ("cycle N: kind name = value"). */
    public List<String> violations() { return Collections.unmodifiableList(violations); }

    private void checkProps() {
        if (m.resetNode() >= 0 && val[m.resetNode()][0] == Logic3.P) return;   // disabled while reset is 1
        for (Design.Prop p : m.props()) {
            Logic3 v = val[p.node()][0];
            if (v != Logic3.P) violations.add("cycle " + cycle + ": " + p.kind() + " " + p.name() + " = " + v);
        }
    }

    @Override public void step() {
        eval();
        checkProps();
        if (vcd != null) dump(10 * cycle, 0);
        Logic3[][] next = new Logic3[state.length][];
        for (int i = 0; i < state.length; i++) {
            Logic3[] d = val[m.regNext(i)].clone();
            for (int k = 0; k < d.length; k++) if (!d[k].isLevel()) d[k] = Logic3.X;
            next[i] = d;
        }
        // memory writes use the values before the edge, ports in order
        for (Design.Mem mm : m.mems()) for (int[] w : mm.writes()) {
            Logic3 we = val[w[0]][0];
            if (we == Logic3.O) continue;
            int idx = memIndex(val[w[1]]);
            Logic3[] data = val[w[2]].clone();
            for (int k = 0; k < data.length; k++) if (!data[k].isLevel()) data[k] = Logic3.X;
            Logic3[][] a = memState[mm.index()];
            if (idx < 0) { for (Logic3[] e : a) Arrays.fill(e, Logic3.X); continue; }       // unknown address: poison all
            if (we == Logic3.P) a[idx] = data;
            else Arrays.fill(a[idx], Logic3.X);                                          // unknown enable
        }
        System.arraycopy(next, 0, state, 0, state.length);
        dirty = true;
        cycle++;
        if (vcd != null) { eval(); dump(10 * (cycle - 1) + 5, 1); }
    }

    // ---------------------------------------------------------------- waveforms
    public void trace(Path file) throws IOException {
        vcd = new VcdWriter(file, m.name);
        traced = new ArrayList<>();
        traced.addAll(m.inputs().keySet());
        for (String o : m.outputs().keySet()) if (!traced.contains(o)) traced.add(o);
        for (String nm : m.named().keySet()) if (!traced.contains(nm)) traced.add(nm);
        for (String nm : traced) {
            Integer id = m.inputs().containsKey(nm) ? m.inputs().get(nm) : m.outputs().containsKey(nm) ? m.outputs().get(nm) : m.named().get(nm);
            vcd.declare(nm, m.node(id).type().width());
        }
        vcd.declareClock();
        vcd.endDefinitions();
    }

    private void dump(long time, int clk) {
        Map<String, String> vals = new LinkedHashMap<>();
        for (String nm : traced) vals.put(nm, peekRaw(nm));
        vcd.dump(time, clk, vals);
    }

    private String peekRaw(String nm) {
        Integer id = m.inputs().get(nm);
        if (id == null) id = m.outputs().get(nm);
        if (id == null) id = m.named().get(nm);
        return Logic3.str(val[id]);
    }

    public void close() throws IOException { if (vcd != null) { vcd.close(); vcd = null; } }

    // ---------------------------------------------------------------- node evaluation
    private Logic3[] a(Node nd, int k) { return val[nd.args()[k]]; }

    private static Logic3 xOf(Logic3 v) { return v.isLevel() ? v : Logic3.X; }

    /** Entry index of a balanced address, or -1 when any digit is unknown. */
    static int memIndex(Logic3[] addr) {
        int idx = 0, p = 1;
        for (Logic3 d : addr) { if (!d.isLevel()) return -1; idx += (d.bal() + 1) * p; p *= 3; }
        return idx;
    }

    /** Current content of a memory entry (balanced address). */
    public Logic3[] memWord(int mem, long address) {
        Logic3[][] a = memState[mem];
        return a[(int) (address + (a.length - 1) / 2)].clone();
    }

    private Logic3[] evalNode(Node nd) {
        int w = nd.type().width();
        switch (nd.op()) {
            case CONST: return nd.constant().clone();
            case MEMRD: {
                int idx = memIndex(a(nd, 0));
                if (idx < 0) { Logic3[] r = new Logic3[w]; Arrays.fill(r, Logic3.X); return r; }
                return memState[nd.aux()][idx].clone();
            }
            case REG: return state[nd.aux()].clone();
            case PRIM: {
                Logic3[] r = new Logic3[w];
                Prim p = nd.prim();
                for (int i = 0; i < w; i++) {
                    Logic3[] in = new Logic3[p.arity];
                    for (int k = 0; k < p.arity; k++) in[k] = a(nd, k)[i];
                    r[i] = nd.type().isBit() ? p.evalBit(in) : p.eval(in);   // Bit: unknown ranges over {0, 1}
                }
                return r;
            }
            case ADD: return Arith.add(a(nd, 0), a(nd, 1), nd.args().length == 3 ? a(nd, 2)[0] : Logic3.O);
            case SHL: return Arith.shl(a(nd, 0), nd.aux());
            case SHR: return Arith.shr(a(nd, 0), nd.aux());
            case CMP: return new Logic3[]{Arith.cmp(a(nd, 0), a(nd, 1))};
            case CONCAT: {
                Logic3[] r = new Logic3[w];
                int pos = 0;
                for (int k = nd.args().length - 1; k >= 0; k--) { Logic3[] s = a(nd, k); System.arraycopy(s, 0, r, pos, s.length); pos += s.length; }
                return r;
            }
            case SLICE: {
                Logic3[] r = new Logic3[w];
                System.arraycopy(a(nd, 0), nd.aux2(), r, 0, w);
                return r;
            }
            case TOBIT: {
                Logic3 v = a(nd, 0)[0];
                if (!v.isLevel()) return new Logic3[]{Logic3.X};
                boolean t = nd.aux() == 0 ? v == Logic3.P : nd.aux() == 1 ? v == Logic3.N : v == Logic3.O;
                return new Logic3[]{t ? Logic3.P : Logic3.O};
            }
            case TOTRIT: {
                Logic3 v = a(nd, 0)[0];
                if (!v.isLevel()) return new Logic3[]{Logic3.X};
                return new Logic3[]{v == Logic3.P ? Logic3.P : (nd.aux() == 0 ? Logic3.O : Logic3.N)};
            }
            case BNOT: {
                Logic3 v = a(nd, 0)[0];
                return new Logic3[]{!v.isLevel() ? Logic3.X : v == Logic3.P ? Logic3.O : Logic3.P};
            }
            case MUXT: {
                Logic3[] r = new Logic3[w];
                Logic3 s = a(nd, 0)[0];
                for (int i = 0; i < w; i++) r[i] = Prim.MUX3.eval(s, a(nd, 1)[i], a(nd, 2)[i], a(nd, 3)[i]);
                return r;
            }
            case MUXB: {
                Logic3[] r = new Logic3[w];
                Logic3 s = a(nd, 0)[0];
                for (int i = 0; i < w; i++) {
                    Logic3 x = a(nd, 1)[i], y = a(nd, 2)[i];
                    // select P (1) -> a, O (0) -> b; unknown select: common known value else X
                    if (s == Logic3.P) r[i] = xOf(x);
                    else if (s == Logic3.O) r[i] = xOf(y);
                    else r[i] = (x.isLevel() && x == y) ? x : Logic3.X;
                }
                return r;
            }
            default: throw new IllegalStateException("op " + nd.op());
        }
    }
}
