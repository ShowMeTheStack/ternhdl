#!/usr/bin/env bash
# Reproduces every TernHDL experiment from a clean checkout on the reference machine.
# Usage: artifact/reproduce.sh [--no-board] [--quick]
# Outputs: bench/results/rerun/<step>.txt plus a manifest. Long steps run in parallel where CPU sets allow.
set -u
cd "$(dirname "$0")/.."
. ./env.sh
BOARD=1; QUICK=0
for a in "$@"; do case $a in --no-board) BOARD=0;; --quick) QUICK=1;; esac; done
OUT=bench/results/rerun; mkdir -p $OUT
J=$(find ~/.gradle/caches -name "junit-jupiter-api-5.11.4.jar" | head -1); O=$(find ~/.gradle/caches -name "opentest4j-*.jar" | head -1); A=$(find ~/.gradle/caches -name "apiguardian-api-*.jar" | head -1)
MODS="tern-spec tern-core tern-sim tern-encode tern-formal tern-map tern-stdlib tern-spice tern-asic tern-fpga tern-isa tern-cpu tern-hls"
CP=""; for m in $MODS; do CP="$CP:$m/build/classes/java/main:$m/build/classes/java/test:$m/build/classes/java/testFixtures:$m/build/resources/test"; done
CP="${CP#:}:$J:$O:$A"
step() { local name=$1; shift; echo "[$(date -Is)] START $name"; ( "$@" ) > $OUT/$name.txt 2>&1; local rc=$?; echo "[$(date -Is)] END $name (exit $rc)"; }
run() { java -Xmx16g -cp "$CP" "$@"; }

{
  echo "commit: $(git rev-parse HEAD)"; echo "date: $(date -Is)"; uname -a; java -version 2>&1 | head -1
  verilator --version; yosys -V; ngspice -v 2>&1 | grep ngspice | head -1; z3 --version; cvc5 --version | head -1
  $OPENROAD -version 2>&1 | head -1; nproc; free -h | head -2
} > $OUT/manifest.txt 2>&1

step build ./gradlew --no-daemon -q build -x test
step unit-tests ./gradlew --no-daemon --continue test
step twin-fuzz ./gradlew --no-daemon :tern-encode:slowTest
step formal-fuzz-pdr ./gradlew --no-daemon :tern-formal:slowTest "-Dtern.ffuzz.solver=abc pdr"
[ $QUICK = 0 ] && step formal-fuzz-z3 ./gradlew --no-daemon :tern-formal:slowTest "-Dtern.ffuzz.solver=z3"
step completeness run org.ternhdl.spec.Completeness
step formal-bench run org.ternhdl.formal.FormalBench
step library-ablation run org.ternhdl.map.LibraryAblation
step mapping run org.ternhdl.map.MappingBench
step exhaustive-ripple9 run org.ternhdl.map.ExhaustiveEq ripple9 8
step exhaustive-mul9 run org.ternhdl.map.ExhaustiveEq mul9 16
step characterize run org.ternhdl.spice.Characterize cells/gt-param-nominal.json
step margin-sweep run org.ternhdl.spice.MarginSweep
step spice-compare run org.ternhdl.spice.SpiceCompare
step asic run org.ternhdl.asic.AsicCompare
step encoding run org.ternhdl.fpga.EncodingStudy
step core-qor-ripple run org.ternhdl.cpu.CoreQor all RIPPLE
step core-qor-prefix run org.ternhdl.cpu.CoreQor all PREFIX
step core-props run org.ternhdl.cpu.CoreProps t27sc,t27pipe,t27ooo "abc pdr"
step hls-ablation run org.ternhdl.hls.HlsBench
step hls-qor run org.ternhdl.hls.HlsQor
if [ $BOARD = 1 ]; then
  for k in sc pipe ooo; do step board-core-$k run org.ternhdl.cpu.CoreBoard board build alu,gcd,fib alu,fib,gcd,sort,dot,sieve,fibrec $k; done
  step board-alu9 bash -c "java -cp '$CP' org.ternhdl.fpga.BoardBuild alu9 E1 && java -cp '$CP' org.ternhdl.fpga.BoardRunner alu9 board E1 1000"
  step board-fifo bash -c "java -cp '$CP' org.ternhdl.fpga.BoardBuild fifo3x9 E1 && java -cp '$CP' org.ternhdl.fpga.BoardRunner fifo3x9 board E1 1000"
  step board-hls-dot run org.ternhdl.hls.HlsBoard dot board build 10
fi
echo "[$(date -Is)] ALL DONE"
