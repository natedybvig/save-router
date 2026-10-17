package com.saverouter

import android.app.Activity
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import java.io.File
import kotlin.concurrent.thread

/** The "Where should this go?" screen. Opened from Share, from a download alert, or from the test button. */
class ChooserActivity : Activity() {
    companion object {
        const val EXTRA_PATH = "com.saverouter.PATH"
        const val EXTRA_NOTIF = "com.saverouter.NOTIF"
        const val EXTRA_PRESELECT = "com.saverouter.PRESELECT"
        private const val REQ_ONE = 21
        private const val REQ_TREE = 22
    }

    private lateinit var store: Store
    private val sources = mutableListOf<Source>()
    private var fromDownloads: File? = null
    private val checks = LinkedHashMap<String, CheckBox>()
    private val folderOverride = HashMap<String, String>()
    private val done = HashSet<String>()
    private var nameField: EditText? = null
    private lateinit var deleteBox: CheckBox
    private lateinit var progress: TextView
    private lateinit var saveBtn: Button
    private var busy = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        store = Store(this)
        readSources(intent)

        val root = vstack(20)
        if (sources.isEmpty()) {
            root.put(label("Nothing to save", 22f, bold = true))
            root.put(label("Save Router saves files. This app shared text or a link instead, so there’s no file here to save.", muted = true))
            root.put(btn("Close") { finish() })
            setScreen(root)
            return
        }

        root.put(label(if (sources.size == 1) "Save this file" else "Save ${sources.size} files", 24f, bold = true))
        if (sources.size == 1) {
            root.put(label("File name", 13f, muted = true))
            nameField = root.put(field("File name", sources[0].name))
        } else {
            root.put(label(sources.joinToString("\n") { it.name }, 14f, muted = true))
        }

        root.put(section("Where should it go? Pick one or more"))
        val ds = store.dests()
        if (ds.isEmpty()) {
            root.put(label("You haven’t added any locations yet. Use “Somewhere else…” below, or open Save Router to add your NAS.", muted = true))
        }
        val pre = intent.getStringExtra(EXTRA_PRESELECT)
        for (d in ds) {
            val row = root.put(vstack().apply { setPadding(0, dp(4), 0, dp(6)) })
            val cb = row.put(CheckBox(this).apply {
                text = d.label
                textSize = 18f
                isChecked = d.id == pre || ds.size == 1
            })
            checks[d.id] = cb
            val sub = row.put(label(d.detail(), 13f, muted = true).apply { setPadding(dp(32), 0, 0, 0) })
            if (d.kind == "smb") {
                row.put(linkBtn("Save to a different folder on this NAS…") {
                    FolderBrowser.show(this, d, store.password(d.id), folderOverride[d.id] ?: d.path) { p ->
                        folderOverride[d.id] = p
                        sub.text = d.copy(path = p).detail() + "   (this time only)"
                        cb.isChecked = true
                    }
                }.apply { setPadding(dp(32), 0, 0, 0) })
            }
        }
        root.put(btn("Somewhere else… (opens the file picker: Drive, this device, USB)") { pickOther() })

