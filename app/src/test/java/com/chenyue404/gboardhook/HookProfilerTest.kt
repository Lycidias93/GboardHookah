package com.chenyue404.gboardhook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HookProfilerTest {
    @Before
    fun resetProfiler() {
        HookProfiler.reset()
    }

    @Test
    fun recordsInvocationTotalAndMaximumDeterministically() {
        HookProfiler.recordDuration(HookProfiler.SQLITE_QUERY, 10)
        HookProfiler.recordDuration(HookProfiler.SQLITE_QUERY, 30)
        HookProfiler.recordDuration(HookProfiler.SQLITE_QUERY, 20)

        val snapshot = HookProfiler.snapshot()
            .single { it.path == HookProfiler.SQLITE_QUERY }

        assertEquals(3L, snapshot.invocations)
        assertEquals(60L, snapshot.totalNanos)
        assertEquals(30L, snapshot.maxNanos)
    }

    @Test
    fun negativeDurationsAreClampedAndUnknownPathsAreIgnored() {
        HookProfiler.recordDuration(HookProfiler.READ_CONFIG, -5)
        HookProfiler.recordDuration("unknown-path", 999)

        val readConfig = HookProfiler.snapshot()
            .single { it.path == HookProfiler.READ_CONFIG }

        assertEquals(1L, readConfig.invocations)
        assertEquals(0L, readConfig.totalNanos)
        assertEquals(0L, readConfig.maxNanos)
        assertTrue(HookProfiler.snapshot().none { it.path == "unknown-path" })
    }

    @Test
    fun resetClearsAllCounters() {
        HookProfiler.recordDuration(HookProfiler.HASHSET_COMPAT, 42)
        HookProfiler.reset()

        HookProfiler.snapshot().forEach { item ->
            assertEquals(0L, item.invocations)
            assertEquals(0L, item.totalNanos)
            assertEquals(0L, item.maxNanos)
        }
    }

    @Test
    fun summaryIsStableAndContainsNoRuntimeArguments() {
        HookProfiler.recordDuration(HookProfiler.PROVIDER_LEGACY_BEFORE, 123)
        val summary = HookProfiler.summary()

        assertTrue(
            summary.contains(
                "provider-legacy-before,count=1,total_ns=123,max_ns=123"
            )
        )
        assertTrue(summary.startsWith("application-attach-flags,count=0"))
    }
}
