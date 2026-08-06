package io.tolgee.development.testDataBuilder.data

import io.tolgee.development.testDataBuilder.builders.UserAccountBuilder
import io.tolgee.model.Language
import io.tolgee.model.enums.ProjectPermissionType

/**
 * A project with two target languages and a translator who may only view one of them — the shape
 * that showed the assets page was listing every language's file regardless of permission.
 */
class BinaryAssetLanguageScopeTestData : BaseTestData("asset_scope_manager", "Asset language scope") {
  lateinit var germanLanguage: Language
  lateinit var slovenianLanguage: Language
  var slovenianTranslator: UserAccountBuilder

  init {
    slovenianTranslator =
      root.addUserAccount {
        username = "slovenian.only@test.com"
        name = "Slovenian only"
      }

    projectBuilder.apply {
      addLanguage {
        name = "German"
        tag = "de"
        originalName = "Deutsch"
        germanLanguage = this
      }

      addLanguage {
        name = "Slovenian"
        tag = "sl"
        originalName = "Slovenščina"
        slovenianLanguage = this
      }

      addPermission {
        user = slovenianTranslator.self
        type = ProjectPermissionType.TRANSLATE
        viewLanguages = mutableSetOf(slovenianLanguage)
        translateLanguages = mutableSetOf(slovenianLanguage)
      }
    }
  }
}
