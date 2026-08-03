package io.tolgee.repository.binaryAsset

import io.tolgee.model.binaryAsset.BinaryAssetTranslation
import org.springframework.context.annotation.Lazy
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

@Repository
@Lazy
interface BinaryAssetTranslationRepository : JpaRepository<BinaryAssetTranslation, Long> {
  fun findByAssetIdAndLanguageId(
    assetId: Long,
    languageId: Long,
  ): BinaryAssetTranslation?

  @Query(
    """
    from BinaryAssetTranslation t
    join fetch t.asset a
    join fetch t.language
    where a.project.id = :projectId and a.id = :assetId and t.language.id = :languageId
    """,
  )
  fun findByProjectAssetAndLanguage(
    projectId: Long,
    assetId: Long,
    languageId: Long,
  ): BinaryAssetTranslation?

  fun deleteAllByLanguageId(languageId: Long)

  @Query("from BinaryAssetTranslation t where t.language.id = :languageId")
  fun findAllByLanguageId(languageId: Long): List<BinaryAssetTranslation>
}
