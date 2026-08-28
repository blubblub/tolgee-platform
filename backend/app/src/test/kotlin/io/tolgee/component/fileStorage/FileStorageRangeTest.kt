package io.tolgee.component.fileStorage

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.io.InputStream

/** The default [FileStorage.openFileStreamRange] — used by backends without a native ranged read. */
class FileStorageRangeTest {
  private val bytes = ByteArray(1000) { (it % 251).toByte() }

  /** A storage whose stream refuses to skip, to prove [skipFully] copes with lazy skip() impls. */
  private val storage =
    object : FileStorage {
      override fun readFile(storageFilePath: String): ByteArray = bytes

      override fun openFileStream(storageFilePath: String): InputStream =
        object : InputStream() {
          private var pos = 0

          override fun read(): Int = if (pos < bytes.size) bytes[pos++].toInt() and 0xff else -1

          override fun skip(n: Long): Long = 0
        }

      override fun deleteFile(storageFilePath: String) = Unit

      override fun storeFile(
        storageFilePath: String,
        bytes: ByteArray,
      ) = Unit

      override fun fileExists(storageFilePath: String): Boolean = true

      override fun pruneDirectory(path: String) = Unit
    }

  @Test
  fun `returns exactly the requested inclusive range`() {
    val out = storage.openFileStreamRange("x", 10, 19).use { it.readBytes() }
    assertThat(out).isEqualTo(bytes.copyOfRange(10, 20))
  }

  @Test
  fun `serves a single byte and the last byte`() {
    assertThat(storage.openFileStreamRange("x", 0, 0).use { it.readBytes() }).isEqualTo(bytes.copyOfRange(0, 1))
    assertThat(storage.openFileStreamRange("x", 999, 999).use { it.readBytes() })
      .isEqualTo(bytes.copyOfRange(999, 1000))
  }

  @Test
  fun `stops at the end of the file when the range overshoots`() {
    val out = storage.openFileStreamRange("x", 990, 5000).use { it.readBytes() }
    assertThat(out).isEqualTo(bytes.copyOfRange(990, 1000))
  }

  @Test
  fun `rejects an inverted or negative range`() {
    assertThatThrownBy { storage.openFileStreamRange("x", 5, 4) }.isInstanceOf(IllegalArgumentException::class.java)
    assertThatThrownBy { storage.openFileStreamRange("x", -1, 4) }.isInstanceOf(IllegalArgumentException::class.java)
  }
}
