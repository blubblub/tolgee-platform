package io.tolgee.dtos.request.binaryAsset

data class SetBinaryAssetVoiceRequest(
  /** null targets the project-wide default */
  val languageId: Long? = null,
  /** null or blank clears the entry */
  val voiceId: String? = null,
)
