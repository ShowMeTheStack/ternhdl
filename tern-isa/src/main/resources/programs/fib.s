; iterative fib(30) -> word at RES
.equ RES, 3000
_start: LI a0, 0
        LI a1, 1
        LI t0, 30
loop:   BEQ t0, zero, done
        ADD t1, a0, a1
        MV a0, a1
        MV a1, t1
        ADDI t0, t0, -1
        B loop
done:   LI t2, RES
        SW a0, 0(t2)
        HALT
