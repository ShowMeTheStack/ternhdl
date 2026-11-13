package org.ternhdl.asic;

import java.util.*;
import org.ternhdl.map.*;
import org.ternhdl.spice.*;

/** LEF, Liberty and structural Verilog views of GT cells and netlists for OpenROAD (abstract cells). */
public final class AsicViews {
    private AsicViews() { }

    static final double SITE_W = 0.19, ROW_H = 1.4;
    static final String SITE = "FreePDK45_38x28_10R_NP_162NW_34O";

    public static List<Cell> physCells() {
        List<Cell> l = new ArrayList<>();
        for (Cell c : Cell.values()) l.add(c);
        return l;
    }

    static List<String> inPins(Cell c) {
        if (c == Cell.DFF) return List.of("D", "CK");
        List<String> p = new ArrayList<>();
        for (int i = 0; i < c.arity; i++) p.add("A" + i);
        return p;
    }

    static String outPin(Cell c) { return c == Cell.DFF ? "Q" : "Y"; }

    static int sites(Cell c) { return inPins(c).size() + 3; }   // pins + output + VMID + spacing

    public static String lef() {
        StringBuilder b = new StringBuilder("VERSION 5.8 ;\nBUSBITCHARS \"[]\" ;\nDIVIDERCHAR \"/\" ;\n");
        for (Cell c : physCells()) {
            String name = "TG_" + c.name();
            double w = sites(c) * SITE_W;
            b.append(String.format(Locale.ROOT, "MACRO %s%n  CLASS CORE ;%n  ORIGIN 0 0 ;%n  FOREIGN %s 0 0 ;%n  SIZE %.3f BY %.3f ;%n  SYMMETRY X Y ;%n  SITE %s ;%n", name, name, w, ROW_H, SITE));
            List<String> pins = new ArrayList<>(inPins(c));
            pins.add(outPin(c));
            pins.add("VMID");
            for (int k = 0; k < pins.size(); k++) {
                String pn = pins.get(k);
                double x = (k + 1) * SITE_W - 0.035;
                String dir = pn.equals(outPin(c)) ? "OUTPUT" : "INPUT";
                b.append(String.format(Locale.ROOT, "  PIN %s%n    DIRECTION %s ;%n    USE %s ;%n    PORT%n      LAYER metal1 ;%n        RECT %.3f 0.400 %.3f 1.000 ;%n    END%n  END %s%n",
                    pn, dir, pn.equals("CK") ? "CLOCK" : "SIGNAL", x, x + 0.07, pn));
            }
            b.append(String.format(Locale.ROOT, "  PIN VDD%n    DIRECTION INOUT ;%n    USE POWER ;%n    SHAPE ABUTMENT ;%n    PORT%n      LAYER metal1 ;%n        RECT 0.000 1.315 %.3f 1.485 ;%n    END%n  END VDD%n", w));
            b.append(String.format(Locale.ROOT, "  PIN VSS%n    DIRECTION INOUT ;%n    USE GROUND ;%n    SHAPE ABUTMENT ;%n    PORT%n      LAYER metal1 ;%n        RECT 0.000 -0.085 %.3f 0.085 ;%n    END%n  END VSS%n", w));
            b.append("END ").append(name).append("\n\n");
        }
        b.append("END LIBRARY\n");
        return b.toString();
    }

