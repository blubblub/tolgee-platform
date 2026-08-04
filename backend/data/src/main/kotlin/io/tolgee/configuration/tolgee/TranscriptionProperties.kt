package io.tolgee.configuration.tolgee

import io.tolgee.configuration.annotations.DocProperty
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Speech-to-text for binary asset transcripts.
 *
 * Deliberately separate from `tolgee.llm`: that stack is EE-only and speaks chat-completions, which
 * cannot carry a multipart upload. Transcription is a direct multipart call, so it works on OSS and
 * streams the file instead of base64-inlining it.
 */
@ConfigurationProperties(prefix = "tolgee.transcription")
@DocProperty(
  name = "transcription",
  displayName = "Transcription (speech-to-text)",
)
class TranscriptionProperties {
  @DocProperty(
    description =
      "ElevenLabs API key. Transcription is disabled entirely when this is unset — " +
        "the UI hides the action and the endpoint returns a clear error.",
  )
  var apiKey: String? = null

  @DocProperty(description = "Scribe model id.")
  var model: String = "scribe_v2"

  @DocProperty(
    description =
      "API base URL. Use a residency endpoint (e.g. https://api.eu.residency.elevenlabs.io) " +
        "to keep audio in a specific region.",
  )
  var apiUrl: String = "https://api.elevenlabs.io"

  @DocProperty(
    description =
      "Largest file that will be sent for transcription, in kilobytes. " +
        "Guards against spending a long request on something that is not speech.",
  )
  var maxFileSizeKb: Long = 204800 // 200 MiB

  @DocProperty(description = "Request timeout in seconds. Long audio legitimately takes a while.")
  var timeoutSeconds: Long = 300

  val enabled: Boolean
    get() = !apiKey.isNullOrBlank()
}
