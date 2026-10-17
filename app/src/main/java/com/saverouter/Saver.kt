package com.saverouter

import android.content.ClipData
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

fun copyStream(input: InputStream, out: OutputStream, onBytes: (Long) -> Unit) {
    val buf = ByteArray(256 * 1024)
    var total = 0L
    var last = 0L
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        out.write(buf, 0, n)
        total += n
        if (total - last >= 1_000_000) { onBytes(total); last = total }
    }
    out.flush()
    onBytes(total)
}

/** A file to be saved: either a real file in Downloads or something shared from another app. */
class Source(val name: String, val size: Long, val file: File? = null, val uri: Uri? = null) {
    fun open(ctx: Context): InputStream =
        file?.inputStream()
            ?: uri?.let { ctx.contentResolver.openInputStream(it) }
            ?: throw IOException("Couldn’t open $name")

    companion object {
        fun fromFile(f: File) = Source(f.name, f.length(), file = f)

        fun fromUri(ctx: Context, uri: Uri): Source {
            var name: String? = null
            var size = -1L
            try {
                ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                        val si = c.getColumnIndex(OpenableColumns.SIZE)
                        if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                    }
                }
            } catch (_: Exception) {}
            if (name.isNullOrBlank()) {
                name = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "file"
            }
            return Source(name!!, size, uri = uri)
        }
    }
}

object Saver {
    const val DRIVE = "com.google.android.apps.docs"

    fun mime(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"

    fun human(b: Long): String = when {
        b >= 1L shl 30 -> String.format("%.1f GB", b / 1073741824.0)
        b >= 1L shl 20 -> String.format("%.1f MB", b / 1048576.0)
        b >= 1L shl 10 -> String.format("%.0f KB", b / 1024.0)
        else -> "$b bytes"
    }

    fun driveInstalled(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(DRIVE, 0); true
    } catch (_: PackageManager.NameNotFoundException) { false }

    /** Saves to a NAS or picked-folder destination. Returns the name it was saved as. */
    fun save(ctx: Context, src: Source, d: Dest, name: String, folderOverride: String? = null, progress: (Long) -> Unit = {}): String =
        when (d.kind) {
            "smb" -> src.open(ctx).use {
                Smb.upload(d.copy(path = folderOverride ?: d.path), Store(ctx).password(d.id), name, it, progress)
            }
            "tree" -> saveToTree(ctx, src, Uri.parse(d.treeUri), name, progress)
            else -> throw IllegalStateException("Google Drive saves go through the Drive app.")
        }

    fun saveToTree(ctx: Context, src: Source, tree: Uri, name: String, progress: (Long) -> Unit = {}): String {
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val doc = try {
            DocumentsContract.createDocument(ctx.contentResolver, parent, mime(name), name)
        } catch (e: SecurityException) {
            throw IOException("Save Router lost permission to that folder. Remove it and add it again.")
        } ?: throw IOException("That folder wouldn’t accept a new file.")
        writeTo(ctx, src, doc, progress)
        return displayName(ctx, doc) ?: name
    }

    fun writeTo(ctx: Context, src: Source, target: Uri, progress: (Long) -> Unit = {}) {
        val out = ctx.contentResolver.openOutputStream(target, "w") ?: throw IOException("Couldn’t write there.")
        out.use { o -> src.open(ctx).use { copyStream(it, o, progress) } }
    }

    fun displayName(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Exception) { null }

    /** Copies a file into the app's cache so another app (Drive) can read it after we close. */
    fun stage(ctx: Context, src: Source, name: String): Uri {
        val dir = File(ctx.cacheDir, "handoff/" + System.nanoTime()).apply { mkdirs() }
        val f = File(dir, name.replace('/', '_'))
        f.outputStream().use { out -> src.open(ctx).use { copyStream(it, out) {} } }
        return HandoffProvider.uriFor(f)
    }

    fun driveIntent(uris: List<Uri>, names: List<String>): Intent {
        val i = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0]).setType(mime(names[0]))
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris)).setType("*/*")
        }
        val clip = ClipData.newRawUri("", uris[0])
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        i.clipData = clip
        i.setPackage(DRIVE)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return i
    }

    fun removeOriginal(ctx: Context, f: File) {
        if (f.delete()) MediaScannerConnection.scanFile(ctx, arrayOf(f.absolutePath), null, null)
    }

    fun cleanCache(ctx: Context) {
        val cutoff = System.currentTimeMillis() - 24L * 3600 * 1000
        File(ctx.cacheDir, "handoff").listFiles()?.forEach { if (it.lastModified() < cutoff) it.deleteRecursively() }
    }
}

/** Serves staged copies to the Drive app, read-only. */
class HandoffProvider : ContentProvider() {
    companion object {
        const val AUTH = "com.saverouter.handoff"
        fun uriFor(f: File): Uri = Uri.Builder().scheme("content").authority(AUTH)
            .appendPath(f.parentFile!!.name).appendPath(f.name).build()
    }

    private fun fileFor(uri: Uri): File {
        val seg = uri.pathSegments
        if (seg.size != 2) throw FileNotFoundException()
        val base = File(context!!.cacheDir, "handoff").canonicalFile
        val f = File(File(base, seg[0]), seg[1]).canonicalFile
        if (!f.path.startsWith(base.path + File.separator) || !f.exists()) throw FileNotFoundException()
        return f
    }

    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, sort: String?): Cursor {
        val f = fileFor(uri)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val c = MatrixCursor(cols)
        c.addRow(cols.map { col ->
            when (col) {
                OpenableColumns.DISPLAY_NAME -> f.name
                OpenableColumns.SIZE -> f.length()
                else -> null
            }
        }.toTypedArray())
        return c
    }

    override fun getType(uri: Uri): String = Saver.mime(uri.lastPathSegment ?: "")
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<String>?) = 0
}
