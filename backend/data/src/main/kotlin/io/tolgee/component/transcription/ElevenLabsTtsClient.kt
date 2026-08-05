package io.tolgee.component.transcription

import io.tolgee.configuration.tolgee.TolgeeProperties
import io.tolgee.constants.Message
import io.tolgee.exceptions.BadRequestException
import io.tolgee.util.Logging
import io.tolgee.util.logger
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestTemplate
import java.time.Duration

@Component
class ElevenLabsTtsClient(
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
    val headers =
      HttpHeaders().apply {
        contentType = MediaType.APPLICATION_JSON
        set("xi-api-key", config.apiKey!!)
      }

    return try {
      restTemplate.postForObject(
        "${config.apiUrl.trimEnd('/')}/v1/text-to-speech/$voiceId?output_format=mp3_44100_128",
        HttpEntity(body, headers),
        ByteArray::class.java,
      ) ?: throw BadRequestException(Message.TRANSCRIPTION_FAILED)
    } catch (e: HttpClientErrorException) {
      logger.warn("ElevenLabs rejected TTS for voice $voiceId: ${e.statusCode} ${e.responseBodyAsString.take(300)}")
      throw BadRequestException(
        when (e.statusCode.value()) {
          401 -> Message.TRANSCRIPTION_UNAUTHORIZED
          429 -> Message.TRANSCRIPTION_RATE_LIMITED
          else -> Message.TRANSCRIPTION_FAILED
        },
      )
    } catch (e: HttpServerErrorException) {
      logger.warn("ElevenLabs server error in TTS for voice $voiceId: ${e.statusCode}")
      throw BadRequestException(Message.TRANSCRIPTION_FAILED)
    } catch (e: RestClientException) {
      logger.warn("ElevenLabs TTS call failed for voice $voiceId: ${e.message}")
      throw BadRequestException(Message.TRANSCRIPTION_FAILED)
    }
  }
}
