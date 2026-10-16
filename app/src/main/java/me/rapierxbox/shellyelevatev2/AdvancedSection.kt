package me.rapierxbox.shellyelevatev2

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible

// a collapsed block of rarely needed settings under a tappable header
object AdvancedSection {
    fun bind(header: TextView, content: View) {
        val title = header.context.getString(R.string.settings_advanced)
        fun show(open: Boolean) {
            content.isVisible = open
            header.text = header.context.getString(if (open) R.string.settings_advanced_open else R.string.settings_advanced_closed, title)
        }
        show(false)
        header.setOnClickListener { show(!content.isVisible) }
    }

    // header plus an empty block added to parent for rows built in code
    fun create(parent: ViewGroup): LinearLayout {
        val header = LayoutInflater.from(parent.context).inflate(R.layout.settings_advanced_header, parent, false) as TextView
        val content = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        parent.addView(header)
        parent.addView(content)
        bind(header, content)
        return content
    }
}
