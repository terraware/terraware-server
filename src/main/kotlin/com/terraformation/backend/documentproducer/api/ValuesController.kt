package com.terraformation.backend.documentproducer.api

import com.terraformation.backend.api.InternalEndpoint
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.StableId
import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.docprod.VariableId
import com.terraformation.backend.db.docprod.VariableType
import com.terraformation.backend.db.docprod.VariableValueId
import com.terraformation.backend.db.docprod.VariableWorkflowStatus
import com.terraformation.backend.documentproducer.VariableValueService
import com.terraformation.backend.documentproducer.db.VariableStore
import com.terraformation.backend.documentproducer.db.VariableValueStore
import com.terraformation.backend.documentproducer.db.VariableWorkflowStore
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.models.examples.Example
import java.time.LocalDate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@InternalEndpoint
@RequestMapping("/api/v1/document-producer")
@RestController
class ValuesController(
    private val variableStore: VariableStore,
    private val variableValueStore: VariableValueStore,
    private val variableValueService: VariableValueService,
    private val variableWorkflowStore: VariableWorkflowStore,
) {
  @GetMapping("/projects/{projectId}/values")
  @Operation(
      summary = "Get the values of the variables in a project.",
      description =
          "This may be used to fetch the full set of current values (the default behavior), the " +
              "values from a saved version (if maxValueId is specified), or to poll for recent " +
              "edits (if minValueId is specified).",
  )
  fun listProjectVariableValues(
      @PathVariable projectId: ProjectId,
      @Parameter(
          description =
              "If specified, only return values that belong to variables that are associated " +
                  "to the given ID"
      )
      @RequestParam
      deliverableId: DeliverableId? = null,
      @Parameter(
          description =
              "If specified, only return values with this ID or higher. Use this to poll for " +
                  "incremental updates to a document. Incremental results may include values of " +
                  "type 'Deleted' in cases where, e.g., elements have been removed from a list."
      )
      @RequestParam
      minValueId: VariableValueId? = null,
      @Parameter(
          description =
              "If specified, only return values with this ID or lower. Use this to retrieve " +
                  "saved document versions."
      )
      @RequestParam
      maxValueId: VariableValueId? = null,
      @Parameter(
          description =
              "If specified, return the value of the variable with this stable ID. May be " +
                  "specified more than once to return values for multiple variables. Ignored if " +
                  "variableId is specified."
      )
      @RequestParam
      stableId: List<StableId>? = null,
      @Parameter(
          description =
              "If specified, return the value of this variable. May be specified more than once " +
                  "to return values for multiple variables."
      )
      @RequestParam
      variableId: List<VariableId>? = null,
  ): ListVariableValuesResponsePayload {
    val currentMax = variableValueStore.fetchMaxValueId(projectId) ?: VariableValueId(0)
    val nextValueId = VariableValueId(currentMax.value + 1)

    val variableIds = variableId ?: stableId?.mapNotNull { variableStore.fetchByStableId(it)?.id }

    // If the client didn't explicitly tell us otherwise, only return values whose IDs are less
    // than the nextValueId we'll be returning, in case new values are inserted by another user at
    // the same time this endpoint is executing.
    val effectiveMax = maxValueId ?: currentMax
    val valuesByVariableId =
        variableValueStore
            .listValues(
                projectId = projectId,
                deliverableId = deliverableId,
                minValueId = minValueId,
                maxValueId = effectiveMax,
                variableIds = variableIds?.ifEmpty { null },
            )
            .groupBy { it.variableId to it.rowValueId }

    val workflowDetailsByVariableId = variableWorkflowStore.fetchCurrentForProject(projectId)

    val valuePayloads =
        valuesByVariableId.values.map { values ->
          val firstValue = values.first()
          val variableId = firstValue.variableId
          val workflowDetails = workflowDetailsByVariableId[variableId]
          val status =
              workflowDetails?.status
                  ?: when {
                    firstValue.rowValueId != null -> null
                    firstValue.type == VariableType.Section -> VariableWorkflowStatus.Incomplete
                    else -> VariableWorkflowStatus.NotSubmitted
                  }

          ExistingVariableValuesPayload(
              variableId,
              firstValue.rowValueId,
              workflowDetails?.feedback,
              workflowDetails?.internalComment,
              status,
              values.map { ExistingValuePayload.of(it) },
          )
        }

    val valuelessWorkflowDetailsPayloads =
        workflowDetailsByVariableId.entries
            .filterNot { (it.key to null) in valuesByVariableId }
            .filter { variableIds?.let { variableIds -> it.key in variableIds } ?: true }
            .map { (_, workflowDetails) ->
              ExistingVariableValuesPayload(
                  workflowDetails.variableId,
                  null,
                  workflowDetails.feedback,
                  workflowDetails.internalComment,
                  workflowDetails.status,
                  emptyList(),
              )
            }

    return ListVariableValuesResponsePayload(
        nextValueId,
        valuePayloads + valuelessWorkflowDetailsPayloads,
    )
  }

  @Operation(
      summary = "Update the values of the variables in a project.",
      description =
          "Make a list of changes to a project's variable values. The changes are applied in " +
              "order and are treated as an atomic unit. That is, the changes will either all " +
              "succeed or all fail; there won't be a case where some of the changes are applied " +
              "and some aren't. See the payload descriptions for more details about the " +
              "operations you can perform on values.",
  )
  @PostMapping("/projects/{projectId}/values")
  fun updateProjectVariableValues(
      @PathVariable projectId: ProjectId,
      @RequestBody payload: UpdateVariableValuesRequestPayload,
  ): SimpleSuccessResponsePayload {
    val existingValueIds = payload.operations.mapNotNull { it.getExistingValueId() }
    val existingBases = variableValueStore.fetchBaseProperties(existingValueIds)
    val operations =
        payload.operations.map { operationPayload ->
          val base = operationPayload.getExistingValueId()?.let { existingBases[it] }
          operationPayload.toOperationModel(projectId, base)
        }

    variableValueService.updateValues(operations, payload.updateStatuses ?: true)

    return SimpleSuccessResponsePayload()
  }
}

