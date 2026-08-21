package com.chenyue404.gboardhook

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.net.toUri
import java.util.Locale
import java.util.UUID

class MainActivity : Activity() {

    companion object {
        private const val SP_KEY_MANUAL_CAPACITY = "manual_clipboard_capacity"
        private const val RECEIVER_EXPORTED_FLAG = 0x2
        private const val STATUS_TIMEOUT_MS = 2000L
    }

    private var modulePreferences: SharedPreferences? = null
    private var statusToken: String? = null
    private var statusReceiverRegistered = false
    private var pendingStatusRequest = 0L
    private lateinit var tvStatus: TextView
    private val handler = Handler(Looper.getMainLooper())

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != StatusProtocol.ACTION_RESPONSE) {
                return
            }
            val expectedToken = statusToken ?: return
            if (intent.getStringExtra(StatusProtocol.EXTRA_TOKEN) != expectedToken) {
                return
            }
            pendingStatusRequest = 0L
            renderLiveStatus(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val sw0 = findViewById<Switch>(R.id.sw0)
        val swLog = findViewById<Switch>(R.id.swLog)
        val swSyncAndroidClipboardCapacity =
            findViewById<Switch>(R.id.swSyncAndroidClipboardCapacity)
        val et0 = findViewById<EditText>(R.id.et0)
        val et1 = findViewById<EditText>(R.id.et1)
        val bt0 = findViewById<Button>(R.id.bt0)
        val btStatus = findViewById<Button>(R.id.btStatus)
        tvStatus = findViewById(R.id.tvStatus)

        modulePreferences = try {
            getSharedPreferences(PluginEntry.SP_FILE_NAME, MODE_WORLD_READABLE)
        } catch (e: SecurityException) {
            Log.d("MainActivity", "getSharedPreferences failed: $e")
            Toast.makeText(this, R.string.config_read_failed, Toast.LENGTH_SHORT).show()
            null
        }

        val pref = modulePreferences
        ensureStatusToken(pref)

        val storedConfig = pref?.getString(PluginEntry.SP_KEY, null)?.split(",")
        val storedCapacity = storedConfig?.getOrNull(0)?.toIntOrNull()
        val fallbackManualCapacity = when (storedCapacity) {
            null, PluginEntry.AUTO_CAPACITY -> PluginEntry.DEFAULT_NUM
            else -> storedCapacity.coerceAtLeast(1)
        }
        val manualCapacity = pref?.getInt(
            SP_KEY_MANUAL_CAPACITY,
            fallbackManualCapacity
        ) ?: fallbackManualCapacity

        et0.setText(manualCapacity.toString())
        et1.setText(storedConfig?.getOrNull(1).orEmpty())
        sw0.isChecked = storedConfig
            ?.getOrNull(2)
            ?.equals("true", true) ?: false
        swLog.isChecked = pref?.getBoolean(PluginEntry.SP_KEY_LOG, false) ?: false
        swSyncAndroidClipboardCapacity.isChecked = pref?.getBoolean(
            PluginEntry.SP_KEY_SYNC_ANDROID_CLIPBOARD_CAPACITY,
            PluginEntry.DEFAULT_SYNC_ANDROID_CLIPBOARD_CAPACITY
        ) ?: PluginEntry.DEFAULT_SYNC_ANDROID_CLIPBOARD_CAPACITY

        fun updateCapacityUi(syncEnabled: Boolean) {
            et0.isEnabled = !syncEnabled
            et0.alpha = if (syncEnabled) 0.45f else 1.0f
        }

        updateCapacityUi(swSyncAndroidClipboardCapacity.isChecked)
        swSyncAndroidClipboardCapacity.setOnCheckedChangeListener { _, isChecked ->
            updateCapacityUi(isChecked)
        }

        bt0.setOnClickListener {
            if (pref == null) {
                Toast.makeText(this, R.string.config_read_failed, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val manualNum = et0.text.toString().toIntOrNull()?.coerceAtLeast(1)
                ?: PluginEntry.DEFAULT_NUM
            val time = et1.text.toString().toLongOrNull()?.coerceAtLeast(0L)
                ?: PluginEntry.DEFAULT_TIME
            val switchOn = sw0.isChecked.toString()

            pref.edit().apply {
                putString(PluginEntry.SP_KEY, "$manualNum,$time,$switchOn")
                putInt(SP_KEY_MANUAL_CAPACITY, manualNum)
                putBoolean(PluginEntry.SP_KEY_LOG, swLog.isChecked)
                putBoolean(
                    PluginEntry.SP_KEY_SYNC_ANDROID_CLIPBOARD_CAPACITY,
                    swSyncAndroidClipboardCapacity.isChecked
                )
                apply()
            }

            val message = if (swSyncAndroidClipboardCapacity.isChecked) {
                R.string.settings_applied_auto_capacity
            } else {
                R.string.settings_applied
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            renderWaitingStatus(getString(R.string.status_restart_required))
        }

        btStatus.setOnClickListener {
            requestLiveStatus()
        }

        findViewById<TextView>(R.id.tvHint).setOnClickListener {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    "https://github.com/Lycidias93/GboardHookah".toUri()
                )
            )
        }

        renderWaitingStatus(getString(R.string.status_not_checked))
    }

    override fun onStart() {
        super.onStart()
        registerStatusReceiver()
        requestLiveStatus()
    }

    override fun onStop() {
        handler.removeCallbacksAndMessages(null)
        if (statusReceiverRegistered) {
            try {
                unregisterReceiver(statusReceiver)
            } catch (_: IllegalArgumentException) {
            }
            statusReceiverRegistered = false
        }
        super.onStop()
    }

    private fun ensureStatusToken(pref: SharedPreferences?) {
        if (pref == null) {
            statusToken = null
            return
        }
        val existing = pref.getString(StatusProtocol.PREF_TOKEN, null)
        if (!existing.isNullOrBlank()) {
            statusToken = existing
            return
        }
        val generated = UUID.randomUUID().toString()
        if (pref.edit().putString(StatusProtocol.PREF_TOKEN, generated).commit()) {
            statusToken = generated
        }
    }

    private fun registerStatusReceiver() {
        if (statusReceiverRegistered) {
            return
        }
        val filter = IntentFilter(StatusProtocol.ACTION_RESPONSE)
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                val method = Context::class.java.getMethod(
                    "registerReceiver",
                    BroadcastReceiver::class.java,
                    IntentFilter::class.java,
                    Integer.TYPE
                )
                method.invoke(this, statusReceiver, filter, RECEIVER_EXPORTED_FLAG)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(statusReceiver, filter)
            }
            statusReceiverRegistered = true
        } catch (t: Throwable) {
            renderWaitingStatus("Status receiver error: ${t.javaClass.simpleName}")
        }
    }

    private fun requestLiveStatus() {
        val token = statusToken
        if (token.isNullOrBlank()) {
            renderWaitingStatus(getString(R.string.status_config_unavailable))
            return
        }

        val requestId = SystemClock.elapsedRealtime()
        pendingStatusRequest = requestId
        renderWaitingStatus(getString(R.string.status_checking))

        val request = Intent(StatusProtocol.ACTION_REQUEST)
            .setPackage(PluginEntry.PACKAGE_NAME)
            .putExtra(StatusProtocol.EXTRA_TOKEN, token)
        sendBroadcast(request)

        handler.postDelayed({
            if (pendingStatusRequest == requestId) {
                pendingStatusRequest = 0L
                renderWaitingStatus(getString(R.string.status_no_live_response))
            }
        }, STATUS_TIMEOUT_MS)
    }

    private fun renderLiveStatus(intent: Intent) {
        val moduleVersion = intent.getStringExtra(StatusProtocol.EXTRA_MODULE_VERSION)
            .orEmpty()
        val gboardVersion = intent.getStringExtra(StatusProtocol.EXTRA_GBOARD_VERSION_NAME)
            .orEmpty()
        val gboardVersionCode = intent.getLongExtra(
            StatusProtocol.EXTRA_GBOARD_VERSION_CODE,
            -1L
        )
        val processName = intent.getStringExtra(StatusProtocol.EXTRA_PROCESS_NAME)
            .orEmpty()
        val syncEnabled = intent.getBooleanExtra(StatusProtocol.EXTRA_SYNC_ENABLED, false)
        val configuredCapacity = intent.getIntExtra(
            StatusProtocol.EXTRA_CONFIGURED_CAPACITY,
            PluginEntry.DEFAULT_NUM
        )
        val effectiveCapacity = intent.getIntExtra(
            StatusProtocol.EXTRA_EFFECTIVE_CAPACITY,
            configuredCapacity
        )
        val retentionMs = intent.getLongExtra(
            StatusProtocol.EXTRA_RETENTION_MS,
            PluginEntry.DEFAULT_TIME
        )
        val debugLogging = intent.getBooleanExtra(
            StatusProtocol.EXTRA_DEBUG_LOGGING,
            false
        )
        val watchers = intent.getStringExtra(StatusProtocol.EXTRA_WATCHERS).orEmpty()
        val observedPaths = intent.getStringExtra(StatusProtocol.EXTRA_OBSERVED_PATHS)
            .orEmpty()
        val rewriteProof = intent.getBooleanExtra(
            StatusProtocol.EXTRA_REWRITE_PROOF,
            false
        )
        val lastError = intent.getStringExtra(StatusProtocol.EXTRA_LAST_ERROR).orEmpty()

        val overall = when {
            lastError.isNotBlank() -> "DEGRADED"
            rewriteProof -> "PASS"
            else -> "LIVE — waiting for clipboard query"
        }
        val effectiveLabel = if (syncEnabled && effectiveCapacity == PluginEntry.AUTO_CAPACITY) {
            "Android history / no Hookah cap"
        } else {
            effectiveCapacity.toString()
        }
        val queryLabel = if (observedPaths.isBlank()) "none yet" else observedPaths
        val proofLabel = if (rewriteProof) {
            "PASS"
        } else {
            "Not observed yet — open Gboard clipboard, then Refresh"
        }
        val errorLabel = if (lastError.isBlank()) "none" else lastError

        tvStatus.text = buildString {
            appendLine("Status: $overall")
            appendLine("Injection: LIVE authenticated response")
            appendLine("Module: $moduleVersion")
            appendLine("Gboard: $gboardVersion ($gboardVersionCode)")
            appendLine("Process: $processName")
            appendLine("Capacity sync: ${if (syncEnabled) "ON" else "OFF"}")
            appendLine("Manual capacity: $configuredCapacity")
            appendLine("Effective capacity: $effectiveLabel")
            appendLine("Retention: ${formatRetention(retentionMs)}")
            appendLine("Debug logging: ${if (debugLogging) "ON" else "OFF"}")
            appendLine("Hook watchers: ${watchers.ifBlank { "none" }}")
            appendLine("Observed query paths: $queryLabel")
            appendLine("Capacity rewrite proof: $proofLabel")
            append("Errors: $errorLabel")
        }
    }

    private fun renderWaitingStatus(detail: String) {
        val localGboard = getLocalGboardVersion()
        tvStatus.text = buildString {
            appendLine("Status: $detail")
            appendLine("Module: ${BuildConfig.VERSION_NAME}")
            appendLine("Gboard: $localGboard")
            append("Live hook proof: waiting for Gboard process")
        }
    }

    private fun getLocalGboardVersion(): String {
        return try {
            val info = packageManager.getPackageInfo(PluginEntry.PACKAGE_NAME, 0)
            val code = if (android.os.Build.VERSION.SDK_INT >= 28) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            "${info.versionName.orEmpty()} ($code)"
        } catch (_: Throwable) {
            "not installed"
        }
    }

    private fun formatRetention(retentionMs: Long): String {
        val days = retentionMs.toDouble() / PluginEntry.DAY.toDouble()
        return if (retentionMs % PluginEntry.DAY == 0L) {
            "$retentionMs ms (${retentionMs / PluginEntry.DAY} days)"
        } else {
            String.format(Locale.ROOT, "%d ms (%.2f days)", retentionMs, days)
        }
    }
}
