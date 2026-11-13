package org.ternhdl.fpga;

import java.util.*;
import org.ternhdl.core.*;
import org.ternhdl.encode.*;

/**
 * ZedBoard harness: the encoded twin of a design behind an AXI-Lite slave (at 0x43C00000 on M_AXI_GP0).
 * Register map (byte addresses): 0x000 write any value = one DUT clock enable pulse; 0x004 read = step count;
 * 0x100 + 4k = input word k (write); 0x200 + 4k = output word k (read). Ports are packed little-endian into 32-bit
 * words in declaration order (each port's bits contiguous, a port never straddles words if it fits in 32 bits).
 */
public final class BoardSoc {
    public record Field(String port, int word, int lsb, int width) { }
    public record Layout(List<Field> in, List<Field> out, int inWords, int outWords) { }

    private BoardSoc() { }

    public static Layout layout(Design d) {
        d.finish();
        return new Layout(pack(d, d.inputs()), pack(d, d.outputs()), words(d, d.inputs()), words(d, d.outputs()));
    }

    static int width(Design d, int node) { Type t = d.node(node).type(); return t.isBit() ? 1 : 2 * t.width(); }

    /** Ports packed into one flat little-endian bit vector (a port may cross 32-bit word boundaries). */
    static List<Field> pack(Design d, Map<String, Integer> ports) {
        List<Field> f = new ArrayList<>();
        int bit = 0;
        for (var en : ports.entrySet()) {
            int w = width(d, en.getValue());
            f.add(new Field(en.getKey(), bit / 32, bit % 32, w));   // word/lsb = start position; bits continue upward
            bit += w;
        }
        return f;
    }

    static int words(Design d, Map<String, Integer> ports) {
        int bits = 0;
        for (var en : ports.entrySet()) bits += width(d, en.getValue());
        return Math.max(1, (bits + 31) / 32);
    }

    /** Global bit offset of a field in the flat vector. */
    public static int offset(Field f) { return 32 * f.word() + f.lsb(); }

    /** Twin with a clock-enable port (every register updates only when ce is 1). */
    public static String twinWithEnable(Design d, Encoding enc) {
        String sv = SvEmitter.emit(d, new SvEmitter.Options(enc, false, false));
        if (!sv.contains("input logic clk")) sv = sv.replaceFirst("module " + d.name + " \\(\n", "module " + d.name + " (\n  input logic clk,\n");
        sv = sv.replaceFirst("input logic clk,", "input logic clk,\n  input logic ce,");
        sv = sv.replace("always_ff @(posedge clk) t_r", "always_ff @(posedge clk) if (ce) t_r");
        sv = sv.replace("always_ff @(posedge clk) begin : t_mw", "always_ff @(posedge clk) if (ce) begin : t_mw");
        return sv;
    }

