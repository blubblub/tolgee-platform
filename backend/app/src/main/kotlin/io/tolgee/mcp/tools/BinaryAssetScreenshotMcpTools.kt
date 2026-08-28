package io.tolgee.mcp.tools

import com.fasterxml.jackson.databind.ObjectMapper
import io.modelcontextprotocol.server.McpSyncServer
import io.tolgee.api.v2.controllers.ScreenshotByLocationController
import io.tolgee.api.v2.controllers.binaryAsset.BinaryAssetScreenshotController
import io.tolgee.dtos.request.KeyInScreenshotPositionDto
import io.tolgee.dtos.request.screenshot.ScreenshotAssetReferenceRequest
import io.tolgee.dtos.request.screenshot.ScreenshotByLocationRequest
import io.tolgee.dtos.request.screenshot.ScreenshotKeyReferenceRequest
import io.tolgee.hateoas.screenshot.ScreenshotModelAssembler
import io.tolgee.mcp.McpRequestContext
import io.tolgee.mcp.McpToolsProvider
import io.tolgee.mcp.buildSpec
import io.tolgee.security.ProjectHolder
import io.tolgee.service.ImageUploadService
import io.tolgee.service.binaryAsset.BinaryAssetScreenshotService
import io.tolgee.service.key.ScreenshotByLocationService
import io.tolgee.util.executeInNewTransaction
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager

/**
 * Screenshots for binary assets, and the screen-level upsert a screenshot bot drives. Each tool
 * borrows its permissions from the REST endpoint it mirrors.
 */
