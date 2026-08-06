package io.tolgee.api.v2.controllers.binaryAsset

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.tolgee.ProjectAuthControllerTest
import io.tolgee.component.transcription.ElevenLabsVoiceClient
import io.tolgee.development.testDataBuilder.data.LanguagePermissionsTestData
import io.tolgee.fixtures.andAssertThatJson
import io.tolgee.fixtures.andIsBadRequest
import io.tolgee.fixtures.andIsCreated
import io.tolgee.fixtures.andIsForbidden
import io.tolgee.fixtures.andIsNoContent
import io.tolgee.fixtures.andIsNotFound
import io.tolgee.fixtures.andIsOk
import io.tolgee.testing.annotations.ProjectJWTAuthTestMethod
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.result.MockMvcResultMatchers

@SpringBootTest(
  properties = [
    "tolgee.internal.use-in-memory-file-storage=false",
    "tolgee.transcription.api-key=test-key",
  ],
)
class BinaryAssetTranslationVersionControllerTest : ProjectAuthControllerTest("/v2/projects/") {
  @Autowired
  @MockitoBean
  lateinit var voiceClient: ElevenLabsVoiceClient

  private val mp3Bytes = byteArrayOf(0x49, 0x44, 0x33, 0x03, 0x00, 0x00, 0x00, 0x00)
  private val voiceChangedBytes = byteArrayOf(0x49, 0x44, 0x33, 0x03, 0x01, 0x01)

  @BeforeEach
  fun mockVoiceClient() {
    whenever(voiceClient.isConfigured).thenReturn(true)
    whenever(voiceClient.checkConfigured()).then { }
    whenever(voiceClient.synthesize(any(), any(), any())).thenReturn(mp3Bytes)
    whenever(voiceClient.changeVoice(any(), any(), any(), any(), any(), any())).thenReturn(voiceChangedBytes)
  }

  private fun createAsset(name: String): Long {
    val result =
      performProjectAuthMultipart(
        url = "binary-assets",
        files =
          listOf(
            MockMultipartFile("name", null, MediaType.TEXT_PLAIN_VALUE, name.toByteArray()),
            MockMultipartFile("file", "$name.mp3", "audio/mpeg", byteArrayOf(0x49, 0x44, 0x33, 0x04)),
          ),
      ).andIsCreated.andReturn()
    return jacksonObjectMapper().readTree(result.response.contentAsString).get("id").asLong()
  }

  private fun uploadTranslation(
    assetId: Long,
    languageTag: String,
  ) {
    val langId = languageId(languageTag)
    val url = "${projectUrlPrefix}${project.id}/binary-assets/$assetId/translations/$langId"
    val builder =
      org.springframework.test.web.servlet.request.MockMvcRequestBuilders
        .multipart(url)
        .file(MockMultipartFile("file", "$languageTag.mp3", "audio/mpeg", byteArrayOf(0x49, 0x44, 0x33, 0x05)))
        .file(
          MockMultipartFile(
            "translatedAgainstSourceRevision",
            null,
            MediaType.TEXT_PLAIN_VALUE,
            "1".toByteArray(),
          ),
        )
    builder.with(
      org.springframework.test.web.servlet.request.RequestPostProcessor { req ->
        req.method = "PUT"
        req
      },
    )
    mvc
      .perform(
        io.tolgee.fixtures.AuthorizedRequestFactory
          .addToken(builder),
      ).andExpect(
        org.springframework.test.web.servlet.result.MockMvcResultMatchers
          .status()
          .isOk,
      )
  }

  private fun languageId(tag: String): Long {
    val tree =
      jacksonObjectMapper()
        .readTree(
          performProjectAuthGet("languages")
            .andIsOk
            .andReturn()
            .response.contentAsString,
        )
    return tree
      .get("_embedded")
      .get("languages")
      .first { it.get("tag").asText() == tag }
      .get("id")
      .asLong()
  }

  private fun createTranscriptWithText(
    assetId: Long,
    text: String,
  ) {
    performProjectAuthPost("binary-assets/$assetId/transcript", mapOf("text" to text)).andIsOk
  }

