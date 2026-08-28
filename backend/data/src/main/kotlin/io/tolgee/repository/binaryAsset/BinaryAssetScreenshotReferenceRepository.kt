package io.tolgee.repository.binaryAsset

import io.tolgee.model.binaryAsset.BinaryAssetScreenshotReference
import org.springframework.context.annotation.Lazy
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

@Repository
@Lazy
interface BinaryAssetScreenshotReferenceRepository : JpaRepository<BinaryAssetScreenshotReference, Long> {
  @Query(
    """
    from BinaryAssetScreenshotReference r
    join fetch r.screenshot s
    left join fetch s.keyScreenshotReferences ksr
    left join fetch ksr.key k
    where r.asset.id in :assetIds
    order by r.asset.id, s.id
  """,
  )
  fun findAllByAssetIdIn(assetIds: Collection<Long>): List<BinaryAssetScreenshotReference>

  @Query(
    """
    from BinaryAssetScreenshotReference r
    join fetch r.asset a
    where r.asset.id = :assetId and r.screenshot.id in :screenshotIds
  """,
  )
  fun findAllByAssetIdAndScreenshotIdIn(
    assetId: Long,
    screenshotIds: Collection<Long>,
  ): List<BinaryAssetScreenshotReference>

  @Query(
    """
    from BinaryAssetScreenshotReference r
    join fetch r.asset a
    where r.screenshot.id in :screenshotIds
  """,
  )
  fun findAllByScreenshotIdIn(screenshotIds: Collection<Long>): List<BinaryAssetScreenshotReference>

  @Query(
    """
    select distinct r.screenshot.id from BinaryAssetScreenshotReference r
    where r.screenshot.id in :screenshotIds
  """,
  )
  fun findReferencedScreenshotIds(screenshotIds: Collection<Long>): Set<Long>

  @Query("select count(r) from BinaryAssetScreenshotReference r where r.asset.id = :assetId")
  fun countByAssetId(assetId: Long): Long
}