    /** AXI-Lite slave top (SystemVerilog), module name board_top. */
    public static String top(Design d, Layout l) {
        StringBuilder s = new StringBuilder();
        s.append("module board_top (\n  input logic aclk, input logic aresetn,\n")
         .append("  input logic [17:0] s_axi_awaddr, input logic s_axi_awvalid, output logic s_axi_awready,\n")
         .append("  input logic [31:0] s_axi_wdata, input logic [3:0] s_axi_wstrb, input logic s_axi_wvalid, output logic s_axi_wready,\n")
         .append("  output logic [1:0] s_axi_bresp, output logic s_axi_bvalid, input logic s_axi_bready,\n")
         .append("  input logic [17:0] s_axi_araddr, input logic s_axi_arvalid, output logic s_axi_arready,\n")
         .append("  output logic [31:0] s_axi_rdata, output logic [1:0] s_axi_rresp, output logic s_axi_rvalid, input logic s_axi_rready,\n")
         .append("  output logic [7:0] leds);\n");
        s.append("  logic [31:0] inw [0:").append(l.inWords() - 1).append("];\n");
        s.append("  logic ce;\n  logic [31:0] steps, runcnt;\n");
        s.append("  logic [" + (32 * l.inWords() - 1) + ":0] inflat;\n  logic [" + (32 * l.outWords() - 1) + ":0] outflat;\n");
        StringBuilder cat = new StringBuilder("{");
        for (int k = l.inWords() - 1; k >= 0; k--) cat.append("inw[").append(k).append("]").append(k > 0 ? ", " : "}");
        s.append("  assign inflat = ").append(cat).append(";\n");
        // write channel: accept address and data together
        s.append("  logic [17:0] awa;\n  logic aw_ok, w_ok;\n");
        s.append("  assign s_axi_awready = !aw_ok;\n  assign s_axi_wready = !w_ok;\n  assign s_axi_bresp = 2'b00;\n  assign s_axi_rresp = 2'b00;\n");
        s.append("  always_ff @(posedge aclk) begin\n    ce <= 1'b0;\n    if (runcnt != 0) begin ce <= 1'b1; runcnt <= runcnt - 1; steps <= steps + 1; end\n    if (!aresetn) begin aw_ok <= 0; w_ok <= 0; s_axi_bvalid <= 0; steps <= 0; runcnt <= 0;");
        for (int k = 0; k < l.inWords(); k++) s.append(" inw[").append(k).append("] <= 0;");
        s.append(" end else begin\n");
        s.append("      if (s_axi_awvalid && !aw_ok) begin aw_ok <= 1; awa <= s_axi_awaddr; end\n");
        s.append("      if (s_axi_wvalid && !w_ok) w_ok <= 1;\n");
        s.append("      if (aw_ok && w_ok && !s_axi_bvalid) begin\n");
        s.append("        if (awa[11:0] == 12'h000) begin ce <= 1'b1; steps <= steps + 1; end\n");
        s.append("        if (awa[11:0] == 12'h008) runcnt <= s_axi_wdata;\n");
        for (int k = 0; k < l.inWords(); k++) s.append("        if (awa[11:0] == 12'h").append(String.format("%03x", 0x100 + 4 * k)).append(") inw[").append(k).append("] <= s_axi_wdata;\n");
        s.append("        s_axi_bvalid <= 1;\n      end\n");
        s.append("      if (s_axi_bvalid && s_axi_bready) begin s_axi_bvalid <= 0; aw_ok <= 0; w_ok <= 0; end\n    end\n  end\n");
        // read channel
        s.append("  always_ff @(posedge aclk) begin\n    if (!aresetn) begin s_axi_rvalid <= 0; s_axi_arready <= 0; end else begin\n");
        s.append("      s_axi_arready <= !s_axi_rvalid && !s_axi_arready;\n");
        s.append("      if (s_axi_arvalid && s_axi_arready) begin\n        s_axi_rvalid <= 1;\n        case (s_axi_araddr[11:0])\n");
        s.append("          12'h004: s_axi_rdata <= steps;\n          12'h00c: s_axi_rdata <= runcnt;\n");
        for (int k = 0; k < l.outWords(); k++) s.append("          12'h").append(String.format("%03x", 0x200 + 4 * k)).append(": s_axi_rdata <= outflat[").append(32 * k + 31).append(":").append(32 * k).append("];\n");
        s.append("          default: s_axi_rdata <= 32'hdeadbeef;\n        endcase\n      end else if (s_axi_rvalid && s_axi_rready) s_axi_rvalid <= 0;\n    end\n  end\n");
        // DUT
        List<String> conn = new ArrayList<>(List.of(".clk(aclk)", ".ce(ce)"));
        for (Field f : l.in()) conn.add("." + f.port() + "(inflat[" + (offset(f) + f.width() - 1) + ":" + offset(f) + "])");
        for (Field f : l.out()) {
            s.append("  logic [").append(f.width() - 1).append(":0] o_").append(f.port()).append(";\n");
            conn.add("." + f.port() + "(o_" + f.port() + ")");
        }
        s.append("  ").append(d.name).append(" dut (").append(String.join(", ", conn)).append(");\n");
        s.append("  always_comb begin\n    outflat = '0;\n");
        for (Field f : l.out()) s.append("    outflat[").append(offset(f) + f.width() - 1).append(":").append(offset(f)).append("] = o_").append(f.port()).append(";\n");
        s.append("  end\n  assign leds = steps[7:0];\nendmodule\n");
        return s.toString();
    }

