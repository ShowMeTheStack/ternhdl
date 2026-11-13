; recursive fib(15) with a stack -> word at RES
.equ RES, 3000
_start: LI sp, 2997
        LI a0, 15
        CALL fib
        LI t0, RES
        SW a0, 0(t0)
        HALT
fib:    LI t0, 2
        BLT a0, t0, base
        ADDI sp, sp, -9
        SW ra, 0(sp)
        SW a0, 3(sp)
        ADDI a0, a0, -1
        CALL fib
        SW a0, 6(sp)
        LW a0, 3(sp)
        ADDI a0, a0, -2
        CALL fib
        LW t1, 6(sp)
        ADD a0, a0, t1
        LW ra, 0(sp)
        ADDI sp, sp, 9
base:   RET
