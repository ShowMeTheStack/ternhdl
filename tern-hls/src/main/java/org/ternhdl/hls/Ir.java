package org.ternhdl.hls;

import java.util.*;

/** HLS intermediate representation: basic blocks of values (local value numbering), memory effects, terminators. */
final class Ir {
    enum K { CONST, VAR, ADD, SUB, NEG, LT, LE, GT, GE, EQ, NE, NOT, AND, OR, SEL, MEMRD, MIN, MAX, ANY, CONS, TSUM, SHL, SHR, MULD, WIDEN }

    static final class Val {
        final int id; final K k; final int[] args; final long c; final String name;
        int width; boolean bit;
        int state = -1; double arrive; int lastUse = -1;
        int after = -1;   // value that must be scheduled in an earlier state (memory read after write)
        Val(int id, K k, int[] args, long c, String name, int width, boolean bit) { this.id = id; this.k = k; this.args = args; this.c = c; this.name = name; this.width = width; this.bit = bit; }
    }

    /** mark = number of values created before this write (reads with a smaller id precede it in program order). */
    record MemWr(String mem, int addr, int data, int order, int mark) { }

    static final class Block {
        final int id;
        final List<Val> vals = new ArrayList<>();
        final Map<String, Integer> varOut = new LinkedHashMap<>();     // variable -> value id at block end (modified vars)
        final List<MemWr> writes = new ArrayList<>();
        final Map<MemWr, Integer> wrState = new HashMap<>();
        String term = "RET";                                            // GOTO, BR, RET
        int cond = -1, tgtTrue = -1, tgtFalse = -1, ret = -1;
        int states = 1;
        Block(int id) { this.id = id; }
    }

    record Var(String name, int width) { }
    record Arr(String name, int width, int depthTrits) { }

    final List<Block> blocks = new ArrayList<>();
    final Map<String, Var> vars = new LinkedHashMap<>();
    final List<String> params = new ArrayList<>();
    final Map<String, Arr> arrays = new LinkedHashMap<>();
    int retWidth = 27;
}
