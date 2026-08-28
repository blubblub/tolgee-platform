package io.tolgee.api.v2.controllers.binaryAsset

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Encoding
import io.swagger.v3.oas.annotations.parameters.RequestBody
import io.swagger.v3.oas.annotations.tags.Tag
import io.tolgee.activity.RequestActivity
import io.tolgee.activity.data.ActivityType
import io.tolgee.api.v2.controllers.IController
import io.tolgee.dtos.request.ScreenshotInfoDto
import io.tolgee.dtos.request.binaryAsset.BinaryAssetScreenshotLinkRequest
import io.tolgee.hateoas.screenshot.ScreenshotModel
import io.tolgee.hateoas.screenshot.ScreenshotModelAssembler
import io.tolgee.model.enums.Scope
import io.tolgee.security.ProjectHolder
import io.tolgee.security.authentication.AllowApiAccess
import io.tolgee.security.authorization.RequiresProjectPermissions
import io.tolgee.service.ImageUploadService
import io.tolgee.service.binaryAsset.BinaryAssetScreenshotService
import jakarta.validation.Valid
import org.springframework.hateoas.CollectionModel
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.CrossOrigin
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

/**
 * Screens a binary asset is used on. Mirrors the key screenshot endpoints; the screenshots are the
 * same entities, so one attached to a key can be linked here without a second upload.
 */
@Suppress("MVCPathVariableInspection", "SpringJavaInjectionPointsAutowiringInspection")
@RestController
@CrossOrigin(origins = ["*"])
@RequestMapping(
  value = [
    "/v2/projects/{projectId:[0-9]+}/binary-assets/{assetId}/screenshots",
  ],
)
@Tag(name = "Binary assets")
class BinaryAssetScreenshotController(
  private val binaryAssetScreenshotService: BinaryAssetScreenshotService,
  private val screenshotModelAssembler: ScreenshotModelAssembler,
  private val imageUploadService: ImageUploadService,
  private val projectHolder: ProjectHolder,
) : IController {
  @GetMapping("")
  @Operation(summary = "List screenshots a binary asset is used on")
  @RequiresProjectPermissions([Scope.SCREENSHOTS_VIEW])
  @AllowApiAccess
  fun list(
    @PathVariable assetId: Long,
  ): CollectionModel<ScreenshotModel> {
    val screenshots = binaryAssetScreenshotService.list(projectHolder.project.id, assetId)
    return screenshotModelAssembler.toCollectionModel(screenshots)
  }

  @PostMapping("", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
  @Operation(
    summary = "Upload a screenshot for a binary asset",
    description = "Stores a new screenshot and links it to the asset. `info.location` names the screen.",
  )
  @ResponseStatus(HttpStatus.CREATED)
  @RequestBody(content = [Content(encoding = [Encoding(name = "info", contentType = "application/json")])])
  @RequiresProjectPermissions([Scope.SCREENSHOTS_UPLOAD])
  @AllowApiAccess
  @RequestActivity(ActivityType.BINARY_ASSET_SCREENSHOT_ADD)
  fun upload(
    @PathVariable assetId: Long,
    @RequestPart("screenshot") screenshot: MultipartFile,
    @RequestPart("info", required = false) info: ScreenshotInfoDto?,
  ): ScreenshotModel {
    imageUploadService.validateIsImage(screenshot)
    val stored = binaryAssetScreenshotService.upload(projectHolder.project.id, assetId, screenshot, info?.location)
    return screenshotModelAssembler.toModel(stored)
  }

  @PostMapping("/link")
  @Operation(
    summary = "Link an existing screenshot to a binary asset",
    description = "The screenshot must belong to this project — attached to one of its keys or assets.",
  )
  @RequiresProjectPermissions([Scope.SCREENSHOTS_UPLOAD])
  @AllowApiAccess
  @RequestActivity(ActivityType.BINARY_ASSET_SCREENSHOT_ADD)
  fun link(
    @PathVariable assetId: Long,
    @Valid @org.springframework.web.bind.annotation.RequestBody dto: BinaryAssetScreenshotLinkRequest,
  ): ScreenshotModel {
    val screenshot = binaryAssetScreenshotService.link(projectHolder.project.id, assetId, dto.screenshotId)
    return screenshotModelAssembler.toModel(screenshot)
  }

  @DeleteMapping("/{ids}")
  @Operation(
    summary = "Unlink screenshots from a binary asset",
    description = "A screenshot nothing else references any more is deleted with its files.",
  )
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @RequiresProjectPermissions([Scope.SCREENSHOTS_DELETE])
  @AllowApiAccess
  @RequestActivity(ActivityType.BINARY_ASSET_SCREENSHOT_DELETE)
  fun unlink(
    @PathVariable assetId: Long,
    @PathVariable("ids") ids: Set<Long>,
  ) {
    binaryAssetScreenshotService.unlink(projectHolder.project.id, assetId, ids)
  }
}
