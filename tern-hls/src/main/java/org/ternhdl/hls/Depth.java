package org.ternhdl.hls;

import java.lang.annotation.*;

/** Address width in trits of an array parameter: the memory has 3^value entries (Java indices 0 .. 3^value - 1). */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.PARAMETER)
public @interface Depth { int value(); }
