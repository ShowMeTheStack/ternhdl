package org.ternhdl.cpu;

import java.util.*;
import org.ternhdl.core.*;

/**
 * Five-stage T27 pipeline (IF, ID, EX, MEM, WB). Forwarding from MEM and WB into EX and a WB->ID register-file bypass;
 * data memory reads are asynchronous, so a load's value is forwarded from MEM and no load-use stall is needed. Branches,
 * JAL and JALR resolve in EX (two younger instructions flushed). MUL/MULH use the radix-3 multi-cycle unit in EX and
 * stall IF/ID/EX (bubble into MEM). HALT stops fetch when decoded (unless flushed) and retires in WB.
 * Same ports as T27Core so the lockstep and board harnesses apply unchanged.
 */
public final class T27Pipe {
    static final int W = 27;

    private T27Pipe() { }

    /** Combinational decode of an instruction word. */
    static final class Dec {
        final Sig op, rd, ra, rb, fn, imm, boff, joff;
        final Sig sys, alu, addi, lui, shli, shri, lw, lt, sw, st, br, jal, jalr, cmpi, halt, isMul, writes;
        Dec(Design d, Sig insn) {
            op = insn.slice(2, 0); rd = insn.slice(5, 3); ra = insn.slice(8, 6); rb = insn.slice(11, 9); fn = insn.slice(14, 12);
            imm = Hw.widen(d, insn.slice(26, 9), W); boff = Hw.widen(d, insn.slice(26, 12), W); joff = Hw.widen(d, insn.slice(26, 6), W);
            sys = Hw.is(d, op, 0); alu = Hw.is(d, op, 1); addi = Hw.is(d, op, 2); lui = Hw.is(d, op, 3); shli = Hw.is(d, op, 4); shri = Hw.is(d, op, 5);
            lw = Hw.is(d, op, 6); lt = Hw.is(d, op, 7); sw = Hw.is(d, op, 8); st = Hw.is(d, op, 9); br = Hw.is(d, op, 10); jal = Hw.is(d, op, 11);
            jalr = Hw.is(d, op, 12); cmpi = Hw.is(d, op, 13);
            halt = sys.and(Hw.is(d, fn, 0));
            isMul = alu.and(Hw.is(d, fn, 2).or(Hw.is(d, fn, 3)));
            writes = alu.or(addi).or(lui).or(shli).or(shri).or(lw).or(lt).or(jal).or(jalr).or(cmpi).and(Hw.is(d, rd, 0).not());
        }
    }

    static Sig preg(Design d, String name, Type t, Sig rstOrFlush, Sig en, Sig in) {
        Sig r = d.reg(name, t);
        Logic3[] z = new Logic3[t.width()];
        Arrays.fill(z, Logic3.O);
        Sig zero = t.isBit() ? d.bit(false) : Hw.k(d, 0, t.width());
        r.next(d.mux(rstOrFlush, zero, d.mux(en, in, r)));
        return r;
    }

