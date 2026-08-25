package io.tolgee.service.binaryAsset

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.tolgee.component.binaryAssetTool.BinaryAssetToolService
import io.tolgee.constants.Message
import io.tolgee.exceptions.BadRequestException
import io.tolgee.exceptions.NotFoundException
import io.tolgee.model.UserAccount
import io.tolgee.model.binaryAsset.BinaryAsset
import io.tolgee.model.binaryAsset.BinaryAssetTranslation
import io.tolgee.model.binaryAsset.BinaryAssetTranslationVersion
import io.tolgee.repository.binaryAsset.BinaryAssetTranslationRepository
import io.tolgee.repository.binaryAsset.BinaryAssetTranslationVersionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile

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
  fun findByAssetIdIn(assetIds: Collection<Long>): List<BinaryAssetTranslationVersion> =
    if (assetIds.isEmpty()) {
      emptyList()
    } else {
      binaryAssetTranslationVersionRepository.findByAssetIdIn(assetIds)
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
  fun upload(
    projectId: Long,
    assetId: Long,
    languageId: Long,
    file: MultipartFile,
    user: UserAccount?,
  ): BinaryAssetTranslationVersion {
    val target = resolveTarget(projectId, assetId, languageId)

    binaryAssetService.requireStreamingStorage()
    val stored = binaryAssetService.storeNewBlob(projectId, file)
    return try {
      binaryAssetTranslationVersionRepository.saveAndFlush(
        BinaryAssetTranslationVersion(target.asset, target.translation).apply {
          storageKey = stored.storageKey
          originalFilename = binaryAssetService.resolveFilename(file)
          contentType = binaryAssetService.resolveContentType(file)
          byteSize = stored.info.byteSize
          sha256 = stored.info.sha256
          tool = "upload"
          createdBy = user
        },
      )
    } catch (e: Exception) {
      binaryAssetService.deleteBlobBestEffort(stored.storageKey)
      throw e
    }
  }

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
    val target = resolveTarget(projectId, assetId, languageId)
    // the tools all produce audio — for an image or a video that would be a version of nothing
    if (!target.asset.capabilities.pipeline) {
      throw BadRequestException(Message.BINARY_ASSET_TOOL_NOT_SUPPORTED)
    }

    val tool = binaryAssetToolService.getTool(toolName)

    // Null when this lane has no file yet — a source-less asset's source lane. Tools that need
    // bytes reject it; TTS synthesizes from the transcript regardless.
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
        val translation = target.translation
        if (translation != null) {
          binaryAssetService.openByStorageKey(
            translation.storageKey,
            translation.contentType,
            translation.originalFilename,
            translation.byteSize,
          )
        } else {
          target.asset.storageKey?.let {
            binaryAssetService.openByStorageKey(
              it,
              target.asset.contentType!!,
              target.asset.originalFilename!!,
              target.asset.byteSize,
            )
          }
        }
      }

    val context =
      io.tolgee.component.binaryAssetTool.BinaryAssetToolContext(
        asset = target.asset,
        translation = target.translation,
        language = target.language,
        defaultVoiceId = binaryAssetVoiceService.resolve(projectId, languageId, tool.name),
      )

    val output =
      if (input == null) {
        tool.run(input = null, params = params, context = context)
      } else {
        input.inputStream.use { stream ->
          tool.run(
            input = input.copy(inputStream = stream),
            params = params,
            context = context,
          )
        }
      }

    val stored = binaryAssetService.storeBlobBytes(projectId, output.bytes)

    val version =
      BinaryAssetTranslationVersion(target.asset, target.translation).apply {
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
  ) {
    val target = resolveTarget(projectId, assetId, languageId)
    val versions = listVersions(projectId, assetId, languageId)

    // a different final is a different thing to confirm, so the review has to be redone
    target.translation?.let { translation ->
      if (versions.firstOrNull { it.chosen }?.id != versionId) {
        translation.reviewed = false
        binaryAssetTranslationRepository.save(translation)
      }
    }

    // clear before setting, or the "one chosen" index sees two rows mid-flush
    versions.forEach { it.chosen = false }
    binaryAssetTranslationVersionRepository.saveAll(versions)
    binaryAssetTranslationVersionRepository.flush()

    if (versionId != null) {
      val version =
        versions.find { it.id == versionId }
          ?: throw NotFoundException(Message.BINARY_ASSET_VERSION_NOT_FOUND)
      version.chosen = true
      binaryAssetTranslationVersionRepository.save(version)
    }
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
      version.translation?.let { translation ->
        translation.reviewed = false
        binaryAssetTranslationRepository.save(translation)
      }
    }
    binaryAssetTranslationVersionRepository.delete(version)
    binaryAssetTranslationVersionRepository.flush()
    binaryAssetService.deleteBlobBestEffort(storageKey)
  }

  private fun resolveTarget(
    projectId: Long,
    assetId: Long,
    languageId: Long,
  ): VersionTarget {
    val asset = binaryAssetService.get(projectId, assetId)
    if (languageId == asset.sourceLanguage.id) {
      return VersionTarget(asset, null)
    }
    val translation =
      binaryAssetTranslationRepository.findByProjectAssetAndLanguage(projectId, assetId, languageId)
        ?: throw NotFoundException(Message.BINARY_ASSET_TRANSLATION_NOT_FOUND)
    return VersionTarget(asset, translation)
  }

  /**
   * What a language's versions hang off, and which file is their "original". For the asset's source
   * language that is the asset itself — it has no translation row.
   */
  private class VersionTarget(
    val asset: BinaryAsset,
    val translation: BinaryAssetTranslation?,
  ) {
    val language get() = translation?.language ?: asset.sourceLanguage
  }
}
