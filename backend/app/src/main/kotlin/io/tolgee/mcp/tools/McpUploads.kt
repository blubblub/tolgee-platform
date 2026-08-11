package io.tolgee.mcp.tools

import io.tolgee.constants.Message
import io.tolgee.exceptions.BadRequestException
import org.springframework.web.multipart.MultipartFile
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.Base64

/**
 * MCP carries no multipart requests, so file uploads arrive base64-encoded inside JSON tool
 * arguments. The cap bounds how much memory a single tool call can hold — larger files must go
 * through the REST multipart endpoints, which stream instead.
 *
 * Unlike the streamed REST path this is not free: a call at the cap holds the base64 argument
 * (~4/3 of the limit) plus the decoded array at once, so peak heap is roughly 2.3x this value per
 * concurrent upload. At 200 MiB that is ~470 MiB, which needs the container heap raised
 * (JAVA_OPTS=-Xmx2g or -XX:MaxRAMPercentage=50) above the JVM's 25%-of-RAM default.
 */
const val MCP_MAX_UPLOAD_BYTES: Long = 200L * 1024 * 1024

class InMemoryMultipartFile(
  private val partName: String,
  private val filename: String,
  private val mimeType: String?,
  private val bytes: ByteArray,
) : MultipartFile {
  override fun getName(): String = partName

  override fun getOriginalFilename(): String = filename

  override fun getContentType(): String? = mimeType

  override fun isEmpty(): Boolean = bytes.isEmpty()

  override fun getSize(): Long = bytes.size.toLong()

  override fun getBytes(): ByteArray = bytes

  override fun getInputStream(): InputStream = ByteArrayInputStream(bytes)

  override fun transferTo(dest: File) {
    dest.writeBytes(bytes)
  }
}

/** Decodes the standard upload args (fileName, fileContentBase64, contentType?) into a [MultipartFile]. */
fun Map<String, Any?>.decodeUpload(): MultipartFile {
  val fileName = requireString("fileName")
  val base64 = requireString("fileContentBase64")
  // reject bloated payloads before decoding — the decoded bytes would sit in memory either way
  if (base64.length.toLong() > MCP_MAX_UPLOAD_BYTES * 2) {
    throw BadRequestException(Message.FILE_TOO_BIG)
  }
  val bytes =
    try {
      Base64.getDecoder().decode(base64)
    } catch (e: IllegalArgumentException) {
      throw BadRequestException(Message.REQUEST_VALIDATION_ERROR, listOf("fileContentBase64"))
    }
  if (bytes.size.toLong() > MCP_MAX_UPLOAD_BYTES) {
    throw BadRequestException(Message.FILE_TOO_BIG)
  }
  return InMemoryMultipartFile("file", fileName, getString("contentType"), bytes)
}
