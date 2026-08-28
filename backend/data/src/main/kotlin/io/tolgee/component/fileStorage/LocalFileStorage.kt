/*
 * Copyright (c) 2020. Tolgee
 */

package io.tolgee.component.fileStorage

import io.tolgee.configuration.tolgee.TolgeeProperties
import io.tolgee.exceptions.FileStoreException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.MessageDigest

class LocalFileStorage(
  tolgeeProperties: TolgeeProperties,
) : FileStorage {
  private val localDataPath = tolgeeProperties.fileStorage.fsDataPath

  override fun supportsStreaming(): Boolean = true

  override fun readFile(storageFilePath: String): ByteArray {
    try {
      return getLocalFile(storageFilePath).readBytes()
    } catch (e: Exception) {
      throw FileStoreException("Can not obtain file", storageFilePath, e)
    }
  }

  override fun openFileStream(storageFilePath: String): InputStream {
    try {
      return getLocalFile(storageFilePath).inputStream().buffered()
    } catch (e: Exception) {
      throw FileStoreException("Can not obtain file", storageFilePath, e)
    }
  }

  override fun openFileStreamRange(
    storageFilePath: String,
    start: Long,
    endInclusive: Long,
  ): InputStream {
    require(start >= 0 && endInclusive >= start) { "Invalid byte range $start-$endInclusive" }
    try {
      val stream = getLocalFile(storageFilePath).inputStream()
      try {
        stream.channel.position(start)
      } catch (e: Exception) {
        stream.close()
        throw e
      }
      return BoundedInputStream(stream.buffered(), endInclusive - start + 1)
    } catch (e: Exception) {
      throw FileStoreException("Can not obtain file", storageFilePath, e)
    }
  }

  override fun deleteFile(storageFilePath: String) {
    try {
      getLocalFile(storageFilePath).delete()
    } catch (e: Exception) {
      throw FileStoreException("Can not delete file from local filesystem!", storageFilePath, e)
    }
  }

  override fun storeFile(
    storageFilePath: String,
    bytes: ByteArray,
  ) {
    storeFileStream(storageFilePath, bytes.inputStream(), bytes.size.toLong())
  }

  override fun storeFileStream(
    storageFilePath: String,
    inputStream: InputStream,
    contentLength: Long?,
  ): StoredFileInfo {
    val target = getLocalFile(storageFilePath)
    try {
      target.parentFile.mkdirs()
      val tmp = File(target.parentFile, ".${target.name}.tmp-${System.nanoTime()}")
      val digest = MessageDigest.getInstance("SHA-256")
      var size = 0L
      try {
        DigestInputStream(inputStream, digest).use { input ->
          tmp.outputStream().buffered().use { output ->
            size = copyCounting(input, output)
          }
        }
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
      } catch (e: Exception) {
        tmp.delete()
        throw e
      }
      return StoredFileInfo(size, digest.digest().toHex())
    } catch (e: Exception) {
      throw FileStoreException("Can not store file to local filesystem!", storageFilePath, e)
    }
  }

  override fun pruneDirectory(path: String) {
    try {
      val dir = getLocalFile(path)
      if (dir.isDirectory) {
        dir.listFiles()?.forEach {
          it.deleteRecursively()
        }
      }
    } catch (e: Exception) {
      throw FileStoreException("Cannot prune directory: $path", path, e)
    }
  }

  override fun fileExists(storageFilePath: String): Boolean {
    return getLocalFile(storageFilePath).exists()
  }

  private fun getLocalFile(storageFilePath: String): File {
    val dataRoot = localDataPath.removeTrailingSlash()
    val normalizedFilePath = storageFilePath.removeLeadingSlash()
    val resolved = File("$dataRoot/$normalizedFilePath").canonicalFile
    val rootDir = File(dataRoot).canonicalFile
    if (!resolved.path.startsWith(rootDir.path + File.separator) && resolved != rootDir) {
      throw FileStoreException("Path traversal detected", storageFilePath)
    }
    return resolved
  }

  private fun String.removeLeadingSlash() = this.removePrefix("/")

  private fun String.removeTrailingSlash() = this.removeSuffix("/")

  private fun copyCounting(
    input: InputStream,
    output: OutputStream,
  ): Long {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
      val read = input.read(buffer)
      if (read < 0) break
      output.write(buffer, 0, read)
      total += read
    }
    return total
  }
}