data class ListVariableValuesResponsePayload(
    @Schema(
        description =
            "The next unused value ID. You can pass this back to the endpoint as the minValueId " +
                "parameter to poll for newly-updated values."
    )
    val nextValueId: VariableValueId,
    @ArraySchema(
        arraySchema =
            Schema(
                description =
                    "Variable values organized by variable ID and table row. If you are getting " +
                        "incremental values (that is, you passed minValueId to the endpoint) " +
                        "this list may include values of type \"Deleted\" to indicate that " +
                        "existing values were deleted and not replaced with new values."
            )
    )
    val values: List<ExistingVariableValuesPayload>,
) : SuccessResponsePayload

data class UpdateVariableValuesRequestPayload(
    @ArraySchema(
        arraySchema =
            Schema(
                description =
                    "List of operations to perform on the document's values. The operations are " +
                        "applied in order, and atomically: if any of them fail, none of them " +
                        "will be applied."
            )
    )
    val operations: List<ValueOperationPayload>,
    @Schema(
        description =
            "Whether to update variable statuses. Defaults to true. Accelerator admins can " +
                "bypass the status updates by setting the flag to false."
    )
    val updateStatuses: Boolean? = true,
) {
  companion object {
    /** Examples are added to the OpenAPI schema programmatically in OpenApiConfig. */
    val examples: Map<String, Example> =
        mapOf(
            "Append to Table" to
                Example()
                    .description(
                        "Append a new row to a table and include values for its columns. In this " +
                            "example, the table is variable ID 171 and it has two columns, a " +
                            "non-list text variable (172) and a date list (173). We will add two " +
                            "values to the date list."
                    )
                    .value(
                        UpdateVariableValuesRequestPayload(
                            listOf(
                                AppendValueOperationPayload(
                                    variableId = VariableId(171),
                                    value = NewTableValuePayload(null),
                                ),
                                AppendValueOperationPayload(
                                    variableId = VariableId(172),
                                    value =
                                        NewTextValuePayload(
                                            "Citation for text value",
                                            "Value of first column",
                                        ),
                                ),
                                AppendValueOperationPayload(
                                    variableId = VariableId(173),
                                    value = NewDateValuePayload(null, LocalDate.of(2021, 7, 3)),
                                ),
                                AppendValueOperationPayload(
                                    variableId = VariableId(173),
                                    value = NewDateValuePayload(null, LocalDate.of(2023, 4, 19)),
                                ),
                            )
                        )
                    ),
        )
  }
}
