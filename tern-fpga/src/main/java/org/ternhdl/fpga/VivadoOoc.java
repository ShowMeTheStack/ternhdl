package org.ternhdl.fpga;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;

/** Out-of-context Vivado synthesis + implementation with every port registered (docker, Vivado 2020.1). */
public final class VivadoOoc {
    public record Qor(String name, int luts, int ffs, int carry4, double wnsNs, double periodNs, String error) {
        public double fmaxMhz() { return 1000.0 / (periodNs - wnsNs); }
    }

    private VivadoOoc() { }

    /** Wrapper registering all ports; dut ports {name, width, in|out}; seq = dut has its own clk port. */
    public static String wrapper(String dut, List<String[]> ports, boolean seq) {
        StringBuilder s = new StringBuilder("module ooc_top (input logic clk");
        for (String[] p : ports) s.append(", ").append(p[2].equals("in") ? "input" : "output").append(" logic [").append(Integer.parseInt(p[1]) - 1).append(":0] p_").append(p[0]);
        s.append(");\n");
        List<String> conn = new ArrayList<>();
        if (seq) conn.add(".clk(clk)");
        for (String[] p : ports) {
            String w = "[" + (Integer.parseInt(p[1]) - 1) + ":0]";
            s.append("  logic ").append(w).append(" r_").append(p[0]).append(", w_").append(p[0]).append(";\n");
            if (p[2].equals("in")) { s.append("  always_ff @(posedge clk) r_").append(p[0]).append(" <= p_").append(p[0]).append(";\n"); conn.add("." + p[0] + "(r_" + p[0] + ")"); }
            else { s.append("  always_ff @(posedge clk) r_").append(p[0]).append(" <= w_").append(p[0]).append(";\n  assign p_").append(p[0]).append(" = r_").append(p[0]).append(";\n"); conn.add("." + p[0] + "(w_" + p[0] + ")"); }
        }
        s.append("  ").append(dut).append(" u (").append(String.join(", ", conn)).append(");\nendmodule\n");
        return s.toString();
    }

    public static Qor run(String name, String dutSv, String dut, List<String[]> ports, boolean seq, double periodNs, Path root, String cpus) throws Exception {
        Path dir = root.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("dut.sv"), dutSv);
        Files.writeString(dir.resolve("ooc_top.sv"), wrapper(dut, ports, seq));
        String tcl = String.join("\n",
            "set_param general.maxThreads 4",
            "read_verilog -sv dut.sv ooc_top.sv",
            "synth_design -top ooc_top -part xc7z020clg484-1 -mode out_of_context -flatten_hierarchy rebuilt -max_dsp 0",
            "create_clock -name clk -period " + periodNs + " [get_ports clk]",
            "opt_design", "place_design", "route_design",
            "report_utilization -file util.rpt",
            "set wns [get_property SLACK [get_timing_paths -max_paths 1 -nworst 1 -setup]]",
            "puts \"QOR_WNS $wns\"", "");
        Files.writeString(dir.resolve("run.tcl"), tcl);
        String cmd = "sudo docker run --rm --cpuset-cpus=" + cpus + " -v " + dir + ":/w -w /w kkrizka/vivado:2020.1 bash -c "
            + "'source /opt/Xilinx/Vivado/2020.1/settings64.sh >/dev/null; vivado -mode batch -nojournal -nolog -source run.tcl > vivado.log 2>&1'; sudo chown -R $(id -u):$(id -g) " + dir;
        Process p = new ProcessBuilder("bash", "-c", cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(3600, TimeUnit.SECONDS)) { p.destroyForcibly(); return new Qor(name, 0, 0, 0, 0, periodNs, "timeout"); }
        Path log = dir.resolve("vivado.log");
        String l = Files.exists(log) ? Files.readString(log) : out;
        Matcher w = Pattern.compile("QOR_WNS (-?[0-9.]+)").matcher(l);
        if (!w.find() || !Files.exists(dir.resolve("util.rpt"))) return new Qor(name, 0, 0, 0, 0, periodNs, "no result: " + l.substring(Math.max(0, l.length() - 800)));
        String u = Files.readString(dir.resolve("util.rpt"));
        return new Qor(name, num(u, "Slice LUTs"), num(u, "Slice Registers"), num(u, "CARRY4"), Double.parseDouble(w.group(1)), periodNs, null);
    }

    static int num(String rpt, String key) {
        Matcher m = Pattern.compile("\\|\\s*" + Pattern.quote(key) + "\\*?\\s*\\|\\s*(\\d+)").matcher(rpt);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }
}
