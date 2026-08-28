/*
 * Copyright (c) 2020. Tolgee
 */

package io.tolgee.service.key

import io.tolgee.component.fileStorage.FileStorage
import io.tolgee.configuration.tolgee.TolgeeProperties
import io.tolgee.constants.Message
import io.tolgee.dtos.CreateScreenshotResult
import io.tolgee.dtos.request.ScreenshotInfoDto
import io.tolgee.dtos.request.key.KeyScreenshotDto
import io.tolgee.exceptions.BadRequestException
import io.tolgee.exceptions.NotFoundException
import io.tolgee.exceptions.PermissionException
import io.tolgee.model.Screenshot
import io.tolgee.model.UploadedImage
import io.tolgee.model.binaryAsset.BinaryAsset
import io.tolgee.model.binaryAsset.BinaryAssetScreenshotReference
import io.tolgee.model.key.Key
import io.tolgee.model.key.screenshotReference.KeyInScreenshotPosition
import io.tolgee.model.key.screenshotReference.KeyScreenshotReference
import io.tolgee.repository.KeyScreenshotReferenceRepository
import io.tolgee.repository.ScreenshotRepository
import io.tolgee.repository.binaryAsset.BinaryAssetScreenshotReferenceRepository
import io.tolgee.security.authentication.AuthenticationFacade
import io.tolgee.service.ImageUploadService
import io.tolgee.service.ImageUploadService.Companion.UPLOADED_IMAGES_STORAGE_FOLDER_NAME
import io.tolgee.util.ImageConverter
import io.tolgee.util.Logging
import io.tolgee.util.logger
import jakarta.persistence.EntityManager
import org.springframework.core.io.InputStreamSource
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.awt.Dimension
import kotlin.math.roundToInt

/**
 * A screenshot is owned by whatever references it — keys, binary assets, or both — and is deleted
 * (row and files) once nothing does. Every path that drops a reference goes through
 * [deleteIfOrphaned] so neither side can strand or steal the other's screenshots.
 */
