package io.tolgee.api.v2.controllers.binaryAsset

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.tolgee.ProjectAuthControllerTest
import io.tolgee.fixtures.andIsCreated
import io.tolgee.fixtures.andIsOk
import io.tolgee.testing.annotations.ProjectJWTAuthTestMethod
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Browsers fetch `<video>` sources with `Range` requests; without them an MP4 whose index
 * (`moov`) sits at the end of the file cannot be played at all.
 */
@SpringBootTest(
  properties = ["tolgee.internal.use-in-memory-file-storage=false"],
)
class BinaryAssetRangeDownloadTest : ProjectAuthControllerTest("/v2/projects/") {
  private val payload = byteArrayOf(0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16)

  @Test
  @ProjectJWTAuthTestMethod
  fun `serves the whole file and advertises byte ranges when no range is asked for`() {
    val token = ticket()
    val result = download(token).andExpect(status().isOk).andReturn()
    assertThat(result.response.contentAsByteArray).isEqualTo(payload)
    assertThat(result.response.getHeader(HttpHeaders.ACCEPT_RANGES)).isEqualTo("bytes")
    assertThat(result.response.getHeader(HttpHeaders.CONTENT_LENGTH)).isEqualTo("7")
    assertThat(result.response.getHeader(HttpHeaders.CONTENT_RANGE)).isNull()
  }

  @Test
  @ProjectJWTAuthTestMethod
  fun `serves a closed range as partial content`() {
    val token = ticket()
    val result = download(token, "bytes=2-4").andExpect(status().isPartialContent).andReturn()
    assertThat(result.response.contentAsByteArray).isEqualTo(byteArrayOf(0x12, 0x13, 0x14))
    assertThat(result.response.getHeader(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes 2-4/7")
    assertThat(result.response.getHeader(HttpHeaders.CONTENT_LENGTH)).isEqualTo("3")
    assertThat(result.response.getHeader(HttpHeaders.ACCEPT_RANGES)).isEqualTo("bytes")
    assertThat(result.response.getHeader(HttpHeaders.CONTENT_TYPE)).isEqualTo("video/mp4")
    assertThat(result.response.getHeader(HttpHeaders.CONTENT_DISPOSITION)).startsWith("inline;")
  }

  @Test
  @ProjectJWTAuthTestMethod
  fun `serves an open-ended range to the end of the file`() {
    val token = ticket()
    val result = download(token, "bytes=5-").andExpect(status().isPartialContent).andReturn()
    assertThat(result.response.contentAsByteArray).isEqualTo(byteArrayOf(0x15, 0x16))
    assertThat(result.response.getHeader(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes 5-6/7")
  }

  @Test
  @ProjectJWTAuthTestMethod
  fun `serves a suffix range`() {
    val token = ticket()
    val result = download(token, "bytes=-2").andExpect(status().isPartialContent).andReturn()
    assertThat(result.response.contentAsByteArray).isEqualTo(byteArrayOf(0x15, 0x16))
    assertThat(result.response.getHeader(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes 5-6/7")
  }

  @Test
  @ProjectJWTAuthTestMethod
  fun `clamps a range that runs past the end of the file`() {
    val token = ticket()
    val result = download(token, "bytes=3-100").andExpect(status().isPartialContent).andReturn()
    assertThat(result.response.contentAsByteArray).isEqualTo(byteArrayOf(0x13, 0x14, 0x15, 0x16))
    assertThat(result.response.getHeader(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes 3-6/7")
  }

  @Test
  @ProjectJWTAuthTestMethod
  fun `rejects a range starting past the end of the file`() {
    val token = ticket()
    val result =
      download(token, "bytes=7-").andExpect(status().isRequestedRangeNotSatisfiable).andReturn()
    assertThat(result.response.getHeader(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes */7")
    assertThat(result.response.contentAsByteArray).isEmpty()
  }

  @Test
  @ProjectJWTAuthTestMethod
  fun `answers a multi-range request with the whole file`() {
    val token = ticket()
    val result = download(token, "bytes=0-1,3-4").andExpect(status().isOk).andReturn()
    assertThat(result.response.contentAsByteArray).isEqualTo(payload)
    assertThat(result.response.getHeader(HttpHeaders.CONTENT_RANGE)).isNull()
  }

  @Test
  @ProjectJWTAuthTestMethod
  fun `ignores a malformed range header`() {
    val token = ticket()
    val result = download(token, "seconds=1-2").andExpect(status().isOk).andReturn()
    assertThat(result.response.contentAsByteArray).isEqualTo(payload)
  }

  @Test
  @ProjectJWTAuthTestMethod
  fun `answers a conditional range with the whole file because no validator is emitted`() {
    val token = ticket()
    val result =
      mvc
        .perform(
          MockMvcRequestBuilders
            .get("/v2/binary-assets/download")
            .param("token", token)
            .header(HttpHeaders.RANGE, "bytes=2-4")
            .header(HttpHeaders.IF_RANGE, "\"some-etag\""),
        ).andExpect(status().isOk)
        .andReturn()
    assertThat(result.response.contentAsByteArray).isEqualTo(payload)
  }

  @Test
  @ProjectJWTAuthTestMethod
  fun `a range request with a bad ticket is still not found`() {
    ticket()
    mvc
      .perform(
        MockMvcRequestBuilders
          .get("/v2/binary-assets/download")
          .param("token", "nope")
          .header(HttpHeaders.RANGE, "bytes=0-1"),
      ).andExpect(status().isNotFound)
  }

  private fun ticket(): String {
    val create =
      performProjectAuthMultipart(
        url = "binary-assets",
        files =
          listOf(
            MockMultipartFile("name", null, MediaType.TEXT_PLAIN_VALUE, "clip".toByteArray()),
            MockMultipartFile("file", "clip.mp4", "video/mp4", payload),
          ),
      ).andIsCreated.andReturn()
    val assetId = jacksonObjectMapper().readTree(create.response.contentAsString).get("id").asLong()
    val ticketResponse =
      performProjectAuthPost("binary-assets/$assetId/source/download-ticket", null).andIsOk.andReturn()
    return jacksonObjectMapper()
      .readTree(ticketResponse.response.contentAsString)
      .get("url")
      .asText()
      .substringAfter("token=")
  }

  private fun download(
    token: String,
    range: String? = null,
  ) = mvc.perform(
    MockMvcRequestBuilders
      .get("/v2/binary-assets/download")
      .param("token", token)
      .apply { if (range != null) header(HttpHeaders.RANGE, range) },
  )
}
