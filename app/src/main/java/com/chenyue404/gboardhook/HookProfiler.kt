package com.chenyue404.gboardhook

import java.util.concurrent.atomic.AtomicLong

/**
 * Low-overhead in-process callback profiler for the VNext performance work.
 *
 * Only timing/counter metadata is retained. Clipboard text, SQL, selections and
 * arguments are never captured.
 */
object HookProfiler {
    const val APPLICATION_ATTACH_FLAGS = "application-attach-flags"
    const val PROVIDER_LEGACY_BEFORE = "provider-legacy-before"
    const val PROVIDER_LEGACY_AFTER = "provider-legacy-after"
    const val PROVIDER_BUNDLE_BEFORE = "provider-bundle-before"
    const val PROVIDER_BUNDLE_AFTER = "provider-bundle-after"
    const val SQLITE_QUERY = "sqlite-query"
    const val SQLITE_QUERY_CANCEL = "sqlite-query-cancel"
    const val SQLITE_QUERY_DISTINCT = "sqlite-query-distinct"
    const val SQLITE_RAW_QUERY = "sqlite-rawQuery"
    const val SQLITE_RAW_QUERY_CANCEL = "sqlite-rawQuery-cancel"
    const val HASHSET_COMPAT = "hashset-compat"
    const val READ_CONFIG = "read-config"

    private val orderedPaths = arrayOf(
        APPLICATION_ATTACH_FLAGS,
        PROVIDER_LEGACY_BEFORE,
        PROVIDER_LEGACY_AFTER,
        PROVIDER_BUNDLE_BEFORE,
        PROVIDER_BUNDLE_AFTER,
        SQLITE_QUERY,
        SQLITE_QUERY_CANCEL,
        SQLITE_QUERY_DISTINCT,
        SQLITE_RAW_QUERY,
        SQLITE_RAW_QUERY_CANCEL,
        HASHSET_COMPAT,
        READ_CONFIG
    )

    private class Counters {
        val invocations = AtomicLong(0)
        val totalNanos = AtomicLong(0)
        val maxNanos = AtomicLong(0)
    }

    data class Snapshot(
        val path: String,
        val invocations: Long,
        val totalNanos: Long,
        val maxNanos: Long
    )

    private val counters = orderedPaths.associateWith { Counters() }

    @JvmStatic
    fun start(): Long = System.nanoTime()

    @JvmStatic
    fun finish(path: String, startedNanos: Long) {
        recordDuration(path, System.nanoTime() - startedNanos)
    }

    internal fun recordDuration(path: String, durationNanos: Long) {
        val target = counters[path] ?: return
        val boundedDuration = durationNanos.coerceAtLeast(0)
        target.invocations.incrementAndGet()
        target.totalNanos.addAndGet(boundedDuration)

        var currentMax = target.maxNanos.get()
        while (
            boundedDuration > currentMax &&
            !target.maxNanos.compareAndSet(currentMax, boundedDuration)
        ) {
            currentMax = target.maxNanos.get()
        }
    }

    @JvmStatic
    fun reset() {
        counters.values.forEach { value ->
            value.invocations.set(0)
            value.totalNanos.set(0)
            value.maxNanos.set(0)
        }
    }

    @JvmStatic
    fun snapshot(): List<Snapshot> = orderedPaths.mapNotNull { path ->
        val value = counters[path] ?: return@mapNotNull null
        Snapshot(
            path = path,
            invocations = value.invocations.get(),
            totalNanos = value.totalNanos.get(),
            maxNanos = value.maxNanos.get()
        )
    }

    /**
     * Compact, deterministic status-channel payload. Units remain nanoseconds so
     * measurement precision is not destroyed by formatting.
     */
    @JvmStatic
    fun summary(): String = snapshot().joinToString(";") { item ->
        item.path +
            ",count=" + item.invocations +
            ",total_ns=" + item.totalNanos +
            ",max_ns=" + item.maxNanos
    }
}