    public static Design build(int k) {
        Design d = new Design("t27pipe");
        Type T = Type.trits(W), B = Type.BIT;
        Sig rst = d.resetInput();
        Sig ldWe = d.input("ld_we", B), ldRow = d.input("ld_row", Type.trits(k)), ldData = d.input("ld_data", T);
        Sig peekRow = d.input("peek_row", Type.trits(k));
        Sig zero = Hw.k(d, 0, W), t = d.bit(true), f = d.bit(false);

        Design.Mem imem = d.memory("imem", T, k);
        imem.write(ldWe, ldRow, ldData);
        Design.Mem[] bank = new Design.Mem[3];
        for (int b = 0; b < 3; b++) bank[b] = d.memory("dmem" + "NOP".charAt(b), Type.trits(9), k);

        // ---------------- state declared up front (pipeline registers are created by preg below)
        Sig pc = d.reg("pc", T);
        Sig halted = d.reg("halted", B), fetchStop = d.reg("fetch_stop", B);
        Sig[] regs = new Sig[27];
        for (int r = -13; r <= 13; r++) regs[r + 13] = r == 0 ? zero : d.reg("x" + r, T);
        // multiplier state
        Sig busy = d.reg("mul_busy", B), cnt = d.reg("mul_cnt", Type.trits(4));
        Sig acc = d.reg("mul_acc", Type.trits(2 * W)), mc = d.reg("mul_mc", Type.trits(2 * W)), mp = d.reg("mul_mp", T);

        // registers whose next values depend on later stages are declared now and assigned at the end
        Sig idValid = d.reg("id_valid", B), idPc = d.reg("id_pc", T), idInsn = d.reg("id_insn", T);
        Sig exValid = d.reg("ex_valid", B), exPc = d.reg("ex_pc", T), exInsn = d.reg("ex_insn", T);
        Sig exA = d.reg("ex_a", T), exB = d.reg("ex_b", T), exS = d.reg("ex_s", T);
        Sig memValid = d.reg("mem_valid", B), memPc = d.reg("mem_pc", T), memInsn = d.reg("mem_insn", T);
        Sig memRes = d.reg("mem_res", T), memS = d.reg("mem_s", T);
        Sig wbValid = d.reg("wb_valid", B), wbPc = d.reg("wb_pc", T), wbRd = d.reg("wb_rd", Type.trits(3)), wbWe = d.reg("wb_we", B), wbWd = d.reg("wb_wd", T);
        Sig wbMw = d.reg("wb_mw", B), wbMaddr = d.reg("wb_maddr", T), wbMdata = d.reg("wb_mdata", T), wbHalt = d.reg("wb_halt", B);

        Sig running = rst.not().and(halted.not());

        // ---------------- WB
        Sig wbWrite = wbValid.and(wbWe).and(running);
        for (int r = -13; r <= 13; r++) {
            if (r == 0) continue;
            regs[r + 13].next(d.mux(wbWrite.and(Hw.is(d, wbRd, r)), wbWd, regs[r + 13]));
        }
        halted.next(d.mux(rst, f, halted.or(wbValid.and(wbHalt))));

        // ---------------- MEM
        Dec md = new Dec(d, memInsn);
        Sig mRow = memRes.shr(1).slice(k - 1, 0), mSel = memRes.digit(0);
        Sig tN = bank[0].read(mRow), tO = bank[1].read(mRow), tP = bank[2].read(mRow);
        Sig loadWord = tP.concat(tO, tN), loadTryte = Hw.widen(d, mSel.mux3(tN, tO, tP), W);
        Sig memWd = d.mux(md.lw, loadWord, d.mux(md.lt, loadTryte, memRes));
        Sig memLive = memValid.and(running);
        Sig sw = memLive.and(md.sw), st = memLive.and(md.st);
        for (int bi = 0; bi < 3; bi++) {
            Sig hit = Hw.is(d, mSel, bi - 1);
            // one write port per bank (loader has priority) so FPGA tools can map the bank to distributed RAM
            Sig cpuWe = sw.or(st.and(hit));
            bank[bi].write(ldWe.or(cpuWe), d.mux(ldWe, ldRow, mRow), d.mux(ldWe, ldData.slice(9 * bi + 8, 9 * bi), d.mux(sw, memS.slice(9 * bi + 8, 9 * bi), memS.slice(8, 0))));
        }

        // ---------------- EX
        Dec ed = new Dec(d, exInsn);
        Sig mWrites = memValid.and(md.writes), wWrites = wbValid.and(wbWe);
        java.util.function.BiFunction<Sig, Sig, Sig> fwd = (idx, v) -> d.mux(mWrites.and(idx.cmp(md.rd).isZero()), memWd,
            d.mux(wWrites.and(idx.cmp(wbRd).isZero()), wbWd, v));
        // B format: compare (rd field operand) - (ra field operand); S format store source = rd field
        Sig a = fwd.apply(ed.ra, exA), b = fwd.apply(ed.rb, exB), s = fwd.apply(ed.rd, exS);
        Sig cntZero = Hw.is(d, cnt, 0);
        Sig mulDone = busy.and(cntZero);
        Sig exLive = exValid.and(running);
        Sig exMul = exLive.and(ed.isMul);
        Sig stall = exMul.and(mulDone.not());
        Sig mulStart = exMul.and(busy.not());
        Sig iter = busy.and(cntZero.not());
        Sig pp = mp.digit(0).mux3(mc.neg(), Hw.k(d, 0, 2 * W), mc);
        acc.next(d.mux(mulStart, Hw.k(d, 0, 2 * W), d.mux(iter, acc.add(pp), acc)));
        mc.next(d.mux(mulStart, Hw.widen(d, a, 2 * W), d.mux(iter, mc.shl(1), mc)));
        mp.next(d.mux(mulStart, b, d.mux(iter, mp.shr(1), mp)));
        cnt.next(d.mux(mulStart, Hw.k(d, 27, 4), d.mux(iter, cnt.add(Hw.k(d, -1, 4)), cnt)));
        busy.next(d.mux(rst, f, d.mux(mulStart, t, d.mux(mulDone, f, busy))));

        Sig[] fv = new Sig[27];
        Arrays.fill(fv, zero);
        fv[13] = a.add(b); fv[14] = a.add(b.neg()); fv[15] = acc.slice(W - 1, 0); fv[16] = acc.slice(2 * W - 1, W);
        fv[17] = a.min(b); fv[18] = a.max(b); fv[19] = a.any(b); fv[20] = a.cons(b); fv[21] = a.sum(b);
        fv[22] = Hw.widen(d, a.cmp(b), W); fv[23] = Hw.shift(a, b, true); fv[24] = Hw.shift(a, b, false); fv[25] = a.neg();
        fv[12] = a.pti(); fv[11] = a.nti(); fv[10] = a.ici(); fv[9] = a.dci(); fv[8] = a.isZeroDigit(); fv[7] = Hw.widen(d, a.cmp(zero), W);
        Sig aluOut = Hw.select(ed.fn, fv);
        Sig addr = a.add(ed.imm);
        Sig exPc3 = exPc.add(Hw.k(d, 3, W));
        Sig exRes = d.mux(ed.alu, aluOut, d.mux(ed.lui, ed.imm.shl(9), d.mux(ed.shli, Hw.shift(a, ed.imm, true),
                    d.mux(ed.shri, Hw.shift(a, ed.imm, false), d.mux(ed.jal.or(ed.jalr), exPc3,
                    d.mux(ed.cmpi, Hw.widen(d, a.cmp(ed.imm), W), addr))))));   // addi / loads / stores / jalr use addr
        Sig take = s.cmp(a).mux3(exInsn.digit(9), exInsn.digit(10), exInsn.digit(11)).isPos().and(ed.br);
        Sig redirect = exLive.and(stall.not()).and(take.or(ed.jal).or(ed.jalr));
        Sig target = d.mux(take, exPc.add(ed.boff.shl(1)), d.mux(ed.jal, exPc.add(ed.joff.shl(1)), addr.slice(W - 1, 1).concat(d.trits("0"))));   // ISA v0.3

        // ---------------- ID
        Dec id = new Dec(d, idInsn);
        java.util.function.Function<Sig, Sig> rd3 = idx -> {
            Sig v = Hw.select(idx, regs);
            return d.mux(wbWrite.and(idx.cmp(wbRd).isZero()), wbWd, v);   // WB -> ID bypass
        };
        Sig idA = rd3.apply(id.ra), idB = rd3.apply(id.rb), idS = rd3.apply(id.rd);
        Sig idHalt = idValid.and(id.halt).and(redirect.not());

        // ---------------- pipeline register updates
        Sig flush = rst.or(redirect);
        Sig adv = running.and(stall.not());           // front of the pipe advances
        // MEM/WB always advances (stall bubbles into MEM)
        Sig wbRst = rst;
        Sig memToWb = memLive;
        wbValid.next(d.mux(wbRst, f, d.mux(running, memToWb, wbValid)));
        wbPc.next(d.mux(running, memPc, wbPc));
        wbRd.next(d.mux(running, md.rd, wbRd));
        wbWe.next(d.mux(running, md.writes, wbWe));
        wbWd.next(d.mux(running, memWd, wbWd));
        wbMw.next(d.mux(running, md.sw.or(md.st), wbMw));
        wbMaddr.next(d.mux(running, d.mux(md.sw, memRes.slice(W - 1, 1).concat(d.trits("0")), memRes), wbMaddr));
        wbMdata.next(d.mux(running, d.mux(md.sw, memS, Hw.widen(d, memS.slice(8, 0), W)), wbMdata));
        wbHalt.next(d.mux(running, md.halt, wbHalt));
        // EX/MEM: bubble when stalled
        memValid.next(d.mux(rst, f, d.mux(running, exLive.and(stall.not()), memValid)));
        memPc.next(d.mux(running, exPc, memPc));
        memInsn.next(d.mux(running, exInsn, memInsn));
        memRes.next(d.mux(running, exRes, memRes));
        memS.next(d.mux(running, s, memS));
        // ID/EX
        exValid.next(d.mux(flush, f, d.mux(adv, idValid, exValid)));
        exPc.next(d.mux(adv, idPc, exPc));
        exInsn.next(d.mux(adv, idInsn, exInsn));
        exA.next(d.mux(adv, idA, d.mux(stall, a, exA)));       // keep forwarded operands while stalled
        exB.next(d.mux(adv, idB, d.mux(stall, b, exB)));
        exS.next(d.mux(adv, idS, d.mux(stall, s, exS)));
        // IF/ID
        Sig fetch = adv.and(fetchStop.not());
        idValid.next(d.mux(flush, f, d.mux(adv, fetch.and(idHalt.not()), idValid)));
        idPc.next(d.mux(adv, pc, idPc));
        idInsn.next(d.mux(adv, imem.read(pc.shr(1).slice(k - 1, 0)), idInsn));
        fetchStop.next(d.mux(rst, f, d.mux(redirect, f, fetchStop.or(idHalt.and(adv)))));
        pc.next(d.mux(rst, zero, d.mux(redirect, target, d.mux(fetch.and(idHalt.not()), pc.add(Hw.k(d, 3, W)), pc))));

        // ---------------- outputs (retirement in WB)
        Sig retire = wbValid.and(running);
        d.output("retire", retire);
        d.output("r_pc", wbPc);
        d.output("rf_we", retire.and(wbWe));
        d.output("rf_rd", wbRd);
        d.output("rf_wd", wbWd);
        d.output("mw", retire.and(wbMw));
        d.output("mw_addr", wbMaddr);
        d.output("mw_data", wbMdata);
        d.output("halted", halted);
        Sig hPrev = d.reg("h_prev", B);
        hPrev.next(halted);
        d.assertAlways("pc_aligned", pc.digit(0).isZero());
        d.assertAlways("retire_pc_aligned", retire.not().or(wbPc.digit(0).isZero()));
        d.assertAlways("halted_sticky", hPrev.and(halted.not()).not());
        d.output("peek_word", bank[2].read(peekRow).concat(bank[1].read(peekRow), bank[0].read(peekRow)));
        return d;
    }

    /** Same memory layout as T27Core (memories 0..3). */
    public static void load(Design d, org.ternhdl.isa.Asm.Program p) { T27Core.load(d, p); }
}
