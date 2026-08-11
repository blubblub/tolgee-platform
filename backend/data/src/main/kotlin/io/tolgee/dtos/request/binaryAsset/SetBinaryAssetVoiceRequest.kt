package io.tolgee.dtos.request.binaryAsset

data class SetBinaryAssetVoiceRequest(
  /** null targets the project-wide default */
  val languageId: Long? = null,
  /** null or blank targets every tool; a tool name (`tts`, `voice-changer`) narrows it to that tool */
  val tool: String? = null,
  /** null or blank clears the entry */
  val voiceId: String? = null,
)
