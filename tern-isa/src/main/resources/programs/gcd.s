; gcd(1071, 462) by subtraction -> RES
.equ RES, 3000
_start: LI a0, 1071
        LI a1, 462
loop:   BEQ a0, a1, done
        BGT a0, a1, agt
        SUB a1, a1, a0
        B loop
agt:    SUB a0, a0, a1
        B loop
done:   LI t0, RES
        SW a0, 0(t0)
        HALT
