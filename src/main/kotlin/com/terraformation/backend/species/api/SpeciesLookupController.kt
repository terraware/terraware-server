package com.terraformation.backend.species.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.CustomerEndpoint
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.default_schema.ConservationCategory
import com.terraformation.backend.db.default_schema.SpeciesProblemType
import com.terraformation.backend.db.default_schema.tables.pojos.SpeciesProblemsRow
import com.terraformation.backend.species.db.GbifStore
import com.terraformation.backend.species.model.GbifTaxonModel
import com.terraformation.backend.species.model.GbifVernacularNameModel
import io.swagger.v3.oas.annotations.ExternalDocumentation
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import jakarta.validation.constraints.Size
import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.NotFoundException
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@CustomerEndpoint
@RestController
@RequestMapping("/api/v1/species/lookup")
class SpeciesLookupController(private val gbifStore: GbifStore) {
  @GetMapping("/names")
  @Operation(
      description =
          "Gets a list of known scientific names whose words begin with particular letters."
  )
  fun listSpeciesNames(
      @RequestParam
      @Schema(
          description =
              "Space-delimited list of word prefixes to search for. Non-alphabetic characters " +
                  "are ignored, and matches are case-insensitive. The order of prefixes is " +
                  "significant; \"ag sc\" will match \"Aglaonema schottianum\" but won't match " +
                  "\"Scabiosa agrestis\".",
          example = "ag sc",
      )
      @Size(min = 2, max = 100)
      search: String,
      @RequestParam("maxResults", defaultValue = "10")
      @Schema(description = "Maximum number of results to return.", minimum = "1", maximum = "50")
      maxResults: Int,
  ): SpeciesLookupNamesResponsePayload {
    if (search.trim().length !in 2..100) {
      throw BadRequestException("Search term must be between 2 and 100 characters")
    }

    val cappedMaxResults = maxResults.coerceIn(1, 50)
    val prefixes = search.split(' ')

    val names =
        gbifStore.findNamesByWordPrefixes(prefixes, maxResults = cappedMaxResults + 1).mapNotNull {
          it.name
        }
    val partial = names.size > cappedMaxResults

    return SpeciesLookupNamesResponsePayload(names.take(cappedMaxResults), partial)
  }

  @ApiResponse(responseCode = "200")
  @ApiResponse404("The scientific name was not found in the server's taxonomic database.")
  @GetMapping("/details")
  @Operation(summary = "Gets more information about a species with a particular scientific name.")
  fun getSpeciesDetails(
      @RequestParam
      @Schema(description = "Exact scientific name to look up. This name is case-sensitive.")
      scientificName: String,
      @RequestParam
      @Schema(
          description =
              "If specified, only return common names in this language or whose language is " +
                  "unknown. Names with unknown languages are always included. This is a " +
                  "two-letter ISO 639-1 language code.",
          example = "en",
      )
      language: String? = null,
  ): SpeciesLookupDetailsResponsePayload {
    val model =
        gbifStore.fetchOneByScientificName(scientificName, language)
            ?: throw NotFoundException("$scientificName not found")
    val problem = gbifStore.checkScientificName(scientificName)
    return SpeciesLookupDetailsResponsePayload(model, problem)
  }
}

data class SpeciesLookupNamesResponsePayload(
    val names: List<String>,
    @Schema(
        description =
            "True if there were more matching names than could be included in the response."
    )
    val partial: Boolean,
) : SuccessResponsePayload

@JsonInclude(JsonInclude.Include.NON_NULL)
data class SpeciesLookupCommonNamePayload(
    val name: String,
    @Schema(
        description =
            "ISO 639-1 two-letter language code indicating the name's language. Some common " +
                "names in the server's taxonomic database are not tagged with languages; this " +
                "value will not be present for those names."
    )
    val language: String?,
) {
  constructor(model: GbifVernacularNameModel) : this(model.name, model.language)
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class SpeciesLookupDetailsResponsePayload(
    val scientificName: String,
    @ArraySchema(
        arraySchema = Schema(description = "List of known common names for the species, if any.")
    )
    val commonNames: List<SpeciesLookupCommonNamePayload>?,
    @Schema(
        description = "IUCN Red List conservation category code.",
        externalDocs =
            ExternalDocumentation(url = "https://en.wikipedia.org/wiki/IUCN_Red_List#Categories"),
    )
    val conservationCategory: ConservationCategory?,
    val familyName: String,
    @Schema(
        description =
            "If this is not the accepted name for the species, the type of problem the name has. " +
                "Currently, this will always be \"Name Is Synonym\"."
    )
    val problemType: SpeciesProblemType?,
    @Schema(
        description =
            "If this is not the accepted name for the species, the name to suggest as an " +
                "alternative."
    )
    val suggestedScientificName: String?,
) {
  constructor(
      model: GbifTaxonModel,
      problem: SpeciesProblemsRow?,
  ) : this(
      model.scientificName,
      model.vernacularNames.map { SpeciesLookupCommonNamePayload(it) }.ifEmpty { null },
      model.conservationCategory,
      model.familyName,
      problem?.typeId,
      problem?.suggestedValue,
  )
}
