package org.ternhdl.cpu;

import java.util.*;
import org.ternhdl.core.*;

/**
 * Single-cycle T27 core (one instruction per cycle) with a multi-cycle radix-3 multiplier (MUL/MULH stall the PC for
 * 28 extra cycles). Instruction memory: 3^k words; data memory: three tryte banks (selected by the lowest address
 * trit) of 3^k rows, row = address >> 1 trit (centered words). A loader port writes a word into IMEM and DMEM rows.
 * Outputs expose the retiring instruction's effects for lockstep comparison with the ISS.
 */
public final class T27Core {
    public static final int W = 27;

    private T27Core() { }

    /** Preloads an assembled program into IMEM and the three DMEM banks (memories 0..3 of a built core). */
    public static void load(Design d, org.ternhdl.isa.Asm.Program p) {
        for (var e : p.words().entrySet()) {
            long a = e.getKey();
            if (Math.floorMod(a, 3) != 0) throw new IllegalArgumentException("word at unaligned address " + a);
            long row = a / 3;
            int[] t = org.ternhdl.isa.T27.trits(e.getValue(), W);
            Logic3[] w = new Logic3[W];
            for (int i = 0; i < W; i++) w[i] = Logic3.ofBal(t[i]);
            d.mems().get(0).init(row, w);
            for (int bi = 0; bi < 3; bi++) d.mems().get(1 + bi).init(row, Arrays.copyOfRange(w, 9 * bi, 9 * bi + 9));
        }
    }

