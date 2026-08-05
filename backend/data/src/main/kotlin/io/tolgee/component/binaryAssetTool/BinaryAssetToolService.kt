package io.tolgee.component.binaryAssetTool

import io.tolgee.constants.Message
import io.tolgee.exceptions.BadRequestException
import org.springframework.stereotype.Component

@Component
class BinaryAssetToolService(
  tools: List<BinaryAssetTool>,
) {
  private val byName: Map<String, BinaryAssetTool> = tools.associateBy { it.name }

  fun getTool(name: String): BinaryAssetTool =
    byName[name] ?: throw BadRequestException(Message.BINARY_ASSET_TOOL_UNKNOWN)
}
