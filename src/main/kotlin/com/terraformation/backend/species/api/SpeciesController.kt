package com.terraformation.backend.species.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.ApiResponse409
import com.terraformation.backend.api.ApiResponseSimpleSuccess
import com.terraformation.backend.api.DuplicateNameException
import com.terraformation.backend.api.ResourceInUseException
import com.terraformation.backend.api.SeedBankAppEndpoint
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.default_schema.ConservationCategory
import com.terraformation.backend.db.default_schema.EcosystemType
import com.terraformation.backend.db.default_schema.GrowthForm
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.SeedStorageBehavior
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.SpeciesProblemField
import com.terraformation.backend.db.default_schema.SpeciesProblemId
import com.terraformation.backend.db.default_schema.SpeciesProblemType
import com.terraformation.backend.db.default_schema.tables.pojos.SpeciesProblemsRow
import com.terraformation.backend.seedbank.api.ValuesController
import com.terraformation.backend.species.SpeciesService
import com.terraformation.backend.species.db.SpeciesStore
import com.terraformation.backend.species.model.ExistingSpeciesModel
import com.terraformation.backend.species.model.SpeciesModel
import io.swagger.v3.oas.annotations.ExternalDocumentation
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
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
class SpeciesController(
    private val speciesService: SpeciesService,
    private val speciesStore: SpeciesStore,
) {
  @GetMapping
  @Operation(summary = "Lists all the species available in an organization.")
  fun listSpecies(
      @RequestParam
      @Schema(description = "Organization whose species should be listed.")
      organizationId: OrganizationId
  ): ListSpeciesResponsePayload {
    val problems = speciesStore.findAllProblems(organizationId)
    val elements =
        speciesStore.findAllSpecies(organizationId).map {
          SpeciesResponseElement(it, problems[it.id])
        }
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
      val speciesId = speciesService.createSpecies(payload.toModel(null))
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
      @RequestParam
      @Schema(description = "Organization whose information about the species should be returned.")
      organizationId: OrganizationId,
  ): GetSpeciesResponsePayload {
    val speciesRow = speciesStore.fetchSpeciesById(speciesId)
    val problems = speciesStore.fetchProblemsBySpeciesId(speciesId)

    val element = SpeciesResponseElement(speciesRow, problems)
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
    speciesService.updateSpecies(payload.toModel(speciesId))
    return SimpleSuccessResponsePayload()
  }

  @ApiResponse404
  @ApiResponse409("Cannot delete the species because it is currently in use.")
  @ApiResponseSimpleSuccess("Species deleted.")
  @DeleteMapping("/{speciesId}")
  @Operation(
      summary = "Deletes an existing species.",
      description =
          "The species will no longer appear in the organization's list of species, but existing " +
              "data (plants, seeds, etc.) that refer to the species will still refer to it.")
  fun deleteSpecies(
      @PathVariable speciesId: SpeciesId,
      @RequestParam
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

  @ApiResponse(responseCode = "200", description = "Problem retrieved.")
  @ApiResponse404
  @GetMapping("/problems/{problemId}")
  @Operation(description = "Returns details about a problem with a species.")
  fun getSpeciesProblem(
      @PathVariable("problemId") problemId: SpeciesProblemId
  ): GetSpeciesProblemResponsePayload {
    val problem = speciesStore.fetchProblemById(problemId)
    return GetSpeciesProblemResponsePayload(SpeciesProblemElement(problem))
  }

  @ApiResponse(
      responseCode = "200",
      description = "Suggestion applied. Response contains the updated species information.")
  @ApiResponse404
  @ApiResponse409("There is no suggested change for this problem.")
  @Operation(
      summary = "Applies suggested changes to fix a problem with a species.",
      description = "Only valid for problems that include suggested changes.")
  @PostMapping("/problems/{problemId}")
  fun acceptProblemSuggestion(
      @PathVariable("problemId") problemId: SpeciesProblemId
  ): GetSpeciesResponsePayload {
    val updatedRow = speciesStore.acceptProblemSuggestion(problemId)
    val remainingProblems = speciesStore.fetchProblemsBySpeciesId(updatedRow.id)
    return GetSpeciesResponsePayload(SpeciesResponseElement(updatedRow, remainingProblems))
  }

  @ApiResponseSimpleSuccess
  @ApiResponse404
  @DeleteMapping("/problems/{problemId}")
  @Operation(
      summary =
          "Deletes information about a problem with a species without applying any suggested " +
              "changes.")
  fun deleteProblem(
      @PathVariable("problemId") problemId: SpeciesProblemId
  ): SimpleSuccessResponsePayload {
    speciesStore.deleteProblem(problemId)
    return SimpleSuccessResponsePayload()
  }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class SpeciesProblemElement(
    val id: SpeciesProblemId,
    val field: SpeciesProblemField,
    val type: SpeciesProblemType,
    @Schema(
        description =
            "Value for the field in question that would correct the problem. Absent if the " +
                "system is unable to calculate a corrected value.")
    val suggestedValue: String?,
) {
  constructor(
      row: SpeciesProblemsRow
  ) : this(
      field = row.fieldId!!,
      id = row.id!!,
      suggestedValue = row.suggestedValue,
      type = row.typeId!!,
  )
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class SpeciesResponseElement(
    val ecosystemTypes: Set<EcosystemType>?,
    val commonName: String?,
    @Schema(
        description = "IUCN Red List conservation category code.",
        externalDocs =
            ExternalDocumentation(url = "https://en.wikipedia.org/wiki/IUCN_Red_List#Categories"))
    val conservationCategory: ConservationCategory?,
    val familyName: String?,
    val growthForm: GrowthForm?,
    val id: SpeciesId,
    val problems: List<SpeciesProblemElement>?,
    val rare: Boolean?,
    val scientificName: String,
    val seedStorageBehavior: SeedStorageBehavior?,
) {
  constructor(
      model: ExistingSpeciesModel,
      problems: List<SpeciesProblemsRow>?,
  ) : this(
      ecosystemTypes = model.ecosystemTypes.ifEmpty { null },
      commonName = model.commonName,
      conservationCategory = model.conservationCategory,
      familyName = model.familyName,
      growthForm = model.growthForm,
      id = model.id,
      problems = problems?.map { SpeciesProblemElement(it) }?.ifEmpty { null },
      rare = model.rare,
      scientificName = model.scientificName,
      seedStorageBehavior = model.seedStorageBehavior,
  )
}

data class SpeciesRequestPayload(
    val ecosystemTypes: Set<EcosystemType>?,
    val commonName: String?,
    @Schema(
        description = "IUCN Red List conservation category code.",
        externalDocs =
            ExternalDocumentation(url = "https://en.wikipedia.org/wiki/IUCN_Red_List#Categories"))
    val conservationCategory: ConservationCategory?,
    val familyName: String?,
    val growthForm: GrowthForm?,
    @Schema(description = "Which organization's species list to update.")
    val organizationId: OrganizationId,
    val rare: Boolean?,
    val scientificName: String,
    val seedStorageBehavior: SeedStorageBehavior?,
) {
  fun <T : SpeciesId?> toModel(id: T) =
      SpeciesModel(
          commonName = commonName,
          conservationCategory = conservationCategory,
          ecosystemTypes = ecosystemTypes ?: emptySet(),
          familyName = familyName,
          growthForm = growthForm,
          id = id,
          initialScientificName = scientificName,
          rare = rare,
          organizationId = organizationId,
          scientificName = scientificName,
          seedStorageBehavior = seedStorageBehavior,
      )
}

data class CreateSpeciesResponsePayload(val id: SpeciesId) : SuccessResponsePayload

data class GetSpeciesResponsePayload(val species: SpeciesResponseElement) : SuccessResponsePayload

data class ListSpeciesResponsePayload(val species: List<SpeciesResponseElement>) :
    SuccessResponsePayload

data class GetSpeciesProblemResponsePayload(val problem: SpeciesProblemElement) :
    SuccessResponsePayload
