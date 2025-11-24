package com.terraformation.backend.i18n

import com.terraformation.backend.assertSetEquals
import java.time.ZoneId
import java.util.Locale
import java.util.stream.Stream
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.util.StringUtils
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue

internal class TimeZonesTest {
  private val objectMapper = jacksonObjectMapper()
  private val timeZones = TimeZones(Messages(), objectMapper)

  @MethodSource("locales")
  @ParameterizedTest
  fun `time zone names are unique`(locale: Locale) {
    val timeZoneNames = timeZones.getTimeZoneNames(locale).values
    val duplicateNames = timeZoneNames.groupBy { it }.filterValues { it.size > 1 }.keys

    assertSetEquals(emptySet<String>(), duplicateNames)
  }

  @MethodSource("locales")
  @ParameterizedTest
  fun `list of time zone IDs is the same in all locales`(locale: Locale) {
    val englishTimeZones = timeZones.getTimeZoneNames(Locale.ENGLISH).map { it.key.id }.sorted()
    val localizedTimeZones = timeZones.getTimeZoneNames(locale).map { it.key.id }.sorted()

    assertEquals(englishTimeZones, localizedTimeZones)
  }

  @MethodSource("locales")
  @ParameterizedTest
  fun `time zone names are not missing components`(locale: Locale) {
    // This relies on the fact that we currently render zone+city with a separator of " - ", so
    // if one of the components is missing, the localized name will begin or end with " - ".
    val localizedTimeZones = timeZones.getTimeZoneNames(locale).map { it.key.id }.sorted()

    assertEquals(localizedTimeZones.map { it.trim() }, localizedTimeZones)
  }

  @MethodSource("nonEnglishLocales")
  @ParameterizedTest
  fun `time zone names are not in English`(locale: Locale) {
    val englishTimeZones = timeZones.getTimeZoneNames(Locale.ENGLISH)
    val localizedTimeZones = timeZones.getTimeZoneNames(locale)

    val localizedTimeZonesWithoutTranslations =
        englishTimeZones
            .mapNotNull { (zoneId, englishName) ->
              if (localizedTimeZones[zoneId] == englishName) {
                zoneId
              } else {
                null
              }
            }
            .toSet()

    assertSetEquals(emptySet<ZoneId>(), localizedTimeZonesWithoutTranslations)
  }

  @MethodSource("browsers")
  @ParameterizedTest
  fun `browser compatibility`(browserName: String) {
    val filename =
        "/i18n/" + browserName.replace(' ', '_').replace("(", "").replace(")", "") + ".json"
    val browserZones =
        javaClass.getResourceAsStream(filename)!!.use { inputStream ->
          objectMapper.readValue<List<String>>(inputStream).toSet()
        }
    val unsupportedZones =
        timeZones
            .getTimeZoneNames(Locale.ENGLISH)
            .keys
            .minus(TimeZones.UTC)
            .map { it.id }
            .toSet()
            .minus(browserZones)

    assertSetEquals(emptySet<String>(), unsupportedZones)
  }

  @Test
  fun `includes city name if there are multiple cities in the same zone`() {
    val names = timeZones.getTimeZoneNames(Locale.ENGLISH)

    assertEquals("Pacific Time - Los Angeles", names[ZoneId.of("America/Los_Angeles")])
    assertEquals("Pacific Time - Vancouver", names[ZoneId.of("America/Vancouver")])
  }

  @Test
  fun `omits city name if zone name is unique without it`() {
    assertEquals(
        "Pitcairn Time",
        timeZones.getTimeZoneNames(Locale.ENGLISH)[ZoneId.of("Pacific/Pitcairn")],
    )
  }

  @Test
  fun `removes redundant zone IDs`() {
    // This is an alias for Europe/London
    val jerseyTime = ZoneId.of("Europe/Jersey")
    assertFalse(
        jerseyTime in timeZones.getTimeZoneNames(Locale.ENGLISH),
        "Should have removed $jerseyTime",
    )
  }

  @Test
  fun `differentiates between DST and non-DST time zones`() {
    val names = timeZones.getTimeZoneNames(Locale.ENGLISH)

    // Boise changes its clocks twice a year
    assertEquals("Mountain Time - Boise", names[ZoneId.of("America/Boise")])
    // Phoenix doesn't
    assertEquals("Mountain Standard Time - Phoenix", names[ZoneId.of("America/Phoenix")])
  }

  companion object {
    /**
     * Locales whose time zone renderings should be tested. This must always include all our
     * supported languages, but may also include other languages to improve our confidence that the
     * time zone rendering code is sufficiently general.
     */
    @JvmStatic
    fun locales(): Stream<Locale> =
        Stream.of(
                "de", // German
                "en", // US English
                "en_GB", // British English - nearly but not completely identical to US English
                "es", // Spanish
                "fr", // French
                "it", // Italian
                "ja", // Japanese
                "ko", // Korean
                "pt_BR", // Brazilian Portuguese
                "pt_PT", // Portuguese Portuguese
                "ru", // Russian
                "th", // Thai
                "uk", // Ukrainian
                "vi", // Vietnamese
                "zh_CN", // Simplified Chinese
                "zh_TW", // Traditional Chinese
            )
            .map { StringUtils.parseLocaleString(it) }

    @JvmStatic fun nonEnglishLocales(): Stream<Locale> = locales().filter { it.language != "en" }

    @JvmStatic
    fun browsers(): Stream<String> =
        Stream.of(
            "Chrome 108.0.5359.124 (OS X 12.6)",
            "Edge 108.0.1462.54 (Windows 11)",
            "Safari 17614.1.25.9.10 (OS X 12.6)",
        )
  }
}
