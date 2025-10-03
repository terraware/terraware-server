package com.terraformation.backend.file.convertapi

import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.file.FileService
import com.terraformation.backend.file.JpegConverter
import com.terraformation.backend.file.SizedInputStream
import com.terraformation.backend.file.ThumbnailNotReadyException
import com.terraformation.backend.log.debugWithTiming
import com.terraformation.backend.log.perClassLogger
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import jakarta.inject.Named
import java.net.URI
import kotlinx.coroutines.runBlocking
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.UnsupportedMediaTypeException

@Named
class ConvertApiService(
    private val config: TerrawareServerConfig,
    private val fileService: FileService,
    private val httpClient: HttpClient,
) : JpegConverter {
  private val formatNames: Map<MediaType, String> =
      mapOf(
          MediaType.valueOf("image/heic") to "heic",
          MediaType.IMAGE_JPEG to "jpg",
      )
  private val supportedFormats = formatNames.keys.toList()

  private val convertApiBaseUrl = URI("https://v2.convertapi.com/")
  private val log = perClassLogger()

  override fun canConvertToJpeg(mimeType: String): Boolean {
    return MediaType.valueOf(mimeType) in supportedFormats
  }

  override fun convertToJpeg(fileId: FileId): ByteArray {
    ensureEnabled()

    val originalFile = fileService.readFile(fileId)
    return try {
      convertFile(originalFile, MediaType.IMAGE_JPEG)
    } catch (_: ConvertApiServiceUnavailableException) {
      // This is likely due to throttling, so treat it as a "thumbnail isn't ready yet" condition.
      // Clients can choose to retry later.
      log.info("ConvertAPI service unavailable")
      throw ThumbnailNotReadyException(fileId)
    }
  }

  fun convertFile(original: SizedInputStream, targetFormat: MediaType): ByteArray {
    ensureEnabled()

    val originalContentType =
        original.contentType ?: throw IllegalArgumentException("Original content type unknown")
    val originalFormatName = getFormatName(originalContentType)
    val targetFormatName = getFormatName(targetFormat)
    val url =
        convertApiBaseUrl.resolve("/convert/$originalFormatName/to/$targetFormatName").toString()

    return log.debugWithTiming("Converted ${original.contentType} to $targetFormatName") {
      runBlocking {
        val response =
            httpClient.post(url) {
              bearerAuth(config.convertApi.apiKey!!)
              contentType(ContentType.Application.OctetStream)
              header("Content-Disposition", "attachment; filename=file.$originalFormatName")
              header("Content-Length", original.size)

              setBody(original)

              expectSuccess = false
            }

        if (response.status == HttpStatusCode.ServiceUnavailable) {
          // Probably throttled; ConvertAPI limits the number of concurrent conversions.
          throw ConvertApiServiceUnavailableException()
        }

        if (!response.status.isSuccess()) {
          throw ConvertApiErrorException(response.body())
        }

        val responsePayload: ConvertApiSuccessResponse = response.body()
        val convertedFile = responsePayload.files.first()

        convertedFile.fileData
      }
    }
  }

  private fun getFormatName(mediaType: MediaType): String {
    return formatNames[mediaType]
        ?: throw UnsupportedMediaTypeException(mediaType, supportedFormats)
  }

  private fun ensureEnabled() {
    if (!config.convertApi.enabled) {
      throw IllegalStateException("ConvertAPI is disabled")
    }
  }
}
