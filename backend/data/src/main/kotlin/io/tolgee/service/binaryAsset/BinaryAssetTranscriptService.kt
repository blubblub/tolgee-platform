package io.tolgee.service.binaryAsset

import io.tolgee.constants.Message
import io.tolgee.dtos.request.key.CreateKeyDto
import io.tolgee.exceptions.BadRequestException
import io.tolgee.exceptions.NotFoundException
import io.tolgee.model.binaryAsset.BinaryAsset
import io.tolgee.model.key.Key
import io.tolgee.repository.KeyRepository
import io.tolgee.repository.TranslationRepository
import io.tolgee.repository.binaryAsset.BinaryAssetRepository
import io.tolgee.service.key.KeyService
import io.tolgee.service.project.ProjectService
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Links a binary asset to a plain-text transcript held in a regular [Key], so the transcript gets
 * the whole translation stack (states, MT, review, import/export) for free.
 *
 * There is deliberately no "update transcript text" operation — that is the normal key/translation
 * API, which is the entire point of storing the transcript as a key.
 */
@Service
class BinaryAssetTranscriptService(
  private val binaryAssetRepository: BinaryAssetRepository,
  private val keyRepository: KeyRepository,
  private val translationRepository: TranslationRepository,
  private val projectService: ProjectService,
  @Lazy
  private val keyService: KeyService,
) {
  companion object {
    const val TRANSCRIPT_KEY_PREFIX = "transcript."
    const val TRANSCRIPT_TAG = "transcript"
  }

  /**
   * Creates a key owned by this asset. No namespace: namespaces are feature-gated per project and
   * auto-garbage-collected when empty, so a "reserved" one would both break projects with the
   * feature off and silently vanish. The FK is the authoritative link; the name is only a default.
   */
  @Transactional
  fun create(
    projectId: Long,
    assetId: Long,
    text: String?,
  ): BinaryAsset {
    val asset = lockAsset(projectId, assetId)
    if (asset.transcriptKey != null) {
      throw BadRequestException(Message.BINARY_ASSET_TRANSCRIPT_EXISTS)
    }
    val project = projectService.get(projectId)
    val key =
      keyService.create(
        project,
        CreateKeyDto(
          name = TRANSCRIPT_KEY_PREFIX + asset.name,
          translations = text?.takeIf { it.isNotBlank() }?.let { mapOf(asset.sourceLanguage.tag to it) },
          tags = listOf(TRANSCRIPT_TAG),
        ),
      )
    asset.transcriptKey = key
    asset.transcriptKeyOwned = true
    return binaryAssetRepository.save(asset)
  }

  /** Points the asset at a key that already exists, e.g. the on-screen string the voiceover reads. */
  @Transactional
  fun link(
    projectId: Long,
    assetId: Long,
    keyId: Long,
  ): BinaryAsset {
    val asset = lockAsset(projectId, assetId)
    if (asset.transcriptKey != null) {
      throw BadRequestException(Message.BINARY_ASSET_TRANSCRIPT_EXISTS)
    }
    val key = keyRepository.findById(keyId).orElseThrow { NotFoundException(Message.KEY_NOT_FOUND) }
    keyService.checkInProject(key, projectId)
    if (key.deletedAt != null) {
      throw NotFoundException(Message.KEY_NOT_FOUND)
    }
    asset.transcriptKey = key
    asset.transcriptKeyOwned = false
    return binaryAssetRepository.save(asset)
  }

  /**
   * Drops the link. An owned key is hard-deleted with it; a linked one is left alone because other
   * assets — or the UI it came from — may still need it.
   */
  @Transactional
  fun unlink(
    projectId: Long,
    assetId: Long,
  ) {
    val asset = lockAsset(projectId, assetId)
    val key = asset.transcriptKey ?: throw NotFoundException(Message.BINARY_ASSET_TRANSCRIPT_NOT_FOUND)
    val owned = asset.transcriptKeyOwned
    asset.transcriptKey = null
    asset.transcriptKeyOwned = false
    binaryAssetRepository.save(asset)
    if (owned) {
      keyService.hardDelete(key.id)
    }
  }

  /** Transcript translations of the linked key, by language id. Empty when nothing is linked. */
  @Transactional(readOnly = true)
  fun getTranscriptTranslations(keyId: Long?): Map<Long, TranscriptText> {
    keyId ?: return emptyMap()
    return getTranscriptTranslationsByKey(listOf(keyId))[keyId] ?: emptyMap()
  }

  /**
   * Transcripts for many keys in one query, keyed by key id then language id — so listing a page
   * of assets costs one query rather than one per asset.
   */
  @Transactional(readOnly = true)
  fun getTranscriptTranslationsByKey(keyIds: Collection<Long>): Map<Long, Map<Long, TranscriptText>> {
    if (keyIds.isEmpty()) return emptyMap()
    return translationRepository
      .getAllByKeyIdIn(keyIds.distinct())
      .groupBy { it.key.id }
      .mapValues { (_, translations) ->
        translations.associate { it.language.id to TranscriptText(it.text, it.state.name) }
      }
  }

  private fun lockAsset(
    projectId: Long,
    assetId: Long,
  ): BinaryAsset =
    binaryAssetRepository.findByProjectIdAndIdForUpdate(projectId, assetId)
      ?: throw NotFoundException(Message.BINARY_ASSET_NOT_FOUND)

  data class TranscriptText(
    val text: String?,
    val state: String,
  )
}

/** Soft-deleted keys stay referenced until someone unlinks them, so the UI needs to say so. */
val Key.isTrashed: Boolean
  get() = deletedAt != null
