package io.tolgee.api.v2.controllers.binaryAsset

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.tolgee.ProjectAuthControllerTest
import io.tolgee.development.testDataBuilder.data.BaseTestData
import io.tolgee.dtos.request.LanguageRequest
import io.tolgee.dtos.request.key.CreateKeyDto
import io.tolgee.dtos.request.project.CreateProjectRequest
import io.tolgee.fixtures.andAssertThatJson
import io.tolgee.fixtures.andIsCreated
import io.tolgee.fixtures.andIsForbidden
import io.tolgee.fixtures.andIsNoContent
import io.tolgee.fixtures.andIsOk
import io.tolgee.fixtures.generateImage
import io.tolgee.model.Screenshot
import io.tolgee.model.key.Key
import io.tolgee.repository.ScreenshotRepository
import io.tolgee.service.binaryAsset.BinaryAssetService
import io.tolgee.service.project.ProjectCreationService
import io.tolgee.testing.annotations.ProjectJWTAuthTestMethod
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile

/**
 * A screenshot is owned by whatever references it — keys, assets, or both. These pin the rules
 * that keep the two sides from stranding or stealing each other's screenshots.
 */
@SpringBootTest(
  properties = ["tolgee.internal.use-in-memory-file-storage=false"],
)
class BinaryAssetScreenshotControllerTest : ProjectAuthControllerTest("/v2/projects/") {
  @Autowired
  lateinit var screenshotRepository: ScreenshotRepository

  @Autowired
  lateinit var binaryAssetService: BinaryAssetService

  @Autowired
  lateinit var projectCreationService: ProjectCreationService

  lateinit var testData: BaseTestData

  private val image by lazy { generateImage(120, 80) }

