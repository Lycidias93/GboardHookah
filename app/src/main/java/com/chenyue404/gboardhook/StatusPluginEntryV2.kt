package com.chenyue404.gboardhook

import android.app.Application
import android.app.Instrumentation
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.atomic.AtomicBoolean

/** Runtime status v2: reliable lifecycle registration plus passive telemetry. */
class StatusPluginEntryV2 : IXposedHookLoadPackage {
    companion object {
        private const val STATUS_TAG = "xposed-GboardHookah-Status-"
        private const val RECEIVER_EXPORTED_FLAG = 0x2
        private const val TELEMETRY_PRIORITY = 10000
        private val LIMIT_REGEX = Regex(
            "\\blimit\\s+\\d+(?:\\s*,\\s*\\d+)?\\b",
            RegexOption.IGNORE_CASE
        )
        private val receiverRegistered = AtomicBoolean(false)

        @Volatile
        private var runtimeProcessName = ""
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != PluginEntry.PACKAGE_NAME) return

        RuntimeStatus.reset()
        runtimeProcessName = lpparam.processName.orEmpty()
        installStatusChannel()
        installLegacyWatcher(lpparam.classLoader)
        installBundleWatcher(lpparam.classLoader)
        installSqliteWatchers()
        installHashSetWatcher()
        log("status v2 entry loaded process=$runtimeProcessName")
    }

    private fun installStatusChannel() {
        installWatcher("status-channel-bootstrap") {
            XposedHelpers.findAndHookMethod(
                Instrumentation::class.java,
                "callApplicationOnCreate",
                Application::class.java,
                object : XC_MethodHook(TELEMETRY_PRIORITY) {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val app = param.args.firstOrNull() as? Application ?: return
                        registerRequestReceiver(app.applicationContext)
                    }
                }
            )
        }

        // Fallback for Gboard builds whose application lifecycle differs.
        installWatcher("status-channel-attach-fallback") {
            XposedHelpers.findAndHookMethod(
                Application::class.java,
                "attach",
                Context::class.java,
                object : XC_MethodHook(TELEMETRY_PRIORITY) {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val context = (param.args.firstOrNull() as? Context)?.applicationContext
                            ?: return
                        registerRequestReceiver(context)
                    }
                }
            )
        }
    }

    private fun registerRequestReceiver(context: Context) {
        if (!receiverRegistered.compareAndSet(false, true)) return

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                if (intent.action != StatusProtocol.ACTION_REQUEST) return
                val suppliedToken = intent.getStringExtra(StatusProtocol.EXTRA_TOKEN) ?: return
                val expectedToken = readPreferences()
                    .getString(StatusProtocol.PREF_TOKEN, null) ?: return
                if (suppliedToken != expectedToken) return
                sendStatus(receiverContext, suppliedToken)
            }
        }

        try {
            val filter = IntentFilter(StatusProtocol.ACTION_REQUEST)
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                val method = Context::class.java.getMethod(
                    "registerReceiver",
                    BroadcastReceiver::class.java,
                    IntentFilter::class.java,
                    Integer.TYPE
                )
                method.invoke(context, receiver, filter, RECEIVER_EXPORTED_FLAG)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(receiver, filter)
            }
            RuntimeStatus.hookReady("status-channel")
            log("status channel ready")
        } catch (t: Throwable) {
            receiverRegistered.set(false)
            RuntimeStatus.hookError("status-channel", t)
            log("status channel registration failed: $t")
        }
    }

    private fun sendStatus(context: Context, token: String) {
        try {
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

            val packageInfo = context.packageManager.getPackageInfo(PluginEntry.PACKAGE_NAME, 0)
            val gboardVersionCode = if (android.os.Build.VERSION.SDK_INT >= 28) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }

            context.sendBroadcast(
                Intent(StatusProtocol.ACTION_RESPONSE)
                    .setPackage(BuildConfig.APPLICATION_ID)
                    .putExtra(StatusProtocol.EXTRA_TOKEN, token)
                    .putExtra(StatusProtocol.EXTRA_MODULE_VERSION, BuildConfig.VERSION_NAME)
                    .putExtra(StatusProtocol.EXTRA_GBOARD_VERSION_NAME, packageInfo.versionName.orEmpty())
                    .putExtra(StatusProtocol.EXTRA_GBOARD_VERSION_CODE, gboardVersionCode)
                    .putExtra(StatusProtocol.EXTRA_GBOARD_PACKAGE, PluginEntry.PACKAGE_NAME)
                    .putExtra(StatusProtocol.EXTRA_PROCESS_NAME, runtimeProcessName)
                    .putExtra(StatusProtocol.EXTRA_SYNC_ENABLED, syncEnabled)
                    .putExtra(StatusProtocol.EXTRA_CONFIGURED_CAPACITY, manualCapacity)
                    .putExtra(StatusProtocol.EXTRA_EFFECTIVE_CAPACITY, effectiveCapacity)
                    .putExtra(StatusProtocol.EXTRA_RETENTION_MS, retentionMs)
                    .putExtra(StatusProtocol.EXTRA_DEBUG_LOGGING, debugLogging)
                    .putExtra(StatusProtocol.EXTRA_PRIMARY_CLASS_PRESENT, true)
                    .putExtra(StatusProtocol.EXTRA_WATCHERS, RuntimeStatus.hookSummary())
                    .putExtra(StatusProtocol.EXTRA_OBSERVED_PATHS, RuntimeStatus.observedSummary())
                    .putExtra(StatusProtocol.EXTRA_REWRITE_PROOF, RuntimeStatus.capacityProof)
                    .putExtra(StatusProtocol.EXTRA_LAST_ERROR, RuntimeStatus.lastError)
                    .putExtra(StatusProtocol.EXTRA_TIMESTAMP_MS, System.currentTimeMillis())
            )
            log("status response sent")
        } catch (t: Throwable) {
            RuntimeStatus.callbackError("status-response", t)
            log("status response failed: $t")
        }
    }

    private fun installLegacyWatcher(classLoader: ClassLoader) {
        installWatcher("provider-legacy") {
            XposedHelpers.findAndHookMethod(
                "com.google.android.apps.inputmethod.libs.clipboard.ClipboardContentProvider",
                classLoader,
                "query",
                Uri::class.java,
                Array<String>::class.java,
                String::class.java,
                Array<String>::class.java,
                String::class.java,
                object : XC_MethodHook(TELEMETRY_PRIORITY) {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val sortOrder = param.args.getOrNull(4)?.toString()
                        RuntimeStatus.observe(
                            "provider-legacy",
                            provesCapacityHandling = matchesEffectiveLimit(sortOrder)
                        )
                    }
                }
            )
        }
    }

    private fun installBundleWatcher(classLoader: ClassLoader) {
        installWatcher("provider-bundle") {
            XposedHelpers.findAndHookMethod(
                "com.google.android.apps.inputmethod.libs.clipboard.ClipboardContentProvider",
                classLoader,
                "query",
                Uri::class.java,
                Array<String>::class.java,
                Bundle::class.java,
                CancellationSignal::class.java,
                object : XC_MethodHook(TELEMETRY_PRIORITY) {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val queryArgs = param.args.getOrNull(2) as? Bundle ?: return
                        val proof = matchesEffectiveLimit(
                            queryArgs.getString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER)
                        ) || matchesEffectiveLimit(
                            queryArgs.getString(ContentResolver.QUERY_ARG_SQL_LIMIT)?.let { "limit $it" }
                        ) || (
                            queryArgs.containsKey(ContentResolver.QUERY_ARG_LIMIT) &&
                                queryArgs.getInt(ContentResolver.QUERY_ARG_LIMIT) == effectiveCapacity()
                            )
                        RuntimeStatus.observe("provider-bundle", proof)
                    }
                }
            )
        }
    }

    private fun installSqliteWatchers() {
        installWatcher("sqlite-query") {
            XposedHelpers.findAndHookMethod(
                SQLiteDatabase::class.java,
                "query",
                String::class.java,
                Array<String>::class.java,
                String::class.java,
                Array<String>::class.java,
                String::class.java,
                String::class.java,
                String::class.java,
                String::class.java,
                object : XC_MethodHook(TELEMETRY_PRIORITY) {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val table = param.args.getOrNull(0)?.toString() ?: return
                        if (!isClipboardQuery(table)) return
                        RuntimeStatus.observe(
                            "sqlite-query",
                            provesCapacityHandling = param.args.getOrNull(7)?.toString() ==
                                effectiveCapacity().toString()
                        )
                    }
                }
            )
        }

        installWatcher("sqlite-rawQuery") {
            XposedHelpers.findAndHookMethod(
                SQLiteDatabase::class.java,
                "rawQuery",
                String::class.java,
                Array<String>::class.java,
                object : XC_MethodHook(TELEMETRY_PRIORITY) {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val sql = param.args.getOrNull(0)?.toString() ?: return
                        if (!isClipboardQuery(sql)) return
                        RuntimeStatus.observe(
                            "sqlite-rawQuery",
                            provesCapacityHandling = matchesEffectiveLimit(sql)
                        )
                    }
                }
            )
        }
    }

    private fun installHashSetWatcher() {
        installWatcher("hashset-compat") {
            XposedHelpers.findAndHookMethod(
                HashSet::class.java,
                "size",
                object : XC_MethodHook(TELEMETRY_PRIORITY) {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val set = param.thisObject as? HashSet<*> ?: return
                            val first = set.firstOrNull() ?: return
                            val instantClassName = "j" + '$' + ".time.Instant"
                            if (first.javaClass.name != instantClassName) return

                            val map = XposedHelpers.getObjectField(set, "map") as? HashMap<*, *>
                                ?: return
                            val applies = map.size <= effectiveCapacity()
                            RuntimeStatus.observe("hashset-compat", applies)
                            if (applies) {
                                log(
                                    "capacity hook proof path=hashset-compat " +
                                        "setSize=${map.size} effectiveCapacity=${effectiveCapacity()}"
                                )
                            }
                        } catch (t: Throwable) {
                            RuntimeStatus.callbackError("hashset-compat", t)
                            log("hashset telemetry failed: $t")
                        }
                    }
                }
            )
        }
    }

    private fun effectiveCapacity(): Int {
        val pref = readPreferences()
        val config = pref.getString(PluginEntry.SP_KEY, null)?.split(",")
        val stored = pref.getInt(
            "manual_clipboard_capacity",
            config?.getOrNull(0)?.toIntOrNull()?.coerceAtLeast(1) ?: PluginEntry.DEFAULT_NUM
        ).coerceAtLeast(1)
        val sync = pref.getBoolean(
            PluginEntry.SP_KEY_SYNC_ANDROID_CLIPBOARD_CAPACITY,
            PluginEntry.DEFAULT_SYNC_ANDROID_CLIPBOARD_CAPACITY
        )
        return if (sync) PluginEntry.AUTO_CAPACITY else stored
    }

    private fun matchesEffectiveLimit(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        val match = LIMIT_REGEX.find(value) ?: return false
        val observed = Regex("\\d+").find(match.value)?.value?.toIntOrNull() ?: return false
        return observed == effectiveCapacity()
    }

    private fun readPreferences(): XSharedPreferences =
        XSharedPreferences(BuildConfig.APPLICATION_ID, PluginEntry.SP_FILE_NAME).also { it.reload() }

    private fun isClipboardQuery(value: String): Boolean =
        value.contains("clipboard", ignoreCase = true) ||
            value.trim('`', '"', '[', ']').equals("clips", ignoreCase = true)

    private fun installWatcher(name: String, block: () -> Unit) {
        try {
            block()
            RuntimeStatus.hookReady(name)
        } catch (t: Throwable) {
            RuntimeStatus.hookError(name, t)
            log("$name setup failed: $t")
        }
    }

    private fun log(message: String) {
        XposedBridge.log("$STATUS_TAG\n$message")
    }
}
