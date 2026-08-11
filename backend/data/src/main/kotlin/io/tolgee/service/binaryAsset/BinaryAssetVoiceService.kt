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
      // project default first, then languages in a stable order, tool-specific rows after shared ones
      .sortedWith(compareBy({ it.language?.id ?: Long.MIN_VALUE }, { it.tool }))

  /**
   * The voice to use when a run does not name one, most specific first: this language and tool, the
   * language for any tool, the project for this tool, then the project for any tool.
   */
  @Transactional(readOnly = true)
  fun resolve(
    projectId: Long,
    languageId: Long,
    tool: String,
  ): String? {
    val rows = binaryAssetVoiceRepository.findByProjectId(projectId)
    fun pick(
      forLanguage: Boolean,
      forTool: String,
    ) = rows
      .find { row ->
        (if (forLanguage) row.language?.id == languageId else row.language == null) && row.tool == forTool
      }?.voiceId

    return pick(forLanguage = true, forTool = tool)
      ?: pick(forLanguage = true, forTool = BinaryAssetVoice.ANY_TOOL)
      ?: pick(forLanguage = false, forTool = tool)
      ?: pick(forLanguage = false, forTool = BinaryAssetVoice.ANY_TOOL)
  }

  /**
   * @param languageId null targets the project-wide default
   * @param tool [BinaryAssetVoice.ANY_TOOL] targets every tool
   * @param voiceId null or blank clears the entry
   */
  @Transactional
  fun set(
    projectId: Long,
    languageId: Long?,
    tool: String,
    voiceId: String?,
  ): BinaryAssetVoice? {
    val existing =
      binaryAssetVoiceRepository
        .findByProjectId(projectId)
        .find { it.language?.id == languageId && it.tool == tool }

    val trimmed = voiceId?.trim()?.takeIf { it.isNotBlank() }
    if (trimmed == null) {
      existing?.let { binaryAssetVoiceRepository.delete(it) }
      return null
    }

    val entity =
      existing ?: BinaryAssetVoice(entityManager.getReference(Project::class.java, projectId)).apply {
        language = languageId?.let { entityManager.getReference(Language::class.java, it) }
        this.tool = tool
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
