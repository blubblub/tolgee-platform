package io.tolgee.mcp.tools

import com.fasterxml.jackson.databind.JsonNode
import io.modelcontextprotocol.client.McpSyncClient
import io.tolgee.AbstractMcpTest
import io.tolgee.component.transcription.ElevenLabsTranscriptionClient
import io.tolgee.component.transcription.ElevenLabsVoiceClient
import io.tolgee.dtos.request.LanguageRequest
import io.tolgee.model.enums.Scope
import io.tolgee.testing.assertions.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.net.URI
import java.util.Base64

@TestPropertySource(
  properties = [
    "tolgee.internal.use-in-memory-file-storage=false",
    "tolgee.transcription.api-key=test-key",
  ],
)
class McpBinaryAssetToolsTest : AbstractMcpTest() {
  @Autowired
  @MockitoBean
  lateinit var voiceClient: ElevenLabsVoiceClient

  @Autowired
  @MockitoBean
  lateinit var transcriptionClient: ElevenLabsTranscriptionClient

  lateinit var data: McpPatTestData
  lateinit var client: McpSyncClient

  private val mp3Bytes = byteArrayOf(0x49, 0x44, 0x33, 0x03, 0x00, 0x00, 0x00, 0x00)
  private val mp3Base64: String get() = Base64.getEncoder().encodeToString(mp3Bytes)

  @BeforeEach
  fun setup() {
    data = createTestDataWithPat()
    client = createMcpClientWithPat(data.pat.token!!)
    whenever(voiceClient.isConfigured).thenReturn(true)
    whenever(voiceClient.checkConfigured()).then { }
    whenever(voiceClient.synthesize(any(), any(), any())).thenReturn(mp3Bytes)
    whenever(voiceClient.changeVoice(any(), any(), any(), any(), any(), any())).thenReturn(mp3Bytes)
    whenever(transcriptionClient.isConfigured).thenReturn(true)
    whenever(transcriptionClient.checkConfigured()).then { }
    whenever(transcriptionClient.transcribe(any(), any(), any(), any(), any())).thenReturn("mocked transcript")
  }

  @Test
  fun `asset lifecycle works over MCP`() {
    val created = createAsset(client, "intro")
    val assetId = created["id"].asLong()
    assertThat(created["name"].asText()).isEqualTo("intro")
    assertThat(created["originalFilename"].asText()).isEqualTo("intro.mp3")
    assertThat(created["sourceLanguageTag"].asText()).isEqualTo("en")
    assertThat(created["byteSize"].asLong()).isEqualTo(mp3Bytes.size.toLong())

    val list = callToolAndGetJson(client, "list_assets", mapOf("projectId" to data.projectId))
    assertThat(list["totalItems"].asInt()).isEqualTo(1)
    assertThat(list["items"][0]["name"].asText()).isEqualTo("intro")

    val got = callToolAndGetJson(client, "get_asset", assetArgs(assetId))
    assertThat(got["id"].asLong()).isEqualTo(assetId)

    val updated =
      callToolAndGetJson(
        client,
        "update_asset",
        assetArgs(assetId) + mapOf("name" to "intro-renamed", "description" to "a description"),
      )
    assertThat(updated["name"].asText()).isEqualTo("intro-renamed")
    assertThat(updated["description"].asText()).isEqualTo("a description")

    val replaced =
      callToolAndGetJson(
        client,
        "replace_asset_source",
        assetArgs(assetId) + uploadArgs("new.mp3"),
      )
    assertThat(replaced["sourceRevision"].asLong()).isEqualTo(2)
    assertThat(replaced["originalFilename"].asText()).isEqualTo("new.mp3")

    val deleted = callToolAndGetJson(client, "delete_asset", assetArgs(assetId))
    assertThat(deleted["deleted"].asBoolean()).isTrue()
    val emptyList = callToolAndGetJson(client, "list_assets", mapOf("projectId" to data.projectId))
    assertThat(emptyList["totalItems"].asInt()).isEqualTo(0)
  }

