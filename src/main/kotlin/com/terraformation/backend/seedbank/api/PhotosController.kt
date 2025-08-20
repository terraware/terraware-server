package com.terraformation.backend.seedbank.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.terraformation.backend.api.ApiResponse200Photo
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.ApiResponseSimpleSuccess
import com.terraformation.backend.api.PHOTO_MAXHEIGHT_DESCRIPTION
import com.terraformation.backend.api.PHOTO_MAXWIDTH_DESCRIPTION
import com.terraformation.backend.api.PHOTO_OPERATION_DESCRIPTION
import com.terraformation.backend.api.RequestBodyPhotoFile
import com.terraformation.backend.api.SeedBankAppEndpoint
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.api.getPlainContentType
import com.terraformation.backend.api.toResponseEntity
import com.terraformation.backend.db.AccessionNotFoundException
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.file.SUPPORTED_PHOTO_TYPES
import com.terraformation.backend.file.model.ExistingFileMetadata
import com.terraformation.backend.file.model.FileMetadata
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.seedbank.db.PhotoRepository
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import jakarta.ws.rs.InternalServerErrorException
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.QueryParam
import java.nio.file.NoSuchFileException
import org.springframework.core.io.InputStreamResource
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@RequestMapping("/api/v1/seedbank/accessions/{id}/photos")
@RestController
@SeedBankAppEndpoint
class PhotosController(private val photoRepository: PhotoRepository) {
  private val log = perClassLogger()

  @ApiResponseSimpleSuccess
  @ApiResponse404("The specified accession does not exist.")
  @Operation(
      summary = "Upload a new photo for an accession.",
      description = "If there was already a photo with the specified filename, replaces it.",
  )
  @PostMapping("/{photoFilename}", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
  @RequestBodyPhotoFile
  fun uploadPhoto(
      @PathVariable("id") accessionId: AccessionId,
      @PathVariable photoFilename: String,
      @RequestPart("file") file: MultipartFile,
  ): SimpleSuccessResponsePayload {
    val contentType = file.getPlainContentType(SUPPORTED_PHOTO_TYPES)

    try {
      photoRepository.storePhoto(
          accessionId,
          file.inputStream,
          FileMetadata.of(contentType, photoFilename, file.size),
      )
    } catch (e: AccessionNotFoundException) {
      throw e
    } catch (e: Exception) {
      log.error("Unable to store photo $photoFilename for accession $accessionId", e)
      throw InternalServerErrorException("Unable to store the photo.")
    }

    return SimpleSuccessResponsePayload()
  }

  @ApiResponse200Photo
  @ApiResponse404(
      "The accession does not exist, or does not have a photo with the requested filename."
  )
  @GetMapping(
      "/{photoFilename}",
      produces = [MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE],
  )
  @Operation(
      summary = "Retrieve a specific photo from an accession.",
      description = PHOTO_OPERATION_DESCRIPTION,
  )
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
    return try {
      photoRepository.readPhoto(accessionId, photoFilename, maxWidth, maxHeight).toResponseEntity()
    } catch (e: NoSuchFileException) {
      throw NotFoundException("The accession does not have a photo named $photoFilename")
    }
  }

  @ApiResponse(
      responseCode = "200",
      description = "The accession's photos are listed in the response.",
  )
  @ApiResponse404("The accession does not exist.")
  @GetMapping
  @Operation(summary = "List all the available photos for an accession.")
  fun listPhotos(@PathVariable("id") accessionId: AccessionId): ListPhotosResponsePayload {
    val photos = photoRepository.listPhotos(accessionId)

    val elements = photos.map { ListPhotosResponseElement(it) }

    return ListPhotosResponsePayload(elements)
  }

  @ApiResponseSimpleSuccess
  @ApiResponse404("The accession does not exist.")
  @DeleteMapping("/{photoFilename}")
  @Operation(summary = "Delete one photo for an accession.")
  fun deletePhoto(
      @PathVariable("id") accessionId: AccessionId,
      @PathVariable photoFilename: String,
  ): SimpleSuccessResponsePayload {
    try {
      photoRepository.deletePhoto(accessionId, photoFilename)
    } catch (e: AccessionNotFoundException) {
      throw e
    } catch (e: Exception) {
      log.error("Unable to delete photo $photoFilename for accession $accessionId", e)
      throw InternalServerErrorException("Unable to delete the photo.")
    }
    return SimpleSuccessResponsePayload()
  }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ListPhotosResponseElement(
    val filename: String,
    val size: Long,
) {
  constructor(metadata: ExistingFileMetadata) : this(metadata.filename, metadata.size)
}

data class ListPhotosResponsePayload(val photos: List<ListPhotosResponseElement>) :
    SuccessResponsePayload
