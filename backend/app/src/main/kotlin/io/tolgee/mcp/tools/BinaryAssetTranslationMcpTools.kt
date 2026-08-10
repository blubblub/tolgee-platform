package io.tolgee.mcp.tools

import com.fasterxml.jackson.databind.ObjectMapper
import io.modelcontextprotocol.server.McpSyncServer
import io.tolgee.api.v2.controllers.binaryAsset.BinaryAssetController
import io.tolgee.api.v2.controllers.binaryAsset.BinaryAssetTranslationVersionController
import io.tolgee.hateoas.binaryAsset.BinaryAssetModel
import io.tolgee.hateoas.binaryAsset.BinaryAssetModelAssembler
import io.tolgee.hateoas.binaryAsset.BinaryAssetTranslationVersionModelAssembler
import io.tolgee.mcp.McpRequestContext
import io.tolgee.mcp.McpToolsProvider
import io.tolgee.mcp.buildSpec
import io.tolgee.security.ProjectHolder
import io.tolgee.security.authentication.AuthenticationFacade
import io.tolgee.security.authentication.JwtService
import io.tolgee.service.binaryAsset.BinaryAssetService
import io.tolgee.service.binaryAsset.BinaryAssetTranscriptService
import io.tolgee.service.binaryAsset.BinaryAssetTranslationVersionService
import io.tolgee.service.security.PermissionService
import io.tolgee.service.security.SecurityService
import io.tolgee.util.executeInNewTransaction
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import org.springframework.web.servlet.support.ServletUriComponentsBuilder

