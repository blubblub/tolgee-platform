package io.tolgee.service.binaryAsset

import io.tolgee.model.binaryAsset.BinaryAsset
import io.tolgee.model.enums.BinaryAssetCapabilities
import io.tolgee.model.enums.BinaryAssetMediaType

/**
 * Inferred from the original file, or — for an asset localized purely by per-language files — from
 * the first localized file that says anything. Null only when no file of the asset is recognised.
 */
val BinaryAsset.mediaType: BinaryAssetMediaType?
  get() =
    BinaryAssetMediaType.infer(contentType, originalFilename)
      ?: translations.firstNotNullOfOrNull { BinaryAssetMediaType.infer(it.contentType, it.originalFilename) }

/** Which parts of the asset workflow apply to this asset — one source of truth for API guards and UI. */
val BinaryAsset.capabilities: BinaryAssetCapabilities
  get() = BinaryAssetMediaType.capabilitiesOf(mediaType)
