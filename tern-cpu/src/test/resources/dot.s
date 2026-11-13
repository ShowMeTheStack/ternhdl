; ternary-weight dot product: sum over i of w[i]*x[i], weights are trytes in {-1,0,1}; also checks MUL
.equ RES, 3000
.equ W, 3002       ; weight trytes 3002.. = low/mid/high trytes of the centered words at 3003, 3006, 3009
.equ X, 3030
.equ N, 9
_start: LI a0, W
        LI a1, X
        LI t0, 0          ; i
        LI t1, 0          ; acc
        LI t7, 0          ; acc via MUL
loop:   LI t9, N
        BGE t0, t9, done
        ADD t2, a0, t0
        LT t3, 0(t2)
        LW t4, 0(a1)
        MUL t8, t3, t4
        ADD t7, t7, t8
        BEQ t3, zero, skip
        BGT t3, zero, plus
        SUB t1, t1, t4
        B skip
plus:   ADD t1, t1, t4
skip:   ADDI t0, t0, 1
        ADDI a1, a1, 3
        B loop
done:   LI t2, RES
        SW t1, 0(t2)
        SW t7, 27(t2)
        HALT
.org 3003
.word 387420488   ; trytes -1, 0, +1 (low tryte first)
.word -387400806   ; trytes 0, +1, -1
.word 387420488   ; trytes -1, 0, +1
.org 3030
.word 5
.word -7
.word 100
.word 3
.word 4
.word -9
.word 1000
.word 22
.word -1
