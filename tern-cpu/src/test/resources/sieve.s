; count primes below 200 with a tryte array at FLAGS -> RES
.equ RES, 3000
.equ FLAGS, 3005       ; RES word occupies trytes 2999..3001
.equ N, 200
_start: LI a0, FLAGS
        LI a1, N
        LI t0, 2
        LI t6, 0            ; count
outer:  BGE t0, a1, done
        ADD t1, a0, t0
        LT t2, 0(t1)
        BNE t2, zero, next
        ADDI t6, t6, 1
        MUL t3, t0, t0
mark:   BGE t3, a1, next
        ADD t4, a0, t3
        LI t5, 1
        ST t5, 0(t4)
        ADD t3, t3, t0
        B mark
next:   ADDI t0, t0, 1
        B outer
done:   LI t0, RES
        SW t6, 0(t0)
        HALT
