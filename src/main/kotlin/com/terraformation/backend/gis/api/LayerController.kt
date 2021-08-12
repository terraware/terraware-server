package com.terraformation.backend.gis.api

import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.GISAppEndpoint
import com.terraformation.backend.api.NotFoundException
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.LayerId
import com.terraformation.backend.db.LayerNotFoundException
import com.terraformation.backend.db.LayerType
import com.terraformation.backend.db.SiteId
import com.terraformation.backend.gis.db.LayerStore
import com.terraformation.backend.gis.model.LayerModel
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RequestMapping("/api/v1/gis/layer")
@RestController
@GISAppEndpoint
class LayerController(private val layerStore: LayerStore) {
  @ApiResponse(
      responseCode = "200",
      description =
          "The layer was created successfully. Response includes fields populated by the " +
              "server, including the layer id.")
  @Operation(summary = "Create a new layer.")
  @PostMapping
  fun create(@RequestBody payload: CreateLayerRequestPayload): CreateLayerResponsePayload {
    val updatedModel = layerStore.createLayer(payload.toModel())
    return CreateLayerResponsePayload(LayerResponse(updatedModel))
  }

  @ApiResponse(responseCode = "200")
  @ApiResponse404(description = "The specified layer doesn't exist.")
  @GetMapping("/{layerId}")
  fun read(@PathVariable layerId: Long): GetLayerResponsePayload {
    val layerModel =
        layerStore.fetchLayer(LayerId(layerId))
            ?: throw NotFoundException("The layer with id $layerId doesn't exist.")

    return GetLayerResponsePayload(LayerResponse(layerModel))
  }

  @ApiResponse(
      responseCode = "200",
      description = "The layer was updated successfully. Response includes a unique layer id.")
  @ApiResponse404(description = "The specified layer doesn't exist.")
  @Operation(
      summary =
          "Update an existing layer. Overwrites all fields, so they must all be defined. " +
              "Does not allow a layer to be moved between sites (siteId cannot be updated)")
  @PutMapping("/{layerId}")
  fun update(
      @RequestBody payload: UpdateLayerRequestPayload,
      @PathVariable layerId: Long,
  ): UpdateLayerResponsePayload {
    try {
      val updatedModel = layerStore.updateLayer(payload.toModel(id = LayerId(layerId)))
      return UpdateLayerResponsePayload(LayerResponse(updatedModel))
    } catch (e: LayerNotFoundException) {
      throw NotFoundException(
          "The layer with id $layerId, site ID ${payload.siteId} doesn't exist.")
    }
  }

  @ApiResponse(responseCode = "200")
  @ApiResponse404(description = "The specified layer doesn't exist.")
  @DeleteMapping("/{layerId}")
  fun delete(@PathVariable layerId: Long): DeleteLayerResponsePayload {
    try {
      val layerModel = layerStore.deleteLayer(LayerId(layerId))
      return DeleteLayerResponsePayload(DeleteLayerResponse(layerModel))
    } catch (e: LayerNotFoundException) {
      throw NotFoundException("The layer with id $layerId doesn't exist.")
    }
  }
}

data class CreateLayerRequestPayload(
    val siteId: SiteId,
    val layerType: LayerType,
    val tileSetName: String,
    val proposed: Boolean,
    val hidden: Boolean,
) {
  fun toModel(): LayerModel {
    return LayerModel(
        siteId = siteId,
        layerType = layerType,
        tileSetName = tileSetName,
        proposed = proposed,
        hidden = hidden)
  }
}

data class UpdateLayerRequestPayload(
    val siteId: SiteId,
    val layerType: LayerType,
    val tileSetName: String,
    val proposed: Boolean,
    val hidden: Boolean,
) {
  fun toModel(id: LayerId): LayerModel {
    return LayerModel(
        id = id,
        siteId = siteId,
        layerType = layerType,
        tileSetName = tileSetName,
        proposed = proposed,
        hidden = hidden)
  }
}

data class LayerResponse(
    val id: LayerId,
    val siteId: SiteId,
    val layerType: LayerType,
    val tileSetName: String,
    val proposed: Boolean,
    val hidden: Boolean,
) {
  constructor(
      model: LayerModel,
  ) : this(
      model.id!!,
      model.siteId,
      model.layerType,
      model.tileSetName,
      model.proposed,
      model.hidden,
  )
}

data class DeleteLayerResponse(
    val id: LayerId,
    val siteId: SiteId,
) {
  constructor(
      model: LayerModel,
  ) : this(
      model.id!!,
      model.siteId,
  )
}

data class CreateLayerResponsePayload(val layer: LayerResponse) : SuccessResponsePayload

data class UpdateLayerResponsePayload(val layer: LayerResponse) : SuccessResponsePayload

data class GetLayerResponsePayload(val layer: LayerResponse) : SuccessResponsePayload

data class DeleteLayerResponsePayload(val layer: DeleteLayerResponse) : SuccessResponsePayload
