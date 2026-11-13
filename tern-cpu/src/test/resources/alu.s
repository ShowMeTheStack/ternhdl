; exercises ALU functions, shifts, CMP/CMPI and all branch masks; results stored from RES
.equ RES, 3000
_start: LI a0, 0t+-0+-0+-0+-0+-0+-0+-0+-0+-
        LI a1, 0t-0+-0+-0+-0+-0+-0+-0+-0+-0+
        LI t0, RES
        MIN t1, a0, a1
        SW t1, 0(t0)
        MAX t1, a0, a1
        SW t1, 3(t0)
        ANY t1, a0, a1
        SW t1, 6(t0)
        CONS t1, a0, a1
        SW t1, 9(t0)
        TSUM t1, a0, a1
        SW t1, 12(t0)
        PTI t1, a0
        SW t1, 15(t0)
        NTI t1, a0
        SW t1, 18(t0)
        ICI t1, a0
        SW t1, 21(t0)
        DCI t1, a0
        SW t1, 24(t0)
        ISZ t1, a0
        SW t1, 27(t0)
        NEG t1, a0
        SW t1, 30(t0)
        SHLI t1, a0, 5
        SW t1, 33(t0)
        SHRI t1, a0, 7
        SW t1, 36(t0)
        CMP t1, a0, a1
        SW t1, 39(t0)
        CMPI t1, a1, 0
        SW t1, 42(t0)
        MULH t1, a0, a1
        SW t1, 45(t0)
        LI t2, -9          ; low three trits -,0,0 -> unbalanced shift amount 0+1*3?  see test
        SHL t1, a1, t2
        SW t1, 48(t0)
        SHR t1, a1, t2
        SW t1, 51(t0)
        SLT3 t1, a1
        SW t1, 54(t0)
        LI t3, 0          ; branch score: one ternary digit per test
        LI t4, 5
        LI t5, 9
        BLT t4, t5, b1
        HALT
b1:     ADDI t3, t3, 1
        BLE t4, t4, b2
        HALT
b2:     ADDI t3, t3, 3
        BGT t5, t4, b3
        HALT
b3:     ADDI t3, t3, 9
        BGE t4, t4, b4
        HALT
b4:     ADDI t3, t3, 27
        BNE t4, t5, b5
        HALT
b5:     ADDI t3, t3, 81
        BEQ t4, t5, bad
        BEQ t4, t4, b6
bad:    HALT
b6:     ADDI t3, t3, 243
        SW t3, 57(t0)
        HALT
