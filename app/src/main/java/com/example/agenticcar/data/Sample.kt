package com.example.agenticcar.data

/** One point of a trend; [t] is epoch milliseconds, or a virtual time once gaps are collapsed. NaN breaks the line. */
data class Sample(val t: Long, val v: Double)
