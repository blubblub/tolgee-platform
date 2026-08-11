package io.tolgee.api.v2.controllers.binaryAsset

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import io.tolgee.api.v2.controllers.IController
import io.tolgee.component.binaryAssetTool.BinaryAssetToolService
import io.tolgee.constants.Message
import io.tolgee.dtos.request.binaryAsset.SetBinaryAssetVoiceRequest
import io.tolgee.exceptions.BadRequestException
import io.tolgee.hateoas.binaryAsset.BinaryAssetVoiceModel
import io.tolgee.hateoas.binaryAsset.BinaryAssetVoiceModelAssembler
import io.tolgee.model.binaryAsset.BinaryAssetVoice
import io.tolgee.model.enums.Scope
import io.tolgee.security.ProjectHolder
import io.tolgee.security.authentication.AllowApiAccess
import io.tolgee.security.authorization.RequiresProjectPermissions
import io.tolgee.service.binaryAsset.BinaryAssetVoiceService
import io.tolgee.service.language.LanguageService
import org.springframework.web.bind.annotation.CrossOrigin
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Suppress("MVCPathVariableInspection", "SpringJavaInjectionPointsAutowiringInspection")
@RestController
@CrossOrigin(origins = ["*"])
@RequestMapping(value = ["/v2/projects/{projectId:[0-9]+}/binary-asset-voices"])
@Tag(name = "Binary asset voices", description = "Default pipeline voices per project and language")
class BinaryAssetVoiceController(
  private val binaryAssetVoiceService: BinaryAssetVoiceService,
  private val binaryAssetVoiceModelAssembler: BinaryAssetVoiceModelAssembler,
  private val binaryAssetToolService: BinaryAssetToolService,
  private val languageService: LanguageService,
  private val projectHolder: ProjectHolder,
) : IController {
  @GetMapping("")
  @Operation(summary = "List default voices — the project default plus any per-language overrides")
  @RequiresProjectPermissions([Scope.TRANSLATIONS_VIEW])
  @AllowApiAccess
  fun list(): List<BinaryAssetVoiceModel> =
    binaryAssetVoiceService
      .list(projectHolder.project.id)
      .map { binaryAssetVoiceModelAssembler.toModel(it) }

  @PutMapping("")
  @Operation(summary = "Set or clear the default voice for the project or one language")
  @RequiresProjectPermissions([Scope.LANGUAGES_EDIT])
  @AllowApiAccess
  fun set(
    @RequestBody request: SetBinaryAssetVoiceRequest,
  ): List<BinaryAssetVoiceModel> {
    val projectId = projectHolder.project.id
    request.languageId?.let { languageId ->
      // a language from another project would otherwise silently become that project's default
      languageService.findAll(projectId).find { it.id == languageId }
        ?: throw BadRequestException(Message.LANGUAGE_NOT_FROM_PROJECT)
    }
    val tool = BinaryAssetVoice.toolKey(request.tool)
    // an unknown tool name would store a default that no run ever reads
    if (tool != BinaryAssetVoice.ANY_TOOL) {
      binaryAssetToolService.getTool(tool)
    }
    binaryAssetVoiceService.set(projectId, request.languageId, tool, request.voiceId)
    return list()
  }
}
