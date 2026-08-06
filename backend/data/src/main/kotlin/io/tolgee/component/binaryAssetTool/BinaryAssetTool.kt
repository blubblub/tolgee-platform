package io.tolgee.component.binaryAssetTool

import io.tolgee.model.Language
import io.tolgee.model.binaryAsset.BinaryAsset
import io.tolgee.model.binaryAsset.BinaryAssetTranslation
import io.tolgee.service.binaryAsset.BinaryAssetService.FileStream

data class BinaryAssetToolContext(
  val asset: BinaryAsset,
  val translation: BinaryAssetTranslation,
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

  fun run(
    input: FileStream,
    params: Map<String, Any?>,
    context: BinaryAssetToolContext,
  ): BinaryAssetToolOutput
}
