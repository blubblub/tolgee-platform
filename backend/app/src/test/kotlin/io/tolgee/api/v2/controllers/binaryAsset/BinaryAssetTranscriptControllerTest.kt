package io.tolgee.api.v2.controllers.binaryAsset

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.tolgee.ProjectAuthControllerTest
import io.tolgee.dtos.request.key.CreateKeyDto
import io.tolgee.fixtures.andAssertThatJson
import io.tolgee.fixtures.andIsBadRequest
import io.tolgee.fixtures.andIsCreated
import io.tolgee.fixtures.andIsNotFound
import io.tolgee.fixtures.andIsOk
import io.tolgee.repository.binaryAsset.BinaryAssetRepository
import io.tolgee.service.key.KeyService
import io.tolgee.service.binaryAsset.BinaryAssetTranscriptService
import io.tolgee.testing.annotations.ProjectJWTAuthTestMethod
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.transaction.annotation.Transactional

@SpringBootTest(
  properties = ["tolgee.internal.use-in-memory-file-storage=false"],
)
class BinaryAssetTranscriptControllerTest : ProjectAuthControllerTest("/v2/projects/") {
  @Autowired
  lateinit var binaryAssetRepository: BinaryAssetRepository

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

  @Test
  @ProjectJWTAuthTestMethod
  @Transactional
  fun `creates an owned transcript key seeded with source text`() {
    val assetId = createAsset("vox-apple")

    performProjectAuthPost("binary-assets/$assetId/transcript", mapOf("text" to "The apple is red."))
      .andIsOk
      .andAssertThatJson {
        node("transcriptKeyId").isNumber
        node("transcriptKeyName").isEqualTo("transcript.vox-apple")
        node("transcriptKeyOwned").isEqualTo(true)
        node("transcriptKeyDeleted").isEqualTo(false)
        // source-language text is not in `translations`, which only covers targets
        node("transcriptSourceText").isEqualTo("The apple is red.")
      }

    val asset = binaryAssetRepository.findByProjectIdAndId(project.id, assetId)!!
    val key = keyService.get(asset.transcriptKey!!.id)
    assertThat(key.name).isEqualTo("transcript.vox-apple")
    assertThat(key.keyMeta?.tags?.map { it.name })
      .contains(BinaryAssetTranscriptService.TRANSCRIPT_TAG)
    assertThat(key.translations.first { it.language.tag == "en" }.text)
      .isEqualTo("The apple is red.")
  }

  @Test
  @ProjectJWTAuthTestMethod
  fun `creates an empty transcript when no text is given`() {
    val assetId = createAsset("vox-empty")

    performProjectAuthPost("binary-assets/$assetId/transcript", mapOf<String, Any>())
      .andIsOk
      .andAssertThatJson {
        node("transcriptKeyName").isEqualTo("transcript.vox-empty")
        node("transcriptKeyOwned").isEqualTo(true)
      }
  }

  @Test
  @ProjectJWTAuthTestMethod
  fun `links an existing key without owning it`() {
    val assetId = createAsset("vox-linked")
    val existing = keyService.create(project, CreateKeyDto(name = "home.greeting"))

    performProjectAuthPost("binary-assets/$assetId/transcript", mapOf("keyId" to existing.id))
      .andIsOk
      .andAssertThatJson {
        node("transcriptKeyId").isEqualTo(existing.id)
        node("transcriptKeyName").isEqualTo("home.greeting")
        node("transcriptKeyOwned").isEqualTo(false)
      }

    // unlinking must leave a key we do not own alone
    performProjectAuthDelete("binary-assets/$assetId/transcript", null)
      .andIsOk
      .andAssertThatJson {
        node("transcriptKeyId").isNull()
        node("transcriptKeyOwned").isEqualTo(false)
      }
    assertThat(keyService.find(existing.id)).isNotNull
  }

  @Test
  @ProjectJWTAuthTestMethod
  fun `unlinking an owned transcript deletes its key`() {
    val assetId = createAsset("vox-owned")
    val response =
      performProjectAuthPost("binary-assets/$assetId/transcript", mapOf("text" to "Bye."))
        .andIsOk.andReturn()
    val keyId =
      jacksonObjectMapper().readTree(response.response.contentAsString).get("transcriptKeyId").asLong()

    performProjectAuthDelete("binary-assets/$assetId/transcript", null).andIsOk

    assertThat(keyService.find(keyId)).isNull()
  }

  @Test
  @ProjectJWTAuthTestMethod
  fun `rejects a second transcript and a request with both text and keyId`() {
    val assetId = createAsset("vox-dupe")
    performProjectAuthPost("binary-assets/$assetId/transcript", mapOf("text" to "One.")).andIsOk
    performProjectAuthPost("binary-assets/$assetId/transcript", mapOf("text" to "Two.")).andIsBadRequest

    val other = createAsset("vox-both")
    val key = keyService.create(project, CreateKeyDto(name = "some.key"))
    performProjectAuthPost(
      "binary-assets/$other/transcript",
      mapOf("text" to "x", "keyId" to key.id),
    ).andIsBadRequest
  }

