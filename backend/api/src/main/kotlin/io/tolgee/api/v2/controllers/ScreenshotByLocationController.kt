package io.tolgee.api.v2.controllers

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Encoding
import io.swagger.v3.oas.annotations.parameters.RequestBody
import io.swagger.v3.oas.annotations.tags.Tag
import io.tolgee.activity.RequestActivity
import io.tolgee.activity.data.ActivityType
import io.tolgee.dtos.request.screenshot.ScreenshotByLocationRequest
import io.tolgee.hateoas.screenshot.ScreenshotByLocationResultModel
import io.tolgee.hateoas.screenshot.ScreenshotModelAssembler
import io.tolgee.model.enums.Scope
import io.tolgee.security.ProjectHolder
import io.tolgee.security.authentication.AllowApiAccess
import io.tolgee.security.authorization.RequiresProjectPermissions
import io.tolgee.service.ImageUploadService
import io.tolgee.service.key.ScreenshotByLocationService
import jakarta.validation.Valid
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.CrossOrigin
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@Suppress("MVCPathVariableInspection")
@RestController
@CrossOrigin(origins = ["*"])
@RequestMapping(
  value = [
    "/v2/projects/screenshots",
    "/v2/projects/{projectId:[0-9]+}/screenshots",
  ],
)
@Tag(name = "Screenshots")
class ScreenshotByLocationController(
  private val screenshotByLocationService: ScreenshotByLocationService,
  private val screenshotModelAssembler: ScreenshotModelAssembler,
  private val imageUploadService: ImageUploadService,
  private val projectHolder: ProjectHolder,
) : IController {
  @PutMapping("/by-location", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
  @Operation(
    summary = "Upload or replace the screenshot of a screen, and set what is on it",
    description =
      "Identified by `info.location`. Uploading the same location again replaces the image in place " +
        "and links exactly the keys and assets listed — links not listed are dropped. Keys and assets " +
        "are never created or deleted; unknown names are reported back. Idempotent, safe to rerun.",
  )
  @RequestBody(content = [Content(encoding = [Encoding(name = "info", contentType = "application/json")])])
  // replaces images, unlinks keys and deletes folded-in screenshots — upload alone is not enough
  @RequiresProjectPermissions([Scope.SCREENSHOTS_UPLOAD, Scope.SCREENSHOTS_DELETE])
  @AllowApiAccess
  @RequestActivity(ActivityType.SCREENSHOT_UPSERT_BY_LOCATION)
  fun upsertByLocation(
    @RequestPart("image") image: MultipartFile,
    @Valid @RequestPart("info") info: ScreenshotByLocationRequest,
  ): ScreenshotByLocationResultModel {
    imageUploadService.validateIsImage(image)
    val result = screenshotByLocationService.upsert(projectHolder.project.id, image, info)
    return ScreenshotByLocationResultModel(
      screenshot = screenshotModelAssembler.toModel(result.screenshot),
      created = result.created,
      replacedScreenshots = result.replacedScreenshots,
      linkedKeys = result.linkedKeys,
      linkedAssets = result.linkedAssets,
      unknownKeys = result.unknownKeys,
      unknownAssets = result.unknownAssets,
    )
  }
}
