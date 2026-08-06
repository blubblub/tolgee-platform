package io.tolgee.dtos.request.binaryAsset

/** Body of the confirm/un-confirm call for one language's final file. */
data class BinaryAssetReviewRequest(
  val reviewed: Boolean = true,
)
