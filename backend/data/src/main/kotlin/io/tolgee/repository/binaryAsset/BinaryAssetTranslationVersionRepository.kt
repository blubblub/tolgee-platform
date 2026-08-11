package io.tolgee.repository.binaryAsset

import io.tolgee.model.binaryAsset.BinaryAssetTranslationVersion
import org.springframework.context.annotation.Lazy
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

@Repository
@Lazy
interface BinaryAssetTranslationVersionRepository : JpaRepository<BinaryAssetTranslationVersion, Long> {
  /**
   * Versions of one language's file. The asset's source language has no translation row, so its
   * versions are the ones hanging straight off the asset.
   */
  @Query(
    """
    from BinaryAssetTranslationVersion v
    join fetch v.asset a
    left join fetch v.translation t
    left join t.language tl
    where a.project.id = :projectId and a.id = :assetId
      and (tl.id = :languageId or (t is null and a.sourceLanguage.id = :languageId))
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
    join fetch v.asset a
    left join fetch v.translation t
    left join t.language tl
    where a.project.id = :projectId and a.id = :assetId and v.id = :versionId
      and (tl.id = :languageId or (t is null and a.sourceLanguage.id = :languageId))
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
    left join fetch v.translation t
    where v.asset.id in :assetIds
    order by v.createdAt asc
    """,
  )
  fun findByAssetIdIn(assetIds: Collection<Long>): List<BinaryAssetTranslationVersion>
}