  private fun setTranslationForKey(
    keyName: String,
    languageTag: String,
    text: String,
  ) {
    performProjectAuthPut(
      "translations",
      mapOf("key" to keyName, "translations" to mapOf(languageTag to text)),
    ).andIsOk
  }

  private fun runTool(
    assetId: Long,
    languageTag: String,
    tool: String,
    params: Map<String, Any?> = mapOf("voiceId" to "voice-1"),
    baseVersionId: Long? = null,
  ): Long {
    val body = mutableMapOf<String, Any?>("tool" to tool, "params" to params)
    if (baseVersionId != null) body["baseVersionId"] = baseVersionId
    val result =
      performProjectAuthPost(
        "binary-assets/$assetId/translations/${languageId(languageTag)}/versions/run",
        body,
      ).andIsCreated.andReturn()
    return jacksonObjectMapper().readTree(result.response.contentAsString).get("id").asLong()
  }

  private fun listVersions(
    assetId: Long,
    languageTag: String,
  ): List<Long> {
    val tree =
      jacksonObjectMapper()
        .readTree(
          performProjectAuthGet("binary-assets/$assetId/translations/${languageId(languageTag)}/versions")
            .andIsOk
            .andReturn()
            .response.contentAsString,
        )
    return tree.map { it.get("id").asLong() }
  }

  @Test
  @ProjectJWTAuthTestMethod
  fun `runs tts on OG and creates a version`() {
    val assetId = createAsset("vox-tts-og")
    uploadTranslation(assetId, "de")
    createTranscriptWithText(assetId, "Hello world.")
    setTranslationForKey("transcript.vox-tts-og", "de", "Hallo Welt.")

    val versionId = runTool(assetId, "de", "tts")

    performProjectAuthGet("binary-assets/$assetId/translations/${languageId("de")}/versions")
      .andIsOk
      .andAssertThatJson {
        node("[0].id").isEqualTo(versionId)
        node("[0].tool").isEqualTo("tts")
        node("[0].chosen").isEqualTo(false)
        node("[0].byteSize").isEqualTo(mp3Bytes.size)
        node("[0].sha256").isString.hasSize(64)
        node("[0].contentType").isEqualTo("audio/mpeg")
        node("[0].originalFilename").isEqualTo("de-tts.mp3")
      }

    assertThat(listVersions(assetId, "de")).containsExactly(versionId)
  }

  @Test
  @ProjectJWTAuthTestMethod
  fun `runs tts on a version for pipeline chaining`() {
    val assetId = createAsset("vox-chain")
    uploadTranslation(assetId, "de")
    createTranscriptWithText(assetId, "Source.")
    setTranslationForKey("transcript.vox-chain", "de", "Quelle.")

    val first = runTool(assetId, "de", "tts")
    val second = runTool(assetId, "de", "tts", baseVersionId = first)

    assertThat(listVersions(assetId, "de")).containsExactly(first, second)
  }

  @Test
  @ProjectJWTAuthTestMethod
  fun `runs voice changer on the uploaded translation`() {
    val assetId = createAsset("vox-changer")
    uploadTranslation(assetId, "de")

    val versionId = runTool(assetId, "de", "voice-changer")

    performProjectAuthGet("binary-assets/$assetId/translations/${languageId("de")}/versions")
      .andIsOk
      .andAssertThatJson {
        node("[0].id").isEqualTo(versionId)
        node("[0].tool").isEqualTo("voice-changer")
        node("[0].byteSize").isEqualTo(voiceChangedBytes.size)
        node("[0].contentType").isEqualTo("audio/mpeg")
        node("[0].originalFilename").isEqualTo("de-voice.mp3")
      }

    // no params given -> configured default model, noise removal off
    verify(voiceClient).changeVoice(
      any(),
      eq("de.mp3"),
      any(),
      eq("voice-1"),
      eq("eleven_multilingual_sts_v2"),
      eq(false),
    )
  }

