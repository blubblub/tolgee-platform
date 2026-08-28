package io.tolgee.api.v2.controllers.binaryAsset

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.tolgee.ProjectAuthControllerTest
import io.tolgee.component.transcription.ElevenLabsVoiceClient
import io.tolgee.fixtures.AuthorizedRequestFactory
import io.tolgee.fixtures.andAssertThatJson
import io.tolgee.fixtures.andIsBadRequest
import io.tolgee.fixtures.andIsCreated
import io.tolgee.fixtures.andIsOk
import io.tolgee.model.enums.BinaryAssetMediaType
import io.tolgee.testing.annotations.ProjectJWTAuthTestMethod
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers

/**
 * An image is localized by another image, a video by another video — only a voice-over goes
 * through transcript, TTS and recording. The model says which apply; the API refuses the rest.
 */
@SpringBootTest(
  properties = [
    "tolgee.internal.use-in-memory-file-storage=false",
    "tolgee.transcription.api-key=test-key",
  ],
)
class BinaryAssetMediaTypeTest : ProjectAuthControllerTest("/v2/projects/") {
  @Autowired
  @MockitoBean
  lateinit var voiceClient: ElevenLabsVoiceClient

  @BeforeEach
  fun mockVoiceClient() {
    whenever(voiceClient.isConfigured).thenReturn(true)
    whenever(voiceClient.checkConfigured()).then { }
    whenever(voiceClient.synthesize(any(), any(), any())).thenReturn(byteArrayOf(0x49, 0x44, 0x33))
  }

  private fun createAsset(
    name: String,
    filename: String?,
    contentType: String = "application/octet-stream",
  ): Long {
    val parts =
      mutableListOf(MockMultipartFile("name", null, MediaType.TEXT_PLAIN_VALUE, name.toByteArray()))
    if (filename != null) {
      parts += MockMultipartFile("file", filename, contentType, byteArrayOf(0x01, 0x02, 0x03, 0x04))
    }
    val result = performProjectAuthMultipart(url = "binary-assets", files = parts).andIsCreated.andReturn()
    return jacksonObjectMapper().readTree(result.response.contentAsString).get("id").asLong()
  }

  private fun sourceLanguageId(assetId: Long): Long {
    val result = performProjectAuthGet("binary-assets/$assetId").andIsOk.andReturn()
    return jacksonObjectMapper().readTree(result.response.contentAsString).get("sourceLanguageId").asLong()
  }

  @Test
  fun `infers the type from the content type before the extension`() {
    assertThat(BinaryAssetMediaType.infer("audio/mpeg", "voice.bin")).isEqualTo(BinaryAssetMediaType.AUDIO)
    assertThat(BinaryAssetMediaType.infer("video/mp4; codecs=avc1", "clip.bin")).isEqualTo(BinaryAssetMediaType.VIDEO)
    assertThat(BinaryAssetMediaType.infer("image/png", "recording.wav")).isEqualTo(BinaryAssetMediaType.IMAGE)
    // octet-stream says nothing, so the extension decides
    assertThat(BinaryAssetMediaType.infer("application/octet-stream", "line.WAV")).isEqualTo(BinaryAssetMediaType.AUDIO)
    assertThat(BinaryAssetMediaType.infer("application/octet-stream", "shot.png")).isEqualTo(BinaryAssetMediaType.IMAGE)
    assertThat(BinaryAssetMediaType.infer(null, null)).isNull()
    assertThat(BinaryAssetMediaType.infer("application/pdf", "manual.pdf")).isNull()
  }

  @Test
  @ProjectJWTAuthTestMethod
  fun `an audio asset keeps every capability`() {
    val assetId = createAsset("vox-full", "vox-full.mp3", "audio/mpeg")

    performProjectAuthGet("binary-assets/$assetId").andIsOk.andAssertThatJson {
      node("mediaType").isEqualTo("AUDIO")
      node("capabilities.transcript").isEqualTo(true)
      node("capabilities.pipeline").isEqualTo(true)
      node("capabilities.record").isEqualTo(true)
    }
  }

