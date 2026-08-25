package io.tolgee.service.binaryAsset

import io.tolgee.constants.Message
import io.tolgee.exceptions.NotFoundException
import io.tolgee.model.Screenshot
import io.tolgee.model.binaryAsset.BinaryAsset
import io.tolgee.repository.binaryAsset.BinaryAssetRepository
import io.tolgee.service.key.ScreenshotService
import org.springframework.core.io.InputStreamSource
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Screens a binary asset is used on. Each operation is one transaction that loads the asset,
 * changes the links and hands back screenshots ready for the model — the controller and the MCP
 * tools stay free of session handling.
 */
@Service
class BinaryAssetScreenshotService(
  private val binaryAssetRepository: BinaryAssetRepository,
  private val screenshotService: ScreenshotService,
) {
  @Transactional(readOnly = true)
  fun list(
    projectId: Long,
    assetId: Long,
  ): List<Screenshot> = screenshotService.findAll(asset(projectId, assetId))

  /** Stores a new screenshot and links it to the asset. */
  @Transactional
  fun upload(
    projectId: Long,
    assetId: Long,
    image: InputStreamSource,
    location: String?,
  ): Screenshot {
    val asset = asset(projectId, assetId)
    val screenshot = screenshotService.storeNew(image, location).screenshot
    screenshotService.addAssetReference(asset, screenshot)
    return screenshot.also { screenshotService.initializeReferences(listOf(it)) }
  }

  /** Links a screenshot this project already has — attached to a key or another asset. */
  @Transactional
  fun link(
    projectId: Long,
    assetId: Long,
    screenshotId: Long,
  ): Screenshot {
    val asset = asset(projectId, assetId)
    val screenshot = screenshotService.get(screenshotId)
    screenshotService.checkInProject(screenshot, projectId)
    screenshotService.addAssetReference(asset, screenshot)
    return screenshot.also { screenshotService.initializeReferences(listOf(it)) }
  }

  /** Drops the links; a screenshot nothing else references is deleted with its files. */
  @Transactional
  fun unlink(
    projectId: Long,
    assetId: Long,
    screenshotIds: Collection<Long>,
  ) {
    val asset = asset(projectId, assetId)
    screenshotService.findByIdIn(screenshotIds).forEach { screenshotService.checkInProject(it, projectId) }
    screenshotService.removeAssetReferences(asset, screenshotIds)
  }

  private fun asset(
    projectId: Long,
    assetId: Long,
  ): BinaryAsset =
    binaryAssetRepository.findByProjectIdAndId(projectId, assetId)
      ?: throw NotFoundException(Message.BINARY_ASSET_NOT_FOUND)
}
