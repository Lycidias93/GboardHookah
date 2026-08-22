package com.chenyue404.gboardhook

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import de.robv.android.xposed.AndroidAppHelper
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Runtime status v3.
 *
 * The Gboard process pushes an authenticated snapshot to a manifest-declared receiver
 * in GboardHookah. This deliberately avoids relying on Application/Instrumentation
 * lifecycle hooks, which are not reached reliably by current Gboard builds.
 */
class StatusPluginEntryV3 : IXposedHookLoadPackage {
    companion object {
        private const val STATUS_TAG = "xposed-GboardHookah-Status-"
        private const val TELEMETRY_PRIORITY = 10000
        private const val CONTEXT_RETRY_COUNT = 50
        private const val CONTEXT_RETRY_SLEEP_MS = 100L
        private val LIMIT_REGEX = Regex(
            "\\blimit\\s+\\d+(?:\\s*,\\s*\\d+)?\\b",
            RegexOption.IGNORE_CASE
        )

        @Volatile
        private var runtimeProcessName = ""
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != PluginEntry.PACKAGE_NAME) return

        RuntimeStatus.reset()
        runtimeProcessName = lpparam.processName.orEmpty()
        installLegacyWatcher(lpparam.classLoader)
        installBundleWatcher(lpparam.classLoader)
        installSqliteWatchers()
        installHashSetWatcher()
        scheduleInitialPush()
        log("status v3 entry loaded process=$runtimeProcessName")
    }

    private fun scheduleInitialPush() {
        Thread({
            repeat(CONTEXT_RETRY_COUNT) {
                val app = currentApplication()
                if (app != null) {
                    pushStatus(app.applicationContext, "process-start")
                    return@Thread
                }
                try {
                    Thread.sleep(CONTEXT_RETRY_SLEEP_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@Thread
                }
            }
            log("status push context timeout")
        }, "GboardHookahStatusInit").apply {
            isDaemon = true
            start()
        }
    }

    private fun currentApplication(): Application? = try {
        AndroidAppHelper.currentApplication()
    } catch (t: Throwable) {
        log("currentApplication failed: $t")
        null
    }

    private fun pushCurrentStatus(reason: String) {
        val app = currentApplication() ?: return
        pushStatus(app.applicationContext, reason)
    }

    private fun pushStatus(context: Context, reason: String) {
        try {
            val pref = readPreferences()
            val token = pref.getString(StatusProtocol.PREF_TOKEN, null)
            if (token.isNullOrBlank()) {
                log("status push skipped reason=$reason token=missing")
                return
            }

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

            val push = Intent(StatusProtocol.ACTION_PUSH)
                .setClassName(
                    BuildConfig.APPLICATION_ID,
                    "com.chenyue404.gboardhook.RuntimeStatusReceiver"
                )
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
                .putExtra(StatusProtocol.EXTRA_WATCHERS, RuntimeStatus.hookSummary())
                .putExtra(StatusProtocol.EXTRA_OBSERVED_PATHS, RuntimeStatus.observedSummary())
                .putExtra(StatusProtocol.EXTRA_REWRITE_PROOF, RuntimeStatus.capacityProof)
                .putExtra(StatusProtocol.EXTRA_LAST_ERROR, RuntimeStatus.lastError)
                .putExtra(StatusProtocol.EXTRA_TIMESTAMP_MS, System.currentTimeMillis())

            context.sendBroadcast(push)
            log(
                "status push sent reason=$reason proof=${RuntimeStatus.capacityProof} " +
                    "paths=${RuntimeStatus.observedSummary()}"
            )
        } catch (t: Throwable) {
            RuntimeStatus.callbackError("status-push", t)
            log("status push failed reason=$reason: $t")
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
                        val changed = RuntimeStatus.observe(
                            "provider-legacy",
                            provesCapacityHandling = matchesEffectiveLimit(
                                param.args.getOrNull(4)?.toString()
                            )
                        )
                        if (changed) pushCurrentStatus("provider-legacy")
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
                            queryArgs.getString(ContentResolver.QUERY_ARG_SQL_LIMIT)
                                ?.let { "limit $it" }
                        ) || (
                            queryArgs.containsKey(ContentResolver.QUERY_ARG_LIMIT) &&
                                queryArgs.getInt(ContentResolver.QUERY_ARG_LIMIT) ==
                                effectiveCapacity()
                            )
                        val changed = RuntimeStatus.observe("provider-bundle", proof)
                        if (changed) pushCurrentStatus("provider-bundle")
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
                        val changed = RuntimeStatus.observe(
                            "sqlite-query",
                            provesCapacityHandling = param.args.getOrNull(7)?.toString() ==
                                effectiveCapacity().toString()
                        )
                        if (changed) pushCurrentStatus("sqlite-query")
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
                        val changed = RuntimeStatus.observe(
                            "sqlite-rawQuery",
                            provesCapacityHandling = matchesEffectiveLimit(sql)
                        )
                        if (changed) pushCurrentStatus("sqlite-rawQuery")
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
                            val changed = RuntimeStatus.observe("hashset-compat", applies)
                            if (changed) {
                                log(
                                    "capacity hook proof path=hashset-compat " +
                                        "setSize=${map.size} effectiveCapacity=${effectiveCapacity()}"
                                )
                                pushCurrentStatus("hashset-compat")
                            }
                        } catch (t: Throwable) {
                            val changed = RuntimeStatus.callbackError("hashset-compat", t)
                            log("hashset telemetry failed: $t")
                            if (changed) pushCurrentStatus("hashset-error")
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
