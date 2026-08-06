package io.tolgee.api.v2.controllers.binaryAsset

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.tolgee.ProjectAuthControllerTest
import io.tolgee.development.testDataBuilder.data.BinaryAssetLanguageScopeTestData
import io.tolgee.fixtures.AuthorizedRequestFactory
import io.tolgee.fixtures.andIsCreated
import io.tolgee.service.project.ProjectStatsService
import io.tolgee.testing.annotations.ProjectJWTAuthTestMethod
import io.tolgee.testing.assertions.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers

@SpringBootTest(
  properties = ["tolgee.internal.use-in-memory-file-storage=false"],
)
class BinaryAssetProjectStatsTest : ProjectAuthControllerTest("/v2/projects/") {
  @Autowired
  lateinit var projectStatsService: ProjectStatsService

  lateinit var testData: BinaryAssetLanguageScopeTestData

  @BeforeEach
  fun setup() {
    testData = BinaryAssetLanguageScopeTestData()
    testDataService.saveTestData(testData.root)
    projectSupplier = { testData.project }
  }

  @Test
  @ProjectJWTAuthTestMethod
  fun `counts assets missing a file in any target language`() {
    userAccount = testData.user

    // the project has two targets (de, sl); en is the source
    val halfDone = createAsset("vox-half")
    uploadTranslation(halfDone, testData.germanLanguage.id)

    val done = createAsset("vox-done")
    uploadTranslation(done, testData.germanLanguage.id)
    uploadTranslation(done, testData.slovenianLanguage.id)

    val untouched = createAsset("vox-untouched")

    val totals = projectStatsService.getProjectsAssetTotals(listOf(testData.project.id))
    val stats = totals[testData.project.id]!!

    assertThat(stats.assetCount).isEqualTo(3)
    // only `done` has every target language covered
    assertThat(stats.untranslatedAssetCount).isEqualTo(2)
    assertThat(untouched).isPositive
  }

  @Test
  @ProjectJWTAuthTestMethod
  fun `reports nothing for a project without assets`() {
    userAccount = testData.user
    assertThat(projectStatsService.getProjectsAssetTotals(listOf(testData.project.id))).isEmpty()
    assertThat(projectStatsService.getProjectsAssetTotals(emptyList())).isEmpty()
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
