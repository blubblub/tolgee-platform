package io.tolgee.component.fileStorage

import io.tolgee.configuration.tolgee.S3Settings
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID

/**
 * Live integration against DigitalOcean Spaces. Enabled only when credentials are present:
 *
 * ```
 * TOLGEE_TEST_DO_SPACES=1
 * TOLGEE_FILE_STORAGE_S3_ACCESS_KEY=...
 * TOLGEE_FILE_STORAGE_S3_SECRET_KEY=...
 * TOLGEE_FILE_STORAGE_S3_ENDPOINT=https://fra1.digitaloceanspaces.com
 * TOLGEE_FILE_STORAGE_S3_SIGNING_REGION=fra1
 * TOLGEE_FILE_STORAGE_S3_BUCKET_NAME=tolgee-localize
 * ```
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DigitalOceanSpacesFileStorageIT {
  @Test
  fun `streams binary asset payload to DigitalOcean Spaces`() {
    assumeTrue(System.getenv("TOLGEE_TEST_DO_SPACES") == "1") {
      "Set TOLGEE_TEST_DO_SPACES=1 and S3 env vars to run this live Spaces test"
    }

    val settings =
      S3Settings(
        enabled = true,
        accessKey = requireEnv("TOLGEE_FILE_STORAGE_S3_ACCESS_KEY"),
        secretKey = requireEnv("TOLGEE_FILE_STORAGE_S3_SECRET_KEY"),
        endpoint = requireEnv("TOLGEE_FILE_STORAGE_S3_ENDPOINT"),
        signingRegion = requireEnv("TOLGEE_FILE_STORAGE_S3_SIGNING_REGION"),
        bucketName = requireEnv("TOLGEE_FILE_STORAGE_S3_BUCKET_NAME"),
        path = System.getenv("TOLGEE_FILE_STORAGE_S3_PATH"),
      )

    val storage = S3FileStorageFactory().create(settings)
    assertThat(storage.supportsStreaming()).isTrue()

    val key = "binary-assets/it/${UUID.randomUUID()}/payload.bin"
    val payload = ByteArray(128 * 1024) { (it % 251).toByte() }
    try {
      val info = storage.storeFileStream(key, payload.inputStream(), payload.size.toLong())
      assertThat(info.byteSize).isEqualTo(payload.size.toLong())
      assertThat(info.sha256).hasSize(64)
      assertThat(storage.fileExists(key)).isTrue()
      storage.openFileStream(key).use { stream ->
        assertThat(stream.readBytes()).isEqualTo(payload)
      }
    } finally {
      if (storage.fileExists(key)) {
        storage.deleteFile(key)
      }
    }
  }

  private fun requireEnv(name: String): String =
    System.getenv(name)?.takeIf { it.isNotBlank() }
      ?: error("Missing required env var $name for DigitalOcean Spaces IT")
}
