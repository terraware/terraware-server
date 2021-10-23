package com.terraformation.backend.seedbank.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.ApiResponseSimpleSuccess
import com.terraformation.backend.api.DuplicateNameException
import com.terraformation.backend.api.InternalErrorException
import com.terraformation.backend.api.NotFoundException
import com.terraformation.backend.api.PHOTO_MAXHEIGHT_DESCRIPTION
import com.terraformation.backend.api.PHOTO_MAXWIDTH_DESCRIPTION
import com.terraformation.backend.api.PHOTO_OPERATION_DESCRIPTION
import com.terraformation.backend.api.SeedBankAppEndpoint
import com.terraformation.backend.api.SimpleErrorResponsePayload
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.api.UnsupportedPhotoFormatException
import com.terraformation.backend.db.AccessionId
import com.terraformation.backend.db.AccessionNotFoundException
import com.terraformation.backend.db.SRID
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.seedbank.db.PhotoRepository
import com.terraformation.backend.seedbank.model.PhotoMetadata
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Encoding
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.parameters.RequestBody
import io.swagger.v3.oas.annotations.responses.ApiResponse
import java.math.BigDecimal
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
import java.time.Instant
import javax.ws.rs.QueryParam
import net.postgis.jdbc.geometry.Point
import org.springframework.core.io.InputStreamResource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@RequestMapping("/api/v1/seedbank/accession/{id}/photo")
@RestController
@SeedBankAppEndpoint
class PhotoController(private val photoRepository: PhotoRepository) {
  private val log = perClassLogger()

  @ApiResponseSimpleSuccess
  @ApiResponse404("The specified accession does not exist.")
  @ApiResponse(
      responseCode = "409",
      description = "The requested photo already exists on the accession.",
      content =
          [
              Content(
                  schema = Schema(implementation = SimpleErrorResponsePayload::class),
                  mediaType = MediaType.APPLICATION_JSON_VALUE)])
  @Operation(summary = "Upload a new photo for an accession.")
  @PostMapping("/{photoFilename}", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
  @RequestBody(
      content =
          [Content(encoding = [Encoding(name = "file", contentType = MediaType.IMAGE_JPEG_VALUE)])])
  fun uploadPhoto(
      @PathVariable("id") accessionId: AccessionId,
      @PathVariable photoFilename: String,
      @RequestPart("file") file: MultipartFile,
      @RequestPart("metadata") metadata: UploadPhotoMetadataPayload
  ): SimpleSuccessResponsePayload {
    val contentType = file.contentType?.substringBefore(';')
    if (contentType != MediaType.IMAGE_JPEG_VALUE) {
      throw UnsupportedPhotoFormatException()
    }

    val location =
        metadata.location
            ?: metadata.longitude?.let { longitude ->
              metadata.latitude?.let { latitude ->
                Point(longitude.toDouble(), latitude.toDouble(), 0.0).apply { srid = SRID.LONG_LAT }
              }
            }

    try {
      photoRepository.storePhoto(
          accessionId,
          file.inputStream,
          file.size,
          PhotoMetadata(
              photoFilename,
              contentType,
              metadata.capturedTime,
              file.size,
              location,
              metadata.gpsAccuracy))
    } catch (e: AccessionNotFoundException) {
      throw NotFoundException("Accession $accessionId does not exist.")
    } catch (e: FileAlreadyExistsException) {
      log.info("Rejecting duplicate photo $photoFilename for accession $accessionId")
      throw DuplicateNameException(
          "Photo $photoFilename already exists for accession $accessionId.")
    } catch (e: Exception) {
      log.error("Unable to store photo $photoFilename for accession $accessionId", e)
      throw InternalErrorException("Unable to store the photo.")
    }

    return SimpleSuccessResponsePayload()
  }

  @ApiResponse(
      responseCode = "200",
      description = "The photo was successfully retrieved.",
      content =
          [
              Content(
                  schema = Schema(type = "string", format = "binary"),
                  mediaType = MediaType.IMAGE_JPEG_VALUE)])
  @ApiResponse404(
      "The accession does not exist, or does not have a photo with the requested filename.")
  @GetMapping("/{photoFilename}", produces = [MediaType.IMAGE_JPEG_VALUE])
  @Operation(
      summary = "Retrieve a specific photo from an accession.",
      description = PHOTO_OPERATION_DESCRIPTION)
  @ResponseBody
  fun getPhoto(
      @PathVariable("id") accessionId: AccessionId,
      @PathVariable photoFilename: String,
      @QueryParam("maxWidth")
      @Schema(description = PHOTO_MAXWIDTH_DESCRIPTION)
      maxWidth: Int? = null,
      @QueryParam("maxHeight")
      @Schema(description = PHOTO_MAXHEIGHT_DESCRIPTION)
      maxHeight: Int? = null,
  ): ResponseEntity<InputStreamResource> {
    val headers = HttpHeaders()

    try {
      val inputStream = photoRepository.readPhoto(accessionId, photoFilename, maxWidth, maxHeight)
      headers.contentLength = inputStream.size

      val resource = InputStreamResource(inputStream)
      return ResponseEntity(resource, headers, HttpStatus.OK)
    } catch (e: NoSuchFileException) {
      throw NotFoundException("The accession does not have a photo named $photoFilename")
    }
  }

  @ApiResponse(
      responseCode = "200", description = "The accession's photos are listed in the response.")
  @ApiResponse404("The accession does not exist.")
  @GetMapping
  @Operation(summary = "List all the available photos for an accession.")
  fun listPhotos(@PathVariable("id") accessionId: AccessionId): ListPhotosResponsePayload {
    val photos =
        try {
          photoRepository.listPhotos(accessionId)
        } catch (e: AccessionNotFoundException) {
          throw NotFoundException("Accession $accessionId does not exist.")
        }

    val elements = photos.map { ListPhotosResponseElement(it) }

    return ListPhotosResponsePayload(elements)
  }
}

// JsonDeserialize annotation is needed by OctetStreamJsonConverter
@JsonDeserialize
data class UploadPhotoMetadataPayload(
    val capturedTime: Instant,
    @Schema(deprecated = true, description = "Use location field instead.")
    val latitude: BigDecimal?,
    @Schema(deprecated = true, description = "Use location field instead.")
    val longitude: BigDecimal?,
    val location: Point?,
    @Schema(
        description = "GPS accuracy in meters.",
    )
    val gpsAccuracy: Int?,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ListPhotosResponseElement(
    val filename: String,
    val size: Long,
    val capturedTime: Instant,
    @Schema(
        deprecated = true,
        description = "Use location field instead.",
    )
    val latitude: Double?,
    @Schema(
        deprecated = true,
        description = "Use location field instead.",
    )
    val longitude: Double?,
    val location: Point?,
    @Schema(
        description = "GPS accuracy in meters.",
    )
    val gpsAccuracy: Int?,
) {
  constructor(
      metadata: PhotoMetadata
  ) : this(
      metadata.filename,
      metadata.size,
      metadata.capturedTime,
      metadata.location?.y,
      metadata.location?.x,
      metadata.location,
      metadata.gpsAccuracy)
}

data class ListPhotosResponsePayload(val photos: List<ListPhotosResponseElement>) :
    SuccessResponsePayload
