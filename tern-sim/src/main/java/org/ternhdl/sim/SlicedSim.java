package org.ternhdl.sim;

import java.util.*;
import org.ternhdl.core.*;

/**
 * Lane-sliced simulator: 64 independent simulations run in parallel. Every trit digit is held as four bit planes,
 * one bit per lane: n (level N), p (level P), x (unknown, U or X), u (the unknown is U). Level O is all planes zero.
 * Invariants: n, p, x are pairwise exclusive; u is a subset of x. Only wiring ops and registers can carry u.
 * Semantics are identical to {@link Interpreter} (the reference); fuzz tests check this lane by lane.
 */
public final class SlicedSim implements Simulator {
    public static final int LANES = 64;

    private final Design m;
    private final long[][] n, p, x, u;
    private final long[][] sn, sp, sx, su;
    private boolean dirty = true;
    private final Logic3[][][][] mem;   // [memory][lane][entry][digit]

    public SlicedSim(Design m) {
        m.finish();
        this.m = m;
        int nodes = m.nodes().size();
        n = new long[nodes][]; p = new long[nodes][]; x = new long[nodes][]; u = new long[nodes][];
        for (Node nd : m.nodes()) {
            int w = nd.type().width();
            n[nd.id()] = new long[w]; p[nd.id()] = new long[w]; x[nd.id()] = new long[w]; u[nd.id()] = new long[w];
            if (nd.op() == Op.INPUT) { Arrays.fill(x[nd.id()], -1L); Arrays.fill(u[nd.id()], -1L); }
        }
        int r = m.regs().size();
        sn = new long[r][]; sp = new long[r][]; sx = new long[r][]; su = new long[r][];
        for (int i = 0; i < r; i++) {
            int w = m.regs().get(i).type().width();
            sn[i] = new long[w]; sp[i] = new long[w]; sx[i] = new long[w]; su[i] = new long[w];
            Arrays.fill(sx[i], -1L); Arrays.fill(su[i], -1L);
        }
        mem = new Logic3[m.mems().size()][][][];
        for (Design.Mem mm : m.mems()) {
            Logic3[][][] a = new Logic3[LANES][mm.depth()][mm.type.width()];
            for (Logic3[][] lane : a) {
                for (Logic3[] e : lane) Arrays.fill(e, Logic3.O);
                for (var en : mm.init().entrySet()) lane[en.getKey()] = en.getValue().clone();
            }
            mem[mm.index()] = a;
        }
    }

    private int laneIndex(int node, int lane) {
        int idx = 0, w3 = 1;
        for (int i = 0; i < n[node].length; i++) {
            if ((x[node][i] >>> lane & 1) != 0) return -1;
            int d = (n[node][i] >>> lane & 1) != 0 ? 0 : (p[node][i] >>> lane & 1) != 0 ? 2 : 1;
            idx += d * w3; w3 *= 3;
        }
        return idx;
    }

    // ---------------------------------------------------------------- poke / peek
    private int sigId(String name) {
        Integer id = m.inputs().get(name);
        if (id == null) id = m.outputs().get(name);
        if (id == null) id = m.named().get(name);
        if (id == null) throw new IllegalArgumentException("no signal " + name);
        return id;
    }

    public void poke(String input, int lane, Logic3[] le) {
        Integer id = m.inputs().get(input);
        if (id == null) throw new IllegalArgumentException("no input " + input);
        if (le.length != n[id].length) throw new IllegalArgumentException("width of " + input);
        long bit = 1L << lane;
        for (int i = 0; i < le.length; i++) {
            n[id][i] &= ~bit; p[id][i] &= ~bit; x[id][i] &= ~bit; u[id][i] &= ~bit;
            switch (le[i]) {
                case N -> n[id][i] |= bit;
                case P -> p[id][i] |= bit;
                case O -> { }
                case U -> { x[id][i] |= bit; u[id][i] |= bit; }
                default -> x[id][i] |= bit;
            }
        }
        dirty = true;
    }

    /** Same value on all lanes. */
    public void pokeAll(String input, Logic3[] le) { for (int l = 0; l < LANES; l++) poke(input, l, le); }
    @Override public void poke(String input, String msbFirst) { pokeAll(input, Logic3.parseVec(msbFirst)); }

    public Logic3[] peekVec(String name, int lane) {
        eval();
        int id = sigId(name);
        return lanes(n[id], p[id], x[id], u[id], lane);
    }