  @Test
  @ProjectJWTAuthTestMethod
  fun `unlinking an asset without a transcript is not found`() {
    val assetId = createAsset("vox-none")
    performProjectAuthDelete("binary-assets/$assetId/transcript", null).andIsNotFound
  }

  /**
   * A new FK to `key` blocks key deletion unless every hard-delete path clears it first. These two
   * are the regression guards for that.
   */
  @Test
  @ProjectJWTAuthTestMethod
  @Transactional
  fun `hard deleting a linked key clears the reference instead of failing`() {
    val assetId = createAsset("vox-hard-single")
    val key = keyService.create(project, CreateKeyDto(name = "to.be.deleted"))
    performProjectAuthPost("binary-assets/$assetId/transcript", mapOf("keyId" to key.id)).andIsOk

    keyService.hardDelete(key.id)

    val asset = binaryAssetRepository.findByProjectIdAndId(project.id, assetId)!!
    assertThat(asset.transcriptKey).isNull()
    assertThat(asset.transcriptKeyOwned).isFalse()
  }

  @Test
  @ProjectJWTAuthTestMethod
  @Transactional
  fun `hard deleting multiple linked keys clears the references`() {
    val first = createAsset("vox-hard-a")
    val second = createAsset("vox-hard-b")
    val keyA = keyService.create(project, CreateKeyDto(name = "bulk.a"))
    val keyB = keyService.create(project, CreateKeyDto(name = "bulk.b"))
    performProjectAuthPost("binary-assets/$first/transcript", mapOf("keyId" to keyA.id)).andIsOk
    performProjectAuthPost("binary-assets/$second/transcript", mapOf("keyId" to keyB.id)).andIsOk

    keyService.hardDeleteMultiple(listOf(keyA.id, keyB.id))

    assertThat(binaryAssetRepository.findByProjectIdAndId(project.id, first)!!.transcriptKey).isNull()
    assertThat(binaryAssetRepository.findByProjectIdAndId(project.id, second)!!.transcriptKey).isNull()
  }

  @Test
  @ProjectJWTAuthTestMethod
  fun `a soft deleted key stays referenced and is reported as deleted`() {
    val assetId = createAsset("vox-soft")
    val key = keyService.create(project, CreateKeyDto(name = "soft.deleted"))
    performProjectAuthPost("binary-assets/$assetId/transcript", mapOf("keyId" to key.id)).andIsOk

    keyService.softDeleteMultiple(listOf(key.id))

    performProjectAuthGet("binary-assets/$assetId").andIsOk.andAssertThatJson {
      node("transcriptKeyId").isEqualTo(key.id)
      node("transcriptKeyDeleted").isEqualTo(true)
    }
  }

  @Test
  @ProjectJWTAuthTestMethod
  fun `the list endpoint carries the transcript so the assets page can show it`() {
    val withTranscript = createAsset("vox-listed")
    createAsset("vox-unlisted")
    performProjectAuthPost(
      "binary-assets/$withTranscript/transcript",
      mapOf("text" to "Listed transcript."),
    ).andIsOk

    val page =
      jacksonObjectMapper()
        .readTree(performProjectAuthGet("binary-assets?size=100").andIsOk.andReturn().response.contentAsString)
    val rows = page.get("_embedded").get("binaryAssets")
    val listed = rows.first { it.get("name").asText() == "vox-listed" }
    val unlisted = rows.first { it.get("name").asText() == "vox-unlisted" }

    assertThat(listed.get("transcriptSourceText").asText()).isEqualTo("Listed transcript.")
    assertThat(listed.get("transcriptKeyName").asText()).isEqualTo("transcript.vox-listed")
    assertThat(unlisted.get("transcriptKeyId").isNull).isTrue()
    assertThat(unlisted.get("transcriptSourceText").isNull).isTrue()
  }

  @Test
  @ProjectJWTAuthTestMethod
  fun `exposes transcript text per target language`() {
    val assetId = createAsset("vox-per-language")
    performProjectAuthPost(
      "binary-assets/$assetId/transcript",
      mapOf("text" to "Source text."),
    ).andIsOk

    // over HTTP so it is committed before the read below
    performProjectAuthPut(
      "translations",
      mapOf(
        "key" to "transcript.vox-per-language",
        "translations" to mapOf("de" to "Quelltext."),
      ),
    ).andIsOk

    val detail =
      jacksonObjectMapper()
        .readTree(performProjectAuthGet("binary-assets/$assetId").andIsOk.andReturn().response.contentAsString)
    val german = detail.get("translations").first { it.get("languageTag").asText() == "de" }
    assertThat(german.get("transcriptText").asText()).isEqualTo("Quelltext.")
    assertThat(german.get("transcriptState").asText()).isEqualTo("TRANSLATED")
  }
}
