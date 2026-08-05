package io.tolgee.component.transcription

import io.tolgee.configuration.tolgee.TolgeeProperties
import io.tolgee.constants.Message
import io.tolgee.exceptions.BadRequestException
import io.tolgee.util.Logging
import io.tolgee.util.logger
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.core.io.InputStreamResource
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestTemplate
import java.io.InputStream
import java.time.Duration

/**
 * ElevenLabs voice generation — text-to-speech and speech-to-speech (voice changer).
 *
 * Both share the same key, base URL and failure modes, so they share a client. Speech-to-speech is
 * multipart so the input audio streams straight from storage instead of landing in heap.
 */
@Component
class ElevenLabsVoiceClient(
  private val tolgeeProperties: TolgeeProperties,
  private val restTemplateBuilder: RestTemplateBuilder,
) : Logging {
  private val config get() = tolgeeProperties.transcription

  private val restTemplate: RestTemplate by lazy {
    restTemplateBuilder
      .connectTimeout(Duration.ofSeconds(30))
      .readTimeout(Duration.ofSeconds(config.timeoutSeconds))
      .build()
  }

  val isConfigured: Boolean get() = config.enabled

  fun checkConfigured() {
    if (!isConfigured) {
      throw BadRequestException(Message.TRANSCRIPTION_NOT_CONFIGURED)
    }
  }

  fun synthesize(
    text: String,
    voiceId: String,
    modelId: String,
  ): ByteArray {
    checkConfigured()
    if (voiceId.isBlank()) {
      throw BadRequestException(Message.BINARY_ASSET_TTS_VOICE_ID_REQUIRED)
    }

    val body = mapOf("text" to text, "model_id" to modelId)

    return call("TTS for voice $voiceId") {
      restTemplate.postForObject(
        "${config.apiUrl.trimEnd('/')}/v1/text-to-speech/$voiceId?output_format=$OUTPUT_FORMAT",
        HttpEntity(body, jsonHeaders()),
        ByteArray::class.java,
      )
    }
  }

  /**
   * Re-speaks [stream] with [voiceId], keeping the original timing and delivery.
   */
  fun changeVoice(
    stream: InputStream,
    filename: String,
    byteSize: Long,
    voiceId: String,
    modelId: String,
    removeBackgroundNoise: Boolean,
  ): ByteArray {
    checkConfigured()
    if (voiceId.isBlank()) {
      throw BadRequestException(Message.BINARY_ASSET_TTS_VOICE_ID_REQUIRED)
    }
    if (byteSize > config.maxFileSizeKb * 1024) {
      throw BadRequestException(Message.TRANSCRIPTION_FILE_TOO_LARGE)
    }

    val body = LinkedMultiValueMap<String, Any>()
    body.add("model_id", modelId)
    body.add("remove_background_noise", removeBackgroundNoise.toString())
    body.add(
      "audio",
      object : InputStreamResource(stream) {
        override fun getFilename() = filename

        // RestTemplate must not buffer the stream to discover the length
        override fun contentLength() = byteSize
      },
    )

    return call("voice change of $filename to voice $voiceId") {
      restTemplate.postForObject(
        "${config.apiUrl.trimEnd('/')}/v1/speech-to-speech/$voiceId?output_format=$OUTPUT_FORMAT",
        HttpEntity(body, multipartHeaders()),
        ByteArray::class.java,
      )
    }
  }

  private fun jsonHeaders() =
    HttpHeaders().apply {
      contentType = MediaType.APPLICATION_JSON
      set("xi-api-key", config.apiKey!!)
    }

  private fun multipartHeaders() =
    HttpHeaders().apply {
      contentType = MediaType.MULTIPART_FORM_DATA
      set("xi-api-key", config.apiKey!!)
    }

  private fun <T : Any> call(
    what: String,
    block: () -> T?,
  ): T =
    try {
      block() ?: throw BadRequestException(Message.TRANSCRIPTION_FAILED)
    } catch (e: HttpClientErrorException) {
      // 4xx is our fault (bad key, unusable audio) — surface it, never retry
      logger.warn("ElevenLabs rejected $what: ${e.statusCode} ${e.responseBodyAsString.take(300)}")
      throw BadRequestException(
        when (e.statusCode.value()) {
          401 -> Message.TRANSCRIPTION_UNAUTHORIZED
          429 -> Message.TRANSCRIPTION_RATE_LIMITED
          else -> Message.TRANSCRIPTION_FAILED
        },
      )
    } catch (e: HttpServerErrorException) {
      logger.warn("ElevenLabs server error on $what: ${e.statusCode}")
      throw BadRequestException(Message.TRANSCRIPTION_FAILED)
    } catch (e: RestClientException) {
      logger.warn("ElevenLabs call failed for $what: ${e.message}")
      throw BadRequestException(Message.TRANSCRIPTION_FAILED)
    }

  companion object {
    private const val OUTPUT_FORMAT = "mp3_44100_128"
  }
}
