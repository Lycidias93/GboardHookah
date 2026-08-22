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

    fun hookReady(name: String): Boolean = synchronized(hookStates) {
        val changed = hookStates.remove("$name=error")
        hookStates.add("$name=ready") || changed
    }

    fun hookError(name: String, throwable: Throwable): Boolean {
        val stateChanged = synchronized(hookStates) {
            val removed = hookStates.remove("$name=ready")
            hookStates.add("$name=error") || removed
        }
        val detail = throwable.javaClass.simpleName +
            (throwable.message?.let { ": $it" } ?: "")
        val message = "$name: $detail"
        val errorChanged = lastError != message
        lastError = message
        return stateChanged || errorChanged
    }

    fun observe(path: String, provesCapacityHandling: Boolean = false): Boolean {
        val pathChanged = observedPaths.add(path)
        val proofChanged = provesCapacityHandling && !capacityProof
        if (provesCapacityHandling) {
            capacityProof = true
        }
        return pathChanged || proofChanged
    }

    fun callbackError(path: String, throwable: Throwable): Boolean {
        val detail = throwable.javaClass.simpleName +
            (throwable.message?.let { ": $it" } ?: "")
        val message = "$path callback: $detail"
        val changed = lastError != message
        lastError = message
        return changed
    }

    fun clearTransientError(prefix: String): Boolean {
        if (lastError.startsWith(prefix)) {
            lastError = ""
            return true
        }
        return false
    }

    fun hookSummary(): String = synchronized(hookStates) {
        hookStates.joinToString(", ")
    }

    fun observedSummary(): String = synchronized(observedPaths) {
        observedPaths.joinToString(", ")
    }
}
