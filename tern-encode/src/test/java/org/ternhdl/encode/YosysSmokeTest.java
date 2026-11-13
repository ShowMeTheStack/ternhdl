package org.ternhdl.encode;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.*;
import org.junit.jupiter.api.Test;

/** The unchecked twin must synthesize with Yosys for every encoding; prints generic-gate and LUT counts. */
class YosysSmokeTest {
    @Test
    void allOpsTwinSynthesizesWithYosys() throws Exception {
        assumeTrue(new ProcessBuilder("sh", "-c", "command -v yosys").start().waitFor() == 0, "yosys not installed");
        Path dir = Files.createDirectories(TwinRunner.workRoot().resolve("yosys"));
        for (Encoding enc : Encoding.values()) {
            Path sv = dir.resolve("allops_" + enc + ".sv");
            Files.writeString(sv, SvEmitter.emit(SampleDesigns.allOps(), new SvEmitter.Options(enc, false, false)));
            String script = "read_verilog -sv " + sv + "; synth -top allops -flatten; abc -g simple; opt_clean; tee -o "
                + dir.resolve("gates_" + enc + ".txt") + " stat; design -reset; read_verilog -sv " + sv
                + "; synth_xilinx -top allops -flatten -noiopad; tee -o " + dir.resolve("lut_" + enc + ".txt") + " stat";
            Process p = new ProcessBuilder("yosys", "-q", "-p", script).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes());
            assertEquals(0, p.waitFor(), enc + ": " + out);
            String gates = Files.readString(dir.resolve("gates_" + enc + ".txt"));
            String luts = Files.readString(dir.resolve("lut_" + enc + ".txt"));
            System.out.println("YOSYS " + enc + " generic: " + grab(gates, "cells") + " | xilinx: " + grab(luts, "cells") + " " + grab(luts, "LUT"));
        }
    }

    private static String grab(String stat, String key) {
        StringBuilder sb = new StringBuilder();
        for (String l : stat.split("\n")) if (l.contains(key)) sb.append(l.trim().replaceAll("\\s+", " ")).append("; ");
        return sb.toString();
    }
}
