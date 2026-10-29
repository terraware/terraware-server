package com.terraformation.backend.customer.api

import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.ApiResponse409
import com.terraformation.backend.api.ArbitraryJsonObject
import com.terraformation.backend.api.CustomerEndpoint
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.customer.db.SavedTableViewStore
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.SavedTableViewId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@CustomerEndpoint
@RequestMapping("/api/v1/users/me/savedTableViews")
@RestController
class SavedTableViewsController(private val savedTableViewStore: SavedTableViewStore) {
  @ApiResponse200
  @ApiResponse409("The current user already has a view with this name for this table.")
  @Operation(summary = "Creates a saved table view for the current user.")
  @PostMapping
  fun createSavedTableView(
      @RequestBody payload: CreateSavedTableViewRequestPayload
  ): CreateSavedTableViewResponsePayload {
    val id =
        savedTableViewStore.create(
            name = payload.name,
            organizationId = payload.organizationId,
            settings = payload.settings,
            tableName = payload.tableName,
        )

    return CreateSavedTableViewResponsePayload(id)
  }
}

data class CreateSavedTableViewRequestPayload(
    @Schema(
        description = "Name of the view. Must be unique per table and organization (for the user)."
    )
    val name: String,
    @Schema(
        description =
            "If present, the view is specific to this organization. If not present, the view is " +
                "global to the user."
    )
    val organizationId: OrganizationId?,
    @Schema(
        description =
            "Client-defined view settings such as filters, search, sort order, visible columns, " +
                "and column order."
    )
    val settings: ArbitraryJsonObject,
    @Schema(description = "Client-defined identifier of the table this view applies to.")
    val tableName: String,
)

data class CreateSavedTableViewResponsePayload(val id: SavedTableViewId) : SuccessResponsePayload
