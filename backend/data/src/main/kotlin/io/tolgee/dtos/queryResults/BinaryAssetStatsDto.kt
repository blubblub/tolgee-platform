package io.tolgee.dtos.queryResults

data class BinaryAssetStatsDto(
  val assetCount: Long,
  val currentTranslationCount: Long,
  val outdatedTranslationCount: Long,
)
