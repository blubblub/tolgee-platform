package io.tolgee.mcp.tools

import com.fasterxml.jackson.databind.ObjectMapper
import io.modelcontextprotocol.server.McpSyncServer
import io.tolgee.api.v2.controllers.binaryAsset.BinaryAssetController
import io.tolgee.hateoas.binaryAsset.BinaryAssetModel
import io.tolgee.hateoas.binaryAsset.BinaryAssetModelAssembler
import io.tolgee.mcp.McpRequestContext
import io.tolgee.mcp.McpToolsProvider
import io.tolgee.mcp.buildSpec
import io.tolgee.model.enums.BinaryAssetMediaType
import io.tolgee.security.ProjectHolder
import io.tolgee.security.authentication.AuthenticationFacade
import io.tolgee.service.binaryAsset.BinaryAssetService
import io.tolgee.service.binaryAsset.BinaryAssetTranscriptService
import io.tolgee.service.binaryAsset.BinaryAssetTranslationVersionService
import io.tolgee.service.security.PermissionService
import io.tolgee.util.executeInNewTransaction
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager

@Component
class BinaryAssetMcpTools(
  private val mcpRequestContext: McpRequestContext,
  private val binaryAssetService: BinaryAssetService,
  private val binaryAssetTranscriptService: BinaryAssetTranscriptService,
  private val binaryAssetTranslationVersionService: BinaryAssetTranslationVersionService,
  private val binaryAssetModelAssembler: BinaryAssetModelAssembler,
  private val projectHolder: ProjectHolder,
  private val permissionService: PermissionService,
  private val authenticationFacade: AuthenticationFacade,
  private val objectMapper: ObjectMapper,
  private val transactionManager: PlatformTransactionManager,
) : McpToolsProvider {
  private val listSpec = buildSpec(BinaryAssetController::list, "list_assets")
  private val getSpec = buildSpec(BinaryAssetController::get, "get_asset")
  private val createSpec = buildSpec(BinaryAssetController::create, "create_asset")
  private val updateSpec = buildSpec(BinaryAssetController::update, "update_asset")
  private val replaceSourceSpec = buildSpec(BinaryAssetController::replaceSource, "replace_asset_source")
  private val deleteSpec = buildSpec(BinaryAssetController::delete, "delete_asset")

  override fun register(server: McpSyncServer) {
    server.addTool(
      "list_assets",
      "List binary assets (audio/video/image/document files) in a Tolgee project, including their " +
        "per-language localization status. Returns up to 50 assets per page.",
      toolSchema {
        number("projectId", "ID of the project (required for PAT, auto-resolved for PAK)")
        string("search", "Optional: search by asset name")
        stringArray("filterMediaType", "Optional: only these media types — audio, video, image")
        number("page", "Optional: page number (0-based, default 0)")
      },
    ) { request ->
      mcpRequestContext.executeAs(listSpec, request.arguments.getProjectId()) {
        executeInNewTransaction(transactionManager) {
          val projectId = projectHolder.project.id
          val pageNum = request.arguments.getInt("page") ?: 0
          val mediaTypes = BinaryAssetMediaType.parseList(request.arguments.getStringList("filterMediaType"))
          val page =
            binaryAssetService.getPage(
              projectId,
              PageRequest.of(pageNum, 50),
              request.arguments.getString("search"),
              mediaTypes,
            )
          val languages = permittedLanguages(projectId)
          // one query for the whole page rather than one per asset
          val transcripts =
            binaryAssetTranscriptService.getTranscriptTranslationsByKey(
              page.content.mapNotNull { it.transcriptKey?.id },
            )
          val versionsByTranslation =
            binaryAssetTranslationVersionService
              .findByTranslationIdIn(page.content.flatMap { asset -> asset.translations.map { it.id } })
              .groupBy { it.translation.id }
          val result =
            pagedResponse(page) { asset ->
              binaryAssetModelAssembler.toListModel(
                asset,
                languages,
                transcripts[asset.transcriptKey?.id].orEmpty(),
                versionsByTranslation,
              )
            }
          textResult(objectMapper.writeValueAsString(result))
        }
      }
    }

    server.addTool(
      "get_asset",
      "Get a binary asset's detail: metadata, transcript key info and per-language localization status.",
      toolSchema {
        number("projectId", "ID of the project (required for PAT, auto-resolved for PAK)")
        number("assetId", "ID of the asset", required = true)
      },
    ) { request ->
      mcpRequestContext.executeAs(getSpec, request.arguments.getProjectId()) {
        executeInNewTransaction(transactionManager) {
          textResult(
            objectMapper.writeValueAsString(
              detailModel(projectHolder.project.id, request.arguments.requireLong("assetId")),
            ),
          )
        }
      }
    }

    server.addTool(
      "create_asset",
      "Create a binary asset with its source file. The file is sent base64-encoded " +
        "(max 32 MiB — use the REST multipart endpoint POST /v2/projects/{projectId}/binary-assets " +
        "for larger files).",
      toolSchema {
        number("projectId", "ID of the project (required for PAT, auto-resolved for PAK)")
        string("name", "Asset name (unique within the project)", required = true)
        string("description", "Optional: description of the asset")
        number("sourceLanguageId", "Optional: source language ID (defaults to the project's base language)")
        string("fileName", "Original file name with extension (e.g. intro.mp3)", required = true)
        string("fileContentBase64", "Base64-encoded file content", required = true)
        string("contentType", "Optional: MIME type (e.g. audio/mpeg)")
      },
    ) { request ->
      mcpRequestContext.executeAs(createSpec, request.arguments.getProjectId()) {
        executeInNewTransaction(transactionManager) {
          val projectId = projectHolder.project.id
          val asset =
            binaryAssetService.create(
              projectId = projectId,
              name = request.arguments.requireString("name"),
              description = request.arguments.getString("description"),
              sourceLanguageId = request.arguments.getLong("sourceLanguageId"),
              file = request.arguments.decodeUpload(),
              uploader = authenticationFacade.authenticatedUserEntityOrNull,
            )
          textResult(objectMapper.writeValueAsString(detailModel(projectId, asset.id)))
        }
      }
    }

    server.addTool(
      "update_asset",
      "Update a binary asset's name or description.",
      toolSchema {
        number("projectId", "ID of the project (required for PAT, auto-resolved for PAK)")
        number("assetId", "ID of the asset", required = true)
        string("name", "Asset name (provide the current name if unchanged)", required = true)
        string("description", "Optional: new description (null clears it)")
      },
    ) { request ->
      mcpRequestContext.executeAs(updateSpec, request.arguments.getProjectId()) {
        executeInNewTransaction(transactionManager) {
          val projectId = projectHolder.project.id
          val assetId = request.arguments.requireLong("assetId")
          binaryAssetService.updateMetadata(
            projectId,
            assetId,
            request.arguments.requireString("name"),
            request.arguments.getString("description"),
          )
          textResult(objectMapper.writeValueAsString(detailModel(projectId, assetId)))
        }
      }
    }

    server.addTool(
      "replace_asset_source",
      "Replace a binary asset's source file (base64-encoded, max 32 MiB). Bumps the source " +
        "revision, so all existing localizations become outdated and their confirmations are cleared.",
      toolSchema {
        number("projectId", "ID of the project (required for PAT, auto-resolved for PAK)")
        number("assetId", "ID of the asset", required = true)
        string("fileName", "Original file name with extension", required = true)
        string("fileContentBase64", "Base64-encoded file content", required = true)
        string("contentType", "Optional: MIME type (e.g. audio/mpeg)")
      },
    ) { request ->
      mcpRequestContext.executeAs(replaceSourceSpec, request.arguments.getProjectId()) {
        executeInNewTransaction(transactionManager) {
          val projectId = projectHolder.project.id
          val assetId = request.arguments.requireLong("assetId")
          binaryAssetService.replaceSource(
            projectId,
            assetId,
            request.arguments.decodeUpload(),
            authenticationFacade.authenticatedUserEntityOrNull,
          )
          textResult(objectMapper.writeValueAsString(detailModel(projectId, assetId)))
        }
      }
    }

    server.addTool(
      "delete_asset",
      "Delete a binary asset with all its localized files and pipeline versions. " +
        "IMPORTANT: This is a destructive operation. The AI assistant should always confirm with the user before calling this tool.",
      toolSchema {
        number("projectId", "ID of the project (required for PAT, auto-resolved for PAK)")
        number("assetId", "ID of the asset", required = true)
      },
    ) { request ->
      mcpRequestContext.executeAs(deleteSpec, request.arguments.getProjectId()) {
        executeInNewTransaction(transactionManager) {
          val assetId = request.arguments.requireLong("assetId")
          binaryAssetService.deleteAsset(projectHolder.project.id, assetId)
          textResult(objectMapper.writeValueAsString(mapOf("deleted" to true, "assetId" to assetId)))
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
      permittedLanguages(projectId),
      binaryAssetTranscriptService.getTranscriptTranslations(asset.transcriptKey?.id),
    )
  }

  /**
   * Only the languages this user may view — a language-scoped translator must not see the other
   * languages' files. Mirrors BinaryAssetController.permittedLanguages.
   */
  private fun permittedLanguages(projectId: Long) =
    permissionService.getPermittedViewLanguages(
      projectId,
      authenticationFacade.authenticatedUser.id,
    )
}