  @Test
  fun `transcript tools manage the transcript key`() {
    val assetId = createAsset(client, "vox")["id"].asLong()

    val withTranscript =
      callToolAndGetJson(
        client,
        "set_asset_transcript",
        assetArgs(assetId) + mapOf("text" to "The apple is red."),
      )
    assertThat(withTranscript["transcriptKeyName"].asText()).isEqualTo("transcript.vox")
    assertThat(withTranscript["transcriptKeyOwned"].asBoolean()).isTrue()
    assertThat(withTranscript["transcriptSourceText"].asText()).isEqualTo("The apple is red.")

    val generated = callToolAndGetJson(client, "generate_asset_transcript", assetArgs(assetId))
    assertThat(generated["transcriptSourceText"].asText()).isEqualTo("mocked transcript")

    val unlinked = callToolAndGetJson(client, "delete_asset_transcript", assetArgs(assetId))
    assertThat(unlinked["transcriptKeyId"].isNull).isTrue()
  }

  @Test
  fun `localized transcription uses the language file`() {
    val deId = createGermanLanguage()
    val assetId = createAsset(client, "vox-de")["id"].asLong()
    uploadTranslation(assetId, deId)

    val json =
      callToolAndGetJson(
        client,
        "generate_asset_transcript",
        assetArgs(assetId) + mapOf("languageId" to deId),
      )
    val de = json["translations"].first { it["languageId"].asLong() == deId }
    assertThat(de["transcriptText"].asText()).isEqualTo("mocked transcript")
  }

  @Test
  fun `translation and version pipeline works end to end`() {
    val deId = createGermanLanguage()
    val assetId = createAsset(client, "pipeline")["id"].asLong()
    // the tts tool reads the transcript's German translation, so seed one
    callTool(
      client,
      "set_asset_transcript",
      assetArgs(assetId) + mapOf("text" to "Hallo Welt", "languageTag" to "de"),
    )

    val afterUpload = uploadTranslation(assetId, deId)
    var de = afterUpload["translations"].first { it["languageId"].asLong() == deId }
    assertThat(de["status"].asText()).isEqualTo("CURRENT")

    val tts =
      callToolAndGetJson(
        client,
        "run_asset_tool",
        assetArgs(assetId) +
          mapOf(
            "languageId" to deId,
            "tool" to "tts",
            "params" to mapOf("voiceId" to "voice-1"),
          ),
      )
    assertThat(tts["tool"].asText()).isEqualTo("tts")
    val ttsVersionId = tts["id"].asLong()

    val voiceChanged =
      callToolAndGetJson(
        client,
        "run_asset_tool",
        assetArgs(assetId) +
          mapOf(
            "languageId" to deId,
            "tool" to "voice-changer",
            "params" to mapOf("voiceId" to "voice-1", "removeBackgroundNoise" to true),
          ),
      )
    assertThat(voiceChanged["tool"].asText()).isEqualTo("voice-changer")

    val versions =
      callToolAndGetJson(
        client,
        "list_asset_versions",
        assetArgs(assetId) + mapOf("languageId" to deId),
      )
    assertThat(versions["versions"].size()).isEqualTo(2)

    val chosen =
      callToolAndGetJson(
        client,
        "set_asset_chosen_version",
        assetArgs(assetId) + mapOf("languageId" to deId, "versionId" to ttsVersionId),
      )
    de = chosen["translations"].first { it["languageId"].asLong() == deId }
    assertThat(de["chosenVersionId"].asLong()).isEqualTo(ttsVersionId)

    val reviewed =
      callToolAndGetJson(
        client,
        "set_asset_translation_reviewed",
        assetArgs(assetId) + mapOf("languageId" to deId, "reviewed" to true),
      )
    de = reviewed["translations"].first { it["languageId"].asLong() == deId }
    assertThat(de["reviewed"].asBoolean()).isTrue()

    val manual =
      callToolAndGetJson(
        client,
        "upload_asset_version",
        assetArgs(assetId) + mapOf("languageId" to deId) + uploadArgs("manual.mp3"),
      )
    assertThat(manual["tool"].asText()).isEqualTo("upload")

    val deletedVersion =
      callToolAndGetJson(
        client,
        "delete_asset_version",
        assetArgs(assetId) + mapOf("languageId" to deId, "versionId" to manual["id"].asLong()),
      )
    assertThat(deletedVersion["deleted"].asBoolean()).isTrue()

    val deletedTranslation =
      callToolAndGetJson(
        client,
        "delete_asset_translation",
        assetArgs(assetId) + mapOf("languageId" to deId),
      )
    assertThat(deletedTranslation["deleted"].asBoolean()).isTrue()
  }

