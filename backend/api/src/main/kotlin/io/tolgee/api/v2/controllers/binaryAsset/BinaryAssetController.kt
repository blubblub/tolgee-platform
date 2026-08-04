package io.tolgee.api.v2.controllers.binaryAsset

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import io.tolgee.activity.RequestActivity
import io.tolgee.activity.data.ActivityType
import io.tolgee.api.v2.controllers.IController
import io.tolgee.constants.Message
import io.tolgee.dtos.request.binaryAsset.BinaryAssetTranscriptRequest
import io.tolgee.dtos.request.binaryAsset.BinaryAssetUpdateRequest
import io.tolgee.exceptions.BadRequestException
import io.tolgee.hateoas.binaryAsset.BinaryAssetDownloadTicketModel
import io.tolgee.hateoas.binaryAsset.BinaryAssetModel
import io.tolgee.hateoas.binaryAsset.BinaryAssetModelAssembler
import io.tolgee.model.enums.BinaryAssetMediaType
import io.tolgee.model.enums.Scope
import io.tolgee.security.ProjectHolder
import io.tolgee.security.authentication.AllowApiAccess
import io.tolgee.security.authentication.AuthenticationFacade
import io.tolgee.security.authentication.JwtService
import io.tolgee.security.authorization.RequiresProjectPermissions
import io.tolgee.service.binaryAsset.BinaryAssetService
import io.tolgee.service.binaryAsset.BinaryAssetTranscriptService
import io.tolgee.service.language.LanguageService
import io.tolgee.service.security.SecurityService
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springdoc.core.annotations.ParameterObject
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PagedResourcesAssembler
import org.springframework.hateoas.PagedModel
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.CrossOrigin
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.servlet.support.ServletUriComponentsBuilder

