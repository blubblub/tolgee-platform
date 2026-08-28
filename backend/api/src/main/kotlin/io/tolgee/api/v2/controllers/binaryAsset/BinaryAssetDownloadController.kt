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
import io.tolgee.service.binaryAsset.BinaryAssetTranslationVersionService
import io.tolgee.service.security.PermissionService
import io.tolgee.service.security.SecurityService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpRange
import org.springframework.http.HttpStatus
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
  private val binaryAssetTranslationVersionService: BinaryAssetTranslationVersionService,
  private val securityService: SecurityService,
  private val permissionService: PermissionService,
) {
  @GetMapping("/download")
  @Operation(
    summary = "Download or inline-preview a binary asset file via short-lived ticket",
    description =
      "Pass inline=true (or omit when content type is audio/image/video) to stream with " +
        "Content-Disposition: inline so browsers can play/preview in-page. " +
        "Supports HTTP byte ranges (a single `Range: bytes=…` → 206 Partial Content).",
  )
  fun download(
    @RequestParam token: String,
    @RequestParam(required = false, defaultValue = "false") inline: Boolean,
    request: HttpServletRequest,
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
    val versionId = data["versionId"]?.toLongOrNull()
    val storageKey = data["storageKey"] ?: throw NotFoundException()
    val rawContentType = data["contentType"] ?: "application/octet-stream"
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
    } else if (versionId != null) {
      val version =
        try {
          binaryAssetTranslationVersionService.getVersion(projectId, assetId, languageId, versionId)
        } catch (_: NotFoundException) {
          throw NotFoundException()
        }
      if (version.storageKey != storageKey) throw NotFoundException()
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

    // Byte ranges let browsers reach the MP4 index (`moov`) when it sits at the end of the
    // file and seek without downloading everything. Resolved only after authorization so a
    // 416 never reveals anything about a file the caller may not access.
    val range = resolveRange(request, byteSize)
    if (range is RangeResolution.Unsatisfiable) {
      return ResponseEntity
        .status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
        .header(HttpHeaders.ACCEPT_RANGES, "bytes")
        .header(HttpHeaders.CONTENT_RANGE, "bytes */$byteSize")
        .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
        .build()
    }
    val partial = range as? RangeResolution.Partial

    val fileStream =
      binaryAssetService.openByStorageKey(
        storageKey,
        rawContentType,
        filename,
        byteSize,
        partial?.let { it.start..it.endInclusive },
      )
    val contentType = normalizeMediaContentType(fileStream.contentType, fileStream.filename)
    val encoded = URLEncoder.encode(fileStream.filename, StandardCharsets.UTF_8).replace("+", "%20")
    val safeName = fileStream.filename.replace("\"", "")
    val useInline = inline || isBrowserPreviewable(contentType)
    val disposition =
      if (useInline) {
        "inline; filename=\"$safeName\"; filename*=UTF-8''$encoded"
      } else {
        "attachment; filename=\"$safeName\"; filename*=UTF-8''$encoded"
      }
    val body =
      StreamingResponseBody { output ->
        fileStream.inputStream.use { input -> input.copyTo(output) }
      }
    val contentLength = partial?.let { it.endInclusive - it.start + 1 } ?: fileStream.byteSize
    val response =
      ResponseEntity
        .status(if (partial != null) HttpStatus.PARTIAL_CONTENT else HttpStatus.OK)
        .header(HttpHeaders.CONTENT_TYPE, contentType)
        .header(HttpHeaders.CONTENT_LENGTH, contentLength.toString())
        .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
        .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
        .header("X-Content-Type-Options", "nosniff")
        .header(HttpHeaders.ACCEPT_RANGES, "bytes")
    if (partial != null) {
      response.header(
        HttpHeaders.CONTENT_RANGE,
        "bytes ${partial.start}-${partial.endInclusive}/${fileStream.byteSize}",
      )
    }
    return response.body(body)
  }

  private sealed interface RangeResolution {
    data object Full : RangeResolution

    data object Unsatisfiable : RangeResolution

    data class Partial(
      val start: Long,
      val endInclusive: Long,
    ) : RangeResolution
  }

  /**
   * RFC 9110 §14: a malformed `Range` is ignored, a multi-range request may be answered with the
   * full representation (browsers never send one), and `If-Range` can only match a validator —
   * we emit none, so its presence means "send the whole file".
   */
  private fun resolveRange(
    request: HttpServletRequest,
    totalSize: Long,
  ): RangeResolution {
    val header = request.getHeader(HttpHeaders.RANGE) ?: return RangeResolution.Full
    if (request.getHeader(HttpHeaders.IF_RANGE) != null) return RangeResolution.Full
    val ranges =
      try {
        HttpRange.parseRanges(header)
      } catch (_: IllegalArgumentException) {
        return RangeResolution.Full
      }
    val single = ranges.singleOrNull() ?: return RangeResolution.Full
    if (totalSize <= 0) return RangeResolution.Unsatisfiable
    val start = single.getRangeStart(totalSize)
    val end = single.getRangeEnd(totalSize)
    if (start >= totalSize || start > end) return RangeResolution.Unsatisfiable
    return RangeResolution.Partial(start, end)
  }

  private fun isBrowserPreviewable(contentType: String): Boolean {
    val ct = contentType.lowercase()
    return ct.startsWith("audio/") || ct.startsWith("video/") || ct.startsWith("image/")
  }

  private fun normalizeMediaContentType(
    contentType: String,
    filename: String,
  ): String {
    val ct = contentType.ifBlank { "application/octet-stream" }.lowercase()
    val ext = filename.substringAfterLast('.', "").lowercase()
    // Prefer extension when stored type is generic or non-standard
    if (ct == "application/octet-stream" || ct == "application/x-www-form-urlencoded" || ct.startsWith("audio/x-")) {
      return when (ext) {
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "ogg", "oga" -> "audio/ogg"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "webm" -> "video/webm"
        "mp4" -> "video/mp4"
        "mov" -> "video/quicktime"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        "pdf" -> "application/pdf"
        else -> if (ct.startsWith("audio/x-")) ct.replace("audio/x-", "audio/") else ct
      }
    }
    return when (ct) {
      "audio/x-wav", "audio/wave" -> "audio/wav"
      "audio/x-mp3" -> "audio/mpeg"
      else -> contentType.ifBlank { "application/octet-stream" }
    }
  }
}
