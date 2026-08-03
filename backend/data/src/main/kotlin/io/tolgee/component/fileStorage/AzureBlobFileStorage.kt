/*
 * Copyright (c) 2020. Tolgee
 */

package io.tolgee.component.fileStorage

import com.azure.core.util.BinaryData
import com.azure.storage.blob.BlobContainerClient
import com.azure.storage.blob.models.ListBlobsOptions
import io.tolgee.exceptions.FileStoreException
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import java.io.InputStream
import java.nio.file.Files
import java.security.DigestInputStream
import java.security.MessageDigest

open class AzureBlobFileStorage(
  private val client: BlobContainerClient,
) : FileStorage {
  override fun supportsStreaming(): Boolean = true

  override fun readFile(storageFilePath: String): ByteArray {
    try {
      return client.getBlobClient(storageFilePath).downloadContent().toBytes()
    } catch (e: Exception) {
      throw FileStoreException("Can not obtain file", storageFilePath, e)
    }
  }

  override fun openFileStream(storageFilePath: String): InputStream {
    try {
      return client.getBlobClient(storageFilePath).openInputStream()
    } catch (e: Exception) {
      throw FileStoreException("Can not obtain file", storageFilePath, e)
    }
  }

  override fun deleteFile(storageFilePath: String) {
    try {
      client.getBlobClient(storageFilePath).delete()
      return
    } catch (e: Exception) {
      throw FileStoreException("Can not delete file using Azure Blob!", storageFilePath, e)
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
    val digest = MessageDigest.getInstance("SHA-256")
    try {
      val tmp = Files.createTempFile("tolgee-azure-", ".bin")
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
        val blobClient = client.getBlobClient(storageFilePath)
        Files.newInputStream(tmp).use { staged ->
          blobClient.upload(BinaryData.fromStream(staged, size), true)
        }
        return StoredFileInfo(size, digest.digest().toHex())
      } finally {
        Files.deleteIfExists(tmp)
      }
    } catch (e: Exception) {
      throw FileStoreException("Can not store file using Azure Blob!", storageFilePath, e)
    }
  }

  override fun fileExists(storageFilePath: String): Boolean {
    return try {
      client.getBlobClient(storageFilePath).exists()
    } catch (e: NoSuchKeyException) {
      false
    }
  }

  override fun pruneDirectory(path: String) {
    val prefix = path.removePrefix("/").removeSuffix("/") + "/"
    val options = ListBlobsOptions()
    options.prefix = prefix
    client.listBlobs(options, null).forEach {
      client.getBlobClient(it.name).delete()
    }
  }
}
