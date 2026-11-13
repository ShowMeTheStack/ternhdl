package org.ternhdl.core;

import java.util.*;

/** A hardware design (one module): an IR graph plus a builder API. Single binary clock, optional synchronous reset. */
public final class Design {
    public record Reg(String name, Type type, Logic3[] reset, int readNode) { }

    /** A property on a Bit node, checked every cycle: ASSERT must be 1; ASSUME constrains the environment (formal). */
    public enum PropKind { ASSERT, ASSUME }
    public record Prop(String name, PropKind kind, int node) { }

    /** A memory of 3^addrTrits words; entry i holds the word at balanced address i - (3^addrTrits - 1)/2. */
    public final class Mem {
        public final String name;
        public final Type type;
        public final int addrTrits;
        final int index;
        final List<int[]> writes = new ArrayList<>();          // {we, addr, data}
        final Map<Integer, Logic3[]> init = new HashMap<>();   // entry -> little-endian digits

        Mem(String name, Type type, int addrTrits, int index) { this.name = name; this.type = type; this.addrTrits = addrTrits; this.index = index; }

        public int depth() { int d = 1; for (int i = 0; i < addrTrits; i++) d *= 3; return d; }
        public int index() { return index; }
        public List<int[]> writes() { return Collections.unmodifiableList(writes); }
        public Map<Integer, Logic3[]> init() { return Collections.unmodifiableMap(init); }

        /** Asynchronous read. */
        public Sig read(Sig addr) {
            if (addr.type().isBit() || addr.width() != addrTrits) throw new IllegalArgumentException("address must be " + addrTrits + " trits");
            return new Sig(Design.this, addNode(Op.MEMRD, type, new int[]{addr.id}, null, index, 0, null, null));
        }

        /** Synchronous write port (ports apply in declaration order at the clock edge). */
        public void write(Sig we, Sig addr, Sig data) {
            if (!we.type().isBit()) throw new IllegalArgumentException("write enable must be Bit");
            if (addr.width() != addrTrits || !data.type().equals(type)) throw new IllegalArgumentException("write port types");
            writes.add(new int[]{we.id, addr.id, data.id});
        }

        /** Initial content of the entry at balanced address a (default: all O). */
        public void init(long address, Logic3[] le) {
            long h = (depth() - 1) / 2;
            if (address < -h || address > h || le.length != type.width()) throw new IllegalArgumentException("init " + address);
            init.put((int) (address + h), le.clone());
        }
    }

    public final String name;
    private final List<Node> nodes = new ArrayList<>();
    private final LinkedHashMap<String, Integer> inputs = new LinkedHashMap<>();
    private final LinkedHashMap<String, Integer> outputs = new LinkedHashMap<>();
    private final LinkedHashMap<String, Integer> named = new LinkedHashMap<>();
    private final List<Reg> regs = new ArrayList<>();
    private final List<Integer> regD = new ArrayList<>();
    private final List<Prop> props = new ArrayList<>();
    private final List<Mem> mems = new ArrayList<>();
    private int rstNode = -1;
    private int[] regNext;

    public Design(String name) { this.name = name; }

    // ---------------------------------------------------------------- accessors
    public List<Node> nodes() { return Collections.unmodifiableList(nodes); }
    public Node node(int id) { return nodes.get(id); }
    public Map<String, Integer> inputs() { return Collections.unmodifiableMap(inputs); }
    public Map<String, Integer> outputs() { return Collections.unmodifiableMap(outputs); }
    public Map<String, Integer> named() { return Collections.unmodifiableMap(named); }
    public List<Reg> regs() { return Collections.unmodifiableList(regs); }
    public List<Prop> props() { return Collections.unmodifiableList(props); }
    public List<Mem> mems() { return Collections.unmodifiableList(mems); }

    /** Design lint: structural warnings that matter for implementation (not semantic errors). */
    public List<String> lint() {
        List<String> w = new ArrayList<>();
        for (Mem m : mems) {
            if (m.writes.size() > 1)
                w.add("memory " + m.name + " has " + m.writes.size() + " write ports: FPGA tools will not map it to RAM (it becomes registers); merge the ports with a mux");
            boolean read = false;
            for (Node n : nodes) if (n.op() == Op.MEMRD && n.aux() == m.index) read = true;
            if (!read) w.add("memory " + m.name + " is never read");
        }
        return w;
    }

    public Mem memory(String nm, Type t, int addrTrits) {
        if (addrTrits < 1 || addrTrits > 12) throw new IllegalArgumentException("addrTrits 1..12");
        Mem m = new Mem(nm, t, addrTrits, mems.size());
        mems.add(m);
        return m;
    }

    /** Node id of the synchronous reset input, or -1. Properties are not checked while reset is 1. */
    public int resetNode() { return rstNode; }

    /** cond (Bit) must be 1 in every cycle. Unknown counts as a violation in simulation. */
    public void assertAlways(String nm, Sig cond) { prop(nm, PropKind.ASSERT, cond); }

    /** Environment assumption (formal); in simulation a violation is reported as a testbench error. */
    public void assume(String nm, Sig cond) { prop(nm, PropKind.ASSUME, cond); }

    private void prop(String nm, PropKind k, Sig cond) {
        if (!cond.type().isBit()) throw new IllegalArgumentException("property " + nm + " needs a Bit condition");
        for (Prop p : props) if (p.name().equals(nm)) throw new IllegalArgumentException("duplicate property " + nm);
        props.add(new Prop(nm, k, cond.id));
    }

