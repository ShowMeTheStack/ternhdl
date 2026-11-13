package org.ternhdl.asic;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;
import org.ternhdl.core.Design;
import org.ternhdl.map.*;
import org.ternhdl.spice.*;
import org.ternhdl.stdlib.Benchmarks;

/** OpenROAD place-and-route spike for one mapped benchmark with abstract ternary cells. */
public final class AsicFlow {
    public record Result(String name, int cells, double areaUm2, double util, double wirelengthUm, int drc, double openStaPs,
                         double vmidUm, Map<Integer, Double> netCapF, Netlist netlist, Path dir, String error) { }

    static final String PLAT = "/extra1/mdev/tools/OpenROAD-flow-scripts/flow/platforms/nangate45";

    public static Result run(String name, Netlist nl, TimingLib lib, DeviceParams p, Path root, int timeoutSec) throws Exception {
        Path dir = root.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("cells.lef"), AsicViews.lef());
        Files.writeString(dir.resolve("cells.lib"), AsicViews.liberty(lib, p));
        Files.writeString(dir.resolve("design.v"), AsicViews.verilog(nl, "top"));
        boolean seq = nl.dffCount() > 0;
        String sdc = (seq ? "create_clock -name clk -period 2000 [get_ports clk]\n" : "create_clock -name vclk -period 2000\n")
            + "set_input_delay 0 -clock " + (seq ? "clk" : "vclk") + " [delete_from_list [all_inputs] [get_ports {vmid clk}]]\n"
            + "set_output_delay 0 -clock " + (seq ? "clk" : "vclk") + " [all_outputs]\n";
        Files.writeString(dir.resolve("design.sdc"), sdc);
        String tcl = String.join("\n",
            "read_lef " + PLAT + "/lef/NangateOpenCellLibrary.tech.lef",
            "read_lef cells.lef",
            "read_liberty cells.lib",
            "read_verilog design.v",
            "link_design top",
            "read_sdc design.sdc",
            "initialize_floorplan -utilization 40 -aspect_ratio 1.0 -core_space 2.0 -site " + AsicViews.SITE,
            "source " + PLAT + "/make_tracks.tcl",
            "place_pins -hor_layers metal3 -ver_layers metal2",
            "add_global_connection -net VDD -pin_pattern {^VDD$} -power",
            "add_global_connection -net VSS -pin_pattern {^VSS$} -ground",
            "global_connect",
            "set_voltage_domain -name CORE -power VDD -ground VSS",
            "define_pdn_grid -name grid -voltage_domains CORE",
            "add_pdn_stripe -grid grid -layer metal1 -width 0.17 -followpins",
            "add_pdn_stripe -grid grid -layer metal4 -width 0.48 -pitch 5.6 -offset 1.0",
            "add_pdn_connect -grid grid -layers {metal1 metal4}",
            "pdngen",
            "global_placement -density 0.6",
            "detailed_placement",
            "check_placement",
            "source " + PLAT + "/setRC.tcl",
            "global_route -congestion_iterations 30",
            "detailed_route -output_drc drc.rpt -droute_end_iter 30 -verbose 1",
            "define_process_corner -ext_model_index 0 X",
            "extract_parasitics -ext_model_file " + PLAT + "/rcx_patterns.rules",
            "write_spef design.spef",
            "report_checks -path_delay max -format full > timing.rpt",
            "report_design_area",
            "write_def design.def",
            "puts \"TERNHDL_DONE\"",
            "exit", "");
        Files.writeString(dir.resolve("flow.tcl"), tcl);
        String or = System.getenv().getOrDefault("OPENROAD", "/extra1/mdev/tools/OpenROAD-flow-scripts/tools/install/OpenROAD/bin/openroad");
        Process pr = new ProcessBuilder(or, "-no_init", "-threads", "4", "-exit", "flow.tcl").directory(dir.toFile()).redirectErrorStream(true).start();
        String log = new String(pr.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!pr.waitFor(timeoutSec, TimeUnit.SECONDS)) pr.destroyForcibly();
        Files.writeString(dir.resolve("flow.log"), log);
        if (!log.contains("TERNHDL_DONE")) {
            String tail = log.length() > 2500 ? log.substring(log.length() - 2500) : log;
            return new Result(name, nl.cellCount(), 0, 0, 0, -1, 0, 0, Map.of(), nl, dir, tail);
        }
        double area = 0, util = 0;
        Matcher m = Pattern.compile("Design area ([0-9.]+) [uU]m?\\^2 ([0-9.]+)% utilization").matcher(log);
        if (m.find()) { area = Double.parseDouble(m.group(1)); util = Double.parseDouble(m.group(2)); }
        double wl = 0;
        Matcher w = Pattern.compile("Total wire length = ([0-9.]+) ?um").matcher(log);
        while (w.find()) wl = Double.parseDouble(w.group(1));
        int drc = 0;
        Path drcF = dir.resolve("drc.rpt");
        if (Files.exists(drcF)) for (String l : Files.readAllLines(drcF)) if (l.startsWith("violation type")) drc++;
        double sta = 0;
        Matcher t = Pattern.compile("([0-9.]+)\\s+data arrival time").matcher(Files.readString(dir.resolve("timing.rpt")));
        if (t.find()) sta = Double.parseDouble(t.group(1));
        Map<Integer, Double> caps = new HashMap<>();
        double vmid = parseSpef(dir.resolve("design.spef"), caps);
        return new Result(name, nl.cellCount(), area, util, wl, drc, sta, vmid, caps, nl, dir, null);
    }

    /** Fills net id -> total capacitance (F) for nets named n<id>; returns the vmid net's capacitance in fF. */
    static double parseSpef(Path spef, Map<Integer, Double> caps) throws Exception {
        Map<String, String> names = new HashMap<>();
        double capUnit = 1e-12, vmid = 0;
        boolean inMap = false;
        for (String l : Files.readAllLines(spef)) {
            if (l.startsWith("*C_UNIT")) { String[] t = l.split("\\s+"); capUnit = Double.parseDouble(t[1]) * (t[2].equals("FF") ? 1e-15 : t[2].equals("PF") ? 1e-12 : 1); }
            if (l.startsWith("*NAME_MAP")) { inMap = true; continue; }
            if (inMap && l.startsWith("*") && l.contains(" ") && !l.startsWith("*D_NET") && !l.startsWith("*PORTS")) { String[] t = l.split("\\s+", 2); names.put(t[0], t[1].trim()); continue; }
            if (l.startsWith("*D_NET")) {
                inMap = false;
                String[] t = l.split("\\s+");
                String nm = names.getOrDefault(t[1], t[1]);
                double c = Double.parseDouble(t[2]) * capUnit;
                if (nm.matches("n\\d+")) caps.put(Integer.parseInt(nm.substring(1)), c);
                if (nm.equals("vmid")) vmid = c * 1e15;
            }
        }
        return vmid;
    }
}