@Component
class BinaryAssetTranslationMcpTools(
  private val mcpRequestContext: McpRequestContext,
  private val binaryAssetService: BinaryAssetService,
  private val binaryAssetTranscriptService: BinaryAssetTranscriptService,
  private val binaryAssetTranslationVersionService: BinaryAssetTranslationVersionService,
  private val binaryAssetModelAssembler: BinaryAssetModelAssembler,
  private val versionModelAssembler: BinaryAssetTranslationVersionModelAssembler,
  private val projectHolder: ProjectHolder,
  private val permissionService: PermissionService,
  private val securityService: SecurityService,
  private val authenticationFacade: AuthenticationFacade,
  private val jwtService: JwtService,
  private val objectMapper: ObjectMapper,
  private val transactionManager: PlatformTransactionManager,
) : McpToolsProvider {
  private val upsertTranslationSpec = buildSpec(BinaryAssetController::upsertTranslation, "upload_asset_translation")
  private val reviewedSpec = buildSpec(BinaryAssetController::setTranslationReviewed, "set_asset_translation_reviewed")
  private val deleteTranslationSpec = buildSpec(BinaryAssetController::deleteTranslation, "delete_asset_translation")
  private val listVersionsSpec = buildSpec(BinaryAssetTranslationVersionController::list, "list_asset_versions")
  private val uploadVersionSpec = buildSpec(BinaryAssetTranslationVersionController::uploadVersion, "upload_asset_version")
  private val runToolSpec = buildSpec(BinaryAssetTranslationVersionController::runTool, "run_asset_tool")
  private val setChosenSpec = buildSpec(BinaryAssetTranslationVersionController::setChosen, "set_asset_chosen_version")
  private val deleteVersionSpec = buildSpec(BinaryAssetTranslationVersionController::delete, "delete_asset_version")
  private val sourceTicketSpec = buildSpec(BinaryAssetController::sourceDownloadTicket, "get_asset_download_url")
  private val translationTicketSpec =
    buildSpec(BinaryAssetController::translationDownloadTicket, "get_asset_download_url")

  override fun register(server: McpSyncServer) {
    server.addTool(
      "upload_asset_translation",
      "Upload or replace the localized file of a binary asset for one language (base64-encoded, " +
        "max 32 MiB — use the REST multipart endpoint for larger files). Clears the language's " +
        "confirmation.",
      toolSchema {
        number("projectId", "ID of the project (required for PAT, auto-resolved for PAK)")
        number("assetId", "ID of the asset", required = true)
        number("languageId", "ID of the target language", required = true)
        number(
          "translatedAgainstSourceRevision",
          "Source revision this file was produced against (see get_asset's sourceRevision)",
          required = true,
        )
        string("fileName", "Original file name with extension", required = true)
        string("fileContentBase64", "Base64-encoded file content", required = true)
        string("contentType", "Optional: MIME type (e.g. audio/mpeg)")
      },
    ) { request ->
      mcpRequestContext.executeAs(upsertTranslationSpec, request.arguments.getProjectId()) {
        executeInNewTransaction(transactionManager) {
          val projectId = projectHolder.project.id
          val assetId = request.arguments.requireLong("assetId")
          val languageId = request.arguments.requireLong("languageId")
          securityService.checkLanguageTranslatePermission(projectId, listOf(languageId))
          binaryAssetService.upsertTranslation(
            projectId = projectId,
            assetId = assetId,
            languageId = languageId,
            file = request.arguments.decodeUpload(),
            translatedAgainstSourceRevision = request.arguments.requireLong("translatedAgainstSourceRevision"),
            uploader = authenticationFacade.authenticatedUserEntityOrNull,
          )
          textResult(objectMapper.writeValueAsString(detailModel(projectId, assetId)))
        }
      }
    }

    server.addTool(
      "set_asset_translation_reviewed",
      "Confirm or un-confirm the final localized file of a binary asset for one language.",
      toolSchema {
        number("projectId", "ID of the project (required for PAT, auto-resolved for PAK)")
        number("assetId", "ID of the asset", required = true)
        number("languageId", "ID of the target language", required = true)
        boolean("reviewed", "True to confirm, false to un-confirm", required = true)
      },
    ) { request ->
      mcpRequestContext.executeAs(reviewedSpec, request.arguments.getProjectId()) {
        executeInNewTransaction(transactionManager) {
          val projectId = projectHolder.project.id
          val assetId = request.arguments.requireLong("assetId")
          val languageId = request.arguments.requireLong("languageId")
          securityService.checkLanguageStateChangePermission(projectId, listOf(languageId))
          binaryAssetService.setTranslationReviewed(
            projectId,
            assetId,
            languageId,
            request.arguments.requireBoolean("reviewed"),
          )
          textResult(objectMapper.writeValueAsString(detailModel(projectId, assetId)))
        }
      }
    }

    server.addTool(
      "delete_asset_translation",
      "Delete one language's localized file of a binary asset, including its pipeline versions. " +
        "IMPORTANT: This is a destructive operation. The AI assistant should always confirm with the user before calling this tool.",
      toolSchema {
        number("projectId", "ID of the project (required for PAT, auto-resolved for PAK)")
        number("assetId", "ID of the asset", required = true)
        number("languageId", "ID of the target language", required = true)
      },
    ) { request ->
      mcpRequestContext.executeAs(deleteTranslationSpec, request.arguments.getProjectId()) {
        executeInNewTransaction(transactionManager) {
          val projectId = projectHolder.project.id
          val assetId = request.arguments.requireLong("assetId")
          val languageId = request.arguments.requireLong("languageId")
          securityService.checkLanguageTranslatePermission(projectId, listOf(languageId))
          binaryAssetService.deleteTranslation(projectId, assetId, languageId)
          textResult(objectMapper.writeValueAsString(mapOf("deleted" to true, "assetId" to assetId, "languageId" to languageId)))
        }
      }
    }

    server.addTool(
      "list_asset_versions",
      "List the pipeline versions of one language's localized file of a binary asset.",
      toolSchema {
        number("projectId", "ID of the project (required for PAT, auto-resolved for PAK)")
        number("assetId", "ID of the asset", required = true)
        number("languageId", "ID of the target language", required = true)
      },
    ) { request ->
      mcpRequestContext.executeAs(listVersionsSpec, request.arguments.getProjectId()) {
        executeInNewTransaction(transactionManager) {
          val projectId = projectHolder.project.id
          val assetId = request.arguments.requireLong("assetId")
          val languageId = request.arguments.requireLong("languageId")
          securityService.checkLanguageViewPermission(projectId, listOf(languageId))
          val versions = binaryAssetTranslationVersionService.listVersions(projectId, assetId, languageId)
          textResult(
            objectMapper.writeValueAsString(
              mapOf("versions" to versions.map { versionModelAssembler.toModel(it) }),
            ),
          )
        }
      }
    }

    server.addTool(
      "upload_asset_version",
      "Upload a manual pipeline version for one language's localized file (base64-encoded, " +
        "max 32 MiB). The translation must already exist — use upload_asset_translation first.",
      toolSchema {
        number("projectId", "ID of the project (required for PAT, auto-resolved for PAK)")
        number("assetId", "ID of the asset", required = true)
        number("languageId", "ID of the target language", required = true)
        string("fileName", "Original file name with extension", required = true)
        string("fileContentBase64", "Base64-encoded file content", required = true)
        string("contentType", "Optional: MIME type (e.g. audio/mpeg)")
      },
    ) { request ->
      mcpRequestContext.executeAs(uploadVersionSpec, request.arguments.getProjectId()) {
        executeInNewTransaction(transactionManager) {
          val projectId = projectHolder.project.id
          val assetId = request.arguments.requireLong("assetId")
          val languageId = request.arguments.requireLong("languageId")
          securityService.checkLanguageTranslatePermission(projectId, listOf(languageId))
          val version =
            binaryAssetTranslationVersionService.upload(
              projectId = projectId,
              assetId = assetId,
              languageId = languageId,
              file = request.arguments.decodeUpload(),
              user = authenticationFacade.authenticatedUserEntityOrNull,
            )
          textResult(objectMapper.writeValueAsString(versionModelAssembler.toModel(version)))
        }
      }
    }

    server.addTool(
      "run_asset_tool",
      "Run a pipeline tool on one language's localized file, producing a new version. Available " +
        "tools: 'tts' (text-to-speech from the transcript's translation; params: voiceId, modelId) " +
        "and 'voice-changer' (re-voices the audio; params: voiceId, modelId, removeBackgroundNoise). " +
        "Without voiceId the language's or project's default voice is used. Synchronous — can take " +
        "minutes.",
      toolSchema {
        number("projectId", "ID of the project (required for PAT, auto-resolved for PAK)")
        number("assetId", "ID of the asset", required = true)
        number("languageId", "ID of the target language", required = true)
        string("tool", "Tool name: tts or voice-changer", required = true)
        number("baseVersionId", "Optional: run on this version instead of the original upload")
        properties["params"] =
          mapOf(
            "type" to "object",
            "description" to "Optional: tool parameters, e.g. {voiceId: \"...\", modelId: \"...\"}",
          )
      },
    ) { request ->
      mcpRequestContext.executeAs(runToolSpec, request.arguments.getProjectId()) {
        executeInNewTransaction(transactionManager) {
          val projectId = projectHolder.project.id
          val assetId = request.arguments.requireLong("assetId")
          val languageId = request.arguments.requireLong("languageId")
          securityService.checkLanguageTranslatePermission(projectId, listOf(languageId))
          @Suppress("UNCHECKED_CAST")
          val params = request.arguments["params"] as? Map<String, Any?> ?: emptyMap()
          val version =
            binaryAssetTranslationVersionService.runTool(
              projectId = projectId,
              assetId = assetId,
              languageId = languageId,
              toolName = request.arguments.requireString("tool"),
              params = params,
              baseVersionId = request.arguments.getLong("baseVersionId"),
              user = authenticationFacade.authenticatedUserEntityOrNull,
            )
          textResult(objectMapper.writeValueAsString(versionModelAssembler.toModel(version)))
        }
      }
    }

    server.addTool(
      "set_asset_chosen_version",
      "Choose which pipeline version is the final file for one language (or omit versionId to use " +
        "the original upload). Clears the language's confirmation.",
      toolSchema {
        number("projectId", "ID of the project (required for PAT, auto-resolved for PAK)")
        number("assetId", "ID of the asset", required = true)
        number("languageId", "ID of the target language", required = true)
        number("versionId", "Optional: version to choose (omit/null for the original upload)")
      },
    ) { request ->
      mcpRequestContext.executeAs(setChosenSpec, request.arguments.getProjectId()) {
        executeInNewTransaction(transactionManager) {
          val projectId = projectHolder.project.id
          val assetId = request.arguments.requireLong("assetId")
          val languageId = request.arguments.requireLong("languageId")
          securityService.checkLanguageStateChangePermission(projectId, listOf(languageId))
          binaryAssetTranslationVersionService.setChosen(
            projectId,
            assetId,
            languageId,
            request.arguments.getLong("versionId"),
          )
          textResult(objectMapper.writeValueAsString(detailModel(projectId, assetId)))
        }
      }
    }

    server.addTool(
      "delete_asset_version",
      "Delete one pipeline version of a localized file. " +
        "IMPORTANT: This is a destructive operation. The AI assistant should always confirm with the user before calling this tool.",
      toolSchema {
        number("projectId", "ID of the project (required for PAT, auto-resolved for PAK)")
        number("assetId", "ID of the asset", required = true)
        number("languageId", "ID of the target language", required = true)
        number("versionId", "ID of the version", required = true)
      },
    ) { request ->
      mcpRequestContext.executeAs(deleteVersionSpec, request.arguments.getProjectId()) {
        executeInNewTransaction(transactionManager) {
          val projectId = projectHolder.project.id
          val assetId = request.arguments.requireLong("assetId")
          val languageId = request.arguments.requireLong("languageId")
          val versionId = request.arguments.requireLong("versionId")
          securityService.checkLanguageTranslatePermission(projectId, listOf(languageId))
          binaryAssetTranslationVersionService.deleteVersion(projectId, assetId, languageId, versionId)
          textResult(objectMapper.writeValueAsString(mapOf("deleted" to true, "versionId" to versionId)))
        }
      }
    }

    server.addTool(
      "get_asset_download_url",
      "Get a short-lived download URL for a binary asset file: the source file (assetId only), a " +
        "language's localized file (with languageId), or one pipeline version (with languageId and " +
        "versionId). The URL works without authentication until the ticket expires.",
      toolSchema {
        number("projectId", "ID of the project (required for PAT, auto-resolved for PAK)")
        number("assetId", "ID of the asset", required = true)
        number("languageId", "Optional: language of the localized file")
        number("versionId", "Optional: pipeline version (requires languageId)")
      },
    ) { request ->
      val languageId = request.arguments.getLong("languageId")
      val versionId = request.arguments.getLong("versionId")
      // source download needs KEYS_VIEW, localized files TRANSLATIONS_VIEW + language rights
      val spec = if (languageId == null) sourceTicketSpec else translationTicketSpec
      mcpRequestContext.executeAs(spec, request.arguments.getProjectId()) {
        executeInNewTransaction(transactionManager) {
          val projectId = projectHolder.project.id
          val assetId = request.arguments.requireLong("assetId")
          val result =
            when {
              versionId != null && languageId != null -> {
                securityService.checkLanguageViewPermission(projectId, listOf(languageId))
                val version =
                  binaryAssetTranslationVersionService.getVersion(projectId, assetId, languageId, versionId)
                ticketResult(
                  projectId = projectId,
                  assetId = assetId,
                  languageId = languageId,
                  versionId = versionId,
                  storageKey = version.storageKey,
                  contentType = version.contentType,
                  filename = version.originalFilename,
                  byteSize = version.byteSize,
                )
              }
              languageId != null -> {
                securityService.checkLanguageViewPermission(projectId, listOf(languageId))
                val stream = binaryAssetService.openTranslationStream(projectId, assetId, languageId)
                stream.inputStream.close()
                ticketResult(
                  projectId = projectId,
                  assetId = assetId,
                  languageId = languageId,
                  versionId = null,
                  storageKey = stream.storageKey,
                  contentType = stream.contentType,
                  filename = stream.filename,
                  byteSize = stream.byteSize,
                )
              }
              else -> {
                val asset = binaryAssetService.get(projectId, assetId)
                ticketResult(
                  projectId = projectId,
                  assetId = assetId,
                  languageId = null,
                  versionId = null,
                  storageKey = asset.storageKey,
                  contentType = asset.contentType,
                  filename = asset.originalFilename,
                  byteSize = asset.byteSize,
                )
              }
            }
          textResult(objectMapper.writeValueAsString(result))
        }
      }
    }
  }

  /** Mirrors the ticket issuing of the REST controllers — the URL hits the public download endpoint. */
  private fun ticketResult(
    projectId: Long,
    assetId: Long,
    languageId: Long?,
    versionId: Long?,
    storageKey: String,
    contentType: String,
    filename: String,
    byteSize: Long,
  ): Map<String, Any?> {
    val data =
      mutableMapOf(
        "projectId" to projectId.toString(),
        "assetId" to assetId.toString(),
        "storageKey" to storageKey,
        "contentType" to contentType,
        "filename" to filename,
        "byteSize" to byteSize.toString(),
      )
    if (languageId != null) {
      data["languageId"] = languageId.toString()
    }
    if (versionId != null) {
      data["versionId"] = versionId.toString()
    }
    val token =
      jwtService.emitTicket(
        authenticationFacade.authenticatedUser.id,
        JwtService.TicketType.BINARY_ASSET_ACCESS,
        JwtService.DEFAULT_TICKET_EXPIRATION_TIME,
        data,
      )
    val url =
      ServletUriComponentsBuilder
        .fromCurrentContextPath()
        .scheme(resolveRequestScheme())
        .path("/v2/binary-assets/download")
        .queryParam("token", token)
        .toUriString()
    return mapOf(
      "url" to url,
      "filename" to filename,
      "contentType" to contentType,
      "byteSize" to byteSize,
    )
  }

  private fun resolveRequestScheme(): String {
    val request =
      (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request
    val forwarded =
      request
        ?.getHeader("X-Forwarded-Proto")
        ?.split(",")
        ?.firstOrNull()
        ?.trim()
    return when {
      !forwarded.isNullOrBlank() -> forwarded
      request?.scheme != null -> request.scheme
      else -> "https"
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
