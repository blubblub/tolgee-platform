package io.tolgee.component.transcription

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
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
 * ElevenLabs Scribe speech-to-text.
 *
 * Multipart rather than base64-in-JSON so the file streams straight from storage — a 200 MB video
 * never lands in heap. This is also why it cannot go through the EE LLM abstraction, which only
 * speaks chat-completions.
 */
@Component
class ElevenLabsTranscriptionClient(
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

  /**
   * @param languageTag source language of the audio. Optional, but improves accuracy noticeably,
   *   and we always know it from the asset.
   */
  fun transcribe(
    stream: InputStream,
    filename: String,
    contentType: String,
    byteSize: Long,
    languageTag: String?,
  ): String {
    checkConfigured()
    if (byteSize > config.maxFileSizeKb * 1024) {
      throw BadRequestException(Message.TRANSCRIPTION_FILE_TOO_LARGE)
    }

    val body = LinkedMultiValueMap<String, Any>()
    body.add("model_id", config.model)
    // ElevenLabs takes ISO-639-1/3; a region suffix like en-GB would be rejected
    languageTag?.substringBefore('-')?.takeIf { it.isNotBlank() }?.let { body.add("language_code", it) }
    // we want the words, not "[laughter]"
    body.add("tag_audio_events", "false")
    body.add("timestamps_granularity", "none")
    body.add("diarize", "false")
    body.add(
      "file",
      object : InputStreamResource(stream) {
        override fun getFilename() = filename

        // RestTemplate must not buffer the stream to discover the length
        override fun contentLength() = byteSize
      },
    )

    val headers =
      HttpHeaders().apply {
        this.contentType = MediaType.MULTIPART_FORM_DATA
        set("xi-api-key", config.apiKey!!)
      }

    return try {
      val response =
        restTemplate.postForObject(
          "${config.apiUrl.trimEnd('/')}/v1/speech-to-text",
          HttpEntity(body, headers),
          ScribeResponse::class.java,
        )
      response?.text?.trim().orEmpty().ifBlank {
        throw BadRequestException(Message.TRANSCRIPTION_EMPTY_RESULT)
      }
    } catch (e: HttpClientErrorException) {
      // 4xx is our fault (bad key, unsupported file) — surface it, never retry
      logger.warn("ElevenLabs rejected transcription of $filename: ${e.statusCode} ${e.responseBodyAsString.take(300)}")
      throw BadRequestException(
        when (e.statusCode.value()) {
          401 -> Message.TRANSCRIPTION_UNAUTHORIZED
          429 -> Message.TRANSCRIPTION_RATE_LIMITED
          else -> Message.TRANSCRIPTION_FAILED
        },
      )
    } catch (e: HttpServerErrorException) {
      logger.warn("ElevenLabs server error transcribing $filename: ${e.statusCode}")
      throw BadRequestException(Message.TRANSCRIPTION_FAILED)
    } catch (e: RestClientException) {
      logger.warn("ElevenLabs call failed for $filename: ${e.message}")
      throw BadRequestException(Message.TRANSCRIPTION_FAILED)
    }
  }

  /** Only `text` is consumed; the rest of Scribe's payload (words, timings) is ignored for now. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  data class ScribeResponse(
    val text: String? = null,
  )
}