  @Test
  @ProjectJWTAuthTestMethod
  fun `a video is localized by another video, with no transcript`() {
    val assetId = createAsset("clip", "clip.mp4", "video/mp4")

    performProjectAuthGet("binary-assets/$assetId").andIsOk.andAssertThatJson {
      node("mediaType").isEqualTo("VIDEO")
      node("capabilities.transcript").isEqualTo(false)
      node("capabilities.pipeline").isEqualTo(false)
      node("capabilities.record").isEqualTo(false)
      node("transcriptionAvailable").isEqualTo(false)
    }
    performProjectAuthPost("binary-assets/$assetId/transcript", mapOf("text" to "Spoken in the clip."))
      .andIsBadRequest
      .andAssertThatJson { node("code").isEqualTo("binary_asset_transcript_not_supported") }
  }

  @Test
  @ProjectJWTAuthTestMethod
  fun `an asset with no original takes its type from its localized files`() {
    val assetId = createAsset("sourceless-voice", null)
    uploadLane(assetId, languageId("de"), "voice_de.m4a", "application/octet-stream")

    performProjectAuthGet("binary-assets/$assetId").andIsOk.andAssertThatJson {
      node("mediaType").isEqualTo("AUDIO")
      // the source stays "not uploaded" — the sync scripts read these as "no source"
      node("contentType").isNull()
      node("originalFilename").isNull()
      node("byteSize").isEqualTo(0)
      node("capabilities.transcript").isEqualTo(true)
    }
    // the list filter agrees with the model: found under AUDIO and nowhere else
    assertThat(filtersListing("sourceless-voice")).containsExactly("AUDIO")
  }

  @Test
  @ProjectJWTAuthTestMethod
  fun `an asset with no original and an image lane is an image, and only an image`() {
    val assetId = createAsset("sourceless-shot", null)
    uploadLane(assetId, languageId("de"), "shot_de.jpg", "image/jpeg")

    performProjectAuthGet("binary-assets/$assetId").andIsOk.andAssertThatJson {
      node("mediaType").isEqualTo("IMAGE")
      node("contentType").isNull()
      node("capabilities.transcript").isEqualTo(false)
      node("capabilities.pipeline").isEqualTo(false)
    }
    assertThat(filtersListing("sourceless-shot")).containsExactly("IMAGE")
  }

  @Test
  @ProjectJWTAuthTestMethod
  fun `the original decides the type even when a lane disagrees`() {
    val assetId = createAsset("voice-with-odd-lane", "voice.mp3", "audio/mpeg")
    uploadLane(assetId, languageId("de"), "voice_de.png", "image/png")

    performProjectAuthGet("binary-assets/$assetId").andIsOk.andAssertThatJson {
      node("mediaType").isEqualTo("AUDIO")
    }
    assertThat(filtersListing("voice-with-odd-lane")).containsExactly("AUDIO")
  }

  @Test
  @ProjectJWTAuthTestMethod
  fun `lanes of mixed types resolve to the oldest typed lane, in the model and in the filter`() {
    val assetId = createAsset("sourceless-mixed", null)
    val frId = createLanguage("fr")
    uploadLane(assetId, languageId("de"), "first_de.png", "image/png")
    uploadLane(assetId, frId, "later_fr.wav", "audio/wav")

    performProjectAuthGet("binary-assets/$assetId").andIsOk.andAssertThatJson {
      node("mediaType").isEqualTo("IMAGE")
    }
    assertThat(filtersListing("sourceless-mixed")).containsExactly("IMAGE")
  }

  @Test
  @ProjectJWTAuthTestMethod
  fun `an asset with no original and no lanes has no type and matches no filter`() {
    createAsset("sourceless-empty", null)

    assertThat(filtersListing("sourceless-empty")).isEmpty()
    // still there when nothing is filtered
    assertThat(listedNames(null)).contains("sourceless-empty")
    assertThat(listedNames("AUDIO,VIDEO,IMAGE")).doesNotContain("sourceless-empty")
  }

  private fun uploadLane(
    assetId: Long,
    langId: Long,
    filename: String,
    contentType: String,
  ) {
    val builder =
      MockMvcRequestBuilders
        .multipart("${projectUrlPrefix}${project.id}/binary-assets/$assetId/translations/$langId")
        .file(MockMultipartFile("file", filename, contentType, byteArrayOf(1, 2, 3, 4)))
        .file(MockMultipartFile("translatedAgainstSourceRevision", null, MediaType.TEXT_PLAIN_VALUE, "1".toByteArray()))
    builder.with(
      RequestPostProcessor { req ->
        req.method = "PUT"
        req
      },
    )
    mvc.perform(AuthorizedRequestFactory.addToken(builder)).andExpect(MockMvcResultMatchers.status().isOk)
  }

