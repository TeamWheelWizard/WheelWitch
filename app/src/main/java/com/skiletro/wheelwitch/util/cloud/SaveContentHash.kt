package com.skiletro.wheelwitch.util.cloud

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * SHA-256 over entries sorted by name: UTF-8 name bytes followed by SHA-256(entryBytes).
 * Excludes `manifest.json` and directories; ignores zip timestamps. For duplicate names the last
 * entry wins. Used for local-change detection independently of zip packaging.
 */
object SaveContentHash {

  fun canonicalHash(zipBytes: ByteArray): String {
    val entries = LinkedHashMap<String, ByteArray>()
    ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
      while (true) {
        val entry = zip.nextEntry ?: break
        if (entry.isDirectory || entry.name == "manifest.json") continue
        entries[entry.name] = zip.readBytes()
      }
    }
    val digest = MessageDigest.getInstance("SHA-256")
    for (name in entries.keys.sorted()) {
      digest.update(name.toByteArray(Charsets.UTF_8))
      digest.update(MessageDigest.getInstance("SHA-256").digest(entries.getValue(name)))
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }
}
