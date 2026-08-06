package io.tolgee.hateoas.binaryAsset

import io.tolgee.api.v2.controllers.binaryAsset.BinaryAssetVoiceController
import io.tolgee.model.binaryAsset.BinaryAssetVoice
import org.springframework.hateoas.server.mvc.RepresentationModelAssemblerSupport
import org.springframework.stereotype.Component

@Component
class BinaryAssetVoiceModelAssembler :
  RepresentationModelAssemblerSupport<BinaryAssetVoice, BinaryAssetVoiceModel>(
    BinaryAssetVoiceController::class.java,
    BinaryAssetVoiceModel::class.java,
  ) {
  override fun toModel(entity: BinaryAssetVoice): BinaryAssetVoiceModel =
    BinaryAssetVoiceModel(
      languageId = entity.language?.id,
      languageTag = entity.language?.tag,
      voiceId = entity.voiceId,
    )
}
