package io.tolgee.repository.binaryAsset

import io.tolgee.model.binaryAsset.BinaryAsset
import jakarta.persistence.LockModeType
import org.springframework.context.annotation.Lazy
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

@Repository
@Lazy
interface BinaryAssetRepository : JpaRepository<BinaryAsset, Long> {
  @Query(
    """
    from BinaryAsset a
    left join fetch a.sourceLanguage
    left join fetch a.uploadedBy
    where a.project.id = :projectId
      and (:search is null or lower(a.name) like lower(concat('%', cast(:search as string), '%')))
    """,
    countQuery = """
    select count(a) from BinaryAsset a
    where a.project.id = :projectId
      and (:search is null or lower(a.name) like lower(concat('%', cast(:search as string), '%')))
    """,
  )
  fun findAllByProjectId(
    projectId: Long,
    search: String?,
    pageable: Pageable,
  ): Page<BinaryAsset>

  @Query(
    """
    from BinaryAsset a
    left join fetch a.sourceLanguage
    left join fetch a.uploadedBy
    left join fetch a.translations t
    left join fetch t.language
    left join fetch t.uploadedBy
    where a.id = :assetId and a.project.id = :projectId
    """,
  )
  fun findByProjectIdAndId(
    projectId: Long,
    assetId: Long,
  ): BinaryAsset?

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("from BinaryAsset a where a.id = :assetId and a.project.id = :projectId")
  fun findByProjectIdAndIdForUpdate(
    projectId: Long,
    assetId: Long,
  ): BinaryAsset?

  fun existsByProjectIdAndName(
    projectId: Long,
    name: String,
  ): Boolean

  fun existsByProjectIdAndNameAndIdNot(
    projectId: Long,
    name: String,
    id: Long,
  ): Boolean

  fun existsBySourceLanguageId(languageId: Long): Boolean

  @Query("from BinaryAsset a where a.project.id = :projectId")
  fun findAllByProjectId(projectId: Long): List<BinaryAsset>

  @Query(
    """
    from BinaryAsset a
    left join fetch a.sourceLanguage
    left join fetch a.uploadedBy
    left join fetch a.translations t
    left join fetch t.language
    left join fetch t.uploadedBy
    where a.id in :ids
    """,
  )
  fun findAllWithDetailsByIdIn(ids: Collection<Long>): List<BinaryAsset>

  @Query(
    """
    select a from BinaryAsset a
    join fetch a.translations t
    where t.language.id = :languageId
    """,
  )
  fun findAllWithTranslationsByLanguageId(languageId: Long): List<BinaryAsset>
}
