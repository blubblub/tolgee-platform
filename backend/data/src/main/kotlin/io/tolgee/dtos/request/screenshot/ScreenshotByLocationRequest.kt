package io.tolgee.dtos.request.screenshot

import io.swagger.v3.oas.annotations.media.Schema
import io.tolgee.dtos.request.KeyInScreenshotPositionDto
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * One screen, as a screenshot bot sees it: its identity and everything it found on it. Sent as the
 * JSON `info` part beside the `image` part.
 */
data class ScreenshotByLocationRequest(
  @Schema(
    description =
      "Identity of the screen (route, flow step, …). Uploading the same location again replaces the " +
        "image in place and resets the linked keys and assets to what this request lists.",
  )
  @field:NotBlank
  @field:Size(max = 255)
  val location: String,
  @Schema(description = "Keys visible on this screen. Names not found in the project are reported, not created.")
  val keys: List<ScreenshotKeyReferenceRequest> = emptyList(),
  @Schema(description = "Binary assets used on this screen. Names not found in the project are reported, not created.")
  val assets: List<ScreenshotAssetReferenceRequest> = emptyList(),
)

data class ScreenshotKeyReferenceRequest(
  @field:NotBlank
  val name: String,
  val namespace: String? = null,
  @Schema(description = "Where the key's text is on the image, in pixels of the uploaded image.")
  val positions: List<KeyInScreenshotPositionDto>? = null,
  @Schema(description = "The text as rendered on this screen.")
  @field:Size(max = 2000)
  val text: String? = null,
)

data class ScreenshotAssetReferenceRequest(
  @field:NotBlank
  val name: String,
)
