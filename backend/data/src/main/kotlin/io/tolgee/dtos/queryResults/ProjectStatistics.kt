package io.tolgee.dtos.queryResults

import io.tolgee.model.enums.TranslationState
import java.math.BigDecimal

data class ProjectStatistics(
  val projectId: Long,
  val keyCount: Long,
  val languageCount: Long,
  val translationStatePercentages: Map<TranslationState, BigDecimal>,
  val qaIssueCount: Long,
  val qaChecksStaleCount: Long,
  /** Binary assets in the project; 0 when the project has none. */
  val assetCount: Long = 0,
  /** Assets still missing a current file in at least one target language. */
  val untranslatedAssetCount: Long = 0,
)