    private static Logic3[] lanes(long[] pn, long[] pp, long[] px, long[] pu, int lane) {
        int w = pn.length;
        Logic3[] r = new Logic3[w];
        for (int i = 0; i < w; i++) {
            if ((px[i] >>> lane & 1) != 0) r[i] = (pu[i] >>> lane & 1) != 0 ? Logic3.U : Logic3.X;
            else if ((pn[i] >>> lane & 1) != 0) r[i] = Logic3.N;
            else if ((pp[i] >>> lane & 1) != 0) r[i] = Logic3.P;
            else r[i] = Logic3.O;
        }
        return r;
    }

    /** Value of node id on one lane (after eval). */
    public Logic3[] nodeValue(int id, int lane) { eval(); return lanes(n[id], p[id], x[id], u[id], lane); }

    @Override public String peek(String name) { return Logic3.str(peekVec(name, 0)); }

    // ---------------------------------------------------------------- evaluation
    @Override public void eval() {
        if (!dirty) return;
        for (Node nd : m.nodes()) if (nd.op() != Op.INPUT) evalNode(nd);
        dirty = false;
    }

    @Override public void step() {
        eval();
        for (Design.Mem mm : m.mems()) for (int[] w : mm.writes()) for (int l = 0; l < LANES; l++) {
            long bit = 1L << l;
            boolean weX = (x[w[0]][0] & bit) != 0, weP = (p[w[0]][0] & bit) != 0;
            if (!weX && !weP) continue;
            int idx = laneIndex(w[1], l);
            Logic3[][] a = mem[mm.index()][l];
            if (idx < 0) { for (Logic3[] e : a) Arrays.fill(e, Logic3.X); continue; }
            if (weX) { Arrays.fill(a[idx], Logic3.X); continue; }
            Logic3[] d = lanes(n[w[2]], p[w[2]], x[w[2]], u[w[2]], l);
            for (int k = 0; k < d.length; k++) if (!d[k].isLevel()) d[k] = Logic3.X;
            a[idx] = d;
        }
        for (int i = 0; i < sn.length; i++) {
            int d = m.regNext(i);
            for (int k = 0; k < sn[i].length; k++) {
                sn[i][k] = n[d][k]; sp[i][k] = p[d][k]; sx[i][k] = x[d][k]; su[i][k] = 0L;   // U becomes X at the clock edge
            }
        }
        dirty = true;
    }

