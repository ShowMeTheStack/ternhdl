package org.ternhdl.cpu;

import java.math.BigInteger;
import java.util.*;
import org.ternhdl.core.*;
import org.ternhdl.isa.*;
import org.ternhdl.sim.*;

/** Runs a core and the ISS in lockstep on one program; returns mismatch descriptions. */
final class Lockstep {
    record Event(long pc, int rd, long val, boolean store, long ma, long mv) { }
    record Result(long instructions, long cycles, List<String> errors, double seconds) { }

    private Lockstep() { }

    static long val(Logic3[] v) { return Arith.toBig(v).longValue(); }

    static List<Event> issTrace(Asm.Program p, long max) {
        Iss iss = new Iss(8);
        iss.load(p);
        List<Event> ev = new ArrayList<>();
        iss.trace = (pc, insn, rd, value, ma, mv, st) -> ev.add(new Event(pc, rd, value, st, ma, mv));
        iss.run(max);
        return ev;
    }

    /** Lockstep check over recorded per-cycle outputs (e.g. from a Verilator twin); cycle 0 is the reset cycle. */
    static Result check(List<Map<String, Logic3[]>> cycles, List<Event> trace) {
        long t0 = System.nanoTime();
        List<String> err = new ArrayList<>();
        int i = 0;
        for (int c = 1; c < cycles.size() && i < trace.size() && err.size() <= 10; c++) {
            Map<String, Logic3[]> o = cycles.get(c);
            if (o.get("retire")[0] != Logic3.P) continue;
            Event e = trace.get(i);
            if (val(o.get("r_pc")) != e.pc()) err.add("instr " + i + ": pc " + val(o.get("r_pc")) + " vs ISS " + e.pc());
            boolean we = o.get("rf_we")[0] == Logic3.P;
            if (e.rd() != 0 && (!we || val(o.get("rf_rd")) != e.rd() || val(o.get("rf_wd")) != e.val())) err.add("instr " + i + " pc " + e.pc() + ": register write mismatch");
            if (e.rd() == 0 && we) err.add("instr " + i + ": unexpected register write");
            boolean mw = o.get("mw")[0] == Logic3.P;
            if (mw != e.store() || (mw && (val(o.get("mw_addr")) != e.ma() || val(o.get("mw_data")) != e.mv()))) err.add("instr " + i + " pc " + e.pc() + ": store mismatch");
            i++;
        }
        if (i < trace.size() && err.isEmpty()) err.add("only " + i + " of " + trace.size() + " instructions retired in " + cycles.size() + " cycles");
        return new Result(i, cycles.size() - 1, err, (System.nanoTime() - t0) / 1e9);
    }

    static Result run(Design core, Simulator sim, java.util.function.Function<String, Logic3[]> peek, List<Event> trace, long maxCycles) {
        long t0 = System.nanoTime();
        List<String> err = new ArrayList<>();
        sim.poke("rst", "+"); sim.poke("ld_we", "0");
        sim.poke("ld_row", "0".repeat(core.node(core.inputs().get("ld_row")).type().width())); sim.poke("ld_data", "0".repeat(27));
        sim.poke("peek_row", "0".repeat(core.node(core.inputs().get("peek_row")).type().width()));
        sim.step();
        sim.poke("rst", "0");
        int i = 0;
        long cycles = 0;
        while (cycles < maxCycles && i < trace.size()) {
            sim.eval();
            if (peek.apply("retire")[0] == Logic3.P) {
                Event e = trace.get(i);
                long pc = val(peek.apply("r_pc"));
                if (pc != e.pc()) err.add("instr " + i + ": pc " + pc + " vs ISS " + e.pc());
                boolean we = peek.apply("rf_we")[0] == Logic3.P;
                if (e.rd() != 0) {
                    if (!we) err.add("instr " + i + " pc " + e.pc() + ": missing register write x" + e.rd());
                    else {
                        long rd = val(peek.apply("rf_rd")), wd = val(peek.apply("rf_wd"));
                        if (rd != e.rd() || wd != e.val()) err.add("instr " + i + " pc " + e.pc() + ": x" + rd + "=" + wd + " vs ISS x" + e.rd() + "=" + e.val());
                    }
                } else if (we) err.add("instr " + i + " pc " + e.pc() + ": unexpected register write");
                boolean mw = peek.apply("mw")[0] == Logic3.P;
                if (mw != e.store()) err.add("instr " + i + " pc " + e.pc() + ": store " + mw + " vs ISS " + e.store());
                else if (mw && (val(peek.apply("mw_addr")) != e.ma() || val(peek.apply("mw_data")) != e.mv()))
                    err.add("instr " + i + " pc " + e.pc() + ": store " + val(peek.apply("mw_addr")) + "=" + val(peek.apply("mw_data")) + " vs " + e.ma() + "=" + e.mv());
                if (err.size() > 10) break;
                i++;
            }
            sim.step();
            cycles++;
        }
        if (i < trace.size() && err.isEmpty()) err.add("stopped after " + i + " of " + trace.size() + " instructions (" + cycles + " cycles)");
        return new Result(i, cycles, err, (System.nanoTime() - t0) / 1e9);
    }
}
