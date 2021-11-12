package com.terraformation.backend.gis.api

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.GISAppEndpoint
import com.terraformation.backend.api.PHOTO_MAXHEIGHT_DESCRIPTION
import com.terraformation.backend.api.PHOTO_MAXWIDTH_DESCRIPTION
import com.terraformation.backend.api.PHOTO_OPERATION_DESCRIPTION
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.FeatureId
import com.terraformation.backend.db.FeatureNotFoundException
import com.terraformation.backend.db.LayerId
import com.terraformation.backend.db.PhotoId
import com.terraformation.backend.db.SpeciesId
import com.terraformation.backend.db.tables.pojos.PhotosRow
import com.terraformation.backend.db.tables.pojos.PlantsRow
import com.terraformation.backend.gis.db.FeatureStore
import com.terraformation.backend.gis.model.FeatureModel
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Encoding
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import java.time.Instant
import java.time.LocalDate
import javax.ws.rs.BadRequestException
import javax.ws.rs.QueryParam
import net.postgis.jdbc.geometry.Geometry
import net.postgis.jdbc.geometry.Point
import org.springframework.core.io.InputStreamResource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@RequestMapping("/api/v1/gis/features")
@RestController
@GISAppEndpoint
class FeatureController(private val featureStore: FeatureStore) {
  @ApiResponse(
      responseCode = "200",
      description =
          "The feature was created successfully. Response includes fields populated by the " +
              "server, including the feature id.")
  @Operation(summary = "Create a new feature.")
  @PostMapping
  fun create(@RequestBody payload: CreateFeatureRequestPayload): CreateFeatureResponsePayload {
    val updatedModel = featureStore.createFeature(payload.toModel())
    return CreateFeatureResponsePayload(FeatureResponse(updatedModel))
  }

  @ApiResponse(responseCode = "200")
  @ApiResponse404(description = "The specified feature doesn't exist.")
  @GetMapping("/{featureId}")
  fun read(@PathVariable featureId: FeatureId): GetFeatureResponsePayload {
    val model = featureStore.fetchFeature(featureId) ?: throw FeatureNotFoundException(featureId)

    return GetFeatureResponsePayload(FeatureResponse(model))
  }

  @ApiResponse(responseCode = "200")
  @Operation(
      summary =
          "Lists the features associated with a layer. Can optionally filter by entered time, " +
              "notes, and (for plants) species name.")
  @GetMapping("/list/{layerId}")
  fun list(
      @QueryParam("minEnteredTime") minEnteredTime: Instant? = null,
      @QueryParam("maxEnteredTime") maxEnteredTime: Instant? = null,
      @QueryParam("notes") notes: String? = null,
      @QueryParam("speciesName") speciesName: String? = null,
      @QueryParam("skip")
      @Schema(
          description =
              "Number of entries to skip in search results. Used in conjunction with limit to " +
                  "paginate through large results. Default is 0 (don't skip any results).")
      skip: Int? = null,
      @QueryParam("limit")
      @Schema(
          description =
              "Maximum number of entries to return. Used in conjunction with skip to paginate " +
                  "through large results. The system may impose a cap on this value.")
      limit: Int? = null,
      @PathVariable layerId: LayerId
  ): ListFeaturesResponsePayload {
    val features =
        featureStore.listFeatures(
            layerId = layerId,
            skip = skip,
            limit = limit,
            speciesName = speciesName,
            minEnteredTime = minEnteredTime,
            maxEnteredTime = maxEnteredTime,
            notes = notes)
    val totalCount = featureStore.countFeatures(layerId)
    return ListFeaturesResponsePayload(features.map { FeatureResponse(it) }, totalCount)
  }

  @ApiResponse(responseCode = "200", description = "The feature was updated successfully.")
  @ApiResponse404(description = "The specified feature doesn't exist.")
  @Operation(
      summary =
          "Updates an existing feature. If plant field is null or omitted, existing plant " +
              "details are preserved.")
  @PutMapping("/{featureId}")
  fun update(
      @RequestBody payload: UpdateFeatureRequestPayload,
      @PathVariable featureId: FeatureId,
  ): UpdateFeatureResponsePayload {
    val updatedModel = featureStore.updateFeature(payload.toModel(featureId))
    return UpdateFeatureResponsePayload(FeatureResponse(updatedModel))
  }

  @ApiResponse(responseCode = "200")
  @ApiResponse404(description = "The specified feature doesn't exist.")
  @Operation(
      summary =
          "Deletes an existing feature and all records that directly or indirectly reference that" +
              "feature. This includes but is not limited to plants, plant observations, photos, " +
              "and thumbnails.")
  @DeleteMapping("/{featureId}")
  fun delete(@PathVariable featureId: FeatureId): DeleteFeatureResponsePayload {
    featureStore.deleteFeature(featureId)
    return DeleteFeatureResponsePayload(featureId)
  }

