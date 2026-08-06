package io.tolgee.api.v2.controllers.binaryAsset

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.tolgee.ProjectAuthControllerTest
import io.tolgee.fixtures.andAssertThatJson
import io.tolgee.fixtures.andIsForbidden
import io.tolgee.fixtures.andIsOk
import io.tolgee.testing.annotations.ProjectJWTAuthTestMethod
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
class BinaryAssetVoiceControllerTest : ProjectAuthControllerTest("/v2/projects/") {
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

  private fun setVoice(
    languageId: Long?,
    voiceId: String?,
  ) = performProjectAuthPut(
    "binary-asset-voices",
    mutableMapOf<String, Any?>("languageId" to languageId, "voiceId" to voiceId),
  )

  @Test
  @ProjectJWTAuthTestMethod
  fun `sets the project default and a language override`() {
    setVoice(null, "voice-project").andIsOk
    setVoice(languageId("de"), "voice-de").andIsOk

    performProjectAuthGet("binary-asset-voices").andIsOk.andAssertThatJson {
      // project default sorts first
      node("[0].languageId").isEqualTo(null)
      node("[0].voiceId").isEqualTo("voice-project")
      node("[1].languageId").isEqualTo(languageId("de"))
      node("[1].languageTag").isEqualTo("de")
      node("[1].voiceId").isEqualTo("voice-de")
    }
  }

  @Test
  @ProjectJWTAuthTestMethod
  fun `overwrites instead of stacking rows`() {
    setVoice(null, "first").andIsOk
    setVoice(null, "second").andIsOk

    val tree =
      jacksonObjectMapper()
        .readTree(performProjectAuthGet("binary-asset-voices").andIsOk.andReturn().response.contentAsString)
    assertThat(tree.size()).isEqualTo(1)
    assertThat(tree.get(0).get("voiceId").asText()).isEqualTo("second")
  }

  @Test
  @ProjectJWTAuthTestMethod
  fun `clears an entry with a null or blank voice`() {
    val de = languageId("de")
    setVoice(null, "voice-project").andIsOk
    setVoice(de, "voice-de").andIsOk

    setVoice(de, null).andIsOk.andAssertThatJson {
      node("[0].voiceId").isEqualTo("voice-project")
    }
    setVoice(null, "   ").andIsOk

    val tree =
      jacksonObjectMapper()
        .readTree(performProjectAuthGet("binary-asset-voices").andIsOk.andReturn().response.contentAsString)
    assertThat(tree.size()).isEqualTo(0)
  }

  @Test
  @ProjectJWTAuthTestMethod
  fun `denies setting a voice without languages edit`() {
    val restrictedUser = dbPopulator.createUserIfNotExists("voice-restricted", "password")
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

    // reading is enough for the run dialog, writing is not
    performProjectAuthGet("binary-asset-voices").andIsOk
    setVoice(null, "nope").andIsForbidden
  }
}
