package com.quickpdf.reader.pdf

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * Persists extracted / recognized text so a document is only indexed once. Keyed by the document's
 * [com.quickpdf.reader.data.docKey]; before 1.2 by URI and size, so an index under one of [legacyUris] is adopted.
 */
class IndexCache(dir: File, key: String, size: Long, legacyUris: Collection<String> = emptyList()) {
    private val file: File = File(dir, "index").apply { mkdirs() }.let { folder ->
        folder.resolve(sha1Hex("doc|$key") + ".bin").also { f ->
            if (!f.exists()) legacyUris.map { folder.resolve(sha1Hex("$it|$size") + ".bin") }.firstOrNull { it.exists() }?.renameTo(f)
        }
    }

    fun load(pageCount: Int): Array<PageText?>? = runCatching {
        if (!file.exists()) return null
        DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
            if (input.readInt() != VERSION) return null
            val n = input.readInt()
            if (n != pageCount) return null
            Array(n) {
                val source = input.readByte().toInt()
                if (source == 0) return@Array null
                val bytes = ByteArray(input.readInt()).also(input::readFully)
                val words = List(input.readInt()) {
                    WordBox(
                        input.readInt(), input.readInt(),
                        input.readFloat(), input.readFloat(), input.readFloat(), input.readFloat(),
                    )
                }
                PageText(String(bytes, Charsets.UTF_8), source, words)
            }
        }
    }.getOrNull()

    fun save(pages: Array<PageText?>) {
        runCatching {
            val tmp = File(file.path + ".tmp")
            DataOutputStream(BufferedOutputStream(tmp.outputStream())).use { out ->
                out.writeInt(VERSION)
                out.writeInt(pages.size)
                for (p in pages) {
                    if (p == null) {
                        out.writeByte(0); continue
                    }
                    out.writeByte(p.source)
                    val bytes = p.text.toByteArray(Charsets.UTF_8)
                    out.writeInt(bytes.size)
                    out.write(bytes)
                    out.writeInt(p.words.size)
                    for (w in p.words) {
                        out.writeInt(w.start); out.writeInt(w.end)
                        out.writeFloat(w.left); out.writeFloat(w.top); out.writeFloat(w.right); out.writeFloat(w.bottom)
                    }
                }
            }
            tmp.renameTo(file)
        }
    }

    private companion object {
        const val VERSION = 1
    }
}

fun sha1Hex(s: String): String =
    MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
