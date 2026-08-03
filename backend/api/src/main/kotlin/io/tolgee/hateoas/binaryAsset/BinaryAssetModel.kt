package io.tolgee.hateoas.binaryAsset

import io.tolgee.model.enums.BinaryAssetTranslationStatus
import org.springframework.hateoas.RepresentationModel
import org.springframework.hateoas.server.core.Relation
import java.util.Date

@Relation(collectionRelation = "binaryAssets", itemRelation = "binaryAsset")
open class BinaryAssetModel(
  val id: Long,
  val name: String,
  val description: String?,
  val sourceLanguageId: Long,
  val sourceLanguageTag: String,
  val sourceRevision: Long,
  val originalFilename: String,
  val contentType: String,
  val byteSize: Long,
  val sha256: String,
  val uploadedById: Long?,
  val createdAt: Date?,
  val updatedAt: Date?,
  val currentCount: Int,
  val outdatedCount: Int,
  val targetLanguageCount: Int,
  val translations: List<BinaryAssetTranslationModel>? = null,
) : RepresentationModel<BinaryAssetModel>()

@Relation(collectionRelation = "binaryAssetTranslations", itemRelation = "binaryAssetTranslation")
open class BinaryAssetTranslationModel(
  val languageId: Long,
  val languageTag: String,
  val languageName: String,
  val status: BinaryAssetTranslationStatus,
  val sourceRevision: Long?,
  val originalFilename: String?,
  val contentType: String?,
  val byteSize: Long?,
  val sha256: String?,
  val uploadedById: Long?,
  val updatedAt: Date?,
)

open class BinaryAssetDownloadTicketModel(
  val url: String,
)
