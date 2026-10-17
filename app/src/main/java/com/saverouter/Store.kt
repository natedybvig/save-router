package com.saverouter

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** One place a file can be saved to. kind is "smb" (NAS), "tree" (a picked folder) or "drive". */
data class Dest(
    val id: String = UUID.randomUUID().toString(),
    val kind: String,
    val label: String,
    val host: String = "",
    val share: String = "",
    val path: String = "",
    val user: String = "",
    val domain: String = "",
    val treeUri: String = "",
    val quick: Boolean = true,
) {
    fun detail(): String = when (kind) {
        "smb" -> {
            val p = Smb.norm(path)
            "NAS  \\\\$host\\$share" + (if (p.isNotEmpty()) "\\$p" else "")
        }
        "tree" -> {
            val u = Uri.parse(treeUri)
            val where = when (u.authority) {
                "com.android.externalstorage.documents" -> "This device"
                "com.google.android.apps.docs.storage" -> "Google Drive"
                else -> "Folder from another app"
            }
            val id = runCatching { DocumentsContract.getTreeDocumentId(u) }.getOrDefault("")
            val tail = id.substringAfter(':', "")
            if (tail.isNotBlank() && u.authority == "com.android.externalstorage.documents")
                "$where › " + tail.replace("/", " › ") else where
        }
        else -> "Google Drive (you pick the folder in Drive)"
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("kind", kind).put("label", label)
        .put("host", host).put("share", share).put("path", path)
        .put("user", user).put("domain", domain).put("treeUri", treeUri)
        .put("quick", quick)

    companion object {
        fun from(o: JSONObject) = Dest(
            id = o.getString("id"),
            kind = o.getString("kind"),
            label = o.optString("label"),
            host = o.optString("host"),
            share = o.optString("share"),
            path = o.optString("path"),
            user = o.optString("user"),
            domain = o.optString("domain"),
            treeUri = o.optString("treeUri"),
            quick = o.optBoolean("quick", true),
        )
    }
}

class Store(ctx: Context) {
    private val p = ctx.getSharedPreferences("saverouter", Context.MODE_PRIVATE)

    fun dests(): MutableList<Dest> {
        val arr = JSONArray(p.getString("dests", "[]"))
        return MutableList(arr.length()) { Dest.from(arr.getJSONObject(it)) }
    }

    private fun saveAll(list: List<Dest>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        p.edit().putString("dests", arr.toString()).apply()
    }

    fun get(id: String): Dest? = dests().firstOrNull { it.id == id }

    fun upsert(d: Dest) {
        val l = dests()
        val i = l.indexOfFirst { it.id == d.id }
        if (i >= 0) l[i] = d else l.add(d)
        saveAll(l)
    }

    fun remove(id: String) {
        saveAll(dests().filter { it.id != id })
        p.edit().remove("pw_$id").apply()
    }

    fun setPassword(id: String, pw: String) {
        p.edit().putString("pw_$id", if (pw.isEmpty()) "" else Crypto.enc(pw)).apply()
    }

    fun password(id: String): String {
        val s = p.getString("pw_$id", "") ?: ""
        return if (s.isEmpty()) "" else runCatching { Crypto.dec(s) }.getOrDefault("")
    }

    var watch: Boolean
        get() = p.getBoolean("watch", false)
        set(v) { p.edit().putBoolean("watch", v).apply() }

    var deleteAfter: Boolean
        get() = p.getBoolean("delete_after", false)
        set(v) { p.edit().putBoolean("delete_after", v).apply() }

    var popup: Boolean
        get() = p.getBoolean("popup", true)
        set(v) { p.edit().putBoolean("popup", v).apply() }

    var initialized: Boolean
        get() = p.getBoolean("initialized", false)
        set(v) { p.edit().putBoolean("initialized", v).apply() }
}

/** Keeps NAS passwords encrypted with a key that never leaves the device. */
object Crypto {
    private const val ALIAS = "saverouter_pw"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return g.generateKey()
    }

    fun enc(s: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(s.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    fun dec(s: String): String {
        val parts = s.split(":")
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        return String(c.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
    }
}
