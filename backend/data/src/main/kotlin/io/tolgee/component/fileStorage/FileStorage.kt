/*
 * Copyright (c) 2020. Tolgee
 */

package io.tolgee.component.fileStorage

import io.tolgee.exceptions.FileStoreException
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest

data class StoredFileInfo(
  val byteSize: Long,
  val sha256: String,
)

interface FileStorage {
  fun readFile(storageFilePath: String): ByteArray

  fun deleteFile(storageFilePath: String)

  fun storeFile(
    storageFilePath: String,
    bytes: ByteArray,
  )

  fun fileExists(storageFilePath: String): Boolean

  fun pruneDirectory(path: String)

  /**
   * True when [storeFileStream] / [openFileStream] avoid buffering the whole object in heap.
   * Binary-asset paths must fail closed when this is false.
   */
  fun supportsStreaming(): Boolean = false

  /**
   * Store from a stream, returning size and SHA-256. Default implementation buffers (legacy-only).
   */
  fun storeFileStream(
    storageFilePath: String,
    inputStream: InputStream,
    contentLength: Long? = null,
  ): StoredFileInfo {
    val digest = MessageDigest.getInstance("SHA-256")
    val bytes =
      DigestInputStream(inputStream, digest).use { it.readBytes() }
    storeFile(storageFilePath, bytes)
    return StoredFileInfo(bytes.size.toLong(), digest.digest().toHex())
  }

  /**
   * Open a readable stream. Caller must close it. Default buffers via [readFile].
   */
  fun openFileStream(storageFilePath: String): InputStream {
    return ByteArrayInputStream(readFile(storageFilePath))
  }

  fun test() {
    try {
      this.storeFile("test", "test".toByteArray())
      this.readFile("test")
      this.deleteFile("test")
    } catch (e: Exception) {
      throw FileStoreException("Storage test failed", "test", e)
    }
  }
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun MessageDigest.digestHex(): String = digest().toHex()
