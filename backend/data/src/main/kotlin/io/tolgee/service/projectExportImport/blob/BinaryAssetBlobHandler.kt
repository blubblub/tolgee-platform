package io.tolgee.service.projectExportImport.blob

import io.tolgee.component.fileStorage.FileStorage
import io.tolgee.model.binaryAsset.BinaryAsset
import io.tolgee.util.Logging
import io.tolgee.util.logger
import org.springframework.stereotype.Component
import kotlin.reflect.KClass

@Component
class BinaryAssetBlobHandler(
  private val fileStorage: FileStorage,
) : BlobHandler,
  Logging {
  override val entityClass: KClass<*> = BinaryAsset::class

  override fun export(entity: Any): List<BlobEntry> {
    val asset = entity as BinaryAsset
    // No original to export — the asset is localized purely by its translations, whose blobs are
    // exported separately by BinaryAssetTranslationBlobHandler.
    val storageKey = asset.storageKey ?: return emptyList()
    if (!fileStorage.fileExists(storageKey)) {
      logger.warn("Binary asset ${asset.id} source missing at $storageKey; exporting without blob")
      return emptyList()
    }
    return listOf(BlobEntry(blobName(asset.id), fileStorage.readFile(storageKey)))
  }

  companion object {
    fun blobName(sourceAssetId: Long): String = "binary-assets/assets/$sourceAssetId"
  }
}
