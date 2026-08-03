package io.tolgee.api.v2.controllers.binaryAsset

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import io.tolgee.dtos.cacheable.isAdmin
import io.tolgee.dtos.cacheable.isSupporterOrAdmin
import io.tolgee.exceptions.NotFoundException
import io.tolgee.exceptions.PermissionException
import io.tolgee.model.enums.Scope
import io.tolgee.security.authentication.JwtService
import io.tolgee.service.binaryAsset.BinaryAssetService
import io.tolgee.service.security.PermissionService
import io.tolgee.service.security.SecurityService
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.CrossOrigin
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@RestController
@CrossOrigin(origins = ["*"])
@RequestMapping("/v2/binary-assets")
@Tag(name = "Binary assets download")
class BinaryAssetDownloadController(
  private val jwtService: JwtService,
  private val binaryAssetService: BinaryAssetService,
  private val securityService: SecurityService,
  private val permissionService: PermissionService,
) {
  @GetMapping("/download")
  @Operation(summary = "Download binary asset file via short-lived ticket")
  fun download(
    @RequestParam token: String,
  ): ResponseEntity<StreamingResponseBody> {
    val auth =
      try {
        jwtService.validateTicket(token, JwtService.TicketType.BINARY_ASSET_ACCESS)
      } catch (_: Exception) {
        throw NotFoundException()
      }
    val data = auth.data ?: throw NotFoundException()
    val projectId = data["projectId"]?.toLongOrNull() ?: throw NotFoundException()
    val assetId = data["assetId"]?.toLongOrNull() ?: throw NotFoundException()
    val languageId = data["languageId"]?.toLongOrNull()
    val storageKey = data["storageKey"] ?: throw NotFoundException()
    val contentType = data["contentType"] ?: "application/octet-stream"
    val filename = data["filename"] ?: "file.bin"
    val byteSize = data["byteSize"]?.toLongOrNull() ?: throw NotFoundException()
    val user = auth.userAccount

    try {
      if (languageId == null) {
        securityService.checkProjectPermissionNoApiKey(projectId, Scope.KEYS_VIEW, user)
      } else {
        securityService.checkProjectPermissionNoApiKey(projectId, Scope.TRANSLATIONS_VIEW, user)
        if (!user.isAdmin() && !user.isSupporterOrAdmin()) {
          val permission = permissionService.getProjectPermissionData(projectId, user.id)
          permission.computedPermissions.checkViewPermitted(languageId)
        }
      }
    } catch (_: PermissionException) {
      throw NotFoundException()
    }

    if (languageId == null) {
      val asset = binaryAssetService.get(projectId, assetId)
      if (asset.storageKey != storageKey) throw NotFoundException()
    } else {
      val streamMeta =
        try {
          binaryAssetService.openTranslationStream(projectId, assetId, languageId)
        } catch (_: NotFoundException) {
          throw NotFoundException()
        }
      streamMeta.inputStream.close()
      if (streamMeta.storageKey != storageKey) throw NotFoundException()
    }

    val fileStream = binaryAssetService.openByStorageKey(storageKey, contentType, filename, byteSize)
    val encoded = URLEncoder.encode(fileStream.filename, StandardCharsets.UTF_8).replace("+", "%20")
    val safeName = fileStream.filename.replace("\"", "")
    val body =
      StreamingResponseBody { output ->
        fileStream.inputStream.use { input -> input.copyTo(output) }
      }
    return ResponseEntity
      .ok()
      .header(HttpHeaders.CONTENT_TYPE, fileStream.contentType.ifBlank { "application/octet-stream" })
      .header(HttpHeaders.CONTENT_LENGTH, fileStream.byteSize.toString())
      .header(
        HttpHeaders.CONTENT_DISPOSITION,
        "attachment; filename=\"$safeName\"; filename*=UTF-8''$encoded",
      )
      .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
      .header("X-Content-Type-Options", "nosniff")
      .body(body)
  }
}