  @Test
  @ProjectJWTAuthTestMethod
  fun `chains voice changer after tts and honours param overrides`() {
    val assetId = createAsset("vox-changer-chain")
    uploadTranslation(assetId, "de")
    createTranscriptWithText(assetId, "Source.")
    setTranslationForKey("transcript.vox-changer-chain", "de", "Quelle.")

    val ttsVersion = runTool(assetId, "de", "tts")
    val changed =
      runTool(
        assetId,
        "de",
        "voice-changer",
        params =
          mapOf(
            "voiceId" to "voice-9",
            "modelId" to "eleven_english_sts_v2",
            "removeBackgroundNoise" to true,
          ),
        baseVersionId = ttsVersion,
      )

    assertThat(listVersions(assetId, "de")).containsExactly(ttsVersion, changed)
    verify(voiceClient).changeVoice(
      any(),
      eq("de-tts.mp3"),
      any(),
      eq("voice-9"),
      eq("eleven_english_sts_v2"),
      eq(true),
    )
  }

  @Test
  @ProjectJWTAuthTestMethod
  fun `rejects voice changer without a voice id`() {
    val assetId = createAsset("vox-changer-no-voice")
    uploadTranslation(assetId, "de")

    performProjectAuthPost(
      "binary-assets/$assetId/translations/${languageId("de")}/versions/run",
      mapOf("tool" to "voice-changer", "params" to mapOf<String, Any?>()),
    ).andIsBadRequest.andAssertThatJson {
      node("code").isEqualTo("binary_asset_tts_voice_id_required")
    }
  }

  @Test
  @ProjectJWTAuthTestMethod
  fun `falls back to the language default voice, then the project default`() {
    val assetId = createAsset("vox-default-voice")
    uploadTranslation(assetId, "de")
    createTranscriptWithText(assetId, "Source.")
    setTranslationForKey("transcript.vox-default-voice", "de", "Quelle.")
    val de = languageId("de")

    performProjectAuthPut(
      "binary-asset-voices",
      mutableMapOf<String, Any?>("languageId" to null, "voiceId" to "voice-project"),
    ).andIsOk

    // no voiceId param -> project default
    runTool(assetId, "de", "tts", params = emptyMap())
    verify(voiceClient).synthesize(any(), eq("voice-project"), any())

    performProjectAuthPut(
      "binary-asset-voices",
      mutableMapOf<String, Any?>("languageId" to de, "voiceId" to "voice-de"),
    ).andIsOk

    // language default wins over the project default
    runTool(assetId, "de", "tts", params = emptyMap())
    verify(voiceClient).synthesize(any(), eq("voice-de"), any())

    // an explicit param still wins over both
    runTool(assetId, "de", "tts", params = mapOf("voiceId" to "voice-explicit"))
    verify(voiceClient).synthesize(any(), eq("voice-explicit"), any())
  }

  @Test
  @ProjectJWTAuthTestMethod
  fun `still rejects a run with no param and no default`() {
    val assetId = createAsset("vox-no-default-voice")
    uploadTranslation(assetId, "de")
    createTranscriptWithText(assetId, "Source.")
    setTranslationForKey("transcript.vox-no-default-voice", "de", "Quelle.")

    performProjectAuthPost(
      "binary-assets/$assetId/translations/${languageId("de")}/versions/run",
      mapOf("tool" to "tts", "params" to mapOf<String, Any?>()),
    ).andIsBadRequest.andAssertThatJson {
      node("code").isEqualTo("binary_asset_tts_voice_id_required")
    }
  }

  @Test
  @ProjectJWTAuthTestMethod
  fun `rejects unknown tool`() {
    val assetId = createAsset("vox-unknown")
    uploadTranslation(assetId, "de")

    performProjectAuthPost(
      "binary-assets/$assetId/translations/${languageId("de")}/versions/run",
      mapOf("tool" to "nonexistent", "params" to mapOf<String, Any?>()),
    ).andIsBadRequest.andAssertThatJson {
      node("code").isEqualTo("binary_asset_tool_unknown")
    }
  }

