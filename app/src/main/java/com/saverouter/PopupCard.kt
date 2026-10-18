package com.saverouter

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.util.concurrent.ExecutorService

/**
 * The "Where should this go?" card, drawn in the middle of the screen by the watcher itself.
 * It stays until the person picks a place or clicks "Keep in Downloads".
 * Needs the "Display over other apps" permission.
 */
class PopupCard(private val svc: Service, private val store: Store, private val pool: ExecutorService) {
    private val ctx = ContextThemeWrapper(svc, android.R.style.Theme_DeviceDefault_DayNight)
    private val wm = svc.getSystemService(WindowManager::class.java)
    private val nm = svc.getSystemService(NotificationManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<Pair<File, Int>>()
    private var shown: View? = null
    private var shownPath: String? = null
    var lastError: String? = null

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun show(f: File, nid: Int) {
        if (f.path == shownPath || queue.any { it.first.path == f.path }) return
        queue.addLast(f to nid)
        if (shown == null) next()
    }

    fun closeAll() {
        queue.clear()
        remove()
    }

    private fun remove() {
        shown?.let { runCatching { wm.removeView(it) } }
        shown = null
        shownPath = null
    }

    private fun closeAndNext() {
        remove()
        next()
    }

    private fun next() {
        while (true) {
            val (f, nid) = queue.removeFirstOrNull() ?: return
            if (f.exists()) { build(f, nid); return }
        }
    }

    private fun color(attr: Int): Int {
        val tv = TypedValue()
        ctx.theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    private fun text(t: String, size: Float, bold: Boolean = false, muted: Boolean = false) = TextView(ctx).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        if (muted) alpha = 0.75f
    }

    private fun build(f: File, nid: Int) {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(color(android.R.attr.colorBackground))
                setStroke(dp(1), 0x40808080)
            }
            elevation = dp(16).toFloat()
        }
        fun add(v: View, w: Int = ViewGroup.LayoutParams.MATCH_PARENT) = card.addView(v, LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Yellow "curb" stripe, same as the app icon.
        add(View(ctx).apply {
            background = GradientDrawable().apply { cornerRadius = dp(3).toFloat(); setColor(0xFFF2C200.toInt()) }
            minimumHeight = dp(6)
        })
        add(text("Where should this go?", 21f, bold = true).apply { setPadding(0, dp(12), 0, dp(2)) })
        add(text(f.name, 15f).apply { maxLines = 2 })
        add(text(Saver.human(f.length()) + " · in Downloads now", 13f, muted = true).apply { setPadding(0, 0, 0, dp(10)) })

        val status = text("", 14f).apply { setPadding(0, dp(6), 0, dp(4)) }
        val buttons = mutableListOf<Button>()
        fun enable(on: Boolean) = buttons.forEach { it.isEnabled = on }

        for (d in store.dests()) {
            val b = Button(ctx).apply {
                text = d.label
                isAllCaps = false
                textSize = 16f
                setOnClickListener {
                    if (d.kind == "drive") openChooser(f, nid, d.id) else save(f, nid, d, status, ::enable)
                }
            }
            buttons.add(b); add(b)
        }
        val more = Button(ctx, null, android.R.attr.borderlessButtonStyle).apply {
            text = "More options… (several places, rename, other folders)"
            isAllCaps = false
            setOnClickListener { openChooser(f, nid, null) }
        }
        buttons.add(more); add(more)
        add(status)
        val keep = Button(ctx, null, android.R.attr.borderlessButtonStyle).apply {
            text = "Keep in Downloads"
            isAllCaps = false
            setOnClickListener { nm.cancel(nid); closeAndNext() }
        }
        buttons.add(keep)
        add(keep, ViewGroup.LayoutParams.WRAP_CONTENT)
        if (queue.isNotEmpty()) add(text("${queue.size} more waiting", 12f, muted = true))

        val wrap = FrameLayout(ctx).apply { setPadding(dp(14), dp(14), dp(14), dp(14)) }
        wrap.addView(card)
        val width = minOf(dp(460), ctx.resources.displayMetrics.widthPixels - dp(24))
        val lp = WindowManager.LayoutParams(
            width, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER; title = "Save Router" }
        try {
            wm.addView(wrap, lp)
            shown = wrap
            shownPath = f.path
            lastError = null
        } catch (e: Exception) {
            lastError = e.javaClass.simpleName + ": " + (e.message ?: "")
            // Permission was turned off: the notification is still there as a fallback.
            shown = null
            shownPath = null
        }
    }

    private fun save(f: File, nid: Int, d: Dest, status: TextView, enable: (Boolean) -> Unit) {
        enable(false)
        status.text = "Saving to ${d.label}…"
        pool.execute {
            try {
                val saved = Saver.save(svc, Source.fromFile(f), d, f.name) { b ->
                    main.post { status.text = "Saving to ${d.label}… ${Saver.human(b)}" }
                }
                if (store.deleteAfter) Saver.removeOriginal(svc, f)
                main.post {
                    status.text = "✓  Saved to ${d.label} as $saved"
                    nm.cancel(nid)
                    main.postDelayed({ if (shownPath == f.path) closeAndNext() }, 1800)
                }
            } catch (e: Exception) {
                main.post {
                    status.text = "✗  ${Smb.friendly(e, d)}"
                    enable(true)
                }
            }
        }
    }

    private fun openChooser(f: File, nid: Int, preselect: String?) {
        val i = Intent(svc, ChooserActivity::class.java)
            .setData(android.net.Uri.fromParts("saverouter", nid.toString(), null))
            .putExtra(ChooserActivity.EXTRA_PATH, f.absolutePath)
            .putExtra(ChooserActivity.EXTRA_NOTIF, nid)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        if (preselect != null) i.putExtra(ChooserActivity.EXTRA_PRESELECT, preselect)
        runCatching { svc.startActivity(i) }
        closeAndNext()
    }
}
