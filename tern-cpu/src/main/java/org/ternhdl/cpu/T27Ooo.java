package org.ternhdl.cpu;

import java.util.*;
import org.ternhdl.core.*;

/**
 * Out-of-order T27 core (Tomasulo with a reorder buffer). One instruction fetched and dispatched per cycle into a
 * 9-entry ROB (tags are 2-trit balanced values) and a reservation station: 3 ALU stations (ALU ops, immediates, address
 * and branch computation) and 1 multiplier station (radix-3 multi-cycle unit, CDB priority). A register status table
 * maps registers to producing ROB tags. Results broadcast on one CDB; loads read memory when they reach the ROB head and
 * broadcast their value on a commit bus. In-order commit, one per cycle: register write, store, or (taken branch/jump)
 * redirect + flush of all speculative state. Lockstep outputs report each commit.
 */
public final class T27Ooo {
    static final int W = 27, NROB = 9, NALU = 3;

    private T27Ooo() { }

    record Op(Sig v, Sig rdy, Sig q) { }

    public static Design build(int k) {
        Design d = new Design("t27ooo");
        Type T = Type.trits(W), B = Type.BIT, TG = Type.trits(2);
        Sig rst = d.resetInput();
        Sig ldWe = d.input("ld_we", B), ldRow = d.input("ld_row", Type.trits(k)), ldData = d.input("ld_data", T);
        Sig peekRow = d.input("peek_row", Type.trits(k));
        Sig zero = Hw.k(d, 0, W), tr = d.bit(true), fa = d.bit(false);

        Design.Mem imem = d.memory("imem", T, k);
        imem.write(ldWe, ldRow, ldData);
        Design.Mem[] bank = new Design.Mem[3];
        for (int b = 0; b < 3; b++) bank[b] = d.memory("dmem" + "NOP".charAt(b), Type.trits(9), k);

        Sig pc = d.reg("pc", T), halted = d.reg("halted", B), fetchStop = d.reg("fetch_stop", B);
        Sig fValid = d.reg("f_valid", B), fPc = d.reg("f_pc", T), fInsn = d.reg("f_insn", T), fPred = d.reg("f_pred", T);
        Sig[] regs = new Sig[27];
        for (int r = -13; r <= 13; r++) regs[r + 13] = r == 0 ? zero : d.reg("x" + r, T);
        Sig[] sBusy = new Sig[27], sTag = new Sig[27];
        for (int r = -13; r <= 13; r++) if (r != 0) { sBusy[r + 13] = d.reg("st_busy" + r, B); sTag[r + 13] = d.reg("st_tag" + r, TG); }
        Sig head = d.reg("rob_head", TG), tail = d.reg("rob_tail", TG), count = d.reg("rob_count", Type.trits(3));
        Sig[] rV = new Sig[NROB], rRdy = new Sig[NROB], rPc = new Sig[NROB], rInsn = new Sig[NROB], rVal = new Sig[NROB], rVal2 = new Sig[NROB], rTk = new Sig[NROB], rPred = new Sig[NROB];
        for (int i = 0; i < NROB; i++) {
            rV[i] = d.reg("rob" + i + "_v", B); rRdy[i] = d.reg("rob" + i + "_rdy", B); rPc[i] = d.reg("rob" + i + "_pc", T);
            rInsn[i] = d.reg("rob" + i + "_insn", T); rVal[i] = d.reg("rob" + i + "_val", T); rVal2[i] = d.reg("rob" + i + "_val2", T); rTk[i] = d.reg("rob" + i + "_tk", B); rPred[i] = d.reg("rob" + i + "_pred", T);
        }
        Sig[] aBusy = new Sig[NALU], aInsn = new Sig[NALU], aPc = new Sig[NALU], aTag = new Sig[NALU];
        Sig[][] aV = new Sig[NALU][3], aR = new Sig[NALU][3], aQ = new Sig[NALU][3];
        for (int i = 0; i < NALU; i++) {
            aBusy[i] = d.reg("rs" + i + "_busy", B); aInsn[i] = d.reg("rs" + i + "_insn", T); aPc[i] = d.reg("rs" + i + "_pc", T); aTag[i] = d.reg("rs" + i + "_tag", TG);
            for (int o = 0; o < 3; o++) { aV[i][o] = d.reg("rs" + i + "_v" + o, T); aR[i][o] = d.reg("rs" + i + "_r" + o, B); aQ[i][o] = d.reg("rs" + i + "_q" + o, TG); }
        }
        Sig mBusy = d.reg("mrs_busy", B), mHigh = d.reg("mrs_high", B), mTag = d.reg("mrs_tag", TG), mRun = d.reg("mul_run", B);
        Sig[] mV = new Sig[2], mR = new Sig[2], mQ = new Sig[2];
        for (int o = 0; o < 2; o++) { mV[o] = d.reg("mrs_v" + o, T); mR[o] = d.reg("mrs_r" + o, B); mQ[o] = d.reg("mrs_q" + o, TG); }
        Sig cnt = d.reg("mul_cnt", Type.trits(4)), acc = d.reg("mul_acc", Type.trits(2 * W)), mc = d.reg("mul_mc", Type.trits(2 * W)), mp = d.reg("mul_mp", T);

        Sig running = rst.not().and(halted.not());

        java.util.function.Function<Sig, Sig[]> tagHit = tag -> {
            Sig[] h = new Sig[NROB];
            for (int i = 0; i < NROB; i++) h[i] = Hw.is(d, tag, i - 4);
            return h;
        };

        // ---------------- commit
        Sig hV = Hw.select(head, rV), hRdy = Hw.select(head, rRdy), hPc = Hw.select(head, rPc), hInsn = Hw.select(head, rInsn);
        Sig hVal = Hw.select(head, rVal), hVal2 = Hw.select(head, rVal2), hTk = Hw.select(head, rTk);
        T27Pipe.Dec hd = new T27Pipe.Dec(d, hInsn);
        Sig commit = running.and(hV).and(hRdy);
        Sig row = hVal.shr(1).slice(k - 1, 0), bsel = hVal.digit(0);
        Sig tN = bank[0].read(row), tO = bank[1].read(row), tP = bank[2].read(row);
        Sig loadWord = tP.concat(tO, tN), loadTryte = Hw.widen(d, bsel.mux3(tN, tO, tP), W);
        Sig cWd = d.mux(hd.lw, loadWord, d.mux(hd.lt, loadTryte, hVal));
        Sig cSw = commit.and(hd.sw), cSt = commit.and(hd.st);
        for (int bi = 0; bi < 3; bi++) {
            Sig cpuWe = cSw.or(cSt.and(Hw.is(d, bsel, bi - 1)));
            bank[bi].write(ldWe.or(cpuWe), d.mux(ldWe, ldRow, row), d.mux(ldWe, ldData.slice(9 * bi + 8, 9 * bi), d.mux(cSw, hVal2.slice(9 * bi + 8, 9 * bi), hVal2.slice(8, 0))));
        }
        Sig cRegWe = commit.and(hd.writes);
        Sig hPred = Hw.select(head, rPred);
        Sig actualNext = d.mux(hTk, hVal2, hPc.add(Hw.k(d, 3, W)));
        Sig cRedirect = commit.and(actualNext.cmp(hPred).isZero().not());   // flush only on misprediction
        Sig cHalt = commit.and(hd.halt);
        Sig flush = rst.or(cRedirect);
        Sig cbValid = commit.and(hd.lw.or(hd.lt));

        // ---------------- multiplier
        Sig cntZero = Hw.is(d, cnt, 0);
        Sig mulDone = mRun.and(cntZero);
        Sig mulReady = mBusy.and(mR[0]).and(mR[1]).and(mRun.not());
        Sig mulStart = running.and(mulReady).and(flush.not());
        Sig iter = mRun.and(cntZero.not());
        Sig pp = mp.digit(0).mux3(mc.neg(), Hw.k(d, 0, 2 * W), mc);
        acc.next(d.mux(mulStart, Hw.k(d, 0, 2 * W), d.mux(iter, acc.add(pp), acc)));
        mc.next(d.mux(mulStart, Hw.widen(d, mV[0], 2 * W), d.mux(iter, mc.shl(1), mc)));
        mp.next(d.mux(mulStart, mV[1], d.mux(iter, mp.shr(1), mp)));
        cnt.next(d.mux(mulStart, Hw.k(d, 27, 4), d.mux(iter, cnt.add(Hw.k(d, -1, 4)), cnt)));

        // ---------------- ALU (lowest-index ready station)
        Sig[] aReady = new Sig[NALU];
        for (int i = 0; i < NALU; i++) aReady[i] = aBusy[i].and(aR[i][0]).and(aR[i][1]).and(aR[i][2]);
        Sig[] aPick = new Sig[NALU];
        Sig taken = fa;
        for (int i = 0; i < NALU; i++) { aPick[i] = aReady[i].and(taken.not()); taken = taken.or(aReady[i]); }
        Sig anyAlu = taken;
        java.util.function.Function<Sig[], Sig> pickT = arr -> d.mux(aPick[0], arr[0], d.mux(aPick[1], arr[1], arr[2]));
        Sig eInsn = pickT.apply(aInsn), ePc = pickT.apply(aPc), eTag = pickT.apply(aTag);
        Sig ea = pickT.apply(new Sig[]{aV[0][0], aV[1][0], aV[2][0]}), eb = pickT.apply(new Sig[]{aV[0][1], aV[1][1], aV[2][1]}), es = pickT.apply(new Sig[]{aV[0][2], aV[1][2], aV[2][2]});
        T27Pipe.Dec ed = new T27Pipe.Dec(d, eInsn);
        Sig[] fv = new Sig[27];
        Arrays.fill(fv, zero);
        fv[13] = ea.add(eb); fv[14] = ea.add(eb.neg());
        fv[17] = ea.min(eb); fv[18] = ea.max(eb); fv[19] = ea.any(eb); fv[20] = ea.cons(eb); fv[21] = ea.sum(eb);
        fv[22] = Hw.widen(d, ea.cmp(eb), W); fv[23] = Hw.shift(ea, eb, true); fv[24] = Hw.shift(ea, eb, false); fv[25] = ea.neg();
        fv[12] = ea.pti(); fv[11] = ea.nti(); fv[10] = ea.ici(); fv[9] = ea.dci(); fv[8] = ea.isZeroDigit(); fv[7] = Hw.widen(d, ea.cmp(zero), W);
        Sig aluOut = Hw.select(ed.fn, fv);
        Sig addr = ea.add(ed.imm);
        Sig ePc3 = ePc.add(Hw.k(d, 3, W));
        Sig eVal = d.mux(ed.alu, aluOut, d.mux(ed.lui, ed.imm.shl(9), d.mux(ed.shli, Hw.shift(ea, ed.imm, true),
                   d.mux(ed.shri, Hw.shift(ea, ed.imm, false), d.mux(ed.jal.or(ed.jalr), ePc3,
                   d.mux(ed.cmpi, Hw.widen(d, ea.cmp(ed.imm), W), addr))))));
        Sig brTk = es.cmp(ea).mux3(eInsn.digit(9), eInsn.digit(10), eInsn.digit(11)).isPos().and(ed.br);
        Sig eTk = brTk.or(ed.jal).or(ed.jalr);
        Sig eTarget = d.mux(brTk, ePc.add(ed.boff.shl(1)), d.mux(ed.jal, ePc.add(ed.joff.shl(1)), addr.slice(W - 1, 1).concat(d.trits("0"))));
        Sig eVal2 = d.mux(ed.sw.or(ed.st), es, eTarget);

        // ---------------- CDB (multiplier first)
        Sig cdbV = running.and(flush.not()).and(mulDone.or(anyAlu));
        Sig cdbFromMul = mulDone;
        Sig cdbTag = d.mux(cdbFromMul, mTag, eTag);
        Sig cdbVal = d.mux(cdbFromMul, d.mux(mHigh, acc.slice(2 * W - 1, W), acc.slice(W - 1, 0)), eVal);
        Sig cdbVal2 = d.mux(cdbFromMul, zero, eVal2), cdbTk = d.mux(cdbFromMul, fa, eTk);
        Sig aluGranted = cdbV.and(cdbFromMul.not());
        // a load's ALU broadcast carries its address, not its value: it completes the ROB entry but must not wake
        // dependents (they take the loaded value from the commit bus)
        Sig cdbIsLoad = cdbFromMul.not().and(ed.lw.or(ed.lt));
        Sig cdbWake = cdbV.and(cdbIsLoad.not());
        mRun.next(d.mux(flush, fa, d.mux(mulStart, tr, d.mux(mulDone, fa, mRun))));
        java.util.function.BiFunction<Sig, Sig, Sig> cdbHit = (q, rdy) -> rdy.not().and(cdbWake.and(cdbTag.cmp(q).isZero()));
        java.util.function.BiFunction<Sig, Sig, Sig> cbHit = (q, rdy) -> rdy.not().and(cbValid.and(head.cmp(q).isZero()));

        // ---------------- dispatch
        T27Pipe.Dec dd = new T27Pipe.Dec(d, fInsn);
        Sig needMul = dd.isMul;
        Sig needAlu = dd.sys.not().and(needMul.not());
        Sig[] aFree = new Sig[NALU];
        Sig chosen = fa;
        for (int i = 0; i < NALU; i++) { aFree[i] = aBusy[i].not().and(chosen.not()); chosen = chosen.or(aBusy[i].not()); }
        Sig aFreeAny = chosen;
        Sig robFull = Hw.is(d, count, NROB);
        Sig dispatch = running.and(flush.not()).and(fValid).and(robFull.not())
            .and(needAlu.not().or(aFreeAny)).and(needMul.not().or(mBusy.not()));
        Sig[] isLd = new Sig[NROB];
        for (int i = 0; i < NROB; i++) { T27Pipe.Dec x = new T27Pipe.Dec(d, rInsn[i]); isLd[i] = x.lw.or(x.lt); }
        Sig[] bz = new Sig[27], tg = new Sig[27];
        for (int r = 0; r < 27; r++) { bz[r] = r == 13 ? fa : sBusy[r]; tg[r] = r == 13 ? Hw.k(d, 0, 2) : sTag[r]; }
        java.util.function.Function<Sig, Op> readOp = idx -> {
            Sig regVal = Hw.select(idx, regs);
            Sig busy = Hw.select(idx, bz), tag = Hw.select(idx, tg);
            Sig robRdy = Hw.select(tag, rRdy), robVal = Hw.select(tag, rVal), robIsLoad = Hw.select(tag, isLd);
            Sig fromRob = busy.and(robRdy).and(robIsLoad.not());
            Sig fromCdb = busy.and(cdbWake).and(cdbTag.cmp(tag).isZero());
            Sig fromCb = busy.and(cbValid).and(head.cmp(tag).isZero());
            Sig v = d.mux(busy.not(), regVal, d.mux(fromRob, robVal, d.mux(fromCdb, cdbVal, d.mux(fromCb, cWd, zero))));
            Sig rdy = busy.not().or(fromRob).or(fromCdb).or(fromCb);
            return new Op(v, rdy, tag);
        };
        Op opA = readOp.apply(dd.ra), opB = readOp.apply(dd.rb), opS = readOp.apply(dd.rd);
        Sig[] tailHit = tagHit.apply(tail);
        Sig dAlu = dispatch.and(needAlu), dMul = dispatch.and(needMul);

        // ---------------- ROB
        Sig[] headHit = tagHit.apply(head), cdbHitRob = tagHit.apply(cdbTag);
        for (int i = 0; i < NROB; i++) {
            Sig alloc = dispatch.and(tailHit[i]);
            Sig done = cdbV.and(cdbHitRob[i]);
            Sig free = commit.and(headHit[i]);
            rV[i].next(d.mux(flush, fa, d.mux(alloc, tr, d.mux(free, fa, rV[i]))));
            rRdy[i].next(d.mux(flush, fa, d.mux(alloc, dd.sys, d.mux(done, tr, rRdy[i]))));
            rPc[i].next(d.mux(alloc, fPc, rPc[i]));
            rInsn[i].next(d.mux(alloc, fInsn, rInsn[i]));
            rVal[i].next(d.mux(done, cdbVal, rVal[i]));
            rVal2[i].next(d.mux(done, cdbVal2, rVal2[i]));
            rTk[i].next(d.mux(flush.or(alloc), fa, d.mux(done, cdbTk, rTk[i])));
            rPred[i].next(d.mux(alloc, fPred, rPred[i]));
        }
        Sig one2 = Hw.k(d, 1, 2);
        head.next(d.mux(flush, Hw.k(d, 0, 2), d.mux(commit, head.add(one2), head)));
        tail.next(d.mux(flush, Hw.k(d, 0, 2), d.mux(dispatch, tail.add(one2), tail)));
        Sig inc = d.mux(dispatch, Hw.k(d, 1, 3), Hw.k(d, 0, 3)), dec = d.mux(commit, Hw.k(d, -1, 3), Hw.k(d, 0, 3));
        count.next(d.mux(flush, Hw.k(d, 0, 3), count.add(inc).add(dec)));

        // ---------------- register file + status
        for (int r = -13; r <= 13; r++) {
            if (r == 0) continue;
            Sig isR = Hw.is(d, hd.rd, r);
            regs[r + 13].next(d.mux(cRegWe.and(isR), cWd, regs[r + 13]));
            Sig setD = dispatch.and(dd.writes).and(Hw.is(d, dd.rd, r));
            Sig clrC = cRegWe.and(isR).and(sTag[r + 13].cmp(head).isZero());
            sBusy[r + 13].next(d.mux(flush, fa, d.mux(setD, tr, d.mux(clrC, fa, sBusy[r + 13]))));
            sTag[r + 13].next(d.mux(setD, tail, sTag[r + 13]));
        }

        // ---------------- stations
        for (int i = 0; i < NALU; i++) {
            Sig set = dAlu.and(aFree[i]);
            Sig release = aluGranted.and(aPick[i]);
            aBusy[i].next(d.mux(flush, fa, d.mux(set, tr, d.mux(release, fa, aBusy[i]))));
            aInsn[i].next(d.mux(set, fInsn, aInsn[i]));
            aPc[i].next(d.mux(set, fPc, aPc[i]));
            aTag[i].next(d.mux(set, tail, aTag[i]));
            Op[] src = {opA, opB, opS};
            for (int o = 0; o < 3; o++) {
                Sig c1 = cdbHit.apply(aQ[i][o], aR[i][o]), c2 = cbHit.apply(aQ[i][o], aR[i][o]);
                aV[i][o].next(d.mux(set, src[o].v(), d.mux(c1, cdbVal, d.mux(c2, cWd, aV[i][o]))));
                aR[i][o].next(d.mux(set, src[o].rdy(), aR[i][o].or(c1).or(c2)));
                aQ[i][o].next(d.mux(set, src[o].q(), aQ[i][o]));
            }
        }
        mBusy.next(d.mux(flush, fa, d.mux(dMul, tr, d.mux(mulDone.and(cdbV), fa, mBusy))));
        mHigh.next(d.mux(dMul, Hw.is(d, dd.fn, 3), mHigh));
        mTag.next(d.mux(dMul, tail, mTag));
        Op[] msrc = {opA, opB};
        for (int o = 0; o < 2; o++) {
            Sig c1 = cdbHit.apply(mQ[o], mR[o]), c2 = cbHit.apply(mQ[o], mR[o]);
            mV[o].next(d.mux(dMul, msrc[o].v(), d.mux(c1, cdbVal, d.mux(c2, cWd, mV[o]))));
            mR[o].next(d.mux(dMul, msrc[o].rdy(), mR[o].or(c1).or(c2)));
            mQ[o].next(d.mux(dMul, msrc[o].q(), mQ[o]));
        }

        // ---------------- fetch (predict not taken; stop after fetching HALT)
        Sig consume = dispatch.or(fValid.not());
        Sig fetchOk = running.and(fetchStop.not());
        Sig fetched = imem.read(pc.shr(1).slice(k - 1, 0));
        fValid.next(d.mux(flush, fa, d.mux(consume, fetchOk, fValid)));
        fPc.next(d.mux(consume, pc, fPc));
        fInsn.next(d.mux(consume, fetched, fInsn));
        fetchStop.next(d.mux(flush, fa, fetchStop.or(consume.and(fetchOk).and(new T27Pipe.Dec(d, fetched).halt))));
        // static prediction at fetch: backward BR taken, JAL to its target, everything else falls through
        T27Pipe.Dec fd = new T27Pipe.Dec(d, fetched);
        Sig backward = fd.br.and(fd.boff.cmp(zero).isNeg());
        // return-address stack (9 entries, 2-trit pointer): calls (JAL x1) push PC+3, returns (JALR x0, x1, 0) pop.
        // Not repaired on flush: a wrong prediction only costs a commit-time redirect.
        Sig[] ras = new Sig[NROB];
        for (int i = 0; i < NROB; i++) ras[i] = d.reg("ras" + i, T);
        Sig rasSp = d.reg("ras_sp", TG);
        Sig isCall = fd.jal.and(Hw.is(d, fd.rd, 1));
        Sig isRet = fd.jalr.and(Hw.is(d, fd.rd, 0)).and(Hw.is(d, fd.ra, 1)).and(Hw.is(d, fetched.slice(26, 9), 0));
        Sig rasTop = Hw.select(rasSp.add(Hw.k(d, -1, 2)), ras);
        Sig pcPlus3 = pc.add(Hw.k(d, 3, W));
        Sig predNext = d.mux(backward, pc.add(fd.boff.shl(1)), d.mux(fd.jal, pc.add(fd.joff.shl(1)), d.mux(isRet, rasTop, pcPlus3)));
        Sig step = consume.and(fetchOk);
        Sig[] spHit = tagHit.apply(rasSp);
        for (int i = 0; i < NROB; i++) ras[i].next(d.mux(step.and(isCall).and(spHit[i]), pcPlus3, ras[i]));
        rasSp.next(d.mux(rst, Hw.k(d, 0, 2), d.mux(step.and(isCall), rasSp.add(Hw.k(d, 1, 2)), d.mux(step.and(isRet), rasSp.add(Hw.k(d, -1, 2)), rasSp))));
        fPred.next(d.mux(consume, predNext, fPred));
        pc.next(d.mux(rst, zero, d.mux(cRedirect, actualNext, d.mux(consume.and(fetchOk), predNext, pc))));
        halted.next(d.mux(rst, fa, halted.or(cHalt)));

        d.output("retire", commit);
        d.output("r_pc", hPc);
        d.output("rf_we", cRegWe);
        d.output("rf_rd", hd.rd);
        d.output("rf_wd", cWd);
        d.output("mw", cSw.or(cSt));
        d.output("mw_addr", d.mux(hd.sw, hVal.slice(W - 1, 1).concat(d.trits("0")), hVal));
        d.output("mw_data", d.mux(hd.sw, hVal2, Hw.widen(d, hVal2.slice(8, 0), W)));
        d.output("halted", halted);
        d.output("peek_word", bank[2].read(peekRow).concat(bank[1].read(peekRow), bank[0].read(peekRow)));
        Sig hPrev = d.reg("h_prev", B);
        hPrev.next(halted);
        d.assertAlways("pc_aligned", pc.digit(0).isZero());
        d.assertAlways("halted_sticky", hPrev.and(halted.not()).not());
        return d;
    }
}
