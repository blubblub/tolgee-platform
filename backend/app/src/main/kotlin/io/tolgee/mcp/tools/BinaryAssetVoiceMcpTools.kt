package io.tolgee.mcp.tools

import com.fasterxml.jackson.databind.ObjectMapper
import io.modelcontextprotocol.server.McpSyncServer
import io.tolgee.api.v2.controllers.binaryAsset.BinaryAssetVoiceController
import io.tolgee.constants.Message
import io.tolgee.exceptions.BadRequestException
import io.tolgee.hateoas.binaryAsset.BinaryAssetVoiceModelAssembler
import io.tolgee.mcp.McpRequestContext
import io.tolgee.mcp.McpToolsProvider
import io.tolgee.mcp.buildSpec
import io.tolgee.security.ProjectHolder
import io.tolgee.service.binaryAsset.BinaryAssetVoiceService
import io.tolgee.service.language.LanguageService
import io.tolgee.util.executeInNewTransaction
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager

@Component
class BinaryAssetVoiceMcpTools(
  private val mcpRequestContext: McpRequestContext,
  private val binaryAssetVoiceService: BinaryAssetVoiceService,
  private val binaryAssetVoiceModelAssembler: BinaryAssetVoiceModelAssembler,
  private val languageService: LanguageService,
  private val projectHolder: ProjectHolder,
  private val objectMapper: ObjectMapper,
  private val transactionManager: PlatformTransactionManager,
) : McpToolsProvider {
  private val listSpec = buildSpec(BinaryAssetVoiceController::list, "list_asset_voices")
  private val setSpec = buildSpec(BinaryAssetVoiceController::set, "set_asset_voice")

  override fun register(server: McpSyncServer) {
    server.addTool(
      "list_asset_voices",
      "List the default text-to-speech voices of a project: the project-wide default plus any " +
        "per-language overrides. Used by run_asset_tool when no voiceId is passed.",
      toolSchema {
        number("projectId", "ID of the project (required for PAT, auto-resolved for PAK)")
      },
    ) { request ->
      mcpRequestContext.executeAs(listSpec, request.arguments.getProjectId()) {
        executeInNewTransaction(transactionManager) {
          textResult(objectMapper.writeValueAsString(mapOf("voices" to voiceModels())))
        }
      }
    }

    server.addTool(
      "set_asset_voice",
      "Set or clear the default text-to-speech voice for the whole project (no languageId) or for " +
        "one language. Pass no voiceId (or a blank one) to clear the entry.",
      toolSchema {
        number("projectId", "ID of the project (required for PAT, auto-resolved for PAK)")
        number("languageId", "Optional: language to override (omit for the project-wide default)")
        string("voiceId", "Optional: ElevenLabs voice ID (omit or blank to clear)")
      },
    ) { request ->
      mcpRequestContext.executeAs(setSpec, request.arguments.getProjectId()) {
        executeInNewTransaction(transactionManager) {
          val projectId = projectHolder.project.id
          val languageId = request.arguments.getLong("languageId")
          languageId?.let {
            // a language from another project would otherwise silently become that project's default
            languageService.findAll(projectId).find { language -> language.id == it }
              ?: throw BadRequestException(Message.LANGUAGE_NOT_FROM_PROJECT)
          }
          binaryAssetVoiceService.set(projectId, languageId, request.arguments.getString("voiceId"))
          textResult(objectMapper.writeValueAsString(mapOf("voices" to voiceModels())))
        }
      }
    }
  }

  private fun voiceModels() =
    binaryAssetVoiceService
      .list(projectHolder.project.id)
      .map { binaryAssetVoiceModelAssembler.toModel(it) }
}
