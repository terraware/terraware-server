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
import com.terraformation.backend.db.ScientificNameExistsException
import com.terraformation.backend.db.default_schema.ConservationCategory
import com.terraformation.backend.db.default_schema.EcosystemType
import com.terraformation.backend.db.default_schema.GrowthForm
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.PlantMaterialSourcingMethod
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.SeedStorageBehavior
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.SpeciesNativity
import com.terraformation.backend.db.default_schema.SpeciesProblemField
import com.terraformation.backend.db.default_schema.SpeciesProblemId
import com.terraformation.backend.db.default_schema.SpeciesProblemType
import com.terraformation.backend.db.default_schema.SuccessionalGroup
import com.terraformation.backend.db.default_schema.WoodDensityLevel
import com.terraformation.backend.db.default_schema.tables.pojos.SpeciesProblemsRow
import com.terraformation.backend.species.SpeciesService
import com.terraformation.backend.species.db.ProjectSpeciesStore
import com.terraformation.backend.species.db.SpeciesStore
import com.terraformation.backend.species.model.ExistingSpeciesModel
import com.terraformation.backend.species.model.ExistingSpeciesProjectModel
import com.terraformation.backend.species.model.NewSpeciesModel
import com.terraformation.backend.species.model.ProjectSpeciesOverride
import io.swagger.v3.oas.annotations.ExternalDocumentation
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import java.math.BigDecimal
import java.time.Instant
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

