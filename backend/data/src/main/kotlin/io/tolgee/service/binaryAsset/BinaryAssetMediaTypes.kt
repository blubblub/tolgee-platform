package io.tolgee.service.binaryAsset

import io.tolgee.model.binaryAsset.BinaryAsset
import io.tolgee.model.enums.BinaryAssetCapabilities
import io.tolgee.model.enums.BinaryAssetMediaType

/** Inferred from the original file; null for an asset with no original or an unrecognised type. */
val BinaryAsset.mediaType: BinaryAssetMediaType?
  get() = BinaryAssetMediaType.infer(contentType, originalFilename)

/** Which parts of the asset workflow apply to this asset — one source of truth for API guards and UI. */
val BinaryAsset.capabilities: BinaryAssetCapabilities
  get() = BinaryAssetMediaType.capabilitiesOf(mediaType)
