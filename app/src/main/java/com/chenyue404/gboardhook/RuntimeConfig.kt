package com.chenyue404.gboardhook

import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * Runtime configuration owned by the injected Gboard process.
 *
 * LSPosed's legacy XSharedPreferences bridge can expose stale/default values on newer
 * Android builds. Authenticated status requests therefore carry the module app's current
 * configuration into Gboard. The injected process persists that configuration in Gboard's
 * own private storage and all functional hooks read from this object.
 */
object RuntimeConfig {
    private const val PREF_FILE = "gboardhookah_runtime_config"
    private const val KEY_MANUAL_CAPACITY = "manual_capacity"
    private const val KEY_RETENTION_MS = "retention_ms"
    private const val KEY_IGNORE_PACKAGE_LIMIT = "ignore_package_limit"
    private const val KEY_SYNC_ENABLED = "sync_enabled"
    private const val KEY_DEBUG_LOGGING = "debug_logging"

    @Volatile
    var manualCapacity: Int = PluginEntry.DEFAULT_NUM
        private set

    @Volatile
    var retentionMs: Long = PluginEntry.DEFAULT_TIME
        private set

    @Volatile
    var ignorePackageLimit: Boolean = false
        private set

    @Volatile
    var syncEnabled: Boolean = PluginEntry.DEFAULT_SYNC_ANDROID_CLIPBOARD_CAPACITY
        private set

    @Volatile
    var debugLogging: Boolean = false
        private set

    @Volatile
    var source: String = "defaults"
        private set

    fun effectiveCapacity(): Int =
        if (syncEnabled) PluginEntry.AUTO_CAPACITY else manualCapacity

    fun load(context: Context) {
        val pref = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        if (!pref.contains(KEY_RETENTION_MS)) return
        manualCapacity = pref.getInt(KEY_MANUAL_CAPACITY, PluginEntry.DEFAULT_NUM)
            .coerceAtLeast(1)
        retentionMs = pref.getLong(KEY_RETENTION_MS, PluginEntry.DEFAULT_TIME)
            .coerceAtLeast(0L)
        ignorePackageLimit = pref.getBoolean(KEY_IGNORE_PACKAGE_LIMIT, false)
        syncEnabled = pref.getBoolean(
            KEY_SYNC_ENABLED,
            PluginEntry.DEFAULT_SYNC_ANDROID_CLIPBOARD_CAPACITY
        )
        debugLogging = pref.getBoolean(KEY_DEBUG_LOGGING, false)
        source = "gboard-cache"
    }

    fun applyAuthenticatedRequest(context: Context, intent: Intent): Boolean {
        if (!intent.getBooleanExtra(StatusProtocol.EXTRA_CONFIG_PRESENT, false)) return false

        manualCapacity = intent.getIntExtra(
            StatusProtocol.EXTRA_CONFIG_MANUAL_CAPACITY,
            PluginEntry.DEFAULT_NUM
        ).coerceAtLeast(1)
        retentionMs = intent.getLongExtra(
            StatusProtocol.EXTRA_CONFIG_RETENTION_MS,
            PluginEntry.DEFAULT_TIME
        ).coerceAtLeast(0L)
        ignorePackageLimit = intent.getBooleanExtra(
            StatusProtocol.EXTRA_CONFIG_IGNORE_PACKAGE_LIMIT,
            false
        )
        syncEnabled = intent.getBooleanExtra(
            StatusProtocol.EXTRA_CONFIG_SYNC_ENABLED,
            PluginEntry.DEFAULT_SYNC_ANDROID_CLIPBOARD_CAPACITY
        )
        debugLogging = intent.getBooleanExtra(
            StatusProtocol.EXTRA_CONFIG_DEBUG_LOGGING,
            false
        )

        val persisted = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_MANUAL_CAPACITY, manualCapacity)
            .putLong(KEY_RETENTION_MS, retentionMs)
            .putBoolean(KEY_IGNORE_PACKAGE_LIMIT, ignorePackageLimit)
            .putBoolean(KEY_SYNC_ENABLED, syncEnabled)
            .putBoolean(KEY_DEBUG_LOGGING, debugLogging)
            .commit()
        source = if (persisted) "authenticated-request" else "authenticated-request-memory-only"
        return true
    }

    fun appendToStatus(bundle: Bundle) {
        bundle.putBoolean(StatusProtocol.EXTRA_SYNC_ENABLED, syncEnabled)
        bundle.putInt(StatusProtocol.EXTRA_CONFIGURED_CAPACITY, manualCapacity)
        bundle.putInt(StatusProtocol.EXTRA_EFFECTIVE_CAPACITY, effectiveCapacity())
        bundle.putLong(StatusProtocol.EXTRA_RETENTION_MS, retentionMs)
        bundle.putBoolean(StatusProtocol.EXTRA_DEBUG_LOGGING, debugLogging)
        bundle.putString(StatusProtocol.EXTRA_CONFIG_SOURCE, source)
    }
}
