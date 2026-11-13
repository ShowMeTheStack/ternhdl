package org.ternhdl.core;

/** A handle to an IR node with a fluent operator API. */
public final class Sig {
    final Design m;
    final int id;

    Sig(Design m, int id) { this.m = m; this.id = id; }

    public int id() { return id; }
    public Type type() { return m.node(id).type(); }
    public int width() { return type().width(); }

    private Sig wrap(int nid) { return new Sig(m, nid); }
    private void sameType(Sig o) {
        if (o.m != m) throw new IllegalArgumentException("different modules");
        if (!type().equals(o.type())) throw new IllegalArgumentException("type mismatch: " + type() + " vs " + o.type());
    }
    private void trit() { if (type().isBit()) throw new IllegalArgumentException("operation needs trits, got Bit"); }

    private Sig p1(Prim p) { trit(); return wrap(m.addNode(Op.PRIM, type(), new int[]{id}, p, 0, 0, null, null)); }
    private Sig p2(Prim p, Sig o) { sameType(o); return wrap(m.addNode(Op.PRIM, type(), new int[]{id, o.id}, p, 0, 0, null, null)); }

    // digit-wise unary
    public Sig not() {
        if (type().isBit()) return wrap(m.addNode(Op.BNOT, Type.BIT, new int[]{id}, null, 0, 0, null, null));
        return p1(Prim.STI);
    }
    public Sig neg() { return p1(Prim.STI); }
    public Sig pti() { return p1(Prim.PTI); }
    public Sig nti() { return p1(Prim.NTI); }
    public Sig ici() { return p1(Prim.ICI); }
    public Sig dci() { return p1(Prim.DCI); }
    public Sig isZeroDigit() { return p1(Prim.ISZERO); }

    // digit-wise binary (also AND/OR on Bit via MIN/MAX)
    public Sig min(Sig o) { return p2(Prim.MIN, o); }
    public Sig max(Sig o) { return p2(Prim.MAX, o); }
    public Sig any(Sig o) { return p2(Prim.ANY, o); }
    public Sig cons(Sig o) { return p2(Prim.CONS, o); }
    public Sig sum(Sig o) { return p2(Prim.SUM, o); }
    public Sig and(Sig o) { if (!type().isBit()) throw new IllegalArgumentException("and needs Bit"); return p2(Prim.MIN, o); }
    public Sig or(Sig o) { if (!type().isBit()) throw new IllegalArgumentException("or needs Bit"); return p2(Prim.MAX, o); }

    // arithmetic
    public Sig add(Sig o) { trit(); sameType(o); return wrap(m.addNode(Op.ADD, type(), new int[]{id, o.id}, null, 0, 0, null, null)); }
    public Sig add(Sig o, Sig cin) {
        trit(); sameType(o);
        if (cin.type().isBit() || cin.width() != 1) throw new IllegalArgumentException("carry-in must be one trit");
        return wrap(m.addNode(Op.ADD, type(), new int[]{id, o.id, cin.id}, null, 0, 0, null, null));
    }
    public Sig shl(int k) { trit(); return wrap(m.addNode(Op.SHL, type(), new int[]{id}, null, k, 0, null, null)); }
    public Sig shr(int k) { trit(); return wrap(m.addNode(Op.SHR, type(), new int[]{id}, null, k, 0, null, null)); }
    public Sig cmp(Sig o) { trit(); sameType(o); return wrap(m.addNode(Op.CMP, Type.TRIT, new int[]{id, o.id}, null, 0, 0, null, null)); }

    // structure
    public Sig slice(int hi, int lo) {
        if (lo < 0 || hi < lo || hi >= width()) throw new IllegalArgumentException("bad slice " + hi + ":" + lo + " of " + width());
        return wrap(m.addNode(Op.SLICE, type().isBit() ? Type.BIT : Type.trits(hi - lo + 1), new int[]{id}, null, hi, lo, null, null));
    }
    public Sig digit(int i) { return slice(i, i); }
    /** This is the most significant part; the arguments follow in decreasing significance. */
    public Sig concat(Sig... lower) {
        trit();
        int w = width();
        int[] a = new int[lower.length + 1];
        a[0] = id;
        for (int i = 0; i < lower.length; i++) { lower[i].trit(); a[i + 1] = lower[i].id; w += lower[i].width(); }
        return wrap(m.addNode(Op.CONCAT, Type.trits(w), a, null, 0, 0, null, null));
    }
    /** This must be one trit: N selects a, O selects b, P selects c. */
    public Sig mux3(Sig a, Sig b, Sig c) {
        trit();
        if (width() != 1) throw new IllegalArgumentException("mux3 select must be one trit");
        a.sameType(b); a.sameType(c);
        return wrap(m.addNode(Op.MUXT, a.type(), new int[]{id, a.id, b.id, c.id}, null, 0, 0, null, null));
    }

    // radix crossing
    public Sig isPos() { return cross(0); }
    public Sig isNeg() { return cross(1); }
    public Sig isZero() { return cross(2); }
    private Sig cross(int mode) {
        trit();
        if (width() != 1) throw new IllegalArgumentException("needs one trit");
        return wrap(m.addNode(Op.TOBIT, Type.BIT, new int[]{id}, null, mode, 0, null, null));
    }
    /** Bit to trit: 0 becomes O, 1 becomes P. */
    public Sig toTrit() { return toTrit(0); }
    /** Bit to trit: 0 becomes N, 1 becomes P. */
    public Sig toTritSigned() { return toTrit(1); }
    private Sig toTrit(int mode) {
        if (!type().isBit()) throw new IllegalArgumentException("needs Bit");
        return wrap(m.addNode(Op.TOTRIT, Type.TRIT, new int[]{id}, null, mode, 0, null, null));
    }

    // naming and state
    public Sig named(String nm) { m.nameNode(nm, id); return this; }
    /** Sets the next-state value of this register. */
    public void next(Sig d) { m.setNext(id, d); }
}
