package com.schoolhelp.app

import org.junit.Assert.*
import org.junit.Test

class CrashDiagnosticsTest {
    @Test fun diagnosticStackOmitsAllExceptionMessages() {
        val failure=IllegalStateException("sk-or-v1-private-key private answer",java.io.IOException("private question and photo"))
        val trace=CrashDiagnostics.safeTrace(failure)
        assertTrue(trace.contains("IllegalStateException"))
        assertTrue(trace.contains("IOException"))
        assertTrue(trace.contains("CrashDiagnosticsTest"))
        assertFalse(trace.contains("sk-or"))
        assertFalse(trace.contains("private"))
    }
}
