package io.tolgee.mcp.tools

import com.fasterxml.jackson.databind.ObjectMapper
import io.modelcontextprotocol.server.McpSyncServer
import io.tolgee.api.v2.controllers.binaryAsset.BinaryAssetController
import io.tolgee.constants.Message
import io.tolgee.exceptions.BadRequestException
import io.tolgee.hateoas.binaryAsset.BinaryAssetModel
import io.tolgee.hateoas.binaryAsset.BinaryAssetModelAssembler
import io.tolgee.mcp.McpRequestContext
import io.tolgee.mcp.McpToolsProvider
import io.tolgee.mcp.buildSpec
import io.tolgee.security.ProjectHolder
import io.tolgee.security.authentication.AuthenticationFacade
import io.tolgee.service.binaryAsset.BinaryAssetService
import io.tolgee.service.binaryAsset.BinaryAssetTranscriptService
import io.tolgee.service.security.PermissionService
import io.tolgee.service.security.SecurityService
import io.tolgee.util.executeInNewTransaction
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager

@Component
class BinaryAssetTranscriptMcpTools(
  private val mcpRequestContext: McpRequestContext,
  private val binaryAssetService: BinaryAssetService,
  private val binaryAssetTranscriptService: BinaryAssetTranscriptService,
  private val binaryAssetModelAssembler: BinaryAssetModelAssembler,
  private val projectHolder: ProjectHolder,
  private val permissionService: PermissionService,
  private val securityService: SecurityService,
  private val authenticationFacade: AuthenticationFacade,
  private val objectMapper: ObjectMapper,
  private val transactionManager: PlatformTransactionManager,
) : McpToolsProvider {
  private val addTranscriptSpec = buildSpec(BinaryAssetController::addTranscript, "set_asset_transcript")
  private val generateSpec = buildSpec(BinaryAssetController::generateTranscript, "generate_asset_transcript")
  private val generateTranslationSpec =
    buildSpec(BinaryAssetController::generateTranslationTranscript, "generate_asset_transcript")
  private val deleteTranscriptSpec = buildSpec(BinaryAssetController::deleteTranscript, "delete_asset_transcript")

  override fun register(server: McpSyncServer) {
    server.addTool(
      "set_asset_transcript",
      "Attach a transcript to a binary asset: either link an existing translation key (keyId), or " +
        "create a new key owned by the asset, optionally seeded with text (text, languageTag). " +
        "Transcript text is edited afterwards through the normal key/translation tools.",
      toolSchema {
        number("projectId", "ID of the project (required for PAT, auto-resolved for PAK)")
        number("assetId", "ID of the asset", required = true)
        number("keyId", "Optional: link this existing key (mutually exclusive with text)")
        string("text", "Optional: seed a new owned transcript key with this text")
        string("languageTag", "Optional: language of the seed text (defaults to the source language)")
      },
    ) { request ->
      // the permission depends on the shape of the request: both shapes need KEYS_CREATE here,
      // mirroring BinaryAssetController.addTranscript
      mcpRequestContext.executeAs(addTranscriptSpec, request.arguments.getProjectId()) {
        executeInNewTransaction(transactionManager) {
          val projectId = projectHolder.project.id
          val assetId = request.arguments.requireLong("assetId")
          val keyId = request.arguments.getLong("keyId")
          val text = request.arguments.getString("text")
          if (keyId != null) {
            if (text != null) {
              throw BadRequestException(Message.BINARY_ASSET_TRANSCRIPT_TEXT_OR_KEY_REQUIRED)
            }
            binaryAssetTranscriptService.link(projectId, assetId, keyId)
          } else {
            binaryAssetTranscriptService.create(projectId, assetId, text, request.arguments.getString("languageTag"))
          }
          textResult(objectMapper.writeValueAsString(detailModel(projectId, assetId)))
        }
      }
    }

    server.addTool(
      "generate_asset_transcript",
      "Transcribe an asset's audio with the configured speech-to-text provider and write the text " +
        "into its transcript key (creating the key if needed). Without languageId the source file is " +
        "transcribed; with languageId that language's localized file is. Overwrites existing text. " +
        "Synchronous — can take minutes for long files. Audio and video only.",
      toolSchema {
        number("projectId", "ID of the project (required for PAT, auto-resolved for PAK)")
        number("assetId", "ID of the asset", required = true)
        number("languageId", "Optional: transcribe this language's localized file instead of the source")
      },
    ) { request ->
      val languageId = request.arguments.getLong("languageId")
      // source transcription needs KEYS_CREATE, a localized one TRANSLATIONS_EDIT + language rights
      val spec = if (languageId == null) generateSpec else generateTranslationSpec
      mcpRequestContext.executeAs(spec, request.arguments.getProjectId()) {
        executeInNewTransaction(transactionManager) {
          val projectId = projectHolder.project.id
          val assetId = request.arguments.requireLong("assetId")
          if (languageId == null) {
            binaryAssetTranscriptService.transcribe(projectId, assetId)
          } else {
            securityService.checkLanguageTranslatePermission(projectId, listOf(languageId))
            binaryAssetTranscriptService.transcribeTranslation(projectId, assetId, languageId)
          }
          textResult(objectMapper.writeValueAsString(detailModel(projectId, assetId)))
        }
      }
    }

    server.addTool(
      "delete_asset_transcript",
      "Remove a binary asset's transcript. An owned key is deleted with it; a linked key is left in place.",
      toolSchema {
        number("projectId", "ID of the project (required for PAT, auto-resolved for PAK)")
        number("assetId", "ID of the asset", required = true)
      },
    ) { request ->
      mcpRequestContext.executeAs(deleteTranscriptSpec, request.arguments.getProjectId()) {
        executeInNewTransaction(transactionManager) {
          val projectId = projectHolder.project.id
          val assetId = request.arguments.requireLong("assetId")
          binaryAssetTranscriptService.unlink(projectId, assetId)
          textResult(objectMapper.writeValueAsString(detailModel(projectId, assetId)))
        }
      }
    }
  }

  private fun detailModel(
    projectId: Long,
    assetId: Long,
  ): BinaryAssetModel {
    val asset = binaryAssetService.get(projectId, assetId)
    return binaryAssetModelAssembler.toDetailModel(
      asset,
      permissionService.getPermittedViewLanguages(projectId, authenticationFacade.authenticatedUser.id),
      binaryAssetTranscriptService.getTranscriptTranslations(asset.transcriptKey?.id),
    )
  }
}
