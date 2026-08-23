package com.chenyue404.gboardhook

import android.net.Uri
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Read-only runtime proof for the current Gboard legacy clipboard-history path.
 *
 * Some Gboard builds query the clipboard provider with `timestamp DESC` and no SQL
 * LIMIT at all. In that case there is nothing for the primary hook to rewrite, but
 * the path is already unbounded at provider level. This observer reports that fact
 * without changing the query.
 */
class HistoryCapacityProofEntry : IXposedHookLoadPackage {
    companion object {
        private const val CLIPBOARD_PROVIDER =
            "com.google.android.apps.inputmethod.libs.clipboard.ClipboardContentProvider"
        private val LIMIT_REGEX = Regex("(?i)\\blimit\\s+\\d+")
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != PluginEntry.PACKAGE_NAME) return

        try {
            XposedHelpers.findAndHookMethod(
                CLIPBOARD_PROVIDER,
                lpparam.classLoader,
                "query",
                Uri::class.java,
                Array<String>::class.java,
                String::class.java,
                Array<String>::class.java,
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val selection = param.args[2]?.toString().orEmpty()
                        val sortOrder = param.args[4]?.toString().orEmpty()
                        val isHistoryQuery = selection.contains("timestamp", ignoreCase = true) &&
                            sortOrder.contains("timestamp", ignoreCase = true)
                        val isAlreadyUnbounded = isHistoryQuery &&
                            !LIMIT_REGEX.containsMatchIn(sortOrder)

                        if (isAlreadyUnbounded) {
                            RuntimeStatus.hookReady("history-capacity-unbounded")
                            if (RuntimeStatus.observe("provider-legacy-unbounded", true)) {
                                StatusPluginEntryV3.notifyRuntimeChanged(
                                    "history-provider-already-unbounded"
                                )
                            }
                        }
                    }
                }
            )
            RuntimeStatus.hookReady("history-capacity-proof-observer")
        } catch (t: Throwable) {
            RuntimeStatus.hookError("history-capacity-proof-observer", t)
        }
    }
}
