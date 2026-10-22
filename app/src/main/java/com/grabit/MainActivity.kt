package com.grabit

import android.Manifest
import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private class Row(
        val item: Item,
        val root: LinearLayout,
        val title: TextView,
        val bar: ProgressBar,
        val info: TextView,
        val main: Button
    )

    private val h = Handler(Looper.getMainLooper())
    private lateinit var field: EditText
    private lateinit var list: LinearLayout
    private val rows = HashMap<Int, Row>()
    private var lastClip = ""
    private var pendingUrl: String? = null

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            h.postDelayed(this, 600)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun findUrl(t: String): String? {
        val m = Regex("https?://[^\\s<>\"']+", RegexOption.IGNORE_CASE).find(t) ?: return null
        return m.value.trimEnd('.', ',', ')', ';')
    }

    private fun hasStorage(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun askStorage() {
        toast("Allow access to files, then come back to this app.")
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
                )
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 2)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16), dp(36), dp(16), dp(12))
        root.setBackgroundColor(Color.WHITE)

        val title = TextView(this)
        title.text = "GrabIt"
        title.textSize = 28f
        title.setTypeface(null, Typeface.BOLD)
        title.setTextColor(Color.BLACK)

        val sub = TextView(this)
        sub.text = "Paste or share a direct download link. Files are saved in Download/GrabIt."
        sub.textSize = 13f
        sub.setTextColor(Color.DKGRAY)

        field = EditText(this)
        field.hint = "https://... link"
        field.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        field.setSingleLine(true)
        field.setTextColor(Color.BLACK)

        val btns = LinearLayout(this)
        btns.orientation = LinearLayout.HORIZONTAL
        val paste = Button(this)
        paste.text = "Paste"
        paste.setOnClickListener { pasteClip() }
        val go = Button(this)
        go.text = "Download"
        go.setOnClickListener { startFromField() }
        btns.addView(paste, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        btns.addView(go, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        val scroll = ScrollView(this)
        scroll.addView(list, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(title)
        root.addView(sub)
        root.addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(btns, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i == null || i.action != Intent.ACTION_SEND) return
        val t = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val u = findUrl(t)
        if (u == null) {
            toast("No link found in what you shared.")
            return
        }
        field.setText(u)
        startFromField()
    }

    override fun onResume() {
        super.onResume()
        val p = pendingUrl
        if (p != null && hasStorage()) {
            pendingUrl = null
            begin(p)
            field.setText("")
        }
        h.post(tick)
    }

    override fun onPause() {
        super.onPause()
        h.removeCallbacks(tick)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        try {
            val u = findUrl(clipText()) ?: return
            if (u != lastClip && field.text.isEmpty()) {
                lastClip = u
                field.setText(u)
                toast("Copied link found. Tap Download.")
            }
        } catch (e: Exception) {
        }
    }

    private fun clipText(): String {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip ?: return ""
        if (clip.itemCount == 0) return ""
        return clip.getItemAt(0).coerceToText(this)?.toString() ?: ""
    }

    private fun pasteClip() {
        val u = try { findUrl(clipText()) } catch (e: Exception) { null }
        if (u == null) toast("No link found in the clipboard.") else field.setText(u)
    }

    private fun startFromField() {
        val url = findUrl(field.text.toString())
        if (url == null) {
            toast("Paste a link that starts with http:// or https://")
            return
        }
        if (!hasStorage()) {
            pendingUrl = url
            askStorage()
            return
        }
        begin(url)
        field.setText("")
    }

    private fun begin(url: String) {
        val exists = Store.items.any {
            it.url == url && (it.status == Status.QUEUED || it.status == Status.DOWNLOADING || it.status == Status.PAUSED)
        }
        if (exists) {
            toast("That link is already in the list.")
            return
        }
        kick(Store.add(url))
    }

    private fun kick(item: Item) {
        item.pause = false
        item.cancel = false
        item.status = Status.QUEUED
        startForegroundService(Intent(this, DlService::class.java).putExtra("id", item.id))
    }

    private fun refresh() {
        for (item in Store.items) {
            if (!rows.containsKey(item.id)) addRow(item)
        }
        val gone = ArrayList<Int>()
        for (entry in rows.entries) {
            val row = entry.value
            if (row.item.status == Status.CANCELLED) {
                list.removeView(row.root)
                gone.add(entry.key)
            } else {
                update(row)
            }
        }
        for (id in gone) {
            rows.remove(id)
            Store.items.removeIf { it.id == id }
        }
    }

    private fun addRow(item: Item) {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(12), dp(10), dp(12), dp(10))
        root.setBackgroundColor(0xFFF2F4F7.toInt())

        val title = TextView(this)
        title.textSize = 15f
        title.setTypeface(null, Typeface.BOLD)
        title.setTextColor(Color.BLACK)
        title.maxLines = 2

        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        bar.max = 1000

        val info = TextView(this)
        info.textSize = 12f
        info.setTextColor(Color.DKGRAY)

        val br = LinearLayout(this)
        br.orientation = LinearLayout.HORIZONTAL
        br.gravity = Gravity.END
        val main = Button(this)
        val x = Button(this)
        x.text = "X"
        br.addView(main)
        br.addView(x)

        root.addView(title)
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(info)
        root.addView(br, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val row = Row(item, root, title, bar, info, main)
        main.setOnClickListener { onMain(row) }
        x.setOnClickListener { onRemove(row) }
        rows[item.id] = row

        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(8)
        list.addView(root, 0, lp)
        update(row)
    }

    private fun update(r: Row) {
        val i = r.item
        r.title.text = if (i.name.isNotEmpty()) i.name else i.url
        val known = i.total > 0
        r.bar.isIndeterminate = !known && i.status == Status.DOWNLOADING
        if (i.status == Status.DONE) {
            r.bar.isIndeterminate = false
            r.bar.progress = 1000
        } else if (known) {
            r.bar.progress = (i.done * 1000 / i.total).toInt()
        }
        val pct = if (known) " - " + (i.done * 100 / i.total) + "%" else ""
        val size = if (known) fmtBytes(i.done) + " / " + fmtBytes(i.total) else fmtBytes(i.done)
        r.info.text = when (i.status) {
            Status.QUEUED -> "Waiting..."
            Status.DOWNLOADING -> size + pct + " - " + fmtBytes(i.speed) + "/s" +
                (if (i.error.isNotEmpty()) "\n" + i.error else "")
            Status.PAUSED -> "Paused - " + size + pct
            Status.DONE -> "Done. Saved as Download/GrabIt/" + i.savedAs
            Status.FAILED -> "Failed: " + i.error
            Status.CANCELLED -> ""
        }
        r.main.text = when (i.status) {
            Status.QUEUED, Status.DOWNLOADING -> if (i.pause) "Pausing..." else "Pause"
            Status.PAUSED -> "Resume"
            Status.FAILED -> "Retry"
            Status.DONE, Status.CANCELLED -> "Clear"
        }
    }

    private fun removeRow(r: Row) {
        list.removeView(r.root)
        rows.remove(r.item.id)
        Store.items.remove(r.item)
    }

    private fun onMain(r: Row) {
        val i = r.item
        when (i.status) {
            Status.QUEUED, Status.DOWNLOADING -> i.pause = true
            Status.PAUSED, Status.FAILED -> {
                if (!hasStorage()) {
                    askStorage()
                } else {
                    kick(i)
                }
            }
            Status.DONE, Status.CANCELLED -> removeRow(r)
        }
    }

    private fun onRemove(r: Row) {
        val i = r.item
        when (i.status) {
            Status.QUEUED, Status.DOWNLOADING -> i.cancel = true
            Status.PAUSED, Status.FAILED -> {
                Downloader.deletePart(i)
                i.status = Status.CANCELLED
            }
            Status.DONE, Status.CANCELLED -> removeRow(r)
        }
    }
}
