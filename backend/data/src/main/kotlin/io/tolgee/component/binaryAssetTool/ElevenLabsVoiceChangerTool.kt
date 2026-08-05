package io.tolgee.component.binaryAssetTool

import io.tolgee.component.transcription.ElevenLabsVoiceClient
import io.tolgee.configuration.tolgee.TolgeeProperties
import io.tolgee.constants.Message
import io.tolgee.exceptions.BadRequestException
import io.tolgee.service.binaryAsset.BinaryAssetService.FileStream
import org.springframework.stereotype.Component

/**
 * Re-speaks the input file (the uploaded translation or any earlier version) with another voice,
 * keeping the original timing and delivery. Chains naturally after [ElevenLabsTtsTool].
 */
@Component
class ElevenLabsVoiceChangerTool(
  private val voiceClient: ElevenLabsVoiceClient,
  private val tolgeeProperties: TolgeeProperties,
) : BinaryAssetTool {
  override val name: String = "voice-changer"

  override fun run(
    input: FileStream,
    params: Map<String, Any?>,
    context: BinaryAssetToolContext,
  ): BinaryAssetToolOutput {
    voiceClient.checkConfigured()

    val voiceId =
      params["voiceId"]?.toString()?.takeIf { it.isNotBlank() }
        ?: throw BadRequestException(Message.BINARY_ASSET_TTS_VOICE_ID_REQUIRED)

    val modelId =
      params["modelId"]?.toString()?.takeIf { it.isNotBlank() }
        ?: tolgeeProperties.transcription.voiceChangerModel

    val removeBackgroundNoise = params["removeBackgroundNoise"]?.toString().toBoolean()

    val bytes =
      voiceClient.changeVoice(
        stream = input.inputStream,
        filename = input.filename,
        byteSize = input.byteSize,
        voiceId = voiceId,
        modelId = modelId,
        removeBackgroundNoise = removeBackgroundNoise,
      )

    val baseName = input.filename.substringBeforeLast('.')

    return BinaryAssetToolOutput(bytes = bytes, filename = "$baseName-voice.mp3", contentType = "audio/mpeg")
  }
}