  @Test
  fun `download urls are issued and actually download`() {
    val deId = createGermanLanguage()
    val assetId = createAsset(client, "downloadable")["id"].asLong()
    uploadTranslation(assetId, deId)

    val source =
      callToolAndGetJson(client, "get_asset_download_url", assetArgs(assetId))
    val sourceUrl = source["url"].asText()
    assertThat(sourceUrl).contains("/v2/binary-assets/download?token=")
    // the ticket URL is public — fetching it must return the uploaded bytes
    assertThat(URI(sourceUrl).toURL().readBytes()).isEqualTo(mp3Bytes)

    val translation =
      callToolAndGetJson(
        client,
        "get_asset_download_url",
        assetArgs(assetId) + mapOf("languageId" to deId),
      )
    assertThat(translation["url"].asText()).contains("/v2/binary-assets/download?token=")

    val version =
      callToolAndGetJson(
        client,
        "run_asset_tool",
        assetArgs(assetId) +
          mapOf(
            "languageId" to deId,
            "tool" to "voice-changer",
            "params" to mapOf("voiceId" to "voice-1"),
          ),
      )
    val versionDownload =
      callToolAndGetJson(
        client,
        "get_asset_download_url",
        assetArgs(assetId) + mapOf("languageId" to deId, "versionId" to version["id"].asLong()),
      )
    assertThat(versionDownload["url"].asText()).contains("/v2/binary-assets/download?token=")
  }

  @Test
  fun `voice defaults are managed over MCP`() {
    val deId = createGermanLanguage()

    val afterDefault =
      callToolAndGetJson(
        client,
        "set_asset_voice",
        mapOf("projectId" to data.projectId, "voiceId" to "voice-project"),
      )
    assertThat(afterDefault["voices"].size()).isEqualTo(1)
    assertThat(afterDefault["voices"][0]["voiceId"].asText()).isEqualTo("voice-project")
    assertThat(afterDefault["voices"][0]["languageId"].isNull).isTrue()

    val afterLanguage =
      callToolAndGetJson(
        client,
        "set_asset_voice",
        mapOf("projectId" to data.projectId, "languageId" to deId, "voiceId" to "voice-de"),
      )
    assertThat(afterLanguage["voices"].size()).isEqualTo(2)

    val listed = callToolAndGetJson(client, "list_asset_voices", mapOf("projectId" to data.projectId))
    assertThat(listed["voices"].size()).isEqualTo(2)

    val cleared =
      callToolAndGetJson(
        client,
        "set_asset_voice",
        mapOf("projectId" to data.projectId, "languageId" to deId),
      )
    assertThat(cleared["voices"].size()).isEqualTo(1)
  }

  @Test
  fun `creates a translation-only asset when both upload args are omitted`() {
    val created =
      callToolAndGetJson(
        client,
        "create_asset",
        mapOf("projectId" to data.projectId, "name" to "combo-only"),
      )

    assertThat(created["name"].asText()).isEqualTo("combo-only")
    assertThat(created["originalFilename"].isNull).isTrue()
    assertThat(created["contentType"].isNull).isTrue()
    assertThat(created["sha256"].isNull).isTrue()
    assertThat(created["byteSize"].asLong()).isEqualTo(0)
    assertThat(created["transcriptionAvailable"].asBoolean()).isFalse()

    // it must also be listable and gettable — the row assembler used to blow up on a null contentType
    val fetched =
      callToolAndGetJson(client, "get_asset", assetArgs(created["id"].asLong()))
    assertThat(fetched["originalFilename"].isNull).isTrue()

    val listed = callToolAndGetJson(client, "list_assets", mapOf("projectId" to data.projectId))
    assertThat(listed.toString()).contains("combo-only")
  }

