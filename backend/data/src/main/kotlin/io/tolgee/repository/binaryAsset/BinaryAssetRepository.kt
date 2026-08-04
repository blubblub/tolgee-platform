package io.tolgee.repository.binaryAsset

import io.tolgee.dtos.queryResults.BinaryAssetStatsDto
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
  /**
   * When filterAudio/Video/Image are all false, every type is returned.
   * When any is true, assets matching any selected type are returned (OR).
   * Type is inferred from source contentType and originalFilename.
   */
  @Query(
    """
    from BinaryAsset a
    left join fetch a.sourceLanguage
    left join fetch a.uploadedBy
    where a.project.id = :projectId
      and (:search is null or lower(a.name) like lower(concat('%', cast(:search as string), '%')))
      and (
        (:filterAudio = false and :filterVideo = false and :filterImage = false)
        or (
          :filterAudio = true and (
            lower(a.contentType) like 'audio/%'
            or lower(a.originalFilename) like '%.mp3'
            or lower(a.originalFilename) like '%.wav'
            or lower(a.originalFilename) like '%.ogg'
            or lower(a.originalFilename) like '%.m4a'
            or lower(a.originalFilename) like '%.aac'
            or lower(a.originalFilename) like '%.flac'
            or lower(a.originalFilename) like '%.webm'
          )
        )
        or (
          :filterVideo = true and (
            lower(a.contentType) like 'video/%'
            or lower(a.originalFilename) like '%.mp4'
            or lower(a.originalFilename) like '%.mov'
            or lower(a.originalFilename) like '%.m4v'
            or lower(a.originalFilename) like '%.webm'
            or lower(a.originalFilename) like '%.mkv'
            or lower(a.originalFilename) like '%.avi'
          )
        )
        or (
          :filterImage = true and (
            lower(a.contentType) like 'image/%'
            or lower(a.originalFilename) like '%.png'
            or lower(a.originalFilename) like '%.jpg'
            or lower(a.originalFilename) like '%.jpeg'
            or lower(a.originalFilename) like '%.gif'
            or lower(a.originalFilename) like '%.webp'
            or lower(a.originalFilename) like '%.svg'
            or lower(a.originalFilename) like '%.bmp'
          )
        )
      )
    """,
    countQuery = """
    select count(a) from BinaryAsset a
    where a.project.id = :projectId
      and (:search is null or lower(a.name) like lower(concat('%', cast(:search as string), '%')))
      and (
        (:filterAudio = false and :filterVideo = false and :filterImage = false)
        or (
          :filterAudio = true and (
            lower(a.contentType) like 'audio/%'
            or lower(a.originalFilename) like '%.mp3'
            or lower(a.originalFilename) like '%.wav'
            or lower(a.originalFilename) like '%.ogg'
            or lower(a.originalFilename) like '%.m4a'
            or lower(a.originalFilename) like '%.aac'
            or lower(a.originalFilename) like '%.flac'
            or lower(a.originalFilename) like '%.webm'
          )
        )
        or (
          :filterVideo = true and (
            lower(a.contentType) like 'video/%'
            or lower(a.originalFilename) like '%.mp4'
            or lower(a.originalFilename) like '%.mov'
            or lower(a.originalFilename) like '%.m4v'
            or lower(a.originalFilename) like '%.webm'
            or lower(a.originalFilename) like '%.mkv'
            or lower(a.originalFilename) like '%.avi'
          )
        )
        or (
          :filterImage = true and (
            lower(a.contentType) like 'image/%'
            or lower(a.originalFilename) like '%.png'
            or lower(a.originalFilename) like '%.jpg'
            or lower(a.originalFilename) like '%.jpeg'
            or lower(a.originalFilename) like '%.gif'
            or lower(a.originalFilename) like '%.webp'
            or lower(a.originalFilename) like '%.svg'
            or lower(a.originalFilename) like '%.bmp'
          )
        )
      )
    """,
  )
  fun findAllByProjectId(
    projectId: Long,
    search: String?,
    filterAudio: Boolean,
    filterVideo: Boolean,
    filterImage: Boolean,
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

  @Query(
    """
    select new io.tolgee.dtos.queryResults.BinaryAssetStatsDto(
      count(distinct a.id),
      coalesce(sum(case when t.id is not null
        and t.language.id <> a.sourceLanguage.id
        and t.sourceRevision = a.sourceRevision then 1 else 0 end), 0),
      coalesce(sum(case when t.id is not null
        and t.language.id <> a.sourceLanguage.id
        and t.sourceRevision <> a.sourceRevision then 1 else 0 end), 0)
    )
    from BinaryAsset a
    left join a.translations t
    where a.project.id = :projectId
    """,
  )
  fun getBinaryAssetStats(projectId: Long): BinaryAssetStatsDto
}
