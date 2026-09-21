package com.skiletro.wheelwitch.util.cloud

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Canonical content hash of a `wheelwitch-save` zip: SHA-256 over the sorted (entryName,
 * SHA-256(entryBytes)) pairs, excluding `manifest.json` and directory entries. Order- and
 * timestamp- independent, so re-zipping identical save data yields the same hash — required for
 * local-change detection (see the WheelSync spec).
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
