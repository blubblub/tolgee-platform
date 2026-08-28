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

  /**
   * Open a stream over the inclusive byte range [start, endInclusive] (HTTP Range semantics).
   * Caller must close it. Default skips into a full [openFileStream]; backends that can seek
   * or issue ranged reads should override so serving the tail of a file does not read its head.
   */
  fun openFileStreamRange(
    storageFilePath: String,
    start: Long,
    endInclusive: Long,
  ): InputStream {
    require(start >= 0 && endInclusive >= start) { "Invalid byte range $start-$endInclusive" }
    val stream = openFileStream(storageFilePath)
    try {
      stream.skipFully(start)
    } catch (e: Exception) {
      stream.close()
      throw e
    }
    return BoundedInputStream(stream, endInclusive - start + 1)
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

/** [InputStream.skip] may stop early; loop until [count] bytes are gone or the stream ends. */
fun InputStream.skipFully(count: Long) {
  var remaining = count
  while (remaining > 0) {
    val skipped = skip(remaining)
    if (skipped > 0) {
      remaining -= skipped
      continue
    }
    // skip() made no progress — fall back to a read to distinguish "slow" from EOF
    if (read() < 0) return
    remaining--
  }
}

/** Reads at most [limit] bytes from [delegate], then reports EOF. Closing closes the delegate. */
class BoundedInputStream(
  private val delegate: InputStream,
  limit: Long,
) : InputStream() {
  private var remaining = limit

  override fun read(): Int {
    if (remaining <= 0) return -1
    val b = delegate.read()
    if (b >= 0) remaining--
    return b
  }

  override fun read(
    b: ByteArray,
    off: Int,
    len: Int,
  ): Int {
    if (remaining <= 0) return -1
    val n = delegate.read(b, off, minOf(len.toLong(), remaining).toInt())
    if (n > 0) remaining -= n
    return n
  }

  override fun available(): Int = minOf(delegate.available().toLong(), remaining).toInt()

  override fun close() = delegate.close()
}