  private fun createLanguage(tag: String): Long {
    val result =
      performProjectAuthPost(
        "languages",
        mapOf("name" to tag.uppercase(), "originalName" to tag, "tag" to tag),
      ).andIsOk.andReturn()
    return jacksonObjectMapper().readTree(result.response.contentAsString).get("id").asLong()
  }

  /** Which of the three media-type filters list the asset — the model and the filter must agree. */
  private fun filtersListing(name: String): List<String> =
    listOf("AUDIO", "VIDEO", "IMAGE").filter { name in listedNames(it) }

  private fun listedNames(mediaType: String?): List<String> {
    val filter = mediaType?.let { "filterMediaType=$it&" } ?: ""
    val body =
      performProjectAuthGet("binary-assets?${filter}size=50")
        .andIsOk
        .andReturn()
        .response.contentAsString
    val embedded = jacksonObjectMapper().readTree(body).get("_embedded") ?: return emptyList()
    return embedded.get("binaryAssets").map { it.get("name").asText() }
  }

  private fun languageId(tag: String): Long {
    val body =
      performProjectAuthGet("languages")
        .andIsOk
        .andReturn()
        .response.contentAsString
    val tree = jacksonObjectMapper().readTree(body)
    return tree
      .get("_embedded")
      .get("languages")
      .first { it.get("tag").asText() == tag }
      .get("id")
      .asLong()
  }

  @Test
  @ProjectJWTAuthTestMethod
  fun `an image has no transcript and no pipeline`() {
    val assetId = createAsset("splash", "splash.png", "image/png")

    performProjectAuthGet("binary-assets/$assetId").andIsOk.andAssertThatJson {
      node("mediaType").isEqualTo("IMAGE")
      node("capabilities.transcript").isEqualTo(false)
      node("capabilities.pipeline").isEqualTo(false)
      node("capabilities.record").isEqualTo(false)
      node("transcriptionAvailable").isEqualTo(false)
    }

    performProjectAuthPost("binary-assets/$assetId/transcript", mapOf("text" to "Let's go!"))
      .andIsBadRequest
      .andAssertThatJson { node("code").isEqualTo("binary_asset_transcript_not_supported") }

    performProjectAuthPost(
      "binary-assets/$assetId/translations/${sourceLanguageId(assetId)}/versions/run",
      mapOf("tool" to "tts", "params" to mapOf("voiceId" to "v1")),
    ).andIsBadRequest
      .andAssertThatJson { node("code").isEqualTo("binary_asset_tool_not_supported") }
  }

  @Test
  @ProjectJWTAuthTestMethod
  fun `the list carries the type so the table can vary per row`() {
    createAsset("row-audio", "row-audio.wav", "audio/wav")
    createAsset("row-image", "row-image.jpg", "image/jpeg")

    val result = performProjectAuthGet("binary-assets?size=50").andIsOk.andReturn()
    val rows =
      jacksonObjectMapper()
        .readTree(result.response.contentAsString)
        .get("_embedded")
        .get("binaryAssets")
        .associateBy { it.get("name").asText() }

    val audio = rows.getValue("row-audio")
    val image = rows.getValue("row-image")
    assertThat(audio["mediaType"].asText()).isEqualTo("AUDIO")
    assertThat(audio["capabilities"]["transcript"].asBoolean()).isTrue()
    assertThat(image["mediaType"].asText()).isEqualTo("IMAGE")
    assertThat(image["capabilities"]["transcript"].asBoolean()).isFalse()
    assertThat(image["capabilities"]["pipeline"].asBoolean()).isFalse()
  }

  @Test
  @ProjectJWTAuthTestMethod
  fun `an asset with no file yet keeps every affordance`() {
    // recording or TTS may be how it gets its first file, and its transcript may be all it has
    val assetId = createAsset("sourceless", null)

    performProjectAuthGet("binary-assets/$assetId").andIsOk.andAssertThatJson {
      node("mediaType").isNull()
      node("capabilities.transcript").isEqualTo(true)
      node("capabilities.pipeline").isEqualTo(true)
      node("capabilities.record").isEqualTo(true)
    }
  }
}
