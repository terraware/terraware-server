package com.terraformation.backend.i18n.api

import com.terraformation.backend.api.CustomerEndpoint
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.i18n.TimeZones
import com.terraformation.backend.i18n.currentLocale
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import java.time.ZoneId
import java.util.Locale
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@CustomerEndpoint
@RequestMapping("/api/v1/i18n/timeZones")
@RestController
class TimeZonesController(private val timeZones: TimeZones) {
  @GetMapping
  @Operation(summary = "Gets a list of supported time zones and their names.")
  fun listTimeZoneNames(
      @RequestParam("locale")
      @Schema(
          description =
              "Language code and optional country code suffix. If not specified, the preferred " +
                  "locale from the Accept-Language header is used if supported; otherwise US " +
                  "English is the default.",
          example = "zh-CN",
      )
      localeName: String?
  ): ListTimeZoneNamesResponsePayload {
    val locale = localeName?.let { Locale.forLanguageTag(it) } ?: currentLocale()
    val payloads = timeZones.getTimeZoneNames(locale).map { TimeZonePayload(it.key, it.value) }
    return ListTimeZoneNamesResponsePayload(payloads)
  }
}

data class TimeZonePayload(
    val id: ZoneId,
    @Schema(
        description =
            "Long name of time zone, possibly including a city name. This name is guaranteed to " +
                "be unique across all zones.",
        example = "Central European Time - Berlin",
    )
    val longName: String,
)

data class ListTimeZoneNamesResponsePayload(
    val timeZones: List<TimeZonePayload>,
) : SuccessResponsePayload