  @BeforeEach
  fun setup() {
    testData = BaseTestData()
    testDataService.saveTestData(testData.root)
    projectSupplier = { testData.project }
    userAccount = testData.user
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

  private fun uploadAssetScreenshot(
    assetId: Long,
    location: String? = null,
  ): Long {
    val parts =
      mutableListOf(
        MockMultipartFile("screenshot", "screen.png", "image/png", image.inputStream.readAllBytes()),
      )
    if (location != null) {
      parts +=
        MockMultipartFile(
          "info",
          "info",
          MediaType.APPLICATION_JSON_VALUE,
          jacksonObjectMapper().writeValueAsBytes(mapOf("location" to location)),
        )
    }
    val result =
      performProjectAuthMultipart(url = "binary-assets/$assetId/screenshots", files = parts)
        .andIsCreated
        .andReturn()
    return jacksonObjectMapper().readTree(result.response.contentAsString).get("id").asLong()
  }

  /** Key and screenshot in one transaction — store() touches the key's lazy reference list. */
  private fun keyWithScreenshot(
    name: String,
    projectId: Long = project.id,
  ): Pair<Key, Screenshot> =
    executeInNewTransaction {
      val key = keyService.create(projectService.get(projectId), CreateKeyDto(name))
      key to screenshotService.store(image, key, null)
    }

  private fun exists(screenshotId: Long) = screenshotRepository.findById(screenshotId).isPresent

  /** A second project of the same organization, so cross-project checks are about the project, not access. */
  private fun createOtherProject(name: String): Long =
    projectCreationService
      .createProject(
        CreateProjectRequest(
          name = name,
          organizationId = testData.userAccountBuilder.defaultOrganizationBuilder.self.id,
          languages = listOf(LanguageRequest(name = "English", originalName = "English", tag = "en")),
        ),
      ).id

  @Test
  @ProjectJWTAuthTestMethod
  fun `uploads a screenshot for an asset and lists it on the asset`() {
    val assetId = createAsset("vox-onboarding")

    val screenshotId = uploadAssetScreenshot(assetId, "onboarding/step-3")

    performProjectAuthGet("binary-assets/$assetId/screenshots").andIsOk.andAssertThatJson {
      node("_embedded.screenshots").isArray.hasSize(1)
      node("_embedded.screenshots[0].id").isEqualTo(screenshotId)
      node("_embedded.screenshots[0].location").isEqualTo("onboarding/step-3")
      node("_embedded.screenshots[0].assetReferences[0].assetName").isEqualTo("vox-onboarding")
      node("_embedded.screenshots[0].keyReferences").isArray.isEmpty()
    }
    performProjectAuthGet("binary-assets/$assetId").andIsOk.andAssertThatJson {
      node("screenshotCount").isEqualTo(1)
      node("screenshots[0].id").isEqualTo(screenshotId)
      node("screenshots[0].thumbnailUrl").isString
    }
    performProjectAuthGet("binary-assets").andIsOk.andAssertThatJson {
      node("_embedded.binaryAssets[0].screenshotCount").isEqualTo(1)
      node("_embedded.binaryAssets[0].screenshots").isArray.hasSize(1)
    }
  }

  @Test
  @ProjectJWTAuthTestMethod
  fun `links a key's screenshot to an asset and unlinking keeps it while the key still has it`() {
    val (key, screenshot) = keyWithScreenshot("welcome_title")
    val assetId = createAsset("vox-welcome")

    performProjectAuthPost("binary-assets/$assetId/screenshots/link", mapOf("screenshotId" to screenshot.id))
      .andIsOk
      .andAssertThatJson {
        node("keyReferences[0].keyName").isEqualTo("welcome_title")
        node("assetReferences[0].assetName").isEqualTo("vox-welcome")
      }
    // linking twice is a no-op, not a duplicate row
    performProjectAuthPost("binary-assets/$assetId/screenshots/link", mapOf("screenshotId" to screenshot.id)).andIsOk
    performProjectAuthGet("binary-assets/$assetId/screenshots").andIsOk.andAssertThatJson {
      node("_embedded.screenshots").isArray.hasSize(1)
    }

    performProjectAuthDelete("binary-assets/$assetId/screenshots/${screenshot.id}", null).andIsNoContent

    assertThat(exists(screenshot.id)).isTrue()
    assertThat(executeInNewTransaction { screenshotService.findAll(key).map { it.id } }).containsExactly(screenshot.id)
    performProjectAuthGet("binary-assets/$assetId/screenshots").andIsOk.andAssertThatJson {
      node("_embedded.screenshots").isAbsent()
    }
  }

  @Test
  @ProjectJWTAuthTestMethod
  fun `unlinking the last reference deletes the screenshot and its files`() {
    val assetId = createAsset("vox-lonely")
    val screenshotId = uploadAssetScreenshot(assetId)
    val screenshot = screenshotRepository.findById(screenshotId).get()
    assertThat(fileStorage.fileExists("screenshots/${screenshot.filename}")).isTrue()
    assertThat(fileStorage.fileExists("screenshots/${screenshot.thumbnailFilename}")).isTrue()

    performProjectAuthDelete("binary-assets/$assetId/screenshots/$screenshotId", null).andIsNoContent

    assertThat(exists(screenshotId)).isFalse()
    assertThat(fileStorage.fileExists("screenshots/${screenshot.filename}")).isFalse()
    assertThat(fileStorage.fileExists("screenshots/${screenshot.thumbnailFilename}")).isFalse()
  }

  @Test
  @ProjectJWTAuthTestMethod
  fun `deleting a key keeps a screenshot an asset still uses`() {
    val (key, screenshot) = keyWithScreenshot("shared_screen")
    val assetId = createAsset("vox-shared")
    performProjectAuthPost("binary-assets/$assetId/screenshots/link", mapOf("screenshotId" to screenshot.id)).andIsOk

    performProjectAuthDelete("keys/${key.id}/screenshots/${screenshot.id}", null).andIsOk

    assertThat(exists(screenshot.id)).isTrue()
    performProjectAuthGet("binary-assets/$assetId/screenshots").andIsOk.andAssertThatJson {
      node("_embedded.screenshots").isArray.hasSize(1)
    }
  }

  @Test
  @ProjectJWTAuthTestMethod
  fun `the key endpoint cannot delete a screenshot that only an asset references`() {
    // before asset references existed, "no key references" meant "orphan" and the row was deleted
    val bystanderKey = keyService.create(project, CreateKeyDto("bystander"))
    val assetId = createAsset("vox-asset-only")
    val screenshotId = uploadAssetScreenshot(assetId)

    performProjectAuthDelete("keys/${bystanderKey.id}/screenshots/$screenshotId", null).andIsOk

    assertThat(exists(screenshotId)).isTrue()
  }

  @Test
  @ProjectJWTAuthTestMethod
  fun `deleting the asset removes its screenshots unless a key still uses them`() {
    val (_, keptScreenshot) = keyWithScreenshot("kept_by_key")
    val assetId = createAsset("vox-doomed")
    performProjectAuthPost(
      "binary-assets/$assetId/screenshots/link",
      mapOf("screenshotId" to keptScreenshot.id),
    ).andIsOk
    val ownScreenshotId = uploadAssetScreenshot(assetId)

    performProjectAuthDelete("binary-assets/$assetId", null).andIsNoContent

    assertThat(exists(ownScreenshotId)).isFalse()
    assertThat(exists(keptScreenshot.id)).isTrue()
  }

  @Test
  @ProjectJWTAuthTestMethod
  fun `another project's screenshot can neither be linked nor deleted through this project`() {
    val otherProject = executeInNewTransaction { createOtherProject("other") }
    val (foreignKey, foreignScreenshot) = keyWithScreenshot("foreign_key", otherProject)
    val assetId = createAsset("vox-local")
    val localKey = keyService.create(project, CreateKeyDto("local_key"))

    performProjectAuthPost("binary-assets/$assetId/screenshots/link", mapOf("screenshotId" to foreignScreenshot.id))
      .andIsForbidden
    performProjectAuthDelete("binary-assets/$assetId/screenshots/${foreignScreenshot.id}", null).andIsForbidden
    performProjectAuthDelete("keys/${localKey.id}/screenshots/${foreignScreenshot.id}", null).andIsForbidden

    assertThat(exists(foreignScreenshot.id)).isTrue()
    assertThat(executeInNewTransaction { screenshotService.findAll(foreignKey) }).hasSize(1)
  }

  @Test
  @ProjectJWTAuthTestMethod
  fun `an asset-only screenshot survives the key screenshot endpoint of a foreign project`() {
    val assetId = createAsset("vox-guarded")
    val screenshotId = uploadAssetScreenshot(assetId)
    val otherProject = executeInNewTransaction { createOtherProject("attacker") }
    val attackerKey =
      executeInNewTransaction { keyService.create(projectService.get(otherProject), CreateKeyDto("attacker_key")) }
    projectSupplier = { projectService.get(otherProject) }

    performProjectAuthDelete("keys/${attackerKey.id}/screenshots/$screenshotId", null).andIsForbidden

    assertThat(exists(screenshotId)).isTrue()
  }

  @Test
  @ProjectJWTAuthTestMethod
  fun `the list carries the first six screenshots and the full count`() {
    val assetId = createAsset("vox-busy")
    repeat(8) { uploadAssetScreenshot(assetId, "screen-$it") }

    performProjectAuthGet("binary-assets").andIsOk.andAssertThatJson {
      node("_embedded.binaryAssets[0].screenshots").isArray.hasSize(6)
      node("_embedded.binaryAssets[0].screenshotCount").isEqualTo(8)
    }
    performProjectAuthGet("binary-assets/$assetId").andIsOk.andAssertThatJson {
      node("screenshots").isArray.hasSize(8)
      node("screenshotCount").isEqualTo(8)
    }
  }
}
