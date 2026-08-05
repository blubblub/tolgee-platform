package io.tolgee.api.v2.controllers.binaryAsset

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import io.tolgee.activity.RequestActivity
import io.tolgee.activity.data.ActivityType
import io.tolgee.api.v2.controllers.IController
import io.tolgee.constants.Message
import io.tolgee.dtos.request.binaryAsset.RunBinaryAssetToolRequest
import io.tolgee.dtos.request.binaryAsset.SetChosenVersionRequest
import io.tolgee.exceptions.BadRequestException
import io.tolgee.hateoas.binaryAsset.BinaryAssetDownloadTicketModel
import io.tolgee.hateoas.binaryAsset.BinaryAssetModel
import io.tolgee.hateoas.binaryAsset.BinaryAssetModelAssembler
import io.tolgee.hateoas.binaryAsset.BinaryAssetTranslationVersionModel
import io.tolgee.hateoas.binaryAsset.BinaryAssetTranslationVersionModelAssembler
import io.tolgee.model.enums.Scope
import io.tolgee.security.ProjectHolder
import io.tolgee.security.authentication.AllowApiAccess
import io.tolgee.security.authentication.AuthenticationFacade
import io.tolgee.security.authentication.JwtService
import io.tolgee.security.authorization.RequiresProjectPermissions
import io.tolgee.service.binaryAsset.BinaryAssetService
import io.tolgee.service.binaryAsset.BinaryAssetTranscriptService
import io.tolgee.service.binaryAsset.BinaryAssetTranslationVersionService
import io.tolgee.service.language.LanguageService
import io.tolgee.service.security.SecurityService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.CrossOrigin
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.support.ServletUriComponentsBuilder

