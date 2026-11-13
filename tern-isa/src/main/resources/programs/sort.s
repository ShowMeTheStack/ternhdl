; bubble sort of N words at ARR
.equ ARR, 3000
.equ N, 12
_start: LI a0, ARR
        LI a1, N
outer:  LI t0, 1
        LI t5, 0            ; swapped flag
        MV t1, a0
inner:  BGE t0, a1, endin
        LW t2, 0(t1)
        LW t3, 3(t1)
        BLE t2, t3, noswap
        SW t3, 0(t1)
        SW t2, 3(t1)
        LI t5, 1
noswap: ADDI t1, t1, 3
        ADDI t0, t0, 1
        B inner
endin:  BNE t5, zero, outer
        HALT
.org 3000
.word 17
.word -5
.word 1000000
.word 0
.word -123456789
.word 42
.word 42
.word -1
.word 3812798742493
.word -3812798742493
.word 7
.word 2
