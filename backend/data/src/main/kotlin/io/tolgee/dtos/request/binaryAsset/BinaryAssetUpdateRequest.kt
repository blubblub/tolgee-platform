package io.tolgee.dtos.request.binaryAsset

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

class BinaryAssetUpdateRequest {
  @field:NotBlank
  @field:Size(max = 255)
  var name: String = ""

  @field:Size(max = 2000)
  var description: String? = null
}
