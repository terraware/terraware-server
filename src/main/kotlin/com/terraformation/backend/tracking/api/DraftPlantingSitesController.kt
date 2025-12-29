package com.terraformation.backend.tracking.api

import com.terraformation.backend.api.ArbitraryJsonObject
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.api.TrackingEndpoint
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.tracking.DraftPlantingSiteId
import com.terraformation.backend.db.tracking.tables.records.DraftPlantingSitesRecord
import com.terraformation.backend.tracking.db.DraftPlantingSiteStore
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.time.ZoneId
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RequestMapping("/api/v1/tracking/draftSites")
@RestController
@TrackingEndpoint
class DraftPlantingSitesController(
    private val draftPlantingSiteStore: DraftPlantingSiteStore,
) {
  @GetMapping("/{id}")
  @Operation(summary = "Gets the details of a saved draft of a planting site.")
  fun getDraftPlantingSite(
      @PathVariable id: DraftPlantingSiteId
  ): GetDraftPlantingSiteResponsePayload {
    return GetDraftPlantingSiteResponsePayload(draftPlantingSiteStore.fetchOneById(id))
  }

  @Operation(summary = "Saves a draft of an in-progress planting site.")
  @PostMapping
  fun createDraftPlantingSite(
      @RequestBody payload: CreateDraftPlantingSiteRequestPayload
  ): CreateDraftPlantingSiteResponsePayload {
    val record =
        draftPlantingSiteStore.create(
            data = payload.data,
            description = payload.description,
            name = payload.name,
            numSubstrata = payload.numPlantingSubzones,
            numStrata = payload.numPlantingZones,
            organizationId = payload.organizationId,
            projectId = payload.projectId,
            timeZone = payload.timeZone,
        )

    return CreateDraftPlantingSiteResponsePayload(record.id!!)
  }

  @Operation(summary = "Updates an existing draft of an in-progress planting site.")
  @PutMapping("/{id}")
  fun updateDraftPlantingSite(
      @PathVariable id: DraftPlantingSiteId,
      @RequestBody payload: UpdateDraftPlantingSiteRequestPayload,
  ): SimpleSuccessResponsePayload {
    draftPlantingSiteStore.update(id, payload::applyTo)

    return SimpleSuccessResponsePayload()
  }

  @DeleteMapping("/{id}")
  @Operation(summary = "Deletes an existing draft of an in-progress planting site.")
  fun deleteDraftPlantingSite(@PathVariable id: DraftPlantingSiteId): SimpleSuccessResponsePayload {
    draftPlantingSiteStore.delete(id)

    return SimpleSuccessResponsePayload()
  }
}

// response payload
data class DraftPlantingSitePayload(
    @Schema(
        description =
            "ID of the user who created this draft. Only that user is allowed to modify or " +
                "delete the draft."
    )
    val createdBy: UserId,
    val createdTime: Instant,
    @Schema(
        description =
            "In-progress state of the draft. This includes map data and other information needed " +
                "by the client. It is treated as opaque data by the server."
    )
    val data: ArbitraryJsonObject,
    val description: String?,
    val id: DraftPlantingSiteId,
    val modifiedTime: Instant,
    val name: String,
    @Schema(
        description =
            "If the user has started defining substrata, the number of substrata defined so far."
    )
    val numSubstrata: Int?,
    @Schema(
        description =
            "If the user has started defining strata, the number of strata defined so far."
    )
    val numStrata: Int?,
    val organizationId: OrganizationId,
    @Schema(description = "If the draft is associated with a project, its ID.")
    val projectId: ProjectId?,
    val timeZone: ZoneId?,
) {
  constructor(
      record: DraftPlantingSitesRecord
  ) : this(
      record.createdBy!!,
      record.createdTime!!,
      record.data!!,
      record.description,
      record.id!!,
      record.modifiedTime!!,
      record.name!!,
      record.numSubstrata,
      record.numStrata,
      record.organizationId!!,
      record.projectId,
      record.timeZone,
  )

  @Deprecated("Use numSubstrata instead.")
  val numPlantingSubzones: Int?
    get() = numSubstrata

  @Deprecated("Use numStrata instead.")
  val numPlantingZones: Int?
    get() = numStrata
}

data class GetDraftPlantingSiteResponsePayload(val site: DraftPlantingSitePayload) :
    SuccessResponsePayload {
  constructor(record: DraftPlantingSitesRecord) : this(DraftPlantingSitePayload(record))
}

data class CreateDraftPlantingSiteRequestPayload(
    @Schema(
        description =
            "In-progress state of the draft. This includes map data and other information needed " +
                "by the client. It is treated as opaque data by the server."
    )
    val data: ArbitraryJsonObject,
    val description: String?,
    val name: String,
    @Schema(
        description =
            "If the user has started defining substrata, the number of substrata defined " +
                "so far."
    )
    val numPlantingSubzones: Int?,
    @Schema(
        description =
            "If the user has started defining strata, the number of strata defined so far."
    )
    val numPlantingZones: Int?,
    val organizationId: OrganizationId,
    @Schema(description = "If the draft is associated with a project, its ID.")
    val projectId: ProjectId?,
    val timeZone: ZoneId?,
)

data class CreateDraftPlantingSiteResponsePayload(val id: DraftPlantingSiteId) :
    SuccessResponsePayload

data class UpdateDraftPlantingSiteRequestPayload(
    @Schema(
        description =
            "In-progress state of the draft. This includes map data and other information needed " +
                "by the client. It is treated as opaque data by the server."
    )
    val data: ArbitraryJsonObject,
    val description: String?,
    val name: String,
    @Schema(
        description =
            "If the user has started defining substrata, the number of substrata defined " +
                "so far."
    )
    val numPlantingSubzones: Int?,
    @Schema(
        description =
            "If the user has started defining strata, the number of strata defined so far."
    )
    val numPlantingZones: Int?,
    @Schema(description = "If the draft is associated with a project, its ID.")
    val projectId: ProjectId?,
    val timeZone: ZoneId?,
) {
  fun applyTo(record: DraftPlantingSitesRecord) {
    record.data = data
    record.description = description
    record.name = name
    record.numSubstrata = numPlantingSubzones
    record.numStrata = numPlantingZones
    record.projectId = projectId
    record.timeZone = timeZone
  }
}