  @Operation(summary = "Uploads a new photo of a feature.")
  @PostMapping("/{featureId}/photos", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
  @io.swagger.v3.oas.annotations.parameters.RequestBody(
      content =
          [Content(encoding = [Encoding(name = "file", contentType = MediaType.IMAGE_JPEG_VALUE)])])
  fun createFeaturePhoto(
      @PathVariable featureId: FeatureId,
      @RequestPart("metadata") payload: CreateFeaturePhotoRequestPayload,
      @RequestPart("file") file: MultipartFile
  ): CreateFeaturePhotoResponsePayload {
    val contentType = file.contentType ?: throw BadRequestException("No content type specified")
    val photosRow =
        PhotosRow(
            capturedTime = payload.capturedTime,
            contentType = contentType,
            fileName = file.originalFilename,
            gpsHorizAccuracy = payload.gpsHorizAccuracy,
            gpsVertAccuracy = payload.gpsVertAccuracy,
            heading = payload.heading,
            location = payload.location,
            orientation = payload.orientation,
            size = file.size,
            userId = currentUser().userId,
        )

    val photoId = featureStore.createPhoto(featureId, photosRow, file.inputStream)

    return CreateFeaturePhotoResponsePayload(photoId)
  }

  @GetMapping("/{featureId}/photos")
  fun listFeaturePhotos(@PathVariable featureId: FeatureId): ListFeaturePhotosResponsePayload {
    val photos = featureStore.listPhotos(featureId)
    return ListFeaturePhotosResponsePayload(photos.map { FeaturePhoto(featureId, it) })
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
  @GetMapping("/{featureId}/photos/{photoId}", produces = [MediaType.IMAGE_JPEG_VALUE])
  @Operation(
      summary = "Gets the contents of a photo of a feature.",
      description = PHOTO_OPERATION_DESCRIPTION)
  @ResponseBody
  fun downloadFeaturePhoto(
      @PathVariable featureId: FeatureId,
      @PathVariable photoId: PhotoId,
      @QueryParam("maxWidth")
      @Schema(description = PHOTO_MAXWIDTH_DESCRIPTION)
      maxWidth: Int? = null,
      @QueryParam("maxHeight")
      @Schema(description = PHOTO_MAXHEIGHT_DESCRIPTION)
      maxHeight: Int? = null,
  ): ResponseEntity<InputStreamResource> {
    val stream = featureStore.getPhotoData(featureId, photoId, maxWidth, maxHeight)
    val headers = HttpHeaders()
    headers.contentLength = stream.size

    val resource = InputStreamResource(stream)
    return ResponseEntity(resource, headers, HttpStatus.OK)
  }

  @ApiResponse(responseCode = "200", description = "Photo metadata retrieved.")
  @ApiResponse404
  @GetMapping("/{featureId}/photos/{photoId}/metadata")
  @Operation(summary = "Gets information about a photo of a feature.")
  fun getFeaturePhotoMetadata(
      @PathVariable featureId: FeatureId,
      @PathVariable photoId: PhotoId
  ): GetFeaturePhotoMetadataResponsePayload {
    val photosRow = featureStore.getPhotoMetadata(featureId, photoId)
    return GetFeaturePhotoMetadataResponsePayload(FeaturePhoto(featureId, photosRow))
  }

  @ApiResponse(responseCode = "200", description = "Photo deleted.")
  @ApiResponse404
  @DeleteMapping("/{featureId}/photos/{photoId}")
  @Operation(summary = "Deletes a photo of a feature.")
  fun deleteFeaturePhoto(
      @PathVariable featureId: FeatureId,
      @PathVariable photoId: PhotoId
  ): SimpleSuccessResponsePayload {
    featureStore.deletePhoto(featureId, photoId)
    return SimpleSuccessResponsePayload()
  }
}

data class CreateFeaturePhotoRequestPayload(
    val capturedTime: Instant,
    @Schema(description = "Compass heading of phone/camera when photo was taken.")
    val heading: Double? = null,
    val location: Point?,
    @Schema(description = "Orientation of phone/camera when photo was taken.")
    val orientation: Double?,
    @Schema(
        description = "GPS horizontal accuracy in meters.",
    )
    val gpsHorizAccuracy: Double?,
    @Schema(description = "GPS vertical (altitude) accuracy in meters.")
    val gpsVertAccuracy: Double?
)

data class CreateFeatureRequestPayload(
    val layerId: LayerId,
    val geom: Geometry? = null,
    val gpsHorizAccuracy: Double? = null,
    val gpsVertAccuracy: Double? = null,
    val attrib: String? = null,
    val notes: String? = null,
    val enteredTime: Instant? = null,
    val plant: PlantDetailsPayload? = null,
) {
  fun toModel(): FeatureModel {
    return FeatureModel(
        layerId = layerId,
        geom = geom,
        gpsHorizAccuracy = gpsHorizAccuracy,
        gpsVertAccuracy = gpsVertAccuracy,
        attrib = attrib,
        notes = notes,
        enteredTime = enteredTime,
        plant = plant?.toRow(),
    )
  }
}

@JsonIgnoreProperties("layerId") // For backward compatibility; used to be a required field.
data class UpdateFeatureRequestPayload(
    val geom: Geometry? = null,
    val gpsHorizAccuracy: Double? = null,
    val gpsVertAccuracy: Double? = null,
    val attrib: String? = null,
    val notes: String? = null,
    val enteredTime: Instant? = null,
    val plant: PlantDetailsPayload? = null,
) {
  fun toModel(id: FeatureId): FeatureModel {
    return FeatureModel(
        id = id,
        geom = geom,
        gpsHorizAccuracy = gpsHorizAccuracy,
        gpsVertAccuracy = gpsVertAccuracy,
        attrib = attrib,
        notes = notes,
        enteredTime = enteredTime,
        plant = plant?.toRow(),
    )
  }
}

data class FeaturePhoto(
    val capturedTime: Instant,
    val contentType: String,
    val featureId: FeatureId,
    val fileName: String,
    @Schema(
        description = "GPS horizontal accuracy in meters.",
    )
    val gpsHorizAccuracy: Double?,
    @Schema(description = "GPS vertical (altitude) accuracy in meters.")
    val gpsVertAccuracy: Double?,
    @Schema(description = "Compass heading of phone/camera when photo was taken.")
    val heading: Double?,
    val id: PhotoId,
    val location: Point?,
    @Schema(description = "Orientation of phone/camera when photo was taken.")
    val orientation: Double?,
    val size: Long,
) {
  constructor(
      featureId: FeatureId,
      photosRow: PhotosRow
  ) : this(
      capturedTime = photosRow.capturedTime!!,
      contentType = photosRow.contentType!!,
      featureId = featureId,
      fileName = photosRow.fileName!!,
      gpsHorizAccuracy = photosRow.gpsHorizAccuracy,
      gpsVertAccuracy = photosRow.gpsVertAccuracy,
      heading = photosRow.heading,
      id = photosRow.id!!,
      location = photosRow.location?.firstPoint,
      orientation = photosRow.orientation,
      size = photosRow.size!!)
}

@Schema(
    description =
        "Describes a map feature. The coordinate reference system of the \"geom\" field will be " +
            "longitude/latitude EPSG:4326.")
data class FeatureResponse(
    val id: FeatureId,
    val layerId: LayerId,
    val geom: Geometry? = null,
    val gpsHorizAccuracy: Double? = null,
    val gpsVertAccuracy: Double? = null,
    val attrib: String? = null,
    val notes: String? = null,
    val enteredTime: Instant? = null,
    val plant: PlantDetailsPayload? = null,
) {
  constructor(
      model: FeatureModel
  ) : this(
      model.id!!,
      model.layerId!!,
      model.geom,
      model.gpsHorizAccuracy,
      model.gpsVertAccuracy,
      model.attrib,
      model.notes,
      model.enteredTime,
      model.plant?.let { PlantDetailsPayload(it) },
  )
}

@Schema(description = "Additional details for features that represent plants.")
data class PlantDetailsPayload(
    val datePlanted: LocalDate? = null,
    val label: String? = null,
    val naturalRegen: Boolean? = null,
    val speciesId: SpeciesId? = null,
) {
  constructor(
      row: PlantsRow
  ) : this(
      datePlanted = row.datePlanted,
      label = row.label,
      naturalRegen = row.naturalRegen,
      speciesId = row.speciesId,
  )

  fun toRow(): PlantsRow {
    return PlantsRow(
        datePlanted = datePlanted,
        label = label,
        naturalRegen = naturalRegen,
        speciesId = speciesId,
    )
  }
}

data class CreateFeaturePhotoResponsePayload(val photoId: PhotoId) : SuccessResponsePayload

data class CreateFeatureResponsePayload(val feature: FeatureResponse) : SuccessResponsePayload

data class GetFeatureResponsePayload(val feature: FeatureResponse) : SuccessResponsePayload

data class ListFeaturePhotosResponsePayload(val photos: List<FeaturePhoto>) :
    SuccessResponsePayload

data class ListFeaturesResponsePayload(val features: List<FeatureResponse>, val totalCount: Int) :
    SuccessResponsePayload

data class GetFeaturePhotoMetadataResponsePayload(val photo: FeaturePhoto) : SuccessResponsePayload

data class UpdateFeatureResponsePayload(val feature: FeatureResponse) : SuccessResponsePayload

data class DeleteFeatureResponsePayload(val id: FeatureId) : SuccessResponsePayload
