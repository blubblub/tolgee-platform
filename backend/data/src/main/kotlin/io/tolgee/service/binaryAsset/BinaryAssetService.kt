package io.tolgee.service.binaryAsset

import io.tolgee.component.fileStorage.FileStorage
import io.tolgee.component.fileStorage.StoredFileInfo
import io.tolgee.configuration.tolgee.TolgeeProperties
import io.tolgee.constants.Message
import io.tolgee.exceptions.BadRequestException
import io.tolgee.exceptions.NotFoundException
import io.tolgee.model.Language
import io.tolgee.model.Project
import io.tolgee.model.UserAccount
import io.tolgee.model.binaryAsset.BinaryAsset
import io.tolgee.model.binaryAsset.BinaryAssetTranslation
import io.tolgee.model.enums.BinaryAssetMediaType
import io.tolgee.model.enums.BinaryAssetTranslationStatus
import io.tolgee.repository.binaryAsset.BinaryAssetRepository
import io.tolgee.repository.binaryAsset.BinaryAssetTranslationRepository
import io.tolgee.service.language.LanguageService
import io.tolgee.service.project.ProjectService
import io.tolgee.util.Logging
import io.tolgee.util.logger
import jakarta.persistence.EntityManager
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import java.io.InputStream

@Service
class BinaryAssetService(
  private val binaryAssetRepository: BinaryAssetRepository,
  private val binaryAssetTranslationRepository: BinaryAssetTranslationRepository,
  private val fileStorage: FileStorage,
  private val projectService: ProjectService,
  private val languageService: LanguageService,
  private val entityManager: EntityManager,
  private val tolgeeProperties: TolgeeProperties,
) : Logging {
  fun requireStreamingStorage() {
    if (!fileStorage.supportsStreaming()) {
      throw BadRequestException(Message.BINARY_ASSET_STORAGE_NOT_STREAMING)
    }
  }

  @Transactional(readOnly = true)
  fun getPage(
    projectId: Long,
    pageable: Pageable,
    search: String?,
    mediaTypes: Set<BinaryAssetMediaType> = emptySet(),
  ): Page<BinaryAsset> {
    val normalized = search?.takeIf { it.isNotBlank() }
    val page =
      binaryAssetRepository.findAllByProjectId(
        projectId,
        normalized,
        filterAudio = BinaryAssetMediaType.AUDIO in mediaTypes,
        filterVideo = BinaryAssetMediaType.VIDEO in mediaTypes,
        filterImage = BinaryAssetMediaType.IMAGE in mediaTypes,
        pageable = pageable,
      )
    if (page.isEmpty) return page
    val detailed = binaryAssetRepository.findAllWithDetailsByIdIn(page.content.map { it.id }).associateBy { it.id }
    return page.map { detailed[it.id] ?: it }
  }

  @Transactional(readOnly = true)
  fun get(
    projectId: Long,
    assetId: Long,
  ): BinaryAsset {
    return binaryAssetRepository.findByProjectIdAndId(projectId, assetId)
      ?: throw NotFoundException(Message.BINARY_ASSET_NOT_FOUND)
  }

  @Transactional
  fun create(
    projectId: Long,
    name: String,
    description: String?,
    sourceLanguageId: Long?,
    file: MultipartFile,
    uploader: UserAccount?,
  ): BinaryAsset {
    requireStreamingStorage()
    val project = projectService.get(projectId)
    val sourceLanguage =
      if (sourceLanguageId != null) {
        languageService.getEntity(sourceLanguageId, projectId)
      } else {
        project.baseLanguage
          ?: throw BadRequestException(Message.BASE_LANGUAGE_NOT_FOUND)
      }
    validateNameAvailable(projectId, name.trim())
    val stored = storeNewBlob(projectId, file)
    val asset =
      BinaryAsset(project).apply {
        this.name = name.trim()
        this.description = description
        this.sourceLanguage = sourceLanguage
        this.sourceRevision = 1
      }
    applySourceFile(asset, stored, file, uploader)
    return binaryAssetRepository.save(asset)
  }

  @Transactional
  fun updateMetadata(
    projectId: Long,
    assetId: Long,
    name: String,
    description: String?,
  ): BinaryAsset {
    val asset =
      binaryAssetRepository.findByProjectIdAndIdForUpdate(projectId, assetId)
        ?: throw NotFoundException(Message.BINARY_ASSET_NOT_FOUND)
    val trimmed = name.trim()
    if (trimmed != asset.name) {
      validateNameAvailable(projectId, trimmed, excludeId = assetId)
      asset.name = trimmed
    }
    asset.description = description
    return binaryAssetRepository.save(asset)
  }

  @Transactional
  fun replaceSource(
    projectId: Long,
    assetId: Long,
    file: MultipartFile,
    uploader: UserAccount?,
  ): BinaryAsset {
    requireStreamingStorage()
    val asset =
      binaryAssetRepository.findByProjectIdAndIdForUpdate(projectId, assetId)
        ?: throw NotFoundException(Message.BINARY_ASSET_NOT_FOUND)
    val previousKey = asset.storageKey
    val stored = storeNewBlob(projectId, file)
    // every localization is now against an older source, so no confirmation still holds
    asset.translations.forEach { it.reviewed = false }
    asset.sourceRevision += 1
    applySourceFile(asset, stored, file, uploader)
    val saved = binaryAssetRepository.save(asset)
    deleteBlobBestEffort(previousKey)
    return saved
  }

  @Transactional
  fun upsertTranslation(
    projectId: Long,
    assetId: Long,
    languageId: Long,
    file: MultipartFile,
    translatedAgainstSourceRevision: Long,
    uploader: UserAccount?,
  ): BinaryAssetTranslation {
    requireStreamingStorage()
    val asset =
      binaryAssetRepository.findByProjectIdAndIdForUpdate(projectId, assetId)
        ?: throw NotFoundException(Message.BINARY_ASSET_NOT_FOUND)
    if (languageId == asset.sourceLanguage.id) {
      throw BadRequestException(Message.BINARY_ASSET_TARGET_IS_SOURCE_LANGUAGE)
    }
    languageService.getEntity(languageId, projectId)
    if (translatedAgainstSourceRevision < 1 || translatedAgainstSourceRevision > asset.sourceRevision) {
      throw BadRequestException(Message.BINARY_ASSET_INVALID_SOURCE_REVISION)
    }
    val existing = binaryAssetTranslationRepository.findByAssetIdAndLanguageId(assetId, languageId)
    val previousKey = existing?.storageKey
    val stored = storeNewBlob(projectId, file)
    val translation =
      existing ?: BinaryAssetTranslation(asset).also {
        it.language = entityManager.getReference(Language::class.java, languageId)
      }
    // a new file is a new thing to confirm
    translation.reviewed = false
    translation.sourceRevision = translatedAgainstSourceRevision
    translation.storageKey = stored.storageKey
    // Keep the source name, but make the extension match the uploaded container.
    translation.originalFilename = resolveTranslationFilename(asset.originalFilename, file)
    translation.contentType = resolveContentType(file)
    translation.byteSize = stored.info.byteSize
    translation.sha256 = stored.info.sha256
    translation.uploadedBy = uploader
    val saved = binaryAssetTranslationRepository.save(translation)
    if (previousKey != null && previousKey != saved.storageKey) {
      deleteBlobBestEffort(previousKey)
    }
    return saved
  }

  /** Confirms (or un-confirms) the final file for one language. */
  @Transactional
  fun setTranslationReviewed(
    projectId: Long,
    assetId: Long,
    languageId: Long,
    reviewed: Boolean,
  ): BinaryAssetTranslation {
    val translation =
      binaryAssetTranslationRepository.findByProjectAssetAndLanguage(projectId, assetId, languageId)
        ?: throw NotFoundException(Message.BINARY_ASSET_TRANSLATION_NOT_FOUND)
    translation.reviewed = reviewed
    return binaryAssetTranslationRepository.save(translation)
  }

  @Transactional
  fun deleteTranslation(
    projectId: Long,
    assetId: Long,
    languageId: Long,
  ) {
    val translation =
      binaryAssetTranslationRepository.findByProjectAssetAndLanguage(projectId, assetId, languageId)
        ?: throw NotFoundException(Message.BINARY_ASSET_TRANSLATION_NOT_FOUND)
    val keys = mutableListOf(translation.storageKey)
    keys += translation.versions.map { it.storageKey }
    binaryAssetTranslationRepository.delete(translation)
    entityManager.flush()
    keys.forEach { deleteBlobBestEffort(it) }
  }

  @Transactional
  fun deleteAsset(
    projectId: Long,
    assetId: Long,
  ) {
    val asset =
      binaryAssetRepository.findByProjectIdAndId(projectId, assetId)
        ?: throw NotFoundException(Message.BINARY_ASSET_NOT_FOUND)
    val keys = mutableListOf(asset.storageKey)
    // asset.versions covers the source file's versions too, which hang off no translation
    keys += asset.versions.map { it.storageKey }
    keys += asset.translations.map { it.storageKey }
    binaryAssetRepository.delete(asset)
    entityManager.flush()
    keys.forEach { deleteBlobBestEffort(it) }
  }

  @Transactional
  fun deleteAllByProject(projectId: Long) {
    val assets = binaryAssetRepository.findAllByProjectId(projectId)
    val keys = mutableListOf<String>()
    assets.forEach { asset ->
      keys += asset.storageKey
      keys += asset.versions.map { it.storageKey }
      keys += asset.translations.map { it.storageKey }
    }
    if (assets.isNotEmpty()) {
      binaryAssetRepository.deleteAll(assets)
      entityManager.flush()
    }
    keys.forEach { deleteBlobBestEffort(it) }
  }

  @Transactional
  fun deleteTranslationsForLanguage(languageId: Long) {
    val translations = binaryAssetTranslationRepository.findAllByLanguageId(languageId)
    val keys = translations.flatMap { it.versions.map { v -> v.storageKey } } + translations.map { it.storageKey }
    if (translations.isNotEmpty()) {
      binaryAssetTranslationRepository.deleteAll(translations)
      entityManager.flush()
    }
    keys.forEach { deleteBlobBestEffort(it) }
  }

  fun assertNotUsedAsSourceLanguage(languageId: Long) {
    if (binaryAssetRepository.existsBySourceLanguageId(languageId)) {
      throw BadRequestException(Message.BINARY_ASSET_LANGUAGE_IS_SOURCE)
    }
  }

  fun openSourceStream(
    projectId: Long,
    assetId: Long,
  ): FileStream {
    requireStreamingStorage()
    val asset = get(projectId, assetId)
    return FileStream(
      inputStream = fileStorage.openFileStream(asset.storageKey),
      contentType = asset.contentType,
      filename = asset.originalFilename,
      byteSize = asset.byteSize,
      storageKey = asset.storageKey,
    )
  }

  fun openTranslationStream(
    projectId: Long,
    assetId: Long,
    languageId: Long,
  ): FileStream {
    requireStreamingStorage()
    val translation =
      binaryAssetTranslationRepository.findByProjectAssetAndLanguage(projectId, assetId, languageId)
        ?: throw NotFoundException(Message.BINARY_ASSET_TRANSLATION_NOT_FOUND)
    return FileStream(
      inputStream = fileStorage.openFileStream(translation.storageKey),
      contentType = translation.contentType,
      filename = translation.originalFilename,
      byteSize = translation.byteSize,
      storageKey = translation.storageKey,
    )
  }

  fun openByStorageKey(
    storageKey: String,
    contentType: String,
    filename: String,
    byteSize: Long,
  ): FileStream {
    requireStreamingStorage()
    if (!fileStorage.fileExists(storageKey)) {
      throw NotFoundException(Message.BINARY_ASSET_NOT_FOUND)
    }
    return FileStream(
      inputStream = fileStorage.openFileStream(storageKey),
      contentType = contentType,
      filename = filename,
      byteSize = byteSize,
      storageKey = storageKey,
    )
  }

  fun statusFor(
    asset: BinaryAsset,
    languageId: Long,
    translation: BinaryAssetTranslation?,
  ): BinaryAssetTranslationStatus {
    if (languageId == asset.sourceLanguage.id) {
      return BinaryAssetTranslationStatus.CURRENT
    }
    if (translation == null) {
      return BinaryAssetTranslationStatus.MISSING
    }
    return if (translation.sourceRevision == asset.sourceRevision) {
      BinaryAssetTranslationStatus.CURRENT
    } else {
      BinaryAssetTranslationStatus.OUTDATED
    }
  }

  private fun applySourceFile(
    asset: BinaryAsset,
    stored: StoredBlob,
    file: MultipartFile,
    uploader: UserAccount?,
  ) {
    asset.storageKey = stored.storageKey
    asset.originalFilename = resolveFilename(file)
    asset.contentType = resolveContentType(file)
    asset.byteSize = stored.info.byteSize
    asset.sha256 = stored.info.sha256
    asset.uploadedBy = uploader
  }

  private fun validateNameAvailable(
    projectId: Long,
    name: String,
    excludeId: Long? = null,
  ) {
    if (name.isBlank()) {
      throw BadRequestException(Message.BINARY_ASSET_NAME_REQUIRED)
    }
    if (name.length > 255) {
      throw BadRequestException(Message.BINARY_ASSET_NAME_TOO_LONG)
    }
    val collision =
      if (excludeId == null) {
        binaryAssetRepository.existsByProjectIdAndName(projectId, name)
      } else {
        binaryAssetRepository.existsByProjectIdAndNameAndIdNot(projectId, name, excludeId)
      }
    if (collision) {
      throw BadRequestException(Message.BINARY_ASSET_NAME_EXISTS)
    }
  }

  internal fun storeNewBlob(
    projectId: Long,
    file: MultipartFile,
  ): StoredBlob {
    if (file.isEmpty) {
      throw BadRequestException(Message.FILE_EMPTY)
    }
    val maxBytes = tolgeeProperties.maxUploadFileSize.toLong() * 1024L
    val declaredSize = file.size
    if (declaredSize > maxBytes) {
      throw BadRequestException(Message.FILE_TOO_BIG)
    }
    sanitizeFilename(file.originalFilename)
    val key = BinaryAssetStoragePaths.newBlobKey(projectId)
    try {
      val info =
        file.inputStream.use { stream ->
          val counting = CountingInputStream(stream, maxBytes)
          fileStorage.storeFileStream(key, counting, declaredSize.takeIf { it >= 0 })
        }
      if (info.byteSize == 0L) {
        deleteBlobBestEffort(key)
        throw BadRequestException(Message.FILE_EMPTY)
      }
      if (info.byteSize > maxBytes) {
        deleteBlobBestEffort(key)
        throw BadRequestException(Message.FILE_TOO_BIG)
      }
      return StoredBlob(key, info)
    } catch (e: FileTooLargeException) {
      deleteBlobBestEffort(key)
      throw BadRequestException(Message.FILE_TOO_BIG)
    } catch (e: BadRequestException) {
      throw e
    } catch (e: Exception) {
      deleteBlobBestEffort(key)
      throw e
    }
  }

  /** Store tool output bytes as a new blob, returning the storage key and file info. */
  internal fun storeBlobBytes(
    projectId: Long,
    bytes: ByteArray,
  ): StoredBlob {
    if (bytes.isEmpty()) {
      throw BadRequestException(Message.FILE_EMPTY)
    }
    val maxBytes = tolgeeProperties.maxUploadFileSize.toLong() * 1024L
    if (bytes.size.toLong() > maxBytes) {
      throw BadRequestException(Message.FILE_TOO_BIG)
    }
    val key = BinaryAssetStoragePaths.newBlobKey(projectId)
    try {
      val info = fileStorage.storeFileStream(key, bytes.inputStream(), bytes.size.toLong())
      if (info.byteSize == 0L) {
        deleteBlobBestEffort(key)
        throw BadRequestException(Message.FILE_EMPTY)
      }
      return StoredBlob(key, info)
    } catch (e: BadRequestException) {
      throw e
    } catch (e: Exception) {
      deleteBlobBestEffort(key)
      throw e
    }
  }

  internal fun deleteBlobBestEffort(storageKey: String) {
    try {
      if (fileStorage.fileExists(storageKey)) {
        fileStorage.deleteFile(storageKey)
      }
    } catch (e: Exception) {
      logger.warn("Failed to delete binary asset blob $storageKey: ${e.message}")
    }
  }

  /**
   * Canonical filename for an asset's original file. Uses the uploaded file's name; when it
   * carries no extension (clipboard pastes, extension-less files), derives one from the
   * content type so downloads and media-type sniffing keep working.
   */
  internal fun resolveFilename(file: MultipartFile): String {
    val name = sanitizeFilename(file.originalFilename)
    if (name.contains('.')) {
      return name
    }
    val extension = resolveExtension(file)
    return if (extension != null) "$name.$extension" else name
  }

  private fun resolveTranslationFilename(
    sourceFilename: String,
    file: MultipartFile,
  ): String {
    val extension = resolveExtension(file) ?: return sourceFilename
    val maxStemLength = 255 - extension.length - 1
    val stem = sourceFilename.substringBeforeLast('.', sourceFilename).take(maxStemLength)
    return "$stem.$extension"
  }

  private fun resolveExtension(file: MultipartFile) =
    EXTENSION_BY_CONTENT_TYPE[
      file.contentType
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase(),
    ]

  private fun sanitizeFilename(original: String?): String {
    val name =
      original
        ?.substringAfterLast('/')
        ?.substringAfterLast('\\')
        ?.trim()
        .orEmpty()
    if (name.isEmpty()) {
      return "file.bin"
    }
    if (name.length > 255) {
      throw BadRequestException(Message.FILE_NAME_TOO_LONG)
    }
    if (name.contains("..") || name.any { it.isISOControl() }) {
      throw BadRequestException(Message.FILE_NAME_NOT_VALID)
    }
    return name
  }

  internal fun resolveContentType(file: MultipartFile): String {
    val type = file.contentType?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
    return type ?: "application/octet-stream"
  }

  data class FileStream(
    val inputStream: InputStream,
    val contentType: String,
    val filename: String,
    val byteSize: Long,
    val storageKey: String,
  )

  companion object {
    /** Content types seen for asset uploads whose original name may carry no extension. */
    private val EXTENSION_BY_CONTENT_TYPE =
      mapOf(
        "audio/mpeg" to "mp3",
        "audio/wav" to "wav",
        "audio/x-wav" to "wav",
        "audio/mp4" to "m4a",
        "audio/x-m4a" to "m4a",
        "audio/aac" to "aac",
        "audio/ogg" to "ogg",
        "audio/opus" to "opus",
        "audio/flac" to "flac",
        "audio/webm" to "webm",
        "video/mp4" to "mp4",
        "video/quicktime" to "mov",
        "video/webm" to "webm",
        "video/x-matroska" to "mkv",
        "image/png" to "png",
        "image/jpeg" to "jpg",
        "image/webp" to "webp",
        "image/gif" to "gif",
        "image/svg+xml" to "svg",
        "image/vnd.adobe.photoshop" to "psd",
        "application/pdf" to "pdf",
        "text/plain" to "txt",
        "text/csv" to "csv",
        "application/json" to "json",
      )
  }

  internal data class StoredBlob(
    val storageKey: String,
    val info: StoredFileInfo,
  )

  private class FileTooLargeException : RuntimeException()

  private class CountingInputStream(
    private val delegate: InputStream,
    private val maxBytes: Long,
  ) : InputStream() {
    private var readTotal = 0L

    override fun read(): Int {
      val b = delegate.read()
      if (b >= 0) {
        readTotal++
        if (readTotal > maxBytes) throw FileTooLargeException()
      }
      return b
    }

    override fun read(
      b: ByteArray,
      off: Int,
      len: Int,
    ): Int {
      val n = delegate.read(b, off, len)
      if (n > 0) {
        readTotal += n
        if (readTotal > maxBytes) throw FileTooLargeException()
      }
      return n
    }

    override fun close() = delegate.close()
  }
}
