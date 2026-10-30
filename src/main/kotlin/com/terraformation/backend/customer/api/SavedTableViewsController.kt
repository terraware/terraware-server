package com.terraformation.backend.customer.api

import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.ApiResponse409
import com.terraformation.backend.api.ArbitraryJsonObject
import com.terraformation.backend.api.CustomerEndpoint
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.customer.db.SavedTableViewStore
import com.terraformation.backend.customer.model.SavedTableViewModel
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.SavedTableViewId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@CustomerEndpoint
@RequestMapping("/api/v1/users/me/savedTableViews")
@RestController
class SavedTableViewsController(private val savedTableViewStore: SavedTableViewStore) {
  @ApiResponse200
  @Operation(summary = "Lists the current user's saved views for a table.")
  @GetMapping
  fun listSavedTableViews(
      @Parameter(
          description =
              "If present, list the views specific to this organization. If not present, list " +
                  "the user's global views."
      )
      @RequestParam
      organizationId: OrganizationId?,
      @Parameter(description = "Client-defined identifier of the table whose views to list.")
      @RequestParam
      tableName: String,
  ): ListSavedTableViewsResponsePayload {
    val models = savedTableViewStore.fetchByTableName(organizationId, tableName)

    return ListSavedTableViewsResponsePayload(models.map { SavedTableViewPayload(it) })
  }

  @ApiResponse200
  @ApiResponse404
  @Operation(summary = "Gets a saved table view of the current user's by id.")
  @GetMapping("/{id}")
  fun getSavedTableView(@PathVariable id: SavedTableViewId): GetSavedTableViewResponsePayload {
    val model = savedTableViewStore.fetchOneById(id)

    return GetSavedTableViewResponsePayload(SavedTableViewPayload(model))
  }

  @ApiResponse200
  @ApiResponse409("The current user already has a view with this name for this table.")
  @Operation(summary = "Creates a saved table view for the current user.")
  @PostMapping
  fun createSavedTableView(
      @RequestBody payload: CreateSavedTableViewRequestPayload
  ): CreateSavedTableViewResponsePayload {
    val id =
        savedTableViewStore.create(
            baseView = payload.baseView,
            name = payload.name,
            organizationId = payload.organizationId,
            settings = payload.settings,
            tableName = payload.tableName,
        )

    return CreateSavedTableViewResponsePayload(id)
  }
}

data class SavedTableViewPayload(
    @Schema(description = "Client-defined identifier of the view this view is based on, if any.")
    val baseView: String?,
    val id: SavedTableViewId,
    val name: String,
    @Schema(
        description =
            "If present, the view is specific to this organization. If not present, the view is " +
                "global to the user."
    )
    val organizationId: OrganizationId?,
    @Schema(description = "Client-defined view settings.") //
    val settings: ArbitraryJsonObject,
    val tableName: String,
) {
  constructor(
      model: SavedTableViewModel
  ) : this(
      baseView = model.baseView,
      id = model.id,
      name = model.name,
      organizationId = model.organizationId,
      settings = model.settings,
      tableName = model.tableName,
  )
}

data class CreateSavedTableViewRequestPayload(
    @Schema(
        description =
            "Client-defined identifier of the view this view is based on, if any. Can't be " +
                "changed after the view is created."
    )
    val baseView: String?,
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

data class GetSavedTableViewResponsePayload(val view: SavedTableViewPayload) :
    SuccessResponsePayload

data class ListSavedTableViewsResponsePayload(val views: List<SavedTableViewPayload>) :
    SuccessResponsePayload
