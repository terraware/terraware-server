package com.terraformation.backend.gis.api

import com.terraformation.backend.api.CustomerEndpoint
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.CountryNotFoundException
import com.terraformation.backend.gis.CountryDetector
import com.terraformation.backend.util.toMultiPolygon
import io.swagger.v3.oas.annotations.Operation
import org.locationtech.jts.geom.MultiPolygon
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@CustomerEndpoint
@RestController
@RequestMapping("/api/v1/countries")
class CountriesController(
    private val countryDetector: CountryDetector,
) {

  @GetMapping("/{countryCode}/boundary")
  @Operation(summary = "Gets boundary of one country.")
  fun getBorder(@PathVariable countryCode: String): GetCountryBorderResponsePayload {
    val border =
        countryDetector.getCountryBorder(countryCode) ?: throw CountryNotFoundException(countryCode)
    return GetCountryBorderResponsePayload(border.toMultiPolygon())
  }
}

data class GetCountryBorderResponsePayload(val border: MultiPolygon) : SuccessResponsePayload
