package io.tolgee.dtos.queryResults

/**
 * Per-project asset totals for the project list. [untranslatedAssetCount] counts assets that are not
 * done — at least one target language is missing a file or has one made against an older source.
 */
data class BinaryAssetProjectStatsDto(
  val projectId: Long,
  val assetCount: Long,
  val untranslatedAssetCount: Long,
)

/** Native-query row for [BinaryAssetProjectStatsDto]; aliases are quoted to keep the camel case. */
interface BinaryAssetProjectStatsProjection {
  fun getProjectId(): Long

  fun getAssetCount(): Long

  fun getUntranslatedAssetCount(): Long
}
