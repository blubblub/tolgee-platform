/*
 * Copyright (c) 2020. Tolgee
 */

package io.tolgee.component.fileStorage

import io.tolgee.exceptions.FileStoreException
import io.tolgee.fixtures.removeSlashSuffix
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.ObjectIdentifier
import software.amazon.awssdk.services.s3.model.S3Exception
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.security.DigestInputStream
import java.security.MessageDigest

open class S3FileStorage(
  private val bucketName: String,
  private val path: String?,
  private val s3: S3Client,
) : FileStorage {
  override fun supportsStreaming(): Boolean = true

  override fun readFile(storageFilePath: String): ByteArray {
    try {
      return s3.getObject { b -> b.bucket(bucketName).key("$canonicalPath$storageFilePath") }.readAllBytes()
    } catch (e: Exception) {
      throw FileStoreException("Can not obtain file", storageFilePath, e)
    }
  }

  override fun openFileStream(storageFilePath: String): InputStream {
    try {
      return s3.getObject { b -> b.bucket(bucketName).key("$canonicalPath$storageFilePath") }
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
      return s3.getObject { b ->
        b.bucket(bucketName).key("$canonicalPath$storageFilePath").range("bytes=$start-$endInclusive")
      }
    } catch (e: Exception) {
      throw FileStoreException("Can not obtain file", storageFilePath, e)
    }
  }

  override fun deleteFile(storageFilePath: String) {
    try {
      s3.deleteObject { b -> b.bucket(bucketName).key("$canonicalPath$storageFilePath") }
      return
    } catch (e: Exception) {
      throw FileStoreException("Can not delete file using s3 bucket!", storageFilePath, e)
    }
  }

  override fun storeFile(
    storageFilePath: String,
    bytes: ByteArray,
  ) {
    storeFileStream(storageFilePath, ByteArrayInputStream(bytes), bytes.size.toLong())
  }

  override fun storeFileStream(
    storageFilePath: String,
    inputStream: InputStream,
    contentLength: Long?,
  ): StoredFileInfo {
    // S3 putObject needs a known content length. Stage to a temp file while hashing, then upload.
    val digest = MessageDigest.getInstance("SHA-256")
    try {
      val tmp = Files.createTempFile("tolgee-s3-", ".bin")
      try {
        var size = 0L
        DigestInputStream(inputStream, digest).use { input ->
          Files.newOutputStream(tmp).use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
              val read = input.read(buffer)
              if (read < 0) break
              output.write(buffer, 0, read)
              size += read
            }
          }
        }
        Files.newInputStream(tmp).use { staged ->
          s3.putObject(
            { b -> b.bucket(bucketName).key("$canonicalPath$storageFilePath") },
            RequestBody.fromInputStream(staged, size),
          )
        }
        return StoredFileInfo(size, digest.digest().toHex())
      } finally {
        Files.deleteIfExists(tmp)
      }
    } catch (e: Exception) {
      throw FileStoreException("Can not store file using s3 bucket!", storageFilePath, e)
    }
  }

  override fun fileExists(storageFilePath: String): Boolean {
    return try {
      s3.headObject { b -> b.bucket(bucketName).key("$canonicalPath$storageFilePath") }
      true
    } catch (e: NoSuchKeyException) {
      false
    } catch (e: S3Exception) {
      // DigitalOcean Spaces and some S3-compatible stores return a generic S3Exception (404)
      // rather than NoSuchKeyException for missing objects.
      if (e.statusCode() == 404 || e.awsErrorDetails()?.errorCode() in setOf("NoSuchKey", "NotFound", "404")) {
        false
      } else {
        throw FileStoreException("Can not check file existence using s3 bucket!", storageFilePath, e)
      }
    }
  }

  override fun pruneDirectory(path: String) {
    try {
      val objectsToDelete =
        s3
          .listObjectsV2 { it.bucket(bucketName).prefix("$canonicalPath$path".withTrailingSlash()) }
          .contents()
          .map { it.key() }
          .toSet()
          .map { ObjectIdentifier.builder().key(it).build() }

      if (objectsToDelete.isNotEmpty()) {
        val deleteObjectsRequest =
          DeleteObjectsRequest
            .builder()
            .bucket(bucketName)
            .delete {
              it.objects(objectsToDelete)
            }.build()

        s3.deleteObjects(deleteObjectsRequest)
      }
    } catch (e: Exception) {
      throw FileStoreException("Can not prune directory in s3 bucket!", path, e)
    }
  }

  private val canonicalPath: String by lazy {
    if (path.isNullOrBlank()) {
      return@lazy ""
    }
    return@lazy path.removeSlashSuffix() + "/"
  }

  private fun String.withTrailingSlash(): String {
    if (this.endsWith("/")) {
      return this
    }
    return "$this/"
  }
}
