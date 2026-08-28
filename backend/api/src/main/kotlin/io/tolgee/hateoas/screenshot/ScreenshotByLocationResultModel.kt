package io.tolgee.hateoas.screenshot

import io.swagger.v3.oas.annotations.media.Schema

class ScreenshotByLocationResultModel(
  val screenshot: ScreenshotModel,
  @Schema(description = "True when no screenshot existed at this location before.")
  val created: Boolean,
  @Schema(description = "Older screenshots at the same location that were folded into this one.")
  val replacedScreenshots: Int,
  val linkedKeys: List<String>,
  val linkedAssets: List<String>,
  @Schema(description = "Requested key names that do not exist in the project. Nothing was created for them.")
  val unknownKeys: List<String>,
  @Schema(description = "Requested asset names that do not exist in the project. Nothing was created for them.")
  val unknownAssets: List<String>,
)
