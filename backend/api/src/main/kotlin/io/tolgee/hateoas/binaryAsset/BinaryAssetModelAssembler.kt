package io.tolgee.hateoas.binaryAsset

import io.tolgee.api.v2.controllers.binaryAsset.BinaryAssetController
import io.tolgee.dtos.cacheable.LanguageDto
import io.tolgee.model.binaryAsset.BinaryAsset
import io.tolgee.model.binaryAsset.BinaryAssetTranslation
import io.tolgee.model.enums.BinaryAssetTranslationStatus
import io.tolgee.service.binaryAsset.BinaryAssetService
import io.tolgee.service.binaryAsset.BinaryAssetTranscriptService
import org.springframework.hateoas.server.mvc.RepresentationModelAssemblerSupport
import org.springframework.stereotype.Component

@Component
class BinaryAssetModelAssembler(
  private val binaryAssetService: BinaryAssetService,
  private val binaryAssetTranscriptService: BinaryAssetTranscriptService,
) : RepresentationModelAssemblerSupport<BinaryAsset, BinaryAssetModel>(
    BinaryAssetController::class.java,
    BinaryAssetModel::class.java,
  ) {
  fun toListModel(
    asset: BinaryAsset,
    targetLanguages: Collection<LanguageDto>,
    visibleLanguageIds: Set<Long>?,
    transcriptSourceText: String? = null,
  ): BinaryAssetModel {
    val byLang = asset.translations.associateBy { it.language.id }
    val visibleTargets =
      targetLanguages.filter { lang ->
        lang.id != asset.sourceLanguage.id &&
          (visibleLanguageIds == null || lang.id in visibleLanguageIds)
      }
    var current = 0
    var outdated = 0
    visibleTargets.forEach { lang ->
      when (binaryAssetService.statusFor(asset, lang.id, byLang[lang.id])) {
        BinaryAssetTranslationStatus.CURRENT -> current++
        BinaryAssetTranslationStatus.OUTDATED -> outdated++
        BinaryAssetTranslationStatus.MISSING -> {}
      }
    }
    return baseModel(
      asset,
      current,
      outdated,
      visibleTargets.size,
      null,
      transcriptSourceText,
      binaryAssetTranscriptService.canTranscribe(asset),
    )
  }

  fun toDetailModel(
    asset: BinaryAsset,
    targetLanguages: Collection<LanguageDto>,
    visibleLanguageIds: Set<Long>?,
    transcripts: Map<Long, BinaryAssetTranscriptService.TranscriptText> = emptyMap(),
  ): BinaryAssetModel {
    val byLang = asset.translations.associateBy { it.language.id }
    val visibleTargets =
      targetLanguages.filter { lang ->
        lang.id != asset.sourceLanguage.id &&
          (visibleLanguageIds == null || lang.id in visibleLanguageIds)
      }
    val translationModels =
      visibleTargets.map { lang ->
        toTranslationModel(
          asset,
          lang,
          byLang[lang.id],
          transcripts[lang.id],
          binaryAssetTranscriptService.canTranscribe(asset),
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
    return baseModel(
      asset,
      current,
      outdated,
      visibleTargets.size,
      translationModels,
      transcripts[asset.sourceLanguage.id]?.text,
      binaryAssetTranscriptService.canTranscribe(asset),
    )
  }

  private fun toTranslationModel(
    asset: BinaryAsset,
    language: LanguageDto,
    translation: BinaryAssetTranslation?,
    transcript: BinaryAssetTranscriptService.TranscriptText?,
    assetTranscribable: Boolean,
  ): BinaryAssetTranslationModel {
    val status = binaryAssetService.statusFor(asset, language.id, translation)
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
      // only meaningful when this language actually has its own audio
      transcriptionAvailable = assetTranscribable && status != BinaryAssetTranslationStatus.MISSING,
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
  ): BinaryAssetModel =
    BinaryAssetModel(
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
      transcriptKeyId = asset.transcriptKey?.id,
      transcriptKeyName = asset.transcriptKey?.name,
      transcriptKeyOwned = asset.transcriptKeyOwned,
      transcriptKeyDeleted = asset.transcriptKey?.deletedAt != null,
      transcriptSourceText = transcriptSourceText,
      transcriptionAvailable = transcriptionAvailable,
      translations = translations,
    )

  override fun toModel(entity: BinaryAsset): BinaryAssetModel {
    return baseModel(entity, 0, 0, 0, null)
  }
}
