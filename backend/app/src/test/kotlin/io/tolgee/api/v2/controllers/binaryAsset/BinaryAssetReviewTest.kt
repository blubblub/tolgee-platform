package io.tolgee.api.v2.controllers.binaryAsset

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.tolgee.ProjectAuthControllerTest
import io.tolgee.development.testDataBuilder.data.BinaryAssetLanguageScopeTestData
import io.tolgee.fixtures.AuthorizedRequestFactory
import io.tolgee.fixtures.andIsCreated
import io.tolgee.fixtures.andIsOk
import io.tolgee.testing.annotations.ProjectJWTAuthTestMethod
import io.tolgee.testing.assertions.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers

@SpringBootTest(
  properties = ["tolgee.internal.use-in-memory-file-storage=false"],
)
class BinaryAssetReviewTest : ProjectAuthControllerTest("/v2/projects/") {
  lateinit var testData: BinaryAssetLanguageScopeTestData

  @BeforeEach
  fun setup() {
    testData = BinaryAssetLanguageScopeTestData()
    testDataService.saveTestData(testData.root)
    projectSupplier = { testData.project }
  }

  @Test
  @ProjectJWTAuthTestMethod
  fun `confirms a language and drops the confirmation when a new file is uploaded`() {
    userAccount = testData.user
    val assetId = createAsset("vox-review")
    val german = testData.germanLanguage.id
    uploadTranslation(assetId, german)

    assertThat(reviewedFlag(assetId, german)).isFalse()

    setReviewed(assetId, german, true)
    assertThat(reviewedFlag(assetId, german)).isTrue()

    // a new file is a different thing to confirm
    uploadTranslation(assetId, german)
    assertThat(reviewedFlag(assetId, german)).isFalse()
  }

  @Test
  @ProjectJWTAuthTestMethod
  fun `replacing the source drops confirmations for every language`() {
    userAccount = testData.user
    val assetId = createAsset("vox-review-source")
    val german = testData.germanLanguage.id
    val slovenian = testData.slovenianLanguage.id
    uploadTranslation(assetId, german)
    uploadTranslation(assetId, slovenian)
    setReviewed(assetId, german, true)
    setReviewed(assetId, slovenian, true)

    replaceSource(assetId)

    assertThat(reviewedFlag(assetId, german)).isFalse()
    assertThat(reviewedFlag(assetId, slovenian)).isFalse()
  }

  @Test
  @ProjectJWTAuthTestMethod
  fun `un-confirming works and the flag reaches the list endpoint`() {
    userAccount = testData.user
    val assetId = createAsset("vox-review-list")
    val german = testData.germanLanguage.id
    uploadTranslation(assetId, german)
    setReviewed(assetId, german, true)

    val list =
      jacksonObjectMapper().readTree(
        performProjectAuthGet("binary-assets").andIsOk.andReturn().response.contentAsString,
      )
    val row =
      list
        .get("_embedded")
        .get("binaryAssets")
        .first { it.get("id").asLong() == assetId }
        .get("translations")
        .first { it.get("languageId").asLong() == german }
    assertThat(row.get("reviewed").asBoolean()).isTrue()

    setReviewed(assetId, german, false)
    assertThat(reviewedFlag(assetId, german)).isFalse()
  }

  private fun setReviewed(
    assetId: Long,
    languageId: Long,
    reviewed: Boolean,
  ) {
    performProjectAuthPut(
      "binary-assets/$assetId/translations/$languageId/reviewed",
      mapOf("reviewed" to reviewed),
    ).andIsOk
  }

  private fun reviewedFlag(
    assetId: Long,
    languageId: Long,
  ): Boolean {
    val detail =
      jacksonObjectMapper().readTree(
        performProjectAuthGet("binary-assets/$assetId").andIsOk.andReturn().response.contentAsString,
      )
    return detail
      .get("translations")
      .first { it.get("languageId").asLong() == languageId }
      .get("reviewed")
      .asBoolean()
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

  private fun replaceSource(assetId: Long) {
    val url = "${projectUrlPrefix}${testData.project.id}/binary-assets/$assetId/source"
    val builder =
      MockMvcRequestBuilders
        .multipart(url)
        .file(MockMultipartFile("file", "new.mp3", "audio/mpeg", byteArrayOf(0x49, 0x44, 0x33, 0x09)))
    builder.with(
      RequestPostProcessor { req ->
        req.method = "PUT"
        req
      },
    )
    mvc
      .perform(AuthorizedRequestFactory.addToken(builder))
      .andExpect(MockMvcResultMatchers.status().isOk)
  }

  private fun uploadTranslation(
    assetId: Long,
    languageId: Long,
  ) {
    val url = "${projectUrlPrefix}${testData.project.id}/binary-assets/$assetId/translations/$languageId"
    val builder =
      MockMvcRequestBuilders
        .multipart(url)
        .file(MockMultipartFile("file", "loc.mp3", "audio/mpeg", byteArrayOf(0x49, 0x44, 0x33, 0x05)))
        .file(
          MockMultipartFile(
            "translatedAgainstSourceRevision",
            null,
            MediaType.TEXT_PLAIN_VALUE,
            "1".toByteArray(),
          ),
        )
    builder.with(
      RequestPostProcessor { req ->
        req.method = "PUT"
        req
      },
    )
    mvc
      .perform(AuthorizedRequestFactory.addToken(builder))
      .andExpect(MockMvcResultMatchers.status().isOk)
  }
}
