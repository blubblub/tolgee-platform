package io.tolgee.repository.binaryAsset

import io.tolgee.model.binaryAsset.BinaryAssetTranslationVersion
import org.springframework.context.annotation.Lazy
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

@Repository
@Lazy
interface BinaryAssetTranslationVersionRepository : JpaRepository<BinaryAssetTranslationVersion, Long> {
  @Query(
    """
    from BinaryAssetTranslationVersion v
    join fetch v.translation t
    join fetch t.asset a
    where a.project.id = :projectId and a.id = :assetId and t.language.id = :languageId
    order by v.createdAt asc
    """,
  )
  fun findByProjectAssetAndLanguage(
    projectId: Long,
    assetId: Long,
    languageId: Long,
  ): List<BinaryAssetTranslationVersion>

  @Query(
    """
    from BinaryAssetTranslationVersion v
    join fetch v.translation t
    join fetch t.asset a
    where a.project.id = :projectId and a.id = :assetId and t.language.id = :languageId and v.id = :versionId
    """,
  )
  fun findByProjectAssetLanguageAndVersionId(
    projectId: Long,
    assetId: Long,
    languageId: Long,
    versionId: Long,
  ): BinaryAssetTranslationVersion?

  @Query(
    """
    from BinaryAssetTranslationVersion v
    join fetch v.translation t
    where t.id in :translationIds
    order by v.createdAt asc
    """,
  )
  fun findByTranslationIdIn(translationIds: Collection<Long>): List<BinaryAssetTranslationVersion>
}