@Service
class ScreenshotService(
  private val screenshotRepository: ScreenshotRepository,
  private val fileStorage: FileStorage,
  private val tolgeeProperties: TolgeeProperties,
  private val imageUploadService: ImageUploadService,
  private val authenticationFacade: AuthenticationFacade,
  private val entityManager: EntityManager,
  private val keyScreenshotReferenceRepository: KeyScreenshotReferenceRepository,
  private val binaryAssetScreenshotReferenceRepository: BinaryAssetScreenshotReferenceRepository,
) : Logging {
  companion object {
    const val SCREENSHOTS_STORAGE_FOLDER_NAME = "screenshots"
    const val MIDDLE_SIZED_MAX_DIMENSION = 600
    const val THUMBNAIL_MAX_DIMENSION = 200
  }

  @Transactional
  fun store(
    screenshotImage: InputStreamSource,
    key: Key,
    info: ScreenshotInfoDto?,
  ): Screenshot {
    if (getScreenshotsCountForKey(key) >= tolgeeProperties.maxScreenshotsPerKey) {
      throw BadRequestException(
        Message.MAX_SCREENSHOTS_EXCEEDED,
        listOf(tolgeeProperties.maxScreenshotsPerKey),
      )
    }

    val result = storeNew(screenshotImage, info?.location)

    return addReference(
      key = key,
      screenshot = result.screenshot,
      info = info,
      originalDimension = result.originalDimension,
      targetDimension = result.targetDimension,
    )
  }

  /** Converts and stores an image as a new screenshot with no references yet. */
  @Transactional
  fun storeNew(
    screenshotImage: InputStreamSource,
    location: String?,
  ): CreateScreenshotResult {
    val converter = ImageConverter(screenshotImage.inputStream)
    val image = converter.getImage()
    val middleSized = converter.getThumbnail(MIDDLE_SIZED_MAX_DIMENSION)
    val thumbnail = converter.getThumbnail(THUMBNAIL_MAX_DIMENSION)

    val screenshot =
      saveScreenshot(
        image.toByteArray(),
        middleSized.toByteArray(),
        thumbnail.toByteArray(),
        location,
        converter.targetDimension,
      )
    return CreateScreenshotResult(
      screenshot = screenshot,
      originalDimension = converter.originalDimension,
      targetDimension = converter.targetDimension,
    )
  }

  /**
   * Replaces the image of an existing screenshot in place, keeping its id and every reference.
   *
   * Old files go first: the filename carries the extension, and a legacy `.jpg` becomes `.png` here,
   * so the new files may land on a different name. Both derived sizes are always written, which is
   * why the flags are forced on — a legacy row may still carry the schema default `false`.
   */
  @Transactional
  fun replaceImage(
    screenshot: Screenshot,
    screenshotImage: InputStreamSource,
  ): CreateScreenshotResult {
    val converter = ImageConverter(screenshotImage.inputStream)
    val image = converter.getImage()
    val middleSized = converter.getThumbnail(MIDDLE_SIZED_MAX_DIMENSION)
    val thumbnail = converter.getThumbnail(THUMBNAIL_MAX_DIMENSION)

    val previousFiles = filePaths(screenshot)
    screenshot.extension = "png"
    screenshot.hasThumbnail = true
    screenshot.hasMiddleSized = true
    screenshot.width = converter.targetDimension.width
    screenshot.height = converter.targetDimension.height
    screenshotRepository.save(screenshot)
    storeFiles(screenshot, image.toByteArray(), middleSized.toByteArray(), thumbnail.toByteArray())
    // files the new image did not overwrite (a legacy .jpg, say) go once the row change is safe
    deleteFilesAfterCommit(previousFiles - filePaths(screenshot).toSet())
    return CreateScreenshotResult(
      screenshot = screenshot,
      originalDimension = converter.originalDimension,
      targetDimension = converter.targetDimension,
    )
  }

  fun addReference(
    key: Key,
    screenshot: Screenshot,
    info: ScreenshotInfoDto?,
    originalDimension: Dimension?,
    targetDimension: Dimension?,
  ): Screenshot {
    val reference = KeyScreenshotReference()
    reference.key = key
    reference.screenshot = screenshot
    screenshot.keyScreenshotReferences.add(reference)
    key.keyScreenshotReferences.add(reference)
    reference.setInfo(info, originalDimension, targetDimension)
    entityManager.persist(reference)
    return screenshot
  }

  private fun KeyScreenshotReference.setInfo(
    info: ScreenshotInfoDto?,
    originalDimension: Dimension?,
    newDimension: Dimension?,
  ) {
    info?.let {
      this.originalText = info.text
      it.positions?.forEach { positionDto ->
        val xRatio =
          newDimension
            ?.width
            ?.toDouble()
            ?.div(originalDimension?.width?.toDouble() ?: 1.0) ?: 1.0
        val yRatio =
          newDimension
            ?.height
            ?.toDouble()
            ?.div(originalDimension?.height?.toDouble() ?: 1.0) ?: 1.0
        positions = positions ?: mutableListOf()
        positions!!.add(
          KeyInScreenshotPosition(
            positionDto.x.adjustByRation(xRatio),
            positionDto.y.adjustByRation(yRatio),
            positionDto.width.adjustByRation(xRatio),
            positionDto.height.adjustByRation(yRatio),
          ),
        )
      }
    }
  }

  fun Int.adjustByRation(ratio: Double): Int {
    return (this * ratio).roundToInt()
  }

  @Transactional
  fun saveUploadedImages(
    uploadedImageIds: Collection<Long>,
    key: Key,
  ): Map<Long, Screenshot> {
    val screenshots =
      uploadedImageIds.map {
        KeyScreenshotDto().apply { uploadedImageId = it }
      }
    return saveUploadedImages(screenshots, key)
  }

  /**
   * @return Map of uploaded image id and screenshot
   */
  fun saveUploadedImages(
    screenshots: List<KeyScreenshotDto>,
    key: Key,
  ): Map<Long, Screenshot> {
    val imageIds = screenshots.map { it.uploadedImageId }
    val images = imageUploadService.find(imageIds).associateBy { it.id }
    return screenshots
      .map { screenshotInfo ->
        val image =
          images[screenshotInfo.uploadedImageId]
            ?: throw NotFoundException(Message.ONE_OR_MORE_IMAGES_NOT_FOUND)

        if (authenticationFacade.authenticatedUser.id != image.userAccount.id) {
          throw PermissionException(Message.CURRENT_USER_DOES_NOT_OWN_IMAGE)
        }

        val info =
          screenshotInfo.let {
            ScreenshotInfoDto(it.text, it.positions)
          }

        val (screenshot, originalDimension, targetDimension) = saveScreenshot(image)

        addReference(key, screenshot, info, originalDimension, targetDimension)

        screenshotInfo.uploadedImageId to screenshot
      }.toMap()
  }

  /**
   * Creates and saves screenshot entity and the corresponding file
   */
  fun saveScreenshot(image: UploadedImage): CreateScreenshotResult {
    val img =
      fileStorage
        .readFile(
          UPLOADED_IMAGES_STORAGE_FOLDER_NAME + "/" + image.filenameWithExtension,
        )
    val middleSized =
      fileStorage
        .readFile(
          UPLOADED_IMAGES_STORAGE_FOLDER_NAME + "/" + image.middleSizedWithExtension,
        )
    val thumbnail =
      fileStorage
        .readFile(
          UPLOADED_IMAGES_STORAGE_FOLDER_NAME + "/" + image.thumbnailFilenameWithExtension,
        )
    val screenshot = saveScreenshot(img, middleSized, thumbnail, image.location, Dimension(image.width, image.height))
    imageUploadService.delete(image)
    return CreateScreenshotResult(
      screenshot = screenshot,
      originalDimension = Dimension(image.originalWidth, image.originalHeight),
      targetDimension = Dimension(image.width, image.height),
    )
  }

  /**
   * Creates and saves screenshot entity and the corresponding file
   */
  fun saveScreenshot(
    image: ByteArray,
    middleSized: ByteArray,
    thumbnail: ByteArray,
    location: String?,
    dimension: Dimension,
  ): Screenshot {
    val screenshot = Screenshot()
    screenshot.extension = "png"
    screenshot.location = location
    screenshot.width = dimension.width
    screenshot.height = dimension.height
    screenshotRepository.save(screenshot)
    storeFiles(screenshot, image, middleSized, thumbnail)
    return screenshot
  }

  fun storeFiles(
    screenshot: Screenshot,
    image: ByteArray?,
    middleSized: ByteArray?,
    thumbnail: ByteArray?,
  ) {
    thumbnail?.let { fileStorage.storeFile(screenshot.getThumbnailPath(), it) }
    middleSized?.let { bytes -> screenshot.getMiddleSizedPath()?.let { fileStorage.storeFile(it, bytes) } }
    image?.let { fileStorage.storeFile(screenshot.getFilePath(), it) }
  }

  @Transactional
  fun findAll(key: Key): List<Screenshot> {
    return screenshotRepository.findAllByKey(key)
  }

  @Transactional
  fun findAll(asset: BinaryAsset): List<Screenshot> {
    return screenshotRepository.findAllByAssetId(asset.id).also { initializeReferences(it) }
  }

  /** Screenshots per asset for a page of assets — a few queries for the page, not one per row. */
  @Transactional(readOnly = true)
  fun getScreenshotsForAssets(assetIds: Collection<Long>): Map<Long, List<Screenshot>> {
    if (assetIds.isEmpty()) return emptyMap()
    val byAsset =
      binaryAssetScreenshotReferenceRepository
        .findAllByAssetIdIn(assetIds)
        .groupBy({ it.asset.id }, { it.screenshot })
        .mapValues { (_, screenshots) -> screenshots.distinctBy { it.id } }
    initializeReferences(byAsset.values.flatten())
    return byAsset
  }

  /**
   * Loads what the model needs once the transaction is over: both reference lists and the names
   * they point at. Callers build models outside the session, so lazy access there would fail.
   */
  fun initializeReferences(screenshots: Collection<Screenshot>) {
    screenshots.forEach { screenshot ->
      screenshot.keyScreenshotReferences.forEach {
        it.key.name
        it.key.namespace?.name
      }
      screenshot.binaryAssetScreenshotReferences.forEach { it.asset.name }
    }
  }

  @Transactional(readOnly = true)
  fun findAllByProjectAndLocation(
    projectId: Long,
    location: String,
  ): List<Screenshot> = screenshotRepository.findAllByProjectIdAndLocation(projectId, location)

  @Transactional
  fun delete(screenshots: Collection<Screenshot>) {
    screenshots.forEach {
      delete(it)
    }
  }

  @Transactional
  fun delete(screenshot: Screenshot) {
    screenshotRepository.delete(screenshot)
    deleteFile(screenshot)
  }

  fun removeScreenshotReference(
    key: Key,
    screenshot: Screenshot,
  ) {
    removeScreenshotReferences(key, listOf(screenshot))
  }

  @Transactional
  fun removeScreenshotReferences(
    key: Key,
    screenshots: List<Screenshot>,
  ) {
    removeScreenshotReferencesById(key, screenshots.map { it.id })
  }

  @Transactional
  fun removeScreenshotReferencesById(
    key: Key,
    screenshotIds: List<Long>?,
  ) {
    screenshotIds ?: return
    val references = keyScreenshotReferenceRepository.findAll(key, screenshotIds)
    keyScreenshotReferenceRepository.deleteAll(references)
    deleteIfOrphaned(screenshotIds)
  }

  @Transactional
  fun removeScreenshotReferences(references: List<KeyScreenshotReference>) {
    val screenshotIds = references.map { it.screenshot.id }.toSet()
    keyScreenshotReferenceRepository.deleteAll(references)
    deleteIfOrphaned(screenshotIds)
  }

  /** Links an asset to a screenshot; a link that already exists is left alone. */
  @Transactional
  fun addAssetReference(
    asset: BinaryAsset,
    screenshot: Screenshot,
  ): BinaryAssetScreenshotReference {
    binaryAssetScreenshotReferenceRepository
      .findAllByAssetIdAndScreenshotIdIn(asset.id, listOf(screenshot.id))
      .firstOrNull()
      ?.let { return it }
    val reference = BinaryAssetScreenshotReference(asset, screenshot)
    screenshot.binaryAssetScreenshotReferences.add(reference)
    asset.screenshotReferences.add(reference)
    entityManager.persist(reference)
    return reference
  }

  @Transactional
  fun removeAssetReferences(
    asset: BinaryAsset,
    screenshotIds: Collection<Long>,
  ) {
    if (screenshotIds.isEmpty()) return
    val references = binaryAssetScreenshotReferenceRepository.findAllByAssetIdAndScreenshotIdIn(asset.id, screenshotIds)
    removeAssetReferences(references)
  }

  @Transactional
  fun removeAssetReferences(references: List<BinaryAssetScreenshotReference>) {
    if (references.isEmpty()) return
    val screenshotIds = references.map { it.screenshot.id }.toSet()
    references.forEach { reference ->
      reference.asset.screenshotReferences.remove(reference)
      reference.screenshot.binaryAssetScreenshotReferences.remove(reference)
    }
    binaryAssetScreenshotReferenceRepository.deleteAll(references)
    deleteIfOrphaned(screenshotIds)
  }

  /**
   * Deletes, with files, every screenshot in [screenshotIds] that no key and no asset references
   * any more. Call after removing references; pending removals are flushed by the lookups.
   */
  @Transactional
  fun deleteIfOrphaned(screenshotIds: Collection<Long>) {
    if (screenshotIds.isEmpty()) return
    val ids = screenshotIds.toSet()
    val keyed = keyScreenshotReferenceRepository.findAll(ids).map { it.screenshot.id }.toSet()
    val onAssets = binaryAssetScreenshotReferenceRepository.findReferencedScreenshotIds(ids)
    val orphans = ids - keyed - onAssets
    if (orphans.isEmpty()) return
    screenshotRepository.findAllById(orphans).forEach { delete(it) }
  }

  /**
   * A screenshot belongs to a project through its references. One referenced from another
   * project — by a key or by an asset — must not be readable or deletable here; one with no
   * references at all belongs to nobody yet and passes.
   */
  fun checkInProject(
    screenshot: Screenshot,
    projectId: Long,
  ) {
    val foreignKey = screenshot.keyScreenshotReferences.any { it.key.project.id != projectId }
    val foreignAsset = screenshot.binaryAssetScreenshotReferences.any { it.asset.project.id != projectId }
    if (foreignKey || foreignAsset) {
      throw PermissionException(Message.SCREENSHOT_NOT_FROM_PROJECT)
    }
  }

  fun findByIdIn(ids: Collection<Long>): List<Screenshot> {
    return screenshotRepository.findAllById(ids)
  }

  fun find(id: Long): Screenshot? {
    return screenshotRepository.findById(id).orElse(null)
  }

  fun get(id: Long): Screenshot = find(id) ?: throw NotFoundException(Message.SCREENSHOT_NOT_FOUND)

  fun deleteAllByProject(projectId: Long) {
    val all =
      (screenshotRepository.getAllByKeyProjectId(projectId) + screenshotRepository.getAllByAssetProjectId(projectId))
        .distinctBy { it.id }
    all.forEach { this.deleteFile(it) }

    entityManager
      .createNativeQuery(
        """
      DELETE FROM key_screenshot_reference WHERE key_id IN (
        SELECT id FROM key WHERE project_id = :projectId
      )
    """,
      ).setParameter("projectId", projectId)
      .executeUpdate()

    entityManager
      .createNativeQuery(
        """
      DELETE FROM binary_asset_screenshot_reference WHERE asset_id IN (
        SELECT id FROM binary_asset WHERE project_id = :projectId
      )
    """,
      ).setParameter("projectId", projectId)
      .executeUpdate()

    if (all.isEmpty()) return
    // ids were collected before the references went, so the rows can actually be found now
    entityManager
      .createNativeQuery(
        """
      DELETE FROM screenshot s WHERE s.id IN (:ids)
        AND NOT EXISTS (SELECT 1 FROM key_screenshot_reference k WHERE k.screenshot_id = s.id)
        AND NOT EXISTS (SELECT 1 FROM binary_asset_screenshot_reference a WHERE a.screenshot_id = s.id)
    """,
      ).setParameter("ids", all.map { it.id })
      .executeUpdate()
  }

  fun deleteAllByKeyId(keyId: Long) {
    deleteAllByKeyId(listOf(keyId))
  }

  fun deleteAllByKeyId(keyIds: Collection<Long>) {
    val all = keyScreenshotReferenceRepository.getAllByKeyIdIn(keyIds)
    removeScreenshotReferences(all)
  }

  /**
   * Deletes storage files for screenshots that will become orphans after all
   * key_screenshot_reference rows for [branchId]'s keys are removed.
   * The actual DB rows are deleted by the caller via bulk SQL.
   */
  fun deleteFilesByBranch(branchId: Long) {
    screenshotRepository.findOrphansByBranchId(branchId).forEach { screenshot ->
      deleteFile(screenshot)
    }
  }

  /** Removes every stored size. A missing derived file is not an error — legacy rows never had them. */
  private fun deleteFile(screenshot: Screenshot) = deleteFilesAfterCommit(filePaths(screenshot))

  private fun filePaths(screenshot: Screenshot): List<String> =
    listOfNotNull(
      screenshot.getFilePath(),
      screenshot.getMiddleSizedPath(),
      screenshot.getThumbnailPath().takeIf { it != screenshot.getFilePath() },
    )

  /**
   * Rows roll back with the transaction; blobs do not. Deleting them before commit would leave
   * surviving rows whose images 404 when anything later in the request fails.
   */
  private fun deleteFilesAfterCommit(paths: Collection<String>) {
    if (paths.isEmpty()) return
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      deleteFilesNow(paths)
      return
    }
    TransactionSynchronizationManager.registerSynchronization(
      object : TransactionSynchronization {
        override fun afterCommit() = deleteFilesNow(paths)
      },
    )
  }

  private fun deleteFilesNow(paths: Collection<String>) {
    paths.forEach { path ->
      try {
        fileStorage.deleteFile(path)
      } catch (e: Exception) {
        logger.warn("Failed to delete screenshot file $path: ${e.message}")
      }
    }
  }

  private fun Screenshot.getFilePath(): String {
    return "$SCREENSHOTS_STORAGE_FOLDER_NAME/${this.filename}"
  }

  private fun Screenshot.getMiddleSizedPath(): String? {
    return this.middleSizedFilename?.let { "$SCREENSHOTS_STORAGE_FOLDER_NAME/$it" }
  }

  private fun Screenshot.getThumbnailPath(): String {
    return "$SCREENSHOTS_STORAGE_FOLDER_NAME/${this.thumbnailFilename}"
  }

  fun getScreenshotPath(filename: String): String {
    return "$SCREENSHOTS_STORAGE_FOLDER_NAME/$filename"
  }

  fun saveAll(screenshots: List<Screenshot>) {
    screenshotRepository.saveAll(screenshots)
  }

  fun getScreenshotsCountForKey(key: Key): Long {
    return screenshotRepository.countByKey(key)
  }

  fun getKeysWithScreenshots(ids: Collection<Long>): List<Key> {
    return screenshotRepository.getKeysWithScreenshots(ids)
  }

  fun saveAllReferences(data: List<KeyScreenshotReference>) {
    keyScreenshotReferenceRepository.saveAll(data)
  }

  fun getScreenshotsForKeys(keyIds: Collection<Long>): Map<Long, List<Screenshot>> {
    return this
      .getKeysWithScreenshots(keyIds)
      .associate {
        it.id to
          it.keyScreenshotReferences
            .map { it.screenshot }
            .toSet()
            .toList()
      }
  }

  fun getKeyScreenshotReferences(
    importedKeys: List<Key>,
    locations: List<String?>,
  ): List<KeyScreenshotReference> {
    return screenshotRepository.getKeyScreenshotReferences(importedKeys, locations)
  }

  fun getAllKeyScreenshotReferences(key: Key): List<KeyScreenshotReference> {
    return screenshotRepository.getAllKeyScreenshotReferences(key)
  }
}
