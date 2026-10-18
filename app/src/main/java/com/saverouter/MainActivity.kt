package com.saverouter

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import java.io.File
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var store: Store
    private lateinit var list: LinearLayout
    private lateinit var addDrive: Button
    private lateinit var status: LinearLayout

    companion object { private const val REQ_TREE = 11 }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        store = Store(this)
        Saver.cleanCache(this)
        if (!store.initialized) {
            if (Saver.driveInstalled(this)) store.upsert(Dest(kind = "drive", label = "Google Drive", quick = false))
            store.initialized = true
        }

        val root = vstack(20)
        root.put(label("Save Router", 28f, bold = true))
        root.put(label("Choose where your files go: your NAS, Google Drive, or any folder. It works for new downloads and from Share in any app.", muted = true))

        root.put(section("Your save locations"))
        list = root.put(vstack())
        root.put(btn("Add a NAS folder") { NasDialog(this, store, null) { refresh() }.show() })
        addDrive = root.put(btn("Add Google Drive") {
            store.upsert(Dest(kind = "drive", label = "Google Drive", quick = false)); refresh()
        })
        root.put(btn("Add another folder (this device, a USB drive, or another app)") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_TREE)
        })

        root.put(section("New downloads"))
        status = root.put(vstack())

        root.put(section("Try it"))
        root.put(btn("Save a test file") { sendTestFile() })
        root.put(btn("Test the download pop-up") { testDownload() })
        root.put(label("From any app: choose Share, then “Save to…” (Save Router). In the Files app, right-click a file and choose Share.", 14f, muted = true))

        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
        root.put(label("Version $version", 12f, muted = true).apply { setPadding(0, dp(18), 0, 0) })

        setScreen(root)
    }

    /** Drops a small file into Downloads so the watcher's pop-up can be tried. */
    private fun testDownload() {
        if (!store.watch) { toast("Turn on “Ask where to save each new download” first."); return }
        if (!Environment.isExternalStorageManager()) { toast("Allow access to your Downloads folder first."); return }
        if (store.popup && !Settings.canDrawOverlays(this)) {
            toast("The pop-up card can’t appear yet: “Display over other apps” isn’t allowed. Click Allow next to it.")
            return
        }
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val f = File(dir, "Save Router test ${System.currentTimeMillis() / 1000}.txt")
        runCatching { f.writeText("A test download from Save Router. You can delete this file.\n") }
            .onSuccess { toast("Test file added to Downloads. The pop-up should appear in a few seconds.") }
            .onFailure { toast("Couldn’t write to Downloads: ${it.message}") }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        if (store.watch && Environment.isExternalStorageManager()) WatchService.start(this)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        refresh()
    }

    private fun refresh() {
        list.removeAllViews()
        val ds = store.dests()
        if (ds.isEmpty()) list.put(label("No locations yet. Add your NAS below.", muted = true))
        for (d in ds) {
            val row = list.put(vstack().apply { setPadding(0, dp(6), 0, dp(10)) })
            row.put(label(d.label, 18f, bold = true))
            row.put(label(d.detail(), 13f, muted = true))
            val actions = row.put(hstack())
            if (d.kind != "drive") {
                actions.put(CheckBox(this).apply {
                    text = "Button on download alerts"
                    isChecked = d.quick
                    setOnCheckedChangeListener { _, c -> store.get(d.id)?.let { store.upsert(it.copy(quick = c)) } }
                }, WRAP, WRAP)
            }
            if (d.kind == "smb") actions.put(linkBtn("Edit") { NasDialog(this, store, d) { refresh() }.show() }, WRAP, WRAP)
            actions.put(linkBtn("Remove") { confirmRemove(d) }, WRAP, WRAP)
        }
        addDrive.visibility = if (ds.none { it.kind == "drive" } && Saver.driveInstalled(this)) View.VISIBLE else View.GONE

        status.removeAllViews()
        val sw = Switch(this).apply {
            text = "Ask where to save each new download"
            textSize = 17f
            isChecked = store.watch
        }
        sw.setOnCheckedChangeListener { _, on ->
            store.watch = on
            if (on) {
                if (Environment.isExternalStorageManager()) WatchService.start(this)
                else toast("Allow access to your Downloads folder first (the button below).")
                askNotifications()
            } else WatchService.stop(this)
            refresh()
        }
        status.put(sw)
        if (store.watch) {
            status.put(permRow("Access to your Downloads folder", Environment.isExternalStorageManager()) {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            })
            status.put(permRow("Notifications", notificationsOk()) { askNotifications() })
            status.put(CheckBox(this).apply {
                text = "Show a pop-up card in the middle of the screen (stays until you choose)"
                isChecked = store.popup
                setOnCheckedChangeListener { _, c -> store.popup = c; refresh() }
            })
            if (store.popup) {
                val overlayOk = Settings.canDrawOverlays(this)
                status.put(permRow("Display over other apps (needed for the pop-up)", overlayOk) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                })
                if (!overlayOk) {
                    status.put(label("Switch already on but this still says needed? Googlebook may be treating Save Router as a restricted app. Click the button below, then click ⋮ (top right) and choose “Allow restricted settings”. Then come back and click Allow again.", 13f, muted = true))
                    status.put(btn("Open Save Router’s app info") {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                    })
                }
                if (Build.VERSION.SDK_INT >= 34 &&
                    !getSystemService(android.app.NotificationManager::class.java).canUseFullScreenIntent()) {
                    status.put(permRow("Full-screen alerts (backup for the pop-up)", false) {
                        startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
                    })
                }
                store.lastPopup?.let { status.put(label("Last pop-up attempt: $it", 12f, muted = true)) }
            }
        }
        status.put(CheckBox(this).apply {
            text = "Remove the file from Downloads after it’s saved"
            isChecked = store.deleteAfter
            setOnCheckedChangeListener { _, c -> store.deleteAfter = c }
        })
    }

    private fun notificationsOk() = Build.VERSION.SDK_INT < 33 ||
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun askNotifications() {
        if (!notificationsOk()) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 5)
    }

    private fun permRow(name: String, ok: Boolean, fix: () -> Unit): View {
        val row = hstack()
        row.put(label(if (ok) "✓  $name: allowed" else "!  $name: needed", 15f), 0, WRAP).also {
            (it.layoutParams as LinearLayout.LayoutParams).weight = 1f
        }
        if (!ok) row.put(btn("Allow") { fix() }, WRAP, WRAP)
        return row
    }

    private fun confirmRemove(d: Dest) {
        AlertDialog.Builder(this)
            .setMessage("Remove “${d.label}” from your list? Files already saved there stay put.")
            .setPositiveButton("Remove") { _, _ ->
                if (d.kind == "tree") runCatching {
                    contentResolver.releasePersistableUriPermission(
                        Uri.parse(d.treeUri),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                }
                store.remove(d.id); refresh()
            }
            .setNegativeButton("Cancel", null).show()
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req != REQ_TREE || res != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (e: SecurityException) {
            toast("That location doesn’t allow saving to a remembered folder. Use “Somewhere else…” when saving instead.")
            return
        }
        val docUri = runCatching { DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri)) }.getOrNull()
        val guess = docUri?.let { Saver.displayName(this, it) } ?: "My folder"
        askText("Name this location", "Name", guess) { n ->
            store.upsert(Dest(kind = "tree", label = n, treeUri = uri.toString())); refresh()
        }
    }

    private fun sendTestFile() {
        val dir = File(cacheDir, "handoff/" + System.nanoTime()).apply { mkdirs() }
        val f = File(dir, "Save Router test.txt")
        f.writeText("This is a test file from Save Router. If you can read this on your NAS or in Drive, it works.\n")
        val i = Intent(this, ChooserActivity::class.java)
            .setAction(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, HandoffProvider.uriFor(f))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(i)
    }
}

