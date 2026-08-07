package io.tolgee.dtos.request.binaryAsset

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Size

/**
 * Either creates a key owned by the asset (optionally seeded with [text]), or points the asset at
 * an existing key ([keyId]). Exactly one of the two.
 */
data class BinaryAssetTranscriptRequest(
  @Schema(
    description =
      "Initial transcript text, in the asset's source language unless languageTag says otherwise. " +
        "Creates a new key owned by this asset. Mutually exclusive with keyId.",
  )
  @field:Size(max = 10000)
  val text: String? = null,
  @Schema(
    description =
      "Language tag the initial text is in. Defaults to the asset's source language. " +
        "Only meaningful together with text; must be a language of this project.",
  )
  val languageTag: String? = null,
  @Schema(
    description =
      "Id of an existing key in this project to use as the transcript. " +
        "The key is not deleted when the asset is. Mutually exclusive with text.",
  )
  val keyId: Long? = null,
)
