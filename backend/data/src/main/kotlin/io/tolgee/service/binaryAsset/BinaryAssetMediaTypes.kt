package io.tolgee.service.binaryAsset

import io.tolgee.model.binaryAsset.BinaryAsset
import io.tolgee.model.enums.BinaryAssetCapabilities
import io.tolgee.model.enums.BinaryAssetMediaType
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("io.tolgee.service.binaryAsset.BinaryAssetMediaTypes")

/**
 * The asset's effective media type: what its original file is, or — when there is no original, or
 * it says nothing — what its localized files are. Lanes are consulted oldest first (lowest id) so
 * the answer is stable; lanes of mixed types are a data problem and get logged. Null only when no
 * file of the asset is recognised, in which case the asset matches no media-type filter either.
 *
 * `BinaryAssetRepository.findAllByProjectId` encodes the same rule in JPQL — keep them in step.
 */
val BinaryAsset.mediaType: BinaryAssetMediaType?
  get() {
    BinaryAssetMediaType.infer(contentType, originalFilename)?.let { return it }
    val typedLanes =
      translations
        .sortedBy { it.id }
        .mapNotNull { lane ->
          BinaryAssetMediaType.infer(lane.contentType, lane.originalFilename)?.let { lane to it }
        }
    val (firstLane, type) = typedLanes.firstOrNull() ?: return null
    if (typedLanes.any { (_, other) -> other != type }) {
      logger.warn(
        "Binary asset {} has localized files of mixed media types {}; using {} from its oldest typed file ({})",
        id,
        typedLanes.map { (lane, laneType) -> "${lane.originalFilename}=$laneType" },
        type,
        firstLane.originalFilename,
      )
    }
    return type
  }

/** Which parts of the asset workflow apply to this asset — one source of truth for API guards and UI. */
val BinaryAsset.capabilities: BinaryAssetCapabilities
  get() = BinaryAssetMediaType.capabilitiesOf(mediaType)
