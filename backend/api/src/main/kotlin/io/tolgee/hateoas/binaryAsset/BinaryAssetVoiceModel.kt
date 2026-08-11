package io.tolgee.hateoas.binaryAsset

import org.springframework.hateoas.RepresentationModel
import org.springframework.hateoas.server.core.Relation

@Relation(collectionRelation = "binaryAssetVoices", itemRelation = "binaryAssetVoice")
open class BinaryAssetVoiceModel(
  /** null = the project-wide default */
  val languageId: Long?,
  val languageTag: String?,
  /** null = applies to every tool */
  val tool: String?,
  val voiceId: String,
) : RepresentationModel<BinaryAssetVoiceModel>()
