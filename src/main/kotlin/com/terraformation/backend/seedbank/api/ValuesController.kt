package com.terraformation.backend.seedbank.api

import com.terraformation.backend.api.SeedBankAppEndpoint
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.search.SearchFieldPrefix
import com.terraformation.backend.search.SearchService
import com.terraformation.backend.search.api.HasSearchNode
import com.terraformation.backend.search.api.SearchNodePayload
import com.terraformation.backend.search.table.SearchTables
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import javax.ws.rs.BadRequestException
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RequestMapping("/api/v1/seedbank/values")
@RestController
@SeedBankAppEndpoint
class ValuesController(
    tables: SearchTables,
    private val searchService: SearchService,
) {
  private val accessionsTable = tables.accessions
  private val rootPrefix = SearchFieldPrefix(root = accessionsTable)

  @Operation(
      summary =
          "List the values of a set of search fields for a set of accessions matching certain " +
              "filter criteria.")
  @PostMapping
  fun listFieldValues(
      @RequestBody payload: ListFieldValuesRequestPayload
  ): ListFieldValuesResponsePayload {
    val limit = 20

    val fields = payload.fields.associateWith { rootPrefix.resolve(it) }
    if (fields.values.any { it.isNested }) {
      throw BadRequestException("Cannot list values of nested fields.")
    }

    val values =
        fields.mapValues { (_, searchField) ->
          val values =
              searchService.fetchValues(
                  rootPrefix, searchField, payload.toSearchNode(rootPrefix), limit)
          val partial = values.size > limit
          FieldValuesPayload(values.take(limit), partial)
        }

    return ListFieldValuesResponsePayload(values)
  }

  @Operation(summary = "List the possible values of a set of search fields.")
  @PostMapping("/all")
  fun listAllFieldValues(
      @RequestBody payload: ListAllFieldValuesRequestPayload
  ): ListAllFieldValuesResponsePayload {
    val limit = 100
    val values =
        payload.fields.associateWith { fieldName ->
          val searchField = rootPrefix.resolve(fieldName)
          val values = searchService.fetchAllValues(searchField, payload.organizationId, limit)

          val partial = values.size > limit
          AllFieldValuesPayload(values.take(limit), partial)
        }

    return ListAllFieldValuesResponsePayload(values)
  }
}

data class FieldValuesPayload(
    @ArraySchema(
        schema = Schema(nullable = true),
        arraySchema =
            Schema(
                description =
                    "List of values in the matching accessions. If there are accessions where " +
                        "the field has no value, this list will contain null (an actual null " +
                        "value, not the string \"null\")."))
    val values: List<String?>,
    @Schema(
        description =
            "If true, the list of values is too long to return in its entirety and \"values\" is " +
                "a partial list.")
    val partial: Boolean
)

data class ListFieldValuesRequestPayload(
    val facilityId: FacilityId?,
    val fields: List<String>,
    val organizationId: OrganizationId?,
    override val search: SearchNodePayload?,
) : HasSearchNode

data class ListFieldValuesResponsePayload(val results: Map<String, FieldValuesPayload>) :
    SuccessResponsePayload

data class AllFieldValuesPayload(
    @ArraySchema(
        arraySchema =
            Schema(
                description =
                    "All the values this field could possibly have, whether or not any " +
                        "accessions have them. For fields that allow the user to enter arbitrary " +
                        "values, this is equivalent to querying the list of values without any " +
                        "filter criteria, that is, it's a list of all the user-entered values."))
    val values: List<String?>,
    @Schema(
        description =
            "If true, the list of values is too long to return in its entirety and \"values\" is " +
                "a partial list.")
    val partial: Boolean
)

data class ListAllFieldValuesRequestPayload(
    val fields: List<String>,
    val organizationId: OrganizationId,
)

data class ListAllFieldValuesResponsePayload(val results: Map<String, AllFieldValuesPayload>) :
    SuccessResponsePayload
