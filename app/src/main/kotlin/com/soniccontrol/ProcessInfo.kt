package com.soniccontrol

import android.os.Process
import android.os.SystemClock
import java.util.UUID

/**
 * Identifies one run of the app process in diagnostics (TASK 010): a random id made at start, never derived from the device
 * or the user, and the wall-clock time the process started.
 */
object ProcessInfo {
    val sessionId: String = UUID.randomUUID().toString().substring(0, 8)

    fun startedAtMillis(): Long = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime())
}
