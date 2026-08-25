package io.tolgee.model.enums

/**
 * What an asset's file is, and therefore which parts of the asset workflow apply to it.
 *
 * The lists here mirror the `like` clauses in `BinaryAssetRepository.findAllByProjectId` — keep
 * them in step, the query cannot share Kotlin constants.
 */
enum class BinaryAssetMediaType(
  val capabilities: BinaryAssetCapabilities,
) {
  /** Voice-over: transcript, TTS / voice-changer pipeline and in-browser recording all apply. */
  AUDIO(BinaryAssetCapabilities(transcript = true, pipeline = true, record = true)),

  /** Speech can be transcribed, but the audio tools would produce audio, not video. */
  VIDEO(BinaryAssetCapabilities(transcript = true, pipeline = false, record = false)),

  /** Localized by swapping in a whole other file; nothing is spoken. */
  IMAGE(BinaryAssetCapabilities(transcript = false, pipeline = false, record = false)),
  ;

  companion object {
    private val AUDIO_EXTENSIONS = listOf(".mp3", ".wav", ".ogg", ".m4a", ".aac", ".flac", ".webm", ".opus")
    private val VIDEO_EXTENSIONS = listOf(".mp4", ".mov", ".m4v", ".mkv", ".avi")
    private val IMAGE_EXTENSIONS = listOf(".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".bmp")

    /**
     * Infers the type from a content type, falling back to the filename extension — uploads often
     * arrive as `application/octet-stream`. Null when neither says anything (no original file, or
     * a document type the asset pipeline has no opinion on).
     */
    fun infer(
      contentType: String?,
      filename: String?,
    ): BinaryAssetMediaType? {
      val type =
        contentType
          ?.substringBefore(';')
          ?.trim()
          ?.lowercase()
          .orEmpty()
      when {
        type.startsWith("audio/") -> return AUDIO
        type.startsWith("video/") -> return VIDEO
        type.startsWith("image/") -> return IMAGE
      }
      val name = filename.orEmpty().lowercase()
      return when {
        AUDIO_EXTENSIONS.any { name.endsWith(it) } -> AUDIO
        VIDEO_EXTENSIONS.any { name.endsWith(it) } -> VIDEO
        IMAGE_EXTENSIONS.any { name.endsWith(it) } -> IMAGE
        else -> null
      }
    }

    /**
     * Capabilities for a possibly-unknown type. An asset with no original — or an unrecognised
     * file — keeps every affordance: a transcript may be the only thing it has, TTS synthesizes
     * from it, and recording is one way to supply the first file.
     */
    fun capabilitiesOf(mediaType: BinaryAssetMediaType?): BinaryAssetCapabilities =
      mediaType?.capabilities ?: BinaryAssetCapabilities.ALL

    fun parseList(raw: Collection<String>?): Set<BinaryAssetMediaType> {
      if (raw.isNullOrEmpty()) return emptySet()
      return raw
        .flatMap { it.split(',') }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { value ->
          entries.find { it.name.equals(value, ignoreCase = true) }
        }.toSet()
    }
  }
}

/** Which parts of the asset workflow a media type takes part in. */
data class BinaryAssetCapabilities(
  /** A transcript key can be attached and edited (AI transcription is additionally provider-gated). */
  val transcript: Boolean,
  /** TTS / voice-changer runs may produce versions. */
  val pipeline: Boolean,
  /** In-browser recording is offered as a way to supply a file. */
  val record: Boolean,
) {
  companion object {
    val ALL = BinaryAssetCapabilities(transcript = true, pipeline = true, record = true)
  }
}