@Suppress("MVCPathVariableInspection", "SpringJavaInjectionPointsAutowiringInspection")
@RestController
@CrossOrigin(origins = ["*"])
@RequestMapping(
  value = [
    "/v2/projects/{projectId:[0-9]+}/binary-assets",
  ],
)
@Tag(name = "Binary assets", description = "Project binary asset localization (source + per-language files)")
class BinaryAssetController(
  private val binaryAssetService: BinaryAssetService,
  private val binaryAssetTranscriptService: BinaryAssetTranscriptService,
  private val binaryAssetModelAssembler: BinaryAssetModelAssembler,
  private val pagedAssembler: PagedResourcesAssembler<io.tolgee.model.binaryAsset.BinaryAsset>,
  private val projectHolder: ProjectHolder,
  private val languageService: LanguageService,
  private val securityService: SecurityService,
  private val authenticationFacade: AuthenticationFacade,
  private val jwtService: JwtService,
) : IController {
  @GetMapping("")
  @Operation(summary = "List binary assets")
  @RequiresProjectPermissions([Scope.KEYS_VIEW])
  @AllowApiAccess
  fun list(
    @ParameterObject pageable: Pageable,
    @RequestParam(required = false) search: String?,
    @RequestParam(required = false, name = "filterMediaType") filterMediaType: List<String>?,
  ): PagedModel<BinaryAssetModel> {
    val projectId = projectHolder.project.id
    val mediaTypes = BinaryAssetMediaType.parseList(filterMediaType)
    val page = binaryAssetService.getPage(projectId, pageable, search, mediaTypes)
    val languages = languageService.findAll(projectId)
    val visible = visibleLanguageIds(projectId)
    return pagedAssembler.toModel(page) { asset ->
      binaryAssetModelAssembler.toListModel(asset, languages, visible)
    }
  }

  @GetMapping("/{assetId}")
  @Operation(summary = "Get binary asset detail")
  @RequiresProjectPermissions([Scope.KEYS_VIEW])
  @AllowApiAccess
  fun get(
    @PathVariable assetId: Long,
  ): BinaryAssetModel {
    return detailModel(projectHolder.project.id, assetId)
  }

  @PostMapping("", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
  @Operation(summary = "Create binary asset with source file")
  @ResponseStatus(HttpStatus.CREATED)
  @RequiresProjectPermissions([Scope.KEYS_CREATE])
  @AllowApiAccess
  @RequestActivity(ActivityType.BINARY_ASSET_CREATE)
  fun create(
    @RequestPart("name") name: String,
    @RequestPart("description", required = false) description: String?,
    @RequestPart("sourceLanguageId", required = false) sourceLanguageId: String?,
    @RequestPart("file") file: MultipartFile,
  ): BinaryAssetModel {
    val projectId = projectHolder.project.id
    val asset =
      binaryAssetService.create(
        projectId = projectId,
        name = name,
        description = description,
        sourceLanguageId = sourceLanguageId?.toLongOrNull(),
        file = file,
        uploader = authenticationFacade.authenticatedUserEntityOrNull,
      )
    return detailModel(projectId, asset.id)
  }

  @PutMapping("/{assetId}")
  @Operation(summary = "Update binary asset metadata")
  @RequiresProjectPermissions([Scope.KEYS_EDIT])
  @AllowApiAccess
  @RequestActivity(ActivityType.BINARY_ASSET_UPDATE)
  fun update(
    @PathVariable assetId: Long,
    @Valid @RequestBody dto: BinaryAssetUpdateRequest,
  ): BinaryAssetModel {
    val projectId = projectHolder.project.id
    binaryAssetService.updateMetadata(projectId, assetId, dto.name, dto.description)
    return detailModel(projectId, assetId)
  }

  @PostMapping("/{assetId}/transcript")
  @Operation(
    summary = "Add a transcript to a binary asset",
    description =
      "Creates a key owned by this asset (optionally seeded with text), or links an existing key. " +
        "Transcript text itself is edited through the normal key/translation endpoints.",
  )
  @RequiresProjectPermissions([Scope.KEYS_CREATE])
  @AllowApiAccess
  @RequestActivity(ActivityType.BINARY_ASSET_TRANSCRIPT_LINK)
  fun addTranscript(
    @PathVariable assetId: Long,
    @Valid @RequestBody dto: BinaryAssetTranscriptRequest,
  ): BinaryAssetModel {
    val projectId = projectHolder.project.id
    val keyId = dto.keyId
    if (keyId != null) {
      if (dto.text != null) {
        throw BadRequestException(Message.BINARY_ASSET_TRANSCRIPT_TEXT_OR_KEY_REQUIRED)
      }
      binaryAssetTranscriptService.link(projectId, assetId, keyId)
    } else {
      binaryAssetTranscriptService.create(projectId, assetId, dto.text)
    }
    return detailModel(projectId, assetId)
  }

  @DeleteMapping("/{assetId}/transcript")
  @Operation(
    summary = "Remove a binary asset's transcript",
    description =
      "An owned key is deleted with it; a linked key is left in place. " +
        "Returns the updated asset so callers do not need to re-fetch.",
  )
  @RequiresProjectPermissions([Scope.KEYS_EDIT])
  @AllowApiAccess
  @RequestActivity(ActivityType.BINARY_ASSET_TRANSCRIPT_UNLINK)
  fun deleteTranscript(
    @PathVariable assetId: Long,
  ): BinaryAssetModel {
    val projectId = projectHolder.project.id
    binaryAssetTranscriptService.unlink(projectId, assetId)
    return detailModel(projectId, assetId)
  }

  @PutMapping("/{assetId}/source", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
  @Operation(summary = "Replace binary asset source file")
  @RequiresProjectPermissions([Scope.KEYS_EDIT])
  @AllowApiAccess
  @RequestActivity(ActivityType.BINARY_ASSET_SOURCE_REPLACE)
  fun replaceSource(
    @PathVariable assetId: Long,
    @RequestPart("file") file: MultipartFile,
  ): BinaryAssetModel {
    val projectId = projectHolder.project.id
    binaryAssetService.replaceSource(
      projectId,
      assetId,
      file,
      authenticationFacade.authenticatedUserEntityOrNull,
    )
    return detailModel(projectId, assetId)
  }

  @PostMapping("/{assetId}/source/download-ticket")
  @Operation(summary = "Issue short-lived download ticket for source file")
  @RequiresProjectPermissions([Scope.KEYS_VIEW])
  @AllowApiAccess
  fun sourceDownloadTicket(
    @PathVariable assetId: Long,
  ): BinaryAssetDownloadTicketModel {
    val projectId = projectHolder.project.id
    val asset = binaryAssetService.get(projectId, assetId)
    return ticketModel(
      projectId = projectId,
      assetId = assetId,
      languageId = null,
      storageKey = asset.storageKey,
      contentType = asset.contentType,
      filename = asset.originalFilename,
      byteSize = asset.byteSize,
    )
  }

  @PutMapping("/{assetId}/translations/{languageId}", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
  @Operation(summary = "Upload or replace localized binary asset file")
  @RequiresProjectPermissions([Scope.TRANSLATIONS_EDIT])
  @AllowApiAccess
  @RequestActivity(ActivityType.BINARY_ASSET_TRANSLATION_UPSERT)
  fun upsertTranslation(
    @PathVariable assetId: Long,
    @PathVariable languageId: Long,
    @RequestPart("file") file: MultipartFile,
    @RequestPart("translatedAgainstSourceRevision") translatedAgainstSourceRevision: String,
  ): BinaryAssetModel {
    val projectId = projectHolder.project.id
    securityService.checkLanguageTranslatePermission(projectId, listOf(languageId))
    binaryAssetService.upsertTranslation(
      projectId = projectId,
      assetId = assetId,
      languageId = languageId,
      file = file,
      translatedAgainstSourceRevision = translatedAgainstSourceRevision.toLong(),
      uploader = authenticationFacade.authenticatedUserEntityOrNull,
    )
    return detailModel(projectId, assetId)
  }

  @PostMapping("/{assetId}/translations/{languageId}/download-ticket")
  @Operation(summary = "Issue short-lived download ticket for localized file")
  @RequiresProjectPermissions([Scope.TRANSLATIONS_VIEW])
  @AllowApiAccess
  fun translationDownloadTicket(
    @PathVariable assetId: Long,
    @PathVariable languageId: Long,
  ): BinaryAssetDownloadTicketModel {
    val projectId = projectHolder.project.id
    securityService.checkLanguageViewPermission(projectId, listOf(languageId))
    val stream = binaryAssetService.openTranslationStream(projectId, assetId, languageId)
    stream.inputStream.close()
    return ticketModel(
      projectId = projectId,
      assetId = assetId,
      languageId = languageId,
      storageKey = stream.storageKey,
      contentType = stream.contentType,
      filename = stream.filename,
      byteSize = stream.byteSize,
    )
  }

  @DeleteMapping("/{assetId}/translations/{languageId}")
  @Operation(summary = "Delete localized binary asset file")
  @RequiresProjectPermissions([Scope.TRANSLATIONS_EDIT])
  @AllowApiAccess
  @RequestActivity(ActivityType.BINARY_ASSET_TRANSLATION_DELETE)
  @ResponseStatus(HttpStatus.NO_CONTENT)
  fun deleteTranslation(
    @PathVariable assetId: Long,
    @PathVariable languageId: Long,
  ) {
    val projectId = projectHolder.project.id
    securityService.checkLanguageTranslatePermission(projectId, listOf(languageId))
    binaryAssetService.deleteTranslation(projectId, assetId, languageId)
  }

  @DeleteMapping("/{assetId}")
  @Operation(summary = "Delete binary asset")
  @RequiresProjectPermissions([Scope.KEYS_DELETE])
  @AllowApiAccess
  @RequestActivity(ActivityType.BINARY_ASSET_DELETE)
  @ResponseStatus(HttpStatus.NO_CONTENT)
  fun delete(
    @PathVariable assetId: Long,
  ) {
    binaryAssetService.deleteAsset(projectHolder.project.id, assetId)
  }

  private fun ticketModel(
    projectId: Long,
    assetId: Long,
    languageId: Long?,
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
      )
    if (languageId != null) {
      data["languageId"] = languageId.toString()
    }
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
    val forwarded = request?.getHeader("X-Forwarded-Proto")?.split(",")?.firstOrNull()?.trim()
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
      visibleLanguageIds(projectId),
      binaryAssetTranscriptService.getTranscriptTranslations(asset.transcriptKey?.id),
    )
  }

  private fun visibleLanguageIds(projectId: Long): Set<Long>? {
    // null = all project languages. Restricted users still only act on permitted languages via API checks.
    return null
  }
}

