package com.terraformation.backend.customer.api

import com.terraformation.backend.api.InternalEndpoint
import com.terraformation.backend.api.RequireGlobalRole
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.customer.db.InternalTagStore
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.InternalTagId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.tables.pojos.InternalTagsRow
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@InternalEndpoint
@RequestMapping("/api/v1")
@RequireGlobalRole([GlobalRole.SuperAdmin])
@RestController
class InternalTagsController(
    private val internalTagStore: InternalTagStore,
) {
  @GetMapping("/internalTags")
  @Operation(summary = "List all the available internal tags")
  fun listAllInternalTags(): ListAllInternalTagsResponsePayload {
    val rows = internalTagStore.findAllTags()

    return ListAllInternalTagsResponsePayload(rows.map { InternalTagPayload(it) })
  }

  @GetMapping("/organizations/{organizationId}/internalTags")
  @Operation(summary = "List the internal tags assigned to an organization")
  fun listOrganizationInternalTags(
      @PathVariable organizationId: OrganizationId
  ): ListOrganizationInternalTagsResponsePayload {
    val tagIds = internalTagStore.fetchTagsByOrganization(organizationId)

    return ListOrganizationInternalTagsResponsePayload(tagIds)
  }

  @PutMapping("/organizations/{organizationId}/internalTags")
  @Operation(summary = "Replace the list of internal tags assigned to an organization")
  fun updateOrganizationInternalTags(
      @PathVariable organizationId: OrganizationId,
      @RequestBody payload: UpdateOrganizationInternalTagsRequestPayload
  ): SimpleSuccessResponsePayload {
    internalTagStore.updateOrganizationTags(organizationId, payload.tagIds)

    return SimpleSuccessResponsePayload()
  }
}

data class InternalTagPayload(
    val id: InternalTagId,
    @Schema(
        description =
            "If true, this internal tag is system-defined and may affect the behavior of the " +
                "application. If falso, the tag is admin-defined and is only used for reporting.")
    val isSystem: Boolean,
    val name: String,
) {
  constructor(
      row: InternalTagsRow
  ) : this(id = row.id!!, name = row.name!!, isSystem = row.isSystem!!)
}

data class ListAllInternalTagsResponsePayload(val tags: List<InternalTagPayload>) :
    SuccessResponsePayload

data class ListOrganizationInternalTagsResponsePayload(val tagIds: Set<InternalTagId>) :
    SuccessResponsePayload

data class UpdateOrganizationInternalTagsRequestPayload(val tagIds: Set<InternalTagId>)
