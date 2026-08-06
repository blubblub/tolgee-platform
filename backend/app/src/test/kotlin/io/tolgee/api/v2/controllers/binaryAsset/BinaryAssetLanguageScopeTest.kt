package io.tolgee.api.v2.controllers.binaryAsset

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.tolgee.ProjectAuthControllerTest
import io.tolgee.development.testDataBuilder.data.BinaryAssetLanguageScopeTestData
import io.tolgee.fixtures.andAssertThatJson
import io.tolgee.fixtures.andIsCreated
import io.tolgee.fixtures.andIsOk
import io.tolgee.testing.annotations.ProjectJWTAuthTestMethod
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile

@SpringBootTest(
  properties = ["tolgee.internal.use-in-memory-file-storage=false"],
)
class BinaryAssetLanguageScopeTest : ProjectAuthControllerTest("/v2/projects/") {
  lateinit var testData: BinaryAssetLanguageScopeTestData

  @BeforeEach
  fun setup() {
    testData = BinaryAssetLanguageScopeTestData()
    testDataService.saveTestData(testData.root)
    projectSupplier = { testData.project }
  }

  @Test
  @ProjectJWTAuthTestMethod
  fun `only lists localized files for languages the user may view`() {
    userAccount = testData.user
    val assetId = createAsset("vox-scoped")

    // the manager is unrestricted, so both targets are there
    performProjectAuthGet("binary-assets").andIsOk.andAssertThatJson {
      node("_embedded.binaryAssets[0].translations").isArray.hasSize(2)
      node("_embedded.binaryAssets[0].targetLanguageCount").isEqualTo(2)
    }

    userAccount = testData.slovenianTranslator.self

    performProjectAuthGet("binary-assets").andIsOk.andAssertThatJson {
      node("_embedded.binaryAssets[0].translations").isArray.hasSize(1)
      node("_embedded.binaryAssets[0].translations[0].languageTag").isEqualTo("sl")
      node("_embedded.binaryAssets[0].targetLanguageCount").isEqualTo(1)
    }

    performProjectAuthGet("binary-assets/$assetId").andIsOk.andAssertThatJson {
      node("translations").isArray.hasSize(1)
      node("translations[0].languageTag").isEqualTo("sl")
    }
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
}
