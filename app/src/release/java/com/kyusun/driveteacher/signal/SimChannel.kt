package com.kyusun.driveteacher.signal

/** Release builds have no simulator channel (PLAN 9절: ③ is debug-only); the car's signals are all there is. */
@Suppress("UNUSED_PARAMETER")
internal fun openSimChannel(onLine: (String) -> Unit): AutoCloseable? = null