  @Test
  @ProjectJWTAuthTestMethod
  fun `rejects tts without transcript in target language`() {
    val assetId = createAsset("vox-no-transcript")
    uploadTranslation(assetId, "de")
    createTranscriptWithText(assetId, "English source.")

    performProjectAuthPost(
      "binary-assets/$assetId/translations/${languageId("de")}/versions/run",
      mapOf("tool" to "tts", "params" to mapOf("voiceId" to "v1")),
    ).andIsBadRequest.andAssertThatJson {
      node("code").isEqualTo("binary_asset_tts_no_transcript")
    }
  }

  @Test
  @ProjectJWTAuthTestMethod
  fun `sets chosen version and switches and clears`() {
    val assetId = createAsset("vox-chosen")
    uploadTranslation(assetId, "de")
    createTranscriptWithText(assetId, "Source.")
    setTranslationForKey("transcript.vox-chosen", "de", "Quelle.")

    val first = runTool(assetId, "de", "tts")
    val second = runTool(assetId, "de", "tts")

    val chosenResponse =
      performProjectAuthPut(
        "binary-assets/$assetId/translations/${languageId("de")}/versions/chosen-version",
        mapOf("versionId" to first),
      ).andIsOk.andReturn()
    val chosenTree = jacksonObjectMapper().readTree(chosenResponse.response.contentAsString)
    val de = chosenTree.get("translations").first { it.get("languageTag").asText() == "de" }
    assertThat(de.get("chosenVersionId").asLong()).isEqualTo(first)
    assertThat(de.get("versionCount").asInt()).isEqualTo(2)
    // the assets list shows which file is final without fetching every version
    assertThat(de.get("chosenVersionFilename").asText()).isEqualTo("de-tts.mp3")
    assertThat(de.get("chosenVersionTool").asText()).isEqualTo("tts")

    performProjectAuthPut(
      "binary-assets/$assetId/translations/${languageId("de")}/versions/chosen-version",
      mapOf("versionId" to second),
    ).andIsOk

    val versions =
      jacksonObjectMapper()
        .readTree(
          performProjectAuthGet("binary-assets/$assetId/translations/${languageId("de")}/versions")
            .andIsOk
            .andReturn()
            .response.contentAsString,
        )
    assertThat(versions.first { it.get("id").asLong() == first }.get("chosen").asBoolean()).isFalse()
    assertThat(versions.first { it.get("id").asLong() == second }.get("chosen").asBoolean()).isTrue()

    performProjectAuthPut(
      "binary-assets/$assetId/translations/${languageId("de")}/versions/chosen-version",
      mapOf<String, Any?>("versionId" to null),
    ).andIsOk

    val cleared =
      jacksonObjectMapper()
        .readTree(
          performProjectAuthGet("binary-assets/$assetId/translations/${languageId("de")}/versions")
            .andIsOk
            .andReturn()
            .response.contentAsString,
        )
    cleared.forEach { assertThat(it.get("chosen").asBoolean()).isFalse() }
  }

  @Test
  @ProjectJWTAuthTestMethod
  fun `switching or deleting the final version drops the confirmation`() {
    val assetId = createAsset("vox-review-version")
    uploadTranslation(assetId, "de")
    createTranscriptWithText(assetId, "Source.")
    setTranslationForKey("transcript.vox-review-version", "de", "Quelle.")
    val de = languageId("de")

    val first = runTool(assetId, "de", "tts")
    val second = runTool(assetId, "de", "tts")

    chooseVersion(assetId, de, first)
    confirm(assetId, de)
    assertThat(isReviewed(assetId, de)).isTrue()

    // a run on its own leaves the final alone, so the confirmation must survive it
    runTool(assetId, "de", "tts")
    assertThat(isReviewed(assetId, de)).isTrue()

    chooseVersion(assetId, de, second)
    assertThat(isReviewed(assetId, de)).isFalse()

    confirm(assetId, de)
    performProjectAuthDelete("binary-assets/$assetId/translations/$de/versions/$second", null)
      .andIsNoContent
    assertThat(isReviewed(assetId, de)).isFalse()
  }

