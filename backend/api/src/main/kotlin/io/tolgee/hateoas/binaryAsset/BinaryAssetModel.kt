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
  val transcriptKeyId: Long? = null,
  val transcriptKeyName: String? = null,
  /** True when the key belongs to this asset and is deleted with it. */
  val transcriptKeyOwned: Boolean = false,
  /** The linked key is in the trash — the reference survives a soft delete. */
  val transcriptKeyDeleted: Boolean = false,
  /**
   * Transcript in the asset's source language. Not in [translations], which only covers target
   * languages.
   */
  val transcriptSourceText: String? = null,
  /** Whether "transcribe with AI" is offered: a provider is configured and this file is speech. */
  val transcriptionAvailable: Boolean = false,
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
  /** Transcript text for this language, from the linked key. Null when there is no transcript. */
  val transcriptText: String? = null,
  /** [io.tolgee.model.enums.TranslationState] name of the transcript translation. */
  val transcriptState: String? = null,
  /** Whether this language's own audio can be transcribed — it has a file and a provider exists. */
  val transcriptionAvailable: Boolean = false,
  /** The final file for this language has been confirmed; cleared by any change to what final is. */
  val reviewed: Boolean = false,
  val chosenVersionId: Long? = null,
  /** Filename of the chosen version; null means the uploaded original is the final. */
  val chosenVersionFilename: String? = null,
  val chosenVersionTool: String? = null,
  val versionCount: Int = 0,
)

open class BinaryAssetDownloadTicketModel(
  val url: String,
)
