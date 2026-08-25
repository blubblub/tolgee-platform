package io.tolgee.hateoas.binaryAsset

import io.tolgee.api.v2.controllers.binaryAsset.BinaryAssetController
import io.tolgee.dtos.cacheable.LanguageDto
import io.tolgee.model.binaryAsset.BinaryAsset
import io.tolgee.model.binaryAsset.BinaryAssetTranslation
import io.tolgee.model.binaryAsset.BinaryAssetTranslationVersion
import io.tolgee.model.enums.BinaryAssetTranslationStatus
import io.tolgee.service.binaryAsset.BinaryAssetService
import io.tolgee.service.binaryAsset.BinaryAssetTranscriptService
import io.tolgee.service.binaryAsset.BinaryAssetTranslationVersionService
import io.tolgee.service.binaryAsset.capabilities
import io.tolgee.service.binaryAsset.mediaType
import org.springframework.hateoas.server.mvc.RepresentationModelAssemblerSupport
import org.springframework.stereotype.Component

@Component
class BinaryAssetModelAssembler(
  private val binaryAssetService: BinaryAssetService,
  private val binaryAssetTranscriptService: BinaryAssetTranscriptService,
  private val binaryAssetTranslationVersionService: BinaryAssetTranslationVersionService,
) : RepresentationModelAssemblerSupport<BinaryAsset, BinaryAssetModel>(
    BinaryAssetController::class.java,
    BinaryAssetModel::class.java,
  ) {
  /**
   * List rows carry their localized files too — the assets page edits them in place. Versions are
   * pre-fetched for the whole page so listing costs one version query, not one per asset.
   *
   * [targetLanguages] must already be the caller's permitted view languages: a translator scoped to
   * one language must not see the other languages' files here either.
   */
  fun toListModel(
    asset: BinaryAsset,
    targetLanguages: Collection<LanguageDto>,
    transcripts: Map<Long, BinaryAssetTranscriptService.TranscriptText> = emptyMap(),
    versionsByAsset: Map<Long, List<BinaryAssetTranslationVersion>> = emptyMap(),
  ): BinaryAssetModel = build(asset, targetLanguages, transcripts, versionsByAsset[asset.id].orEmpty())

  fun toDetailModel(
    asset: BinaryAsset,
    targetLanguages: Collection<LanguageDto>,
    transcripts: Map<Long, BinaryAssetTranscriptService.TranscriptText> = emptyMap(),
  ): BinaryAssetModel =
    build(
      asset,
      targetLanguages,
      transcripts,
      binaryAssetTranslationVersionService.findByAssetIdIn(listOf(asset.id)),
    )

  private fun build(
    asset: BinaryAsset,
    targetLanguages: Collection<LanguageDto>,
    transcripts: Map<Long, BinaryAssetTranscriptService.TranscriptText>,
    versions: List<BinaryAssetTranslationVersion>,
  ): BinaryAssetModel {
    // source-file versions have no translation, so they land under the null key
    val versionsByTranslation = versions.groupBy { it.translation?.id }
    val byLang = asset.translations.associateBy { it.language.id }
    val visibleTargets = targetLanguages.filter { lang -> lang.id != asset.sourceLanguage.id }
    val translationModels =
      visibleTargets.map { lang ->
        toTranslationModel(
          asset,
          lang,
          byLang[lang.id],
          transcripts[lang.id],
          binaryAssetTranscriptService.canTranscribe(asset),
          // a language with no translation row has no versions — it must not read the null (source) bucket
          byLang[lang.id]?.let { versionsByTranslation[it.id] }.orEmpty(),
        )
      }
    var current = 0
    var outdated = 0
    translationModels.forEach {
      when (it.status) {
        BinaryAssetTranslationStatus.CURRENT -> current++
        BinaryAssetTranslationStatus.OUTDATED -> outdated++
        BinaryAssetTranslationStatus.MISSING -> {}
      }
    }
    val sourceVersions = versionsByTranslation[null].orEmpty()
    return baseModel(
      asset,
      current,
      outdated,
      visibleTargets.size,
      translationModels,
      transcripts[asset.sourceLanguage.id]?.text,
      binaryAssetTranscriptService.canTranscribe(asset),
      sourceVersions,
    )
  }

  private fun toTranslationModel(
    asset: BinaryAsset,
    language: LanguageDto,
    translation: BinaryAssetTranslation?,
    transcript: BinaryAssetTranscriptService.TranscriptText?,
    assetTranscribable: Boolean,
    versions: List<BinaryAssetTranslationVersion> = emptyList(),
  ): BinaryAssetTranslationModel {
    val status = binaryAssetService.statusFor(asset, language.id, translation)
    val chosen = versions.firstOrNull { it.chosen }
    return BinaryAssetTranslationModel(
      languageId = language.id,
      languageTag = language.tag,
      languageName = language.name,
      status = status,
      sourceRevision = translation?.sourceRevision,
      originalFilename = translation?.originalFilename,
      contentType = translation?.contentType,
      byteSize = translation?.byteSize,
      sha256 = translation?.sha256,
      uploadedById = translation?.uploadedBy?.id,
      updatedAt = translation?.updatedAt,
      transcriptText = transcript?.text,
      transcriptState = transcript?.state,
      transcriptionAvailable = assetTranscribable && status != BinaryAssetTranslationStatus.MISSING,
      reviewed = translation?.reviewed ?: false,
      chosenVersionId = chosen?.id,
      chosenVersionFilename = chosen?.originalFilename,
      chosenVersionTool = chosen?.tool,
      versionCount = versions.size,
    )
  }

  private fun baseModel(
    asset: BinaryAsset,
    current: Int,
    outdated: Int,
    targetCount: Int,
    translations: List<BinaryAssetTranslationModel>?,
    transcriptSourceText: String? = null,
    transcriptionAvailable: Boolean = false,
    sourceVersions: List<BinaryAssetTranslationVersion> = emptyList(),
  ): BinaryAssetModel {
    val chosenSource = sourceVersions.firstOrNull { it.chosen }
    return BinaryAssetModel(
      id = asset.id,
      name = asset.name,
      description = asset.description,
      sourceLanguageId = asset.sourceLanguage.id,
      sourceLanguageTag = asset.sourceLanguage.tag,
      sourceRevision = asset.sourceRevision,
      originalFilename = asset.originalFilename,
      contentType = asset.contentType,
      byteSize = asset.byteSize,
      sha256 = asset.sha256,
      uploadedById = asset.uploadedBy?.id,
      createdAt = asset.createdAt,
      updatedAt = asset.updatedAt,
      currentCount = current,
      outdatedCount = outdated,
      targetLanguageCount = targetCount,
      mediaType = asset.mediaType,
      capabilities = BinaryAssetCapabilitiesModel.of(asset.capabilities),
      transcriptKeyId = asset.transcriptKey?.id,
      transcriptKeyName = asset.transcriptKey?.name,
      transcriptKeyOwned = asset.transcriptKeyOwned,
      transcriptKeyDeleted = asset.transcriptKey?.deletedAt != null,
      transcriptSourceText = transcriptSourceText,
      transcriptionAvailable = transcriptionAvailable,
      chosenVersionId = chosenSource?.id,
      chosenVersionFilename = chosenSource?.originalFilename,
      chosenVersionTool = chosenSource?.tool,
      versionCount = sourceVersions.size,
      translations = translations,
    )
  }

  override fun toModel(entity: BinaryAsset): BinaryAssetModel {
    return baseModel(entity, 0, 0, 0, null)
  }
}
