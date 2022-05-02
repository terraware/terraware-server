package com.terraformation.backend.species.api

import com.terraformation.backend.api.CustomerEndpoint
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.species.db.GbifStore
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import javax.ws.rs.BadRequestException
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
          "Gets a list of known scientific names whose words begin with particular letters.")
  fun listSpeciesNames(
      @RequestParam("search")
      @Schema(
          description =
              "Space-delimited list of word prefixes to search for. Non-alphabetic characters " +
                  "are ignored, and matches are case-insensitive. The order of prefixes is " +
                  "significant; \"ag sc\" will match \"Aglaonema schottianum\" but won't match " +
                  "\"Scabiosa agrestis\".",
          example = "ag sc",
          minLength = 2,
          maxLength = 100)
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
}

data class SpeciesLookupNamesResponsePayload(
    val names: List<String>,
    @Schema(
        description =
            "True if there were more matching names than could be included in the response.")
    val partial: Boolean
) : SuccessResponsePayload
