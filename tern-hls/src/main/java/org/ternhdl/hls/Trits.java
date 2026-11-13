package org.ternhdl.hls;

import java.lang.annotation.*;

/** Width in trits of a scalar (or of the elements of an array parameter). Default 27. */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.PARAMETER, ElementType.LOCAL_VARIABLE})
public @interface Trits { int value(); }
