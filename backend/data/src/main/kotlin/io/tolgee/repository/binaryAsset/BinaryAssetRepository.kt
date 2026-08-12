package io.tolgee.repository.binaryAsset

import io.tolgee.dtos.queryResults.BinaryAssetProjectStatsProjection
import io.tolgee.dtos.queryResults.BinaryAssetStatsDto
import io.tolgee.model.binaryAsset.BinaryAsset
import jakarta.persistence.LockModeType
import org.springframework.context.annotation.Lazy
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

@Repository
@Lazy
interface BinaryAssetRepository : JpaRepository<BinaryAsset, Long> {
  /**
   * When filterAudio/Video/Image are all false, every type is returned.
   * When any is true, assets matching any selected type are returned (OR).
   * Type is inferred from source contentType and originalFilename.
   *
   * ponytail: an asset with no original has no type to infer, so it shows under every chip rather
   * than disappearing from all of them. Coalesce its translations' content types if that bites.
   */
  @Query(
    """
    from BinaryAsset a
    left join fetch a.sourceLanguage
    left join fetch a.uploadedBy
    left join fetch a.transcriptKey
    where a.project.id = :projectId
      and (:search is null or lower(a.name) like lower(concat('%', cast(:search as string), '%')))
      and (
        (:filterAudio = false and :filterVideo = false and :filterImage = false)
        or a.storageKey is null
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
    order by a.id
    """,
    countQuery = """
    select count(a) from BinaryAsset a
    where a.project.id = :projectId
      and (:search is null or lower(a.name) like lower(concat('%', cast(:search as string), '%')))
      and (
        (:filterAudio = false and :filterVideo = false and :filterImage = false)
        or a.storageKey is null
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
    left join fetch a.transcriptKey
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

  /**
   * Asset totals for a whole page of projects in one query — the project list would otherwise cost
   * a query per project. An asset counts as untranslated when it has fewer current target-language
   * files than the project has target languages.
   */
  @Query(
    nativeQuery = true,
    value = """
    with lang as (
      select l.project_id, count(*) - 1 as target_count
      from language l
      where l.deleted_at is null and l.project_id in :projectIds
      group by l.project_id
    ),
    per_asset as (
      select a.project_id,
             a.id,
             count(t.id) filter (
               where t.language_id <> a.source_language_id
                 and t.source_revision = a.source_revision
             ) as current_count
      from binary_asset a
      left join binary_asset_translation t on t.asset_id = a.id
      where a.project_id in :projectIds
      group by a.project_id, a.id
    )
    select pa.project_id as "projectId",
           count(*) as "assetCount",
           count(*) filter (
             where pa.current_count < greatest(coalesce(l.target_count, 0), 0)
           ) as "untranslatedAssetCount"
    from per_asset pa
    left join lang l on l.project_id = pa.project_id
    group by pa.project_id
    """,
  )
  fun getBinaryAssetStatsByProject(projectIds: Collection<Long>): List<BinaryAssetProjectStatsProjection>

  /**
   * There is no DB-level cascade from key, and deletion is ordered manually in application code,
   * so every key hard-delete path must drop this reference first or the FK blocks the delete.
   */
  @Modifying
  @Query(
    """
    update BinaryAsset a
    set a.transcriptKey = null, a.transcriptKeyOwned = false
    where a.transcriptKey.id in :keyIds
    """,
  )
  fun clearTranscriptKeyByKeyIdIn(keyIds: Collection<Long>)

  @Modifying
  @Query(
    """
    update BinaryAsset a
    set a.transcriptKey = null, a.transcriptKeyOwned = false
    where a.transcriptKey.id in (select k.id from Key k where k.project.id = :projectId)
    """,
  )
  fun clearTranscriptKeyByKeyProjectId(projectId: Long)
}
