package com.saverouter

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import java.io.InputStream
import java.net.ConnectException
import java.net.Inet4Address
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/** Talks to a NAS over SMB 2 / SMB 3. */
object Smb {
    private val config: SmbConfig by lazy {
        SmbConfig.builder()
            .withSecurityProvider(BCSecurityProvider())
            .withTimeout(30, TimeUnit.SECONDS)
            .withSoTimeout(45, TimeUnit.SECONDS)
            .build()
    }

    fun norm(p: String): String = p.trim().replace('/', '\\').trim('\\')

    private fun <T> withShare(d: Dest, pw: String, block: (DiskShare) -> T): T {
        return SMBClient(config).use { client ->
            client.connect(d.host.trim()).use { conn ->
                val auth = if (d.user.isBlank()) AuthenticationContext.guest()
                else AuthenticationContext(d.user.trim(), pw.toCharArray(), d.domain.trim().ifBlank { null })
                conn.authenticate(auth).use { s ->
                    (s.connectShare(d.share.trim()) as DiskShare).use { share -> block(share) }
                }
            }
        }
    }

    /** Connects and returns how many folders are at the top of the share. */
    fun test(d: Dest, pw: String): Int = withShare(d, pw) { s ->
        s.list("").count { isDir(it.fileAttributes) && it.fileName != "." && it.fileName != ".." }
    }

    private fun isDir(attrs: Long) = (attrs and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L

    fun listDirs(d: Dest, pw: String, path: String): List<String> = withShare(d, pw) { s ->
        s.list(norm(path))
            .filter { isDir(it.fileAttributes) && it.fileName != "." && it.fileName != ".." && !it.fileName.startsWith(".") }
            .map { it.fileName }
            .sortedBy { it.lowercase() }
    }

    fun mkdir(d: Dest, pw: String, path: String) = withShare(d, pw) { s -> ensureDir(s, norm(path)) }

    private fun ensureDir(s: DiskShare, path: String) {
        if (path.isEmpty()) return
        var cur = ""
        for (part in path.split('\\')) {
            if (part.isBlank()) continue
            cur = if (cur.isEmpty()) part else "$cur\\$part"
            if (!s.folderExists(cur)) s.mkdir(cur)
        }
    }

    private fun unique(s: DiskShare, dir: String, name: String): String {
        fun full(n: String) = if (dir.isEmpty()) n else "$dir\\$n"
        if (!s.fileExists(full(name))) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (s.fileExists(full("$base ($i)$ext"))) i++
        return "$base ($i)$ext"
    }

    /** Uploads into d.path, creating folders as needed. Returns the name it was saved as. */
    fun upload(d: Dest, pw: String, name: String, input: InputStream, progress: (Long) -> Unit): String =
        withShare(d, pw) { s ->
            val dir = norm(d.path)
            ensureDir(s, dir)
            val finalName = unique(s, dir, name)
            val target = if (dir.isEmpty()) finalName else "$dir\\$finalName"
            s.openFile(
                target,
                EnumSet.of(AccessMask.GENERIC_WRITE),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_CREATE,
                EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)
            ).use { f ->
                f.outputStream.use { out -> copyStream(input, out, progress) }
            }
            finalName
        }

    /** Turns an error into a sentence a person can act on. */
    fun friendly(e: Throwable, d: Dest?): String {
        var t: Throwable? = e
        while (t != null) {
            when (t) {
                is SMBApiException -> return when (t.statusCode) {
                    0xC000006DL -> "The username or password is wrong."
                    0xC00000CCL -> "There’s no shared folder called “${d?.share}”. On a WD My Cloud, try Public or your My Cloud username."
                    0xC0000022L -> "This account isn’t allowed to save there. Check its permissions in the My Cloud dashboard under Shares."
                    0xC000003AL, 0xC0000034L -> "That folder doesn’t exist on the NAS."
                    0xC0000072L -> "This NAS account is turned off."
                    0xC0000071L -> "This NAS account’s password has expired."
                    else -> "The NAS said no (${t.status})."
                }
                is UnknownHostException -> return "Can’t find a device called “${d?.host}”. Use the NAS’s IP address, like 192.168.1.20."
                is ConnectException, is NoRouteToHostException ->
                    return "Couldn’t reach the NAS. Make sure it’s on, on the same Wi-Fi, and that the address is right."
                is SocketTimeoutException -> return "The NAS took too long to answer. Check that it’s awake and on the same network."
            }
            val m = t.message ?: ""
            if (m.contains("negotiat", true) || m.contains("dialect", true))
                return "The NAS only speaks the old SMB 1. In the My Cloud dashboard, open Settings › Network › Windows Services and set the maximum SMB protocol to SMB 3."
            t = t.cause
        }
        return e.message ?: e.javaClass.simpleName
    }
}

/** Looks for NAS boxes that announce themselves on the local network. */
class NasFinder(ctx: Context, private val onFound: (String, String) -> Unit, private val onDone: () -> Unit) {
    private val nsd = ctx.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var stopped = false

    private val listener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(t: String) {}
        override fun onServiceFound(s: NsdServiceInfo) { main.post { queue.addLast(s); next() } }
        override fun onServiceLost(s: NsdServiceInfo) {}
        override fun onDiscoveryStopped(t: String) {}
        override fun onStartDiscoveryFailed(t: String, e: Int) { main.post { finish() } }
        override fun onStopDiscoveryFailed(t: String, e: Int) {}
    }

    fun start() {
        try {
            nsd.discoverServices("_smb._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            main.post { finish() }
            return
        }
        main.postDelayed({ finish() }, 8000)
    }

    @Suppress("DEPRECATION")
    private fun next() {
        if (resolving || stopped) return
        val s = queue.removeFirstOrNull() ?: return
        resolving = true
        nsd.resolveService(s, object : NsdManager.ResolveListener {
            override fun onResolveFailed(i: NsdServiceInfo, e: Int) {
                main.post { resolving = false; next() }
            }
            override fun onServiceResolved(i: NsdServiceInfo) {
                val ip = if (Build.VERSION.SDK_INT >= 34) {
                    (i.hostAddresses.firstOrNull { it is Inet4Address } ?: i.hostAddresses.firstOrNull())?.hostAddress
                } else i.host?.hostAddress
                main.post {
                    resolving = false
                    if (ip != null && !stopped) onFound(i.serviceName, ip)
                    next()
                }
            }
        })
    }

    fun finish() {
        if (stopped) return
        stopped = true
        try { nsd.stopServiceDiscovery(listener) } catch (_: Exception) {}
        onDone()
    }
}