@SeedBankAppEndpoint
@RequestMapping("/api/v1/species")
@RestController
class SpeciesController(
    private val projectSpeciesStore: ProjectSpeciesStore,
    private val speciesService: SpeciesService,
    private val speciesStore: SpeciesStore,
) {
  @GetMapping
  @Operation(summary = "Lists all the species available in an organization.")
  fun listSpecies(
      @RequestParam
      @Schema(description = "Organization whose species should be listed.")
      organizationId: OrganizationId,
      @RequestParam
      @Schema(
          description =
              "Only list species that are currently used in the organization's inventory, accessions or planting sites."
      )
      inUse: Boolean?,
  ): ListSpeciesResponsePayload {
    val problems = speciesStore.findAllProblems(organizationId)
    val elements =
        speciesStore.findAllSpecies(organizationId, inUse ?: false).map {
          SpeciesResponseElement(it, problems[it.id])
        }
    return ListSpeciesResponsePayload(elements)
  }

  @ApiResponses(
      ApiResponse(responseCode = "200", description = "Species created."),
      ApiResponse(
          responseCode = "409",
          description = "A species with the requested name already exists.",
      ),
  )
  @Operation(summary = "Creates a new species.")
  @PostMapping
  fun createSpecies(@RequestBody payload: SpeciesRequestPayload): CreateSpeciesResponsePayload {
    try {
      val speciesId = speciesService.createSpecies(payload.toNew())
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
      responseCode = "200",
      description = "Species updated or merged with an existing species.",
  )
  @ApiResponse404
  @ApiResponse409("A species with the requested name already exists.")
  @Operation(summary = "Updates an existing species.")
  @PutMapping("/{speciesId}")
  fun updateSpecies(
      @PathVariable speciesId: SpeciesId,
      @RequestBody payload: SpeciesRequestPayload,
  ): SimpleSuccessResponsePayload {
    try {
      speciesService.updateSpecies(payload.toExisting(speciesId))
      return SimpleSuccessResponsePayload()
    } catch (e: ScientificNameExistsException) {
      throw DuplicateNameException(e.message)
    }
  }

  @ApiResponse404
  @ApiResponse409("Cannot delete the species because it is currently in use.")
  @ApiResponseSimpleSuccess("Species deleted.")
  @DeleteMapping("/{speciesId}")
  @Operation(
      summary = "Deletes an existing species.",
      description =
          "The species will no longer appear in the organization's list of species, but existing " +
              "data (plants, seeds, etc.) that refer to the species will still refer to it.",
  )
  fun deleteSpecies(
      @PathVariable speciesId: SpeciesId,
  ): SimpleSuccessResponsePayload {
    try {
      speciesService.deleteSpecies(speciesId)
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
      @PathVariable problemId: SpeciesProblemId
  ): GetSpeciesProblemResponsePayload {
    val problem = speciesStore.fetchProblemById(problemId)
    return GetSpeciesProblemResponsePayload(SpeciesProblemElement(problem))
  }

  @ApiResponse(
      responseCode = "200",
      description = "Suggestion applied. Response contains the updated species information.",
  )
  @ApiResponse404
  @ApiResponse409("There is no suggested change for this problem.")
  @Operation(
      summary = "Applies suggested changes to fix a problem with a species.",
      description = "Only valid for problems that include suggested changes.",
  )
  @PostMapping("/problems/{problemId}")
  fun acceptProblemSuggestion(
      @PathVariable problemId: SpeciesProblemId
  ): GetSpeciesResponsePayload {
    val updatedRow = speciesService.acceptProblemSuggestion(problemId)
    val remainingProblems = speciesStore.fetchProblemsBySpeciesId(updatedRow.id)
    return GetSpeciesResponsePayload(SpeciesResponseElement(updatedRow, remainingProblems))
  }

  @ApiResponseSimpleSuccess
  @ApiResponse404
  @DeleteMapping("/problems/{problemId}")
  @Operation(
      summary =
          "Deletes information about a problem with a species without applying any suggested " +
              "changes."
  )
  fun deleteProblem(@PathVariable problemId: SpeciesProblemId): SimpleSuccessResponsePayload {
    speciesStore.deleteProblem(problemId)
    return SimpleSuccessResponsePayload()
  }

  @ApiResponseSimpleSuccess
  @ApiResponse404
  @ApiResponse409("A species and project are in different organizations.")
  @Operation(summary = "Associates species with projects.")
  @PostMapping("/projects/assign")
  fun assignSpeciesToProjects(
      @RequestBody payload: AssignSpeciesToProjectsPayload
  ): SimpleSuccessResponsePayload {
    projectSpeciesStore.assignProjects(payload.toMap())
    return SimpleSuccessResponsePayload()
  }

  @PostMapping("/projects/override")
  @Operation(summary = "Overrides calculated per-project species data.")
  fun overrideProjectSpeciesData(
      @RequestBody payload: OverrideSpeciesProjectDataRequestPayload
  ): SimpleSuccessResponsePayload {
    projectSpeciesStore.overridePerProjectData(payload.overrides.map { it.toModel() })
    return SimpleSuccessResponsePayload()
  }

  @ApiResponseSimpleSuccess
  @ApiResponse404
  @ApiResponse409("A species and project are in different organizations.")
  @Operation(summary = "Removes species from projects.")
  @PostMapping("/projects/unassign")
  fun unassignSpeciesFromProjects(
      @RequestBody payload: AssignSpeciesToProjectsPayload
  ): SimpleSuccessResponsePayload {
    projectSpeciesStore.removeProjects(payload.toMap())
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
                "system is unable to calculate a corrected value."
    )
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
data class SpeciesProjectElement(
    val calculatedNativity: SpeciesNativity?,
    val calculatedNativitySource: SpeciesDataSourcePayload?,
    val overriddenJustification: String?,
    val overriddenNativity: SpeciesNativity?,
    val projectId: ProjectId?,
) {
  constructor(
      model: ExistingSpeciesProjectModel
  ) : this(
      calculatedNativity = model.calculatedNativity,
      calculatedNativitySource = model.calculatedNativitySource?.toPayload(),
      overriddenJustification = model.overriddenJustification,
      overriddenNativity = model.overriddenNativity,
      projectId = model.projectId,
  )
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class SpeciesResponseElement(
    val averageWoodDensity: BigDecimal?,
    val commonName: String?,
    val commonNameSource: SpeciesDataSourcePayload?,
    @Schema(
        description = "IUCN Red List conservation category code.",
        externalDocs =
            ExternalDocumentation(url = "https://en.wikipedia.org/wiki/IUCN_Red_List#Categories"),
    )
    val conservationCategory: ConservationCategory?,
    val createdTime: Instant,
    val dbhSource: String?,
    val dbhValue: BigDecimal?,
    val ecologicalRoleKnown: String?,
    val ecosystemTypes: Set<EcosystemType>?,
    val familyName: String?,
    val familyNameSource: SpeciesDataSourcePayload?,
    val growthForms: Set<GrowthForm>?,
    val heightAtMaturitySource: String?,
    val heightAtMaturityValue: BigDecimal?,
    val id: SpeciesId,
    val localUsesKnown: String?,
    val modifiedTime: Instant,
    val nativeEcosystem: String?,
    val plantMaterialSourcingMethods: Set<PlantMaterialSourcingMethod>?,
    val problems: List<SpeciesProblemElement>?,
    val projects: List<SpeciesProjectElement>,
    val otherFacts: String?,
    val rare: Boolean?,
    val scientificName: String,
    val seedStorageBehavior: SeedStorageBehavior?,
    val successionalGroups: Set<SuccessionalGroup>?,
    val woodDensityLevel: WoodDensityLevel?,
) {
  constructor(
      model: ExistingSpeciesModel,
      problems: List<SpeciesProblemsRow>?,
  ) : this(
      averageWoodDensity = model.averageWoodDensity,
      commonName = model.commonName,
      commonNameSource = model.commonNameSource?.let { it.toPayload() },
      conservationCategory = model.conservationCategory,
      createdTime = model.createdTime,
      dbhSource = model.dbhSource,
      dbhValue = model.dbhValue,
      ecologicalRoleKnown = model.ecologicalRoleKnown,
      ecosystemTypes = model.ecosystemTypes.ifEmpty { null },
      familyName = model.familyName,
      familyNameSource = model.familyNameSource?.let { it.toPayload() },
      growthForms = model.growthForms,
      heightAtMaturitySource = model.heightAtMaturitySource,
      heightAtMaturityValue = model.heightAtMaturityValue,
      id = model.id,
      localUsesKnown = model.localUsesKnown,
      modifiedTime = model.modifiedTime,
      nativeEcosystem = model.nativeEcosystem,
      plantMaterialSourcingMethods = model.plantMaterialSourcingMethods,
      problems = problems?.map { SpeciesProblemElement(it) }?.ifEmpty { null },
      projects = model.projects.map { SpeciesProjectElement(it) },
      otherFacts = model.otherFacts,
      rare = model.rare,
      scientificName = model.scientificName,
      seedStorageBehavior = model.seedStorageBehavior,
      successionalGroups = model.successionalGroups,
      woodDensityLevel = model.woodDensityLevel,
  )
}

data class SpeciesRequestPayload(
    val averageWoodDensity: BigDecimal?,
    val commonName: String?,
    @Schema(
        description = "IUCN Red List conservation category code.",
        externalDocs =
            ExternalDocumentation(url = "https://en.wikipedia.org/wiki/IUCN_Red_List#Categories"),
    )
    val conservationCategory: ConservationCategory?,
    val dbhSource: String?,
    val dbhValue: BigDecimal?,
    val ecologicalRoleKnown: String?,
    val ecosystemTypes: Set<EcosystemType>?,
    val familyName: String?,
    val growthForms: Set<GrowthForm>?,
    val heightAtMaturitySource: String?,
    val heightAtMaturityValue: BigDecimal?,
    val localUsesKnown: String?,
    val nativeEcosystem: String?,
    @Schema(description = "Which organization's species list to update.")
    val organizationId: OrganizationId,
    val otherFacts: String?,
    val plantMaterialSourcingMethods: Set<PlantMaterialSourcingMethod>?,
    val rare: Boolean?,
    val scientificName: String,
    val seedStorageBehavior: SeedStorageBehavior?,
    val successionalGroups: Set<SuccessionalGroup>?,
    val woodDensityLevel: WoodDensityLevel?,
) {
  fun toExisting(id: SpeciesId) =
      ExistingSpeciesModel(
          averageWoodDensity = averageWoodDensity,
          commonName = commonName,
          conservationCategory = conservationCategory,
          createdTime = Instant.EPOCH, // Dummy value; ignored on update
          dbhSource = dbhSource,
          dbhValue = dbhValue,
          ecologicalRoleKnown = ecologicalRoleKnown,
          ecosystemTypes = ecosystemTypes ?: emptySet(),
          familyName = familyName,
          growthForms = growthForms ?: emptySet(),
          heightAtMaturitySource = heightAtMaturitySource,
          heightAtMaturityValue = heightAtMaturityValue,
          id = id,
          initialScientificName = scientificName,
          localUsesKnown = localUsesKnown,
          modifiedTime = Instant.EPOCH, // Dummy value; ignored on update
          nativeEcosystem = nativeEcosystem,
          rare = rare,
          organizationId = organizationId,
          otherFacts = otherFacts,
          plantMaterialSourcingMethods = plantMaterialSourcingMethods ?: emptySet(),
          scientificName = scientificName,
          seedStorageBehavior = seedStorageBehavior,
          successionalGroups = successionalGroups ?: emptySet(),
          woodDensityLevel = woodDensityLevel,
      )

  fun toNew() =
      NewSpeciesModel(
          averageWoodDensity = averageWoodDensity,
          commonName = commonName,
          conservationCategory = conservationCategory,
          dbhSource = dbhSource,
          dbhValue = dbhValue,
          ecologicalRoleKnown = ecologicalRoleKnown,
          ecosystemTypes = ecosystemTypes ?: emptySet(),
          familyName = familyName,
          growthForms = growthForms ?: emptySet(),
          heightAtMaturitySource = heightAtMaturitySource,
          heightAtMaturityValue = heightAtMaturityValue,
          localUsesKnown = localUsesKnown,
          nativeEcosystem = nativeEcosystem,
          rare = rare,
          organizationId = organizationId,
          otherFacts = otherFacts,
          plantMaterialSourcingMethods = plantMaterialSourcingMethods ?: emptySet(),
          scientificName = scientificName,
          seedStorageBehavior = seedStorageBehavior,
          successionalGroups = successionalGroups ?: emptySet(),
          woodDensityLevel = woodDensityLevel,
      )
}

data class SpeciesProjectsPayload(
    val speciesId: SpeciesId,
    val projectIds: Set<ProjectId>,
)

data class OverrideSpeciesProjectDataElement(
    val overriddenJustification: String,
    val overriddenNativity: SpeciesNativity,
    val projectId: ProjectId?,
    val speciesId: SpeciesId,
) {
  fun toModel() =
      ProjectSpeciesOverride(
          overriddenJustification = overriddenJustification,
          overriddenNativity = overriddenNativity,
          projectId = projectId,
          speciesId = speciesId,
      )
}

data class AssignSpeciesToProjectsPayload(
    @Schema(description = "The species to assign, each with the projects to associate it with.")
    val species: List<SpeciesProjectsPayload>,
) {
  fun toMap(): Map<SpeciesId, Set<ProjectId>> =
      species
          .groupingBy { it.speciesId }
          .aggregate { _, accumulator, element, _ ->
            accumulator?.let { it + element.projectIds } ?: element.projectIds
          }
}

data class OverrideSpeciesProjectDataRequestPayload(
    val overrides: List<OverrideSpeciesProjectDataElement>,
)

data class CreateSpeciesResponsePayload(val id: SpeciesId) : SuccessResponsePayload

data class GetSpeciesResponsePayload(val species: SpeciesResponseElement) : SuccessResponsePayload

data class ListSpeciesResponsePayload(val species: List<SpeciesResponseElement>) :
    SuccessResponsePayload

data class GetSpeciesProblemResponsePayload(val problem: SpeciesProblemElement) :
    SuccessResponsePayload
