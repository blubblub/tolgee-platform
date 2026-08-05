package io.tolgee.hateoas.binaryAsset

import org.springframework.hateoas.RepresentationModel
import org.springframework.hateoas.server.core.Relation
import java.util.Date

@Relation(collectionRelation = "binaryAssetTranslationVersions", itemRelation = "binaryAssetTranslationVersion")
open class BinaryAssetTranslationVersionModel(
  val id: Long,
  val tool: String,
  val toolParams: String?,
  val originalFilename: String,
  val contentType: String,
  val byteSize: Long,
  val sha256: String,
  val chosen: Boolean,
  val createdById: Long?,
  val createdAt: Date?,
) : RepresentationModel<BinaryAssetTranslationVersionModel>()
