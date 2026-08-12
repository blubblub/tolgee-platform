package io.tolgee.component.binaryAssetTool

import io.tolgee.model.Language
import io.tolgee.model.binaryAsset.BinaryAsset
import io.tolgee.model.binaryAsset.BinaryAssetTranslation
import io.tolgee.service.binaryAsset.BinaryAssetService.FileStream

data class BinaryAssetToolContext(
  val asset: BinaryAsset,
  /** Null when the run targets the asset's source language, which has no translation row. */
  val translation: BinaryAssetTranslation?,
  val language: Language,
  /** Project/language default, used when the run does not pass a `voiceId` param. */
  val defaultVoiceId: String? = null,
)

data class BinaryAssetToolOutput(
  val bytes: ByteArray,
  val filename: String,
  val contentType: String,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is BinaryAssetToolOutput) return false
    return bytes.contentEquals(other.bytes) && filename == other.filename && contentType == other.contentType
  }

  override fun hashCode(): Int {
    var result = bytes.contentHashCode()
    result = 31 * result + filename.hashCode()
    result = 31 * result + contentType.hashCode()
    return result
  }
}

interface BinaryAssetTool {
  val name: String

  /**
   * [input] is null when the lane being run has no file to work from — a source-less asset's source
   * lane. Tools that synthesize from scratch (TTS) ignore it; tools that transform existing audio
   * must reject null rather than assume one is present.
   */
  fun run(
    input: FileStream?,
    params: Map<String, Any?>,
    context: BinaryAssetToolContext,
  ): BinaryAssetToolOutput
}
