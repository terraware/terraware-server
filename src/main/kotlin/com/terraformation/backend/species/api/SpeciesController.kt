package com.terraformation.backend.species.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.DuplicateNameException
import com.terraformation.backend.api.GISAppEndpoint
import com.terraformation.backend.api.NotFoundException
import com.terraformation.backend.api.ResourceInUseException
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.FamilyId
import com.terraformation.backend.db.PlantForm
import com.terraformation.backend.db.RareType
import com.terraformation.backend.db.SpeciesId
import com.terraformation.backend.db.SpeciesNameId
import com.terraformation.backend.db.SpeciesNotFoundException
import com.terraformation.backend.db.tables.daos.SpeciesDao
import com.terraformation.backend.db.tables.daos.SpeciesNamesDao
import com.terraformation.backend.db.tables.pojos.SpeciesNamesRow
import com.terraformation.backend.db.tables.pojos.SpeciesRow
import com.terraformation.backend.seedbank.api.ValuesController
import com.terraformation.backend.species.db.SpeciesStore
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
import org.springframework.web.bind.annotation.RestController

/**
 * Species and family management endpoints.
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
@GISAppEndpoint
@RequestMapping("/api/v1/species")
@RestController
class SpeciesController(
    private val speciesDao: SpeciesDao,
    private val speciesNamesDao: SpeciesNamesDao,
    private val speciesStore: SpeciesStore,
) {
  @GetMapping
  @Operation(summary = "Lists all known species.")
  fun speciesList(): SpeciesListResponsePayload {
    val species = speciesDao.findAll()
    return SpeciesListResponsePayload(species.map { SpeciesResponseElement(it) })
  }

  @ApiResponses(
      ApiResponse(responseCode = "200", description = "Species created."),
      ApiResponse(
          responseCode = "409", description = "A species with the requested name already exists."))
  @Operation(summary = "Creates a new species.")
  @PostMapping
  fun speciesCreate(@RequestBody payload: SpeciesRequestPayload): SpeciesCreateResponsePayload {
    try {
      val speciesId = speciesStore.createSpecies(payload.toRow())
      return SpeciesCreateResponsePayload(speciesId)
    } catch (e: DuplicateKeyException) {
      throw DuplicateNameException("A species with that name already exists.")
    }
  }

  @ApiResponse(responseCode = "200", description = "Species retrieved.")
  @ApiResponse404
  @GetMapping("/{speciesId}")
  @Operation(summary = "Gets information about a single species.")
  fun speciesRead(@PathVariable speciesId: Long): SpeciesGetResponsePayload {
    val row =
        speciesDao.fetchOneById(SpeciesId(speciesId))
            ?: throw NotFoundException("Species $speciesId not found.")
    return SpeciesGetResponsePayload(SpeciesResponseElement(row))
  }

  @ApiResponse(
      responseCode = "200", description = "Species updated or merged with an existing species.")
  @ApiResponse404
  @Operation(summary = "Updates an existing species.")
  @PutMapping("/{speciesId}")
  fun speciesUpdate(
      @PathVariable speciesId: Long,
      @RequestBody payload: SpeciesRequestPayload
  ): SimpleSuccessResponsePayload {
    speciesStore.updateSpecies(payload.toRow(SpeciesId(speciesId)))
    return SimpleSuccessResponsePayload()
  }

  @ApiResponses(
      ApiResponse(responseCode = "200", description = "Species deleted."),
      ApiResponse(
          responseCode = "409",
          description = "Cannot delete the species because it is currently in use."))
  @ApiResponse404
  @DeleteMapping("/{speciesId}")
  @Operation(summary = "Delete an existing species.")
  fun speciesDelete(@PathVariable speciesId: Long): SimpleSuccessResponsePayload {
    try {
      speciesStore.deleteSpecies(SpeciesId(speciesId))
      return SimpleSuccessResponsePayload()
    } catch (e: DataIntegrityViolationException) {
      throw ResourceInUseException("Species $speciesId is currently in use.")
    }
  }

  @GetMapping("/names")
  @Operation(summary = "Lists all species names.")
  fun speciesNamesListAll(): SpeciesNamesListResponsePayload {
    val names = speciesNamesDao.findAll()
    return SpeciesNamesListResponsePayload(names.map { SpeciesNamesResponseElement(it) })
  }

  @ApiResponses(
      ApiResponse(responseCode = "200", description = "Species name added."),
      ApiResponse(
          responseCode = "409", description = "The species already has the requested name."))
  @ApiResponse404("The species does not exist.")
  @Operation(summary = "Adds a new name for an existing species.")
  @PostMapping("/names")
  fun speciesNameCreate(
      @RequestBody payload: SpeciesNameRequestPayload
  ): SpeciesNameCreateResponsePayload {
    try {
      val speciesNameId = speciesStore.createSpeciesName(payload.toRow())
      return SpeciesNameCreateResponsePayload(speciesNameId)
    } catch (e: DataIntegrityViolationException) {
      throw DuplicateNameException("The species already has the requested name.")
    } catch (e: SpeciesNotFoundException) {
      throw NotFoundException("The species does not exist.")
    }
  }

  @ApiResponse(responseCode = "200", description = "Species names retrieved.")
  @ApiResponse404("The species does not exist.")
  @GetMapping("/{speciesId}/names")
  fun speciesNamesList(@PathVariable speciesId: Long): SpeciesNamesListResponsePayload {
    try {
      val names = speciesStore.listAllSpeciesNames(SpeciesId(speciesId))
      return SpeciesNamesListResponsePayload(names.map { SpeciesNamesResponseElement(it) })
    } catch (e: SpeciesNotFoundException) {
      throw NotFoundException("The species does not exist.")
    }
  }

  @ApiResponse(responseCode = "200", description = "Species name retrieved.")
  @ApiResponse404
  @GetMapping("/names/{speciesNameId}")
  @Operation(description = "Gets information about a single species name.")
  fun speciesNameGet(@PathVariable speciesNameId: Long): SpeciesNameGetResponsePayload {
    val row =
        speciesNamesDao.fetchOneById(SpeciesNameId(speciesNameId))
            ?: throw NotFoundException("Species name not found")
    return SpeciesNameGetResponsePayload(SpeciesNamesResponseElement(row))
  }

  @ApiResponses(
      ApiResponse(responseCode = "200", description = "Species name deleted."),
      ApiResponse(
          responseCode = "409", description = "Cannot delete the primary name of a species."))
  @ApiResponse404
  @DeleteMapping("/names/{speciesNameId}")
  @Operation(description = "Deletes one of the secondary names of a species.")
  fun speciesNameDelete(@PathVariable speciesNameId: Long): SimpleSuccessResponsePayload {
    speciesStore.deleteSpeciesName(SpeciesNameId(speciesNameId))
    return SimpleSuccessResponsePayload()
  }

  @Operation(description = "Updates one of the names of a species.")
  @PutMapping("/names/{speciesNameId}")
  fun speciesNameUpdate(
      @PathVariable speciesNameId: Long,
      @RequestBody payload: SpeciesNameRequestPayload
  ): SimpleSuccessResponsePayload {
    speciesStore.updateSpeciesName(payload.toRow().copy(id = SpeciesNameId(speciesNameId)))
    return SimpleSuccessResponsePayload()
  }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class SpeciesResponseElement(
    val conservationStatus: String?,
    val familyId: FamilyId?,
    val id: SpeciesId,
    @Schema(description = "True if name is the scientific name for the species.")
    val isScientific: Boolean,
    val name: String,
    val plantForm: PlantForm?,
    val rare: RareType?,
    @Schema(
        description = "Taxonomic serial number from ITIS database.",
        externalDocs =
            ExternalDocumentation(
                url = "https://en.wikipedia.org/wiki/Integrated_Taxonomic_Information_System"))
    val tsn: String?,
) {
  constructor(
      row: SpeciesRow
  ) : this(
      conservationStatus = row.conservationStatusId,
      familyId = row.familyId,
      id = row.id!!,
      isScientific = row.isScientific!!,
      plantForm = row.plantFormId,
      rare = row.rareTypeId,
      name = row.name!!,
      tsn = row.tsn,
  )
}

data class SpeciesRequestPayload(
    val conservationStatus: String?,
    val familyId: FamilyId?,
    @Schema(description = "True if name is the scientific name for the species.")
    val isScientific: Boolean?,
    val name: String,
    val plantForm: PlantForm?,
    val rare: RareType?,
    @Schema(
        description = "Taxonomic serial number from ITIS database.",
        externalDocs =
            ExternalDocumentation(
                url = "https://en.wikipedia.org/wiki/Integrated_Taxonomic_Information_System"))
    val tsn: String?,
) {
  fun toRow(id: SpeciesId? = null) =
      SpeciesRow(
          conservationStatusId = conservationStatus,
          familyId = familyId,
          id = id,
          isScientific = isScientific == true,
          name = name,
          plantFormId = plantForm,
          rareTypeId = rare,
          tsn = tsn,
      )
}

data class SpeciesNamesResponseElement(
    val id: SpeciesNameId,
    @Schema(description = "True if name is the scientific name for the species.")
    val isScientific: Boolean,
    val name: String,
    val speciesId: SpeciesId,
) {
  constructor(
      row: SpeciesNamesRow
  ) : this(
      id = row.id!!,
      isScientific = row.isScientific == true,
      name = row.name!!,
      speciesId = row.speciesId!!,
  )
}

data class SpeciesNameRequestPayload(
    @Schema(description = "True if name is a scientific name for the species.")
    val isScientific: Boolean?,
    val locale: String?,
    val name: String,
    val speciesId: SpeciesId,
) {
  fun toRow() =
      SpeciesNamesRow(
          speciesId = speciesId, name = name, isScientific = isScientific == true, locale = locale)
}

data class SpeciesCreateResponsePayload(val id: SpeciesId) : SuccessResponsePayload

data class SpeciesNameCreateResponsePayload(val id: SpeciesNameId) : SuccessResponsePayload

data class SpeciesGetResponsePayload(val species: SpeciesResponseElement) : SuccessResponsePayload

data class SpeciesNameGetResponsePayload(val speciesName: SpeciesNamesResponseElement) :
    SuccessResponsePayload

data class SpeciesListResponsePayload(val species: List<SpeciesResponseElement>) :
    SuccessResponsePayload

data class SpeciesNamesListResponsePayload(val speciesNames: List<SpeciesNamesResponseElement>) :
    SuccessResponsePayload
