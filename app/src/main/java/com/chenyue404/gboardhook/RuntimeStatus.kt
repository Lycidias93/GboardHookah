package com.chenyue404.gboardhook

import java.util.Collections

/**
 * In-process telemetry shared by the primary clipboard hooks and the status channel.
 * This deliberately reports only hooks/callbacks that actually ran in the injected
 * Gboard process; the settings UI never guesses runtime health from LSPosed config.
 */
object RuntimeStatus {
    private val hookStates = Collections.synchronizedSet(linkedSetOf<String>())
    private val observedPaths = Collections.synchronizedSet(linkedSetOf<String>())

    @Volatile
    var capacityProof: Boolean = false
        private set

    @Volatile
    var lastError: String = ""
        private set

    fun reset() {
        synchronized(hookStates) { hookStates.clear() }
        synchronized(observedPaths) { observedPaths.clear() }
        capacityProof = false
        lastError = ""
    }

    fun hookReady(name: String) {
        synchronized(hookStates) {
            hookStates.remove("$name=error")
            hookStates.add("$name=ready")
        }
    }

    fun hookError(name: String, throwable: Throwable) {
        synchronized(hookStates) {
            hookStates.remove("$name=ready")
            hookStates.add("$name=error")
        }
        val detail = throwable.javaClass.simpleName +
            (throwable.message?.let { ": $it" } ?: "")
        lastError = "$name: $detail"
    }

    fun observe(path: String, provesCapacityHandling: Boolean = false) {
        observedPaths.add(path)
        if (provesCapacityHandling) {
            capacityProof = true
        }
    }

    fun callbackError(path: String, throwable: Throwable) {
        val detail = throwable.javaClass.simpleName +
            (throwable.message?.let { ": $it" } ?: "")
        lastError = "$path callback: $detail"
    }

    fun clearTransientError(prefix: String) {
        if (lastError.startsWith(prefix)) {
            lastError = ""
        }
    }

    fun hookSummary(): String = synchronized(hookStates) {
        hookStates.joinToString(", ")
    }

    fun observedSummary(): String = synchronized(observedPaths) {
        observedPaths.joinToString(", ")
    }
}
