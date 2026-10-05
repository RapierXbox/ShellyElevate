package me.rapierxbox.shellyelevatev2.switcher

import android.content.Context
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import me.rapierxbox.shellyelevatev2.R

// searchable list of launchable apps
object AppPickerDialog {

    fun show(context: Context, onPicked: (AppCatalog.AppEntry) -> Unit) {
        val density = context.resources.displayMetrics.density
        val search = EditText(context).apply {
            setHint(R.string.display_app_search)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }
        val list = ListView(context)
        val adapter = AppListAdapter(context)
        list.adapter = adapter

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (360 * density).toInt()))
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.display_app_pick_title)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        search.doAfterTextChanged { adapter.filter(it?.toString().orEmpty()) }
        list.setOnItemClickListener { _, _, position, _ ->
            onPicked(adapter.getItem(position))
            dialog.dismiss()
        }

        // the catalog may still be loading on first use
        val listener = { adapter.filter(search.text.toString()) }
        AppCatalog.addListener(listener)
        dialog.setOnDismissListener { AppCatalog.removeListener(listener) }
        if (AppCatalog.apps().isEmpty()) AppCatalog.refresh()
        dialog.show()
    }

    private class AppListAdapter(private val context: Context) : BaseAdapter() {
        private var items: List<AppCatalog.AppEntry> = AppCatalog.apps()

        fun filter(query: String) {
            val q = query.trim().lowercase()
            items = AppCatalog.apps().filter {
                q.isEmpty() || it.label.lowercase().contains(q) || it.packageName.contains(q)
            }
            notifyDataSetChanged()
        }

        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_app_pick, parent, false)
            val app = items[position]
            view.findViewById<ImageView>(R.id.appIcon).setImageBitmap(app.icon)
            view.findViewById<TextView>(R.id.appLabel).text = app.label
            view.findViewById<TextView>(R.id.appPackage).text = app.packageName
            return view
        }
    }
}
