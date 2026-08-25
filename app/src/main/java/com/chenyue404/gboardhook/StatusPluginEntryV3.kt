package com.chenyue404.gboardhook

import android.app.Application
import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ProviderInfo
import android.os.Bundle
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runtime status transport.
 *
 * Context acquisition is anchored to framework callbacks that necessarily receive a
 * real Gboard Context. In particular ContentProvider.attachInfo() runs in the injected
 * Gboard process before providers are used, avoiding lifecycle timing assumptions.
 * The primary clipboard hooks report their own real callbacks through reportHookEvent(),
 * so telemetry is evidence from the functional hook rather than a parallel observer.
 */
class StatusPluginEntryV3 : IXposedHookLoadPackage {
    companion object {
        private const val STATUS_TAG = "xposed-GboardHookah-Status-"
        private const val RECEIVER_EXPORTED_FLAG = 0x2
        private val requestReceiverRegistered = AtomicBoolean(false)

        @Volatile
        private var runtimeProcessName = ""

        @Volatile
        private var runtimeContext: Context? = null

        @Volatile
        private var sessionStatusToken: String? = null

        @JvmStatic
        fun captureRuntimeContext(context: Context, reason: String) {
            val appContext = context.applicationContext ?: context
            val firstContext = runtimeContext == null
            runtimeContext = appContext
            registerRequestReceiver(appContext)
            if (firstContext) {
                logStatic("runtime context ready reason=$reason")
                pushStatus(appContext, "context-$reason")
            }
        }

        @JvmStatic
        fun reportHookEvent(owner: Any?, path: String, provesCapacityHandling: Boolean) {
            val changed = RuntimeStatus.observe(path, provesCapacityHandling)
            val providerContext = (owner as? ContentProvider)?.context
            if (providerContext != null && runtimeContext == null) {
                captureRuntimeContext(providerContext, "hook-$path")
            }
            if (changed) {
                pushCurrentStatus("hook-$path")
            }
        }

        @JvmStatic
        fun reportHookError(path: String, throwable: Throwable) {
            if (RuntimeStatus.callbackError(path, throwable)) {
                pushCurrentStatus("error-$path")
            }
        }

        @JvmStatic
        fun notifyRuntimeChanged(reason: String) {
            pushCurrentStatus(reason)
        }

        private fun pushCurrentStatus(reason: String) {
            runtimeContext?.let { pushStatus(it, reason) }
        }

        private fun registerRequestReceiver(context: Context) {
            if (!requestReceiverRegistered.compareAndSet(false, true)) return

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context, intent: Intent) {
                    if (intent.action != StatusProtocol.ACTION_REQUEST) return
                    val suppliedToken = intent.getStringExtra(StatusProtocol.EXTRA_TOKEN) ?: return
                    if (!isOrderedBroadcast) {
                        logStatic("status request ignored reason=non-ordered")
                        return
                    }

                    // Authentication is enforced by Android at receiver registration time:
                    // only senders holding our signature-level status permission can reach
                    // this receiver. The request token is therefore only a correlation nonce
                    // that binds the ordered result to the app's current refresh request.
                    sessionStatusToken = suppliedToken

                    try {
                        setResultExtras(buildStatusBundle(receiverContext, suppliedToken))
                        logStatic("ordered status response returned via signature permission channel")
                    } catch (t: Throwable) {
                        RuntimeStatus.callbackError("status-ordered-response", t)
                        logStatic("ordered status response failed: $t")
                    }

                    // Keep the explicit push channel as a compatibility fallback for later
                    // hook events. It can reuse the nonce established by the authenticated
                    // ordered request without depending on cross-process shared preferences.
                    pushStatus(receiverContext.applicationContext, "refresh-request")
                }
            }

