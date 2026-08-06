package io.tolgee.service.binaryAsset

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.tolgee.component.binaryAssetTool.BinaryAssetToolService
import io.tolgee.constants.Message
import io.tolgee.exceptions.NotFoundException
import io.tolgee.model.UserAccount
import io.tolgee.model.binaryAsset.BinaryAssetTranslation
import io.tolgee.model.binaryAsset.BinaryAssetTranslationVersion
import io.tolgee.repository.binaryAsset.BinaryAssetTranslationRepository
import io.tolgee.repository.binaryAsset.BinaryAssetTranslationVersionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class BinaryAssetTranslationVersionService(
  private val binaryAssetTranslationRepository: BinaryAssetTranslationRepository,
  private val binaryAssetTranslationVersionRepository: BinaryAssetTranslationVersionRepository,
  private val binaryAssetService: BinaryAssetService,
  private val binaryAssetToolService: BinaryAssetToolService,
  private val binaryAssetVoiceService: BinaryAssetVoiceService,
) {
  @Transactional(readOnly = true)
  fun listVersions(
    projectId: Long,
    assetId: Long,
    languageId: Long,
  ): List<BinaryAssetTranslationVersion> =
    binaryAssetTranslationVersionRepository.findByProjectAssetAndLanguage(projectId, assetId, languageId)

  @Transactional(readOnly = true)
  fun findByTranslationIdIn(translationIds: Collection<Long>): List<BinaryAssetTranslationVersion> =
    if (translationIds.isEmpty()) {
      emptyList()
    } else {
      binaryAssetTranslationVersionRepository.findByTranslationIdIn(translationIds)
    }

  @Transactional(readOnly = true)
  fun getVersion(
    projectId: Long,
    assetId: Long,
    languageId: Long,
    versionId: Long,
  ): BinaryAssetTranslationVersion =
    binaryAssetTranslationVersionRepository.findByProjectAssetLanguageAndVersionId(
      projectId,
      assetId,
      languageId,
      versionId,
    )
      ?: throw NotFoundException(Message.BINARY_ASSET_VERSION_NOT_FOUND)

  @Transactional
  fun runTool(
    projectId: Long,
    assetId: Long,
    languageId: Long,
    toolName: String,
    params: Map<String, Any?>,
    baseVersionId: Long?,
    user: UserAccount?,
  ): BinaryAssetTranslationVersion {
    val translation =
      binaryAssetTranslationRepository.findByProjectAssetAndLanguage(projectId, assetId, languageId)
        ?: throw NotFoundException(Message.BINARY_ASSET_TRANSLATION_NOT_FOUND)

    val tool = binaryAssetToolService.getTool(toolName)

    val input =
      if (baseVersionId != null) {
        val baseVersion = getVersion(projectId, assetId, languageId, baseVersionId)
        binaryAssetService.openByStorageKey(
          baseVersion.storageKey,
          baseVersion.contentType,
          baseVersion.originalFilename,
          baseVersion.byteSize,
        )
      } else {
        binaryAssetService.openByStorageKey(
          translation.storageKey,
          translation.contentType,
          translation.originalFilename,
          translation.byteSize,
        )
      }

    val context =
      io.tolgee.component.binaryAssetTool.BinaryAssetToolContext(
        asset = translation.asset,
        translation = translation,
        language = translation.language,
        defaultVoiceId = binaryAssetVoiceService.resolve(projectId, languageId),
      )

    val output =
      input.inputStream.use { stream ->
        tool.run(
          input =
            io.tolgee.service.binaryAsset.BinaryAssetService.FileStream(
              inputStream = stream,
              contentType = input.contentType,
              filename = input.filename,
              byteSize = input.byteSize,
              storageKey = input.storageKey,
            ),
          params = params,
          context = context,
        )
      }

    val stored = binaryAssetService.storeBlobBytes(projectId, output.bytes)

    val version =
      BinaryAssetTranslationVersion(translation).apply {
        this.storageKey = stored.storageKey
        this.originalFilename = output.filename
        this.contentType = output.contentType
        this.byteSize = stored.info.byteSize
        this.sha256 = stored.info.sha256
        this.tool = tool.name
        this.toolParams = if (params.isNotEmpty()) jacksonObjectMapper().writeValueAsString(params) else null
        this.createdBy = user
      }
    return binaryAssetTranslationVersionRepository.save(version)
  }

  @Transactional
  fun setChosen(
    projectId: Long,
    assetId: Long,
    languageId: Long,
    versionId: Long?,
  ): BinaryAssetTranslation {
    val translation =
      binaryAssetTranslationRepository.findByProjectAssetAndLanguage(projectId, assetId, languageId)
        ?: throw NotFoundException(Message.BINARY_ASSET_TRANSLATION_NOT_FOUND)

    // a different final is a different thing to confirm, so the review has to be redone
    val previousChosenId = translation.versions.firstOrNull { it.chosen }?.id
    if (previousChosenId != versionId) {
      translation.reviewed = false
    }

    translation.versions.forEach { it.chosen = false }

    if (versionId != null) {
      val version = getVersion(projectId, assetId, languageId, versionId)
      version.chosen = true
      binaryAssetTranslationVersionRepository.save(version)
    }

    return binaryAssetTranslationRepository.save(translation)
  }

  @Transactional
  fun deleteVersion(
    projectId: Long,
    assetId: Long,
    languageId: Long,
    versionId: Long,
  ) {
    val version = getVersion(projectId, assetId, languageId, versionId)
    val storageKey = version.storageKey
    if (version.chosen) {
      // final falls back to the original upload — a different file than the one confirmed
      val translation = version.translation
      translation.reviewed = false
      binaryAssetTranslationRepository.save(translation)
    }
    binaryAssetTranslationVersionRepository.delete(version)
    binaryAssetTranslationVersionRepository.flush()
    binaryAssetService.deleteBlobBestEffort(storageKey)
  }
}