    /** Liberty view: worst characterized delay per input pin versus load (ps, fF). DFF timing is a placeholder. */
    public static String liberty(TimingLib lib, DeviceParams p) {
        double cin = p.cIn() * 1e15;
        double[] loads = {cin, 2 * cin, 4 * cin, 8 * cin};
        String idx = String.format(Locale.ROOT, "\"%.3f, %.3f, %.3f, %.3f\"", loads[0], loads[1], loads[2], loads[3]);
        StringBuilder b = new StringBuilder();
        b.append("library (ternhdl_gt) {\n  delay_model : table_lookup ;\n  time_unit : \"1ps\" ;\n  voltage_unit : \"1V\" ;\n  current_unit : \"1uA\" ;\n");
        b.append("  pulling_resistance_unit : \"1kohm\" ;\n  leakage_power_unit : \"1nW\" ;\n  capacitive_load_unit (1, ff) ;\n");
        b.append(String.format(Locale.ROOT, "  nom_process : 1 ;\n  nom_temperature : 25 ;\n  nom_voltage : %.2f ;%n", p.vdd()));
        b.append("  default_max_transition : 500 ;\n  slew_lower_threshold_pct_rise : 25 ;\n  slew_upper_threshold_pct_rise : 75 ;\n  slew_lower_threshold_pct_fall : 25 ;\n  slew_upper_threshold_pct_fall : 75 ;\n");
        b.append("  input_threshold_pct_rise : 50 ;\n  input_threshold_pct_fall : 50 ;\n  output_threshold_pct_rise : 50 ;\n  output_threshold_pct_fall : 50 ;\n");
        b.append("  lu_table_template (load4) {\n    variable_1 : total_output_net_capacitance ;\n    index_1 (").append(idx).append(") ;\n  }\n");
        for (Cell c : physCells()) {
            double area = sites(c) * SITE_W * ROW_H;
            b.append(String.format(Locale.ROOT, "  cell (TG_%s) {%n    area : %.4f ;%n    dont_touch : true ;%n    dont_use : true ;%n", c.name(), area));
            b.append("    pin (VMID) { direction : input ; capacitance : 0 ; }\n");
            for (String ip : inPins(c)) {
                b.append(String.format(Locale.ROOT, "    pin (%s) { direction : input ; capacitance : %.3f ;%s }%n", ip, cin, ip.equals("CK") ? " clock : true ;" : ""));
            }
            b.append("    pin (").append(outPin(c)).append(") {\n      direction : output ;\n      max_capacitance : 50 ;\n");
            if (c == Cell.DFF) {
                b.append("      timing () {\n        related_pin : \"CK\" ;\n        timing_type : rising_edge ;\n        cell_rise (scalar) { values (\"20\") ; }\n        cell_fall (scalar) { values (\"20\") ; }\n        rise_transition (scalar) { values (\"10\") ; }\n        fall_transition (scalar) { values (\"10\") ; }\n      }\n");
            } else if (!c.isTie()) {
                for (int pin = 0; pin < c.arity; pin++) {
                    StringBuilder vals = new StringBuilder("\"");
                    for (int k = 0; k < 4; k++) vals.append(k > 0 ? ", " : "").append(String.format(Locale.ROOT, "%.2f", lib.worst(c, loads[k] / cin) * 1e12 + lib.meanPassDwell * 1e12));
                    vals.append("\"");
                    b.append("      timing () {\n        related_pin : \"A").append(pin).append("\" ;\n        timing_sense : non_unate ;\n");
                    for (String t : new String[]{"cell_rise", "cell_fall", "rise_transition", "fall_transition"})
                        b.append("        ").append(t).append(" (load4) { values (").append(vals).append(") ; }\n");
                    b.append("      }\n");
                }
            }
            b.append("    }\n");
            if (c == Cell.DFF) b.append("    ff (IQ, IQN) { clocked_on : \"CK\" ; next_state : \"D\" ; }\n");
            b.append("  }\n");
        }
        b.append("}\n");
        return b.toString();
    }

    /** Structural Verilog: every net is n<id>; ports are bit-blasted as <port>_<digit>; vmid and clk are top ports. */
    public static String verilog(Netlist nl, String top) {
        StringBuilder b = new StringBuilder();
        List<String> ports = new ArrayList<>(), decl = new ArrayList<>();
        boolean seq = nl.dffCount() > 0;
        ports.add("vmid"); decl.add("  input vmid;");
        if (seq) { ports.add("clk"); decl.add("  input clk;"); }
        for (var en : nl.inputs().entrySet()) for (int i = 0; i < en.getValue().length; i++) { String pn = en.getKey() + "_" + i; ports.add(pn); decl.add("  input " + pn + ";"); }
        for (var en : nl.outputs().entrySet()) for (int i = 0; i < en.getValue().length; i++) { String pn = en.getKey() + "_" + i; ports.add(pn); decl.add("  output " + pn + ";"); }
        b.append("module ").append(top).append(" (").append(String.join(", ", ports)).append(");\n");
        for (String s : decl) b.append(s).append('\n');
        for (int n = 0; n < nl.netCount(); n++) b.append("  wire n").append(n).append(";\n");
        for (var en : nl.inputs().entrySet()) for (int i = 0; i < en.getValue().length; i++) b.append("  assign n").append(en.getValue()[i]).append(" = ").append(en.getKey()).append("_").append(i).append(";\n");
        int k = 0;
        for (Netlist.Inst in : nl.insts()) {
            b.append("  TG_").append(in.cell.name()).append(" u").append(k++).append(" (");
            List<String> conn = new ArrayList<>();
            if (in.cell == Cell.DFF) { conn.add(".D(n" + in.in[0] + ")"); conn.add(".CK(clk)"); conn.add(".Q(n" + in.out + ")"); }
            else { for (int j = 0; j < in.in.length; j++) conn.add(".A" + j + "(n" + in.in[j] + ")"); conn.add(".Y(n" + in.out + ")"); }
            conn.add(".VMID(vmid)");
            b.append(String.join(", ", conn)).append(");\n");
        }
        for (var en : nl.outputs().entrySet()) for (int i = 0; i < en.getValue().length; i++) b.append("  assign ").append(en.getKey()).append("_").append(i).append(" = n").append(en.getValue()[i]).append(";\n");
        b.append("endmodule\n");
        return b.toString();
    }
}
