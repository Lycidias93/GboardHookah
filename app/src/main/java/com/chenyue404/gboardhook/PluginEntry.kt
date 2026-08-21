package com.chenyue404.gboardhook

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import androidx.core.content.edit
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.XposedHelpers.ClassNotFoundError
import de.robv.android.xposed.XposedHelpers.findAndHookMethod
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.wrap.DexMethod
import java.lang.System.loadLibrary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PluginEntry : IXposedHookLoadPackage {
    companion object {
        const val SP_FILE_NAME = "GboardinHook"
        const val SP_KEY = "key"
        const val SP_KEY_LOG = "key_log"
        const val TAG = "xposed-GboardHookah-"
        const val PACKAGE_NAME = "com.google.android.inputmethod.latin"
        const val DAY: Long = 1000 * 60 * 60 * 24
        const val DEFAULT_NUM = 10
        const val DEFAULT_TIME = DAY * 3

        private const val CLIPBOARD_PROVIDER =
            "com.google.android.apps.inputmethod.libs.clipboard.ClipboardContentProvider"
        private val LIMIT_REGEX = Regex(
            "\\blimit\\s+\\d+(?:\\s*,\\s*\\d+)?\\b",
            RegexOption.IGNORE_CASE
        )
        private val TIMESTAMP_FILTER_REGEX = Regex(
            "\\btimestamp\\s*>=\\s*\\?",
            RegexOption.IGNORE_CASE
        )
        private val CLIPBOARD_SQL_REGEX = Regex(
            "\\b(?:clips|clipboard_content|clipboard_items?)\\b",
            RegexOption.IGNORE_CASE
        )
    }

    init {
        loadLibrary("dexkit")
    }

    private fun getPref(): XSharedPreferences? {
        val pref = XSharedPreferences(BuildConfig.APPLICATION_ID, SP_FILE_NAME)
        return if (pref.file.canRead()) pref else null
    }

    private val clipboardTextSize by lazy {
        getPref()?.getString(SP_KEY, null)?.split(",")?.getOrNull(0)?.toIntOrNull()
            ?.coerceAtLeast(1)
            ?: DEFAULT_NUM
    }

    private val clipboardTextTime by lazy {
        getPref()?.getString(SP_KEY, null)?.split(",")?.getOrNull(1)?.toLongOrNull()
            ?.coerceAtLeast(0L)
            ?: DEFAULT_TIME
    }

    private val logSwitch by lazy {
        getPref()?.getBoolean(SP_KEY_LOG, false) ?: false
    }

    private fun log(str: String) {
        if (logSwitch) {
            XposedBridge.log(TAG + "\n" + str)
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val packageName = lpparam.packageName
        val classLoader = lpparam.classLoader
        val ignorePackageLimit = getPref()
            ?.getString(SP_KEY, null)
            ?.split(",")
            ?.getOrNull(2)
            ?.equals("true", true) == true

        if (packageName != PACKAGE_NAME && !ignorePackageLimit) {
            return
        }

        log("handleLoadPackage: $packageName, capacity=$clipboardTextSize, retentionMs=$clipboardTextTime")

        hookGboardFlags(classLoader)
        hookClipboardProviderLegacy(classLoader)
        hookClipboardProviderBundle(classLoader)
        hookSQLiteClipboardQueries()
        hookHashSetCompatibility()
    }

    private fun hookGboardFlags(classLoader: ClassLoader) {
        findAndHookMethod(
            Application::class.java,
            "attach",
            Context::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val dexBridge by lazy { DexKitBridge.create(classLoader, true) }
                        val context = param.args.first() as Context
                        val sp = context.getSharedPreferences("gboard_hook", Context.MODE_PRIVATE)
                        val spKeyMethodReadConfig = "SP_KEY_METHOD_READ_CONFIG"
                        val spKeyVersion = "SP_KEY_VERSION"
                        val versionCode = context.packageManager
                            .getPackageInfo(context.packageName, 0)
                            .versionCode
                        val cachedVersion = sp.getInt(spKeyVersion, -1)
                        val cachedMethod = sp.getString(spKeyMethodReadConfig, null)?.let {
                            try {
                                DexMethod(it)
                            } catch (e: Exception) {
                                log("invalid cached ReadConfig method: $it")
                                null
                            }
                        }

                        val readConfigMethod = if (cachedVersion == versionCode && cachedMethod != null) {
                            cachedMethod
                        } else {
                            findReadConfigMethod(dexBridge)?.also { method ->
                                sp.edit {
                                    putInt(spKeyVersion, versionCode)
                                    putString(spKeyMethodReadConfig, method.serialize())
                                }
                            }
                        }

                        readConfigMethod?.let { hookReadConfig(it, classLoader) }
                    } catch (t: Throwable) {
                        log("flag hook setup failed: $t")
                    }
                }
            }
        )
    }

    private fun hookClipboardProviderLegacy(classLoader: ClassLoader) {
        tryHook("$CLIPBOARD_PROVIDER#query-legacy") { name ->
            findAndHookMethod(
                CLIPBOARD_PROVIDER,
                classLoader,
                "query",
                Uri::class.java,
                Array<String>::class.java,
                String::class.java,
                Array<String>::class.java,
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val selection = param.args[2]?.toString().orEmpty()
                            val selectionArgs = param.args[3] as? Array<String>
                            val sortOrder = param.args[4]?.toString()
                            log("$name selection=$selection sortOrder=$sortOrder")

                            rewriteSelectionArgs(selection, selectionArgs)?.let {
                                param.args[3] = it
                            }
                            rewriteLimitString(sortOrder)?.let {
                                param.args[4] = it
                                log("legacy limit rewritten: $it")
                            }
                        } catch (t: Throwable) {
                            log("legacy query callback failed: $t")
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        logCursorCount("legacy query", param.result)
                    }
                }
            )
        }
    }

    private fun hookClipboardProviderBundle(classLoader: ClassLoader) {
        tryHook("$CLIPBOARD_PROVIDER#query-bundle") { name ->
            findAndHookMethod(
                CLIPBOARD_PROVIDER,
                classLoader,
                "query",
                Uri::class.java,
                Array<String>::class.java,
                Bundle::class.java,
                CancellationSignal::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val originalArgs = param.args[2] as? Bundle ?: return
                            val queryArgs = Bundle(originalArgs)
                            val selection = queryArgs
                                .getString(ContentResolver.QUERY_ARG_SQL_SELECTION)
                                .orEmpty()
                            val selectionArgs = queryArgs
                                .getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS)
                            val sortOrder = queryArgs
                                .getString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER)
                            val sqlLimit = queryArgs
                                .getString(ContentResolver.QUERY_ARG_SQL_LIMIT)
                            val structuredLimit = if (
                                queryArgs.containsKey(ContentResolver.QUERY_ARG_LIMIT)
                            ) {
                                queryArgs.getInt(ContentResolver.QUERY_ARG_LIMIT)
                            } else {
                                null
                            }

                            log(
                                "$name selection=$selection sortOrder=$sortOrder " +
                                    "sqlLimit=$sqlLimit limit=$structuredLimit"
                            )

                            rewriteSelectionArgs(selection, selectionArgs)?.let {
                                queryArgs.putStringArray(
                                    ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                                    it
                                )
                            }

                            rewriteLimitString(sortOrder)?.let {
                                queryArgs.putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, it)
                                log("bundle sort limit rewritten: $it")
                            }

                            if (queryArgs.containsKey(ContentResolver.QUERY_ARG_SQL_LIMIT)) {
                                queryArgs.putString(
                                    ContentResolver.QUERY_ARG_SQL_LIMIT,
                                    clipboardTextSize.toString()
                                )
                                log("bundle SQL limit forced to $clipboardTextSize")
                            }

                            if (queryArgs.containsKey(ContentResolver.QUERY_ARG_LIMIT)) {
                                queryArgs.putInt(
                                    ContentResolver.QUERY_ARG_LIMIT,
                                    clipboardTextSize
                                )
                                log("bundle structured limit forced to $clipboardTextSize")
                            }

                            param.args[2] = queryArgs
                        } catch (t: Throwable) {
                            log("bundle query callback failed: $t")
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        logCursorCount("bundle query", param.result)
                    }
                }
            )
        }
    }

    private fun hookSQLiteClipboardQueries() {
        tryHook("SQLiteDatabase#query-limit") {
            findAndHookMethod(
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
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        rewriteSqliteQuery(param, 0, 2, 3, 7, "sqlite query")
                    }
                }
            )
        }

        tryHook("SQLiteDatabase#query-limit-cancel") {
            findAndHookMethod(
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
                CancellationSignal::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        rewriteSqliteQuery(param, 0, 2, 3, 7, "sqlite query cancel")
                    }
                }
            )
        }

        tryHook("SQLiteDatabase#query-distinct") {
            findAndHookMethod(
                SQLiteDatabase::class.java,
                "query",
                java.lang.Boolean.TYPE,
                String::class.java,
                Array<String>::class.java,
                String::class.java,
                Array<String>::class.java,
                String::class.java,
                String::class.java,
                String::class.java,
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        rewriteSqliteQuery(param, 1, 3, 4, 8, "sqlite query distinct")
                    }
                }
            )
        }

        tryHook("SQLiteDatabase#rawQuery") {
            findAndHookMethod(
                SQLiteDatabase::class.java,
                "rawQuery",
                String::class.java,
                Array<String>::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        rewriteRawSql(param, 0, "rawQuery")
                    }
                }
            )
        }

        tryHook("SQLiteDatabase#rawQuery-cancel") {
            findAndHookMethod(
                SQLiteDatabase::class.java,
                "rawQuery",
                String::class.java,
                Array<String>::class.java,
                CancellationSignal::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        rewriteRawSql(param, 0, "rawQuery cancel")
                    }
                }
            )
        }
    }

    private fun rewriteSqliteQuery(
        param: XC_MethodHook.MethodHookParam,
        tableIndex: Int,
        selectionIndex: Int,
        selectionArgsIndex: Int,
        limitIndex: Int,
        label: String
    ) {
        try {
            val table = param.args.getOrNull(tableIndex)?.toString()
            if (!isClipboardTable(table)) {
                return
            }

            val selection = param.args.getOrNull(selectionIndex)?.toString().orEmpty()
            val selectionArgs = param.args.getOrNull(selectionArgsIndex) as? Array<String>
            val oldLimit = param.args.getOrNull(limitIndex)?.toString()
            log("$label table=$table selection=$selection limit=$oldLimit")

            rewriteSelectionArgs(selection, selectionArgs)?.let {
                param.args[selectionArgsIndex] = it
            }

            param.args[limitIndex] = clipboardTextSize.toString()
            log("$label limit forced to $clipboardTextSize")
        } catch (t: Throwable) {
            log("$label callback failed: $t")
        }
    }

    private fun rewriteRawSql(
        param: XC_MethodHook.MethodHookParam,
        sqlIndex: Int,
        label: String
    ) {
        try {
            val sql = param.args.getOrNull(sqlIndex) as? String ?: return
            if (!CLIPBOARD_SQL_REGEX.containsMatchIn(sql)) {
                return
            }
            rewriteLimitString(sql)?.let {
                param.args[sqlIndex] = it
                log("$label clipboard limit rewritten")
            }
        } catch (t: Throwable) {
            log("$label callback failed: $t")
        }
    }

    private fun hookHashSetCompatibility() {
        tryHook("HashSet#size") {
            findAndHookMethod(
                HashSet::class.java,
                "size",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val set = param.thisObject as HashSet<*>
                            val instantClassName = "j" + '$' + ".time.Instant"
                            if (set.firstOrNull()?.javaClass?.name == instantClassName) {
                                val map = XposedHelpers.getObjectField(set, "map") as? HashMap<*, *>
                                if (map != null && map.size <= clipboardTextSize) {
                                    param.result = 5
                                }
                            }
                        } catch (t: Throwable) {
                            log("HashSet compatibility callback failed: $t")
                        }
                    }
                }
            )
        }
    }

    private fun isClipboardTable(table: String?): Boolean {
        val normalized = table
            ?.trim()
            ?.trim('`', '"', '[', ']')
            ?.lowercase(Locale.ROOT)
            ?: return false
        return normalized == "clips" || normalized.contains("clipboard")
    }

    private fun rewriteSelectionArgs(
        selection: String,
        selectionArgs: Array<String>?
    ): Array<String>? {
        val match = TIMESTAMP_FILTER_REGEX.find(selection) ?: return null
        if (selectionArgs == null) {
            return null
        }

        var placeholderIndex = 0
        selection.forEachIndexed { index, c ->
            if (index >= match.range.first) {
                return@forEachIndexed
            }
            if (c == '?') {
                placeholderIndex++
            }
        }

        if (placeholderIndex !in selectionArgs.indices) {
            return null
        }

        val updatedArgs = selectionArgs.copyOf()
        val afterTimestamp = System.currentTimeMillis() - clipboardTextTime
        updatedArgs[placeholderIndex] = afterTimestamp.toString()
        val formatted = SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss.SSS",
            Locale.ROOT
        ).format(Date(afterTimestamp))
        log("timestamp rewritten: $formatted")
        return updatedArgs
    }

    private fun rewriteLimitString(value: String?): String? {
        if (value.isNullOrBlank() || !LIMIT_REGEX.containsMatchIn(value)) {
            return null
        }
        return LIMIT_REGEX.replace(value, "limit $clipboardTextSize")
    }

    private fun logCursorCount(prefix: String, result: Any?) {
        try {
            val cursor = result as? Cursor ?: return
            log("$prefix end, count=${cursor.count}")
        } catch (t: Throwable) {
            log("$prefix count failed: $t")
        }
    }

    private fun tryHook(logStr: String, unit: (name: String) -> Unit) {
        try {
            unit(logStr)
        } catch (e: NoSuchMethodError) {
            log("NoSuchMethodError--$logStr")
        } catch (e: ClassNotFoundError) {
            log("ClassNotFoundError--$logStr")
        } catch (t: Throwable) {
            log("HookError--$logStr -- $t")
        }
    }

    private fun findReadConfigMethod(bridge: DexKitBridge): DexMethod? {
        val methodData = bridge.findMethod {
            matcher {
                usingStrings("Invalid flag: ")
                returnType("java.lang.Object")
            }
        }.singleOrNull()

        if (methodData == null) {
            log("Can't find ReadConfig")
            return null
        }
        return methodData.toDexMethod()
    }

    private fun hookReadConfig(dexMethod: DexMethod, classLoader: ClassLoader) {
        val methodName = dexMethod.name
        val className = dexMethod.className
        val tag = "$className#$methodName"
        log(tag)

        tryHook(tag) {
            findAndHookMethod(
                className,
                classLoader,
                methodName,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val name = XposedHelpers
                                .getObjectField(param.thisObject, "a")
                                .toString()
                            if (
                                name == "enable_clipboard_entity_extraction" ||
                                name == "enable_clipboard_query_refactoring"
                            ) {
                                param.result = false
                                log("flag forced false: $name")
                            }
                        } catch (t: Throwable) {
                            log("ReadConfig callback failed: $t")
                        }
                    }
                }
            )
        }
    }
}
