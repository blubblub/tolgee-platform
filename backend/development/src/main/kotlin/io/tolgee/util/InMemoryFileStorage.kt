/**
 * Copyright (C) 2023 Tolgee s.r.o. and contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.tolgee.util

import io.tolgee.component.fileStorage.FileStorage
import io.tolgee.component.fileStorage.StoredFileInfo
import io.tolgee.component.fileStorage.toHex
import io.tolgee.exceptions.FileStoreException
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest

/**
 * In-memory storage for tests. Does not support true streaming (buffers in heap).
 * Binary-asset service paths must not use this for large-file tests.
 */
class InMemoryFileStorage : FileStorage {
  private val files = mutableMapOf<String, ByteArray>()

  override fun supportsStreaming(): Boolean = false

  override fun readFile(storageFilePath: String): ByteArray {
    return files[storageFilePath]
      ?: throw FileStoreException("File not found", storageFilePath)
  }

  override fun openFileStream(storageFilePath: String): InputStream {
    return ByteArrayInputStream(readFile(storageFilePath))
  }

  override fun deleteFile(storageFilePath: String) {
    files.remove(storageFilePath)
  }

  override fun storeFile(
    storageFilePath: String,
    bytes: ByteArray,
  ) {
    files[storageFilePath] = bytes
  }

  override fun storeFileStream(
    storageFilePath: String,
    inputStream: InputStream,
    contentLength: Long?,
  ): StoredFileInfo {
    val digest = MessageDigest.getInstance("SHA-256")
    val bytes = DigestInputStream(inputStream, digest).use { it.readBytes() }
    files[storageFilePath] = bytes
    return StoredFileInfo(bytes.size.toLong(), digest.digest().toHex())
  }

  override fun fileExists(storageFilePath: String): Boolean {
    return files.contains(storageFilePath)
  }

  override fun pruneDirectory(path: String) {
    val keysToDelete = files.keys.filter { it.startsWith(path.removeSuffix("/") + "/") }
    keysToDelete.forEach {
      files.remove(it)
    }
  }

  fun clear() {
    files.clear()
  }
}
