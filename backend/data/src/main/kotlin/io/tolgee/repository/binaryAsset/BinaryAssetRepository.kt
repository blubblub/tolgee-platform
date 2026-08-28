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
   * Type is inferred from the source file, or — with no original — from the oldest localized file
   * that has one, the same way [io.tolgee.service.binaryAsset.mediaType] does. An asset with no
   * recognised file at all has no type and matches no chip.
   */
  @Query(
    "from BinaryAsset a " +
      "left join fetch a.sourceLanguage " +
      "left join fetch a.uploadedBy " +
      "left join fetch a.transcriptKey " +
      "where a.project.id = :projectId " +
      MEDIA_TYPE_FILTER +
      " order by a.id",
    countQuery =
      "select count(a) from BinaryAsset a " +
        "where a.project.id = :projectId " +
        MEDIA_TYPE_FILTER,
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

  fun findAllByProjectIdAndNameIn(
    projectId: Long,
    names: Collection<String>,
  ): List<BinaryAsset>

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

// The extension lists mirror BinaryAssetMediaType.infer — JPQL cannot share Kotlin constants.
// A source-less asset has null type and filename; coalesce keeps `not (...)` a real boolean.
private const val SRC_TYPE = "lower(coalesce(a.contentType, ''))"
private const val SRC_NAME = "lower(coalesce(a.originalFilename, ''))"
private const val SOURCE_AUDIO =
  "($SRC_TYPE like 'audio/%' or $SRC_NAME like '%.mp3' or $SRC_NAME like '%.wav' or " +
    "$SRC_NAME like '%.ogg' or $SRC_NAME like '%.m4a' or $SRC_NAME like '%.aac' or " +
    "$SRC_NAME like '%.flac' or $SRC_NAME like '%.webm' or $SRC_NAME like '%.opus')"
private const val SOURCE_VIDEO =
  "($SRC_TYPE like 'video/%' or $SRC_NAME like '%.mp4' or $SRC_NAME like '%.mov' or " +
    "$SRC_NAME like '%.m4v' or $SRC_NAME like '%.mkv' or $SRC_NAME like '%.avi')"
private const val SOURCE_IMAGE =
  "($SRC_TYPE like 'image/%' or $SRC_NAME like '%.png' or $SRC_NAME like '%.jpg' or " +
    "$SRC_NAME like '%.jpeg' or $SRC_NAME like '%.gif' or $SRC_NAME like '%.webp' or " +
    "$SRC_NAME like '%.svg' or $SRC_NAME like '%.bmp')"

private const val LANE_AUDIO =
  "(lower(t.contentType) like 'audio/%' or lower(t.originalFilename) like '%.mp3' or " +
    "lower(t.originalFilename) like '%.wav' or lower(t.originalFilename) like '%.ogg' or " +
    "lower(t.originalFilename) like '%.m4a' or lower(t.originalFilename) like '%.aac' or " +
    "lower(t.originalFilename) like '%.flac' or lower(t.originalFilename) like '%.webm' or " +
    "lower(t.originalFilename) like '%.opus')"
private const val LANE_VIDEO =
  "(lower(t.contentType) like 'video/%' or lower(t.originalFilename) like '%.mp4' or " +
    "lower(t.originalFilename) like '%.mov' or lower(t.originalFilename) like '%.m4v' or " +
    "lower(t.originalFilename) like '%.mkv' or lower(t.originalFilename) like '%.avi')"
private const val LANE_IMAGE =
  "(lower(t.contentType) like 'image/%' or lower(t.originalFilename) like '%.png' or " +
    "lower(t.originalFilename) like '%.jpg' or lower(t.originalFilename) like '%.jpeg' or " +
    "lower(t.originalFilename) like '%.gif' or lower(t.originalFilename) like '%.webp' or " +
    "lower(t.originalFilename) like '%.svg' or lower(t.originalFilename) like '%.bmp')"

/** Any older lane (`x`) of the same asset that already has a recognised type — it decides, not `t`. */
private const val OLDER_TYPED_LANE =
  "exists (select x.id from BinaryAssetTranslation x where x.asset.id = a.id and x.id < t.id and (" +
    "lower(x.contentType) like 'audio/%' or lower(x.contentType) like 'video/%' or " +
    "lower(x.contentType) like 'image/%' or lower(x.originalFilename) like '%.mp3' or " +
    "lower(x.originalFilename) like '%.wav' or lower(x.originalFilename) like '%.ogg' or " +
    "lower(x.originalFilename) like '%.m4a' or lower(x.originalFilename) like '%.aac' or " +
    "lower(x.originalFilename) like '%.flac' or lower(x.originalFilename) like '%.webm' or " +
    "lower(x.originalFilename) like '%.opus' or lower(x.originalFilename) like '%.mp4' or " +
    "lower(x.originalFilename) like '%.mov' or lower(x.originalFilename) like '%.m4v' or " +
    "lower(x.originalFilename) like '%.mkv' or lower(x.originalFilename) like '%.avi' or " +
    "lower(x.originalFilename) like '%.png' or lower(x.originalFilename) like '%.jpg' or " +
    "lower(x.originalFilename) like '%.jpeg' or lower(x.originalFilename) like '%.gif' or " +
    "lower(x.originalFilename) like '%.webp' or lower(x.originalFilename) like '%.svg' or " +
    "lower(x.originalFilename) like '%.bmp'))"

/** The original says nothing about the type (missing or not a media file), so the lanes decide. */
private const val SOURCE_UNTYPED = "not ($SOURCE_AUDIO or $SOURCE_VIDEO or $SOURCE_IMAGE)"

private const val LANE_FALLBACK_AUDIO =
  "($SOURCE_UNTYPED and exists (select t.id from BinaryAssetTranslation t where t.asset.id = a.id and " +
    "$LANE_AUDIO and not $OLDER_TYPED_LANE))"
private const val LANE_FALLBACK_VIDEO =
  "($SOURCE_UNTYPED and exists (select t.id from BinaryAssetTranslation t where t.asset.id = a.id and " +
    "$LANE_VIDEO and not $OLDER_TYPED_LANE))"
private const val LANE_FALLBACK_IMAGE =
  "($SOURCE_UNTYPED and exists (select t.id from BinaryAssetTranslation t where t.asset.id = a.id and " +
    "$LANE_IMAGE and not $OLDER_TYPED_LANE))"

/**
 * Media-type filter shared by the page and count queries; see [BinaryAssetRepository.findAllByProjectId].
 *
 * Resolution mirrors `BinaryAsset.mediaType`: the original file's type wins; when there is no
 * original (or it is not a recognised media file) the asset takes the type of its oldest localized
 * file that has one. An asset with no recognised file at all has no type and matches no filter.
 */
private const val MEDIA_TYPE_FILTER =
  "and (:search is null or lower(a.name) like lower(concat('%', cast(:search as string), '%'))) and " +
    "((:filterAudio = false and :filterVideo = false and :filterImage = false) or " +
    "(:filterAudio = true and ($SOURCE_AUDIO or $LANE_FALLBACK_AUDIO)) or " +
    "(:filterVideo = true and ($SOURCE_VIDEO or $LANE_FALLBACK_VIDEO)) or " +
    "(:filterImage = true and ($SOURCE_IMAGE or $LANE_FALLBACK_IMAGE)))"