@Suppress("MVCPathVariableInspection", "SpringJavaInjectionPointsAutowiringInspection")
@RestController
@CrossOrigin(origins = ["*"])
@RequestMapping(
  value = [
    "/v2/projects/{projectId:[0-9]+}/binary-assets/{assetId}/translations/{languageId}/versions",
  ],
)
@Tag(name = "Binary asset translation versions", description = "Pipeline versions for per-language binary asset files")
class BinaryAssetTranslationVersionController(
  private val binaryAssetService: BinaryAssetService,
  private val binaryAssetTranscriptService: BinaryAssetTranscriptService,
  private val binaryAssetTranslationVersionService: BinaryAssetTranslationVersionService,
  private val binaryAssetModelAssembler: BinaryAssetModelAssembler,
  private val binaryAssetTranslationVersionModelAssembler: BinaryAssetTranslationVersionModelAssembler,
  private val projectHolder: ProjectHolder,
  private val languageService: LanguageService,
  private val securityService: SecurityService,
  private val authenticationFacade: AuthenticationFacade,
  private val jwtService: JwtService,
) : IController {
  @GetMapping("")
  @Operation(summary = "List pipeline versions for a translation")
  @RequiresProjectPermissions([Scope.TRANSLATIONS_VIEW])
  @AllowApiAccess
  fun list(
    @PathVariable assetId: Long,
    @PathVariable languageId: Long,
  ): List<BinaryAssetTranslationVersionModel> {
    val projectId = projectHolder.project.id
    securityService.checkLanguageViewPermission(projectId, listOf(languageId))
    val versions = binaryAssetTranslationVersionService.listVersions(projectId, assetId, languageId)
    return versions.map { binaryAssetTranslationVersionModelAssembler.toModel(it) }
  }

  @PostMapping("/run")
  @Operation(summary = "Run a tool on the OG or a version, producing a new version")
  @ResponseStatus(HttpStatus.CREATED)
  @RequiresProjectPermissions([Scope.TRANSLATIONS_EDIT])
  @AllowApiAccess
  @RequestActivity(ActivityType.BINARY_ASSET_TRANSLATION_VERSION_RUN)
  fun runTool(
    @PathVariable assetId: Long,
    @PathVariable languageId: Long,
    @RequestBody request: RunBinaryAssetToolRequest,
  ): BinaryAssetTranslationVersionModel {
    val projectId = projectHolder.project.id
    securityService.checkLanguageTranslatePermission(projectId, listOf(languageId))
    val version =
      binaryAssetTranslationVersionService.runTool(
        projectId = projectId,
        assetId = assetId,
        languageId = languageId,
        toolName = request.tool,
        params = request.params ?: emptyMap(),
        baseVersionId = request.baseVersionId,
        user = authenticationFacade.authenticatedUserEntityOrNull,
      )
    return binaryAssetTranslationVersionModelAssembler.toModel(version)
  }

  @PutMapping("/chosen-version")
  @Operation(summary = "Set the chosen (reviewed final) version, or null for OG")
  @RequiresProjectPermissions([Scope.TRANSLATIONS_STATE_EDIT])
  @AllowApiAccess
  @RequestActivity(ActivityType.BINARY_ASSET_TRANSLATION_VERSION_CHOOSE)
  fun setChosen(
    @PathVariable assetId: Long,
    @PathVariable languageId: Long,
    @RequestBody request: SetChosenVersionRequest,
  ): BinaryAssetModel {
    val projectId = projectHolder.project.id
    securityService.checkLanguageStateChangePermission(projectId, listOf(languageId))
    binaryAssetTranslationVersionService.setChosen(projectId, assetId, languageId, request.versionId)
    return detailModel(projectId, assetId)
  }

  @DeleteMapping("/{versionId}")
  @Operation(summary = "Delete a pipeline version")
  @RequiresProjectPermissions([Scope.TRANSLATIONS_EDIT])
  @AllowApiAccess
  @RequestActivity(ActivityType.BINARY_ASSET_TRANSLATION_VERSION_DELETE)
  @ResponseStatus(HttpStatus.NO_CONTENT)
  fun delete(
    @PathVariable assetId: Long,
    @PathVariable languageId: Long,
    @PathVariable versionId: Long,
  ) {
    val projectId = projectHolder.project.id
    securityService.checkLanguageTranslatePermission(projectId, listOf(languageId))
    binaryAssetTranslationVersionService.deleteVersion(projectId, assetId, languageId, versionId)
  }

  @PostMapping("/{versionId}/download-ticket")
  @Operation(summary = "Issue short-lived download ticket for a version file")
  @RequiresProjectPermissions([Scope.TRANSLATIONS_VIEW])
  @AllowApiAccess
  fun downloadTicket(
    @PathVariable assetId: Long,
    @PathVariable languageId: Long,
    @PathVariable versionId: Long,
  ): BinaryAssetDownloadTicketModel {
    val projectId = projectHolder.project.id
    securityService.checkLanguageViewPermission(projectId, listOf(languageId))
    val version = binaryAssetTranslationVersionService.getVersion(projectId, assetId, languageId, versionId)
    return ticketModel(
      projectId = projectId,
      assetId = assetId,
      languageId = languageId,
      versionId = versionId,
      storageKey = version.storageKey,
      contentType = version.contentType,
      filename = version.originalFilename,
      byteSize = version.byteSize,
    )
  }

  private fun ticketModel(
    projectId: Long,
    assetId: Long,
    languageId: Long,
    versionId: Long,
    storageKey: String,
    contentType: String,
    filename: String,
    byteSize: Long,
  ): BinaryAssetDownloadTicketModel {
    val userId = authenticationFacade.authenticatedUser.id
    val data =
      mutableMapOf(
        "projectId" to projectId.toString(),
        "assetId" to assetId.toString(),
        "storageKey" to storageKey,
        "contentType" to contentType,
        "filename" to filename,
        "byteSize" to byteSize.toString(),
        "languageId" to languageId.toString(),
        "versionId" to versionId.toString(),
      )
    val token =
      jwtService.emitTicket(
        userId,
        JwtService.TicketType.BINARY_ASSET_ACCESS,
        JwtService.DEFAULT_TICKET_EXPIRATION_TIME,
        data,
      )
    val url =
      ServletUriComponentsBuilder
        .fromCurrentContextPath()
        .scheme(resolveRequestScheme())
        .path("/v2/binary-assets/download")
        .queryParam("token", token)
        .toUriString()
    return BinaryAssetDownloadTicketModel(url)
  }

  private fun resolveRequestScheme(): String {
    val request =
      org.springframework.web.context.request.RequestContextHolder
        .getRequestAttributes()
        .let { it as? org.springframework.web.context.request.ServletRequestAttributes }
        ?.request
    val forwarded =
      request
        ?.getHeader("X-Forwarded-Proto")
        ?.split(",")
        ?.firstOrNull()
        ?.trim()
    return when {
      !forwarded.isNullOrBlank() -> forwarded
      request?.scheme != null -> request.scheme
      else -> "https"
    }
  }

  private fun detailModel(
    projectId: Long,
    assetId: Long,
  ): BinaryAssetModel {
    val asset = binaryAssetService.get(projectId, assetId)
    return binaryAssetModelAssembler.toDetailModel(
      asset,
      languageService.findAll(projectId),
      null,
      binaryAssetTranscriptService.getTranscriptTranslations(asset.transcriptKey?.id),
    )
  }
}
