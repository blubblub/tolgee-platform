package io.tolgee.component.fileStorage

import io.tolgee.configuration.tolgee.FileStorageProperties
import io.tolgee.configuration.tolgee.TolgeeProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.security.MessageDigest

class LocalFileStorageStreamingTest {
  private lateinit var root: java.nio.file.Path
  private lateinit var storage: LocalFileStorage

  @BeforeEach
  fun setup() {
    root = Files.createTempDirectory("tolgee-fs-test")
    val props = TolgeeProperties()
    props.fileStorage = FileStorageProperties().also { it.fsDataPath = root.toString() }
    storage = LocalFileStorage(props)
  }

  @AfterEach
  fun cleanup() {
    root.toFile().deleteRecursively()
  }

  @Test
  fun `supports streaming and round-trips with sha256`() {
    assertThat(storage.supportsStreaming()).isTrue()
    val payload = ByteArray(1024 * 256) { (it % 251).toByte() }
    val expectedSha =
      MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
    val info = storage.storeFileStream("binary-assets/1/test.bin", payload.inputStream(), payload.size.toLong())
    assertThat(info.byteSize).isEqualTo(payload.size.toLong())
    assertThat(info.sha256).isEqualTo(expectedSha)
    assertThat(storage.fileExists("binary-assets/1/test.bin")).isTrue()
    storage.openFileStream("binary-assets/1/test.bin").use { stream ->
      assertThat(stream.readBytes()).isEqualTo(payload)
    }
    storage.deleteFile("binary-assets/1/test.bin")
    assertThat(storage.fileExists("binary-assets/1/test.bin")).isFalse()
  }
}
