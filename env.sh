# Source this: . ~/ternhdl/env.sh
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
export TERN_WORK=/extra1/mdev/ternhdl-work
export PATH=$JAVA_HOME/bin:$TERN_WORK/tools/gradle-9.5.0/bin:$TERN_WORK/tools/venv/bin:$PATH
export SBY="$TERN_WORK/tools/venv/bin/python $TERN_WORK/tools/sby/sbysrc/sby.py"
export OPENROAD=/extra1/mdev/tools/OpenROAD-flow-scripts/tools/install/OpenROAD/bin/openroad
export TERN_MAX_THREADS=24
