package com.chenyue404.gboardhook

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.net.toUri

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val sw0 = findViewById<Switch>(R.id.sw0)
        val et0 = findViewById<EditText>(R.id.et0)
        val et1 = findViewById<EditText>(R.id.et1)
        val bt0 = findViewById<Button>(R.id.bt0)
        val swLog = findViewById<Switch>(R.id.swLog)

        val pref: SharedPreferences? = try {
            getSharedPreferences(PluginEntry.SP_FILE_NAME, MODE_WORLD_READABLE)
        } catch (e: SecurityException) {
            Log.d("MainActivity", "getSharedPreferences failed: $e")
            Toast.makeText(this, R.string.config_read_failed, Toast.LENGTH_SHORT).show()
            null
        }

        pref?.getString(PluginEntry.SP_KEY, null)?.split(",")?.let { list ->
            et0.text.append(list.getOrNull(0).orEmpty())
            et1.text.append(list.getOrNull(1).orEmpty())
            sw0.isChecked = list.getOrNull(2)?.equals("true", true) ?: false
        }
        swLog.isChecked = pref?.getBoolean(PluginEntry.SP_KEY_LOG, false) ?: false

        bt0.setOnClickListener {
            val num = et0.text.toString().toIntOrNull()?.coerceAtLeast(1)
                ?: PluginEntry.DEFAULT_NUM
            val time = et1.text.toString().toLongOrNull()?.coerceAtLeast(0L)
                ?: PluginEntry.DEFAULT_TIME
            val switchOn = sw0.isChecked.toString()
            pref?.edit()?.apply {
                putString(PluginEntry.SP_KEY, "$num,$time,$switchOn")
                putBoolean(PluginEntry.SP_KEY_LOG, swLog.isChecked)
                apply()
            }

            startActivity(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    addCategory(Intent.CATEGORY_DEFAULT)
                    data = "package:${PluginEntry.PACKAGE_NAME}".toUri()
                }
            )
        }

        findViewById<TextView>(R.id.tvHint).setOnClickListener {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    "https://github.com/Lycidias93/GboardHookah".toUri()
                )
            )
        }
    }
}
