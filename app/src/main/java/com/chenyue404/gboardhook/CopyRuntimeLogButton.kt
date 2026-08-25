package com.chenyue404.gboardhook

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.AttributeSet
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

class CopyRuntimeLogButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.buttonStyle
) : Button(context, attrs, defStyleAttr) {

    init {
        setOnClickListener {
            val status = rootView.findViewById<TextView>(R.id.tvStatus)
                ?.text
                ?.toString()
                .orEmpty()

            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(
                ClipData.newPlainText(
                    context.getString(R.string.runtime_status_title),
                    status
                )
            )
            Toast.makeText(context, R.string.runtime_log_copied, Toast.LENGTH_SHORT).show()
        }
    }
}
