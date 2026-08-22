package com.chenyue404.gboardhook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences

/**
 * App-side endpoint for authenticated runtime status pushed from the injected Gboard
 * process. A manifest receiver avoids relying on a lifecycle callback inside Gboard.
 */
class RuntimeStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != StatusProtocol.ACTION_PUSH &&
            intent.action != StatusProtocol.ACTION_REQUEST
        ) {
            return
        }

        val pref = context.getSharedPreferences(PluginEntry.SP_FILE_NAME, Context.MODE_PRIVATE)
        val suppliedToken = intent.getStringExtra(StatusProtocol.EXTRA_TOKEN) ?: return
        val expectedToken = pref.getString(StatusProtocol.PREF_TOKEN, null) ?: return
        if (suppliedToken != expectedToken) {
            return
        }

        when (intent.action) {
            StatusProtocol.ACTION_PUSH -> {
                persistSnapshot(pref, intent)
                sendSnapshot(context, pref, suppliedToken)
            }
            StatusProtocol.ACTION_REQUEST -> {
                if (pref.getBoolean(StatusProtocol.PREF_SNAPSHOT_PRESENT, false)) {
                    sendSnapshot(context, pref, suppliedToken)
                }
            }
        }
    }

    private fun persistSnapshot(pref: SharedPreferences, source: Intent) {
        pref.edit()
            .putBoolean(StatusProtocol.PREF_SNAPSHOT_PRESENT, true)
            .putString(
                StatusProtocol.EXTRA_MODULE_VERSION,
                source.getStringExtra(StatusProtocol.EXTRA_MODULE_VERSION).orEmpty()
            )
            .putString(
                StatusProtocol.EXTRA_GBOARD_VERSION_NAME,
                source.getStringExtra(StatusProtocol.EXTRA_GBOARD_VERSION_NAME).orEmpty()
            )
            .putLong(
                StatusProtocol.EXTRA_GBOARD_VERSION_CODE,
                source.getLongExtra(StatusProtocol.EXTRA_GBOARD_VERSION_CODE, -1L)
            )
            .putString(
                StatusProtocol.EXTRA_GBOARD_PACKAGE,
                source.getStringExtra(StatusProtocol.EXTRA_GBOARD_PACKAGE).orEmpty()
            )
            .putString(
                StatusProtocol.EXTRA_PROCESS_NAME,
                source.getStringExtra(StatusProtocol.EXTRA_PROCESS_NAME).orEmpty()
            )
            .putBoolean(
                StatusProtocol.EXTRA_SYNC_ENABLED,
                source.getBooleanExtra(StatusProtocol.EXTRA_SYNC_ENABLED, false)
            )
            .putInt(
                StatusProtocol.EXTRA_CONFIGURED_CAPACITY,
                source.getIntExtra(
                    StatusProtocol.EXTRA_CONFIGURED_CAPACITY,
                    PluginEntry.DEFAULT_NUM
                )
            )
            .putInt(
                StatusProtocol.EXTRA_EFFECTIVE_CAPACITY,
                source.getIntExtra(
                    StatusProtocol.EXTRA_EFFECTIVE_CAPACITY,
                    PluginEntry.DEFAULT_NUM
                )
            )
            .putLong(
                StatusProtocol.EXTRA_RETENTION_MS,
                source.getLongExtra(StatusProtocol.EXTRA_RETENTION_MS, PluginEntry.DEFAULT_TIME)
            )
            .putBoolean(
                StatusProtocol.EXTRA_DEBUG_LOGGING,
                source.getBooleanExtra(StatusProtocol.EXTRA_DEBUG_LOGGING, false)
            )
            .putBoolean(
                StatusProtocol.EXTRA_PRIMARY_CLASS_PRESENT,
                source.getBooleanExtra(StatusProtocol.EXTRA_PRIMARY_CLASS_PRESENT, false)
            )
            .putString(
                StatusProtocol.EXTRA_WATCHERS,
                source.getStringExtra(StatusProtocol.EXTRA_WATCHERS).orEmpty()
            )
            .putString(
                StatusProtocol.EXTRA_OBSERVED_PATHS,
                source.getStringExtra(StatusProtocol.EXTRA_OBSERVED_PATHS).orEmpty()
            )
            .putBoolean(
                StatusProtocol.EXTRA_REWRITE_PROOF,
                source.getBooleanExtra(StatusProtocol.EXTRA_REWRITE_PROOF, false)
            )
            .putString(
                StatusProtocol.EXTRA_LAST_ERROR,
                source.getStringExtra(StatusProtocol.EXTRA_LAST_ERROR).orEmpty()
            )
            .putLong(
                StatusProtocol.EXTRA_TIMESTAMP_MS,
                source.getLongExtra(StatusProtocol.EXTRA_TIMESTAMP_MS, System.currentTimeMillis())
            )
            .apply()
    }

    private fun sendSnapshot(context: Context, pref: SharedPreferences, token: String) {
        val response = Intent(StatusProtocol.ACTION_RESPONSE)
            .setPackage(BuildConfig.APPLICATION_ID)
            .putExtra(StatusProtocol.EXTRA_TOKEN, token)
            .putExtra(
                StatusProtocol.EXTRA_MODULE_VERSION,
                pref.getString(StatusProtocol.EXTRA_MODULE_VERSION, "").orEmpty()
            )
            .putExtra(
                StatusProtocol.EXTRA_GBOARD_VERSION_NAME,
                pref.getString(StatusProtocol.EXTRA_GBOARD_VERSION_NAME, "").orEmpty()
            )
            .putExtra(
                StatusProtocol.EXTRA_GBOARD_VERSION_CODE,
                pref.getLong(StatusProtocol.EXTRA_GBOARD_VERSION_CODE, -1L)
            )
            .putExtra(
                StatusProtocol.EXTRA_GBOARD_PACKAGE,
                pref.getString(StatusProtocol.EXTRA_GBOARD_PACKAGE, "").orEmpty()
            )
            .putExtra(
                StatusProtocol.EXTRA_PROCESS_NAME,
                pref.getString(StatusProtocol.EXTRA_PROCESS_NAME, "").orEmpty()
            )
            .putExtra(
                StatusProtocol.EXTRA_SYNC_ENABLED,
                pref.getBoolean(StatusProtocol.EXTRA_SYNC_ENABLED, false)
            )
            .putExtra(
                StatusProtocol.EXTRA_CONFIGURED_CAPACITY,
                pref.getInt(StatusProtocol.EXTRA_CONFIGURED_CAPACITY, PluginEntry.DEFAULT_NUM)
            )
            .putExtra(
                StatusProtocol.EXTRA_EFFECTIVE_CAPACITY,
                pref.getInt(StatusProtocol.EXTRA_EFFECTIVE_CAPACITY, PluginEntry.DEFAULT_NUM)
            )
            .putExtra(
                StatusProtocol.EXTRA_RETENTION_MS,
                pref.getLong(StatusProtocol.EXTRA_RETENTION_MS, PluginEntry.DEFAULT_TIME)
            )
            .putExtra(
                StatusProtocol.EXTRA_DEBUG_LOGGING,
                pref.getBoolean(StatusProtocol.EXTRA_DEBUG_LOGGING, false)
            )
            .putExtra(
                StatusProtocol.EXTRA_PRIMARY_CLASS_PRESENT,
                pref.getBoolean(StatusProtocol.EXTRA_PRIMARY_CLASS_PRESENT, false)
            )
            .putExtra(
                StatusProtocol.EXTRA_WATCHERS,
                pref.getString(StatusProtocol.EXTRA_WATCHERS, "").orEmpty()
            )
            .putExtra(
                StatusProtocol.EXTRA_OBSERVED_PATHS,
                pref.getString(StatusProtocol.EXTRA_OBSERVED_PATHS, "").orEmpty()
            )
            .putExtra(
                StatusProtocol.EXTRA_REWRITE_PROOF,
                pref.getBoolean(StatusProtocol.EXTRA_REWRITE_PROOF, false)
            )
            .putExtra(
                StatusProtocol.EXTRA_LAST_ERROR,
                pref.getString(StatusProtocol.EXTRA_LAST_ERROR, "").orEmpty()
            )
            .putExtra(
                StatusProtocol.EXTRA_TIMESTAMP_MS,
                pref.getLong(StatusProtocol.EXTRA_TIMESTAMP_MS, 0L)
            )
        context.sendBroadcast(response)
    }
}
