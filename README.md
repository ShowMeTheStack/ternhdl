# TernHDL

TernHDL is a hardware design stack in which balanced-ternary values are native, from the semantics up to simulation,
formal verification, technology mapping, transistor-level characterisation, place and route, FPGA prototyping, three
processor cores and a small high-level synthesis flow.

Designs are written in a Java-embedded language. One source is lowered two ways:

1. A binary-encoded SystemVerilog **twin**. It runs under Verilator and on FPGAs.
2. A native netlist of generic ternary (GT) cells. It feeds the SPICE, timing and OpenROAD flows.

The two lowerings are not assumed to agree. Their equivalence is checked, by simulation and by formal proof.

Author: Jyotiprakash Mishra

---

## Contents

1. [What is in the repository](#1-what-is-in-the-repository)
2. [Requirements](#2-requirements)
3. [Building and testing](#3-building-and-testing)
4. [Writing a design](#4-writing-a-design)
5. [Semantics](#5-semantics)
6. [The T27 instruction set](#6-the-t27-instruction-set)
7. [Benchmarks and baselines](#7-benchmarks-and-baselines)
8. [Results](#8-results)
9. [Reproducing every experiment](#9-reproducing-every-experiment)
10. [Limitations](#10-limitations)
11. [License](#11-license)
12. [Appendix A. Truth tables](#appendix-a-truth-tables-balanced-values)

---

## 1. What is in the repository

| Module | Contents |
|---|---|
| `tern-spec` | Gate definitions and the completeness checker (`Completeness`, writes `spec/completeness-report.txt`) |
| `tern-core` | The embedded language: `Design`, `Sig`, `Type`, `Op`, `Logic3`, driver resolution, balanced arithmetic |
| `tern-sim` | Scalar interpreter, 64-lane bit-sliced simulator (`SlicedSim`), VCD writer |
| `tern-encode` | Encodings E0/E1/E2, SystemVerilog twin emitter, Verilator runner, twin fuzzer (slow tests) |
| `tern-formal` | Miter generation, independent reference-model emitter, SymbiYosys driver, equivalence API, formal fuzzer |
| `tern-map` | GT cell library, direct lowering (B3), MUX baseline (B1), exact two-input database (`ExactDb`), cut mapper |
| `tern-stdlib` | Arithmetic generators (ripple, carry-select, prefix adders, multipliers) and the benchmark set |
| `tern-spice` | Device parameters, parametric and MOS cell models, ngspice characterisation, timing library, event-driven timing simulator, STA, SPICE comparison |
| `tern-asic` | Abstract LEF/Liberty views, OpenROAD flow driver, post-layout comparison |
| `tern-fpga` | Vivado out-of-context runs, binary baselines, encoding study, ZedBoard build and runner |
| `tern-isa` | T27 assembler and instruction-set simulator, test programs |
| `tern-cpu` | Single-cycle, five-stage pipelined and Tomasulo out-of-order T27 cores, lockstep harness, properties, QoR runs |
| `tern-hls` | Java-subset front end, IR, scheduler and FSMD generator, kernels, ablation and QoR runs |

Other directories:

- `spec/completeness-report.txt`: computed completeness facts for gate bases (Section 5.6).
- `cells/gt-param-nominal.json`: characterised timing library for the parametric device model.
- `bench/results/`: every result table quoted in Section 8 (see Section 8.9 for the file list).
- `artifact/reproduce.sh`: reruns every experiment from a clean checkout (Section 9).
- `env.sh`: environment for the reference machine (JDK path, tool paths, thread cap). Edit the paths for another machine.

---

## 2. Requirements

Everything was developed and measured on one Linux machine (Ubuntu 26.04, Intel i9-13900K, 32 threads, 123 GiB).

| Tool | Version used | Needed for |
|---|---|---|
| JDK | 25 | everything |
| Gradle | 9.5.0 (wrapper included) | build |
| Verilator | 5.032 | twin simulation, core lockstep on the twin |
| Yosys | 0.52 | formal front end, binary-encoded mapping baseline (B2) |
| SymbiYosys + ABC | from the Yosys release | PDR and k-induction proofs |
| z3 | 4.13.3 | SMT engine comparison |
| cvc5 | 1.3.2 | SMT engine comparison |
| ngspice | 45.2 | characterisation, SPICE timing comparison |
| OpenROAD (OpenROAD-flow-scripts) | with the nangate45 platform | place and route of GT cells |
| Vivado | 2020.1 (run in Docker) | FPGA out-of-context runs and bitstreams |
| ZedBoard (xc7z020) | | board runs (optional) |

Only the JDK is needed for the core language, the simulators, the mapper and most unit tests. Tests that need an
external tool look for it on the `PATH` (or in the variables set by `env.sh`: `SBY`, `OPENROAD`, `TERN_WORK`,
`TERN_MAX_THREADS`).

---

## 3. Building and testing

```sh
. ./env.sh                      # edit paths first on a new machine
./gradlew build -x test         # compile all modules
./gradlew test                  # unit tests (fast)
./gradlew :tern-encode:slowTest # twin fuzzing under Verilator (150 random designs)
./gradlew :tern-formal:slowTest "-Dtern.ffuzz.solver=abc pdr"   # formal fuzzing (120 random designs)
./gradlew :tern-formal:slowTest "-Dtern.ffuzz.solver=z3"
```

Benchmarks and experiment drivers are plain `main` classes (not tests). Examples:

```sh
java -cp <classpath> org.ternhdl.spec.Completeness
java -cp <classpath> org.ternhdl.map.MappingBench          # writes bench/results/mapping.txt
java -cp <classpath> org.ternhdl.map.ExhaustiveEq mul9 16  # exhaustive equivalence, 16 threads
java -cp <classpath> org.ternhdl.spice.Characterize cells/gt-param-nominal.json
java -cp <classpath> org.ternhdl.spice.SpiceCompare
java -cp <classpath> org.ternhdl.asic.AsicCompare          # writes bench/results/asic.txt
java -cp <classpath> org.ternhdl.fpga.EncodingStudy        # writes bench/results/encoding-study.txt
java -cp <classpath> org.ternhdl.cpu.CoreQor all PREFIX
java -cp <classpath> org.ternhdl.hls.HlsBench
```

`artifact/reproduce.sh` shows how the classpath is assembled and the arguments of every driver.

---

## 4. Writing a design

A design is a Java object built through the `Design` API. A 9-trit balanced ripple adder with carry in:

```java
import org.ternhdl.core.*;

Design d = new Design("ripple9");
Sig a = d.input("a", Type.trits(9));
Sig b = d.input("b", Type.trits(9));
Sig c = d.input("cin", Type.TRIT);
d.output("s", Arith.addWide(d, a, b, c));   // 10-trit result
```

Registers (`d.reg(...)`), memories, multiplexers (`d.mux(sel, a, b)`, three-way `MUX3`), binary clocks and resets,
explicit Trit/Bit conversions and properties (`assertAlways`, `assume`) are part of the same API. The benchmark set in
`tern-stdlib/.../Benchmarks.java` and the cores in `tern-cpu` are worked examples.

The HLS flow accepts a restricted Java method instead:

```java
public static long gcd(@Trits(9) long a, @Trits(9) long b) {
    while (a != b) {
        if (a > b) a = a - b; else b = b - a;
    }
    return a;
}
```

Supported subset: one static method; `long` scalars with `@Trits(n)` widths; `@Depth(k) long[]` memories; block, local
variable, assignment and compound assignment, `if`, `for`, `while`, `return`; calls only to the intrinsics in `T`
(`shl`, `shr`, `min`, `max`, `any`, `cons`, `tsum`). There is no `do`, `switch`, `break` or `continue`, no calls to other
methods (so no recursion) and no objects. Options: chaining budget, ripple or prefix adder cost model, strength
reduction.

---

## 5. Semantics

This section is the reference for the simulators, the encoded twin, the formal flow and the cell library.

### 5.1 Conventions

- Levels are written `N`, `O`, `P` (balanced −1, 0, +1). Index form: N=0, O=1, P=2. **O is the balanced zero, never
  "unknown" or "floating".**
- Non-level states exist only in simulation and verification: `U` (uninitialised), `X` (unknown or conflict), `F`
  (floating, undriven).
- `Bit` is the binary domain {0, 1} (clocks, resets, enables). Mixed radix is a first-class feature (Section 5.8).
- Operators are written in the balanced numeric view unless stated otherwise.

### 5.2 Value domain

| Domain | Elements | Where it exists |
|---|---|---|
| `Trit` (design value) | N, O, P | RTL, gates, hardware |
| `Logic3` (simulation value) | N, O, P, U, X, F | native simulator, event simulator |
| `Bit` | 0, 1 | binary control |
| Encoded trit | 2-bit codes (Section 5.9) | Verilator twin, FPGA |

Rules:
1. A **net** carries a `Logic3` value; a **register or memory cell** holds {N, O, P, U, X} (never F).
2. A floating net (`F`) read by an operator or register is treated as `X`.
3. `U` becomes a level only through reset or a write. Before that, operators see `U` as unknown (result `X`, except
   where Section 5.4 says the result is determined).

### 5.3 Drivers, strength and resolution

A driver is a pair (value, strength) with strength in {`strong`, `weak`, `float`}; `float` means the driver is
disconnected. Resolution of the drivers on one net:

1. Drop `float` drivers. If none remain, the net is `F`.
2. Keep only the strongest remaining class (`strong` over `weak`).
3. If every kept driver has the same value, the net has that value (including `U` if all are `U`).
4. Otherwise (different levels, or any `X`, or `U` mixed with levels) the net is `X`.

A weak `O` driver models the resistive mid-level hold of some cell designs; a strong driver overrides it. Resolution is
commutative, associative and idempotent, so driver order never matters (checked exhaustively over all driver triples in
the `tern-core` tests).

### 5.4 Operators and the exact X-extension

Level semantics (balanced values):

| Op | Arity | Definition |
|---|---|---|
| BUF | 1 | x |
| STI | 1 | −x |
| PTI | 1 | +1 if x in {−1, 0}, else −1 |
| NTI | 1 | +1 if x = −1, else −1 |
| ICI | 1 | cyclic increment: −1 to 0, 0 to +1, +1 to −1 |
| DCI | 1 | cyclic decrement: −1 to +1, 0 to −1, +1 to 0 |
| IsZero | 1 | +1 if x = 0, else −1 |
| MIN | 2 | min(a, b) |
| MAX | 2 | max(a, b) |
| ANY | 2 | clamp(a + b, −1, +1) |
| CONS | 2 | a if a = b, else 0 |
| SUM | 2 | (a + b) mod 3 mapped to {−1, 0, +1} (−2 to +1, +2 to −1) |
| MUX3 | 4 | select s in {N, O, P} picks a, b, c |
| TIE_N, TIE_O, TIE_P | 0 | constants |

Complement forms (NMIN = STI of MIN, NMAX, NANY, ...) are compositions, not primitives. All truth tables are in
Appendix A.

**X-extension.** For an operator f, the extension to `Logic3` inputs replaces every input in {U, X, F} by "any level",
evaluates f on all completions, and returns the level if all completions agree, else `X`. Examples: MIN(N, X) = N,
MAX(P, X) = P, CONS(N, X) = X, ANY(P, X) = X, MUX3(s, a, b, c) with unknown s and a = b = c yields that common value.

- The extension is exact per primitive. A composite expression with reconvergent unknowns can be more pessimistic than
  its exact extension (as in binary X-propagation). Composite behaviour is defined as the composition of primitive
  extensions, and the optimiser may not rely on exactness for derived identities.
- `U` and `F` inputs give the same results as `X` in operators; they differ only in storage and reporting.

### 5.5 Types and arithmetic

- `Trit`; `TritVec<n>` (little-endian digits, digit i has weight 3^i); `Tryte` (9 trits); `Word` (27 trits). A 3-trit
  value is just `TritVec<3>`.
- **Balanced integer** value of an n-trit vector: sum of d_i 3^i with d_i in {−1, 0, +1}, range ±(3^n − 1)/2.
  **Unbalanced** (digits 0..2) is a distinct type with range 0..3^n − 1. Conversions are explicit operations, never
  implicit. The conversion is the digit-wise relabelling N, O, P to 0, 1, 2 (and back). Since
  sum (d_i + 1) 3^i = sum d_i 3^i + (3^n − 1)/2, relabelling is the numeric offset, so
  `toUnbalanced(v) = v + (3^n − 1)/2` and `toBalanced(u) = u − (3^n − 1)/2` need no carries and cost no logic. Both are
  total and bijective on n-trit vectors (checked exhaustively for n ≤ 6).
- **Add with carry:** per digit s = a + b + cin, s in [−3, 3]; result digit r and carry c in {−1, 0, +1} with
  s = r + 3c: s = ±3 gives (r, c) = (0, ±1); s = 2 gives (−1, +1); s = −2 gives (+1, −1); otherwise (s, 0). Variants:
  wrapping (mod 3^n, balanced range), widening (n + 1 trits), saturating.
- **Negation** is digit-wise STI (no carry). **Subtraction** is addition of the negation.
- **Shifts:** `<< k` multiplies by 3^k; `>> k` drops k low trits. For balanced numbers, dropping low trits is
  round-to-nearest with no ties (a property of the representation, checked exhaustively on small widths).
- **Compare:** the three-way compare returns the trit sign(a − b) in {N, O, P}; relational operators derive from it.
- **Multiplication:** shift-and-add. Division is not provided.
- X/U in any operand of an arithmetic operation: result digits from the lowest unknown position upward are `X`
  (carry unknown); lower digits are exact.

### 5.6 Cell basis and completeness (computed)

From `spec/completeness-report.txt` (values N=0, O=1, P=2; "closure" counts how many of the 19,683 binary functions are
reachable; brute force and the Slupecki criterion agree on every basis tested):

- The Webb function (max(x, y) + 1 mod 3) is complete on its own.
- The Kleene basis {MIN, MAX, STI} is not complete (closure 82 without tie cells, 197 with). This is the classical
  result: every function built from MIN, MAX, STI and constants is monotone in the Kleene order, and 197 is the number
  of such binary functions.
- {MIN, MAX, STI, PTI, NTI} is complete only with tie cells (closure 3,888 without).
- {ICI, MIN} is complete without tie cells; so are {ICI, MAX}, {DCI, MIN}, {PTI, ANY}, {PTI, SUM} and others (full
  list in the report).
- Of the 4,095 non-empty subsets of the 12-gate library, 3,770 are complete without tie cells and 3,978 with them.

The gate-level IR keeps every library operation as a node (not a minimal basis); the mapper relies on the completeness
facts to guarantee that any function can be built. Tie cells are always available.

### 5.7 Sequential semantics

- All state is clocked by binary clocks (`Bit`). A T-DFF samples its trit input on the rising edge (falling edge
  optional) and holds {N, O, P, U, X}.
- **Reset:** per-register reset value (default O); synchronous or asynchronous (binary reset signal). Before reset a
  register is `U`.
- **Unknown handling:** D = X/U/F gives next state X. A `U` clock or enable is a simulation error (reported; the state
  becomes X).
- **Latch (T-DL):** transparent while its binary enable is 1; holds otherwise.
- **Memories:** read and write ports are binary-clocked; an `X` read address returns `X`; a write with an `X` address
  makes every entry `X` (poison). Write enable is a `Bit`.
- Ternary-valued clocks, enables or phases are not supported.
- **Properties:** `assertAlways(name, cond)` and `assume(name, cond)` take a `Bit` condition checked in every cycle.
  They are not checked in cycles where the synchronous reset is 1 (like SVA `disable iff (rst)`). In simulation an
  unknown condition counts as a violation; in formal, assumptions constrain the environment.

### 5.8 Mixed radix

- `Bit` is a native type. Clocks, resets and enables must be `Bit`; using a `Trit` there is a type error.
- Conversions are explicit nodes: `isPos`, `isNeg`, `isZero` (Trit to Bit), `toTrit(b)` mapping 0 to O and 1 to P (or
  0 to N and 1 to P as a separately named operation).
- **Bit X-extension:** an unknown `Bit` ranges over {0, 1} only, never N. AND (MIN on Bits) has 0 dominant and OR (MAX
  on Bits) has 1 dominant: AND(0, X) = 0, OR(1, X) = 1. (Using the trit-level extension for Bits would give
  AND(0, X) = X, because X "could be N", which keeps a reset-gated write enable unknown.)

### 5.9 Encodings (binary twin and FPGA)

The default is E1. In every encoding the encoded domain is two-valued per wire: `U`, `X` and `F` have no encoding.

| Enc | Trit codes | Illegal | Notes |
|---|---|---|---|
| **E1** dual-rail sign `(neg, pos)` | O=00, P=01, N=10 | 11 | STI = swap bits; MIN: neg = a_n or b_n, pos = a_p and b_p; MAX: neg = a_n and b_n, pos = a_p or b_p; zero is all-zeros |
| E0 (Park-style) | O=00, P=01, N=11 | 10 | |
| E2 offset | N=00, O=01, P=10 | 11 | friendly to binary carry chains |
| E3 packed (memory only) | 5 trits in 8 bits, base-3 value 0..242 | 243..255 | explicit converters |

**Illegal codes.** In checked mode (default in simulation and formal) an illegal code reaching a checked point is an
assertion violation. In unchecked mode (synthesis) behaviour is unspecified and the optimiser may treat it as a
don't-care. Generated assertions state that illegal codes never occur under legal inputs, and the formal flow proves
them.

### 5.10 Timing and electrical abstractions

- Three nominal voltages VSS < VMID < VDD map to N, O, P. Thresholds V_thL < V_thH classify a voltage as N, O or P.
- Four noise margins: NML (low level against the lower threshold), NMML (mid level against the lower threshold), NMMH
  (mid level against the upper threshold), NMH (high level against the upper threshold).
- **Transitions:** an N to P change passes through O. In the event simulator a wire between the two levels is "in
  transit" for an interval, and a reader that samples during that interval may see O. The simulator can report such
  events (glitch-window check); the cycle-based mode ignores them.
- Cells carry delays per (input level change, output level change) arc, a static mid-level current, a drive strength
  and a fan-out limit; values come from characterisation.

### 5.11 Correctness obligations and how each is checked

- **T1 Encoding soundness.** For every primitive f and encoding E, on legal inputs, decode(f_E(encode(x))) = f(x).
  Checked three ways: the SystemVerilog functions are generated from the same level tables as the simulators;
  exhaustive Verilator simulation of every operation on all 78,732 legal inputs for E0, E1 and E2; and a SymbiYosys
  proof of an all-operations twin against the independent reference model, for every encoding.
- **T2 Refinement.** The encoded twin of a design refines the native design on legal inputs. Checked per design by
  formal equivalence of the twin against the reference model on every node (k-induction or PDR). The general statement
  follows by induction over the IR in topological order: inputs are encoded digit-wise; every node's twin value is a
  function of its arguments' twin values that agrees with the native operator on legal codes (T1) and produces only
  legal codes (checked-mode assertions, proved); registers start from legal, equal states and are updated by such
  functions (induction over cycles). This argument is written out, not mechanised.
- **T3 Mapping soundness.** A mapped netlist is equivalent to its RTL, checked per design by equivalence checking.
- **T4 Resolution laws.** Driver resolution is commutative, associative and idempotent (exhaustive tests).
- **T5 X-extension soundness.** The exact extension of each primitive never returns a level that some completion
  contradicts (exhaustive tests).

---

## 6. The T27 instruction set

### 6.1 State

- **Word** = 27 trits (balanced range ±(3^27 − 1)/2 = ±3,812,798,742,493). **Tryte** = 9 trits.
- **Registers:** 27 general registers named by their balanced 3-trit index: `x-13 ... x-1, x0, x1 ... x13`. `x0`
  (field `000`) always reads 0 and ignores writes. Conventions: `x1` return address, `x2` stack pointer, `x3..x8`
  arguments and results, `x-1..x-13` temporaries.
- **PC:** tryte address of the current instruction. Instructions are one word (3 trytes); the PC advances by 3.
- **Memory:** tryte-addressed, balanced addresses. A word at an aligned address A (lowest trit of A = O) occupies the
  centred trytes A−1 (trits 0-8), A (trits 9-17) and A+1 (trits 18-26). The three trytes share the index A >> 1 and
  differ only in their lowest address trit (N, O, P), so memory is three tryte banks selected by the lowest trit, with
  no carry in address decoding. Implementations provide a window of 3^k trytes centred on 0 (the FPGA cores use 3^7 =
  2,187 trytes per memory).

### 6.2 Instruction formats (trit 0 = least significant)

| Format | trits 0-2 | 3-5 | 6-8 | 9-11 | 12-14 | 15-26 |
|---|---|---|---|---|---|---|
| R | op | rd | ra | rb | fn | 0 (reserved) |
| I | op | rd | ra | imm (trits 9-26, 18-trit balanced) | | |
| S | op | rs | ra | imm (trits 9-26) | | |
| B | op | ra | rb | mask (3 trits) | offset (trits 12-26, 15-trit balanced, in words) | |
| J | op | rd | offset (trits 6-26, 21-trit balanced, in words) | | | |

Opcode = balanced value of trits 0-2 (−13..13).

### 6.3 Instructions

| op | Mnemonic | Format | Semantics |
|---|---|---|---|
| 0 | `SYS fn` | R | fn 0 = `HALT`; other fn reserved |
| 1 | ALU group | R | `rd = f_fn(ra, rb)`, see 6.4 |
| 2 | `ADDI rd, ra, imm` | I | rd = ra + imm |
| 3 | `LUI rd, imm` | I | rd = imm * 3^9 (ra ignored) |
| 4 | `SHLI rd, ra, k` | I | rd = ra << u(imm) (multiply by 3^k, wrap); the assembler encodes imm = k − 13 |
| 5 | `SHRI rd, ra, k` | I | rd = ra >> u(imm) (drop k low trits: round to nearest) |
| 6 | `LW rd, imm(ra)` | I | rd = word at ra + imm |
| 7 | `LT rd, imm(ra)` | I | rd = tryte at ra + imm (upper trits O) |
| 8 | `SW rs, imm(ra)` | S | word at ra + imm = rs |
| 9 | `ST rs, imm(ra)` | S | tryte at ra + imm = low 9 trits of rs |
| 10 | `BR ra, rb, mask, off` | B | if sign(ra − rb) is in mask: PC += 3*off. Mask trit k (k = 0: "<", 1: "=", 2: ">") is P to include |
| 11 | `JAL rd, off` | J | rd = PC + 3; PC += 3*off |
| 12 | `JALR rd, ra, imm` | I | t = (ra + imm) with its lowest trit cleared; rd = PC + 3; PC = t |
| 13 | `CMPI rd, ra, imm` | I | rd = sign(ra − imm) (a trit in digit 0) |

Branch mask aliases: `BLT` = (P,O,O), only "<"; `BEQ` = (O,P,O); `BGT` = (O,O,P); `BNE` = (P,O,P); `BLE` = (P,P,O);
`BGE` = (O,P,P); `B` (always) = (P,P,P). One instruction covers all six relations because the compare result is a
single trit.

### 6.4 ALU functions (fn = balanced value of trits 12-14)

| fn | Name | rd = |
|---|---|---|
| 0 | ADD | ra + rb |
| 1 | SUB | ra − rb |
| 2 | MUL | low 27 trits of ra * rb |
| 3 | MULH | high 27 trits of the 54-trit product |
| 4 | MIN | trit-wise min |
| 5 | MAX | trit-wise max |
| 6 | ANY | trit-wise clamp(a + b) |
| 7 | CONS | trit-wise consensus |
| 8 | TSUM | trit-wise sum mod 3 |
| 9 | CMP | sign(ra − rb) in digit 0, other digits O |
| 10 | SHL | ra << u(rb) |
| 11 | SHR | ra >> u(rb) |
| 12 | NEG | −ra (trit-wise STI) |
| −1 | PTI | trit-wise PTI(ra) |
| −2 | NTI | trit-wise NTI(ra) |
| −3 | ICI | trit-wise ICI(ra) |
| −4 | DCI | trit-wise DCI(ra) |
| −5 | ISZ | trit-wise IsZero(ra) |
| −6 | SLT3 | sign(ra) (digit 0) |

Arithmetic wraps modulo 3^27 into the balanced range. u(rb) is the unbalanced value (digits N, O, P read as 0, 1, 2) of
rb's lowest three trits, 0..26; it equals the balanced value of those trits plus 13.

### 6.5 Design notes

- **Total alignment.** JALR clears the lowest trit of its target and word loads and stores ignore the lowest address
  trit, so every behaviour is defined and PC alignment is an invariant. (An earlier version let JALR produce an
  unaligned PC; formal property checking of the cores found it.)
- **Centred words.** With a straight (A, A+1, A+2) layout the third tryte of an aligned word falls into the next index
  (A + 2 = A + 3 − 1), so a word would straddle two memory rows. Centring keeps all three trytes in one row.
- **Unbalanced shift amount.** A ternary barrel shifter selects 0, 1 or 2 times 3^k per stage with one MUX3 per digit
  when the amount digits are unbalanced; "rb mod 27" on a balanced value would need a correction adder.
- Not provided: traps, interrupts and a privilege model. MULH is executed by the cores' multiplier.

### 6.6 Programs

`tern-cpu/src/test/resources/*.s`: `alu`, `gcd`, `fib`, `sort`, `dot`, `sieve`, `fibrec`. The assembler and ISS are in
`tern-isa`.

---

## 7. Benchmarks and baselines

The benchmark set and baselines were fixed before any results were collected.

| Group | Designs |
|---|---|
| Combinational arithmetic | `ripple9`, `ripple27`, `csel9`, `csel27`, `prefix9`, `prefix27` (ripple, carry-select, prefix adders), `satadd9`, `cmp27`, `mul9` |
| Combinational control and kernels | `alu9`, `dot4x9` (ternary-weight dot product), `tritsum27` |
| Sequential | `counter9`, `shiftreg4x9`, `fifo3x9`, `regfile9x9`, `mulfsm9`, `irqctl9`, `traffic` |
| Systems | single-cycle, five-stage pipelined and Tomasulo out-of-order T27 cores |
| HLS kernels | `tmix`, `poly`, `gcd`, `sumsq`, `fir`, `dot`, `bubble`, `matvec`, `digitsum` |

The sequential designs re-create the kinds of design named by Park et al. (IEEE Access 2025); their RTL was not
available, so these are our own versions.

Baselines (all built in this repository):

| ID | Baseline |
|---|---|
| B1 | MUX-based mapping (3:1 MUX decomposition per output) |
| B2 | Binary-encoded twin through Yosys + ABC (generic gates and Xilinx LUTs) |
| B3 | Park-style direct lowering (case to MUX, if to IsZero/STI/MIN/MAX, operators to gates), our re-implementation |
| B4 | Matched-width binary datapaths (43 bits for 27 trits, 15 bits for 9 trits) on the same FPGA flow |

Reporting rules: every table says whether numbers are measured, simulated, estimated (post-route timing) or derived;
numbers reported by other papers are quoted as reported and never mixed with ours; negative results are reported in the
same tables.

---

## 8. Results

Labels: *measured* (tool run on real inputs or hardware), *simulated* (ngspice, event simulator, RTL simulation),
*estimated* (post-route timing), *derived* (computed from other rows).

### 8.1 Simulators and the encoded twin

- Bit-sliced simulator: 151.5 to 156.5 k cycles/s on one core over 64 lanes (9.7 to 10.0 M lane-cycles/s); the scalar
  interpreter runs 22.3 to 22.5 k cycles/s (*measured*, `bench/results/sim-throughput.txt`).
- Twin unit tests: every operation checked exhaustively in E0, E1 and E2 (72 tests).
- Twin fuzzing: 150 random designs, 6,032 nodes, 321,008 digit comparisons between the native simulator and the
  Verilator twin, 0 mismatches (*simulated*).

### 8.2 Formal verification

- Formal fuzzing: 120 random designs, twin against the independent reference model. ABC PDR proves 120 of 120 (median
  1.5 s, maximum 5.2 s); z3 proves 118 (two time-outs, both proved by PDR; median 5.2 s); cvc5 gave no verdict within
  10 minutes on the designs tried.
- Mapping equivalence: 18 of 19 mapped benchmarks are proved equivalent to their RTL by PDR. The nineteenth, `mul9`,
  times out at 1,800 s and is instead checked by exhaustive simulation of all 387,420,489 inputs (0 mismatches).
- Core properties: PDR proves the invariants (including PC alignment) for the single-cycle and pipelined cores
  (small-memory variants). The out-of-order core times out at 3,600 s and is checked by lockstep simulation only.

### 8.3 Technology mapping (`bench/results/mapping.txt`)

Area model: unary 1, binary 2, MUX3 3, DFF 4 (placeholder, not characterised). The cut mapper uses an exact-synthesis
database of all 19,683 two-input ternary functions (built in 1 to 3 s).

- Geometric mean over 19 benchmarks: 20.0 % fewer cells than the Park-style direct lowering (B3) and 67.9 % fewer than
  MUX-based mapping (B1).
- Largest gains on prefix adders and reduction trees (`tritsum27` 62.3 %, `prefix9` 55.3 %, `mul9` 42.5 %); ripple
  adders gain little (1.5 % and 4.3 %); `traffic` is 8 % worse.
- Library ablation over 8 libraries: the full library cuts the mean cost of a function by 57 % (6.56 vs 15.31)
  compared with a four-cell library similar to tritflow's.
- The mapper is deterministic (sorted merge of parallel results).

### 8.4 Device model, characterisation and timing (`bench/results/spice-compare.txt`, `noise-margins.txt`)

- ngspice characterisation of the GT library with a parametric three-level device model gives 4,824 values and is
  bit-for-bit reproducible. A second model builds all 15 cells from generic level-1 MOSFETs; their DC truth tables are
  correct at every input combination within 20 mV, and a cell whose input sits at the mid level draws about 0.7 µA.
- Noise margins at VDD = 0.6, 0.9 and 1.2 V for switching softness s = 5 to 40 mV: the minimum margin is 51 to 92 % of
  the ideal VDD/4, and the four margins are pairwise symmetric (within 1 mV).
- SPICE against the event-driven timing simulator on 10 mapped designs (29 vectors each): the STA bound is at least the
  SPICE maximum on all 10. Per-vector settle-time correlation is 0.53 to 0.92 on seven designs and 0.12 to 0.32 on the
  glitch-heavy `dot4x9`, `tritsum27` and `mul9`; the mean settle time still agrees within 2 % (`dot4x9`, `tritsum27`)
  and 12 % (`mul9`). Transition-count agreement is 80.5 to 100 % on the seven designs (*simulated*).

### 8.5 Place and route (`bench/results/asic.txt`)

- OpenROAD places, routes and extracts all 9 designs with abstract GT cells on the nangate45 technology LEF; VMID is
  routed as a signal net.
- DRC count 0 on `ripple9`, `prefix9`, `satadd9`, `alu9`, `counter9`; `csel9` 5, `fifo3x9` 15, `regfile9x9` 85, `mul9`
  148. The counts are sensitive to equal-cost cell choices in the mapper.
- Post-layout: our STA is at least the SPICE maximum (with extracted wire capacitance) on all 6 compared designs;
  SPICE vs event-simulator correlation 0.60 to 0.82, except `mul9` (−0.05) (*simulated*).
- OpenROAD runs are deterministic (repeat runs give byte-identical DEF).

### 8.6 FPGA encoding study (`bench/results/encoding-study.txt`)

Vivado 2020.1, xc7z020clg484-1, out of context, all ports registered, no DSPs, 4 ns target (*estimated*). 42
implementations: 11 ternary designs in E0, E1 and E2 plus 9 binary baselines.

- The three encodings are within a few percent on most designs. Exceptions: `cmp27` and `counter9` differ by up to
  25 % in LUTs, and `fifo3x9` by 40 % in Fmax.
- Matched-width binary adders and multipliers are 2.4 to 5 times faster than the encoded ternary versions, because
  the encoded twin cannot use the CARRY4 chains (27-trit ripple: 108 LUT, 61 MHz; 43-bit binary: 43 LUT + 11 CARRY4,
  306 MHz). The gap is smaller for the dot product (1.2x) and the ALU (1.7x). FIFOs, counters and register files run at
  280 to 430 MHz in both radices. This is a negative result for ternary arithmetic on binary FPGAs.

### 8.7 Cores (*estimated* Fmax, *simulated* CPI)

| Core | LUT ripple to prefix adders | FF | Fmax ripple to prefix |
|---|---|---|---|
| single-cycle | 15,759 to 16,443 | 2,110 / 2,102 | 14.5 to 25.3 MHz |
| five-stage pipeline | 12,927 to 14,220 | 2,829 / 2,828 | 15.1 to 38.1 MHz |
| Tomasulo out-of-order | 18,957 to 20,216 | 6,095 / 6,062 | 14.5 to 45.5 MHz |

- All three cores run the seven programs in lockstep with the ISS on the native simulator and on the Verilator twin. On
  the ZedBoard, three programs (`alu`, `gcd`, `fib`) run in lockstep and all seven match the ISS final memory.
- Pipeline CPI 1.35 to 3.09 (no branch prediction). The out-of-order core (single issue, 9-entry ROB, branch prediction
  with a return-address stack) beats the pipeline's CPI on `fib` (1.03 vs 1.35), `gcd`, `sieve` and `fibrec`.
- Throughput (Fmax / CPI, geometric mean over seven programs, *derived*): 19.7, 21.8 and 27.4 MIPS for single-cycle,
  pipeline and out-of-order. The out-of-order core costs 42 % more LUTs than the pipeline.

### 8.8 HLS (`bench/results/hls-ablation.txt`, `hls-runtime.txt`, `hls-qor-all.txt`)

- 9 kernels compile to FSM-with-datapath designs whose results match the native Java execution; 45 ablation variants
  (chaining budget, adder cost model, strength reduction) all match.
- Encoded twins of all kernels pass under Verilator with both adder styles; `tmix` is proved by PDR to match its
  reference for all inputs and `poly` is checked exhaustively (243 inputs).
- With the prefix-adder cost model the run time per call improves by 1.65x (geometric mean, *derived*), usually with more LUTs.
- The `dot` kernel runs on the ZedBoard (10 of 10 test vectors).

### 8.9 Result files

| File | Contents |
|---|---|
| `mapping.txt` | B1, B3, ours, equivalence verdicts, B2 gate and LUT counts for the 19 benchmarks |
| `spice-compare.txt` | pre-layout SPICE vs event simulator vs STA |
| `noise-margins.txt` | noise-margin sweep |
| `asic.txt`, `asic-det.log` | OpenROAD results and post-layout comparison |
| `asic-nondet.txt`, `spice-compare-nondet.txt` | the same runs before the mapper was made deterministic (kept for reference) |
| `asic-compare.txt` | event simulator vs post-layout comparison log |
| `encoding-study.txt`, `encoding-study.raw.txt` | the 42 Vivado implementations |
| `core-props-ooo.txt` | out-of-order core property-checking log |
| `hls-ablation.txt`, `hls-runtime.txt`, `hls-qor-all.txt`, `hls-qor2.txt`, `hls-qor3.txt`, `hls-dot-board.txt` | HLS results |
| `sim-throughput.txt` | simulator throughput runs |

---

## 9. Reproducing every experiment

```sh
artifact/reproduce.sh            # everything, including the ZedBoard runs
artifact/reproduce.sh --no-board # skip the board
artifact/reproduce.sh --quick    # also skip the z3 formal fuzzing run
```

Outputs go to `bench/results/rerun/<step>.txt` together with a manifest (commit, tool versions, machine). A full run
takes several hours on the reference machine; the steps are listed in the script. Vivado runs in Docker and writes to
`~/ternhdl-viv`.

A clean-checkout rerun reproduced every verdict and table. It also exposed three defects, now fixed:

1. The script reported exit code 0 for every step.
2. The exact-database merge was nondeterministic under parallel streams (`regfile9x9` mapped to 262 to 265 cells across
   runs); the mapping, SPICE and OpenROAD tables were regenerated with the deterministic code.
3. The ripple-adder baselines in the core Fmax table predated the final ISA revision and were replaced.

Run-to-run variation that remains: bitstream timing slack differs by up to 1.4 ns between Vivado runs, and solver wall
times depend on machine load.

---

## 10. Limitations

- No real ternary process: the device models are a parametric behavioural model and generic level-1 MOSFETs. The
  OpenROAD flow uses abstract cells on a binary technology LEF and routes VMID as a signal, with no three-rail power
  network.
- The mapper's area model is a placeholder, not characterised area.
- Twin equivalence is proved per design; the general refinement argument (Section 5.11) is written out, not mechanised.
- The event simulator does not model glitch interaction in deep reconvergent trees, which explains the low
  correlation on `dot4x9`, `tritsum27` and `mul9`.
- On binary FPGAs, encoded ternary arithmetic is slower than binary arithmetic of matched width.
- The out-of-order core is checked by lockstep simulation only; the core properties are safety invariants, not full ISA
  conformance.
- Board runs use a fixed 50 MHz clock with a single-stepped design under test; FPGA Fmax values are post-route
  estimates.

---

## 11. License

Copyright (C) 2026 Jyotiprakash Mishra.

TernHDL is licensed under the GNU General Public License, version 3 (see `LICENSE`), with an additional attribution
term under section 7(b) (see `NOTICE`): every copy of the source and every work based on it must keep the attribution
"TernHDL, by Jyotiprakash Mishra (https://github.com/ShowMeTheStack/ternhdl)". There is no Classpath Exception.
TernHDL does not modify OpenJDK; any future modified OpenJDK sources would keep OpenJDK's own license.

---

## Appendix A. Truth tables (balanced values)

These tables are the single source for the simulator, the cell library and the encodings. A unit test
(`tern-spec/.../SpecTablesTest.java`) parses this appendix and checks it against the Java gate definitions, so keep the
format unchanged and keep this appendix at the end of the file.

Unary (input −, 0, +):

| Op | −  | 0  | +  |
|---|---|---|---|
| BUF | − | 0 | + |
| STI | + | 0 | − |
| PTI | + | + | − |
| NTI | + | − | − |
| ICI | 0 | + | − |
| DCI | + | − | 0 |
| IsZero | − | + | − |

Binary (rows a, columns b = −, 0, +):

| a \ b | MIN | MAX | ANY | CONS | SUM |
|---|---|---|---|---|---|
| − | −,−,− | −,0,+ | −,−,0 | −,0,0 | +,−,0 |
| 0 | −,0,0 | 0,0,+ | −,0,+ | 0,0,0 | −,0,+ |
| + | −,0,+ | +,+,+ | 0,+,+ | 0,0,+ | 0,+,− |

Each cell lists the output for b = −, 0, +.