    private void evalNode(Node nd) {
        int id = nd.id();
        int w = nd.type().width();
        int[] a = nd.args();
        long[] on = n[id], op = p[id], ox = x[id], ou = u[id];
        Arrays.fill(ou, 0L);
        switch (nd.op()) {
            case CONST -> {
                Logic3[] c = nd.constant();
                for (int i = 0; i < w; i++) {
                    on[i] = c[i] == Logic3.N ? -1L : 0L;
                    op[i] = c[i] == Logic3.P ? -1L : 0L;
                    ox[i] = c[i].isLevel() ? 0L : -1L;
                    ou[i] = c[i] == Logic3.U ? -1L : 0L;
                }
            }
            case MEMRD -> {
                Arrays.fill(on, 0L); Arrays.fill(op, 0L); Arrays.fill(ox, 0L);
                for (int l = 0; l < LANES; l++) {
                    long bit = 1L << l;
                    int idx = laneIndex(a[0], l);
                    for (int i = 0; i < w; i++) {
                        Logic3 v = idx < 0 ? Logic3.X : mem[nd.aux()][l][idx][i];
                        if (v == Logic3.N) on[i] |= bit; else if (v == Logic3.P) op[i] |= bit; else if (!v.isLevel()) ox[i] |= bit;
                    }
                }
            }
            case REG -> {
                int r = nd.aux();
                System.arraycopy(sn[r], 0, on, 0, w); System.arraycopy(sp[r], 0, op, 0, w);
                System.arraycopy(sx[r], 0, ox, 0, w); System.arraycopy(su[r], 0, ou, 0, w);
            }
            case PRIM -> {
                if (nd.type().isBit()) {   // Bit domain: unknown is 0 or 1; AND has 0 dominant, OR has 1 dominant
                    long ap = p[a[0]][0], ax = x[a[0]][0], bp = p[a[1]][0], bx = x[a[1]][0];
                    long a0 = ~(ap | ax), b0 = ~(bp | bx);
                    on[0] = 0;
                    if (nd.prim() == Prim.MIN) { op[0] = ap & bp; ox[0] = (ax | bx) & ~(a0 | b0); }
                    else { op[0] = ap | bp; ox[0] = (ax | bx) & ~(ap | bp); }
                } else prim(nd.prim(), a, on, op, ox, w);
            }
            case ADD -> add(a, on, op, ox, w);
            case SHL -> { int k = nd.aux(); for (int i = 0; i < w; i++) copy(a[0], i - k, id, i); }
            case SHR -> { int k = nd.aux(); for (int i = 0; i < w; i++) copy(a[0], i + k < w ? i + k : -1, id, i); }
            case SLICE -> { for (int i = 0; i < w; i++) copy(a[0], nd.aux2() + i, id, i); }
            case CONCAT -> {
                int pos = 0;
                for (int k = a.length - 1; k >= 0; k--) { int sw = n[a[k]].length; for (int i = 0; i < sw; i++) copy(a[k], i, id, pos + i); pos += sw; }
            }
            case CMP -> cmp(a, on, op, ox);
            case TOBIT -> {
                int s = a[0];
                long an = n[s][0], ap = p[s][0], ax = x[s][0];
                on[0] = 0; ox[0] = ax;
                op[0] = nd.aux() == 0 ? ap : nd.aux() == 1 ? an : ~(an | ap | ax);
            }
            case TOTRIT -> {
                int s = a[0];
                long ap = p[s][0], ax = x[s][0];
                op[0] = ap; ox[0] = ax; on[0] = nd.aux() == 1 ? ~(ap | ax) : 0L;
            }
            case BNOT -> { int s = a[0]; on[0] = 0; ox[0] = x[s][0]; op[0] = ~(p[s][0] | x[s][0]); }
            case MUXT -> {
                long sn_ = n[a[0]][0], sp_ = p[a[0]][0], sx_ = x[a[0]][0];
                long so = ~(sn_ | sp_ | sx_);
                for (int i = 0; i < w; i++) {
                    long an = n[a[1]][i], ap = p[a[1]][i], ax = x[a[1]][i];
                    long bn = n[a[2]][i], bp = p[a[2]][i], bx = x[a[2]][i];
                    long cn = n[a[3]][i], cp = p[a[3]][i], cx = x[a[3]][i];
                    long ao = ~(an | ap | ax), bo = ~(bn | bp | bx), co = ~(cn | cp | cx);
                    long nu = sx_ & an & bn & cn, pu = sx_ & ap & bp & cp, ou_ = sx_ & ao & bo & co;
                    on[i] = (sn_ & an) | (so & bn) | (sp_ & cn) | nu;
                    op[i] = (sn_ & ap) | (so & bp) | (sp_ & cp) | pu;
                    ox[i] = (sn_ & ax) | (so & bx) | (sp_ & cx) | (sx_ & ~(nu | pu | ou_));
                }
            }
            case MUXB -> {
                long sp_ = p[a[0]][0], sx_ = x[a[0]][0];
                long so = ~(sp_ | sx_);
                for (int i = 0; i < w; i++) {
                    long an = n[a[1]][i], ap = p[a[1]][i], ax = x[a[1]][i];
                    long bn = n[a[2]][i], bp = p[a[2]][i], bx = x[a[2]][i];
                    long ao = ~(an | ap | ax), bo = ~(bn | bp | bx);
                    long nu = sx_ & an & bn, pu = sx_ & ap & bp, ou_ = sx_ & ao & bo;
                    on[i] = (sp_ & an) | (so & bn) | nu;
                    op[i] = (sp_ & ap) | (so & bp) | pu;
                    ox[i] = (sp_ & ax) | (so & bx) | (sx_ & ~(nu | pu | ou_));
                }
            }
            default -> throw new IllegalStateException("op " + nd.op());
        }
    }

    /** Copy digit sd of node s into digit dd of node d (all planes, including u); sd out of range gives O. */
    private void copy(int s, int sd, int d, int dd) {
        if (sd < 0 || sd >= n[s].length) { n[d][dd] = 0; p[d][dd] = 0; x[d][dd] = 0; u[d][dd] = 0; return; }
        n[d][dd] = n[s][sd]; p[d][dd] = p[s][sd]; x[d][dd] = x[s][sd]; u[d][dd] = u[s][sd];
    }

