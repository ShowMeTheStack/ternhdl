package org.ternhdl.spice;

import java.util.Locale;

/**
 * Parametric behavioural ternary device (the "ternary transistor" abstraction of this project; not a physical
 * transistor model). Levels VSS < VMID < VDD; input thresholds vthL < vthH with logistic steepness s (smaller s =
 * sharper switching, larger noise margins); drive resistance per output level (mid usually weaker); intrinsic RC
 * delay; input/output capacitance; static current while an output rests at the mid level.
 */
public record DeviceParams(double vdd, double vthL, double vthH, double s, double rN, double rM, double rP,
                           double rInt, double cInt, double cIn, double cOut, double iMid) {

    /** Default operating point: 0.9 V, thresholds at VDD/4 and 3VDD/4, mid driver 2x weaker. */
    public static DeviceParams nominal() {
        return new DeviceParams(0.9, 0.225, 0.675, 0.02, 5e3, 10e3, 5e3, 1e3, 0.5e-15, 0.5e-15, 1e-15, 1e-6);
    }

    public double vmid() { return vdd / 2; }

    public DeviceParams withS(double v) { return new DeviceParams(vdd, vthL, vthH, v, rN, rM, rP, rInt, cInt, cIn, cOut, iMid); }
    public DeviceParams withVdd(double v) { return new DeviceParams(v, v / 4, 3 * v / 4, s, rN, rM, rP, rInt, cInt, cIn, cOut, iMid); }
    public DeviceParams withRm(double v) { return new DeviceParams(vdd, vthL, vthH, s, rN, v, rP, rInt, cInt, cIn, cOut, iMid); }
    public DeviceParams withThresholds(double l, double h) { return new DeviceParams(vdd, l, h, s, rN, rM, rP, rInt, cInt, cIn, cOut, iMid); }

    String spiceParams() {
        return String.format(Locale.ROOT, ".param vdd=%g vthl=%g vthh=%g s=%g rn=%g rm=%g rp=%g rint=%g cint=%g cin=%g cout=%g imid=%g%n",
            vdd, vthL, vthH, s, rN, rM, rP, rInt, cInt, cIn, cOut, iMid);
    }
}
