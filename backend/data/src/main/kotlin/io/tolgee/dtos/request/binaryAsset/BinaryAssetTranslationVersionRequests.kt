package io.tolgee.dtos.request.binaryAsset

data class RunBinaryAssetToolRequest(
  val tool: String,
  val params: Map<String, Any?>? = null,
  val baseVersionId: Long? = null,
)

data class SetChosenVersionRequest(
  val versionId: Long? = null,
)
