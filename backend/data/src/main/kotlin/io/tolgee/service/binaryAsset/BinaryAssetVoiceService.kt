package io.tolgee.service.binaryAsset

import io.tolgee.model.Language
import io.tolgee.model.Project
import io.tolgee.model.binaryAsset.BinaryAssetVoice
import io.tolgee.repository.binaryAsset.BinaryAssetVoiceRepository
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Default voices for the pipeline tools. Deliberately holds no `ProjectService`/`LanguageService` —
 * project deletion calls into this, and injecting them back would close a bean cycle. The caller
 * validates that the language belongs to the project; here a reference is enough to set the FK.
 */
@Service
class BinaryAssetVoiceService(
  private val binaryAssetVoiceRepository: BinaryAssetVoiceRepository,
  private val entityManager: EntityManager,
) {
  @Transactional(readOnly = true)
  fun list(projectId: Long): List<BinaryAssetVoice> =
    binaryAssetVoiceRepository
      .findByProjectId(projectId)
      // project default first, then languages in a stable order
      .sortedBy { it.language?.id ?: Long.MIN_VALUE }

  /**
   * The voice to use when a run does not name one: the language's own default, else the project's.
   */
  @Transactional(readOnly = true)
  fun resolve(
    projectId: Long,
    languageId: Long,
  ): String? {
    val rows = binaryAssetVoiceRepository.findByProjectId(projectId)
    return rows.find { it.language?.id == languageId }?.voiceId
      ?: rows.find { it.language == null }?.voiceId
  }

  /**
   * @param languageId null targets the project-wide default
   * @param voiceId null or blank clears the entry
   */
  @Transactional
  fun set(
    projectId: Long,
    languageId: Long?,
    voiceId: String?,
  ): BinaryAssetVoice? {
    val existing =
      binaryAssetVoiceRepository
        .findByProjectId(projectId)
        .find { it.language?.id == languageId }

    val trimmed = voiceId?.trim()?.takeIf { it.isNotBlank() }
    if (trimmed == null) {
      existing?.let { binaryAssetVoiceRepository.delete(it) }
      return null
    }

    val entity =
      existing ?: BinaryAssetVoice(entityManager.getReference(Project::class.java, projectId)).apply {
        language = languageId?.let { entityManager.getReference(Language::class.java, it) }
      }
    entity.voiceId = trimmed
    return binaryAssetVoiceRepository.save(entity)
  }

  @Transactional
  fun deleteAllByProjectId(projectId: Long) {
    binaryAssetVoiceRepository.deleteAllByProjectId(projectId)
  }

  @Transactional
  fun deleteAllByLanguageId(languageId: Long) {
    binaryAssetVoiceRepository.deleteAllByLanguageId(languageId)
  }
}