    /** Node id computing the next state of register i (after finish()). */
    public int regNext(int i) { finish(); return regNext[i]; }

    // ---------------------------------------------------------------- construction
    int addNode(Op op, Type type, int[] args, Prim prim, int aux, int aux2, Logic3[] c, String nm) {
        if (regNext != null) throw new IllegalStateException("module is finished");
        int id = nodes.size();
        nodes.add(new Node(id, op, type, args, prim, aux, aux2, c, nm));
        return id;
    }

    /** Handle to an existing input (for instrumenting generated designs). */
    public Sig inputSig(String nm) { Integer id = inputs.get(nm); if (id == null) throw new IllegalArgumentException("no input " + nm); return new Sig(this, id); }

    /** Handle to the node driving an existing output. */
    public Sig outputSig(String nm) { Integer id = outputs.get(nm); if (id == null) throw new IllegalArgumentException("no output " + nm); return new Sig(this, id); }

    public Sig input(String nm, Type t) {
        if (inputs.containsKey(nm)) throw new IllegalArgumentException("duplicate input " + nm);
        int id = addNode(Op.INPUT, t, new int[0], null, 0, 0, null, nm);
        inputs.put(nm, id);
        return new Sig(this, id);
    }

    /** Declares the synchronous reset input "rst" (Bit). Registers reset to their reset value while rst is 1. */
    public Sig resetInput() {
        if (rstNode >= 0) throw new IllegalStateException("reset already declared");
        Sig s = input("rst", Type.BIT);
        rstNode = s.id;
        return s;
    }

    public void output(String nm, Sig s) {
        if (outputs.containsKey(nm)) throw new IllegalArgumentException("duplicate output " + nm);
        outputs.put(nm, s.id);
    }

    public Sig constant(Logic3... msbFirst) {
        Logic3[] le = new Logic3[msbFirst.length];
        for (int i = 0; i < le.length; i++) le[i] = msbFirst[le.length - 1 - i];
        return constantLE(le, Type.trits(le.length));
    }

    /** Constant from a most-significant-first string such as "+0-" (also X and U allowed). */
    public Sig trits(String s) { return constantLE(Logic3.parseVec(s), Type.trits(s.length())); }

    public Sig bit(boolean v) { return constantLE(new Logic3[]{v ? Logic3.P : Logic3.O}, Type.BIT); }

    Sig constantLE(Logic3[] le, Type t) {
        return new Sig(this, addNode(Op.CONST, t, new int[0], null, 0, 0, le, null));
    }

    /** Register that resets to O (all digits). */
    public Sig reg(String nm, Type t) {
        Logic3[] z = new Logic3[t.width()];
        Arrays.fill(z, Logic3.O);
        return reg(nm, t, z);
    }

    public Sig reg(String nm, Type t, Logic3[] resetLE) {
        if (resetLE.length != t.width()) throw new IllegalArgumentException("reset width");
        int idx = regs.size();
        int id = addNode(Op.REG, t, new int[0], null, idx, 0, null, nm);
        regs.add(new Reg(nm, t, resetLE.clone(), id));
        regD.add(-1);
        named.put(nm, id);
        return new Sig(this, id);
    }

    void setNext(int regNode, Sig d) {
        Node n = nodes.get(regNode);
        if (n.op() != Op.REG) throw new IllegalArgumentException("not a register");
        if (!n.type().equals(d.type())) throw new IllegalArgumentException("next type " + d.type() + " != " + n.type());
        if (regD.get(n.aux()) >= 0) throw new IllegalStateException("next already set for " + n.name());
        regD.set(n.aux(), d.id);
    }

    void nameNode(String nm, int id) {
        if (named.putIfAbsent(nm, id) != null) throw new IllegalArgumentException("duplicate name " + nm);
    }

    public Sig mux(Sig sel, Sig a, Sig b) {
        if (!sel.type().isBit()) throw new IllegalArgumentException("mux select must be Bit");
        if (!a.type().equals(b.type())) throw new IllegalArgumentException("mux operand types differ");
        return new Sig(this, addNode(Op.MUXB, a.type(), new int[]{sel.id, a.id, b.id}, null, 0, 0, null, null));
    }

    /** Completes the module: registers without next hold; reset muxes are added. Idempotent. */
    public synchronized void finish() {
        if (regNext != null) return;
        int n = regs.size();
        int[] next = new int[n];
        for (int i = 0; i < n; i++) {
            Reg r = regs.get(i);
            int d = regD.get(i) >= 0 ? regD.get(i) : r.readNode();
            if (rstNode >= 0) {
                int c = addNodeUnchecked(Op.CONST, r.type(), new int[0], null, 0, 0, r.reset(), null);
                d = addNodeUnchecked(Op.MUXB, r.type(), new int[]{rstNode, c, d}, null, 0, 0, null, null);
            }
            next[i] = d;
        }
        regNext = next;
    }

    private int addNodeUnchecked(Op op, Type type, int[] args, Prim prim, int aux, int aux2, Logic3[] c, String nm) {
        int id = nodes.size();
        nodes.add(new Node(id, op, type, args, prim, aux, aux2, c, nm));
        return id;
    }
}