    private void prim(Prim pr, int[] a, long[] on, long[] op, long[] ox, int w) {
        for (int i = 0; i < w; i++) {
            long an = n[a[0]][i], ap = p[a[0]][i], ax = x[a[0]][i];
            long ao = ~(an | ap | ax);
            long bn = 0, bp = 0, bx = 0, bo = -1L;
            if (pr.arity == 2) { bn = n[a[1]][i]; bp = p[a[1]][i]; bx = x[a[1]][i]; bo = ~(bn | bp | bx); }
            long rn, rp, rx;
            switch (pr) {
                case BUF -> { rn = an; rp = ap; rx = ax; }
                case STI -> { rn = ap; rp = an; rx = ax; }
                case PTI -> { rn = ap; rp = ~(ap | ax); rx = ax; }
                case NTI -> { rn = ~(an | ax); rp = an; rx = ax; }
                case ICI -> { rn = ap; rp = ao; rx = ax; }
                case DCI -> { rn = ao; rp = an; rx = ax; }
                case ISZERO -> { rn = an | ap; rp = ao; rx = ax; }
                case MIN -> { rn = an | bn; rp = ap & bp; rx = (ax | bx) & ~rn; }
                case MAX -> { rp = ap | bp; rn = an & bn; rx = (ax | bx) & ~rp; }
                case ANY -> {
                    rx = ax | bx;
                    rn = ((an & (bn | bo)) | (ao & bn)) & ~rx;
                    rp = ((ap & (bp | bo)) | (ao & bp)) & ~rx;
                }
                case CONS -> { rx = (ax | bx) & ~(ao | bo); rn = an & bn; rp = ap & bp; }
                case SUM -> {
                    rx = ax | bx;
                    rn = ((an & bo) | (ao & bn) | (ap & bp)) & ~rx;
                    rp = ((ap & bo) | (ao & bp) | (an & bn)) & ~rx;
                }
                default -> throw new IllegalStateException("prim " + pr);
            }
            on[i] = rn; op[i] = rp; ox[i] = rx;
        }
    }

    private void add(int[] a, long[] on, long[] op, long[] ox, int w) {
        long cN = 0, cP = 0, xc = 0;
        if (a.length == 3) { cN = n[a[2]][0]; cP = p[a[2]][0]; xc = x[a[2]][0]; }
        for (int i = 0; i < w; i++) {
            long an = n[a[0]][i], ap = p[a[0]][i], ax = x[a[0]][i];
            long bn = n[a[1]][i], bp = p[a[1]][i], bx = x[a[1]][i];
            long xx = xc | ax | bx;
            long ao = ~(an | ap | ax), bo = ~(bn | bp | bx);
            long r1n = (an & bo) | (ao & bn) | (ap & bp);
            long r1p = (ap & bo) | (ao & bp) | (an & bn);
            long c1P = ap & bp, c1N = an & bn;
            long r1o = ~(r1n | r1p);
            long co = ~(cN | cP);
            long rn = (r1n & co) | (r1o & cN) | (r1p & cP);
            long rp = (r1p & co) | (r1o & cP) | (r1n & cN);
            long c2P = r1p & cP, c2N = r1n & cN;
            long coutP = (c1P & ~c2N) | (c2P & ~c1N);
            long coutN = (c1N & ~c2P) | (c2N & ~c1P);
            on[i] = rn & ~xx; op[i] = rp & ~xx; ox[i] = xx;
            cN = coutN & ~xx; cP = coutP & ~xx; xc = xx;
        }
    }

    private void cmp(int[] a, long[] on, long[] op, long[] ox) {
        int w = n[a[0]].length;
        long decided = 0, rn = 0, rp = 0, rx = 0;
        for (int i = w - 1; i >= 0; i--) {
            long an = n[a[0]][i], ap = p[a[0]][i], ax = x[a[0]][i];
            long bn = n[a[1]][i], bp = p[a[1]][i], bx = x[a[1]][i];
            long ao = ~(an | ap | ax), bo = ~(bn | bp | bx);
            long unk = ax | bx, und = ~decided;
            long gt = (ap & (bn | bo)) | (ao & bn);
            long lt = (an & (bp | bo)) | (ao & bp);
            long dx = und & unk, dp = und & ~unk & gt, dn = und & ~unk & lt;
            rx |= dx; rp |= dp; rn |= dn; decided |= dx | dp | dn;
        }
        on[0] = rn; op[0] = rp; ox[0] = rx;
    }
}