            try {
                val filter = IntentFilter(StatusProtocol.ACTION_REQUEST)
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    val method = Context::class.java.getMethod(
                        "registerReceiver",
                        BroadcastReceiver::class.java,
                        IntentFilter::class.java,
                        String::class.java,
                        android.os.Handler::class.java,
                        Integer.TYPE
                    )
                    method.invoke(
                        context,
                        receiver,
                        filter,
                        StatusProtocol.PERMISSION_STATUS,
                        null,
                        RECEIVER_EXPORTED_FLAG
                    )
                } else {
                    @Suppress("DEPRECATION")
                    context.registerReceiver(
                        receiver,
                        filter,
                        StatusProtocol.PERMISSION_STATUS,
                        null
                    )
                }
                RuntimeStatus.hookReady("status-channel")
                logStatic("status channel ready permission=${StatusProtocol.PERMISSION_STATUS}")
            } catch (t: Throwable) {
                requestReceiverRegistered.set(false)
                RuntimeStatus.hookError("status-channel", t)
                logStatic("status channel registration failed: $t")
            }
        }

        private fun buildStatusBundle(context: Context, token: String): Bundle {
            val pref = readPreferences()
            val config = pref.getString(PluginEntry.SP_KEY, null)?.split(",")
            val storedCapacity = config?.getOrNull(0)?.toIntOrNull()
                ?.coerceAtLeast(1) ?: PluginEntry.DEFAULT_NUM
            val manualCapacity = pref.getInt("manual_clipboard_capacity", storedCapacity)
                .coerceAtLeast(1)
            val syncEnabled = pref.getBoolean(
                PluginEntry.SP_KEY_SYNC_ANDROID_CLIPBOARD_CAPACITY,
                PluginEntry.DEFAULT_SYNC_ANDROID_CLIPBOARD_CAPACITY
            )
            val effectiveCapacity = if (syncEnabled) {
                PluginEntry.AUTO_CAPACITY
            } else {
                manualCapacity
            }
            val retentionMs = config?.getOrNull(1)?.toLongOrNull()
                ?.coerceAtLeast(0L) ?: PluginEntry.DEFAULT_TIME
            val debugLogging = pref.getBoolean(PluginEntry.SP_KEY_LOG, false)
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val gboardVersionCode = if (android.os.Build.VERSION.SDK_INT >= 28) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }

            return Bundle().apply {
                putString(StatusProtocol.EXTRA_TOKEN, token)
                putString(StatusProtocol.EXTRA_MODULE_VERSION, BuildConfig.VERSION_NAME)
                putString(StatusProtocol.EXTRA_GBOARD_VERSION_NAME, packageInfo.versionName.orEmpty())
                putLong(StatusProtocol.EXTRA_GBOARD_VERSION_CODE, gboardVersionCode)
                putString(StatusProtocol.EXTRA_GBOARD_PACKAGE, PluginEntry.PACKAGE_NAME)
                putString(StatusProtocol.EXTRA_PROCESS_NAME, runtimeProcessName)
                putBoolean(StatusProtocol.EXTRA_SYNC_ENABLED, syncEnabled)
                putInt(StatusProtocol.EXTRA_CONFIGURED_CAPACITY, manualCapacity)
                putInt(StatusProtocol.EXTRA_EFFECTIVE_CAPACITY, effectiveCapacity)
                putLong(StatusProtocol.EXTRA_RETENTION_MS, retentionMs)
                putBoolean(StatusProtocol.EXTRA_DEBUG_LOGGING, debugLogging)
                putBoolean(StatusProtocol.EXTRA_PRIMARY_CLASS_PRESENT, true)
                putString(StatusProtocol.EXTRA_WATCHERS, RuntimeStatus.hookSummary())
                putString(StatusProtocol.EXTRA_OBSERVED_PATHS, RuntimeStatus.observedSummary())
                putBoolean(StatusProtocol.EXTRA_REWRITE_PROOF, RuntimeStatus.capacityProof)
                putString(StatusProtocol.EXTRA_LAST_ERROR, RuntimeStatus.lastError)
                putLong(StatusProtocol.EXTRA_TIMESTAMP_MS, System.currentTimeMillis())
            }
        }

        private fun pushStatus(context: Context, reason: String) {
            try {
                val pref = readPreferences()
                val token = sessionStatusToken
                    ?: pref.getString(StatusProtocol.PREF_TOKEN, null)
                if (token.isNullOrBlank()) {
                    logStatic("status push skipped reason=$reason token=missing")
                    return
                }

                val push = Intent(StatusProtocol.ACTION_PUSH)
                    .setClassName(
                        BuildConfig.APPLICATION_ID,
                        "com.chenyue404.gboardhook.RuntimeStatusReceiver"
                    )
                    .putExtras(buildStatusBundle(context, token))

                context.sendBroadcast(push)
                logStatic(
                    "status push sent reason=$reason proof=${RuntimeStatus.capacityProof} " +
                        "paths=${RuntimeStatus.observedSummary()}"
                )
            } catch (t: Throwable) {
                RuntimeStatus.callbackError("status-push", t)
                logStatic("status push failed reason=$reason: $t")
            }
        }

        private fun readPreferences(): XSharedPreferences =
            XSharedPreferences(BuildConfig.APPLICATION_ID, PluginEntry.SP_FILE_NAME).also { it.reload() }

        private fun logStatic(message: String) {
            XposedBridge.log("$STATUS_TAG $message")
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != PluginEntry.PACKAGE_NAME) return

        runtimeProcessName = lpparam.processName.orEmpty()
        installContextCapture()
        logStatic("status v7 transport loaded process=$runtimeProcessName")
    }

    private fun installContextCapture() {
        installWatcher("status-context-provider") {
            XposedHelpers.findAndHookMethod(
                ContentProvider::class.java,
                "attachInfo",
                Context::class.java,
                ProviderInfo::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val context = param.args.firstOrNull() as? Context ?: return
                        captureRuntimeContext(context, "provider-attach")
                    }
                }
            )
        }

        installWatcher("status-context-application") {
            XposedHelpers.findAndHookMethod(
                Application::class.java,
                "attach",
                Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val context = param.args.firstOrNull() as? Context ?: return
                        captureRuntimeContext(context, "application-attach")
                    }
                }
            )
        }
    }

    private fun installWatcher(name: String, block: () -> Unit) {
        try {
            block()
            RuntimeStatus.hookReady(name)
        } catch (t: Throwable) {
            RuntimeStatus.hookError(name, t)
            logStatic("$name setup failed: $t")
        }
    }
}
