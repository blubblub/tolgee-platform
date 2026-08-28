package io.tolgee.dtos.request.binaryAsset

import io.swagger.v3.oas.annotations.media.Schema

data class BinaryAssetScreenshotLinkRequest(
  @Schema(description = "Id of a screenshot that already exists in this project, e.g. one attached to a key.")
  val screenshotId: Long,
)
