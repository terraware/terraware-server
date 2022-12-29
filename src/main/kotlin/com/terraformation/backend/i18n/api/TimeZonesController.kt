package com.terraformation.backend.i18n.api

import com.terraformation.backend.api.CustomerEndpoint
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.i18n.TimeZones
import java.time.ZoneId
import java.util.Locale
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@CustomerEndpoint
@RequestMapping("/api/v1/i18n/tz")
@RestController
class TimeZonesController {
  @GetMapping("/names")
  fun listTimeZoneNames(
      @RequestParam("locale") localeName: String?
  ): ListTimeZoneNamesResponsePayload {
    val locale = localeName?.let { Locale.forLanguageTag(it.replace('_', '-')) } ?: Locale.US
    return ListTimeZoneNamesResponsePayload(TimeZones.getTimeZoneNames(locale))
  }
}

data class ListTimeZoneNamesResponsePayload(val timeZones: Map<ZoneId, String>) :
    SuccessResponsePayload
