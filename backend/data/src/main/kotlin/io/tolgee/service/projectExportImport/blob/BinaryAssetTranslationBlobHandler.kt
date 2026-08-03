package io.tolgee.service.projectExportImport.blob

import io.tolgee.component.fileStorage.FileStorage
import io.tolgee.model.binaryAsset.BinaryAssetTranslation
import io.tolgee.util.Logging
import io.tolgee.util.logger
import org.springframework.stereotype.Component
import kotlin.reflect.KClass

@Component
class BinaryAssetTranslationBlobHandler(
  private val fileStorage: FileStorage,
) : BlobHandler,
  Logging {
  override val entityClass: KClass<*> = BinaryAssetTranslation::class

  override fun export(entity: Any): List<BlobEntry> {
    val translation = entity as BinaryAssetTranslation
    if (!fileStorage.fileExists(translation.storageKey)) {
      logger.warn(
        "Binary asset translation ${translation.id} missing at ${translation.storageKey}; exporting without blob",
      )
      return emptyList()
    }
    return listOf(BlobEntry(blobName(translation.id), fileStorage.readFile(translation.storageKey)))
  }

  companion object {
    fun blobName(sourceTranslationId: Long): String = "binary-assets/translations/$sourceTranslationId"
  }
}