@Component
class BinaryAssetScreenshotMcpTools(
  private val mcpRequestContext: McpRequestContext,
  private val binaryAssetScreenshotService: BinaryAssetScreenshotService,
  private val screenshotByLocationService: ScreenshotByLocationService,
  private val screenshotModelAssembler: ScreenshotModelAssembler,
  private val imageUploadService: ImageUploadService,
  private val projectHolder: ProjectHolder,
  private val objectMapper: ObjectMapper,
  private val transactionManager: PlatformTransactionManager,
) : McpToolsProvider {
  private val listSpec = buildSpec(BinaryAssetScreenshotController::list, "list_asset_screenshots")
  private val uploadSpec = buildSpec(BinaryAssetScreenshotController::upload, "upload_asset_screenshot")
  private val linkSpec = buildSpec(BinaryAssetScreenshotController::link, "link_asset_screenshot")
  private val unlinkSpec = buildSpec(BinaryAssetScreenshotController::unlink, "unlink_asset_screenshot")
  private val byLocationSpec =
    buildSpec(ScreenshotByLocationController::upsertByLocation, "upload_screenshot_by_location")

  override fun register(server: McpSyncServer) {
    server.addTool(
      "list_asset_screenshots",
      "List the screenshots (screens of the app) a binary asset is used on.",
      toolSchema {
        number("projectId", "ID of the project (required for PAT, auto-resolved for PAK)")
        number("assetId", "ID of the asset", required = true)
      },
    ) { request ->
      mcpRequestContext.executeAs(listSpec, request.arguments.getProjectId()) {
        executeInNewTransaction(transactionManager) {
          val screenshots =
            binaryAssetScreenshotService
              .list(projectHolder.project.id, request.arguments.requireLong("assetId"))
              .map { screenshotModelAssembler.toModel(it) }
          textResult(objectMapper.writeValueAsString(screenshots))
        }
      }
    }

    server.addTool(
      "upload_asset_screenshot",
      "Upload a screenshot (png/jpeg/gif, base64-encoded) and attach it to a binary asset. " +
        "Prefer upload_screenshot_by_location when the screenshot is a whole screen with several " +
        "keys and assets on it.",
      toolSchema {
        number("projectId", "ID of the project (required for PAT, auto-resolved for PAK)")
        number("assetId", "ID of the asset", required = true)
        string("fileName", "Image file name with extension (e.g. onboarding.png)", required = true)
        string("fileContentBase64", "Base64-encoded image content", required = true)
        string("contentType", "Optional: MIME type (image/png, image/jpeg, image/gif)")
        string("location", "Optional: identity of the screen (route, flow step, …)")
      },
    ) { request ->
      mcpRequestContext.executeAs(uploadSpec, request.arguments.getProjectId()) {
        executeInNewTransaction(transactionManager) {
          val image = request.arguments.decodeUpload()
          imageUploadService.validateIsImage(image)
          val stored =
            binaryAssetScreenshotService.upload(
              projectHolder.project.id,
              request.arguments.requireLong("assetId"),
              image,
              request.arguments.getString("location"),
            )
          textResult(objectMapper.writeValueAsString(screenshotModelAssembler.toModel(stored)))
        }
      }
    }

    server.addTool(
      "link_asset_screenshot",
      "Link an existing screenshot of this project (e.g. one attached to a key) to a binary asset.",
      toolSchema {
        number("projectId", "ID of the project (required for PAT, auto-resolved for PAK)")
        number("assetId", "ID of the asset", required = true)
        number("screenshotId", "ID of the screenshot", required = true)
      },
    ) { request ->
      mcpRequestContext.executeAs(linkSpec, request.arguments.getProjectId()) {
        executeInNewTransaction(transactionManager) {
          val screenshot =
            binaryAssetScreenshotService.link(
              projectHolder.project.id,
              request.arguments.requireLong("assetId"),
              request.arguments.requireLong("screenshotId"),
            )
          textResult(objectMapper.writeValueAsString(screenshotModelAssembler.toModel(screenshot)))
        }
      }
    }

    server.addTool(
      "unlink_asset_screenshot",
      "Remove a screenshot from a binary asset. The screenshot is deleted only when nothing else " +
        "references it any more.",
      toolSchema {
        number("projectId", "ID of the project (required for PAT, auto-resolved for PAK)")
        number("assetId", "ID of the asset", required = true)
        number("screenshotId", "ID of the screenshot", required = true)
      },
    ) { request ->
      mcpRequestContext.executeAs(unlinkSpec, request.arguments.getProjectId()) {
        executeInNewTransaction(transactionManager) {
          val screenshotId = request.arguments.requireLong("screenshotId")
          binaryAssetScreenshotService.unlink(
            projectHolder.project.id,
            request.arguments.requireLong("assetId"),
            listOf(screenshotId),
          )
          textResult(objectMapper.writeValueAsString(mapOf("unlinked" to true, "screenshotId" to screenshotId)))
        }
      }
    }

    server.addTool(
      "upload_screenshot_by_location",
      "Upload or replace the screenshot of one screen of the app and record which keys and binary " +
        "assets appear on it. `location` identifies the screen (route, flow step, …): uploading it " +
        "again replaces the image in place and resets the linked keys and assets to exactly what is " +
        "listed. Keys and assets are never created or deleted; unknown names are reported back. " +
        "Safe to rerun.",
      toolSchema {
        number("projectId", "ID of the project (required for PAT, auto-resolved for PAK)")
        string("location", "Identity of the screen (route, flow step, …)", required = true)
        string("fileName", "Image file name with extension (e.g. onboarding-step-3.png)", required = true)
        string("fileContentBase64", "Base64-encoded image content (png/jpeg/gif)", required = true)
        string("contentType", "Optional: MIME type (image/png, image/jpeg, image/gif)")
        objectArray("keys", "Keys visible on this screen") {
          string("name", "Key name", required = true)
          string("namespace", "Optional: key namespace")
          string("text", "Optional: the text as rendered on this screen")
          objectArray("positions", "Optional: where the text is, in pixels of the uploaded image") {
            number("x", "Left edge", required = true)
            number("y", "Top edge", required = true)
            number("width", "Width", required = true)
            number("height", "Height", required = true)
          }
        }
        stringArray("assets", "Names of binary assets used on this screen")
      },
    ) { request ->
      mcpRequestContext.executeAs(byLocationSpec, request.arguments.getProjectId()) {
        executeInNewTransaction(transactionManager) {
          val image = request.arguments.decodeUpload()
          imageUploadService.validateIsImage(image)
          val info =
            ScreenshotByLocationRequest(
              location = request.arguments.requireString("location"),
              keys =
                request.arguments.getList("keys").orEmpty().map { key ->
                  ScreenshotKeyReferenceRequest(
                    name = key.requireString("name"),
                    namespace = key.getString("namespace"),
                    text = key.getString("text"),
                    positions =
                      key.getList("positions")?.map { p ->
                        KeyInScreenshotPositionDto(
                          x = p.requireLong("x").toInt(),
                          y = p.requireLong("y").toInt(),
                          width = p.requireLong("width").toInt(),
                          height = p.requireLong("height").toInt(),
                        )
                      },
                  )
                },
              assets =
                request.arguments
                  .getStringList("assets")
                  .orEmpty()
                  .map { ScreenshotAssetReferenceRequest(it) },
            )
          val result = screenshotByLocationService.upsert(projectHolder.project.id, image, info)
          textResult(
            objectMapper.writeValueAsString(
              mapOf(
                "screenshot" to screenshotModelAssembler.toModel(result.screenshot),
                "created" to result.created,
                "replacedScreenshots" to result.replacedScreenshots,
                "linkedKeys" to result.linkedKeys,
                "linkedAssets" to result.linkedAssets,
                "unknownKeys" to result.unknownKeys,
                "unknownAssets" to result.unknownAssets,
              ),
            ),
          )
        }
      }
    }
  }
}
