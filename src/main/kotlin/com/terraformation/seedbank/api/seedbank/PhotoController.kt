package com.terraformation.seedbank.api.seedbank

import com.fasterxml.jackson.annotation.JsonInclude
import com.terraformation.seedbank.api.NotFoundException
import com.terraformation.seedbank.api.SimpleSuccessResponsePayload
import com.terraformation.seedbank.api.SuccessResponsePayload
import com.terraformation.seedbank.api.UnsupportedPhotoFormatException
import com.terraformation.seedbank.api.annotation.ApiResponse404
import com.terraformation.seedbank.api.annotation.ApiResponseSimpleSuccess
import com.terraformation.seedbank.api.annotation.SeedBankAppEndpoint
import com.terraformation.seedbank.db.AccessionFetcher
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Encoding
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.parameters.RequestBody
import io.swagger.v3.oas.annotations.responses.ApiResponse
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@RequestMapping("/api/v1/seedbank/accession/{accessionNumber}/photo")
@RestController
@SeedBankAppEndpoint
class PhotoController(private val accessionFetcher: AccessionFetcher) {
  // TODO: Store photos in the database!
  private val tempMetadata = ConcurrentHashMap<String, ConcurrentHashMap<String, PhotoMetadata>>()
  private val tempImages = ConcurrentHashMap<String, ConcurrentHashMap<String, ByteArray>>()

  @ApiResponseSimpleSuccess
  @ApiResponse404("The specified accession does not exist.")
  @Operation(summary = "Upload a new photo for an accession.")
  @PostMapping("/{photoFilename}", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
  @RequestBody(
      content =
          [Content(encoding = [Encoding(name = "file", contentType = MediaType.IMAGE_JPEG_VALUE)])])
  fun uploadPhoto(
      @PathVariable accessionNumber: String,
      @PathVariable photoFilename: String,
      @RequestPart("file") file: MultipartFile,
      @RequestPart("metadata") metadata: PhotoMetadata
  ): SimpleSuccessResponsePayload {
    if (accessionFetcher.fetchByNumber(accessionNumber) == null) {
      throw NotFoundException("Accession $accessionNumber does not exist.")
    }

    val contentType = file.contentType?.substringBefore(';')
    if (contentType != MediaType.IMAGE_JPEG_VALUE) {
      throw UnsupportedPhotoFormatException()
    }

    tempMetadata.computeIfAbsent(accessionNumber) { ConcurrentHashMap() }[photoFilename] = metadata
    tempImages.computeIfAbsent(accessionNumber) { ConcurrentHashMap() }[photoFilename] = file.bytes

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
  @Operation(summary = "Retrieve a specific photo from an accession.")
  @ResponseBody
  fun getPhoto(
      @PathVariable accessionNumber: String,
      @PathVariable photoFilename: String
  ): ByteArray {
    if (accessionFetcher.fetchByNumber(accessionNumber) == null) {
      throw NotFoundException("Accession $accessionNumber does not exist.")
    }

    val photosForAccession = tempImages[accessionNumber]
    val imageData =
        photosForAccession?.get(photoFilename)
            ?: throw NotFoundException("The accession does not have a photo named $photoFilename")

    return imageData
  }

  @ApiResponse(
      responseCode = "200", description = "The accession's photos are listed in the response.")
  @ApiResponse404("The accession does not exist.")
  @GetMapping
  @Operation(summary = "List all the available photos for an accession.")
  fun listPhotos(@PathVariable accessionNumber: String): ListPhotosResponsePayload {
    if (accessionFetcher.fetchByNumber(accessionNumber) == null) {
      throw NotFoundException("Accession $accessionNumber does not exist.")
    }

    val photos = tempMetadata[accessionNumber] ?: emptyMap()

    return ListPhotosResponsePayload(
        photos.map {
          ListPhotosResponseElement(it.key, tempImages[accessionNumber]!![it.key]!!.size, it.value)
        })
  }
}

data class PhotoMetadata(
    val capturedTime: Instant,
    val latitude: BigDecimal?,
    val longitude: BigDecimal?,
    @Schema(description = "GPS accuracy in meters.") val gpsAccuracy: Int?,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ListPhotosResponseElement(
    val filename: String,
    val size: Int,
    val capturedTime: Instant,
    val latitude: BigDecimal?,
    val longitude: BigDecimal?,
    @Schema(description = "GPS accuracy in meters.") val gpsAccuracy: Int?,
) {
  constructor(
      filename: String,
      size: Int,
      metadata: PhotoMetadata
  ) : this(
      filename,
      size,
      metadata.capturedTime,
      metadata.latitude,
      metadata.longitude,
      metadata.gpsAccuracy)
}

data class ListPhotosResponsePayload(val photos: List<ListPhotosResponseElement>) :
    SuccessResponsePayload
