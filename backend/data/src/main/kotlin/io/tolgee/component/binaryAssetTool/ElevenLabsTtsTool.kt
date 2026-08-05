package io.tolgee.component.binaryAssetTool

import io.tolgee.component.transcription.ElevenLabsTtsClient
import io.tolgee.configuration.tolgee.TolgeeProperties
import io.tolgee.constants.Message
import io.tolgee.exceptions.BadRequestException
import io.tolgee.service.binaryAsset.BinaryAssetService.FileStream
import io.tolgee.service.translation.TranslationService
import org.springframework.stereotype.Component

@Component
class ElevenLabsTtsTool(
  private val ttsClient: ElevenLabsTtsClient,
  private val translationService: TranslationService,
  private val tolgeeProperties: TolgeeProperties,
) : BinaryAssetTool {
  override val name: String = "tts"

  override fun run(
    input: FileStream,
    params: Map<String, Any?>,
    context: BinaryAssetToolContext,
  ): BinaryAssetToolOutput {
    ttsClient.checkConfigured()

    val voiceId =
      params["voiceId"]?.toString()?.takeIf { it.isNotBlank() }
        ?: throw BadRequestException(Message.BINARY_ASSET_TTS_VOICE_ID_REQUIRED)

    val modelId =
      params["modelId"]?.toString()?.takeIf { it.isNotBlank() }
        ?: tolgeeProperties.transcription.ttsModel

    val key =
      context.asset.transcriptKey
        ?: throw BadRequestException(Message.BINARY_ASSET_TTS_NO_TRANSCRIPT)
    val translations = translationService.findForKeyByLanguages(key, listOf(context.language.tag))
    val text =
      translations.firstOrNull()?.text?.takeIf { it.isNotBlank() }
        ?: throw BadRequestException(Message.BINARY_ASSET_TTS_NO_TRANSCRIPT)

    val bytes = ttsClient.synthesize(text, voiceId, modelId)

    val baseName = context.translation.originalFilename.substringBeforeLast('.')
    val filename = "$baseName-tts.mp3"

    return BinaryAssetToolOutput(bytes = bytes, filename = filename, contentType = "audio/mpeg")
  }
}
