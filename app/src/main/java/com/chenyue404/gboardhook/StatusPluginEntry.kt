package com.chenyue404.gboardhook

import android.app.Application
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
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

class StatusPluginEntry : IXposedHookLoadPackage {
    companion object {
        private const val STATUS_TAG = "xposed-GboardHookah-Status-"
        private const val RECEIVER_EXPORTED_FLAG = 0x2
        private val LIMIT_REGEX = Regex(
            "\\blimit\\s+\\d+(?:\\s*,\\s*\\d+)?\\b",
            RegexOption.IGNORE_CASE
        )

        private val receiverRegistered = AtomicBoolean(false)
        private val watchers = Collections.synchronizedSet(linkedSetOf<String>())
        private val observedPaths = Collections.synchronizedSet(linkedSetOf<String>())

        @Volatile
        private var rewriteProof = false

        @Volatile
        private var lastError = ""

        @Volatile
        private var runtimeProcessName = ""
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != PluginEntry.PACKAGE_NAME) {
            return
        }

        runtimeProcessName = lpparam.processName.orEmpty()
        installStatusChannel(lpparam.classLoader)
        installLegacyWatcher(lpparam.classLoader)
        installBundleWatcher(lpparam.classLoader)
        installSqliteWatchers()
        log("status entry loaded process=$runtimeProcessName")
    }

    private fun installStatusChannel(classLoader: ClassLoader) {
        installWatcher("status-channel") {
            XposedHelpers.findAndHookMethod(
                Application::class.java,
                "attach",
                Context::class.java,
                object : XC_MethodHook() {
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
        if (!receiverRegistered.compareAndSet(false, true)) {
            return
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                if (intent.action != StatusProtocol.ACTION_REQUEST) {
                    return
                }
                val suppliedToken = intent.getStringExtra(StatusProtocol.EXTRA_TOKEN)
                    ?: return
                val expectedToken = readPreferences().getString(StatusProtocol.PREF_TOKEN, null)
                    ?: return
                if (suppliedToken != expectedToken) {
                    return
                }
                sendStatus(receiverContext, suppliedToken)
            }
        }

        val filter = IntentFilter(StatusProtocol.ACTION_REQUEST)
        try {
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
            watchers.add("status-channel=ready")
            log("status channel ready")
        } catch (t: Throwable) {
            receiverRegistered.set(false)
            recordError("status channel registration failed", t)
        }
    }

    private fun sendStatus(context: Context, token: String) {
        try {
            val pref = readPreferences()
            val config = pref.getString(PluginEntry.SP_KEY, null)?.split(",")
            val storedCapacity = config?.getOrNull(0)?.toIntOrNull()
                ?.coerceAtLeast(1)
                ?: PluginEntry.DEFAULT_NUM
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
                ?.coerceAtLeast(0L)
                ?: PluginEntry.DEFAULT_TIME
            val debugLogging = pref.getBoolean(PluginEntry.SP_KEY_LOG, false)

            val packageInfo = context.packageManager.getPackageInfo(
                PluginEntry.PACKAGE_NAME,
                0
            )
            val gboardVersionCode = if (android.os.Build.VERSION.SDK_INT >= 28) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }

            val response = Intent(StatusProtocol.ACTION_RESPONSE)
                .setPackage(BuildConfig.APPLICATION_ID)
                .putExtra(StatusProtocol.EXTRA_TOKEN, token)
                .putExtra(StatusProtocol.EXTRA_MODULE_VERSION, BuildConfig.VERSION_NAME)
                .putExtra(
                    StatusProtocol.EXTRA_GBOARD_VERSION_NAME,
                    packageInfo.versionName.orEmpty()
                )
                .putExtra(StatusProtocol.EXTRA_GBOARD_VERSION_CODE, gboardVersionCode)
                .putExtra(StatusProtocol.EXTRA_GBOARD_PACKAGE, PluginEntry.PACKAGE_NAME)
                .putExtra(StatusProtocol.EXTRA_PROCESS_NAME, runtimeProcessName)
                .putExtra(StatusProtocol.EXTRA_SYNC_ENABLED, syncEnabled)
                .putExtra(StatusProtocol.EXTRA_CONFIGURED_CAPACITY, manualCapacity)
                .putExtra(StatusProtocol.EXTRA_EFFECTIVE_CAPACITY, effectiveCapacity)
                .putExtra(StatusProtocol.EXTRA_RETENTION_MS, retentionMs)
                .putExtra(StatusProtocol.EXTRA_DEBUG_LOGGING, debugLogging)
                .putExtra(StatusProtocol.EXTRA_PRIMARY_CLASS_PRESENT, true)
                .putExtra(StatusProtocol.EXTRA_WATCHERS, synchronizedJoin(watchers))
                .putExtra(
                    StatusProtocol.EXTRA_OBSERVED_PATHS,
                    synchronizedJoin(observedPaths)
                )
                .putExtra(StatusProtocol.EXTRA_REWRITE_PROOF, rewriteProof)
                .putExtra(StatusProtocol.EXTRA_LAST_ERROR, lastError)
                .putExtra(StatusProtocol.EXTRA_TIMESTAMP_MS, System.currentTimeMillis())

            context.sendBroadcast(response)
        } catch (t: Throwable) {
            recordError("status response failed", t)
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
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        observedPaths.add("provider-legacy")
                        val sortOrder = param.args.getOrNull(4)?.toString()
                        verifyLimit("provider-legacy", sortOrder)
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
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        observedPaths.add("provider-bundle")
                        val queryArgs = param.args.getOrNull(2) as? Bundle ?: return
                        val values = mutableListOf<String>()
                        queryArgs.getString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER)?.let {
                            values.add(it)
                        }
                        queryArgs.getString(ContentResolver.QUERY_ARG_SQL_LIMIT)?.let {
                            values.add("limit $it")
                        }
                        if (queryArgs.containsKey(ContentResolver.QUERY_ARG_LIMIT)) {
                            values.add(
                                "limit ${queryArgs.getInt(ContentResolver.QUERY_ARG_LIMIT)}"
                            )
                        }
                        values.forEach { verifyLimit("provider-bundle", it) }
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
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val table = param.args.getOrNull(0)?.toString() ?: return
                        if (!isClipboardQuery(table)) {
                            return
                        }
                        observedPaths.add("sqlite-query")
                        verifyLimit("sqlite-query", param.args.getOrNull(7)?.toString())
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
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val sql = param.args.getOrNull(0)?.toString() ?: return
                        if (!isClipboardQuery(sql)) {
                            return
                        }
                        observedPaths.add("sqlite-rawQuery")
                        verifyLimit("sqlite-rawQuery", sql)
                    }
                }
            )
        }
    }

    private fun verifyLimit(path: String, value: String?) {
        if (value.isNullOrBlank()) {
            return
        }
        val match = LIMIT_REGEX.find(value) ?: return
        val limitValue = Regex("\\d+").find(match.value)?.value?.toIntOrNull() ?: return
        val pref = readPreferences()
        val config = pref.getString(PluginEntry.SP_KEY, null)?.split(",")
        val configured = pref.getInt(
            "manual_clipboard_capacity",
            config?.getOrNull(0)?.toIntOrNull()?.coerceAtLeast(1)
                ?: PluginEntry.DEFAULT_NUM
        ).coerceAtLeast(1)
        val syncEnabled = pref.getBoolean(
            PluginEntry.SP_KEY_SYNC_ANDROID_CLIPBOARD_CAPACITY,
            PluginEntry.DEFAULT_SYNC_ANDROID_CLIPBOARD_CAPACITY
        )
        val expected = if (syncEnabled) PluginEntry.AUTO_CAPACITY else configured

        if (limitValue == expected) {
            if (!rewriteProof) {
                log("rewrite proof path=$path effectiveCapacity=$expected")
            }
            rewriteProof = true
            if (lastError.startsWith("rewrite mismatch")) {
                lastError = ""
            }
        } else {
            val message = "rewrite mismatch path=$path expected=$expected observed=$limitValue"
            lastError = message
            log(message)
        }
    }

    private fun readPreferences(): XSharedPreferences {
        return XSharedPreferences(BuildConfig.APPLICATION_ID, PluginEntry.SP_FILE_NAME).also {
            it.reload()
        }
    }

    private fun isClipboardQuery(value: String): Boolean {
        return value.contains("clipboard", ignoreCase = true) ||
            value.trim('`', '"', '[', ']').equals("clips", ignoreCase = true)
    }

    private fun installWatcher(name: String, block: () -> Unit) {
        try {
            block()
            watchers.add("$name=ready")
        } catch (t: Throwable) {
            watchers.add("$name=error")
            recordError("$name setup failed", t)
        }
    }

    private fun recordError(prefix: String, throwable: Throwable) {
        val detail = throwable.javaClass.simpleName +
            (throwable.message?.let { ": $it" } ?: "")
        lastError = "$prefix: $detail"
        log(lastError)
    }

    private fun synchronizedJoin(values: MutableSet<String>): String {
        synchronized(values) {
            return values.joinToString(", ")
        }
    }

    private fun log(message: String) {
        XposedBridge.log("$STATUS_TAG\n$message")
    }
}
