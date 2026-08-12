package io.tolgee.api.v2.controllers.binaryAsset

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.tolgee.ProjectAuthControllerTest
import io.tolgee.component.fileStorage.LocalFileStorage
import io.tolgee.fixtures.AuthorizedRequestFactory
import io.tolgee.fixtures.andAssertThatJson
import io.tolgee.fixtures.andIsCreated
import io.tolgee.fixtures.andIsOk
import io.tolgee.testing.annotations.ProjectJWTAuthTestMethod
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.ResultActions
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

  @Test
  @ProjectJWTAuthTestMethod
  fun `localized file is stored under the original filename regardless of the uploaded name`() {
    val assetId = createAsset(filename = "intro.mp3", contentType = "audio/mpeg")
    val germanId = languageId("de")

    uploadTranslation(assetId, germanId, uploadedFilename = "Aufnahme final (2).m4a")
      .andIsOk
      .andAssertThatJson {
        node("translations[0].languageTag").isEqualTo("de")
        node("translations[0].status").isEqualTo("CURRENT")
        node("translations[0].originalFilename").isEqualTo("intro.mp3")
      }

    // replacing the localized file with a differently named one changes nothing
    uploadTranslation(assetId, germanId, uploadedFilename = "de-final-v3.wav")
      .andIsOk
      .andAssertThatJson {
        node("translations[0].originalFilename").isEqualTo("intro.mp3")
      }
  }

  @Test
  @ProjectJWTAuthTestMethod
  fun `replacing the source re-anchors the filename to the new original file`() {
    val assetId = createAsset(filename = "intro.mp3", contentType = "audio/mpeg")

    putMultipart(
      "binary-assets/$assetId/source",
      MockMultipartFile("file", "intro-v2.wav", "audio/wav", byteArrayOf(4, 5, 6)),
    ).andIsOk.andAssertThatJson {
      node("originalFilename").isEqualTo("intro-v2.wav")
      node("sourceRevision").isEqualTo(2)
    }
  }

  @Test
  @ProjectJWTAuthTestMethod
  fun `localized filename extension matches the uploaded media type`() {
    val assetId = createAsset(filename = "intro.mp3", contentType = "audio/mpeg")
    val germanId = languageId("de")

    uploadTranslation(
      assetId,
      germanId,
      uploadedFilename = "recording.webm",
      contentType = "audio/webm;codecs=opus",
    ).andIsOk.andAssertThatJson {
      node("translations[0].originalFilename").isEqualTo("intro.webm")
      node("translations[0].contentType").isEqualTo("audio/webm;codecs=opus")
    }
  }

  @Test
  @ProjectJWTAuthTestMethod
  fun `localized filename stays within the database limit when its extension grows`() {
    val assetId = createAsset(filename = "${"a".repeat(251)}.mp3", contentType = "audio/mpeg")
    val germanId = languageId("de")

    uploadTranslation(
      assetId,
      germanId,
      uploadedFilename = "recording.webm",
      contentType = "audio/webm;codecs=opus",
    ).andIsOk.andAssertThatJson {
      node("translations[0].originalFilename").isEqualTo("${"a".repeat(250)}.webm")
    }
  }

  @Test
  @ProjectJWTAuthTestMethod
  fun `source filename without extension gets one from the content type`() {
    val create =
      performProjectAuthMultipart(
        url = "binary-assets",
        files =
          listOf(
            MockMultipartFile("name", null, MediaType.TEXT_PLAIN_VALUE, "voiceover".toByteArray()),
            MockMultipartFile("file", "voiceover", "audio/mpeg", byteArrayOf(1, 2, 3)),
          ),
      ).andIsCreated

    create.andAssertThatJson {
      node("originalFilename").isEqualTo("voiceover.mp3")
    }

    val noMapping =
      performProjectAuthMultipart(
        url = "binary-assets",
        files =
          listOf(
            MockMultipartFile("name", null, MediaType.TEXT_PLAIN_VALUE, "project".toByteArray()),
            MockMultipartFile("file", "project", "application/octet-stream", byteArrayOf(1, 2, 3)),
          ),
      ).andIsCreated

    noMapping.andAssertThatJson {
      node("originalFilename").isEqualTo("project")
    }
  }

  @Test
  @ProjectJWTAuthTestMethod
  fun `list page stays stable when an asset is updated between fetches`() {
    // Regression: the list query had no ORDER BY, so a write that moves the row physically
    // (e.g. the ✨ transcribe button linking a transcript key) shifted the page window and
    // pulled a never-seen asset into view — looking exactly like the click created an asset.
    val ids =
      (1..35).map {
        createAsset(filename = "vox-$it.mp3", contentType = "audio/mpeg", name = "vox-$it")
      }

    fun pageOne(): Pair<List<Long>, Long> {
      val response = performProjectAuthGet("binary-assets?size=30").andIsOk.andReturn()
      val tree = jacksonObjectMapper().readTree(response.response.contentAsString)
      val assets = tree.at("/_embedded/binaryAssets").map { it.get("id").asLong() }
      return assets to tree.at("/page/totalElements").asLong()
    }

    val (before, totalBefore) = pageOne()
    assertThat(before).hasSize(30)

    // the same write the ✨ transcribe button performs: links a transcript key to the asset
    performProjectAuthPost("binary-assets/${ids.first()}/transcript", mapOf("text" to "hi")).andIsOk

    val (after, totalAfter) = pageOne()
    assertThat(totalAfter).isEqualTo(totalBefore)
    assertThat(after).containsExactlyElementsOf(before)
  }

  @Test
  @ProjectJWTAuthTestMethod
  fun `creates lists and gets an asset with no source file`() {
    val assetId = createSourcelessAsset("combo-only")

    // isTranscribable used to dereference contentType, which 500s the whole list for one such row
    performProjectAuthGet("binary-assets").andIsOk.andAssertThatJson {
      node("_embedded.binaryAssets").isArray.isNotEmpty
    }

    performProjectAuthGet("binary-assets/$assetId").andIsOk.andAssertThatJson {
      node("name").isEqualTo("combo-only")
      node("sourceRevision").isEqualTo(1)
      node("originalFilename").isNull()
      node("contentType").isNull()
      node("sha256").isNull()
      node("byteSize").isEqualTo(0)
      node("transcriptionAvailable").isEqualTo(false)
    }
  }

  @Test
  @ProjectJWTAuthTestMethod
  fun `a source download ticket for an asset with no source file is not found`() {
    val assetId = createSourcelessAsset("no-source")

    performProjectAuthPost("binary-assets/$assetId/source/download-ticket", null)
      .andExpect(status().isNotFound)
      .andAssertThatJson { node("code").isEqualTo("binary_asset_source_not_found") }
  }

  @Test
  @ProjectJWTAuthTestMethod
  fun `a translation on a source-less asset keeps its own filename and extension`() {
    val assetId = createSourcelessAsset("combo-de")
    val german = languageId("de")

    uploadTranslation(assetId, german, "combo-de.mp3", "audio/mpeg").andIsOk

    performProjectAuthGet("binary-assets/$assetId").andIsOk.andAssertThatJson {
      node("originalFilename").isNull()
      node("translations").isArray.isNotEmpty
    }
  }

  @Test
  @ProjectJWTAuthTestMethod
  fun `an empty file part is still rejected rather than read as no source`() {
    performProjectAuthMultipart(
      url = "binary-assets",
      files =
        listOf(
          MockMultipartFile("name", null, MediaType.TEXT_PLAIN_VALUE, "empty-file".toByteArray()),
          MockMultipartFile("file", "empty.mp3", "audio/mpeg", ByteArray(0)),
        ),
    ).andExpect(status().isBadRequest)
  }

  @Test
  @ProjectJWTAuthTestMethod
  fun `deletes a source-less asset that has translations`() {
    val assetId = createSourcelessAsset("delete-me")
    uploadTranslation(assetId, languageId("de"), "delete-me-de.mp3", "audio/mpeg").andIsOk

    performProjectAuthDelete("binary-assets/$assetId", null).andExpect(status().isNoContent)
    performProjectAuthGet("binary-assets/$assetId").andExpect(status().isNotFound)
  }

  @Test
  @ProjectJWTAuthTestMethod
  fun `attaching a source later does not invalidate the files that predate it`() {
    val assetId = createSourcelessAsset("late-source")
    val german = languageId("de")
    uploadTranslation(assetId, german, "late-de.mp3", "audio/mpeg").andIsOk

    performProjectAuthPut("binary-assets/$assetId/translations/$german/reviewed", mapOf("reviewed" to true)).andIsOk

    putMultipart(
      "binary-assets/$assetId/source",
      MockMultipartFile("file", "late.mp3", "audio/mpeg", byteArrayOf(4, 5, 6)),
    ).andIsOk

    // those files were never translations of this original, so they stay current and confirmed
    performProjectAuthGet("binary-assets/$assetId").andIsOk.andAssertThatJson {
      node("sourceRevision").isEqualTo(1)
      node("originalFilename").isEqualTo("late.mp3")
      node("translations[0].status").isEqualTo("CURRENT")
      node("translations[0].reviewed").isEqualTo(true)
    }
  }

  @Test
  @ProjectJWTAuthTestMethod
  fun `replacing a real source still invalidates its translations`() {
    val assetId = createAsset("orig.mp3", "audio/mpeg", name = "real-source")
    val german = languageId("de")
    uploadTranslation(assetId, german, "orig-de.mp3", "audio/mpeg").andIsOk
    performProjectAuthPut("binary-assets/$assetId/translations/$german/reviewed", mapOf("reviewed" to true)).andIsOk

    putMultipart(
      "binary-assets/$assetId/source",
      MockMultipartFile("file", "orig2.mp3", "audio/mpeg", byteArrayOf(4, 5, 6)),
    ).andIsOk

    performProjectAuthGet("binary-assets/$assetId").andIsOk.andAssertThatJson {
      node("sourceRevision").isEqualTo(2)
      node("translations[0].status").isEqualTo("OUTDATED")
      node("translations[0].reviewed").isEqualTo(false)
    }
  }

  private fun createSourcelessAsset(name: String): Long {
    val create =
      performProjectAuthMultipart(
        url = "binary-assets",
        files = listOf(MockMultipartFile("name", null, MediaType.TEXT_PLAIN_VALUE, name.toByteArray())),
      ).andIsCreated.andReturn()
    return jacksonObjectMapper().readTree(create.response.contentAsString).get("id").asLong()
  }

  private fun createAsset(
    filename: String,
    contentType: String,
    name: String = "asset",
  ): Long {
    val create =
      performProjectAuthMultipart(
        url = "binary-assets",
        files =
          listOf(
            MockMultipartFile("name", null, MediaType.TEXT_PLAIN_VALUE, name.toByteArray()),
            MockMultipartFile("file", filename, contentType, byteArrayOf(1, 2, 3)),
          ),
      ).andIsCreated.andReturn()
    return jacksonObjectMapper().readTree(create.response.contentAsString).get("id").asLong()
  }

  private fun uploadTranslation(
    assetId: Long,
    languageId: Long,
    uploadedFilename: String,
    contentType: String = "application/octet-stream",
  ): ResultActions =
    putMultipart(
      "binary-assets/$assetId/translations/$languageId",
      MockMultipartFile("file", uploadedFilename, contentType, byteArrayOf(7, 8, 9)),
      MockMultipartFile("translatedAgainstSourceRevision", null, MediaType.TEXT_PLAIN_VALUE, "1".toByteArray()),
    )

  private fun putMultipart(
    url: String,
    vararg parts: MockMultipartFile,
  ): ResultActions {
    val builder = MockMvcRequestBuilders.multipart(HttpMethod.PUT, "/v2/projects/${project.id}/$url")
    parts.forEach { builder.file(it) }
    return mvc.perform(AuthorizedRequestFactory.addToken(builder))
  }

  private fun languageId(tag: String): Long {
    val response = performProjectAuthGet("languages?size=100").andIsOk.andReturn()
    val languages = jacksonObjectMapper().readTree(response.response.contentAsString).at("/_embedded/languages")
    return languages.first { it.get("tag").asText() == tag }.get("id").asLong()
  }
}