    public static Design build(int k) {
        Design d = new Design("t27sc");
        Type T = Type.trits(W);
        Sig rst = d.resetInput();
        Sig ldWe = d.input("ld_we", Type.BIT), ldRow = d.input("ld_row", Type.trits(k)), ldData = d.input("ld_data", T);
        Sig dbgRow = d.input("peek_row", Type.trits(k));
        Sig zero = Hw.k(d, 0, W);

        Sig pc = d.reg("pc", T), halted = d.reg("halted", Type.BIT);
        Design.Mem imem = d.memory("imem", T, k);
        imem.write(ldWe, ldRow, ldData);
        Design.Mem[] bank = new Design.Mem[3];
        for (int b = 0; b < 3; b++) bank[b] = d.memory("dmem" + "NOP".charAt(b), Type.trits(9), k);

        Sig insn = imem.read(pc.shr(1).slice(k - 1, 0));
        Sig op = insn.slice(2, 0), rd = insn.slice(5, 3), ra = insn.slice(8, 6), rb = insn.slice(11, 9), fn = insn.slice(14, 12);
        Sig imm = Hw.widen(d, insn.slice(26, 9), W), boff = Hw.widen(d, insn.slice(26, 12), W), joff = Hw.widen(d, insn.slice(26, 6), W);

        // register file: x0 is the constant 0
        Sig[] regs = new Sig[27];
        for (int r = -13; r <= 13; r++) regs[r + 13] = r == 0 ? zero : d.reg("x" + r, T);
        Sig a = Hw.select(ra, regs), b = Hw.select(rb, regs), s = Hw.select(rd, regs);   // s: store source / branch operand

        Sig[] isOp = new Sig[27];
        for (int o = -13; o <= 13; o++) isOp[o + 13] = Hw.is(d, op, o);
        Sig opSys = isOp[13], opAlu = isOp[14], opAddi = isOp[15], opLui = isOp[16], opShli = isOp[17], opShri = isOp[18];
        Sig opLw = isOp[19], opLt = isOp[20], opSw = isOp[21], opSt = isOp[22], opBr = isOp[23], opJal = isOp[24], opJalr = isOp[25], opCmpi = isOp[26];

        // multi-cycle multiplier
        Sig fnMul = Hw.is(d, fn, 2), fnMulh = Hw.is(d, fn, 3);
        Sig isMul = opAlu.and(fnMul.or(fnMulh));
        Sig busy = d.reg("mul_busy", Type.BIT), cnt = d.reg("mul_cnt", Type.trits(4));
        Sig acc = d.reg("mul_acc", Type.trits(2 * W)), mc = d.reg("mul_mc", Type.trits(2 * W)), mp = d.reg("mul_mp", T);
        Sig cntZero = Hw.is(d, cnt, 0);
        Sig mulDone = busy.and(cntZero);
        Sig running = rst.not().and(halted.not());   // gate with rst: during reset the registers are still U and would poison memory
        Sig stall = isMul.and(mulDone.not());
        Sig retire = running.and(stall.not());
        Sig mulStart = isMul.and(busy.not()).and(running);
        Sig pp = mp.digit(0).mux3(mc.neg(), Hw.k(d, 0, 2 * W), mc);
        Sig iter = busy.and(cntZero.not());
        acc.next(d.mux(mulStart, Hw.k(d, 0, 2 * W), d.mux(iter, acc.add(pp), acc)));
        mc.next(d.mux(mulStart, Hw.widen(d, a, 2 * W), d.mux(iter, mc.shl(1), mc)));
        mp.next(d.mux(mulStart, b, d.mux(iter, mp.shr(1), mp)));
        cnt.next(d.mux(mulStart, Hw.k(d, 27, 4), d.mux(iter, cnt.add(Hw.k(d, -1, 4)), cnt)));
        busy.next(d.mux(mulStart, d.bit(true), d.mux(mulDone, d.bit(false), busy)));

        // ALU
        Sig[] f = new Sig[27];
        Arrays.fill(f, zero);
        f[13 + 0] = a.add(b);
        f[13 + 1] = a.add(b.neg());
        f[13 + 2] = acc.slice(W - 1, 0);
        f[13 + 3] = acc.slice(2 * W - 1, W);
        f[13 + 4] = a.min(b); f[13 + 5] = a.max(b); f[13 + 6] = a.any(b); f[13 + 7] = a.cons(b); f[13 + 8] = a.sum(b);
        f[13 + 9] = Hw.widen(d, a.cmp(b), W);
        f[13 + 10] = Hw.shift(a, b, true); f[13 + 11] = Hw.shift(a, b, false);
        f[13 + 12] = a.neg();
        f[13 - 1] = a.pti(); f[13 - 2] = a.nti(); f[13 - 3] = a.ici(); f[13 - 4] = a.dci(); f[13 - 5] = a.isZeroDigit();
        f[13 - 6] = Hw.widen(d, a.cmp(zero), W);
        Sig alu = Hw.select(fn, f);

        // memory
        Sig addr = a.add(imm);
        Sig row = addr.shr(1).slice(k - 1, 0), bsel = addr.digit(0);
        Sig tN = bank[0].read(row), tO = bank[1].read(row), tP = bank[2].read(row);
        Sig loadWord = tP.concat(tO, tN);
        Sig loadTryte = Hw.widen(d, bsel.mux3(tN, tO, tP), W);
        Sig storeOk = retire;
        Sig sw = storeOk.and(opSw), st = storeOk.and(opSt);
        for (int bi = 0; bi < 3; bi++) {
            Sig hit = Hw.is(d, bsel, bi - 1);
            Sig wordPart = s.slice(9 * bi + 8, 9 * bi);
            // one write port per bank (loader has priority) so FPGA tools can map the bank to distributed RAM
            Sig cpuWe = sw.or(st.and(hit));
            bank[bi].write(ldWe.or(cpuWe), d.mux(ldWe, ldRow, row), d.mux(ldWe, ldData.slice(9 * bi + 8, 9 * bi), d.mux(sw, wordPart, s.slice(8, 0))));
        }

        // branch / jump
        Sig pc3 = pc.add(Hw.k(d, 3, W));
        Sig sign = s.cmp(a);                                   // B format: ra in trits 3-5 (rd field), rb in trits 6-8 (ra field)
        Sig take = sign.mux3(insn.digit(9), insn.digit(10), insn.digit(11)).isPos().and(opBr);
        Sig brTarget = pc.add(boff.shl(1)), jTarget = pc.add(joff.shl(1)), jrTarget = addr.slice(W - 1, 1).concat(d.trits("0"));   // ISA v0.3: lowest trit cleared

        // writeback
        Sig wd = d.mux(opAlu, alu,
                 d.mux(opAddi, addr,
                 d.mux(opLui, imm.shl(9),
                 d.mux(opShli, Hw.shift(a, imm, true),
                 d.mux(opShri, Hw.shift(a, imm, false),
                 d.mux(opLw, loadWord,
                 d.mux(opLt, loadTryte,
                 d.mux(opJal.or(opJalr), pc3,
                 d.mux(opCmpi, Hw.widen(d, a.cmp(imm), W), zero)))))))));
        Sig writes = opAlu.or(opAddi).or(opLui).or(opShli).or(opShri).or(opLw).or(opLt).or(opJal).or(opJalr).or(opCmpi);
        Sig rfWe = retire.and(writes).and(Hw.is(d, rd, 0).not());
        for (int r = -13; r <= 13; r++) {
            if (r == 0) continue;
            Sig reg = regs[r + 13];
            reg.next(d.mux(rfWe.and(Hw.is(d, rd, r)), wd, reg));
        }
        Sig halt = retire.and(opSys).and(Hw.is(d, fn, 0));
        Sig nextPc = d.mux(retire.not().or(halt), pc,
                     d.mux(take, brTarget,
                     d.mux(opJal, jTarget,
                     d.mux(opJalr, jrTarget, pc3))));
        pc.next(nextPc);
        halted.next(halted.or(halt));

        d.output("retire", retire);
        d.output("r_pc", pc);
        d.output("rf_we", rfWe);
        d.output("rf_rd", rd);
        d.output("rf_wd", wd);
        d.output("mw", sw.or(st));
        d.output("mw_addr", d.mux(sw, addr.slice(W - 1, 1).concat(d.trits("0")), addr));   // word stores ignore the lowest address trit
        d.output("mw_data", d.mux(sw, s, Hw.widen(d, s.slice(8, 0), W)));
        d.output("halted", halted);
        // invariants (checked in simulation, provable formally)
        Sig hPrev = d.reg("h_prev", Type.BIT);
        hPrev.next(halted);
        d.assertAlways("pc_aligned", pc.digit(0).isZero());
        d.assertAlways("halted_sticky", hPrev.and(halted.not()).not());
        d.output("peek_word", bank[2].read(dbgRow).concat(bank[1].read(dbgRow), bank[0].read(dbgRow)));
        return d;
    }
}
