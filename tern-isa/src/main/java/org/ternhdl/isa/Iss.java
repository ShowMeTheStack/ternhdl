package org.ternhdl.isa;

import java.util.*;

/** Instruction-set simulator for T27 (the golden model). */
public final class Iss {
    public final long[] x = new long[27];          // index = register number + 13
    public long pc;
    public boolean halted;
    public long steps;
    private final Map<Long, Long> mem = new HashMap<>();   // tryte address -> tryte value
    public final long memLo, memHi;                         // allowed tryte window (inclusive)

    public interface Trace { void retire(long pc, long insn, int rd, long value, long memAddr, long memValue, boolean store); }
    public Trace trace;

    public Iss(int windowTrits) { long h = (T27.pow3(windowTrits) - 1) / 2; memLo = -h; memHi = h; }

    public long reg(int r) { return x[r + 13]; }
    void setReg(int r, long v) { if (r != 0) x[r + 13] = T27.wrap(v); }

    void check(long a) { if (a < memLo || a > memHi) throw new IllegalStateException("address out of window: " + a + " at pc " + pc); }

    public long tryte(long a) { check(a); return mem.getOrDefault(a, 0L); }
    public void setTryte(long a, long v) { check(a); mem.put(a, T27.wrap(v, 9)); }

    /** Clears the lowest trit (ISA v0.3: word accesses and JALR targets ignore it). */
    public static long align(long a) { return a - T27.trits(T27.wrap(a), 27)[0]; }

    public long word(long a) {
        a = align(a);
        return tryte(a - 1) + tryte(a) * T27.MT + tryte(a + 1) * T27.MT * T27.MT;   // centered word
    }

    public void setWord(long a, long v) {
        a = align(a);
        int[] t = T27.trits(T27.wrap(v), 27);
        for (int k = 0; k < 3; k++) setTryte(a - 1 + k, T27.value(t, 9 * k, 9));   // centered word
    }

    public void load(Asm.Program p) { for (var e : p.words().entrySet()) setWord(e.getKey(), e.getValue()); pc = p.entry(); }

    public void run(long maxSteps) { while (!halted && steps < maxSteps) step(); if (!halted) throw new IllegalStateException("no HALT within " + maxSteps + " steps"); }

    public void step() {
        long insn = word(pc);
        int[] t = T27.trits(insn, 27);
        int op = (int) T27.value(t, 0, 3), rd = (int) T27.value(t, 3, 3), ra = (int) T27.value(t, 6, 3), rb = (int) T27.value(t, 9, 3);
        int fn = (int) T27.value(t, 12, 3);
        long imm = T27.value(t, 9, 18);
        long next = pc + 3;
        long a = reg(ra), b = reg(rb);
        int wr = 0; long wv = 0; long ma = 0, mv = 0; boolean st = false;
        switch (op) {
            case 0 -> { if (fn == 0) halted = true; else throw new IllegalStateException("SYS fn " + fn); }
            case 1 -> { wr = rd; wv = alu(fn, a, b); }
            case 2 -> { wr = rd; wv = a + imm; }
            case 3 -> { wr = rd; wv = imm * T27.MT; }
            case 4 -> { wr = rd; wv = T27.shl(a, shamt(imm)); }
            case 5 -> { wr = rd; wv = T27.shr(a, shamt(imm)); }
            case 6 -> { wr = rd; ma = align(T27.wrap(a + imm)); wv = word(ma); mv = wv; }
            case 7 -> { wr = rd; ma = T27.wrap(a + imm); wv = tryte(ma); mv = wv; }
            case 8 -> { ma = align(T27.wrap(a + imm)); mv = reg(rd); setWord(ma, mv); st = true; }
            case 9 -> { ma = T27.wrap(a + imm); mv = T27.wrap(reg(rd), 9); setTryte(ma, mv); st = true; }
            case 10 -> {
                int ba = rd, bb = ra;                         // B format: ra in trits 3-5, rb in 6-8
                int s = Long.signum(reg(ba) - reg(bb));
                if (t[9 + (s + 1)] == 1) next = pc + 3 * T27.value(t, 12, 15);
            }
            case 11 -> { wr = rd; wv = pc + 3; next = pc + 3 * T27.value(t, 6, 21); }
            case 12 -> { long tgt = align(T27.wrap(a + imm)); wr = rd; wv = pc + 3; next = tgt; }
            case 13 -> { wr = rd; wv = Long.signum(a - imm); }
            default -> throw new IllegalStateException("illegal opcode " + op + " at pc " + pc);
        }
        if (wr != 0) setReg(wr, wv);
        if (trace != null) trace.retire(pc, insn, wr, wr != 0 ? reg(wr) : 0, ma, mv, st);
        pc = T27.wrap(next);
        steps++;
    }

    /** Unbalanced value of the lowest three trits (0..26). */
    public static int shamt(long b) { int[] t = T27.trits(T27.wrap(b), 27); return (t[0] + 1) + 3 * (t[1] + 1) + 9 * (t[2] + 1); }

    public static long alu(int fn, long a, long b) {
        return switch (fn) {
            case 0 -> T27.wrap(a + b);
            case 1 -> T27.wrap(a - b);
            case 2 -> T27.mul(a, b)[0];
            case 3 -> T27.mul(a, b)[1];
            case 4 -> T27.tritwise(a, b, Math::min);
            case 5 -> T27.tritwise(a, b, Math::max);
            case 6 -> T27.tritwise(a, b, (p, q) -> Math.max(-1, Math.min(1, p + q)));
            case 7 -> T27.tritwise(a, b, (p, q) -> p == q ? p : 0);
            case 8 -> T27.tritwise(a, b, (p, q) -> { int s = p + q; return s > 1 ? s - 3 : s < -1 ? s + 3 : s; });
            case 9 -> Long.signum(a - b);
            case 10 -> T27.shl(a, shamt(b));
            case 11 -> T27.shr(a, shamt(b));
            case 12 -> -a;
            case -1 -> T27.tritwise(a, 0, (p, q) -> p == 1 ? -1 : 1);
            case -2 -> T27.tritwise(a, 0, (p, q) -> p == -1 ? 1 : -1);
            case -3 -> T27.tritwise(a, 0, (p, q) -> p == 1 ? -1 : p + 1);
            case -4 -> T27.tritwise(a, 0, (p, q) -> p == -1 ? 1 : p - 1);
            case -5 -> T27.tritwise(a, 0, (p, q) -> p == 0 ? 1 : -1);
            case -6 -> Long.signum(a);
            default -> throw new IllegalStateException("ALU fn " + fn);
        };
    }
}
