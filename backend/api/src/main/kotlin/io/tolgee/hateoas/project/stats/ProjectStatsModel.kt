package io.tolgee.hateoas.project.stats

@Suppress("unused")
open class ProjectStatsModel(
  val projectId: Long,
  val languageCount: Int,
  val keyCount: Long,
  val taskCount: Long,
  val baseWordsCount: Long,
  val translatedPercentage: Double,
  val reviewedPercentage: Double,
  val membersCount: Long,
  val tagCount: Long,
  val binaryAssetCount: Long,
  val currentBinaryAssetTranslationCount: Long,
  val outdatedBinaryAssetTranslationCount: Long,
  val missingBinaryAssetTranslationCount: Long,
  val languageStats: List<LanguageStatsModel>,
)