        deleteBox = root.put(CheckBox(this).apply {
            text = "Remove from Downloads after saving"
            isChecked = store.deleteAfter
            visibility = if (fromDownloads != null) View.VISIBLE else View.GONE
        })
        progress = root.put(label("", 15f).apply { setPadding(0, dp(10), 0, dp(10)) })
        val bar = root.put(hstack())
        saveBtn = bar.put(btn("Save") { startSave() }, WRAP, WRAP)
        bar.put(btn("Cancel") { finish() }, WRAP, WRAP)
        setScreen(root)
    }

    @Suppress("DEPRECATION")
    private fun readSources(i: Intent) {
        i.getStringExtra(EXTRA_PATH)?.let { p ->
            val f = File(p)
            if (f.exists()) { sources.add(Source.fromFile(f)); fromDownloads = f }
        }
        when (i.action) {
            Intent.ACTION_SEND -> {
                val u: Uri? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else i.getParcelableExtra(Intent.EXTRA_STREAM)
                u?.let { sources.add(Source.fromUri(this, it)) }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val l: List<Uri>? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else i.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                l?.forEach { sources.add(Source.fromUri(this, it)) }
            }
        }
        if (sources.isEmpty()) i.clipData?.let { c ->
            for (k in 0 until c.itemCount) c.getItemAt(k).uri?.let { sources.add(Source.fromUri(this, it)) }
        }
    }

    private fun nameFor(index: Int): String {
        val typed = nameField?.text?.toString()?.trim()
        return if (sources.size == 1 && !typed.isNullOrEmpty()) typed else sources[index].name
    }

    private fun ui(block: () -> Unit) = runOnUiThread(block)

    private fun startSave() {
        if (busy) return
        val chosen = store.dests().filter { checks[it.id]?.isChecked == true }
        if (chosen.isEmpty()) { progress.text = "Tick at least one place, or use “Somewhere else…”."; return }
        busy = true
        saveBtn.isEnabled = false
        val direct = chosen.filter { it.kind != "drive" }
        val wantDrive = chosen.any { it.kind == "drive" } && "drive" !in done
        val deleteAfter = fromDownloads != null && deleteBox.isChecked

        thread {
            val lines = mutableListOf<String>()
            var failures = 0
            for (d in direct) for (idx in sources.indices) {
                val key = "${d.id}|$idx"
                if (key in done) continue
                val s = sources[idx]
                val nm = nameFor(idx)
                ui { progress.text = "Saving $nm to ${d.label}…" }
                try {
                    val saved = Saver.save(this, s, d, nm, folderOverride[d.id]) { bytes ->
                        val of = if (s.size > 0) " of " + Saver.human(s.size) else ""
                        ui { progress.text = "Saving $nm to ${d.label}… ${Saver.human(bytes)}$of" }
                    }
                    done.add(key)
                    lines.add("✓  $saved saved to ${d.label}")
                } catch (e: Exception) {
                    failures++
                    lines.add("✗  ${d.label}: ${Smb.friendly(e, d)}")
                }
            }
            var driveIntent: Intent? = null
            if (wantDrive) {
                try {
                    val names = sources.indices.map { nameFor(it) }
                    val uris = sources.indices.map { Saver.stage(this, sources[it], names[it]) }
                    driveIntent = Saver.driveIntent(uris, names)
                } catch (e: Exception) {
                    failures++
                    lines.add("✗  Google Drive: ${e.message}")
                }
            }
            if (failures == 0 && deleteAfter) fromDownloads?.let { Saver.removeOriginal(this, it) }

            val di = driveIntent
            ui {
                busy = false
                if (di != null) lines.add("Google Drive is opening. Pick the account and folder there, then tap Save.")
                progress.text = lines.joinToString("\n")
                if (failures == 0) {
                    val nid = intent.getIntExtra(EXTRA_NOTIF, 0)
                    if (nid != 0) getSystemService(NotificationManager::class.java).cancel(nid)
                }
                if (di != null) {
                    try {
                        startActivity(di); done.add("drive")
                    } catch (e: ActivityNotFoundException) {
                        failures++
                        progress.append("\nThe Google Drive app isn’t installed. Use “Somewhere else…” instead.")
                    }
                }
                if (failures == 0) {
                    saveBtn.text = "Done"
                    saveBtn.isEnabled = true
                    saveBtn.setOnClickListener { finish() }
                    progress.postDelayed({ if (!isFinishing) finish() }, if (di != null) 300 else 1800)
                } else {
                    saveBtn.text = "Try again"
                    saveBtn.isEnabled = true
                }
            }
        }
    }

    private fun pickOther() {
        if (busy) return
        if (sources.size == 1) {
            val n = nameFor(0)
            startActivityForResult(
                Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(Saver.mime(n)).putExtra(Intent.EXTRA_TITLE, n),
                REQ_ONE
            )
        } else {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_TREE)
        }
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val target = data?.data
        if (res != RESULT_OK || target == null) return
        busy = true
        saveBtn.isEnabled = false
        progress.text = "Saving…"
        val deleteAfter = fromDownloads != null && deleteBox.isChecked
        thread {
            val r = runCatching {
                if (req == REQ_ONE) {
                    Saver.writeTo(this, sources[0], target) { b -> ui { progress.text = "Saving… ${Saver.human(b)}" } }
                } else {
                    sources.indices.forEach { Saver.saveToTree(this, sources[it], target, nameFor(it)) }
                }
            }
            if (r.isSuccess && deleteAfter) fromDownloads?.let { Saver.removeOriginal(this, it) }
            ui {
                busy = false
                saveBtn.isEnabled = true
                r.onSuccess {
                    progress.text = "✓  Saved."
                    val nid = intent.getIntExtra(EXTRA_NOTIF, 0)
                    if (nid != 0) getSystemService(NotificationManager::class.java).cancel(nid)
                    progress.postDelayed({ if (!isFinishing) finish() }, 1500)
                }.onFailure { progress.text = "✗  Couldn’t save there: ${it.message}" }
            }
        }
    }
}