/** Add or edit a NAS location. */
class NasDialog(private val act: Activity, private val store: Store, private val existing: Dest?, private val onSaved: () -> Unit) {
    fun show() {
        val d0 = existing
        val box = act.vstack(20)
        val name = act.field("Name, like “NAS Photos”", d0?.label ?: "")
        val host = act.field("NAS address, like 192.168.1.20", d0?.host ?: "")
        val share = act.field("Shared folder, like Public", d0?.share ?: "Public")
        val path = act.field("Folder inside it (optional), like Downloads", d0?.path?.replace('\\', '/') ?: "")
        val user = act.field("Username (leave blank for Public)", d0?.user ?: "")
        val pw = act.field("Password", if (d0 != null) store.password(d0.id) else "", password = true)
        val msg = act.label("", 14f)

        box.put(act.label("Name", 13f, muted = true)); box.put(name)
        box.put(act.label("NAS address", 13f, muted = true)); box.put(host)
        val finds = box.put(act.vstack())
        box.put(act.linkBtn("Find my NAS on this network") {
            finds.removeAllViews()
            finds.put(act.label("Looking for 8 seconds…", 14f, muted = true))
            val seen = HashSet<String>()
            NasFinder(act, { n, ip ->
                if (seen.add(ip)) {
                    if (seen.size == 1) finds.removeAllViews()
                    finds.put(act.linkBtn("Use $n  ($ip)") { host.setText(ip); finds.removeAllViews() })
                }
            }, {
                if (seen.isEmpty()) {
                    finds.removeAllViews()
                    finds.put(act.label("Didn’t find one automatically. Type its IP address. It’s in the My Cloud dashboard under Settings › Network, or in your router’s list of devices.", 14f, muted = true))
                }
            }).start()
        })
        box.put(act.label("Shared folder", 13f, muted = true)); box.put(share)
        box.put(act.label("Folder inside it", 13f, muted = true)); box.put(path)
        box.put(act.linkBtn("Browse the NAS for a folder…") {
            val d = build(name, host, share, path, user) ?: return@linkBtn
            FolderBrowser.show(act, d, pw.text.toString(), d.path) { p -> path.setText(p.replace('\\', '/')) }
        })
        box.put(act.label("Sign-in", 13f, muted = true)); box.put(user); box.put(pw)
        box.put(msg)

        val dlg = AlertDialog.Builder(act)
            .setTitle(if (d0 == null) "Add a NAS folder" else "Edit NAS folder")
            .setView(android.widget.ScrollView(act).apply { addView(box) })
            .setPositiveButton("Save", null)
            .setNeutralButton("Test", null)
            .setNegativeButton("Cancel", null)
            .create()

        var saveAnyway = false
        fun runTest(thenSave: Boolean) {
            val d = build(name, host, share, path, user)
            if (d == null) { msg.text = "Fill in the NAS address and the shared folder."; return }
            msg.text = "Connecting…"
            val pass = pw.text.toString()
            thread {
                val r = runCatching { Smb.test(d, pass) }
                act.runOnUiThread {
                    r.onSuccess { n ->
                        msg.text = "✓ Connected. Found $n folder${if (n == 1) "" else "s"} in “${d.share}”."
                        if (thenSave) { save(d, pass); dlg.dismiss() }
                    }.onFailure {
                        msg.text = "✗ " + Smb.friendly(it, d)
                        if (thenSave) {
                            saveAnyway = true
                            dlg.getButton(AlertDialog.BUTTON_POSITIVE).text = "Save anyway"
                        }
                    }
                }
            }
        }

        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { runTest(false) }
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val d = build(name, host, share, path, user)
                if (saveAnyway && d != null) { save(d, pw.text.toString()); dlg.dismiss() } else runTest(true)
            }
        }
        dlg.show()
    }

    private fun build(name: TextView, host: TextView, share: TextView, path: TextView, user: TextView): Dest? {
        val h = host.text.toString().trim().removePrefix("\\\\").removePrefix("smb://").trimEnd('/')
        val s = share.text.toString().trim().trim('/', '\\')
        if (h.isEmpty() || s.isEmpty()) return null
        val label = name.text.toString().trim().ifEmpty { "NAS $s" }
        val base = existing ?: Dest(kind = "smb", label = label)
        return base.copy(label = label, host = h, share = s, path = Smb.norm(path.text.toString()), user = user.text.toString().trim())
    }

    private fun save(d: Dest, pw: String) {
        store.upsert(d)
        store.setPassword(d.id, pw)
        onSaved()
    }
}
