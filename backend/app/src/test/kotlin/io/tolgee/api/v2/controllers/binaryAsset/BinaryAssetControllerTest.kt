package io.tolgee.api.v2.controllers.binaryAsset

import io.tolgee.ProjectAuthControllerTest
import io.tolgee.component.fileStorage.LocalFileStorage
import io.tolgee.fixtures.andAssertThatJson
import io.tolgee.fixtures.andIsCreated
import io.tolgee.fixtures.andIsOk
import io.tolgee.testing.annotations.ProjectJWTAuthTestMethod
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(
  properties = ["tolgee.internal.use-in-memory-file-storage=false"],
)
class BinaryAssetControllerTest : ProjectAuthControllerTest("/v2/projects/") {
  @Test
  @ProjectJWTAuthTestMethod
  fun `creates lists and tickets binary assets on local streaming storage`() {
    assertThat(fileStorage).isInstanceOf(LocalFileStorage::class.java)
    assertThat(fileStorage.supportsStreaming()).isTrue()

    val create =
      performProjectAuthMultipart(
        url = "binary-assets",
        files =
          listOf(
            MockMultipartFile("name", null, MediaType.TEXT_PLAIN_VALUE, "hero-banner".toByteArray()),
            MockMultipartFile(
              "file",
              "hero.psd",
              "application/octet-stream",
              byteArrayOf(0x38, 0x42, 0x50, 0x53, 0x01, 0x02, 0x03),
            ),
          ),
      ).andIsCreated
        .andAssertThatJson {
          node("name").isEqualTo("hero-banner")
          node("sourceRevision").isEqualTo(1)
          node("originalFilename").isEqualTo("hero.psd")
          node("byteSize").isEqualTo(7)
          node("sha256").isString.hasSize(64)
        }.andReturn()

    val assetId =
      com.fasterxml.jackson.module.kotlin
        .jacksonObjectMapper()
        .readTree(create.response.contentAsString)
        .get("id")
        .asLong()

    performProjectAuthGet("binary-assets").andIsOk.andAssertThatJson {
      node("_embedded.binaryAssets").isArray.hasSize(1)
      node("_embedded.binaryAssets[0].name").isEqualTo("hero-banner")
      // the assets page edits localized files in place, so list rows carry them
      node("_embedded.binaryAssets[0].translations").isArray.hasSize(1)
      node("_embedded.binaryAssets[0].translations[0].languageTag").isEqualTo("de")
      node("_embedded.binaryAssets[0].translations[0].status").isEqualTo("MISSING")
    }

    performProjectAuthGet("binary-assets/$assetId").andIsOk.andAssertThatJson {
      node("id").isEqualTo(assetId)
      node("name").isEqualTo("hero-banner")
    }

    val ticketResponse =
      performProjectAuthPost("binary-assets/$assetId/source/download-ticket", null)
        .andIsOk
        .andReturn()
    val ticketUrl =
      com.fasterxml.jackson.module.kotlin
        .jacksonObjectMapper()
        .readTree(ticketResponse.response.contentAsString)
        .get("url")
        .asText()
    assertThat(ticketUrl).contains("/v2/binary-assets/download?token=")

    // Download via public ticket endpoint (no project auth)
    val token = ticketUrl.substringAfter("token=")
    mvc
      .perform(MockMvcRequestBuilders.get("/v2/binary-assets/download").param("token", token))
      .andExpect(status().isOk)
      .andExpect { result ->
        assertThat(result.response.contentAsByteArray)
          .isEqualTo(byteArrayOf(0x38, 0x42, 0x50, 0x53, 0x01, 0x02, 0x03))
      }

    performProjectAuthDelete("binary-assets/$assetId", null).andExpect(status().isNoContent)
    performProjectAuthGet("binary-assets").andIsOk.andAssertThatJson {
      node("page.totalElements").isEqualTo(0)
    }
  }
}
