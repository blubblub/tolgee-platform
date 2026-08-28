package io.tolgee.api.v2.controllers

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.tolgee.ProjectAuthControllerTest
import io.tolgee.dtos.request.ScreenshotInfoDto
import io.tolgee.dtos.request.key.CreateKeyDto
import io.tolgee.fixtures.AuthorizedRequestFactory
import io.tolgee.fixtures.andAssertThatJson
import io.tolgee.fixtures.andIsBadRequest
import io.tolgee.fixtures.andIsCreated
import io.tolgee.fixtures.andIsOk
import io.tolgee.fixtures.generateImage
import io.tolgee.repository.ScreenshotRepository
import io.tolgee.testing.annotations.ProjectJWTAuthTestMethod
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.request.RequestPostProcessor

/**
 * The screenshot bot's contract: one upload per screen, identified by location, idempotent, and
 * never able to create or delete anything but screenshots and their links.
 */
@SpringBootTest(
  properties = ["tolgee.internal.use-in-memory-file-storage=false"],
)
class ScreenshotByLocationControllerTest : ProjectAuthControllerTest("/v2/projects/") {
  @Autowired
  lateinit var screenshotRepository: ScreenshotRepository

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

  private fun upsert(
    info: Map<String, Any?>,
    width: Int = 200,
    height: Int = 100,
  ): ResultActions {
    val builder =
      MockMvcRequestBuilders
        .multipart("${projectUrlPrefix}${project.id}/screenshots/by-location")
        .file(
          MockMultipartFile(
            "image",
            "screen.png",
            "image/png",
            generateImage(width, height).inputStream.readAllBytes(),
          ),
        ).file(
          MockMultipartFile(
            "info",
            "info",
            MediaType.APPLICATION_JSON_VALUE,
            jacksonObjectMapper().writeValueAsBytes(info),
          ),
        )
    builder.with(
      RequestPostProcessor { req ->
        req.method = "PUT"
        req
      },
    )
    return mvc.perform(AuthorizedRequestFactory.addToken(builder))
  }

  private fun screenshotId(result: ResultActions): Long =
    jacksonObjectMapper()
      .readTree(result.andReturn().response.contentAsString)
      .get("screenshot")
      .get("id")
      .asLong()

  @Test
  @ProjectJWTAuthTestMethod
  fun `creates a screenshot for a new location and links what is on it`() {
    keyService.create(project, CreateKeyDto("title"))
    keyService.create(project, CreateKeyDto("cta"))
    createAsset("vox-intro")

    val result =
      upsert(
        mapOf(
          "location" to "onboarding/step-1",
          "keys" to
            listOf(
              mapOf(
                "name" to "title",
                "text" to "Welcome",
                "positions" to listOf(mapOf("x" to 10, "y" to 10, "width" to 80, "height" to 20)),
              ),
              mapOf("name" to "cta"),
              mapOf("name" to "does_not_exist"),
            ),
          "assets" to listOf(mapOf("name" to "vox-intro"), mapOf("name" to "no_such_asset")),
        ),
      ).andIsOk.andAssertThatJson {
        node("created").isEqualTo(true)
        node("replacedScreenshots").isEqualTo(0)
        node("linkedKeys").isArray.containsExactlyInAnyOrder("title", "cta")
        node("linkedAssets").isArray.containsExactly("vox-intro")
        node("unknownKeys").isArray.containsExactly("does_not_exist")
        node("unknownAssets").isArray.containsExactly("no_such_asset")
        node("screenshot.location").isEqualTo("onboarding/step-1")
        node("screenshot.keyReferences").isArray.hasSize(2)
        node("screenshot.assetReferences[0].assetName").isEqualTo("vox-intro")
      }

    // unknown names were reported, not created
    assertThat(keyService.find(project.id, "does_not_exist", null)).isNull()
    val titleRef =
      executeInNewTransaction {
        val title = keyService.get(project.id, "title", null)
        screenshotService.getAllKeyScreenshotReferences(title).single()
      }
    assertThat(titleRef.originalText).isEqualTo("Welcome")
    assertThat(titleRef.positions).hasSize(1)
    assertThat(screenshotId(result)).isPositive()
  }

  @Test
  @ProjectJWTAuthTestMethod
  fun `re-uploading a location replaces the image in place and resets the links`() {
    keyService.create(project, CreateKeyDto("a"))
    keyService.create(project, CreateKeyDto("b"))
    val assetA = createAsset("asset-a")
    createAsset("asset-b")

    val first =
      upsert(
        mapOf(
          "location" to "home",
          "keys" to listOf(mapOf("name" to "a"), mapOf("name" to "b")),
          "assets" to listOf(mapOf("name" to "asset-a")),
        ),
      ).andIsOk
    val id = screenshotId(first)
    val before = screenshotRepository.findById(id).get()
    val oldFile = before.filename

    val second =
      upsert(
        mapOf(
          "location" to "home",
          "keys" to listOf(mapOf("name" to "b")),
          "assets" to listOf(mapOf("name" to "asset-b")),
        ),
        width = 400,
        height = 300,
      ).andIsOk.andAssertThatJson {
        node("created").isEqualTo(false)
        node("screenshot.width").isEqualTo(400)
        node("screenshot.height").isEqualTo(300)
        node("screenshot.keyReferences").isArray.hasSize(1)
        node("screenshot.keyReferences[0].keyName").isEqualTo("b")
        node("screenshot.assetReferences").isArray.hasSize(1)
        node("screenshot.assetReferences[0].assetName").isEqualTo("asset-b")
      }

    assertThat(screenshotId(second)).isEqualTo(id)
    assertThat(screenshotRepository.count()).isEqualTo(1)
    assertThat(fileStorage.fileExists("screenshots/$oldFile")).isTrue()
    // a dropped link is only a link: the key and the asset are untouched
    assertThat(keyService.find(project.id, "a", null)).isNotNull
    performProjectAuthGet("binary-assets/$assetA").andIsOk.andAssertThatJson {
      node("screenshotCount").isEqualTo(0)
    }
  }

  @Test
  @ProjectJWTAuthTestMethod
  fun `folds legacy per-key screenshots at the same location into one`() {
    // keys and screenshots in one transaction: store() touches the key's lazy reference list
    executeInNewTransaction {
      val a = keyService.create(project, CreateKeyDto("legacy_a"))
      val b = keyService.create(project, CreateKeyDto("legacy_b"))
      screenshotService.store(generateImage(50, 50), a, ScreenshotInfoDto(location = "settings"))
      screenshotService.store(generateImage(50, 50), b, ScreenshotInfoDto(location = "settings"))
    }
    assertThat(screenshotRepository.count()).isEqualTo(2)

    upsert(
      mapOf(
        "location" to "settings",
        "keys" to listOf(mapOf("name" to "legacy_a"), mapOf("name" to "legacy_b")),
      ),
    ).andIsOk.andAssertThatJson {
      node("created").isEqualTo(false)
      node("replacedScreenshots").isEqualTo(1)
      node("screenshot.keyReferences").isArray.hasSize(2)
    }

    assertThat(screenshotRepository.count()).isEqualTo(1)
  }

  @Test
  @ProjectJWTAuthTestMethod
  fun `rejects a blank location and an implausible number of references`() {
    upsert(mapOf("location" to "   ")).andIsBadRequest

    upsert(
      mapOf(
        "location" to "huge",
        "keys" to (1..501).map { mapOf("name" to "k$it") },
      ),
    ).andIsBadRequest.andAssertThatJson {
      node("code").isEqualTo("screenshot_too_many_references")
    }
    assertThat(screenshotRepository.count()).isEqualTo(0)
  }
}
