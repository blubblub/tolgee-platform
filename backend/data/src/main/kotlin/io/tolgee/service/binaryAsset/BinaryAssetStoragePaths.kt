package io.tolgee.service.binaryAsset

import java.util.UUID

object BinaryAssetStoragePaths {
  fun newBlobKey(projectId: Long): String = "binary-assets/$projectId/${UUID.randomUUID()}/${UUID.randomUUID()}"
}
