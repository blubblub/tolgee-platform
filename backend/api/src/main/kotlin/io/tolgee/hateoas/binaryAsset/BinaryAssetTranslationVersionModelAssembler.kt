package io.tolgee.hateoas.binaryAsset

import io.tolgee.api.v2.controllers.binaryAsset.BinaryAssetTranslationVersionController
import io.tolgee.model.binaryAsset.BinaryAssetTranslationVersion
import org.springframework.hateoas.server.mvc.RepresentationModelAssemblerSupport
import org.springframework.stereotype.Component

@Component
class BinaryAssetTranslationVersionModelAssembler :
  RepresentationModelAssemblerSupport<BinaryAssetTranslationVersion, BinaryAssetTranslationVersionModel>(
    BinaryAssetTranslationVersionController::class.java,
    BinaryAssetTranslationVersionModel::class.java,
  ) {
  override fun toModel(entity: BinaryAssetTranslationVersion): BinaryAssetTranslationVersionModel =
    BinaryAssetTranslationVersionModel(
      id = entity.id,
      tool = entity.tool,
      toolParams = entity.toolParams,
      originalFilename = entity.originalFilename,
      contentType = entity.contentType,
      byteSize = entity.byteSize,
      sha256 = entity.sha256,
      chosen = entity.chosen,
      createdById = entity.createdBy?.id,
      createdAt = entity.createdAt,
    )
}