    public static String verilogWrapper() {
        return "module board_wrap (\n  input wire aclk, input wire aresetn,\n"
            + "  input wire [17:0] s_axi_awaddr, input wire s_axi_awvalid, output wire s_axi_awready,\n"
            + "  input wire [31:0] s_axi_wdata, input wire [3:0] s_axi_wstrb, input wire s_axi_wvalid, output wire s_axi_wready,\n"
            + "  output wire [1:0] s_axi_bresp, output wire s_axi_bvalid, input wire s_axi_bready,\n"
            + "  input wire [17:0] s_axi_araddr, input wire s_axi_arvalid, output wire s_axi_arready,\n"
            + "  output wire [31:0] s_axi_rdata, output wire [1:0] s_axi_rresp, output wire s_axi_rvalid, input wire s_axi_rready,\n"
            + "  output wire [7:0] leds);\n"
            + "  board_top t (.aclk(aclk), .aresetn(aresetn), .s_axi_awaddr(s_axi_awaddr), .s_axi_awvalid(s_axi_awvalid), .s_axi_awready(s_axi_awready),\n"
            + "    .s_axi_wdata(s_axi_wdata), .s_axi_wstrb(s_axi_wstrb), .s_axi_wvalid(s_axi_wvalid), .s_axi_wready(s_axi_wready),\n"
            + "    .s_axi_bresp(s_axi_bresp), .s_axi_bvalid(s_axi_bvalid), .s_axi_bready(s_axi_bready),\n"
            + "    .s_axi_araddr(s_axi_araddr), .s_axi_arvalid(s_axi_arvalid), .s_axi_arready(s_axi_arready),\n"
            + "    .s_axi_rdata(s_axi_rdata), .s_axi_rresp(s_axi_rresp), .s_axi_rvalid(s_axi_rvalid), .s_axi_rready(s_axi_rready), .leds(leds));\nendmodule\n";
    }

    /** Vivado batch script: PS7 (ZedBoard preset) + board_wrap on M_AXI_GP0 at 0x43C00000, LEDs; writes soc.bit/soc.xsa. */
    public static String vivadoTcl(int fclkMhz) {
        return String.join("\n",
            "set_param general.maxThreads 8",
            "create_project soc proj -part xc7z020clg484-1 -force",
            "set_property board_part em.avnet.com:zed:part0:1.4 [current_project]",
            "add_files [list dut.sv board_top.sv board_wrap.v]",
            "set_property file_type SystemVerilog [get_files dut.sv]",
            "set_property file_type SystemVerilog [get_files board_top.sv]",
            "update_compile_order -fileset sources_1",
            "create_bd_design soc",
            "set ps [create_bd_cell -type ip -vlnv xilinx.com:ip:processing_system7:5.5 ps7]",
            "apply_bd_automation -rule xilinx.com:bd_rule:processing_system7 -config {make_external \"FIXED_IO, DDR\" apply_board_preset \"1\" Master \"Disable\" Slave \"Disable\"} $ps",
            "set_property -dict [list CONFIG.PCW_USE_M_AXI_GP0 {1} CONFIG.PCW_FPGA0_PERIPHERAL_FREQMHZ " + fclkMhz + "] $ps",
            "set b [create_bd_cell -type module -reference board_wrap tern]",
            "apply_bd_automation -rule xilinx.com:bd_rule:axi4 -config {Master \"/ps7/M_AXI_GP0\" Clk \"Auto\"} [get_bd_intf_pins tern/s_axi]",
            "make_bd_pins_external [get_bd_pins tern/leds]",
            "set_property name leds [get_bd_ports leds_0]",
            "set seg [get_bd_addr_segs -of_objects [get_bd_addr_spaces ps7/Data] -filter {NAME =~ \"*tern*\"}]",
            "set_property range 256K $seg",
            "set_property offset 0x43C00000 $seg",
            "validate_bd_design", "save_bd_design",
            "make_wrapper -files [get_files soc.bd] -top",
            "add_files -norecurse [glob proj/soc.srcs/sources_1/bd/soc/hdl/soc_wrapper.v]",
            "set_property top soc_wrapper [current_fileset]",
            "set xdc [open leds.xdc w]",
            "foreach {i pin} {0 T22 1 T21 2 U22 3 U21 4 V22 5 W22 6 U19 7 U14} { puts $xdc \"set_property -dict {PACKAGE_PIN $pin IOSTANDARD LVCMOS33} \\[get_ports {leds\\[$i\\]}\\]\" }",
            "close $xdc",
            "add_files -fileset constrs_1 leds.xdc",
            "launch_runs synth_1 -jobs 8", "wait_on_run synth_1",
            "launch_runs impl_1 -to_step write_bitstream -jobs 8", "wait_on_run impl_1",
            "open_run impl_1",
            "report_utilization -hierarchical -hierarchical_depth 3 -file utilization_hier.rpt",
            "report_timing_summary -file timing.rpt",
            "file copy -force [glob proj/soc.runs/impl_1/soc_wrapper.bit] soc.bit",
            "write_hw_platform -fixed -include_bit -force soc.xsa",
            "puts \"WNS [get_property SLACK [get_timing_paths -max_paths 1 -nworst 1 -setup]]\"",
            "puts \"TERN_BUILD_DONE\"", "");
    }
}
