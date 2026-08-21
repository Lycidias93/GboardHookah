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

    companion object {
        private const val SP_KEY_MANUAL_CAPACITY = "manual_clipboard_capacity"
        private const val AUTO_CAPACITY = Int.MAX_VALUE
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

        val pref: SharedPreferences? = try {
            getSharedPreferences(PluginEntry.SP_FILE_NAME, MODE_WORLD_READABLE)
        } catch (e: SecurityException) {
            Log.d("MainActivity", "getSharedPreferences failed: $e")
            Toast.makeText(this, R.string.config_read_failed, Toast.LENGTH_SHORT).show()
            null
        }

        val storedConfig = pref?.getString(PluginEntry.SP_KEY, null)?.split(",")
        val storedCapacity = storedConfig?.getOrNull(0)?.toIntOrNull()
        val fallbackManualCapacity = when (storedCapacity) {
            null, AUTO_CAPACITY -> PluginEntry.DEFAULT_NUM
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
            val effectiveNum = if (swSyncAndroidClipboardCapacity.isChecked) {
                AUTO_CAPACITY
            } else {
                manualNum
            }
            val time = et1.text.toString().toLongOrNull()?.coerceAtLeast(0L)
                ?: PluginEntry.DEFAULT_TIME
            val switchOn = sw0.isChecked.toString()

            pref.edit().apply {
                putString(PluginEntry.SP_KEY, "$effectiveNum,$time,$switchOn")
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