  @Test
  fun `a half-specified upload is still rejected rather than read as no source`() {
    assertThrows<Exception> {
      callTool(
        client,
        "create_asset",
        mapOf(
          "projectId" to data.projectId,
          "name" to "half",
          "fileName" to "half.mp3",
        ),
      )
    }
  }

  @Test
  fun `source download url is refused for a translation-only asset`() {
    val created =
      callToolAndGetJson(
        client,
        "create_asset",
        mapOf("projectId" to data.projectId, "name" to "no-source-download"),
      )

    assertThrows<Exception> {
      callTool(client, "get_asset_download_url", assetArgs(created["id"].asLong()))
    }
  }

  @Test
  fun `invalid base64 upload is rejected`() {
    assertThrows<Exception> {
      callTool(
        client,
        "create_asset",
        mapOf(
          "projectId" to data.projectId,
          "name" to "bad",
          "fileName" to "bad.mp3",
          "fileContentBase64" to "!!!not-base64!!!",
        ),
      )
    }
  }

  @Test
  fun `PAK without keys create scope is denied on writes`() {
    val viewOnlyData = createTestDataWithPak(setOf(Scope.KEYS_VIEW))
    val viewOnly = createMcpClientWithPak(viewOnlyData.apiKey.encodedKey!!)
    // projectId auto-resolves from the PAK; KEYS_VIEW suffices for listing
    assertToolSucceeds(viewOnly, "list_assets")
    assertToolFails(
      viewOnly,
      "create_asset",
      mapOf("name" to "denied", "fileName" to "d.mp3", "fileContentBase64" to mp3Base64),
    )
  }

  @Test
  fun `PAK with full scopes creates assets`() {
    val fullData = createTestDataWithPak()
    val full = createMcpClientWithPak(fullData.apiKey.encodedKey!!)
    // no projectId — it auto-resolves from the PAK
    val created = callToolAndGetJson(full, "create_asset", mapOf("name" to "pak-asset") + uploadArgs("pak-asset.mp3"))
    assertThat(created["id"].asLong()).isGreaterThan(0)
  }

  @Test
  fun `unauthenticated calls are denied`() {
    val noAuth = createMcpClientWithoutAuth()
    assertThrows<Exception> {
      callTool(noAuth, "list_assets")
    }
  }

  private fun assetArgs(assetId: Long) = mapOf("projectId" to data.projectId, "assetId" to assetId)

  private fun uploadArgs(fileName: String) =
    mapOf(
      "fileName" to fileName,
      "contentType" to "audio/mpeg",
      "fileContentBase64" to mp3Base64,
    )

  private fun createAsset(
    targetClient: McpSyncClient,
    name: String,
  ): JsonNode =
    callToolAndGetJson(
      targetClient,
      "create_asset",
      mapOf("projectId" to data.projectId, "name" to name) + uploadArgs("$name.mp3"),
    )

  private fun uploadTranslation(
    assetId: Long,
    languageId: Long,
  ): JsonNode =
    callToolAndGetJson(
      client,
      "upload_asset_translation",
      assetArgs(assetId) +
        mapOf(
          "languageId" to languageId,
          "translatedAgainstSourceRevision" to 1,
        ) + uploadArgs("de.mp3"),
    )

  private fun createGermanLanguage(): Long =
    languageService
      .createLanguage(
        LanguageRequest(name = "German", originalName = "Deutsch", tag = "de"),
        data.testData.projectBuilder.self,
      ).id
}
