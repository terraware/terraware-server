package com.terraformation.backend.species.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.DuplicateNameException
import com.terraformation.backend.api.ResourceInUseException
import com.terraformation.backend.api.SeedBankAppEndpoint
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.GrowthForm
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.SpeciesId
import com.terraformation.backend.db.tables.pojos.SpeciesRow
import com.terraformation.backend.seedbank.api.ValuesController
import com.terraformation.backend.species.db.SpeciesStore
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import javax.ws.rs.NotFoundException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.DuplicateKeyException
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Species management endpoints.
 *
 * Implementation note: The names of the methods and payload classes are unusual here because there
 * are some existing species management endpoints in [ValuesController], and those endpoints are
 * already using the obvious names. The OpenAPI schema doesn't allow duplicate names, so if we
 * reused names such as `CreateSpeciesRequestPayload` here, the schema generator would pick one and
 * ignore the other. We can't change the names in [ValuesController] because the seed bank app uses
 * the existing names to look up the payload definitions.
 *
 * Once we've updated the seed bank client code to use these endpoints, we can get rid of the
 * equivalents in [ValuesController] and update the names here to conform to our usual naming
 * conventions.
 */
@SeedBankAppEndpoint
@RequestMapping("/api/v1/species")
@RestController
class SpeciesController(private val speciesStore: SpeciesStore) {
  @GetMapping
  @Operation(summary = "Lists all the species available in an organization.")
  fun listSpecies(
      @RequestParam("organizationId", required = true)
      @Schema(description = "Organization whose species should be listed.")
      organizationId: OrganizationId
  ): ListSpeciesResponsePayload {
    val elements = speciesStore.findAllSpecies(organizationId).map { SpeciesResponseElement(it) }
    return ListSpeciesResponsePayload(elements)
  }

  @ApiResponses(
      ApiResponse(responseCode = "200", description = "Species created."),
      ApiResponse(
          responseCode = "409", description = "A species with the requested name already exists."))
  @Operation(summary = "Creates a new species.")
  @PostMapping
  fun createSpecies(@RequestBody payload: SpeciesRequestPayload): CreateSpeciesResponsePayload {
    try {
      val speciesId = speciesStore.createSpecies(payload.toRow())
      return CreateSpeciesResponsePayload(speciesId)
    } catch (e: DuplicateKeyException) {
      throw DuplicateNameException("A species with that name already exists.")
    }
  }

  @ApiResponse(responseCode = "200", description = "Species retrieved.")
  @ApiResponse404
  @GetMapping("/{speciesId}")
  @Operation(summary = "Gets information about a single species.")
  fun getSpecies(
      @PathVariable speciesId: SpeciesId,
      @RequestParam("organizationId", required = true)
      @Schema(description = "Organization whose information about the species should be returned.")
      organizationId: OrganizationId,
  ): GetSpeciesResponsePayload {
    val speciesRow =
        speciesStore.fetchSpeciesById(speciesId)
            ?: throw NotFoundException("Species $speciesId not found.")

    val element = SpeciesResponseElement(speciesRow)
    return GetSpeciesResponsePayload(element)
  }

  @ApiResponse(
      responseCode = "200", description = "Species updated or merged with an existing species.")
  @ApiResponse404
  @Operation(summary = "Updates an existing species.")
  @PutMapping("/{speciesId}")
  fun updateSpecies(
      @PathVariable speciesId: SpeciesId,
      @RequestBody payload: SpeciesRequestPayload
  ): SimpleSuccessResponsePayload {
    speciesStore.updateSpecies(payload.toRow(speciesId))
    return SimpleSuccessResponsePayload()
  }

  @ApiResponses(
      ApiResponse(responseCode = "200", description = "Species deleted."),
      ApiResponse(
          responseCode = "409",
          description = "Cannot delete the species because it is currently in use."))
  @ApiResponse404
  @DeleteMapping("/{speciesId}")
  @Operation(
      summary = "Deletes an existing species.",
      description =
          "The species will no longer appear in the organization's list of species, but existing " +
              "data (plants, seeds, etc.) that refer to the species will still refer to it.")
  fun deleteSpecies(
      @PathVariable speciesId: SpeciesId,
      @RequestParam("organizationId", required = true)
      @Schema(description = "Organization from which the species should be deleted.")
      organizationId: OrganizationId,
  ): SimpleSuccessResponsePayload {
    try {
      speciesStore.deleteSpecies(speciesId)
      return SimpleSuccessResponsePayload()
    } catch (e: DataIntegrityViolationException) {
      throw ResourceInUseException("Species $speciesId is currently in use.")
    }
  }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class SpeciesResponseElement(
    val commonName: String?,
    val endangered: Boolean?,
    val familyName: String?,
    val growthForm: GrowthForm?,
    val id: SpeciesId,
    val rare: Boolean?,
    val scientificName: String,
) {
  constructor(
      row: SpeciesRow
  ) : this(
      commonName = row.commonName,
      endangered = row.endangered,
      familyName = row.familyName,
      growthForm = row.growthFormId,
      id = row.id!!,
      rare = row.rare,
      scientificName = row.scientificName!!,
  )
}

data class SpeciesRequestPayload(
    val commonName: String?,
    val endangered: Boolean?,
    val familyName: String?,
    val growthForm: GrowthForm?,
    val scientificName: String,
    @Schema(description = "Which organization's species list to update.")
    val organizationId: OrganizationId,
    val rare: Boolean?,
) {
  fun toRow(id: SpeciesId? = null) =
      SpeciesRow(
          commonName = commonName,
          endangered = endangered,
          familyName = familyName,
          growthFormId = growthForm,
          id = id,
          rare = rare,
          organizationId = organizationId,
          scientificName = scientificName,
      )
}

data class CreateSpeciesResponsePayload(val id: SpeciesId) : SuccessResponsePayload

data class GetSpeciesResponsePayload(val species: SpeciesResponseElement) : SuccessResponsePayload

data class ListSpeciesResponsePayload(val species: List<SpeciesResponseElement>) :
    SuccessResponsePayload
