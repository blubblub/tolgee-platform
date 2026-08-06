package io.tolgee.repository.binaryAsset

import io.tolgee.model.binaryAsset.BinaryAssetVoice
import org.springframework.context.annotation.Lazy
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

@Repository
@Lazy
interface BinaryAssetVoiceRepository : JpaRepository<BinaryAssetVoice, Long> {
  @Query(
    """
    from BinaryAssetVoice v
    left join fetch v.language
    where v.project.id = :projectId
    """,
  )
  fun findByProjectId(projectId: Long): List<BinaryAssetVoice>

  @Modifying
  @Query("delete from BinaryAssetVoice v where v.project.id = :projectId")
  fun deleteAllByProjectId(projectId: Long)

  @Modifying
  @Query("delete from BinaryAssetVoice v where v.language.id = :languageId")
  fun deleteAllByLanguageId(languageId: Long)
}