  private fun chooseVersion(
    assetId: Long,
    languageId: Long,
    versionId: Long?,
  ) {
    performProjectAuthPut(
      "binary-assets/$assetId/translations/$languageId/versions/chosen-version",
      mapOf("versionId" to versionId),
    ).andIsOk
  }

  private fun confirm(
    assetId: Long,
    languageId: Long,
  ) {
    performProjectAuthPut(
      "binary-assets/$assetId/translations/$languageId/reviewed",
      mapOf("reviewed" to true),
    ).andIsOk
  }

  private fun isReviewed(
    assetId: Long,
    languageId: Long,
  ): Boolean =
    jacksonObjectMapper()
      .readTree(
        performProjectAuthGet("binary-assets/$assetId").andIsOk.andReturn().response.contentAsString,
      ).get("translations")
      .first { it.get("languageId").asLong() == languageId }
      .get("reviewed")
      .asBoolean()

  @Test
  @ProjectJWTAuthTestMethod
  fun `deletes a version and deleting the translation works with versions present`() {
    val assetId = createAsset("vox-delete")
    uploadTranslation(assetId, "de")
    createTranscriptWithText(assetId, "Source.")
    setTranslationForKey("transcript.vox-delete", "de", "Quelle.")

    val first = runTool(assetId, "de", "tts")
    runTool(assetId, "de", "tts")

    performProjectAuthDelete(
      "binary-assets/$assetId/translations/${languageId("de")}/versions/$first",
      null,
    ).andIsNoContent

    assertThat(listVersions(assetId, "de")).doesNotContain(first)

    performProjectAuthDelete("binary-assets/$assetId/translations/${languageId("de")}", null).andIsNoContent
    assertThat(listVersions(assetId, "de")).isEmpty()
  }

  @Test
  @ProjectJWTAuthTestMethod
  fun `version download ticket returns the exact bytes`() {
    val assetId = createAsset("vox-download")
    uploadTranslation(assetId, "de")
    createTranscriptWithText(assetId, "Source.")
    setTranslationForKey("transcript.vox-download", "de", "Quelle.")

    val versionId = runTool(assetId, "de", "tts")

    val ticketResponse =
      performProjectAuthPost(
        "binary-assets/$assetId/translations/${languageId("de")}/versions/$versionId/download-ticket",
        null,
      ).andIsOk.andReturn()
    val ticketUrl =
      jacksonObjectMapper()
        .readTree(ticketResponse.response.contentAsString)
        .get("url")
        .asText()
    val token = ticketUrl.substringAfter("token=")

    mvc
      .perform(MockMvcRequestBuilders.get("/v2/binary-assets/download").param("token", token))
      .andExpect(MockMvcResultMatchers.status().isOk)
      .andExpect { result ->
        assertThat(result.response.contentAsByteArray).isEqualTo(mp3Bytes)
      }
  }

  @Test
  @ProjectJWTAuthTestMethod
  fun `denies run for user without translations edit`() {
    val assetId = createAsset("vox-forbidden")
    uploadTranslation(assetId, "de")
    createTranscriptWithText(assetId, "Source.")
    setTranslationForKey("transcript.vox-forbidden", "de", "Quelle.")
    val deLangId = languageId("de")

    val restrictedUser = dbPopulator.createUserIfNotExists("vox-restricted", "password")
    val projectId = project.id

    executeInNewTransaction {
      val freshProject = projectRepository.findById(projectId).orElseThrow()
      val permission =
        io.tolgee.model.Permission().apply {
          user = restrictedUser
          project = freshProject
          scopes = arrayOf(io.tolgee.model.enums.Scope.TRANSLATIONS_VIEW)
          type = null
        }
      permissionService.create(permission)
    }

    userAccount = restrictedUser

    performProjectAuthPost(
      "binary-assets/$assetId/translations/$deLangId/versions/run",
      mapOf("tool" to "tts", "params" to mapOf("voiceId" to "v1")),
    ).andIsForbidden
  }
}
