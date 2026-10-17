package com.saverouter

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun Context.vstack(pad: Int = 0): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
}

fun Context.hstack(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

fun Context.label(t: CharSequence, size: Float = 16f, bold: Boolean = false, muted: Boolean = false): TextView =
    TextView(this).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        if (muted) alpha = 0.75f
        setPadding(0, dp(3), 0, dp(3))
    }

fun Context.section(t: String): TextView = label(t.uppercase(), 13f, bold = true).apply {
    letterSpacing = 0.06f
    setPadding(0, dp(22), 0, dp(6))
}

fun Context.btn(t: String, onClick: () -> Unit): Button = Button(this).apply {
    text = t
    isAllCaps = false
    setOnClickListener { onClick() }
}

fun Context.linkBtn(t: String, onClick: () -> Unit): Button =
    Button(this, null, android.R.attr.borderlessButtonStyle).apply {
        text = t
        isAllCaps = false
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setOnClickListener { onClick() }
    }

fun Context.field(hint: String, value: String = "", password: Boolean = false): EditText = EditText(this).apply {
    this.hint = hint
    setText(value)
    isSingleLine = true
    inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
}

fun <T : View> ViewGroup.put(v: T, w: Int = MATCH, h: Int = WRAP): T {
    addView(v, LinearLayout.LayoutParams(w, h))
    return v
}

fun Context.toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

/** Shows content in a scrolling, centered column that stays clear of system bars. */
fun Activity.setScreen(content: View) {
    val frame = FrameLayout(this)
    frame.addView(content, FrameLayout.LayoutParams(MATCH, WRAP))
    val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
    scroll.addView(frame, FrameLayout.LayoutParams(MATCH, WRAP))
    scroll.setOnApplyWindowInsetsListener { v, ins ->
        val s = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime() or WindowInsets.Type.displayCutout())
        v.setPadding(s.left, s.top, s.right, s.bottom)
        WindowInsets.CONSUMED
    }
    scroll.addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ ->
        val side = maxOf(0, (r - l - dp(720)) / 2)
        if (frame.paddingLeft != side) frame.setPadding(side, 0, side, 0)
    }
    setContentView(scroll)
}

fun Activity.askText(title: String, hint: String, value: String = "", onOk: (String) -> Unit) {
    val f = field(hint, value)
    val box = vstack(20).apply { put(f) }
    AlertDialog.Builder(this).setTitle(title).setView(box)
        .setPositiveButton("OK") { _, _ -> f.text.toString().trim().takeIf { it.isNotEmpty() }?.let(onOk) }
        .setNegativeButton("Cancel", null).show()
}

/** Lets the person walk the folders on a NAS share and pick one. */
object FolderBrowser {
    fun show(act: Activity, d: Dest, pw: String, start: String, onPick: (String) -> Unit) {
        var cur = Smb.norm(start)
        val box = act.vstack(20)
        val where = box.put(act.label("", 13f, muted = true))
        val list = box.put(act.vstack())
        val dlg = AlertDialog.Builder(act).setTitle("Choose a folder on your NAS")
            .setView(ScrollView(act).apply { addView(box) })
            .setPositiveButton("Use this folder", null)
            .setNeutralButton("New folder", null)
            .setNegativeButton("Cancel", null)
            .create()

        fun load() {
            where.text = "\\\\${d.host}\\${d.share}" + (if (cur.isEmpty()) "" else "\\$cur")
            list.removeAllViews()
            list.put(act.label("Loading…", muted = true))
            thread {
                val r = runCatching { Smb.listDirs(d, pw, cur) }
                act.runOnUiThread {
                    list.removeAllViews()
                    if (cur.isNotEmpty()) list.put(act.linkBtn("‹  Up one level") {
                        cur = cur.substringBeforeLast('\\', ""); load()
                    })
                    r.onSuccess { dirs ->
                        if (dirs.isEmpty()) list.put(act.label("No folders in here. Use this one, or make a new one.", muted = true))
                        dirs.forEach { name ->
                            list.put(act.linkBtn("📁  $name") {
                                cur = if (cur.isEmpty()) name else "$cur\\$name"; load()
                            })
                        }
                    }.onFailure { list.put(act.label(Smb.friendly(it, d))) }
                }
            }
        }

        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { onPick(cur); dlg.dismiss() }
            dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                act.askText("New folder", "Folder name") { n ->
                    val p = if (cur.isEmpty()) n else "$cur\\$n"
                    thread {
                        val r = runCatching { Smb.mkdir(d, pw, p) }
                        act.runOnUiThread {
                            r.onSuccess { cur = p; load() }.onFailure { act.toast(Smb.friendly(it, d)) }
                        }
                    }
                }
            }
            load()
        }
        dlg.show()
    }
}
